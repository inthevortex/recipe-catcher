package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeFtsEntity

@Dao
interface RecipeFtsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fts: RecipeFtsEntity)

    @Update
    suspend fun update(fts: RecipeFtsEntity)

    @Query("DELETE FROM recipe_fts WHERE rowid = :rowid")
    suspend fun deleteByRowid(rowid: Long)

    @Query("""
        SELECT recipe.* FROM recipe
        JOIN recipe_fts ON recipe.id = recipe_fts.rowid
        WHERE recipe_fts MATCH :query AND recipe.status = 'saved'
        ORDER BY recipe.saved_at DESC, recipe.created_at DESC
    """)
    suspend fun search(query: String): List<RecipeEntity>
}
