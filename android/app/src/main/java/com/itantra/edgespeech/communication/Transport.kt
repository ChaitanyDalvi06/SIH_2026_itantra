package com.itantra.edgespeech.communication

enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DEGRADED,
    RECONNECTING
}

enum class TransportTier {
    WIFI_DIRECT,   // Tier 1: P2P Wi-Fi Direct
    LOCAL_WIFI,    // Tier 2: LAN via NSD / mDNS
    BLUETOOTH,     // Tier 3: RFCOMM fallback
    OFFLINE        // Tier 4: Store-and-forward queue
}

interface Transport {
    val tier: TransportTier
    fun state(): ConnectionState
    fun rtt(): Long
    fun mtu(): Int

    suspend fun connect(targetAddress: String, port: Int)
    suspend fun send(frameBytes: ByteArray): Boolean
    suspend fun receive(): ByteArray?
    fun close()
}
