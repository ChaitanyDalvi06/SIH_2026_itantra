package com.itantra.edgespeech.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object FrameSerializer {

    fun serialize(frame: BinaryFrame, secretKey: ByteArray? = null): ByteArray {
        val payloadLen = frame.payload.size
        require(payloadLen <= BinaryFrame.MAX_PAYLOAD_SIZE) { "Payload exceeds max size" }

        val totalSize = BinaryFrame.HEADER_SIZE + payloadLen + BinaryFrame.AUTH_TAG_SIZE
        val buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN)

        // Header (37 bytes)
        buffer.put(BinaryFrame.MAGIC_BYTES)
        buffer.put(BinaryFrame.PROTOCOL_VERSION)
        buffer.put(frame.packetType.code)
        buffer.put(frame.flags)
        buffer.put(BinaryFrame.HEADER_SIZE.toByte())
        buffer.putLong(frame.sessionId)
        buffer.putLong(frame.messageId)
        buffer.putInt(frame.sequence)
        buffer.putLong(frame.timestamp)
        buffer.put(frame.language.code)
        buffer.putShort(payloadLen.toShort())

        // Payload
        buffer.put(frame.payload)

        // Auth Tag
        val frameWithoutTag = buffer.array().copyOfRange(0, BinaryFrame.HEADER_SIZE + payloadLen)
        val tag = if (secretKey != null) {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
            mac.doFinal(frameWithoutTag).copyOfRange(0, BinaryFrame.AUTH_TAG_SIZE)
        } else {
            frame.authTag.copyOf(BinaryFrame.AUTH_TAG_SIZE)
        }
        buffer.put(tag)

        return buffer.array()
    }

    fun deserialize(data: ByteArray, secretKey: ByteArray? = null): BinaryFrame {
        if (data.size < BinaryFrame.MIN_FRAME_SIZE) {
            throw IllegalArgumentException("Frame size ${data.size} smaller than minimum ${BinaryFrame.MIN_FRAME_SIZE}")
        }

        val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

        val magic0 = buffer.get()
        val magic1 = buffer.get()
        if (magic0 != BinaryFrame.MAGIC_BYTES[0] || magic1 != BinaryFrame.MAGIC_BYTES[1]) {
            throw IllegalArgumentException("Invalid magic bytes: 0x%02X 0x%02X".format(magic0, magic1))
        }

        val version = buffer.get()
        if (version != BinaryFrame.PROTOCOL_VERSION) {
            throw IllegalArgumentException("Unsupported protocol version: $version")
        }

        val typeCode = buffer.get()
        val packetType = PacketType.fromCode(typeCode)
        val flags = buffer.get()
        val headerLen = buffer.get().toInt() and 0xFF
        if (headerLen != BinaryFrame.HEADER_SIZE) {
            throw IllegalArgumentException("Header length mismatch: $headerLen")
        }

        val sessionId = buffer.getLong()
        val messageId = buffer.getLong()
        val sequence = buffer.getInt()
        val timestamp = buffer.getLong()
        val langCode = buffer.get()
        val language = Language.fromCode(langCode)
        val payloadLen = buffer.getShort().toInt() and 0xFFFF

        val expectedTotal = BinaryFrame.HEADER_SIZE + payloadLen + BinaryFrame.AUTH_TAG_SIZE
        if (data.size < expectedTotal) {
            throw IllegalArgumentException("Truncated packet: expected $expectedTotal, got ${data.size}")
        }

        val payload = ByteArray(payloadLen)
        buffer.get(payload)

        val authTag = ByteArray(BinaryFrame.AUTH_TAG_SIZE)
        buffer.get(authTag)

        if (secretKey != null) {
            val frameWithoutTag = data.copyOfRange(0, BinaryFrame.HEADER_SIZE + payloadLen)
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey, "HmacSHA256"))
            val expectedTag = mac.doFinal(frameWithoutTag).copyOfRange(0, BinaryFrame.AUTH_TAG_SIZE)
            if (!MessageDigest.isEqual(authTag, expectedTag)) {
                throw SecurityException("Cryptographic authentication tag verification failed")
            }
        }

        return BinaryFrame(
            packetType = packetType,
            sessionId = sessionId,
            messageId = messageId,
            sequence = sequence,
            timestamp = timestamp,
            language = language,
            payload = payload,
            flags = flags,
            authTag = authTag
        )
    }
}
