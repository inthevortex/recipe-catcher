package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity

@Dao
interface LlmUsageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(usage: LlmUsageEntity): Long

    @Query("SELECT * FROM llm_usage ORDER BY created_at DESC")
    suspend fun getAll(): List<LlmUsageEntity>

    @Query("SELECT * FROM llm_usage ORDER BY created_at DESC")
    fun observeAll(): Flow<List<LlmUsageEntity>>

    @Query("SELECT SUM(input_tokens) FROM llm_usage")
    suspend fun getTotalInputTokens(): Long?

    @Query("SELECT SUM(output_tokens) FROM llm_usage")
    suspend fun getTotalOutputTokens(): Long?

    @Query("SELECT SUM(cache_read_tokens) FROM llm_usage")
    suspend fun getTotalCacheReadTokens(): Long?

    @Query("SELECT SUM(web_searches) FROM llm_usage")
    suspend fun getTotalWebSearches(): Long?
}
