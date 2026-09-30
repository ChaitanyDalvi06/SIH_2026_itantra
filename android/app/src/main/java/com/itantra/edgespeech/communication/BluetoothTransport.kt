package com.itantra.edgespeech.communication

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class BluetoothTransport : Transport {

    override val tier: TransportTier = TransportTier.BLUETOOTH
    private var currentState = ConnectionState.DISCONNECTED
    private var socket: BluetoothSocket? = null
    private var measuredRtt: Long = 75L

    companion object {
        val ITANTRA_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB") // Standard Serial Port Profile
    }

    override fun state(): ConnectionState = currentState
    override fun rtt(): Long = measuredRtt
    override fun mtu(): Int = 1024

    override suspend fun connect(targetAddress: String, port: Int) = withContext(Dispatchers.IO) {
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return@withContext
        try {
            currentState = ConnectionState.CONNECTING
            val device: BluetoothDevice = adapter.getRemoteDevice(targetAddress)
            socket = device.createRfcommSocketToServiceRecord(ITANTRA_UUID)
            socket?.connect()
            currentState = ConnectionState.CONNECTED
        } catch (e: SecurityException) {
            currentState = ConnectionState.DISCONNECTED
        } catch (e: Exception) {
            currentState = ConnectionState.DISCONNECTED
            close()
        }
    }

    override suspend fun send(frameBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            socket?.outputStream?.write(frameBytes)
            socket?.outputStream?.flush()
            true
        } catch (e: Exception) {
            currentState = ConnectionState.DEGRADED
            false
        }
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val buf = ByteArray(1024)
            val read = socket?.inputStream?.read(buf) ?: -1
            if (read > 0) buf.copyOf(read) else null
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        currentState = ConnectionState.DISCONNECTED
    }
}
