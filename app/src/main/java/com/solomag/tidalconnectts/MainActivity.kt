package com.solomag.tidalconnectts

import android.app.Activity
import android.content.SharedPreferences
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.WindowInsets
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlin.concurrent.thread

/**
 * Settings screen: the PC(s) to look for receivers on. Saved into the framework's remote
 * preferences, so this needs the module enabled in Vector/LSPosed; TIDAL reads it on its next start.
 */
class MainActivity : Activity() {

    private var prefs: SharedPreferences? = null
    private lateinit var hostsField: EditText
    private lateinit var save: Button
    private lateinit var test: Button
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.hide()
        val pad = dp(16)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        column.addView(text(getString(R.string.app_name), 22f))
        column.addView(text(getString(R.string.hosts_title), 18f))
        column.addView(text(getString(R.string.hosts_help), 14f))
        hostsField = EditText(this).apply {
            hint = getString(R.string.hosts_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            isEnabled = false
        }
        column.addView(hostsField, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        save = Button(this).apply { text = getString(R.string.save); isEnabled = false; setOnClickListener { save() } }
        test = Button(this).apply { text = getString(R.string.test); setOnClickListener { test() } }
        buttons.addView(save)
        buttons.addView(test)
        column.addView(buttons)
        status = text(getString(R.string.service_waiting), 14f)
        column.addView(status)
        column.addView(text(getString(R.string.notes), 13f))
        val scroll = ScrollView(this).apply { addView(column) }
        // Edge-to-edge (targetSdk 35+): keep the content clear of the status, navigation and IME bars.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)

        XposedServiceHelper.registerListener(object : XposedServiceHelper.OnServiceListener {
            override fun onServiceBind(service: XposedService) {
                prefs = runCatching { service.getRemotePreferences(Settings.GROUP) }.getOrNull()
                runOnUiThread {
                    if (prefs == null) {
                        status.text = getString(R.string.service_missing)
                        return@runOnUiThread
                    }
                    hostsField.setText(prefs?.getString(Settings.HOSTS, "").orEmpty())
                    hostsField.isEnabled = true
                    save.isEnabled = true
                    status.text = getString(R.string.service_ready)
                }
            }

            override fun onServiceDied(service: XposedService) {
                prefs = null
                runOnUiThread {
                    hostsField.isEnabled = false
                    save.isEnabled = false
                    status.text = getString(R.string.service_missing)
                }
            }
        })
    }

    private fun save() {
        val value = hostsField.text.toString().trim()
        if (value.isNotEmpty() && Probe.parseHosts(value).isEmpty()) {
            status.text = getString(R.string.bad_hosts)
            return
        }
        prefs?.edit()?.putString(Settings.HOSTS, value)?.apply()
        status.text = getString(R.string.saved)
    }

    /** Asks the hosts in the field from this app, the way the hook will from TIDAL. */
    private fun test() {
        val hosts = Probe.parseHosts(hostsField.text.toString())
        if (hosts.isEmpty()) {
            status.text = getString(R.string.bad_hosts)
            return
        }
        status.text = getString(R.string.testing)
        test.isEnabled = false
        thread(isDaemon = true) {
            val notes = StringBuilder()
            val lines = hosts.flatMap { host ->
                val found = Probe.services(host) { notes.append(it).append('\n') }
                if (found.isEmpty()) listOf(getString(R.string.nothing_at, "${host.host}:${host.firstPort}–${host.firstPort + Probe.PORTS_TRIED - 1}"))
                else found.map { "✓ ${it.txt["fn"]} — ${it.address.hostAddress}:${it.port}" }
            }
            runOnUiThread {
                status.text = (lines.joinToString("\n") + "\n" + notes).trim()
                test.isEnabled = true
            }
        }
    }

    private fun text(s: String, sp: Float) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
