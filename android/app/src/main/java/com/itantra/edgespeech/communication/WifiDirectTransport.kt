package com.itantra.edgespeech.communication

import android.content.Context
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class WifiDirectTransport(
    private val context: Context,
    private val port: Int = 8988
) : Transport {

    override val tier: TransportTier = TransportTier.WIFI_DIRECT
    private var currentState = ConnectionState.DISCONNECTED
    private var socket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var measuredRtt: Long = 20L

    override fun state(): ConnectionState = currentState
    override fun rtt(): Long = measuredRtt
    override fun mtu(): Int = 4096

    override suspend fun connect(targetAddress: String, port: Int) = withContext(Dispatchers.IO) {
        try {
            currentState = ConnectionState.CONNECTING
            socket = Socket()
            socket?.connect(InetSocketAddress(targetAddress, port), 5000)
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            currentState = ConnectionState.CONNECTED
        } catch (e: Exception) {
            currentState = ConnectionState.DISCONNECTED
            close()
        }
    }

    suspend fun startServer() = withContext(Dispatchers.IO) {
        try {
            serverSocket = ServerSocket(port)
            socket = serverSocket?.accept()
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            currentState = ConnectionState.CONNECTED
        } catch (e: Exception) {
            currentState = ConnectionState.DISCONNECTED
        }
    }

    override suspend fun send(frameBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            outputStream?.write(frameBytes)
            outputStream?.flush()
            true
        } catch (e: Exception) {
            currentState = ConnectionState.DEGRADED
            false
        }
    }

    override suspend fun receive(): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val buffer = ByteArray(4096)
            val read = inputStream?.read(buffer) ?: -1
            if (read > 0) buffer.copyOf(read) else null
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        try {
            socket?.close()
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
        socket = null
        serverSocket = null
        currentState = ConnectionState.DISCONNECTED
    }
}
