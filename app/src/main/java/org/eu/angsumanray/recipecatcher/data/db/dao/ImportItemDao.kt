package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity

@Dao
interface ImportItemDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: ImportItemEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<ImportItemEntity>)

    @Update
    suspend fun update(item: ImportItemEntity)

    @Query("SELECT * FROM import_item WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): ImportItemEntity?

    @Query("SELECT * FROM import_item WHERE source_key = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): ImportItemEntity?

    @Query("SELECT * FROM import_item WHERE status = 'queued' ORDER BY created_at ASC LIMIT 1")
    suspend fun getNextQueued(): ImportItemEntity?

    @Query("SELECT * FROM import_item WHERE status = 'queued' ORDER BY created_at ASC")
    suspend fun getAllQueued(): List<ImportItemEntity>

    @Query("SELECT * FROM import_item ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ImportItemEntity>>

    @Query("SELECT COUNT(*) FROM import_item WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT COUNT(*) FROM import_item WHERE status = :status")
    fun observeCountByStatus(status: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM import_item WHERE status = 'queued'")
    fun observeQueuedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM import_item WHERE status = 'done'")
    fun observeDoneCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM import_item WHERE status = 'skipped'")
    fun observeSkippedCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM import_item WHERE status = 'failed'")
    fun observeFailedCount(): Flow<Int>

    @Query("SELECT * FROM import_item WHERE status = 'skipped' OR status = 'failed' ORDER BY created_at DESC")
    fun observeSkippedAndFailed(): Flow<List<ImportItemEntity>>

    @Query("SELECT * FROM import_item WHERE status = 'skipped' ORDER BY created_at DESC")
    suspend fun getSkippedItems(): List<ImportItemEntity>

    @Query("SELECT * FROM import_item WHERE status = 'skipped' ORDER BY created_at DESC")
    fun observeSkippedItems(): Flow<List<ImportItemEntity>>

    @Query("UPDATE import_item SET status = :status, last_error = :lastError, recipe_id = :recipeId, attempts = attempts + 1 WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, lastError: String? = null, recipeId: Long? = null)

    @Query("UPDATE import_item SET status = 'queued', attempts = 0, last_error = null WHERE id = :id")
    suspend fun retry(id: Long)

    @Query("UPDATE import_item SET status = 'queued', attempts = 0, last_error = null WHERE status = 'skipped' OR status = 'failed'")
    suspend fun retryAllSkippedAndFailed()

    @Query("DELETE FROM import_item WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM import_item WHERE status = 'done'")
    suspend fun clearCompleted()

    @Query("DELETE FROM import_item")
    suspend fun deleteAll()
}
