package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity

@Dao
interface RecipeDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recipe: RecipeEntity): Long

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
