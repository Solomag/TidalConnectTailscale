package com.solomag.tidalconnectts

import android.net.nsd.NsdServiceInfo
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Finds TIDAL Connect receivers on a host reachable over Tailscale.
 *
 * Contract with TidaLuna's DesktopConnectNative: its receiver takes the first free port from its
 * "Port" setting (9000 by default, [PORTS_TRIED] tried) and answers `GET /tidalconnect/service` on it
 * with its mDNS record as JSON: {"name","type","port","txt":{mn,id,fn,ca,ve}}. Each port in that
 * range is asked; every receiver that answers is a device (several TIDAL installs on one PC, too).
 *
 * Fallback for a receiver without that endpoint: if nothing answers but the first port accepts a TCP
 * connection, one device is shown there under a name of its own.
 */
object Probe {
    const val DEFAULT_PORT = 9000
    const val PORTS_TRIED = 20
    private const val TIMEOUT_MS = 2000
    private const val PATH = "/tidalconnect/service"

    data class Host(val host: String, val firstPort: Int)

    class Service(val name: String, val address: InetAddress, val port: Int, val txt: Map<String, String>) {
        /** typePrefix: "." for resolveService (historical "._type._tcp"), "" for info callbacks. */
        fun toInfo(typePrefix: String) = NsdServiceInfo().apply {
            serviceName = name
            serviceType = typePrefix + ModuleMain.TIDAL_TYPE
            hostAddresses = listOf(address)
            port = this@Service.port
            txt.forEach { (k, v) -> setAttribute(k, v) }
        }
    }

    /** "host[:firstPort];host2" — host is the PC's Tailscale IP or MagicDNS name. */
    fun parseHosts(spec: String): List<Host> = spec.split(';', ',').mapNotNull { entry ->
        val e = entry.trim()
        if (e.isEmpty()) return@mapNotNull null
        val port = e.substringAfterLast(':', "").toIntOrNull()
        if (port != null && !e.startsWith("[") && e.count { it == ':' } == 1) Host(e.substringBeforeLast(':'), port)
        else Host(e, DEFAULT_PORT)
    }

    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "tidalconnect-ts-probe").apply { isDaemon = true } }

    fun services(host: Host, log: (String) -> Unit): List<Service> {
        val address = runCatching { InetAddress.getByName(host.host) }.getOrElse {
            log("${host.host}: no address (${it.message})")
            return emptyList()
        }
        val ports = (host.firstPort until host.firstPort + PORTS_TRIED).filter { it in 1..65535 }
        val asked = ports.map { port -> pool.submit(Callable { ask(address, port) }) }
        val services = asked.mapNotNull { runCatching { it.get(TIMEOUT_MS * 3L, TimeUnit.MILLISECONDS) }.getOrNull() }
            .distinctBy { it.name }
        if (services.isNotEmpty()) return services
        if (!accepts(address, host.firstPort)) return emptyList()
        log("${host.host}:${host.firstPort} takes connections but has no $PATH, shown under a name of its own")
        return listOf(fallback(host, address))
    }

    /** The receiver's own record from `GET /tidalconnect/service`, or null. */
    private fun ask(address: InetAddress, port: Int): Service? = runCatching {
        val conn = URL("https", address.hostAddress, port, PATH).openConnection() as HttpsURLConnection
        try {
            // Only to read the record: TIDAL checks the receiver's certificate itself when it connects.
            conn.sslSocketFactory = trustAll.socketFactory
            conn.hostnameVerifier = HostnameVerifier { _, _ -> true }
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            if (conn.responseCode != 200) return@runCatching null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val txtJson = json.getJSONObject("txt")
            val txt = txtJson.keys().asSequence().associateWith { txtJson.getString(it) }
            // Something else can hold these ports (TidaLunar, TIDAL's own receiver): only a TidaLuna record counts.
            val name = json.getString("name")
            val servicePort = json.getInt("port")
            val valid = txt["mn"] == "TidaLuna" && !txt["id"].isNullOrEmpty() && !txt["fn"].isNullOrEmpty() &&
                name.isNotBlank() && servicePort in 1..65535 &&
                json.optString("type").trim('.').removeSuffix(".local") == ModuleMain.TIDAL_TYPE
            if (valid) Service(name, address, servicePort, txt) else null
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun accepts(address: InetAddress, port: Int) = runCatching {
        Socket().use { it.connect(InetSocketAddress(address, port), TIMEOUT_MS) }
    }.isSuccess

    private fun fallback(host: Host, address: InetAddress): Service {
        val key = "${host.host}:${host.firstPort}"
        return Service(
            "TidalConnect-ts-" + md5(key).take(8), address, host.firstPort,
            mapOf("mn" to "TidaLuna", "id" to md5("TidalConnectTailscale:$key"), "fn" to "TidaLuna (${host.host})", "ca" to "0", "ve" to "1"),
        )
    }

    private val trustAll: SSLContext by lazy {
        SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }), null)
        }
    }

    private fun md5(s: String) = MessageDigest.getInstance("MD5").digest(s.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
