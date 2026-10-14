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
import kotlinx.coroutines.flow.first
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.llm.ApiKeyInvalidException
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.ModelNotFoundException
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.llm.TransientApiException
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
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
class CaptureWorker @AssistedInject constructor(
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
        const val KEY_RECIPE_ID = "key_recipe_id"
        private const val TAG = "CaptureWorker"
        private const val MAX_ATTEMPTS = 3

        fun enqueue(context: Context, recipeId: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = OneTimeWorkRequestBuilder<CaptureWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_RECIPE_ID to recipeId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "capture_recipe_$recipeId",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val recipeId = inputData.getLong(KEY_RECIPE_ID, -1L)
        if (recipeId == -1L) return Result.failure()

        val recipe = recipeRepository.getRecipe(recipeId) ?: return Result.failure()
        if (recipe.status == RecipeEntity.STATUS_SAVED || recipe.status == RecipeEntity.STATUS_NEEDS_REVIEW) {
            return Result.success()
        }

        var currentRecipe = recipe
        if (currentRecipe.captionRaw.isNullOrBlank()) {
            if (currentRecipe.sourceType != SourceType.INSTAGRAM.value) {
                recipeRepository.updateRecipeStatus(
                    recipeId,
                    RecipeEntity.STATUS_FAILED_FETCH,
                    "Only Instagram links can be captured for now"
                )
                return Result.failure()
            }

            val postData = try {
                instagramPostFetcher.fetch(currentRecipe.sourceKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Instagram fetch failed for ${currentRecipe.sourceKey}", e)
                null
            }

            if (postData == null || postData.caption.isNullOrBlank()) {
                return retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_FETCH, "Couldn't read this post")
            }

            val imagePath = postData.imageUrl?.takeIf { it.isNotBlank() }
                ?.let { imageStorageManager.downloadAndSave(recipeId, it) }

            currentRecipe = currentRecipe.copy(
                captionRaw = postData.caption,
                authorHandle = postData.author ?: currentRecipe.authorHandle,
                imagePath = imagePath ?: currentRecipe.imagePath,
                status = RecipeEntity.STATUS_PENDING_EXTRACT,
                errorMessage = null,
                updatedAt = System.currentTimeMillis()
            )
            database.recipeDao().update(currentRecipe)
        }

        val apiKey = settingsStore.getApiKey()
        if (apiKey.isBlank()) {
            recipeRepository.updateRecipeStatus(
                recipeId,
                RecipeEntity.STATUS_PENDING_EXTRACT,
                "Add Anthropic API key in Settings"
            )
            return Result.failure()
        }

        val tagDao = database.tagDao()
        val tagsByKind = tagDao.getAll().groupBy { it.kind }

        val captionRaw = currentRecipe.captionRaw.orEmpty()
        val extractionInput = ExtractionInput(
            sourceType = SourceType.from(currentRecipe.sourceType),
            sourceText = InstagramPostFetcher.stripTrailingHashtags(captionRaw).ifBlank { captionRaw },
            topComment = currentRecipe.commentRaw,
            author = currentRecipe.authorHandle,
            allowedTags = tagsByKind[TagEntity.KIND_TAG].orEmpty().map { it.name },
            allowedDiet = tagsByKind[TagEntity.KIND_DIET].orEmpty().map { it.name },
            allowedEquipment = tagsByKind[TagEntity.KIND_EQUIPMENT].orEmpty().map { it.name },
            allowedConsumables = tagsByKind[TagEntity.KIND_CONSUMABLE].orEmpty().map { it.name }
        )

        val activeModel = settingsStore.modelIdFlow.first()
        val extractionResult = recipeExtractor.extract(extractionInput)
        val extracted = extractionResult.getOrElse { ex ->
            return when (ex) {
                is NoRecipeFoundException -> {
                    ex.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                    recipeRepository.updateRecipeStatus(
                        recipeId,
                        RecipeEntity.STATUS_NO_RECIPE_FOUND,
                        "No recipe in caption"
                    )
                    Result.failure()
                }
                is ApiKeyInvalidException -> {
                    recipeRepository.updateRecipeStatus(
                        recipeId,
                        RecipeEntity.STATUS_FAILED_EXTRACT,
                        "Check API key in Settings"
                    )
                    Result.failure()
                }
                is ModelNotFoundException -> {
                    recipeRepository.updateRecipeStatus(
                        recipeId,
                        RecipeEntity.STATUS_FAILED_EXTRACT,
                        "Model not found, update model ID in Settings"
                    )
                    Result.failure()
                }
                is TransientApiException ->
                    retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, "API error: ${ex.message}")
                else -> {
                    Log.w(TAG, "Extraction failed for recipe $recipeId", ex)
                    retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, ex.message ?: "Extraction failed")
                }
            }
        }

        val model = extracted.usage.model ?: activeModel
        val needsReviewRecipe = currentRecipe.copy(
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
            status = RecipeEntity.STATUS_NEEDS_REVIEW,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
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

        // Names are unique per kind only, so each list is matched against its own vocabulary.
        fun tagIds(kind: String, names: List<String>): List<Long> {
            val byName = tagsByKind[kind].orEmpty().associateBy { it.name.trim().lowercase() }
            return names.mapNotNull { byName[it.trim().lowercase()]?.id }
        }
        val matchedTagIds = (
            tagIds(TagEntity.KIND_TAG, extracted.tags) +
                tagIds(TagEntity.KIND_DIET, extracted.contains) +
                tagIds(TagEntity.KIND_EQUIPMENT, extracted.equipment) +
                tagIds(TagEntity.KIND_CONSUMABLE, extracted.consumables)
            ).distinct()

        recipeRepository.saveExtraction(
            recipe = needsReviewRecipe,
            ingredients = ingredientEntities,
            steps = stepEntities,
            tagIds = matchedTagIds,
            usage = extracted.usage.toExtractionUsageEntity(model)
        )

        return Result.success()
    }

    private suspend fun retryOrFail(recipeId: Long, failedStatus: String, message: String): Result {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        recipeRepository.updateRecipeStatus(recipeId, failedStatus, message)
        return Result.failure()
    }
}
