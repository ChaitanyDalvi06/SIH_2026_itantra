package com.itantra.edgespeech.communication

import com.itantra.edgespeech.protocol.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicInteger

class SessionController(
    val nodeId: String,
    private val connectionManager: ConnectionManager
) {
    var sessionId: Long = 0x1122334455667788L
        private set

    private val sequenceCounter = AtomicInteger(1)
    val replayGuard = ReplayGuard()
    val reorderBuffer = ReorderBuffer()

    private val _peerId = MutableStateFlow<String?>(null)
    val peerId: StateFlow<String?> = _peerId

    fun nextSequence(): Int = sequenceCounter.getAndIncrement()

    fun startSession(peerNodeId: String, newSessionId: Long) {
        this._peerId.value = peerNodeId
        this.sessionId = newSessionId
        sequenceCounter.set(1)
        replayGuard.reset()
        reorderBuffer.reset()
    }

    fun buildPartialTextFrame(
        text: String,
        language: Language,
        messageId: Long = System.currentTimeMillis()
    ): BinaryFrame {
        return BinaryFrame(
            packetType = PacketType.TEXT_PARTIAL,
            sessionId = sessionId,
            messageId = messageId,
            sequence = nextSequence(),
            language = language,
            payload = text.toByteArray(Charsets.UTF_8),
            flags = 0
        )
    }

    fun buildTextFrame(
        text: String,
        language: Language,
        isAlert: Boolean = false,
        messageId: Long = System.currentTimeMillis()
    ): BinaryFrame {
        var flags: Byte = BinaryFrame.FLAG_ACK_REQUESTED.toByte()
        if (isAlert) {
            flags = (flags.toInt() or BinaryFrame.FLAG_PRIORITY_ALERT).toByte()
        }

        return BinaryFrame(
            packetType = if (isAlert) PacketType.ALERT else PacketType.TEXT_FINAL,
            sessionId = sessionId,
            messageId = messageId,
            sequence = nextSequence(),
            language = language,
            payload = text.toByteArray(Charsets.UTF_8),
            flags = flags
        )
    }

    fun endSession() {
        _peerId.value = null
        replayGuard.reset()
        reorderBuffer.reset()
    }
}
