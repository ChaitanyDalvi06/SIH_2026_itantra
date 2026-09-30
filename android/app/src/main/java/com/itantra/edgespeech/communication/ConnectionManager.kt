package com.itantra.edgespeech.communication

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class ConnectionManager(
    private val context: Context,
    val wifiDirect: WifiDirectTransport,
    val lanNsd: LanNsdTransport,
    val bluetooth: BluetoothTransport
) {
    private var activeTransport: Transport = lanNsd
    private val scope = CoroutineScope(Dispatchers.IO)

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState

    private val _activeTier = MutableStateFlow(TransportTier.LOCAL_WIFI)
    val activeTier: StateFlow<TransportTier> = _activeTier

    private val _connectedPeerAddress = MutableStateFlow<String?>(null)
    val connectedPeerAddress: StateFlow<String?> = _connectedPeerAddress

    var onFrameReceived: ((ByteArray) -> Unit)? = null

    init {
        lanNsd.onFrameReceived = { data ->
            onFrameReceived?.invoke(data)
        }
        lanNsd.addStateListener { state ->
            _connectionState.value = state
        }
        // Start local TCP ServerSocket to accept incoming connections from peers
        lanNsd.startServer()
    }

    fun connectToPeer(ip: String, port: Int = 8988) {
        val currentState = _connectionState.value
        if (currentState == ConnectionState.CONNECTED || currentState == ConnectionState.CONNECTING) {
            return
        }

        scope.launch {
            _connectedPeerAddress.value = "$ip:$port"
            lanNsd.connect(ip, port)
        }
    }

    fun selectTransport(tier: TransportTier) {
        activeTransport.close()
        activeTransport = when (tier) {
            TransportTier.WIFI_DIRECT -> wifiDirect
            TransportTier.LOCAL_WIFI -> lanNsd
            TransportTier.BLUETOOTH -> bluetooth
            TransportTier.OFFLINE -> lanNsd
        }
        _activeTier.value = tier
        _connectionState.value = activeTransport.state()
    }

    suspend fun sendFrame(bytes: ByteArray): Boolean {
        return lanNsd.send(bytes)
    }

    fun getActiveRtt(): Long = activeTransport.rtt()
}
