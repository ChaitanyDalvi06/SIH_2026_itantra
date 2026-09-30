package com.itantra.edgespeech.protocol

data class BinaryFrame(
    val packetType: PacketType,
    val sessionId: Long,
    val messageId: Long,
    val sequence: Int,
    val timestamp: Long = System.currentTimeMillis(),
    val language: Language = Language.HINDI,
    val payload: ByteArray = ByteArray(0),
    val flags: Byte = 0,
    var authTag: ByteArray = ByteArray(AUTH_TAG_SIZE)
) {
    val text: String
        get() = try {
            String(payload, Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }

    val isAlert: Boolean
        get() = (packetType == PacketType.ALERT) || ((flags.toInt() and FLAG_PRIORITY_ALERT) != 0)

    val isAckRequested: Boolean
        get() = (flags.toInt() and FLAG_ACK_REQUESTED) != 0

    companion object {
        val MAGIC_BYTES = byteArrayOf(0x49, 0x54) // 'IT'
        const val PROTOCOL_VERSION: Byte = 0x01
        const val HEADER_SIZE = 37
        const val AUTH_TAG_SIZE = 16
        const val MIN_FRAME_SIZE = HEADER_SIZE + AUTH_TAG_SIZE
        const val MAX_PAYLOAD_SIZE = 4096

        const val FLAG_COMPRESSED: Int = 0x01
        const val FLAG_ENCRYPTED: Int = 0x02
        const val FLAG_PRIORITY_ALERT: Int = 0x04
        const val FLAG_ACK_REQUESTED: Int = 0x08
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as BinaryFrame
        return messageId == other.messageId && sequence == other.sequence && packetType == other.packetType
    }

    override fun hashCode(): Int {
        var result = packetType.hashCode()
        result = 31 * result + messageId.hashCode()
        result = 31 * result + sequence
        return result
    }
}
