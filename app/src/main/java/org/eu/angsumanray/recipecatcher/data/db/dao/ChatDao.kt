package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatMessageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.ChatSessionEntity

@Dao
interface ChatDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_session ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at ASC")
    fun observeMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at DESC LIMIT :limit")
    suspend fun getLastMessagesForSession(sessionId: Long, limit: Int = 10): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_session ORDER BY id ASC LIMIT 1")
    suspend fun getFirstSession(): ChatSessionEntity?

    @Query("DELETE FROM chat_message WHERE session_id = :sessionId")
    suspend fun clearMessagesForSession(sessionId: Long)

    @Query("DELETE FROM chat_message")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessageEntity>
}
