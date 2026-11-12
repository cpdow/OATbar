package com.example.oatbar

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

/** Setup screen: permissions, dongle selection, units, pill position, start/stop. Built in code (no XML). */
@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var deviceView: TextView
    private lateinit var statusView: TextView
    private lateinit var unitBtn: Button
    private lateinit var transportBtn: Button
    private lateinit var demoBtn: Button
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
    }

    override fun onResume() {
        super.onResume()
        OatState.listener = { runOnUiThread { render() } }
        render()
    }

    override fun onPause() {
        OatState.listener = null
        super.onPause()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { v ->
            addView(v, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 16f; setPadding(0, dp(14), 0, dp(4))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

        root.addView(header("1. Permissions"))
        root.addView(row(
            button("Bluetooth permissions") { requestBtPermissions() },
            button("Overlay permission") { requestOverlay() }
        ))

        root.addView(header("2. OBD2 dongle"))
        deviceView = TextView(this)
        root.addView(deviceView)
        root.addView(row(
            button("Paired devices") { pickPaired() },
            button("Scan for BLE") { scanBle() }
        ))
        transportBtn = button("") {
            prefs.useBle = !prefs.useBle; render(); refreshService()
        }
        root.addView(transportBtn)

        root.addView(header("3. Display"))
        unitBtn = button("") { prefs.fahrenheit = !prefs.fahrenheit; render(); refreshService() }
        root.addView(unitBtn)
        root.addView(row(
            button("◄") { prefs.offsetX = (prefs.offsetX - 10).coerceAtLeast(0); refreshService() },
            button("►") { prefs.offsetX += 10; refreshService() },
            button("▲") { prefs.offsetY = (prefs.offsetY - 4).coerceAtLeast(0); refreshService() },
            button("▼") { prefs.offsetY += 4; refreshService() }
        ))
        root.addView(row(
            button("Text −") { prefs.textSp = (prefs.textSp - 2).coerceAtLeast(10); refreshService() },
            button("Text +") { prefs.textSp = (prefs.textSp + 2).coerceAtMost(60); refreshService() }
        ))
        root.addView(TextView(this).apply {
            text = "Start the service, then nudge the arrows until the pill sits next to your clock."
        })

        root.addView(header("4. Run"))
        demoBtn = button("") { prefs.demo = !prefs.demo; render(); refreshService() }
        root.addView(demoBtn)
        root.addView(row(
            button("Start") { startOat() },
            button("Stop") {
                startService(Intent(this, OatBarService::class.java).setAction(OatBarService.ACTION_STOP))
            }
        ))
        statusView = TextView(this).apply { setPadding(0, dp(12), 0, 0) }
        root.addView(statusView)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun render() {
        val addr = prefs.address
        deviceView.text = "Device: ${addr ?: "none selected"}"
        transportBtn.text = "Transport: ${if (prefs.useBle) "BLE" else "Classic SPP"} (tap to switch)"
        unitBtn.text = "Units: ${if (prefs.fahrenheit) "°F" else "°C"} (tap to switch)"
        demoBtn.text = "Demo mode: ${if (prefs.demo) "ON" else "OFF"} (fake temperature, no dongle needed)"
        val c = OatState.lastTempC
        val last = if (c == null) "--" else
            "$c°C / ${(c * 9.0 / 5.0 + 32).roundToInt()}°F"
        statusView.text = "Status: ${OatState.status}\nLast reading: $last"
    }

    private fun refreshService() {
        if (OatState.status != "Stopped") {
            startService(Intent(this, OatBarService::class.java).setAction(OatBarService.ACTION_REFRESH))
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    // ---------- permissions ----------

    private fun requestBtPermissions() {
        val perms = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            perms += Manifest.permission.BLUETOOTH_CONNECT
            perms += Manifest.permission.BLUETOOTH_SCAN
        } else {
            perms += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        requestPermissions(perms.toTypedArray(), 1)
    }

    private fun requestOverlay() {
        if (Settings.canDrawOverlays(this)) { toast("Overlay permission already granted"); return }
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            toast("This head unit has no overlay settings screen. From a PC: adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow")
        }
    }

    // ---------- device selection ----------

    private fun adapter() = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private fun chooseDevice(d: BluetoothDevice) {
        prefs.address = d.address
        // Dual-mode devices default to classic SPP, which is the simpler/more reliable path.
        prefs.useBle = d.type == BluetoothDevice.DEVICE_TYPE_LE
        render()
        refreshService()
    }

    private fun pickPaired() {
        val devs = adapter()?.bondedDevices?.toList().orEmpty()
        if (devs.isEmpty()) {
            toast("No paired devices. Pair the dongle in the head unit's Bluetooth settings (PIN usually 1234 or 0000), or use Scan for BLE.")
            return
        }
        val names = devs.map { "${it.name ?: "unnamed"}  (${it.address})" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Paired devices")
            .setItems(names) { _, i -> chooseDevice(devs[i]) }
            .show()
    }

    private fun scanBle() {
        val scanner = adapter()?.bluetoothLeScanner
        if (scanner == null) { toast("Bluetooth is off or BLE is unavailable"); return }
        val found = LinkedHashMap<String, BluetoothDevice>()
        val labels = ArrayList<String>()
        val listAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val d = result.device
                if (found.put(d.address, d) == null) {
                    labels.add("${d.name ?: "unnamed"}  (${d.address})")
                    listAdapter.notifyDataSetChanged()
                }
            }
        }
        scanner.startScan(cb)
        val stop = Runnable { try { scanner.stopScan(cb) } catch (_: Exception) {} }
        handler.postDelayed(stop, 15_000)
        AlertDialog.Builder(this)
            .setTitle("BLE devices (plug the dongle in first)")
            .setAdapter(listAdapter) { _, i -> chooseDevice(found.values.toList()[i]) }
            .setOnDismissListener { handler.removeCallbacks(stop); stop.run() }
            .show()
    }

    // ---------- run ----------

    private fun startOat() {
        if (prefs.address == null && !prefs.demo) { toast("Pick a dongle first, or turn on Demo mode"); return }
        if (!Settings.canDrawOverlays(this)) { toast("Grant overlay permission first"); return }
        startForegroundService(
            Intent(this, OatBarService::class.java).setAction(OatBarService.ACTION_START)
        )
    }
}
