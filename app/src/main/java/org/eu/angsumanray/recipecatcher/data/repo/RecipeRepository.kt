package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeFtsEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecipeRepository @Inject constructor(
    private val database: RecipeDatabase
) {
    private val recipeDao = database.recipeDao()
    private val ingredientDao = database.ingredientDao()
    private val stepDao = database.stepDao()
    private val recipeTagDao = database.recipeTagDao()
    private val tagDao = database.tagDao()
    private val recipeFtsDao = database.recipeFtsDao()

    fun observeSavedRecipes(): Flow<List<RecipeEntity>> = recipeDao.observeAllSaved()

    fun observePendingRecipes(): Flow<List<RecipeEntity>> = recipeDao.observeAllPending()

    fun observeRecipe(id: Long): Flow<RecipeEntity?> = recipeDao.observeById(id)

    fun observeIngredients(recipeId: Long): Flow<List<IngredientEntity>> =
        ingredientDao.observeByRecipeId(recipeId)

    fun observeSteps(recipeId: Long): Flow<List<StepEntity>> =
        stepDao.observeByRecipeId(recipeId)

    fun observeTags(recipeId: Long): Flow<List<TagEntity>> =
        recipeTagDao.observeTagsForRecipe(recipeId)

    suspend fun getRecipe(id: Long): RecipeEntity? = recipeDao.getById(id)

    suspend fun getRecipeBySourceKey(sourceKey: String): RecipeEntity? =
        recipeDao.getBySourceKey(sourceKey)

    suspend fun insertPendingRecipe(
        sourceType: String,
        sourceKey: String,
        sourceUrl: String? = null
    ): Long {
        val entity = RecipeEntity(
            sourceType = sourceType,
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            status = RecipeEntity.STATUS_PENDING_FETCH,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        return recipeDao.insert(entity)
    }

    suspend fun updateRecipeStatus(
        id: Long,
        status: String,
        errorMessage: String? = null
    ) {
        recipeDao.updateStatus(id, status, errorMessage)
    }

    suspend fun updateImagePath(id: Long, imagePath: String) {
        recipeDao.updateImagePath(id, imagePath)
    }

    suspend fun saveFullRecipe(
        recipe: RecipeEntity,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        tagIds: List<Long>,
        source: String = RecipeTagEntity.SOURCE_AI
    ): Long = database.withTransaction {
        val savedRecipe = recipe.copy(
            status = RecipeEntity.STATUS_SAVED,
            savedAt = recipe.savedAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        val recipeId = recipeDao.insert(savedRecipe)

        // Clear and re-insert ingredients
        ingredientDao.deleteByRecipeId(recipeId)
        val positionedIngredients = ingredients.mapIndexed { index, ing ->
            ing.copy(id = 0, recipeId = recipeId, position = index)
        }
        ingredientDao.insertAll(positionedIngredients)

        // Clear and re-insert steps
        stepDao.deleteByRecipeId(recipeId)
        val positionedSteps = steps.mapIndexed { index, st ->
            st.copy(id = 0, recipeId = recipeId, position = index)
        }
        stepDao.insertAll(positionedSteps)

        // Clear and re-insert tags
        recipeTagDao.deleteByRecipeId(recipeId)
        val recipeTags = tagIds.map { tagId ->
            RecipeTagEntity(recipeId = recipeId, tagId = tagId, source = source)
        }
        recipeTagDao.insertAll(recipeTags)

        // Update FTS row
        val ingredientsText = positionedIngredients.joinToString(" ") { "${it.name} ${it.rawText}" }
        val stepsText = positionedSteps.joinToString(" ") { it.text }
        val fts = RecipeFtsEntity(
            rowid = recipeId,
            title = savedRecipe.title.orEmpty(),
            ingredients = ingredientsText,
            steps = stepsText,
            caption = savedRecipe.captionRaw.orEmpty(),
            author = savedRecipe.authorHandle.orEmpty(),
            notes = savedRecipe.personalNote.orEmpty()
        )
        recipeFtsDao.insert(fts)

        recipeId
    }

    suspend fun deleteRecipe(recipeId: Long) = database.withTransaction {
        recipeDao.deleteById(recipeId)
        recipeFtsDao.deleteByRowid(recipeId)
    }

    suspend fun search(query: String): List<RecipeEntity> {
        val sanitized = sanitizeFtsQuery(query)
        if (sanitized.isBlank()) return emptyList()
        return recipeFtsDao.search(sanitized)
    }

    companion object {
        fun sanitizeFtsQuery(query: String): String {
            // Remove punctuation and characters that break SQLite FTS syntax (*, ", ', :, etc.)
            val clean = query.replace("[^a-zA-Z0-9\\s]".toRegex(), " ").trim()
            val tokens = clean.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return ""
            // Append wildcard prefix search to each term: "pan" -> "pan*"
            return tokens.joinToString(" ") { "$it*" }
        }
    }
}
