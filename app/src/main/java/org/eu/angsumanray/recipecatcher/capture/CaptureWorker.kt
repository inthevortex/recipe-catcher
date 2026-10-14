package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
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
import org.eu.angsumanray.recipecatcher.data.db.entity.LlmUsageEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeTagEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
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
        if (recipe.status == RecipeEntity.STATUS_SAVED) return Result.success()

        val recipeDao = database.recipeDao()
        val ingredientDao = database.ingredientDao()
        val stepDao = database.stepDao()
        val tagDao = database.tagDao()
        val recipeTagDao = database.recipeTagDao()
        val llmUsageDao = database.llmUsageDao()

        // Phase 1: Fetch post if needed
        var currentRecipe = recipe
        if (currentRecipe.captionRaw.isNullOrBlank()) {
            val postData = try {
                instagramPostFetcher.fetch(currentRecipe.sourceKey)
            } catch (e: Exception) {
                null
            }

            if (postData == null || postData.caption.isNullOrBlank()) {
                return if (runAttemptCount < 3) {
                    Result.retry()
                } else {
                    recipeRepository.updateRecipeStatus(
                        recipeId,
                        RecipeEntity.STATUS_FAILED_FETCH,
                        "Couldn't read this post"
                    )
                    Result.failure()
                }
            }

            var imagePath = currentRecipe.imagePath
            val postImageUrl = postData.imageUrl
            if (!postImageUrl.isNullOrBlank()) {
                imagePath = imageStorageManager.downloadAndSave(recipeId, postImageUrl)
            }

            currentRecipe = currentRecipe.copy(
                captionRaw = postData.caption,
                authorHandle = postData.author ?: currentRecipe.authorHandle,
                imagePath = imagePath ?: currentRecipe.imagePath,
                status = RecipeEntity.STATUS_PENDING_EXTRACT,
                updatedAt = System.currentTimeMillis()
            )
            recipeDao.update(currentRecipe)
        }

        // Phase 2: Extract recipe with Claude
        val apiKey = settingsStore.getApiKey()
        if (apiKey.isBlank()) {
            recipeRepository.updateRecipeStatus(
                recipeId,
                RecipeEntity.STATUS_PENDING_EXTRACT,
                "Add Anthropic API key in Settings"
            )
            return Result.failure()
        }

        val allowedTags = tagDao.getByKind(TagEntity.KIND_TAG).map { it.name }
        val allowedDiet = tagDao.getByKind(TagEntity.KIND_DIET).map { it.name }
        val allowedEquipment = tagDao.getByKind(TagEntity.KIND_EQUIPMENT).map { it.name }
        val allowedConsumables = tagDao.getByKind(TagEntity.KIND_CONSUMABLE).map { it.name }

        val extractionInput = ExtractionInput(
            sourceType = SourceType.INSTAGRAM,
            sourceText = currentRecipe.captionRaw.orEmpty(),
            topComment = currentRecipe.commentRaw,
            author = currentRecipe.authorHandle,
            allowedTags = allowedTags,
            allowedDiet = allowedDiet,
            allowedEquipment = allowedEquipment,
            allowedConsumables = allowedConsumables
        )

        val extractionResult = recipeExtractor.extract(extractionInput)
        if (extractionResult.isFailure) {
            val ex = extractionResult.exceptionOrNull()
            return when (ex) {
                is NoRecipeFoundException -> {
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
                is TransientApiException -> {
                    if (runAttemptCount < 3) {
                        Result.retry()
                    } else {
                        recipeRepository.updateRecipeStatus(
                            recipeId,
                            RecipeEntity.STATUS_FAILED_EXTRACT,
                            "API error: ${ex.message}"
                        )
                        Result.failure()
                    }
                }
                else -> {
                    if (runAttemptCount < 2) {
                        Result.retry()
                    } else {
                        recipeRepository.updateRecipeStatus(
                            recipeId,
                            RecipeEntity.STATUS_FAILED_EXTRACT,
                            ex?.message ?: "Extraction failed"
                        )
                        Result.failure()
                    }
                }
            }
        }

        val extracted = extractionResult.getOrThrow()

        // Log usage
        val activeModel = settingsStore.modelIdFlow.first()
        llmUsageDao.insert(
            LlmUsageEntity(
                purpose = LlmUsageEntity.PURPOSE_EXTRACTION,
                model = activeModel,
                inputTokens = extracted.usage.inputTokens,
                outputTokens = extracted.usage.outputTokens,
                cacheReadTokens = extracted.usage.cacheReadTokens
            )
        )

        // Update recipe row to needs_review
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
            llmModel = activeModel,
            status = RecipeEntity.STATUS_NEEDS_REVIEW,
            errorMessage = null,
            updatedAt = System.currentTimeMillis()
        )
        recipeDao.update(needsReviewRecipe)

        // Insert ingredients
        val ingredientEntities = extracted.ingredientGroups.flatMapIndexed { groupIdx, group ->
            group.items.mapIndexed { itemIdx, item ->
                IngredientEntity(
                    recipeId = recipeId,
                    position = groupIdx * 100 + itemIdx,
                    groupName = group.name,
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
        }
        ingredientDao.deleteByRecipeId(recipeId)
        ingredientDao.insertAll(ingredientEntities)

        // Insert steps
        val stepEntities = extracted.steps.mapIndexed { idx, step ->
            StepEntity(
                recipeId = recipeId,
                position = idx,
                text = step.text,
                timerSeconds = step.timerSeconds
            )
        }
        stepDao.deleteByRecipeId(recipeId)
        stepDao.insertAll(stepEntities)

        // Match tags and recipe_tag
        val allDbTags = tagDao.getAll().associateBy { it.name.lowercase().trim() }
        val extractedTagNames = (extracted.tags + extracted.contains + extracted.equipment + extracted.consumables)
        val matchedTagIds = extractedTagNames.mapNotNull { allDbTags[it.lowercase().trim()]?.id }.distinct()
        val recipeTags = matchedTagIds.map {
            RecipeTagEntity(recipeId = recipeId, tagId = it, source = RecipeTagEntity.SOURCE_AI)
        }
        recipeTagDao.deleteByRecipeId(recipeId)
        recipeTagDao.insertAll(recipeTags)

        return Result.success()
    }
}
