package com.example.oatbar

import android.content.Context

/** Simple SharedPreferences wrapper. Offsets are in dp from the top-left corner of the screen. */
class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("oatbar", Context.MODE_PRIVATE)

    var address: String?
        get() = sp.getString("address", null)
        set(v) { sp.edit().putString("address", v).apply() }

    /** true = BLE GATT transport, false = classic Bluetooth SPP (RFCOMM). */
    var useBle: Boolean
        get() = sp.getBoolean("ble", false)
        set(v) { sp.edit().putBoolean("ble", v).apply() }

    var fahrenheit: Boolean
        get() = sp.getBoolean("fahrenheit", true)
        set(v) { sp.edit().putBoolean("fahrenheit", v).apply() }

    var offsetX: Int
        get() = sp.getInt("x", 300)
        set(v) { sp.edit().putInt("x", v).apply() }

    var offsetY: Int
        get() = sp.getInt("y", 8)
        set(v) { sp.edit().putInt("y", v).apply() }

    var textSp: Int
        get() = sp.getInt("textSp", 20)
        set(v) { sp.edit().putInt("textSp", v).apply() }

    var pollSeconds: Int
        get() = sp.getInt("poll", 10)
        set(v) { sp.edit().putInt("poll", v).apply() }

    /** Whether the service should auto-start at boot. */
    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }
}
