package com.itantra.edgespeech.communication

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

class LanNsdTransport(
    private val context: Context,
    val localPort: Int = 8988
) : Transport {

    override val tier: TransportTier = TransportTier.LOCAL_WIFI
    @Volatile
    private var currentState = ConnectionState.DISCONNECTED
    private var activeSocket: Socket? = null
    private var serverSocket: ServerSocket? = null
    private var dataOutputStream: DataOutputStream? = null

    private var measuredRtt: Long = 15L
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var listenerJob: Job? = null

    var onFrameReceived: ((ByteArray) -> Unit)? = null
    private val stateListeners = CopyOnWriteArrayList<(ConnectionState) -> Unit>()

    fun addStateListener(listener: (ConnectionState) -> Unit) {
        stateListeners.add(listener)
        listener(currentState)
    }

    fun removeStateListener(listener: (ConnectionState) -> Unit) {
        stateListeners.remove(listener)
    }

    override fun state(): ConnectionState = currentState
    override fun rtt(): Long = measuredRtt
    override fun mtu(): Int = 4096

    fun startServer() {
        if (serverSocket != null && !serverSocket!!.isClosed) return
        listenerJob?.cancel()
        listenerJob = scope.launch {
            try {
                serverSocket = ServerSocket(localPort).apply {
                    reuseAddress = true
                }
                Log.i("LanNsdTransport", "ServerSocket listening on port $localPort")

                while (isActive && serverSocket != null && !serverSocket!!.isClosed) {
                    val incoming = serverSocket?.accept() ?: break
                    Log.i("LanNsdTransport", "Accepted incoming socket from ${incoming.inetAddress?.hostAddress}")
                    incoming.tcpNoDelay = true
                    incoming.keepAlive = true

                    synchronized(this@LanNsdTransport) {
                        if (currentState == ConnectionState.CONNECTED &&
                            activeSocket?.isConnected == true &&
                            activeSocket?.isClosed == false
                        ) {
                            Log.i("LanNsdTransport", "Channel already active, closing duplicate incoming socket")
                            try { incoming.close() } catch (_: Exception) {}
                            return@synchronized
                        }

                        attachSocket(incoming)
                    }
                }
            } catch (e: Exception) {
                Log.w("LanNsdTransport", "ServerSocket closed: ${e.message}")
            }
        }
    }

    override suspend fun connect(targetAddress: String, port: Int) = withContext(Dispatchers.IO) {
        synchronized(this@LanNsdTransport) {
            if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
                Log.i("LanNsdTransport", "Skipping connect to $targetAddress:$port (current state: $currentState)")
                return@withContext
            }
            setState(ConnectionState.CONNECTING)
        }

        var socket: Socket? = null
        try {
            Log.i("LanNsdTransport", "Connecting to $targetAddress:$port...")
            socket = Socket()
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.connect(InetSocketAddress(targetAddress, port), 2500)
            Log.i("LanNsdTransport", "Connected successfully to $targetAddress:$port")

            synchronized(this@LanNsdTransport) {
                if (currentState == ConnectionState.CONNECTED &&
                    activeSocket?.isConnected == true &&
                    activeSocket?.isClosed == false
                ) {
                    Log.i("LanNsdTransport", "Incoming connection established while connecting, closing outgoing")
                    try { socket.close() } catch (_: Exception) {}
                    return@withContext
                }

                attachSocket(socket)
            }
        } catch (e: Exception) {
            Log.w("LanNsdTransport", "Connect to $targetAddress:$port failed: ${e.message}")
            try { socket?.close() } catch (_: Exception) {}
            synchronized(this@LanNsdTransport) {
                if (activeSocket?.isConnected != true) {
                    setState(ConnectionState.DISCONNECTED)
                }
            }
        }
    }

    private fun attachSocket(sock: Socket) {
        try {
            if (activeSocket !== sock) {
                activeSocket?.close()
            }
        } catch (_: Exception) {}

        activeSocket = sock
        val dos = DataOutputStream(sock.getOutputStream())
        dataOutputStream = dos
        setState(ConnectionState.CONNECTED)
        Log.i("LanNsdTransport", "Active channel attached, starting receive loop")
        startReceiveLoop(sock)
    }

    private fun startReceiveLoop(sock: Socket) {
        scope.launch {
            try {
                val dis = DataInputStream(sock.getInputStream())
                while (isActive && !sock.isClosed && sock.isConnected) {
                    val frameLength = dis.readInt()
                    if (frameLength in 1..65536) {
                        val frame = ByteArray(frameLength)
                        dis.readFully(frame)
                        onFrameReceived?.invoke(frame)
                    }
                }
            } catch (e: Exception) {
                Log.w("LanNsdTransport", "Socket read error: ${e.message}")
            } finally {
                synchronized(this@LanNsdTransport) {
                    if (activeSocket === sock) {
                        activeSocket = null
                        dataOutputStream = null
                        setState(ConnectionState.DISCONNECTED)
                    }
                }
                try { sock.close() } catch (_: Exception) {}
            }
        }
    }

    override suspend fun send(frameBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val dos = synchronized(this@LanNsdTransport) { dataOutputStream }
            val sock = synchronized(this@LanNsdTransport) { activeSocket }
            if (dos == null || sock == null || !sock.isConnected || sock.isClosed) {
                return@withContext false
            }
            synchronized(dos) {
                dos.writeInt(frameBytes.size)
                dos.write(frameBytes)
                dos.flush()
            }
            true
        } catch (e: Exception) {
            Log.e("LanNsdTransport", "Send frame failed: ${e.message}")
            synchronized(this@LanNsdTransport) {
                if (activeSocket?.isClosed == true || activeSocket?.isConnected == false) {
                    setState(ConnectionState.DISCONNECTED)
                }
            }
            false
        }
    }

    override suspend fun receive(): ByteArray? = null

    private fun setState(state: ConnectionState) {
        if (currentState != state) {
            currentState = state
            Log.i("LanNsdTransport", "State changed to: $state")
            stateListeners.forEach { it.invoke(state) }
        }
    }

    override fun close() {
        synchronized(this) {
            try {
                activeSocket?.close()
                serverSocket?.close()
            } catch (_: Exception) {}
            activeSocket = null
            serverSocket = null
            dataOutputStream = null
            setState(ConnectionState.DISCONNECTED)
        }
        listenerJob?.cancel()
    }
}
