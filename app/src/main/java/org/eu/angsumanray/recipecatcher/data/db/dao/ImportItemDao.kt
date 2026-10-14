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

    @Query("SELECT * FROM import_item WHERE source_key = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): ImportItemEntity?

    @Query("SELECT * FROM import_item WHERE status = 'queued' ORDER BY created_at ASC LIMIT 1")
    suspend fun getNextQueued(): ImportItemEntity?

    @Query("SELECT * FROM import_item ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ImportItemEntity>>
}
