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

    @androidx.room.Update
    suspend fun update(tag: TagEntity)

    @androidx.room.Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM tag WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tag WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TagEntity?
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

    @Query("DELETE FROM recipe_tag WHERE tag_id = :tagId")
    suspend fun deleteByTagId(tagId: Long)

    @Query("SELECT tag_id FROM recipe_tag WHERE recipe_id = :recipeId AND source = :source")
    suspend fun getTagIdsForRecipe(recipeId: Long, source: String): List<Long>

    @Query("SELECT recipe_id FROM recipe_tag WHERE tag_id = :tagId")
    suspend fun getRecipeIdsForTag(tagId: Long): List<Long>
}
