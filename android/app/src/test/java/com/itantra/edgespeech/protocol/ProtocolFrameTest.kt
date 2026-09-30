package com.itantra.edgespeech.protocol

import org.junit.Assert.*
import org.junit.Test

class ProtocolFrameTest {

    private val secret = "test_secret_key_1234567890123456".toByteArray(Charsets.UTF_8)

    @Test
    fun testTextPartialFrameSerialization() {
        val frame = BinaryFrame(
            packetType = PacketType.TEXT_PARTIAL,
            sessionId = 0x123456789ABCDEFL,
            messageId = 9988776655L,
            sequence = 42,
            language = Language.HINDI,
            payload = "नमस्ते भाई".toByteArray(Charsets.UTF_8)
        )

        val serialized = FrameSerializer.serialize(frame, secret)
        val deserialized = FrameSerializer.deserialize(serialized, secret)

        assertEquals(PacketType.TEXT_PARTIAL, deserialized.packetType)
        assertEquals(frame.sessionId, deserialized.sessionId)
        assertEquals(frame.messageId, deserialized.messageId)
        assertEquals(frame.sequence, deserialized.sequence)
        assertEquals(Language.HINDI, deserialized.language)
        assertEquals("नमस्ते भाई", deserialized.text)
        assertFalse(deserialized.isAlert)
    }

    @Test
    fun testTextFinalFrameSerialization() {
        val frame = BinaryFrame(
            packetType = PacketType.TEXT_FINAL,
            sessionId = 0x123456789ABCDEFL,
            messageId = 1122334455L,
            sequence = 43,
            language = Language.ENGLISH,
            payload = "All perimeter posts secure.".toByteArray(Charsets.UTF_8)
        )

        val serialized = FrameSerializer.serialize(frame, secret)
        val deserialized = FrameSerializer.deserialize(serialized, secret)

        assertEquals(PacketType.TEXT_FINAL, deserialized.packetType)
        assertEquals("All perimeter posts secure.", deserialized.text)
        assertEquals(Language.ENGLISH, deserialized.language)
    }

    @Test(expected = SecurityException::class)
    fun testHmacTamperRejection() {
        val frame = BinaryFrame(
            packetType = PacketType.TEXT_FINAL,
            sessionId = 1L,
            messageId = 2L,
            sequence = 1,
            payload = "Original text".toByteArray(Charsets.UTF_8)
        )

        val serialized = FrameSerializer.serialize(frame, secret)
        // Tamper payload byte
        serialized[BinaryFrame.HEADER_SIZE + 2] = (serialized[BinaryFrame.HEADER_SIZE + 2] + 1).toByte()

        FrameSerializer.deserialize(serialized, secret)
    }
}
