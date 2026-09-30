package com.itantra.edgespeech.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class QueueStatus {
    CREATED,
    QUEUED,
    SENDING,
    ACKED,
    FAILED,
    EXPIRED
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val messageId: Long,
    val sessionId: Long,
    val sequence: Int,
    val timestamp: Long,
    val languageCode: Byte,
    val isAlert: Boolean,
    val text: String,
    val rawBytes: ByteArray,
    val status: QueueStatus = QueueStatus.QUEUED,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MessageEntity
        return messageId == other.messageId
    }

    override fun hashCode(): Int = messageId.hashCode()
}
