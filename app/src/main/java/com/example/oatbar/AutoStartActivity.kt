package com.example.oatbar

import android.app.Activity
import android.os.Bundle

/**
 * Invisible launcher entry for the head unit's "application starts automatically" setting.
 * It starts the pill service (if you left it switched on) and closes itself immediately,
 * so nothing covers the screen and the setup screen never opens.
 */
class AutoStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        OatBarService.startIfEnabled(this, "quiet start")
        finish()
    }
}
