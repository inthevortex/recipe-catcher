package org.eu.angsumanray.recipecatcher.core.capture

import org.eu.angsumanray.recipecatcher.core.model.SourceType

data class ClassifiedSource(
    val sourceType: SourceType,
    val canonicalUrl: String,
    val sourceKey: String
)

object SourceClassifier {

    /**
     * Classifies a raw URL or text containing a URL into a ClassifiedSource.
     * Extracts the URL if found in text, strips tracking parameters, and canonicalizes.
     */
    fun classify(urlOrText: String): ClassifiedSource? {
        val rawUrl = UrlNormalizer.extractUrl(urlOrText)
            ?: urlOrText.trim().takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
            ?: return null

        val stripped = UrlNormalizer.stripTrackingParams(rawUrl)

        // 1. Instagram check
        val instaCode = UrlNormalizer.extractInstagramShortcode(stripped)
        if (instaCode != null) {
            return ClassifiedSource(
                sourceType = SourceType.INSTAGRAM,
                canonicalUrl = UrlNormalizer.canonicalizeInstagramUrl(instaCode),
                sourceKey = instaCode
            )
        }

        // 2. YouTube check
        val ytId = UrlNormalizer.extractYouTubeVideoId(stripped)
        if (ytId != null) {
            return ClassifiedSource(
                sourceType = SourceType.YOUTUBE,
                canonicalUrl = UrlNormalizer.canonicalizeYouTubeUrl(ytId),
                sourceKey = ytId
            )
        }

        // 3. Web check
        val normalizedWeb = UrlNormalizer.normalizeWebUrl(stripped)
        return ClassifiedSource(
            sourceType = SourceType.WEB,
            canonicalUrl = normalizedWeb,
            sourceKey = normalizedWeb
        )
    }

    fun isInstagram(urlOrText: String): Boolean =
        classify(urlOrText)?.sourceType == SourceType.INSTAGRAM

    fun isYouTube(urlOrText: String): Boolean =
        classify(urlOrText)?.sourceType == SourceType.YOUTUBE

    fun isWeb(urlOrText: String): Boolean =
        classify(urlOrText)?.sourceType == SourceType.WEB
}
