package com.solomag.tidalconnectts

/**
 * The module's settings live in the framework's remote-preferences group [GROUP] (Vector/LSPosed
 * daemon). The settings screen ([MainActivity]) writes it through the libxposed service library;
 * inside TIDAL the hooks read the same group through XposedInterface.getRemotePreferences.
 */
object Settings {
    const val GROUP = "settings"

    /** "host[:firstPort];host2" — the PC's Tailscale IP or MagicDNS name. */
    const val HOSTS = "hosts"
}
