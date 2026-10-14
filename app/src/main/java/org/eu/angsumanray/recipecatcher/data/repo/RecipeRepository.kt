package org.eu.angsumanray.recipecatcher.data.repo

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import org.eu.angsumanray.recipecatcher.core.capture.SourceClassifier
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeFtsEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import javax.inject.Inject
import javax.inject.Singleton

sealed interface CaptureStart {
    data class Created(val recipeId: Long, val sourceType: SourceType) : CaptureStart
    data class Existing(val recipeId: Long) : CaptureStart
    data object NoLink : CaptureStart
}

@Singleton
class RecipeRepository @Inject constructor(
    private val database: RecipeDatabase,
    private val imageStorageManager: ImageStorageManager?
) {
    // Secondary constructor for tests without ImageStorageManager
    constructor(database: RecipeDatabase) : this(database, null)

    private val recipeDao = database.recipeDao()
    private val ingredientDao = database.ingredientDao()
    private val stepDao = database.stepDao()
    private val recipeTagDao = database.recipeTagDao()
    private val tagDao = database.tagDao()
    private val recipeFtsDao = database.recipeFtsDao()
    private val llmUsageDao = database.llmUsageDao()
    private val cookLogDao = database.cookLogDao()

    fun observeSavedRecipes(): Flow<List<RecipeEntity>> = recipeDao.observeAllSaved()

    fun observePendingRecipes(): Flow<List<RecipeEntity>> = recipeDao.observeAllPending()

    fun observeRecipe(id: Long): Flow<RecipeEntity?> = recipeDao.observeById(id)

    fun observeIngredients(recipeId: Long): Flow<List<IngredientEntity>> =
        ingredientDao.observeByRecipeId(recipeId)

    fun observeSteps(recipeId: Long): Flow<List<StepEntity>> =
        stepDao.observeByRecipeId(recipeId)

    fun observeTags(recipeId: Long): Flow<List<TagEntity>> =
        recipeTagDao.observeTagsForRecipe(recipeId)

    suspend fun getIngredients(recipeId: Long): List<IngredientEntity> =
        ingredientDao.getByRecipeId(recipeId)

    suspend fun getSteps(recipeId: Long): List<StepEntity> =
        stepDao.getByRecipeId(recipeId)

    suspend fun getTags(recipeId: Long): List<TagEntity> =
        recipeTagDao.getTagsForRecipe(recipeId)

    fun observeFilteredRecipes(filter: RecipeFilter): Flow<List<RecipeEntity>> {
        val query = RecipeFilterQueryBuilder.buildQuery(filter)
        return recipeDao.queryRecipes(query)
    }

    suspend fun queryFilteredRecipes(filter: RecipeFilter): List<RecipeEntity> {
        val query = RecipeFilterQueryBuilder.buildQuery(filter)
        return recipeDao.queryRecipesList(query)
    }

    suspend fun getRecipe(id: Long): RecipeEntity? = recipeDao.getById(id)

    suspend fun getRecipeBySourceKey(sourceKey: String): RecipeEntity? =
        recipeDao.getBySourceKey(sourceKey)

    suspend fun toggleFavourite(id: Long, isFavourite: Boolean) {
        recipeDao.updateFavourite(id, isFavourite)
    }

    suspend fun updatePersonalNote(id: Long, personalNote: String?) {
        recipeDao.updatePersonalNote(id, personalNote)
        val recipe = recipeDao.getById(id)
        if (recipe != null) {
            val ingredients = ingredientDao.getByRecipeId(id)
            val steps = stepDao.getByRecipeId(id)
            val ingredientsText = ingredients.joinToString(" ") { "${it.name} ${it.rawText}" }
            val stepsText = steps.joinToString(" ") { it.text }
            val fts = RecipeFtsEntity(
                rowid = id,
                title = recipe.title.orEmpty(),
                ingredients = ingredientsText,
                steps = stepsText,
                caption = recipe.captionRaw.orEmpty(),
                author = recipe.authorHandle.orEmpty(),
                notes = personalNote.orEmpty()
            )
            recipeFtsDao.insert(fts)
        }
    }

    suspend fun updateCoverPhotoPath(id: Long, coverPhotoPath: String?) {
        recipeDao.updateCoverPhotoPath(id, coverPhotoPath)
    }

    /**
     * Dedupes [sharedText]'s link against the library and, when it is new, writes the
     * `pending_fetch` row. Check and insert run in one transaction, so two quick shares of the
     * same post can't both create a row.
     */
    suspend fun startCapture(sharedText: String): CaptureStart {
        val source = SourceClassifier.classifySharedText(sharedText) ?: return CaptureStart.NoLink
        return database.withTransaction {
            val existing = recipeDao.getBySourceKey(source.sourceKey)
            if (existing != null) {
                CaptureStart.Existing(existing.id)
            } else {
                val id = insertPendingRecipe(source.sourceType.value, source.sourceKey, source.canonicalUrl)
                CaptureStart.Created(id, source.sourceType)
            }
        }
    }

    /** Inserts a `pending_fetch` row, or returns the id of the row that already has [sourceKey]. */
    suspend fun insertPendingRecipe(
        sourceType: String,
        sourceKey: String,
        sourceUrl: String? = null
    ): Long {
        val now = System.currentTimeMillis()
        val entity = RecipeEntity(
            sourceType = sourceType,
            sourceKey = sourceKey,
            sourceUrl = sourceUrl,
            status = RecipeEntity.STATUS_PENDING_FETCH,
            createdAt = now,
            updatedAt = now
        )
        val id = recipeDao.insertIfAbsent(entity)
        return if (id != -1L) id else checkNotNull(recipeDao.getBySourceKey(sourceKey)).id
    }

    /** Writes an extraction result and its usage row atomically, leaving the recipe in [RecipeEntity.STATUS_NEEDS_REVIEW]. */
    suspend fun saveExtraction(
        recipe: RecipeEntity,
        ingredients: List<IngredientEntity>,
        steps: List<StepEntity>,
        tagIds: List<Long>,
        usage: LlmUsageEntity
    ) = database.withTransaction {
        recipeDao.update(recipe)
        ingredientDao.deleteByRecipeId(recipe.id)
        ingredientDao.insertAll(ingredients)
        stepDao.deleteByRecipeId(recipe.id)
        stepDao.insertAll(steps)
        recipeTagDao.deleteByRecipeId(recipe.id)
        recipeTagDao.insertAll(tagIds.map { RecipeTagEntity(recipeId = recipe.id, tagId = it, source = RecipeTagEntity.SOURCE_AI) })
        llmUsageDao.insert(usage)
    }

    suspend fun logUsage(usage: LlmUsageEntity) {
        llmUsageDao.insert(usage)
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
        val recipeId = recipeDao.upsert(savedRecipe).let { if (it == -1L) savedRecipe.id else it }

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
        val recipe = recipeDao.getById(recipeId)
        val cookLogs = cookLogDao.getAllByRecipe(recipeId)

        // Delete associated image files
        imageStorageManager?.let { mgr ->
            recipe?.imagePath?.let { mgr.deleteImage(it) }
            recipe?.coverPhotoPath?.let { mgr.deleteImage(it) }
            cookLogs.forEach { log ->
                log.photoPath?.let { mgr.deleteImage(it) }
            }
        }

        recipeDao.deleteById(recipeId)
        recipeFtsDao.deleteByRowid(recipeId)
    }

    suspend fun cleanOrphanedImages(): Int {
        val mgr = imageStorageManager ?: return 0
        val diskFiles = mgr.scanAllImageFiles()
        if (diskFiles.isEmpty()) return 0

        val recipeImages = recipeDao.getReferencedImagePaths().toSet()
        val cookPhotos = cookLogDao.getReferencedPhotoPaths().toSet()
        val referenced = recipeImages + cookPhotos

        var deletedCount = 0
        for (file in diskFiles) {
            if (file !in referenced) {
                if (mgr.deleteImage(file)) {
                    deletedCount++
                }
            }
        }
        return deletedCount
    }

    suspend fun search(query: String): List<RecipeEntity> {
        val sanitized = sanitizeFtsQuery(query)
        if (sanitized.isBlank()) return emptyList()
        return recipeFtsDao.search(sanitized)
    }

    companion object {
        fun sanitizeFtsQuery(query: String): String {
            // Keep letters and digits in any script (unicode61 tokenizer); drop FTS syntax like * " : -
            val clean = query.replace("[^\\p{L}\\p{M}\\p{N}\\s]".toRegex(), " ").trim()
            val tokens = clean.split("\\s+".toRegex()).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return ""
            // Append wildcard prefix search to each term: "pan" -> "pan*"
            return tokens.joinToString(" ") { "$it*" }
        }
    }
}
