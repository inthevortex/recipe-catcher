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

    // ABORT, not REPLACE: replacing a session row would cascade-delete its messages.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ChatSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("UPDATE chat_message SET suggestions_json = :suggestionsJson WHERE id = :id")
    suspend fun updateSuggestions(id: Long, suggestionsJson: String?)

    @Query("SELECT * FROM chat_message WHERE id = :id")
    suspend fun getMessage(id: Long): ChatMessageEntity?

    @Query("UPDATE chat_session SET updated_at = :updatedAt WHERE id = :id")
    suspend fun touchSession(id: Long, updatedAt: Long)

    @Query("UPDATE chat_session SET title = :title WHERE id = :id")
    suspend fun updateSessionTitle(id: Long, title: String)

    @Query("SELECT * FROM chat_session WHERE id = :id LIMIT 1")
    suspend fun getSession(id: Long): ChatSessionEntity?

    @Delete
    suspend fun deleteSession(session: ChatSessionEntity)

    @Query("SELECT * FROM chat_session ORDER BY updated_at DESC")
    fun observeSessions(): Flow<List<ChatSessionEntity>>

    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at ASC, id ASC")
    fun observeMessagesForSession(sessionId: Long): Flow<List<ChatMessageEntity>>

    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at DESC, id DESC LIMIT :limit")
    suspend fun getLastMessagesForSession(sessionId: Long, limit: Int = 10): List<ChatMessageEntity>

    @Query("SELECT * FROM chat_session ORDER BY id ASC LIMIT 1")
    suspend fun getFirstSession(): ChatSessionEntity?

    @Query("DELETE FROM chat_message WHERE session_id = :sessionId")
    suspend fun clearMessagesForSession(sessionId: Long)

    @Query("DELETE FROM chat_message")
    suspend fun clearAllMessages()

    @Query("SELECT * FROM chat_message WHERE session_id = :sessionId ORDER BY created_at ASC, id ASC")
    suspend fun getMessagesForSession(sessionId: Long): List<ChatMessageEntity>
}
