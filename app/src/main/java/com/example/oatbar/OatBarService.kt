package com.example.oatbar

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Foreground service that (1) keeps a small always-on-top temperature pill on screen
 * and (2) polls the OBD2 dongle for ambient air temperature (Mode 01, PID 0x46).
 */
class OatBarService : Service() {

    companion object {
        const val ACTION_START = "com.example.oatbar.START"
        const val ACTION_REFRESH = "com.example.oatbar.REFRESH"
        const val ACTION_STOP = "com.example.oatbar.STOP"
        private const val CHANNEL = "oatbar"
    }

    private lateinit var prefs: Prefs
    private lateinit var wm: WindowManager
    private var overlay: TextView? = null
    private var worker: Thread? = null
    @Volatile private var running = false
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                prefs.enabled = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_REFRESH -> {
                main.post { applyOverlayStyle() }
                worker?.interrupt() // reconnect with possibly changed device/transport
                return START_STICKY
            }
        }

        goForeground()
        prefs.enabled = true
        if (!running) {
            running = true
            main.post { addOverlay() }
            worker = Thread({ workerLoop() }, "oatbar-obd").also { it.start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        worker?.interrupt()
        main.post { removeOverlay() }
        OatState.update("Stopped", null)
        super.onDestroy()
    }

    // ---------- foreground notification ----------

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "OATbar", NotificationManager.IMPORTANCE_LOW)
        )
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("OATbar")
            .setContentText("Reading outside temperature from OBD2")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(1, n)
        }
    }

    // ---------- overlay ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addOverlay() {
        if (overlay != null) return
        if (!Settings.canDrawOverlays(this)) {
            OatState.update("Overlay permission missing")
            return
        }
        val tv = TextView(this).apply {
            text = "--°"
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(2), dp(10), dp(2))
            background = GradientDrawable().apply {
                setColor(0x66000000)
                cornerRadius = dp(10).toFloat()
            }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        overlay = tv
        applyOverlayStyle()
        try {
            wm.addView(tv, lp.also { it.x = dp(prefs.offsetX); it.y = dp(prefs.offsetY) })
        } catch (e: Exception) {
            overlay = null
            OatState.update("Overlay failed: ${e.message}")
        }
    }

    private fun applyOverlayStyle() {
        val tv = overlay ?: return
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.textSp.toFloat())
        val lp = tv.layoutParams as? WindowManager.LayoutParams ?: return
        lp.x = dp(prefs.offsetX)
        lp.y = dp(prefs.offsetY)
        try { wm.updateViewLayout(tv, lp) } catch (_: Exception) {}
        setText(OatState.lastTempC)
    }

    private fun removeOverlay() {
        overlay?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        overlay = null
    }

    private fun setText(tempC: Int?) {
        overlay?.text = when {
            tempC == null -> "--°"
            prefs.fahrenheit -> "${(tempC * 9.0 / 5.0 + 32).roundToInt()}°F"
            else -> "$tempC°C"
        }
    }

    private fun show(tempC: Int?, status: String) {
        OatState.update(status, tempC)
        main.post { setText(tempC) }
    }

    /** Sweeps a fake temperature between -10°C and 30°C, one degree per second, until demo mode is turned off. */
    private fun runDemo() {
        var c = 2
        var step = 1
        while (running && prefs.demo) {
            show(c, "Demo mode (simulated temperature, no dongle)")
            Thread.sleep(1000)
            if (c >= 30) step = -1
            if (c <= -10) step = 1
            c += step
        }
    }

    // ---------- OBD worker ----------

    private fun workerLoop() {
        var backoff = 2000L
        while (running) {
            var transport: ObdTransport? = null
            try {
                if (prefs.demo) {
                    runDemo()
                    continue
                }
                val addr = prefs.address
                if (addr == null) {
                    show(null, "No device selected")
                    Thread.sleep(3000)
                    continue
                }
                show(OatState.lastTempC, "Connecting to $addr…")
                val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
                if (adapter == null || !adapter.isEnabled) throw java.io.IOException("Bluetooth is off")
                val dev = adapter.getRemoteDevice(addr)
                transport = if (prefs.useBle) BleTransport(this, dev) else ClassicTransport(dev)
                transport.open()
                val elm = Elm327(transport)
                elm.init()
                backoff = 2000L
                show(OatState.lastTempC, "Connected, waiting for ECU…")

                var misses = 0
                while (running) {
                    val c = elm.ambientCelsius()
                    if (c != null) {
                        misses = 0
                        show(c, "OK")
                    } else {
                        misses++
                        if (misses >= 3) {
                            show(null, "ECU gave no data for PID 0146 (ignition off, or PID unsupported)")
                        }
                    }
                    Thread.sleep(prefs.pollSeconds * 1000L)
                }
            } catch (e: InterruptedException) {
                // refresh or shutdown requested: loop re-checks `running` and reconnects
            } catch (e: Exception) {
                show(null, "Error: ${e.message}")
            } finally {
                try { transport?.close() } catch (_: Exception) {}
            }
            if (!running) break
            try { Thread.sleep(backoff) } catch (_: InterruptedException) {}
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }
}
