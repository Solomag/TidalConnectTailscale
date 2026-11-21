package com.solomag.tidalconnectts

import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.os.Handler
import android.net.nsd.NsdServiceInfo
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import kotlin.concurrent.thread

/**
 * TIDAL Android finds Connect receivers with NsdManager (mDNS). Android does no mDNS on mobile
 * networks, nor on a VPN (Tailscale) whose underlying network is mobile, so a PC reachable over
 * Tailscale never shows up. This module adds the receivers found on the configured hosts to
 * TIDAL's discovery itself (see [Probe] for how they are found):
 *
 *  - discoverServices("_tidalconnect._tcp") → after the real call, and again every [REPROBE_MS]
 *    while it runs, the hosts are probed; receivers found are reported with onServiceFound, ones
 *    gone with onServiceLost;
 *  - registerServiceInfoCallback / resolveService for such a receiver are answered here with its
 *    address, port and TXT record instead of going to the system.
 *
 * A receiver keeps its own mDNS service name, so on the local network TIDAL sees one device, not two;
 * it is then reached over Tailscale too. The real discovery keeps running for other devices.
 */
class ModuleMain : XposedModule() {

    /** The settings screen's group, read live: a change applies on the next probe. */
    private var prefs: SharedPreferences? = null

    /** Hosts from the settings screen, else the build's default (debug builds only). */
    private fun hosts(): List<Probe.Host> {
        val saved = runCatching { prefs?.getString(Settings.HOSTS, null) }.getOrNull()
        return Probe.parseHosts(saved?.takeIf { it.isNotBlank() } ?: BuildConfig.HOSTS)
    }

    /** Receivers last found, by service name: what the resolve hooks answer with. */
    private val found = ConcurrentHashMap<String, Probe.Service>()

    /** Discovery listeners we report to (overloads call each other: the first call wins). */
    private val listeners: MutableMap<Any, Boolean> = Collections.synchronizedMap(WeakHashMap())

    /** Our ServiceInfoCallbacks → the executor TIDAL gave with them. */
    private val ourCallbacks: MutableMap<Any, Executor> = Collections.synchronizedMap(WeakHashMap())

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        log(Log.INFO, TAG, "loaded in ${param.processName}")
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (!param.isFirstPackage || param.packageName != TIDAL) return
        prefs = runCatching { getRemotePreferences(Settings.GROUP) }
            .onFailure { log(Log.WARN, TAG, "no remote preferences", it) }.getOrNull()
        val hosts = hosts()
        if (hosts.isEmpty()) log(Log.WARN, TAG, "no PC set: open the module's app and enter the PC's Tailscale address")
        else log(Log.INFO, TAG, "hosts: $hosts")
        var hooked = 0
        for (m in NsdManager::class.java.declaredMethods) {
            val hooker: Hooker = when {
                m.name == "discoverServices" && m.parameterTypes.any { it == NsdManager.DiscoveryListener::class.java } -> discoverHooker
                m.name == "stopServiceDiscovery" -> stopHooker
                m.name == "registerServiceInfoCallback" -> registerInfoHooker
                m.name == "unregisterServiceInfoCallback" -> unregisterInfoHooker
                m.name == "resolveService" -> resolveHooker
                else -> continue
            }
            runCatching { hook(m).intercept(hooker) }
                .onSuccess { hooked++ }
                .onFailure { log(Log.ERROR, TAG, "hook $m failed", it) }
        }
        log(Log.INFO, TAG, "hooked $hooked NsdManager methods")
        hookWifiGate(param.classLoader)
    }

    /**
     * TIDAL's discovery lives in Sony's SDK (ScMediaRouteProvider), which only searches on Wi-Fi:
     * it starts when isWifiAvailable() (active network has the WIFI transport) and follows a
     * NetworkCallback for WIFI networks, whose onLost stops the search for good. So on mobile data
     * the search never starts and our receivers are never reported.
     *  - isWifiAvailable() → true whenever there is an active network;
     *  - its WIFI NetworkCallback → registered as a default-network callback instead: it starts the
     *    search on whatever network is in use and restarts it when the network changes.
     */
    private fun hookWifiGate(classLoader: ClassLoader) {
        val provider = runCatching { classLoader.loadClass(SONY_PROVIDER) }.getOrElse {
            log(Log.WARN, TAG, "$SONY_PROVIDER not found: discovery stays Wi-Fi only")
            return
        }
        runCatching {
            val m = provider.getDeclaredMethod("isWifiAvailable")
            hook(m).intercept { chain ->
                val wifi = chain.proceed()
                if (wifi == true) return@intercept wifi
                val cm = (chain.thisObject as Any).let { connectivity(it) }
                if (cm?.activeNetwork != null) java.lang.Boolean.TRUE else wifi
            }
        }.onSuccess { log(Log.INFO, TAG, "hooked isWifiAvailable") }
            .onFailure { log(Log.ERROR, TAG, "hook isWifiAvailable failed", it) }

        for (m in ConnectivityManager::class.java.declaredMethods) {
            if (m.name != "registerNetworkCallback") continue
            val p = m.parameterTypes
            if (p.size !in 2..3 || p[0] != NetworkRequest::class.java || p[1] != ConnectivityManager.NetworkCallback::class.java) continue
            if (p.size == 3 && p[2] != Handler::class.java) continue
            runCatching {
                hook(m).intercept { chain ->
                    val cm = chain.thisObject as ConnectivityManager
                    val callback = chain.args[1] as ConnectivityManager.NetworkCallback?
                    if (callback == null || !callback.javaClass.name.startsWith("$SONY_PROVIDER$")) return@intercept chain.proceed()
                    val handler = chain.args.getOrNull(2) as Handler?
                    if (handler != null) cm.registerDefaultNetworkCallback(callback, handler)
                    else cm.registerDefaultNetworkCallback(callback)
                    log(Log.INFO, TAG, "Sony's Wi-Fi callback follows the default network instead")
                    null
                }
            }.onFailure { log(Log.ERROR, TAG, "hook $m failed", it) }
        }
    }

    private fun connectivity(provider: Any): ConnectivityManager? = runCatching {
        provider.javaClass.getDeclaredField("mConnectivityManager").apply { isAccessible = true }.get(provider) as ConnectivityManager?
    }.getOrNull()

    private val discoverHooker = Hooker { chain ->
        val result = chain.proceed()
        runCatching { onDiscover(chain) }.onFailure { log(Log.ERROR, TAG, "discover", it) }
        result
    }

    private fun onDiscover(chain: XposedInterface.Chain) {
        val args = chain.args
        val listener = args.firstOrNull { it is NsdManager.DiscoveryListener } as NsdManager.DiscoveryListener? ?: return
        val type = args.firstNotNullOfOrNull { serviceTypeOf(it) } ?: return
        if (baseType(type) != TIDAL_TYPE) return
        if (listeners.put(listener, true) != null) return
        val executor = args.firstOrNull { it is Executor } as Executor?
        thread(name = "tidalconnect-ts", isDaemon = true) { report(listener, executor) }
    }

    /** Probes the hosts until the discovery stops, telling the listener what came and went. */
    private fun report(listener: NsdManager.DiscoveryListener, executor: Executor?) {
        var shown = emptySet<String>()
        while (listeners[listener] == true) {
            val services = hosts().flatMap { Probe.services(it) { msg -> log(Log.INFO, TAG, msg) } }
            if (listeners[listener] != true) break
            services.forEach { found[it.name] = it }
            val names = services.map { it.name }.toSet()
            for (s in services) if (s.name !in shown) {
                val info = NsdServiceInfo().apply { serviceName = s.name; serviceType = "$TIDAL_TYPE." }
                run(executor) { listener.onServiceFound(info) }
                log(Log.INFO, TAG, "shown ${s.txt["fn"]} (${s.name}) at ${s.address.hostAddress}:${s.port}")
            }
            for (name in shown - names) {
                val info = NsdServiceInfo().apply { serviceName = name; serviceType = "$TIDAL_TYPE." }
                run(executor) { listener.onServiceLost(info) }
                log(Log.INFO, TAG, "gone $name")
            }
            shown = names
            Thread.sleep(REPROBE_MS)
        }
    }

    private val stopHooker = Hooker { chain ->
        chain.args.firstOrNull()?.let { listeners.remove(it) }
        chain.proceed()
    }

    private val registerInfoHooker = Hooker { chain ->
        val info = chain.args.firstOrNull { it is NsdServiceInfo } as NsdServiceInfo?
        val service = info?.let { found[it.serviceName] } ?: return@Hooker chain.proceed()
        val callback = chain.args.firstOrNull { it is NsdManager.ServiceInfoCallback } as NsdManager.ServiceInfoCallback?
            ?: return@Hooker chain.proceed()
        val executor = chain.args.firstOrNull { it is Executor } as Executor? ?: Executor { it.run() }
        ourCallbacks[callback] = executor
        executor.execute { callback.onServiceUpdated(service.toInfo("")) }
        log(Log.INFO, TAG, "resolved ${service.name} → ${service.address.hostAddress}:${service.port}")
        null
    }

    private val unregisterInfoHooker = Hooker { chain ->
        val callback = chain.args.firstOrNull() as NsdManager.ServiceInfoCallback?
        val executor = callback?.let { ourCallbacks.remove(it) } ?: return@Hooker chain.proceed()
        executor.execute { callback.onServiceInfoCallbackUnregistered() }
        null
    }

    private val resolveHooker = Hooker { chain ->
        val info = chain.args.firstOrNull { it is NsdServiceInfo } as NsdServiceInfo?
        val service = info?.let { found[it.serviceName] } ?: return@Hooker chain.proceed()
        val listener = chain.args.firstOrNull { it is NsdManager.ResolveListener } as NsdManager.ResolveListener?
            ?: return@Hooker chain.proceed()
        val executor = chain.args.firstOrNull { it is Executor } as Executor?
        run(executor) { listener.onServiceResolved(service.toInfo(".")) }
        null
    }

    private fun run(executor: Executor?, block: () -> Unit) {
        if (executor != null) executor.execute(block) else block()
    }

    companion object {
        const val TAG = "TidalConnectTs"
        const val TIDAL = "com.aspiro.tidal"
        const val TIDAL_TYPE = "_tidalconnect._tcp"
        const val REPROBE_MS = 30_000L
        const val SONY_PROVIDER = "com.sony.sonycast.sdk.ScMediaRouteProvider"

        /** "_tidalconnect._tcp.local." / "._tidalconnect._tcp" → "_tidalconnect._tcp" */
        fun baseType(type: String) = type.trim('.').removeSuffix(".local").lowercase()

        /** The service type from a String argument or an API 35 DiscoveryRequest. */
        fun serviceTypeOf(arg: Any?): String? = when {
            arg is String && arg.contains("._") -> arg
            arg?.javaClass?.name == "android.net.nsd.DiscoveryRequest" ->
                runCatching { arg.javaClass.getMethod("getServiceType").invoke(arg) as String? }.getOrNull()
            else -> null
        }
    }
}
