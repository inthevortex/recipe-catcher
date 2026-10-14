package org.eu.angsumanray.recipecatcher.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "llm_usage",
    indices = [
        Index(value = ["purpose"]),
        Index(value = ["created_at"])
    ]
)
data class LlmUsageEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis(),
    val purpose: String, // extraction, chat, planning, web
    val model: String,
    @ColumnInfo(name = "input_tokens")
    val inputTokens: Int,
    @ColumnInfo(name = "output_tokens")
    val outputTokens: Int,
    @ColumnInfo(name = "cache_read_tokens")
    val cacheReadTokens: Int,
    @ColumnInfo(name = "web_searches")
    val webSearches: Int = 0
) {
    companion object {
        const val PURPOSE_EXTRACTION = "extraction"
        const val PURPOSE_CHAT = "chat"
        const val PURPOSE_PLANNING = "planning"
        const val PURPOSE_WEB = "web"
    }
}
