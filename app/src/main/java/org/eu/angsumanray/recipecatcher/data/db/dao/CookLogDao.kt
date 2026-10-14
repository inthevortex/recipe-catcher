package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.CookLogEntity

@Dao
interface CookLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: CookLogEntity): Long

    @Delete
    suspend fun delete(entry: CookLogEntity)

    @Query("SELECT * FROM cook_log WHERE recipe_id = :recipeId ORDER BY cooked_on DESC")
    suspend fun getByRecipeId(recipeId: Long): List<CookLogEntity>

    @Query("SELECT * FROM cook_log WHERE recipe_id = :recipeId ORDER BY cooked_on DESC")
    fun observeByRecipeId(recipeId: Long): Flow<List<CookLogEntity>>

    @Query("SELECT COUNT(*) FROM cook_log WHERE recipe_id = :recipeId")
    suspend fun getTimesCooked(recipeId: Long): Int

    @Query("SELECT MAX(cooked_on) FROM cook_log WHERE recipe_id = :recipeId")
    suspend fun getLastCookedDate(recipeId: Long): String?

    @Query("SELECT photo_path FROM cook_log WHERE photo_path IS NOT NULL")
    suspend fun getReferencedPhotoPaths(): List<String>

    @Query("SELECT * FROM cook_log WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): CookLogEntity?

    @Query("DELETE FROM cook_log WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM cook_log WHERE recipe_id = :recipeId")
    suspend fun getAllByRecipe(recipeId: Long): List<CookLogEntity>
}
