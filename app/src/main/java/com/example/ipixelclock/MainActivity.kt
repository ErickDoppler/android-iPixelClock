package com.example.ipixelclock

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.example.ipixelclock.ui.PanelPreviewView
import com.example.ipixelclock.web.Auth
import com.example.ipixelclock.web.WebServer

/**
 * The on-device shell.
 *
 * Almost none of the settings live here. The full interface is the web UI, and
 * the app embeds it in a WebView pointed at its own server on 127.0.0.1 — so the
 * cyberpunk page is written once and the phone and a laptop browser show exactly
 * the same controls, with no chance of the two drifting apart.
 *
 * What is native, and why:
 *  - the **panel preview**, at the top of the screen, because it should be up
 *    and animating before the server has even bound a port
 *  - **start / stop** and **detect panel**, because they are what you reach for
 *    when something is wrong and the web UI is the thing that is wrong
 *  - the **web password** — a single input, no confirmation field. It is set
 *    here and only here: a browser session can never change it, so getting in
 *    once is not enough to lock the owner out.
 */
class MainActivity : android.app.Activity() {

    private lateinit var preview: PanelPreviewView
    private lateinit var statusLine: TextView
    private lateinit var reminderLine: TextView
    private lateinit var urlLine: TextView
    private lateinit var serviceButton: Button
    private lateinit var panelButton: Button
    private lateinit var passwordInput: EditText
    private lateinit var web: WebView

    private val ui = Handler(Looper.getMainLooper())
    private var loadedUrl: String? = null

    private val refresh = object : Runnable {
        override fun run() {
            syncUi()
            ui.postDelayed(this, 1000L)
        }
    }

    // ------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        requestWhatWeNeed()
        ClockService.start(this)
    }

    override fun onResume() {
        super.onResume()
        ui.post(refresh)
    }

    override fun onPause() {
        ClockService.instance?.previewListener = null
        ui.removeCallbacks(refresh)
        super.onPause()
    }

    // Still the right hook at minSdk 21: OnBackPressedDispatcher needs AndroidX
    // Activity, and this shell deliberately has no AppCompat dependency.
    @Deprecated("Deprecated in Java")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Inside the settings UI, back should mean "back a page", not "quit".
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        ClockService.instance?.previewListener = null
        super.onDestroy()
    }

    // ---------------------------------------------------------------- layout

    private fun buildLayout(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(VOID)
        }

        preview = PanelPreviewView(this)
        root.addView(
            preview,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(PANEL)
        }

        statusLine = label("STARTING…", CYAN, 13f, bold = true)
        reminderLine = label("", AMBER, 11f)
        reminderLine.visibility = View.GONE
        urlLine = label("", DIM, 11f)

        header.addView(statusLine)
        header.addView(reminderLine)
        header.addView(urlLine)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        serviceButton = neonButton("STOP CLOCK") { toggleService() }
        panelButton = neonButton("DETECT DISPLAY") { togglePanel() }
        buttons.addView(serviceButton, equalWidth())
        buttons.addView(panelButton, equalWidth())
        header.addView(buttons)

        // The single password input, exactly as specified: one field, one
        // button, no confirmation, and no way to do this from a browser.
        val pwRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        passwordInput = EditText(this).apply {
            hint = "Web interface password"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine()
        }
        pwRow.addView(
            passwordInput,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        pwRow.addView(neonButton("SET") { setPassword() })
        header.addView(pwRow)

        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        web = WebView(this).apply {
            setBackgroundColor(VOID)
            @Suppress("SetJavaScriptEnabled")
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.textZoom = 100
            webViewClient = object : WebViewClient() {
                // The pre-23 signature deliberately: it is the one every API
                // level calls, and referencing WebResourceError in an override
                // would drag an API 23 class into a class that has to load on
                // Android 5.
                @Deprecated("Deprecated in Java")
                @Suppress("DEPRECATION")
                override fun onReceivedError(
                    view: WebView?, errorCode: Int, description: String?, failingUrl: String?
                ) {
                    // The server may not have bound its port yet on a cold
                    // start. Forget what we loaded so the next tick retries,
                    // rather than leaving the browser's error page in our app.
                    loadedUrl = null
                }
            }
        }
        root.addView(
            web,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        return wrapIfTiny(root)
    }

    /** Very short screens get a scroller so the password row stays reachable. */
    private fun wrapIfTiny(root: LinearLayout): View {
        if (resources.displayMetrics.heightPixels >= dp(480)) return root
        return ScrollView(this).apply {
            setBackgroundColor(VOID)
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
    }

    // ----------------------------------------------------------- the actions

    private fun toggleService() {
        val running = ClockService.instance != null
        if (running) {
            ClockService.stop(this)
            web.loadUrl("about:blank")
            loadedUrl = null
        } else {
            ClockService.start(this)
        }
        ui.postDelayed({ syncUi() }, 300L)
    }

    private fun togglePanel() {
        val service = ClockService.instance
        if (service == null) {
            toast("Start the clock first.")
            return
        }
        if (service.isPanelRequested()) {
            service.disconnectPanel()
        } else {
            if (!hasBluetoothPermission()) {
                requestWhatWeNeed()
                toast("Bluetooth permission is needed to find the panel.")
                return
            }
            if (!service.btEnabled()) {
                toast("Switch Bluetooth on, then tap DETECT DISPLAY again.")
                return
            }
            service.connectPanel()
        }
        syncUi()
    }

    private fun setPassword() {
        val value = passwordInput.text.toString()
        if (value.isNotEmpty() && value.length < 4) {
            toast("Use at least four characters.")
            return
        }
        Auth(this).setPassword(value)
        passwordInput.setText("")
        toast(
            if (value.isEmpty()) "Password cleared — the web interface is now closed."
            else "Password set. Anyone already logged in has been signed out."
        )
        syncUi()
    }

    // -------------------------------------------------------------- the sync

    private fun syncUi() {
        val service = ClockService.instance
        if (service == null) {
            statusLine.text = "CLOCK STOPPED"
            statusLine.setTextColor(MAGENTA)
            reminderLine.visibility = View.GONE
            urlLine.text = ""
            serviceButton.text = "START CLOCK"
            panelButton.isEnabled = false
            return
        }

        serviceButton.text = "STOP CLOCK"
        panelButton.isEnabled = true

        // Claim the preview here rather than in onResume: the service is
        // usually still starting when the activity resumes, and this runs every
        // second until it exists.
        if (service.previewListener == null) {
            service.previewListener = { canvas -> preview.submit(canvas) }
        }

        val state = try {
            service.state()
        } catch (_: Exception) {
            return
        }
        val panel = state.optJSONObject("panel")
        val server = state.optJSONObject("server")

        val live = panel?.optBoolean("live") == true
        val status = panel?.optString("status").orEmpty()
        val w = panel?.optInt("width") ?: 0
        val h = panel?.optInt("height") ?: 0

        statusLine.text = when {
            live -> "LIVE  ${w}×$h  ·  $status"
            status.isNotEmpty() -> status
            else -> "SIMULATED  ${w}×$h"
        }
        statusLine.setTextColor(if (live) CYAN else AMBER)

        val reminder = panel?.optString("reminder").orEmpty()
        reminderLine.text = reminder
        reminderLine.visibility = if (reminder.isEmpty()) View.GONE else View.VISIBLE

        panelButton.text = if (service.isPanelRequested()) "FORGET DISPLAY" else "DETECT DISPLAY"

        val port = server?.optInt("port") ?: 0
        val passwordSet = server?.optBoolean("passwordSet") == true
        val ip = WebServer.localIps().firstOrNull()
        urlLine.text = when {
            port == 0 -> "web interface starting…"
            ip == null -> "no network — web interface reachable on this device only"
            !passwordSet -> "http://$ip:$port  —  locked until you set a password above"
            else -> "http://$ip:$port  ·  open this from any browser on the network"
        }

        // Load the embedded UI once the port is known. embedded=1 tells the page
        // to drop its own preview: the native one above it is smoother and the
        // two stacked would be silly.
        if (port != 0) {
            val url = "http://127.0.0.1:$port/?embedded=1"
            if (loadedUrl != url) {
                loadedUrl = url
                web.loadUrl(url)
            }
        }
    }

    // ------------------------------------------------------- the permissions

    private fun requestWhatWeNeed() {
        val wanted = ArrayList<String>()

        if (Build.VERSION.SDK_INT >= 31) {
            // Needed both to find the panel and, from Android 14, to run a
            // connectedDevice foreground service at all.
            if (!granted(Manifest.permission.BLUETOOTH_SCAN)) {
                wanted.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (!granted(Manifest.permission.BLUETOOTH_CONNECT)) {
                wanted.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        } else {
            // Android 6..11 return no BLE scan results at all without this, and
            // with location services switched off — which the driver's own
            // status line will tell you about.
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
                wanted.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            wanted.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        if (wanted.isNotEmpty() && Build.VERSION.SDK_INT >= 23) {
            requestPermissions(wanted.toTypedArray(), REQ_PERMS)
        }
    }

    private fun granted(permission: String): Boolean =
        Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun hasBluetoothPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 31) {
            granted(Manifest.permission.BLUETOOTH_SCAN) &&
                granted(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            true // implicitly granted at install time below Android 12
        }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS) syncUi()
    }

    // ----------------------------------------------------------------- chrome

    private fun label(text: String, color: Int, sizeSp: Float, bold: Boolean = false) =
        TextView(this).apply {
            this.text = text
            setTextColor(color)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            typeface = Typeface.create(Typeface.MONOSPACE, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setPadding(0, dp(2), 0, dp(2))
        }

    private fun neonButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        setTextColor(CYAN)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        setBackgroundColor(0xFF121826.toInt())
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { onClick() }
    }

    private fun equalWidth() =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            .apply { marginEnd = dp(6) }

    private fun dp(v: Int): Int =
        (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQ_PERMS = 42

        private val VOID = 0xFF05060A.toInt()
        private val PANEL = 0xFF0B0E16.toInt()
        private val CYAN = 0xFF00F0FF.toInt()
        private val MAGENTA = 0xFFFF2BD1.toInt()
        private val AMBER = 0xFFFFB000.toInt()
        private val DIM = 0xFF6A7A90.toInt()
        private val TEXT = 0xFFD6E6F2.toInt()
    }
}
