package org.eu.angsumanray.recipecatcher.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tag ORDER BY position ASC, name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tag WHERE kind = :kind ORDER BY position ASC, name ASC")
    suspend fun getByKind(kind: String): List<TagEntity>

    @Query("SELECT * FROM tag WHERE kind = :kind ORDER BY position ASC, name ASC")
    fun observeByKind(kind: String): Flow<List<TagEntity>>

    @Query("SELECT * FROM tag ORDER BY position ASC, name ASC")
    fun observeAll(): Flow<List<TagEntity>>
}

@Dao
interface RecipeTagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(recipeTags: List<RecipeTagEntity>)

    @Query("""
        SELECT tag.* FROM tag
        JOIN recipe_tag ON tag.id = recipe_tag.tag_id
        WHERE recipe_tag.recipe_id = :recipeId
        ORDER BY tag.position ASC, tag.name ASC
    """)
    suspend fun getTagsForRecipe(recipeId: Long): List<TagEntity>

    @Query("""
        SELECT tag.* FROM tag
        JOIN recipe_tag ON tag.id = recipe_tag.tag_id
        WHERE recipe_tag.recipe_id = :recipeId
        ORDER BY tag.position ASC, tag.name ASC
    """)
    fun observeTagsForRecipe(recipeId: Long): Flow<List<TagEntity>>

    @Query("DELETE FROM recipe_tag WHERE recipe_id = :recipeId")
    suspend fun deleteByRecipeId(recipeId: Long)
}
