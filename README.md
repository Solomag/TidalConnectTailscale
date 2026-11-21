# TIDAL Connect over Tailscale

A Vector/LSPosed module for TIDAL Android (`com.aspiro.tidal`). It adds a TIDAL Connect receiver you
reach over Tailscale, such as [TidaLuna](https://github.com/Inrixia/TidaLuna)'s DesktopConnectNative
on your PC, to TIDAL's device list. It works on mobile data too.

## Why
TIDAL finds Connect devices with Android's NsdManager (mDNS). Android does no mDNS on mobile
networks. A VPN whose traffic goes over mobile data, Tailscale included, gets the mobile network's
CELLULAR transport, so Android does no mDNS there either. The PC is reachable over Tailscale but
never shows up.

## How
You give the module only the PC's address. The port and the device's name come from the receiver:
DesktopConnectNative takes the first free port from its "Port" setting (9000 by default) and answers
`GET /tidalconnect/service` on it with its mDNS record (`name`, `type`, `port`, TXT `mn/id/fn/ca/ve`).
The module asks ports 9000–9019 on each host. Every answer with `mn` = `TidaLuna` is a device, so
several TIDAL installs on one PC show up too.

Inside TIDAL the module hooks NsdManager:
- `discoverServices("_tidalconnect._tcp")`: while discovery runs, the hosts are asked every 30 s;
  receivers that answer are reported as found, ones that went away as lost;
- `registerServiceInfoCallback` / `resolveService` for those receivers: answered with the address,
  port and TXT record, without going to the system.

A receiver without that endpoint (DesktopConnectNative before it): if the first port accepts a TCP
connection, one device is shown there under the name `TidaLuna (<host>)`.

TIDAL's own search (Sony's SDK, `com.sony.sonycast.sdk.ScMediaRouteProvider`) runs only on Wi-Fi:
it checks that the active network is Wi-Fi and stops when Wi-Fi goes away. The module lets it run on
any network: `isWifiAvailable()` answers true when there is a network, and its Wi-Fi network callback
follows the default network instead.

Real discovery keeps running, so devices on your local network show as before.

## Requirements
- Android 14+, root, a Vector or LSPosed framework with the modern libxposed API (101+).
- Tailscale (or any VPN that reaches the PC) on the phone and the PC.
- On the PC: TIDAL Desktop with [TidaLuna](https://github.com/Inrixia/TidaLuna) and its
  DesktopConnectNative plugin 1.0.6 or newer.
- Tested with TIDAL Android 2.220.1. A TIDAL update can move the parts the module hooks; then
  `adb logcat -s TidalConnectTs` says what was not found.

## Setup
1. Install the APK from [Releases](../../releases) (or from the LSPosed module repository).
2. Enable the module in Vector/LSPosed with TIDAL (`com.aspiro.tidal`) in its scope.
3. Open the module's app, enter the PC's Tailscale IP or MagicDNS name, tap **Test**, then **Save**.
   Several PCs: separate them with `;`. If the plugin's Port setting is not 9000, add `:port`.
4. Force-stop TIDAL and open it again. The PC appears in TIDAL's device list as
   "TidaLuna: <PC name>".
5. Allow the plugin's port (shown in its settings) through the PC's firewall on the Tailscale adapter.

With Vector's CLI instead of its app:
`su -c '/data/adb/lspd/cli modules enable io.github.solomag.tidalconnectts'` and
`su -c '/data/adb/lspd/cli scope set io.github.solomag.tidalconnectts com.aspiro.tidal/0'`.

## Build
`./gradlew assembleRelease` (JDK 17). Release signing reads `keystore.properties` (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`; not in git). Debug builds may take a default PC from
`ts.properties` (`tsHosts=...`) for when the settings screen is empty.

Logs: `adb logcat -s TidalConnectTs`.

## License
MIT
