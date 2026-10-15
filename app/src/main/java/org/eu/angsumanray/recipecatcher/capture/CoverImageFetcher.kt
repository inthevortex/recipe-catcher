package org.eu.angsumanray.recipecatcher.capture

import android.util.Log
import kotlinx.coroutines.CancellationException
import org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher
import org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.image.ImageStorageManager
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Downloads the source's picture again for a recipe whose image is missing. */
@Singleton
class CoverImageFetcher @Inject constructor(
    private val recipeRepository: RecipeRepository,
    private val imageStorageManager: ImageStorageManager,
    private val instagramPostFetcher: InstagramPostFetcher,
    private val webRecipeFetcher: WebRecipeFetcher,
    private val commentFetcher: CommentFetcher
) {

    /** True when neither the cover photo nor the source image exists on disk. */
    fun isImageMissing(recipe: RecipeEntity): Boolean =
        listOfNotNull(recipe.coverPhotoPath, recipe.imagePath).none { path ->
            path.isNotBlank() && imageStorageManager.getFile(path).let { it.isFile && it.length() > 0 }
        }

    /** True when [recipe] has no image and comes from a source whose picture can be fetched. */
    fun canFetch(recipe: RecipeEntity): Boolean {
        val type = SourceType.from(recipe.sourceType)
        val linked = type == SourceType.INSTAGRAM || type == SourceType.WEB || type == SourceType.YOUTUBE
        return linked && (!recipe.sourceUrl.isNullOrBlank() || !recipe.sourceKey.isNullOrBlank()) && isImageMissing(recipe)
    }

    /** Fetches and stores the source's picture as the recipe image. Returns whether one was saved. */
    suspend fun fetch(recipe: RecipeEntity): Boolean {
        val candidates: List<suspend () -> String?> = when (SourceType.from(recipe.sourceType)) {
            SourceType.INSTAGRAM -> {
                val postUrl = recipe.sourceUrl ?: recipe.sourceKey?.let { UrlNormalizer.canonicalizeInstagramUrl(it) }
                val shortcode = postUrl?.let { UrlNormalizer.extractInstagramShortcode(it) } ?: recipe.sourceKey
                listOf(
                    { shortcode?.let { instagramPostFetcher.fetch(it)?.imageUrl } },
                    { postUrl?.let { commentFetcher.fetchCoverImageUrl(it) } }
                )
            }
            SourceType.WEB -> listOf({ recipe.sourceUrl?.let { webRecipeFetcher.fetch(it)?.imageUrl } })
            SourceType.YOUTUBE -> listOf({
                (recipe.sourceUrl?.let { UrlNormalizer.extractYouTubeVideoId(it) } ?: recipe.sourceKey)
                    ?.let { YouTubeFetcher.thumbnailUrlForVideoId(it) }
            })
            else -> emptyList()
        }

        for (candidate in candidates) {
            val imageUrl = try {
                candidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Image lookup failed for recipe ${recipe.id}", e)
                null
            }?.takeIf { it.isNotBlank() } ?: continue

            val path = imageStorageManager.downloadAndSave(recipe.id, imageUrl) ?: continue
            recipeRepository.updateImagePath(recipe.id, path)
            // A cover photo whose file is gone would still hide the new image.
            if (recipe.coverPhotoPath != null && recipe.coverPhotoPath != path) {
                recipeRepository.updateCoverPhotoPath(recipe.id, null)
            }
            return true
        }
        return false
    }

    private companion object {
        const val TAG = "CoverImageFetcher"
    }
}
