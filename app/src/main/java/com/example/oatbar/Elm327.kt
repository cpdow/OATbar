package com.example.oatbar

import java.io.IOException

/** Minimal ELM327 protocol layer: init sequence plus OBD-II Mode 01 PID 0x46 (ambient air temperature). */
class Elm327(private val t: ObdTransport) {

    @Throws(IOException::class)
    fun cmd(c: String, timeoutMs: Long = 4000): String {
        t.send(c)
        return t.readResponse(timeoutMs)
            .split('\r', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n")
    }

    /** Reset, echo off, linefeeds off, spaces off, headers off, auto protocol. */
    @Throws(IOException::class)
    fun init() {
        cmd("ATZ", 7000)
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) cmd(c)
    }

    /** Ambient air temperature in °C, or null if the ECU returned no usable data (ignition off, PID unsupported...). */
    @Throws(IOException::class)
    fun ambientCelsius(): Int? {
        // First request after ATSP0 can take several seconds while the adapter searches protocols.
        return parseAmbientC(cmd("0146", 10000))
    }

    companion object {
        /** Parses "4146XX" (spaces already optional). Temperature = XX - 40. */
        fun parseAmbientC(resp: String): Int? {
            for (line in resp.lineSequence()) {
                val s = line.replace(" ", "").uppercase()
                if (s.length >= 6 && s.startsWith("4146") &&
                    s.all { it in '0'..'9' || it in 'A'..'F' }
                ) {
                    return s.substring(4, 6).toInt(16) - 40
                }
            }
            return null
        }
    }
}
