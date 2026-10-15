package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.ImportItemEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.toExtractionUsageEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.concurrent.TimeUnit

@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: RecipeDatabase,
    private val recipeRepository: RecipeRepository,
    private val instagramPostFetcher: InstagramPostFetcher,
    private val imageStorageManager: ImageStorageManager,
    private val recipeExtractor: RecipeExtractor,
    private val settingsStore: SettingsStore
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "instagram_bulk_import"
        const val KEY_INTERVAL_MILLIS = "key_interval_millis"
        const val DEFAULT_INTERVAL_MILLIS = 30_000L // ~30 seconds interval
        const val COOLDOWN_DURATION_MILLIS = 30 * 60 * 1000L // 30 minutes cooldown
        private const val TAG = "ImportWorker"
        private const val MAX_CONSECUTIVE_FAILURES = 3

        fun enqueue(context: Context, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<ImportWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_INTERVAL_MILLIS to intervalMillis))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
        }

        fun scheduleWithDelay(context: Context, delayMillis: Long, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS) {
            try {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val request = OneTimeWorkRequestBuilder<ImportWorker>()
                    .setConstraints(constraints)
                    .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                    .setInputData(workDataOf(KEY_INTERVAL_MILLIS to intervalMillis))
                    .build()

                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule delayed import worker", e)
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        if (settingsStore.importPausedFlow.first()) {
            Log.d(TAG, "Import is paused by user, skipping work.")
            return Result.success()
        }

        val cooldownUntil = settingsStore.importCooldownUntilFlow.first()
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            val remaining = cooldownUntil - now
            Log.d(TAG, "Import is under cooldown for $remaining ms, rescheduling.")
            scheduleWithDelay(applicationContext, remaining)
            return Result.success()
        }

        val intervalMillis = inputData.getLong(KEY_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
        var consecutiveFailures = 0

        while (!isStopped) {
            if (settingsStore.importPausedFlow.first()) {
                Log.d(TAG, "Import paused during execution.")
                break
            }

            val item = database.importItemDao().getNextQueued() ?: break
            Log.d(TAG, "Processing import item ${item.id}: ${item.sourceKey}")

            val outcome = processItem(item)
            when (outcome) {
                ItemOutcome.SUCCESS -> {
                    consecutiveFailures = 0
                }
                ItemOutcome.SKIPPED -> {
                    consecutiveFailures = 0
                }
                ItemOutcome.FAILURE -> {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                        Log.w(TAG, "3 consecutive fetch failures! Enforcing 30-minute cooldown.")
                        val cooldownEnd = System.currentTimeMillis() + COOLDOWN_DURATION_MILLIS
                        settingsStore.setImportCooldownUntil(cooldownEnd)
                        scheduleWithDelay(applicationContext, COOLDOWN_DURATION_MILLIS, intervalMillis)
                        return Result.success()
                    }
                }
            }

            if (intervalMillis > 0 && !isStopped) {
                delay(intervalMillis)
            }
        }

        return Result.success()
    }

    private enum class ItemOutcome { SUCCESS, SKIPPED, FAILURE }

    private suspend fun processItem(item: ImportItemEntity): ItemOutcome {
        val importDao = database.importItemDao()
        val recipeDao = database.recipeDao()

        // Check if already in recipe library
        val existingRecipe = recipeDao.getBySourceKey(item.sourceKey)
        if (existingRecipe != null && existingRecipe.status == RecipeEntity.STATUS_SAVED) {
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_DONE, recipeId = existingRecipe.id)
            return ItemOutcome.SUCCESS
        }

        val recipeId = if (existingRecipe != null) {
            existingRecipe.id
        } else {
            val initial = RecipeEntity(
                sourceType = SourceType.INSTAGRAM.name.lowercase(),
                sourceKey = item.sourceKey,
                sourceUrl = item.url,
                reviewed = false, // Bulk imported recipes are marked to review
                status = RecipeEntity.STATUS_PENDING_FETCH,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            recipeDao.insert(initial)
        }

        // Apply collection tag if enabled
        val tagWithCollections = settingsStore.tagWithCollectionsFlow.first()
        if (tagWithCollections && !item.collection.isNullOrBlank()) {
            attachCollectionTag(recipeId, item.collection)
        }

        // 1. Fetch Instagram post
        val postData = try {
            instagramPostFetcher.fetch(item.sourceKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Instagram fetch failed for item ${item.id}", e)
            null
        }

        if (postData == null || postData.caption.isNullOrBlank()) {
            val errorMsg = "Couldn't read this post"
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_FAILED, lastError = errorMsg, recipeId = recipeId)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_FETCH, errorMsg)
            return ItemOutcome.FAILURE
        }

        // 2. Download image
        val imagePath = postData.imageUrl?.takeIf { it.isNotBlank() }?.let {
            imageStorageManager.downloadAndSave(recipeId, it)
        }

        var currentRecipe = recipeDao.getById(recipeId) ?: return ItemOutcome.FAILURE
        currentRecipe = currentRecipe.copy(
            captionRaw = postData.caption,
            authorHandle = postData.author ?: currentRecipe.authorHandle,
            imagePath = imagePath ?: currentRecipe.imagePath,
            status = RecipeEntity.STATUS_PENDING_EXTRACT,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        recipeDao.update(currentRecipe)

        // 3. Extract Recipe
        val tagDao = database.tagDao()
        val tagsByKind = tagDao.getAll().groupBy { it.kind }
        val activeModel = settingsStore.modelIdFlow.first()
        val apiKey = settingsStore.getApiKey()

        if (apiKey.isBlank()) {
            val errorMsg = "Add Anthropic API key in Settings"
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_FAILED, lastError = errorMsg, recipeId = recipeId)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_PENDING_EXTRACT, errorMsg)
            return ItemOutcome.FAILURE
        }

        val captionRaw = currentRecipe.captionRaw.orEmpty()
        val cleanedCaption = InstagramPostFetcher.stripTrailingHashtags(captionRaw).ifBlank { captionRaw }
        val input = ExtractionInput(
            sourceType = SourceType.INSTAGRAM,
            sourceText = cleanedCaption,
            topComment = currentRecipe.commentRaw,
            author = currentRecipe.authorHandle,
            allowedTags = tagsByKind[TagEntity.KIND_TAG].orEmpty().map { it.name },
            allowedDiet = tagsByKind[TagEntity.KIND_DIET].orEmpty().map { it.name },
            allowedEquipment = tagsByKind[TagEntity.KIND_EQUIPMENT].orEmpty().map { it.name },
            allowedConsumables = tagsByKind[TagEntity.KIND_CONSUMABLE].orEmpty().map { it.name }
        )

        val extractionResult = recipeExtractor.extract(input)
        val extracted = extractionResult.getOrElse { ex ->
            if (ex is NoRecipeFoundException) {
                ex.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                importDao.updateStatus(item.id, ImportItemEntity.STATUS_SKIPPED, lastError = "No recipe in caption", recipeId = recipeId)
                recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NO_RECIPE_FOUND, "No recipe in caption")
                return ItemOutcome.SKIPPED
            }
            Log.w(TAG, "Extraction error for item ${item.id}", ex)
            val errorMsg = ex.message ?: "Extraction failed"
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_FAILED, lastError = errorMsg, recipeId = recipeId)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, errorMsg)
            return ItemOutcome.FAILURE
        }

        if (!extracted.isRecipe) {
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_SKIPPED, lastError = "No recipe in caption", recipeId = recipeId)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NO_RECIPE_FOUND, "No recipe in caption")
            return ItemOutcome.SKIPPED
        }

        saveExtractedRecipe(
            currentRecipe = currentRecipe,
            extracted = extracted,
            activeModel = activeModel,
            tagsByKind = tagsByKind,
            collectionName = if (tagWithCollections) item.collection else null
        )
        importDao.updateStatus(item.id, ImportItemEntity.STATUS_DONE, recipeId = recipeId)
        return ItemOutcome.SUCCESS
    }

    private suspend fun attachCollectionTag(recipeId: Long, collectionName: String) {
        val tagDao = database.tagDao()
        val trimmed = collectionName.trim()
        if (trimmed.isEmpty()) return

        val existingTag = tagDao.getByNameAndKind(trimmed, TagEntity.KIND_TAG)
        val tagId = if (existingTag != null) {
            existingTag.id
        } else {
            tagDao.insert(TagEntity(name = trimmed, kind = TagEntity.KIND_TAG))
        }
        if (tagId > 0) {
            database.recipeTagDao().insertAll(
                listOf(RecipeTagEntity(recipeId = recipeId, tagId = tagId, source = RecipeTagEntity.SOURCE_USER))
            )
        }
    }

    private suspend fun saveExtractedRecipe(
        currentRecipe: RecipeEntity,
        extracted: ExtractedRecipe,
        activeModel: String,
        tagsByKind: Map<String, List<TagEntity>>,
        collectionName: String?
    ) {
        val model = extracted.usage.model ?: activeModel
        val recipeId = currentRecipe.id
        val now = System.currentTimeMillis()

        val savedRecipe = currentRecipe.copy(
            title = extracted.title ?: currentRecipe.title,
            description = extracted.description ?: currentRecipe.description,
            servings = extracted.servings ?: currentRecipe.servings,
            prepMin = extracted.prepMinutes ?: currentRecipe.prepMin,
            cookMin = extracted.cookMinutes ?: currentRecipe.cookMin,
            totalMin = extracted.totalMinutes ?: currentRecipe.totalMin,
            cuisine = extracted.cuisine ?: currentRecipe.cuisine,
            complexity = extracted.complexity ?: currentRecipe.complexity,
            complexityReason = extracted.complexityReason ?: currentRecipe.complexityReason,
            spiceLevel = extracted.spiceLevel ?: currentRecipe.spiceLevel,
            dietType = extracted.dietType ?: currentRecipe.dietType,
            kcalPerServing = extracted.kcalPerServing ?: currentRecipe.kcalPerServing,
            makeAhead = extracted.makeAhead,
            freezerFriendly = extracted.freezerFriendly,
            llmModel = model,
            status = RecipeEntity.STATUS_SAVED,
            reviewed = false, // Marked to review for bulk-imported recipes
            savedAt = now,
            errorMessage = null,
            updatedAt = now
        )

        val ingredientEntities = extracted.ingredientGroups
            .flatMap { group -> group.items.map { group.name to it } }
            .mapIndexed { position, (groupName, item) ->
                IngredientEntity(
                    recipeId = recipeId,
                    position = position,
                    groupName = groupName,
                    quantity = item.quantity,
                    quantityMax = item.quantityMax,
                    unit = item.unit,
                    name = item.name,
                    baseName = item.baseName,
                    category = item.category,
                    note = item.note,
                    rawText = item.rawText
                )
            }

        val stepEntities = extracted.steps.mapIndexed { idx, step ->
            StepEntity(
                recipeId = recipeId,
                position = idx,
                text = step.text,
                timerSeconds = step.timerSeconds
            )
        }

        fun tagIds(kind: String, names: List<String>): List<Long> {
            val byName = tagsByKind[kind].orEmpty().associateBy { it.name.trim().lowercase() }
            return names.mapNotNull { byName[it.trim().lowercase()]?.id }
        }

        val collectionTagId = if (!collectionName.isNullOrBlank()) {
            val trimmed = collectionName.trim()
            val existing = database.tagDao().getByNameAndKind(trimmed, TagEntity.KIND_TAG)
            existing?.id ?: database.tagDao().insert(TagEntity(name = trimmed, kind = TagEntity.KIND_TAG))
        } else null

        val matchedTagIds = (
            tagIds(TagEntity.KIND_TAG, extracted.tags) +
                tagIds(TagEntity.KIND_DIET, extracted.contains) +
                tagIds(TagEntity.KIND_EQUIPMENT, extracted.equipment) +
                tagIds(TagEntity.KIND_CONSUMABLE, extracted.consumables) +
                listOfNotNull(collectionTagId)
            ).distinct()

        recipeRepository.saveExtraction(
            recipe = savedRecipe,
            ingredients = ingredientEntities,
            steps = stepEntities,
            tagIds = matchedTagIds,
            usage = extracted.usage.toExtractionUsageEntity(model)
        )
    }
}
