package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TagRepository @Inject constructor(
    private val database: RecipeDatabase
) {
    private val tagDao = database.tagDao()
    private val recipeTagDao = database.recipeTagDao()

    fun observeAllTags(): Flow<List<TagEntity>> = tagDao.observeAll()

    fun observeTagsByKind(kind: String): Flow<List<TagEntity>> = tagDao.observeByKind(kind)

    suspend fun getTagsByKind(kind: String): List<TagEntity> = tagDao.getByKind(kind)

    suspend fun getAllTags(): List<TagEntity> = tagDao.getAll()

    suspend fun addTag(kind: String, name: String): Long {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return -1L
        val existing = tagDao.getByKind(kind).find { it.name.equals(trimmed, ignoreCase = true) }
        if (existing != null) return existing.id
        val maxPos = tagDao.getByKind(kind).maxOfOrNull { it.position } ?: 0
        return tagDao.insert(
            TagEntity(
                kind = kind,
                name = trimmed,
                position = maxPos + 1
            )
        )
    }

    suspend fun renameTag(id: Long, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        val tag = tagDao.getById(id) ?: return
        tagDao.update(tag.copy(name = trimmed))
    }

    suspend fun deleteTag(id: Long) = database.withTransaction {
        recipeTagDao.deleteByTagId(id)
        tagDao.deleteById(id)
    }

    suspend fun mergeTags(sourceTagId: Long, targetTagId: Long) = database.withTransaction {
        if (sourceTagId == targetTagId) return@withTransaction
        val sourceTag = tagDao.getById(sourceTagId) ?: return@withTransaction
        val targetTag = tagDao.getById(targetTagId) ?: return@withTransaction

        val affectedRecipeIds = recipeTagDao.getRecipeIdsForTag(sourceTagId)
        val existingTargetRecipeIds = recipeTagDao.getRecipeIdsForTag(targetTagId).toSet()

        val newLinks = affectedRecipeIds
            .filter { it !in existingTargetRecipeIds }
            .map { recipeId ->
                RecipeTagEntity(
                    recipeId = recipeId,
                    tagId = targetTagId,
                    source = RecipeTagEntity.SOURCE_USER
                )
            }

        if (newLinks.isNotEmpty()) {
            recipeTagDao.insertAll(newLinks)
        }

        recipeTagDao.deleteByTagId(sourceTagId)
        tagDao.deleteById(sourceTagId)
    }
}
