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
}
