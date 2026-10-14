package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity

@Dao
interface RecipeDao {

    // Never REPLACE: it deletes the existing row first, cascading away its ingredients, steps,
    // tags and cook log, and nulling plan entries that point at it.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(recipe: RecipeEntity): Long

    /** Returns -1 when a row with the same `source_key` already exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(recipe: RecipeEntity): Long

    @Upsert
    suspend fun upsert(recipe: RecipeEntity): Long

    @Update
    suspend fun update(recipe: RecipeEntity)

    @Delete
    suspend fun delete(recipe: RecipeEntity)

    @Query("DELETE FROM recipe WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM recipe WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): RecipeEntity?

    @Query("SELECT * FROM recipe WHERE source_key = :sourceKey LIMIT 1")
    suspend fun getBySourceKey(sourceKey: String): RecipeEntity?

    @Query("SELECT * FROM recipe WHERE id = :id")
    fun observeById(id: Long): Flow<RecipeEntity?>

    @Query("SELECT * FROM recipe WHERE status = 'saved' ORDER BY saved_at DESC, created_at DESC")
    fun observeAllSaved(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipe WHERE status != 'saved' ORDER BY created_at DESC")
    fun observeAllPending(): Flow<List<RecipeEntity>>

    @Query("SELECT * FROM recipe WHERE status = 'saved' ORDER BY saved_at DESC, created_at DESC")
    suspend fun getAllSaved(): List<RecipeEntity>

    @Query("SELECT * FROM recipe WHERE status != 'saved' ORDER BY created_at DESC")
    suspend fun getAllPending(): List<RecipeEntity>

    @Query("UPDATE recipe SET status = :status, error_message = :errorMessage, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE recipe SET image_path = :imagePath, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateImagePath(id: Long, imagePath: String, updatedAt: Long = System.currentTimeMillis())
}
