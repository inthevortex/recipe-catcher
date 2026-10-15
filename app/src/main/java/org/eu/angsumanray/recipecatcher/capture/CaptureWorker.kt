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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.eu.angsumanray.recipecatcher.core.insta.InstagramComments
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.llm.ApiKeyInvalidException
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.ExtractionInput
import org.eu.angsumanray.recipecatcher.core.llm.ImageRejectedException
import org.eu.angsumanray.recipecatcher.core.llm.ModelNotFoundException
import org.eu.angsumanray.recipecatcher.core.llm.NoRecipeFoundException
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.llm.TransientApiException
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.web.JsonLdParser
import org.eu.angsumanray.recipecatcher.core.web.WebFetchBlockedException
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher
import org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher
import org.eu.angsumanray.recipecatcher.data.db.RecipeDatabase
import org.eu.angsumanray.recipecatcher.data.db.entity.IngredientEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.StepEntity
import org.eu.angsumanray.recipecatcher.data.db.entity.TagEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.data.repo.toExtractionUsageEntity
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.io.File
import java.util.concurrent.TimeUnit

@HiltWorker
class CaptureWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: RecipeDatabase,
    private val recipeRepository: RecipeRepository,
    private val instagramPostFetcher: InstagramPostFetcher,
    private val webRecipeFetcher: WebRecipeFetcher,
    private val youTubeFetcher: YouTubeFetcher,
    private val imageStorageManager: ImageStorageManager,
    private val imagePreprocessor: ImagePreprocessor,
    private val recipeExtractor: RecipeExtractor,
    private val settingsStore: SettingsStore
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_RECIPE_ID = "key_recipe_id"
        const val BLOCKED_MESSAGE = "Blocked by site. Tap Retry to load in browser or paste page text."
        private const val TAG = "CaptureWorker"
        private const val MAX_ATTEMPTS = 3
        private const val MAX_VISION_IMAGES = 5
        private const val MAX_CAROUSEL_SLIDES = 4
        private const val IMAGE_FAILED_MESSAGE = "Image processing failed or file too large"

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

        val result = capture(recipeId)
        if (result is Result.Success) deleteCachedCaptureImages(recipeId)
        return result
    }

    private suspend fun capture(recipeId: Long): Result {
        val recipe = recipeRepository.getRecipe(recipeId) ?: return Result.failure()
        if (recipe.status == RecipeEntity.STATUS_SAVED || recipe.status == RecipeEntity.STATUS_NEEDS_REVIEW) {
            return Result.success()
        }

        var currentRecipe = recipe
        val sourceType = SourceType.from(currentRecipe.sourceType)

        // Step 1: Fetch source content if captionRaw is blank (or if not yet fetched)
        if (currentRecipe.captionRaw.isNullOrBlank() && sourceType != SourceType.IMAGE && sourceType != SourceType.MANUAL) {
            when (sourceType) {
                SourceType.INSTAGRAM -> {
                    val postData = try {
                        instagramPostFetcher.fetch(currentRecipe.sourceKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Instagram fetch failed for ${currentRecipe.sourceKey}", e)
                        null
                    }

                    if (postData == null || (postData.caption.isNullOrBlank() && postData.slideImageUrls.isEmpty())) {
                        return retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_FETCH, "Couldn't read this post")
                    }

                    val imagePath = postData.imageUrl?.takeIf { it.isNotBlank() }
                        ?.let { imageStorageManager.downloadAndSave(recipeId, it) }

                    // Slides 2-5 are only worth downloading when the caption doesn't carry the recipe.
                    if (postData.slideImageUrls.isNotEmpty() &&
                        InstagramPostFetcher.hasFewerThan3QuantityLines(postData.caption)
                    ) {
                        downloadCarouselSlides(recipeId, postData.slideImageUrls)
                    }

                    currentRecipe = currentRecipe.copy(
                        captionRaw = postData.caption ?: "",
                        authorHandle = postData.author ?: currentRecipe.authorHandle,
                        imagePath = imagePath ?: currentRecipe.imagePath,
                        status = RecipeEntity.STATUS_PENDING_EXTRACT,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.recipeDao().update(currentRecipe)
                }

                SourceType.WEB -> {
                    val url = currentRecipe.sourceUrl ?: currentRecipe.sourceKey
                    val webData = try {
                        webRecipeFetcher.fetch(url)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: WebFetchBlockedException) {
                        recipeRepository.updateRecipeStatus(
                            recipeId,
                            RecipeEntity.STATUS_FAILED_FETCH,
                            BLOCKED_MESSAGE
                        )
                        return Result.failure()
                    } catch (e: Exception) {
                        Log.w(TAG, "Web fetch failed for $url", e)
                        null
                    }

                    if (webData == null || webData.sourceText.isBlank()) {
                        return retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_FETCH, "Couldn't read this website")
                    }

                    val imagePath = webData.imageUrl?.takeIf { it.isNotBlank() }
                        ?.let { imageStorageManager.downloadAndSave(recipeId, it) }

                    currentRecipe = currentRecipe.copy(
                        title = webData.title ?: currentRecipe.title,
                        captionRaw = webData.sourceText,
                        authorHandle = webData.author ?: currentRecipe.authorHandle,
                        imagePath = imagePath ?: currentRecipe.imagePath,
                        status = RecipeEntity.STATUS_PENDING_EXTRACT,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.recipeDao().update(currentRecipe)
                }

                SourceType.YOUTUBE -> {
                    val ytData = try {
                        youTubeFetcher.fetch(currentRecipe.sourceKey)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "YouTube fetch failed for ${currentRecipe.sourceKey}", e)
                        null
                    }

                    if (ytData == null) {
                        return retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_FETCH, "Couldn't read this YouTube video")
                    }

                    var rawText = ytData.description
                    val trackUrl = ytData.preferredCaptionTrackUrl
                    // When YouTube description has no recipe, check for captionTracks / preferredCaptionTrackUrl
                    if (rawText.isNullOrBlank() && trackUrl != null) {
                        val transcript = try {
                            youTubeFetcher.fetchCaptionTranscript(trackUrl)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        if (!transcript.isNullOrBlank()) {
                            rawText = transcript
                        }
                    }

                    if (rawText.isNullOrBlank()) {
                        return retryOrFail(recipeId, RecipeEntity.STATUS_FAILED_FETCH, "Couldn't read this YouTube video")
                    }

                    val imagePath = ytData.thumbnailUrl.takeIf { it.isNotBlank() }
                        ?.let { imageStorageManager.downloadAndSave(recipeId, it) }

                    currentRecipe = currentRecipe.copy(
                        title = ytData.title ?: currentRecipe.title,
                        captionRaw = rawText,
                        authorHandle = ytData.author ?: currentRecipe.authorHandle,
                        imagePath = imagePath ?: currentRecipe.imagePath,
                        status = RecipeEntity.STATUS_PENDING_EXTRACT,
                        errorMessage = null,
                        updatedAt = System.currentTimeMillis()
                    )
                    database.recipeDao().update(currentRecipe)
                }

                SourceType.IMAGE, SourceType.MANUAL -> {
                    // No remote fetch needed
                }
            }
        }

        val tagDao = database.tagDao()
        val tagsByKind = tagDao.getAll().groupBy { it.kind }
        val activeModel = settingsStore.modelIdFlow.first()

        // Check for direct mapping fallback (Web with JSON-LD)
        val directMapping = if (sourceType == SourceType.WEB) {
            extractDirectMappingIfJsonLd(currentRecipe.captionRaw)
        } else null

        val apiKey = settingsStore.getApiKey()
        if (apiKey.isBlank()) {
            if (directMapping != null) {
                applyAndSaveExtraction(currentRecipe, directMapping, activeModel, tagsByKind)
                return Result.success()
            }
            recipeRepository.updateRecipeStatus(
                recipeId,
                RecipeEntity.STATUS_PENDING_EXTRACT,
                "Add Anthropic API key in Settings"
            )
            return Result.failure()
        }

        // Prepare multimodal images for SourceType.IMAGE or Instagram carousel slides
        val sourceImages = mutableListOf<String>()

        if (sourceType == SourceType.IMAGE) {
            val imageFiles = resolveSourceImages(currentRecipe).take(MAX_VISION_IMAGES)
            if (imageFiles.isEmpty()) {
                recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, IMAGE_FAILED_MESSAGE)
                return Result.failure()
            }

            val processedImages = mutableListOf<ProcessedImage>()
            for (file in imageFiles) {
                val processed = imagePreprocessor.processFile(file)
                if (processed != null) {
                    processedImages.add(processed)
                }
            }

            if (processedImages.isEmpty()) {
                recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, IMAGE_FAILED_MESSAGE)
                return Result.failure()
            }

            // Save primary photo as the recipe cover photo in filesDir/images/<recipeId>.jpg
            val primary = processedImages.first()
            val coverPath = imagePreprocessor.saveAsRecipeCover(primary, recipeId)
            currentRecipe = currentRecipe.copy(
                imagePath = coverPath,
                coverPhotoPath = coverPath
            )
            database.recipeDao().update(currentRecipe)

            sourceImages.addAll(processedImages.map { it.base64 })
        } else if (sourceType == SourceType.INSTAGRAM) {
            val caption = currentRecipe.captionRaw.orEmpty()
            val lacksQuantities = InstagramPostFetcher.hasFewerThan3QuantityLines(caption)
            if (lacksQuantities) {
                sourceImages.addAll(processCachedSlides(recipeId))
            }
        }

        val captionRaw = currentRecipe.captionRaw.orEmpty()
        val extractionInput = ExtractionInput(
            sourceType = sourceType,
            sourceText = if (sourceType == SourceType.INSTAGRAM) {
                InstagramPostFetcher.stripTrailingHashtags(captionRaw).ifBlank { captionRaw }
            } else {
                captionRaw
            },
            sourceImages = sourceImages,
            topComment = currentRecipe.commentRaw?.takeIf { InstagramComments.looksLikeCommentText(it) },
            author = currentRecipe.authorHandle,
            allowedTags = tagsByKind[TagEntity.KIND_TAG].orEmpty().map { it.name },
            allowedDiet = tagsByKind[TagEntity.KIND_DIET].orEmpty().map { it.name },
            allowedEquipment = tagsByKind[TagEntity.KIND_EQUIPMENT].orEmpty().map { it.name },
            allowedConsumables = tagsByKind[TagEntity.KIND_CONSUMABLE].orEmpty().map { it.name }
        )

        val extractionResult = recipeExtractor.extract(extractionInput)
        val extracted = extractionResult.getOrElse { ex ->
            // If LLM fails but JSON-LD exists on web: use direct mapping fallback!
            if (directMapping != null) {
                applyAndSaveExtraction(currentRecipe, directMapping, activeModel, tagsByKind)
                return Result.success()
            }

            return when (ex) {
                is NoRecipeFoundException -> {
                    ex.usage?.let { recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel)) }
                    if (tryNoRecipeFallbacks(currentRecipe, sourceType, sourceImages.isNotEmpty(), activeModel, tagsByKind)) {
                        return Result.success()
                    }
                    recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NO_RECIPE_FOUND, noRecipeMessage(sourceType))
                    Result.failure()
                }
                is ImageRejectedException -> {
                    Log.w(TAG, "Image rejected for recipe $recipeId", ex)
                    recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_FAILED_EXTRACT, IMAGE_FAILED_MESSAGE)
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

        if (!extracted.isRecipe) {
            if (tryNoRecipeFallbacks(currentRecipe, sourceType, sourceImages.isNotEmpty(), activeModel, tagsByKind)) {
                return Result.success()
            }
            recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_NO_RECIPE_FOUND, noRecipeMessage(sourceType))
            return Result.failure()
        }

        applyAndSaveExtraction(currentRecipe, extracted, activeModel, tagsByKind)
        return Result.success()
    }

    /**
     * Second chances after the main extraction found no recipe: carousel slides for Instagram (unless they were
     * already sent), then for YouTube the linked recipe page before the caption transcript.
     */
    private suspend fun tryNoRecipeFallbacks(
        recipe: RecipeEntity,
        sourceType: SourceType,
        imagesAlreadySent: Boolean,
        activeModel: String,
        tagsByKind: Map<String, List<TagEntity>>
    ): Boolean = when (sourceType) {
        SourceType.INSTAGRAM -> !imagesAlreadySent && tryInstagramCarouselFallback(recipe, activeModel, tagsByKind)
        SourceType.YOUTUBE -> tryLinkedPageFallback(recipe, activeModel, tagsByKind) ||
            tryYouTubeCaptionFallback(recipe, activeModel, tagsByKind)
        else -> false
    }

    private fun noRecipeMessage(sourceType: SourceType): String = when (sourceType) {
        SourceType.YOUTUBE -> "No recipe in description or captions"
        SourceType.IMAGE -> "No recipe in photos"
        else -> "No recipe in caption"
    }

    private fun slidesDir(recipeId: Long) = File(applicationContext.cacheDir, "slides/$recipeId")

    private suspend fun downloadCarouselSlides(recipeId: Long, slideUrls: List<String>) {
        val dir = slidesDir(recipeId).apply { mkdirs() }
        slideUrls.take(MAX_CAROUSEL_SLIDES).forEachIndexed { idx, slideUrl ->
            val bytes = imageStorageManager.downloadBytes(slideUrl) ?: return@forEachIndexed
            try {
                File(dir, "slide_${idx + 2}.jpg").writeBytes(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "Could not cache carousel slide ${idx + 2} for recipe $recipeId", e)
            }
        }
    }

    private suspend fun processCachedSlides(recipeId: Long): List<String> {
        val slideFiles = slidesDir(recipeId).listFiles()
            ?.filter { it.isFile && it.length() > 0 }
            ?.sortedBy { it.name }
            .orEmpty()
        return slideFiles.take(MAX_CAROUSEL_SLIDES).mapNotNull { imagePreprocessor.processFile(it)?.base64 }
    }

    /** Shared photos and carousel slides are only needed until extraction succeeds; the cover lives in filesDir. */
    private suspend fun deleteCachedCaptureImages(recipeId: Long) {
        slidesDir(recipeId).deleteRecursively()
        val recipe = recipeRepository.getRecipe(recipeId) ?: return
        if (SourceType.from(recipe.sourceType) == SourceType.IMAGE) {
            File(applicationContext.cacheDir, "image_captures/${recipe.sourceKey}").deleteRecursively()
        }
    }

    private fun resolveSourceImages(recipe: RecipeEntity): List<File> {
        val files = mutableListOf<File>()

        val sourceUrl = recipe.sourceUrl
        if (!sourceUrl.isNullOrBlank()) {
            val candidate = File(sourceUrl)
            if (candidate.isDirectory) {
                candidate.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }?.let {
                    files.addAll(it)
                }
            } else if (candidate.isFile && candidate.length() > 0) {
                files.add(candidate)
            } else if (sourceUrl.contains("|")) {
                sourceUrl.split("|").forEach { path ->
                    val f = File(path)
                    if (f.isFile && f.length() > 0) files.add(f)
                }
            }
        }

        if (files.isEmpty()) {
            val dirByKey = File(applicationContext.cacheDir, "image_captures/${recipe.sourceKey}")
            if (dirByKey.isDirectory) {
                dirByKey.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }?.let {
                    files.addAll(it)
                }
            }
        }

        if (files.isEmpty()) {
            val dirById = File(applicationContext.cacheDir, "images/${recipe.id}")
            if (dirById.isDirectory) {
                dirById.listFiles()?.filter { it.isFile && it.length() > 0 }?.sortedBy { it.name }?.let {
                    files.addAll(it)
                }
            }
        }

        if (files.isEmpty() && !recipe.imagePath.isNullOrBlank()) {
            val f = imageStorageManager.getFile(recipe.imagePath)
            if (f.isFile && f.length() > 0) files.add(f)
        }

        return files
    }

    private suspend fun tryInstagramCarouselFallback(
        recipe: RecipeEntity,
        activeModel: String,
        tagsByKind: Map<String, List<TagEntity>>
    ): Boolean {
        val slideImages = processCachedSlides(recipe.id)
        if (slideImages.isEmpty()) return false

        val carouselInput = ExtractionInput(
            sourceType = SourceType.INSTAGRAM,
            sourceText = recipe.captionRaw.orEmpty(),
            sourceImages = slideImages,
            author = recipe.authorHandle,
            allowedTags = tagsByKind[TagEntity.KIND_TAG].orEmpty().map { it.name },
            allowedDiet = tagsByKind[TagEntity.KIND_DIET].orEmpty().map { it.name },
            allowedEquipment = tagsByKind[TagEntity.KIND_EQUIPMENT].orEmpty().map { it.name },
            allowedConsumables = tagsByKind[TagEntity.KIND_CONSUMABLE].orEmpty().map { it.name }
        )

        val extraction = recipeExtractor.extract(carouselInput)
        (extraction.exceptionOrNull() as? NoRecipeFoundException)?.usage?.let {
            recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel))
        }
        val result = extraction.getOrNull()
        if (result != null && result.isRecipe) {
            applyAndSaveExtraction(recipe, result, activeModel, tagsByKind)
            return true
        }
        return false
    }

    private suspend fun tryYouTubeCaptionFallback(
        recipe: RecipeEntity,
        activeModel: String,
        tagsByKind: Map<String, List<TagEntity>>
    ): Boolean {
        val ytData = try {
            youTubeFetcher.fetch(recipe.sourceKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return false

        val trackUrl = ytData.preferredCaptionTrackUrl ?: return false
        val transcript = youTubeFetcher.fetchCaptionTranscript(trackUrl) ?: return false
        // A blank description already sent the transcript through the main extraction.
        if (transcript.isBlank() || transcript == recipe.captionRaw) return false

        val captionInput = ExtractionInput(
            sourceType = SourceType.YOUTUBE,
            sourceText = transcript,
            author = ytData.author ?: recipe.authorHandle,
            allowedTags = tagsByKind[TagEntity.KIND_TAG].orEmpty().map { it.name },
            allowedDiet = tagsByKind[TagEntity.KIND_DIET].orEmpty().map { it.name },
            allowedEquipment = tagsByKind[TagEntity.KIND_EQUIPMENT].orEmpty().map { it.name },
            allowedConsumables = tagsByKind[TagEntity.KIND_CONSUMABLE].orEmpty().map { it.name }
        )

        val extraction = recipeExtractor.extract(captionInput)
        (extraction.exceptionOrNull() as? NoRecipeFoundException)?.usage?.let {
            recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel))
        }
        val result = extraction.getOrNull()
        if (result != null && result.isRecipe) {
            applyAndSaveExtraction(recipe.copy(captionRaw = transcript), result, activeModel, tagsByKind)
            return true
        }
        return false
    }

    private suspend fun tryLinkedPageFallback(
        recipe: RecipeEntity,
        activeModel: String,
        tagsByKind: Map<String, List<TagEntity>>
    ): Boolean {
        val description = recipe.captionRaw.orEmpty()
        val candidateLinks = YouTubeFetcher.findCandidateRecipeLinks(description, maxLinks = 2)
        for (link in candidateLinks) {
            try {
                val webData = webRecipeFetcher.fetch(link) ?: continue
                if (webData.sourceText.isBlank()) continue

                val linkedInput = ExtractionInput(
                    sourceType = SourceType.WEB,
                    sourceText = webData.sourceText,
                    author = webData.author,
                    allowedTags = tagsByKind[TagEntity.KIND_TAG].orEmpty().map { it.name },
                    allowedDiet = tagsByKind[TagEntity.KIND_DIET].orEmpty().map { it.name },
                    allowedEquipment = tagsByKind[TagEntity.KIND_EQUIPMENT].orEmpty().map { it.name },
                    allowedConsumables = tagsByKind[TagEntity.KIND_CONSUMABLE].orEmpty().map { it.name }
                )

                val extraction = recipeExtractor.extract(linkedInput)
                (extraction.exceptionOrNull() as? NoRecipeFoundException)?.usage?.let {
                    recipeRepository.logUsage(it.toExtractionUsageEntity(activeModel))
                }
                val result = extraction.getOrNull()
                val targetRecipe = if (result != null && result.isRecipe) {
                    result
                } else {
                    webData.directMapping
                }

                if (targetRecipe != null && targetRecipe.isRecipe) {
                    // The linked page is what the recipe came from, so re-extract reads it too;
                    // source_url stays the video.
                    applyAndSaveExtraction(recipe.copy(captionRaw = webData.sourceText), targetRecipe, activeModel, tagsByKind)
                    return true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Linked page fallback failed for link $link", e)
            }
        }
        return false
    }

    private suspend fun applyAndSaveExtraction(
        currentRecipe: RecipeEntity,
        extracted: ExtractedRecipe,
        activeModel: String,
        tagsByKind: Map<String, List<TagEntity>>
    ) {
        val model = extracted.usage.model ?: activeModel
        val recipeId = currentRecipe.id
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
    }

    private fun extractDirectMappingIfJsonLd(text: String?): ExtractedRecipe? {
        if (text.isNullOrBlank()) return null
        return try {
            val element = Json.parseToJsonElement(text)
            if (element is JsonObject) {
                JsonLdParser.parseRecipeObject(element).directMapping
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun retryOrFail(recipeId: Long, failedStatus: String, message: String): Result {
        if (runAttemptCount + 1 < MAX_ATTEMPTS) return Result.retry()
        recipeRepository.updateRecipeStatus(recipeId, failedStatus, message)
        return Result.failure()
    }
}
