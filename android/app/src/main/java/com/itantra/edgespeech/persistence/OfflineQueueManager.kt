package com.itantra.edgespeech.persistence

import com.itantra.edgespeech.communication.ConnectionManager
import com.itantra.edgespeech.communication.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class OfflineQueueManager(
    private val messageDao: MessageDao,
    private val connectionManager: ConnectionManager,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    companion object {
        const val MAX_OFFLINE_CAPACITY = 100
        const val MAX_RETRIES = 5
    }

    init {
        startFlushWorker()
    }

    suspend fun enqueueOutboundMessage(
        messageId: Long,
        sessionId: Long,
        sequence: Int,
        languageCode: Byte,
        text: String,
        rawBytes: ByteArray,
        isAlert: Boolean
    ) {
        val currentCount = messageDao.getQueuedCount()
        if (currentCount >= MAX_OFFLINE_CAPACITY) {
            messageDao.evictOldestNonAlert()
        }

        val entity = MessageEntity(
            messageId = messageId,
            sessionId = sessionId,
            sequence = sequence,
            timestamp = System.currentTimeMillis(),
            languageCode = languageCode,
            isAlert = isAlert,
            text = text,
            rawBytes = rawBytes,
            status = QueueStatus.QUEUED
        )
        messageDao.insertMessage(entity)
    }

    fun flushNow() {
        scope.launch {
            if (connectionManager.connectionState.value == ConnectionState.CONNECTED) {
                val pending = messageDao.getPendingMessages(10)
                for (msg in pending) {
                    val sent = connectionManager.sendFrame(msg.rawBytes)
                    if (sent) {
                        messageDao.updateStatus(msg.messageId, QueueStatus.ACKED)
                    }
                }
            }
        }
    }

    private fun startFlushWorker() {
        scope.launch {
            while (isActive) {
                if (connectionManager.connectionState.value == ConnectionState.CONNECTED) {
                    val pending = messageDao.getPendingMessages(5)
                    for (msg in pending) {
                        messageDao.incrementRetryAndMarkSending(msg.messageId)
                        val sent = connectionManager.sendFrame(msg.rawBytes)
                        if (sent) {
                            messageDao.updateStatus(msg.messageId, QueueStatus.ACKED)
                        } else {
                            if (msg.retryCount >= MAX_RETRIES) {
                                messageDao.updateStatus(msg.messageId, QueueStatus.FAILED)
                            } else {
                                messageDao.updateStatus(msg.messageId, QueueStatus.QUEUED)
                            }
                        }
                    }
                }
                delay(1000)
            }
        }
    }
}
