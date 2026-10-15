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
        const val DEFAULT_INTERVAL_MILLIS = 30_000L
        const val COOLDOWN_DURATION_MILLIS = 30 * 60 * 1000L
        const val MAX_CONSECUTIVE_FETCH_FAILURES = 3
        /** A post whose fetch failed in a run that hit the cooldown goes back in the queue until it has this many attempts. */
        const val MAX_FETCH_ATTEMPTS = 3
        private const val TAG = "ImportWorker"

        /** Starts the queue now, unless a run (or its cooldown wait) is already scheduled. */
        fun enqueue(context: Context, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                buildRequest(delayMillis = 0L, intervalMillis = intervalMillis)
            )
        }

        /**
         * Schedules the next run after [delayMillis]. Called from inside a running worker, so it
         * appends to the unique chain instead of replacing (and so cancelling) the caller.
         */
        fun scheduleWithDelay(context: Context, delayMillis: Long, intervalMillis: Long = DEFAULT_INTERVAL_MILLIS) {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_WORK_NAME,
                    ExistingWorkPolicy.APPEND_OR_REPLACE,
                    buildRequest(delayMillis, intervalMillis)
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to schedule delayed import worker", e)
            }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }

        private fun buildRequest(delayMillis: Long, intervalMillis: Long) =
            OneTimeWorkRequestBuilder<ImportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_INTERVAL_MILLIS to intervalMillis))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
    }

    override suspend fun doWork(): Result {
        if (settingsStore.importPausedFlow.first()) {
            Log.d(TAG, "Import is paused by user, skipping work.")
            return Result.success()
        }

        val intervalMillis = inputData.getLong(KEY_INTERVAL_MILLIS, DEFAULT_INTERVAL_MILLIS)
        val cooldownUntil = settingsStore.importCooldownUntilFlow.first()
        val now = System.currentTimeMillis()
        if (now < cooldownUntil) {
            Log.d(TAG, "Import is under cooldown for ${cooldownUntil - now} ms, rescheduling.")
            scheduleWithDelay(applicationContext, cooldownUntil - now, intervalMillis)
            return Result.success()
        }

        // Without a key every post would fail at extraction, so leave the queue untouched.
        if (settingsStore.getApiKey().isBlank()) {
            Log.w(TAG, "No Anthropic API key; import queue left as is.")
            return Result.success()
        }

        val failedFetchIds = mutableListOf<Long>()
        var processedAny = false

        while (!isStopped) {
            if (processedAny && intervalMillis > 0) {
                delay(intervalMillis)
            }
            if (settingsStore.importPausedFlow.first()) {
                Log.d(TAG, "Import paused during execution.")
                break
            }

            val item = database.importItemDao().getNextQueued() ?: break
            processedAny = true
            Log.d(TAG, "Processing import item ${item.id}: ${item.sourceKey}")

            if (processItem(item) == ItemOutcome.FETCH_FAILED) {
                failedFetchIds += item.id
                if (failedFetchIds.size >= MAX_CONSECUTIVE_FETCH_FAILURES) {
                    startCooldown(failedFetchIds, intervalMillis)
                    return Result.success()
                }
            } else {
                failedFetchIds.clear()
            }
        }

        return Result.success()
    }

    /** Instagram is probably rate-limiting: pause 30 minutes and give the posts that just failed another go. */
    private suspend fun startCooldown(failedFetchIds: List<Long>, intervalMillis: Long) {
        Log.w(TAG, "$MAX_CONSECUTIVE_FETCH_FAILURES fetch failures in a row; pausing for 30 minutes.")
        val importDao = database.importItemDao()
        for (id in failedFetchIds) {
            val item = importDao.getById(id) ?: continue
            if (item.attempts < MAX_FETCH_ATTEMPTS) importDao.requeue(id)
        }
        settingsStore.setImportCooldownUntil(System.currentTimeMillis() + COOLDOWN_DURATION_MILLIS)
        scheduleWithDelay(applicationContext, COOLDOWN_DURATION_MILLIS, intervalMillis)
    }

    private enum class ItemOutcome { SAVED, SKIPPED, FETCH_FAILED, EXTRACT_FAILED }

    private suspend fun processItem(item: ImportItemEntity): ItemOutcome {
        val importDao = database.importItemDao()
        val recipeDao = database.recipeDao()

        // A post already saved, or waiting in review from a normal capture, is left as it is.
        val existingRecipe = recipeDao.getBySourceKey(item.sourceKey)
        if (existingRecipe != null &&
            (existingRecipe.status == RecipeEntity.STATUS_SAVED || existingRecipe.status == RecipeEntity.STATUS_NEEDS_REVIEW)
        ) {
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_DONE, recipeId = existingRecipe.id)
            return ItemOutcome.SAVED
        }

        val recipeId = existingRecipe?.id ?: recipeRepository.insertPendingRecipe(
            sourceType = SourceType.INSTAGRAM.value,
            sourceKey = item.sourceKey,
            sourceUrl = item.url
        )

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
            return ItemOutcome.FETCH_FAILED
        }

        val imagePath = postData.imageUrl?.takeIf { it.isNotBlank() }?.let {
            imageStorageManager.downloadAndSave(recipeId, it)
        }

        var currentRecipe = recipeDao.getById(recipeId) ?: return ItemOutcome.EXTRACT_FAILED
        currentRecipe = currentRecipe.copy(
            captionRaw = postData.caption,
            authorHandle = postData.author ?: currentRecipe.authorHandle,
            imagePath = imagePath ?: currentRecipe.imagePath,
            reviewed = false,
            status = RecipeEntity.STATUS_PENDING_EXTRACT,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        recipeDao.update(currentRecipe)

        val tagsByKind = database.tagDao().getAll().groupBy { it.kind }
        val activeModel = settingsStore.modelIdFlow.first()

        val captionRaw = currentRecipe.captionRaw.orEmpty()
        val cleanedCaption = InstagramPostFetcher.stripTrailingHashtags(captionRaw).ifBlank { captionRaw }
        // Bulk imports never fetch comments, but a comment from an earlier foreground capture is still used.
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

        val extracted = recipeExtractor.extract(input).getOrElse { ex ->
            if (ex is CancellationException) throw ex
            if (ex is NoRecipeFoundException) {
                ex.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                markSkipped(item, recipeId)
                return ItemOutcome.SKIPPED
            }
            Log.w(TAG, "Extraction error for item ${item.id}", ex)
            val errorMsg = ex.message ?: "Extraction failed"
            importDao.updateStatus(item.id, ImportItemEntity.STATUS_FAILED, lastError = errorMsg, recipeId = recipeId)
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, errorMsg)
            return ItemOutcome.EXTRACT_FAILED
        }

        if (!extracted.isRecipe) {
            recipeRepository.logUsage(extracted.usage.toExtractionUsageEntity(extracted.usage.model ?: activeModel))
            markSkipped(item, recipeId)
            return ItemOutcome.SKIPPED
        }

        val collectionName = item.collection?.trim()?.takeIf { it.isNotEmpty() && settingsStore.tagWithCollectionsFlow.first() }
        saveExtractedRecipe(
            importItemId = item.id,
            currentRecipe = currentRecipe,
            extracted = extracted,
            activeModel = activeModel,
            tagsByKind = tagsByKind,
            collectionName = collectionName
        )
        return ItemOutcome.SAVED
    }

    private suspend fun markSkipped(item: ImportItemEntity, recipeId: Long) {
        val reason = "No recipe in caption"
        database.importItemDao().updateStatus(item.id, ImportItemEntity.STATUS_SKIPPED, lastError = reason, recipeId = recipeId)
        recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NO_RECIPE_FOUND, reason)
    }

    /** The collection's tag id, creating the tag on first use. */
    private suspend fun collectionTagId(collectionName: String): Long? {
        val tagDao = database.tagDao()
        tagDao.getByNameAndKind(collectionName, TagEntity.KIND_TAG)?.let { return it.id }
        val inserted = tagDao.insert(TagEntity(name = collectionName, kind = TagEntity.KIND_TAG))
        return if (inserted > 0) inserted else tagDao.getByNameAndKind(collectionName, TagEntity.KIND_TAG)?.id
    }

    private suspend fun saveExtractedRecipe(
        importItemId: Long,
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
            reviewed = false,
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

        val aiTagIds = (
            tagIds(TagEntity.KIND_TAG, extracted.tags) +
                tagIds(TagEntity.KIND_DIET, extracted.contains) +
                tagIds(TagEntity.KIND_EQUIPMENT, extracted.equipment) +
                tagIds(TagEntity.KIND_CONSUMABLE, extracted.consumables)
            ).distinct()

        recipeRepository.saveImportedRecipe(
            recipe = savedRecipe,
            ingredients = ingredientEntities,
            steps = stepEntities,
            aiTagIds = aiTagIds,
            userTagIds = listOfNotNull(collectionName?.let { collectionTagId(it) }),
            usage = extracted.usage.toExtractionUsageEntity(model),
            importItemId = importItemId
        )
    }
}
