package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity

@Dao
interface StepDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(steps: List<StepEntity>)

    @Query("SELECT * FROM step WHERE recipe_id = :recipeId ORDER BY position ASC")
    suspend fun getByRecipeId(recipeId: Long): List<StepEntity>

    @Query("SELECT * FROM step WHERE recipe_id = :recipeId ORDER BY position ASC")
    fun observeByRecipeId(recipeId: Long): Flow<List<StepEntity>>

    @Query("DELETE FROM step WHERE recipe_id = :recipeId")
    suspend fun deleteByRecipeId(recipeId: Long)
}
