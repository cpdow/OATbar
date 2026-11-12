package com.example.oatbar

/** Tiny shared state so the setup screen can show what the service is doing. */
object OatState {
    @Volatile var status: String = "Stopped"
    @Volatile var lastTempC: Int? = null
    @Volatile var listener: (() -> Unit)? = null

    fun update(newStatus: String? = null, tempC: Int? = lastTempC) {
        if (newStatus != null) status = newStatus
        lastTempC = tempC
        listener?.invoke()
    }
}
