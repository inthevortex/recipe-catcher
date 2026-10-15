package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity

@Dao
interface IngredientDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(ingredients: List<IngredientEntity>)

    @Query("SELECT * FROM ingredient WHERE recipe_id = :recipeId ORDER BY position ASC")
    suspend fun getByRecipeId(recipeId: Long): List<IngredientEntity>

    @Query("SELECT * FROM ingredient WHERE recipe_id = :recipeId ORDER BY position ASC")
    fun observeByRecipeId(recipeId: Long): Flow<List<IngredientEntity>>

    @Query("SELECT * FROM ingredient ORDER BY recipe_id ASC, position ASC")
    suspend fun getAll(): List<IngredientEntity>

    @Query("DELETE FROM ingredient WHERE recipe_id = :recipeId")
    suspend fun deleteByRecipeId(recipeId: Long)
}
