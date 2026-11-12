package com.example.oatbar

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Byte pipe to an ELM327-style adapter. Responses end with the '>' prompt. */
interface ObdTransport {
    @Throws(IOException::class) fun open()
    @Throws(IOException::class) fun send(cmd: String)
    /** Returns everything received up to (not including) the '>' prompt. */
    @Throws(IOException::class) fun readResponse(timeoutMs: Long): String
    fun close()
}

/** Classic Bluetooth SPP (RFCOMM). Works with Veepeak OBDCheck BT, OBDLink MX+, vLinker MC, etc. */
@SuppressLint("MissingPermission")
class ClassicTransport(private val device: BluetoothDevice) : ObdTransport {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    override fun open() {
        val spp = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        var s = device.createRfcommSocketToServiceRecord(spp)
        try {
            s.connect()
        } catch (e: IOException) {
            try { s.close() } catch (_: IOException) {}
            // Some cheap ELM clones only accept the hidden channel-1 socket.
            try {
                val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
                s = m.invoke(device, 1) as BluetoothSocket
                s.connect()
            } catch (e2: Exception) {
                throw IOException("RFCOMM connect failed: ${e2.message ?: e.message}")
            }
        }
        socket = s
        input = s.inputStream
        output = s.outputStream
    }

    override fun send(cmd: String) {
        val ins = input ?: throw IOException("not open")
        val out = output ?: throw IOException("not open")
        while (ins.available() > 0) ins.read() // drop stale bytes
        out.write((cmd + "\r").toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    override fun readResponse(timeoutMs: Long): String {
        val ins = input ?: throw IOException("not open")
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (ins.available() > 0) {
                val b = ins.read()
                if (b < 0) throw IOException("stream closed")
                val ch = b.toChar()
                if (ch == '>') return sb.toString()
                sb.append(ch)
            } else {
                Thread.sleep(15)
            }
        }
        throw IOException("timeout waiting for prompt (got: ${sb.toString().trim()})")
    }

    override fun close() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null; input = null; output = null
    }
}

/**
 * BLE GATT transport for adapters like Vgate iCar Pro 2S, OBDLink CX, Veepeak OBDCheck BLE.
 * Finds a service that has both a notify/indicate characteristic and a writable one
 * (usually FFF0/FFF1/FFF2 or FFE0/FFE1) instead of hard-coding UUIDs.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class BleTransport(private val ctx: Context, private val device: BluetoothDevice) : ObdTransport {
    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val ready = CountDownLatch(1)
    @Volatile private var failure: String? = null
    private val rx = LinkedBlockingQueue<Int>()
    private val writeAck = LinkedBlockingQueue<Int>()
    private val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private fun fail(msg: String) {
        if (failure == null) failure = msg
        ready.countDown()
        writeAck.offer(-1)
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else {
                fail("disconnected (status $status)")
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) { fail("service discovery failed ($status)"); return }
            var notifyC: BluetoothGattCharacteristic? = null
            var writeC: BluetoothGattCharacteristic? = null
            for (svc in g.services) {
                val id = svc.uuid.toString()
                if (id.startsWith("00001800") || id.startsWith("00001801")) continue
                var n: BluetoothGattCharacteristic? = null
                var w: BluetoothGattCharacteristic? = null
                for (c in svc.characteristics) {
                    val p = c.properties
                    if (n == null && (p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                                BluetoothGattCharacteristic.PROPERTY_INDICATE)) != 0) n = c
                    if (w == null && (p and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE)) != 0) w = c
                }
                if (n != null && w != null) { notifyC = n; writeC = w; break }
            }
            if (notifyC == null || writeC == null) { fail("no serial-style characteristics found"); return }
            writeChar = writeC
            g.setCharacteristicNotification(notifyC, true)
            val d = notifyC.getDescriptor(cccd)
            if (d == null) {
                ready.countDown()
            } else {
                d.value = if ((notifyC.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0)
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                g.writeDescriptor(d)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) ready.countDown() else fail("enable notify failed ($status)")
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            val v = c.value ?: return
            for (b in v) rx.offer(b.toInt() and 0xFF)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            writeAck.offer(status)
        }
    }

    override fun open() {
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (!ready.await(15, TimeUnit.SECONDS)) {
            throw IOException("BLE connect timeout")
        }
        failure?.let { throw IOException(it) }
        Thread.sleep(300) // let the adapter settle after enabling notifications
    }

    override fun send(cmd: String) {
        val g = gatt ?: throw IOException("not open")
        val c = writeChar ?: throw IOException("not open")
        rx.clear()
        writeAck.clear()
        val data = (cmd + "\r").toByteArray(Charsets.US_ASCII)
        c.writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0)
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        var i = 0
        while (i < data.size) {
            val end = minOf(i + 20, data.size) // default ATT MTU payload
            c.value = data.copyOfRange(i, end)
            if (!g.writeCharacteristic(c)) throw IOException("write rejected")
            val st = writeAck.poll(2, TimeUnit.SECONDS) ?: throw IOException("write timeout")
            if (st != BluetoothGatt.GATT_SUCCESS) throw IOException(failure ?: "write failed ($st)")
            i = end
        }
    }

    override fun readResponse(timeoutMs: Long): String {
        val sb = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) throw IOException("timeout waiting for prompt (got: ${sb.toString().trim()})")
            val b = rx.poll(remaining.coerceAtMost(250), TimeUnit.MILLISECONDS)
            if (b == null) {
                failure?.let { throw IOException(it) }
                continue
            }
            val ch = b.toChar()
            if (ch == '>') return sb.toString()
            sb.append(ch)
        }
    }

    override fun close() {
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null; writeChar = null
    }
}
