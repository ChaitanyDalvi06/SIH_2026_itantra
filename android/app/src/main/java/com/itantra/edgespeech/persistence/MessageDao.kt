package com.itantra.edgespeech.persistence

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE status IN ('QUEUED', 'SENDING') ORDER BY isAlert DESC, createdAt ASC LIMIT :limit")
    suspend fun getPendingMessages(limit: Int = 10): List<MessageEntity>

    @Query("SELECT COUNT(*) FROM messages WHERE status IN ('QUEUED', 'SENDING')")
    suspend fun getQueuedCount(): Int

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateStatus(messageId: Long, status: QueueStatus)

    @Query("UPDATE messages SET status = 'SENDING', retryCount = retryCount + 1 WHERE messageId = :messageId")
    suspend fun incrementRetryAndMarkSending(messageId: Long)

    @Query("DELETE FROM messages WHERE messageId = (SELECT messageId FROM messages WHERE status = 'QUEUED' AND isAlert = 0 ORDER BY createdAt ASC LIMIT 1)")
    suspend fun evictOldestNonAlert()

    @Query("SELECT * FROM messages ORDER BY createdAt ASC LIMIT 100")
    fun observeRecentMessages(): Flow<List<MessageEntity>>
}
