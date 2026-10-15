package org.eu.angsumanray.recipecatcher.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun stripTrackingParams_stripsInstagramAndUtmParams() {
        val instaUrl = "https://www.instagram.com/p/C123abc/?igsh=YWJjMTIz&utm_source=ig_web_copy_link"
        assertEquals("https://www.instagram.com/p/C123abc/", UrlNormalizer.stripTrackingParams(instaUrl))

        val webUrl = "https://example.com/recipe/pasta?id=42&utm_source=newsletter&utm_medium=email&fbclid=123"
        assertEquals("https://example.com/recipe/pasta?id=42", UrlNormalizer.stripTrackingParams(webUrl))

        val cleanUrl = "https://example.com/recipe"
        assertEquals("https://example.com/recipe", UrlNormalizer.stripTrackingParams(cleanUrl))
    }

    @Test
    fun extractInstagramShortcode_extractsFromVariousFormats() {
        // Post
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://www.instagram.com/p/C123abc/"))
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://instagram.com/p/C123abc"))

        // Reel
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://www.instagram.com/reel/C123abc/"))

        // Reels plural
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://www.instagram.com/reels/C123abc/"))

        // IGTV
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://www.instagram.com/tv/C123abc/"))

        // instagr.am short domain
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://instagr.am/p/C123abc/"))

        // With query and trailing slash
        assertEquals("C123abc", UrlNormalizer.extractInstagramShortcode("https://www.instagram.com/reel/C123abc/?igsh=test123"))

        // Not an instagram link
        assertNull(UrlNormalizer.extractInstagramShortcode("https://example.com/p/C123abc"))
    }

    @Test
    fun canonicalizeInstagramUrl_returnsStandardUrl() {
        assertEquals("https://www.instagram.com/p/C123abc/", UrlNormalizer.canonicalizeInstagramUrl("C123abc"))
        assertEquals("https://www.instagram.com/p/C123abc/c/18012345678901234/", UrlNormalizer.canonicalizeInstagramUrl("C123abc", "18012345678901234"))
    }

    @Test
    fun isInstagramPostLink_acceptsOnlyBareInstagramLinks() {
        assertTrue(UrlNormalizer.isInstagramPostLink(" https://www.instagram.com/reel/DcnveOzi-eO/?exln=cHZjcjcwYngzcWQ5 "))
        assertTrue(UrlNormalizer.isInstagramPostLink("https://www.instagram.com/p/Deb32BxCglN/c/18035225489906097/"))
        assertFalse(UrlNormalizer.isInstagramPostLink("Recipe: https://www.instagram.com/p/Deb32BxCglN/"))
        assertFalse(UrlNormalizer.isInstagramPostLink("https://example.com/p/Deb32BxCglN/"))
    }

    @Test
    fun extractInstagramCommentId_extractsFromCommentLinks() {
        assertEquals(
            "18012345678901234",
            UrlNormalizer.extractInstagramCommentId("https://www.instagram.com/p/C123abc/c/18012345678901234/")
        )
        assertEquals(
            "18012345678901234",
            UrlNormalizer.extractInstagramCommentId("https://www.instagram.com/reel/C123abc/?comment_id=18012345678901234&igsh=xyz")
        )
        assertNull(UrlNormalizer.extractInstagramCommentId("https://www.instagram.com/p/C123abc/"))
    }

    @Test
    fun extractYouTubeVideoId_extractsFromVariousFormats() {
        val id = "dQw4w9WgXcQ"

        // Watch page
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://m.youtube.com/watch?v=dQw4w9WgXcQ&feature=share"))

        // youtu.be short domain
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://youtu.be/dQw4w9WgXcQ?si=12345"))

        // Shorts
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://www.youtube.com/shorts/dQw4w9WgXcQ"))

        // Embed and /v/
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://www.youtube.com/embed/dQw4w9WgXcQ"))
        assertEquals(id, UrlNormalizer.extractYouTubeVideoId("https://www.youtube.com/v/dQw4w9WgXcQ"))

        // Not youtube
        assertNull(UrlNormalizer.extractYouTubeVideoId("https://vimeo.com/123456"))
    }

    @Test
    fun canonicalizeYouTubeUrl_returnsStandardWatchUrl() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", UrlNormalizer.canonicalizeYouTubeUrl("dQw4w9WgXcQ"))
    }

    @Test
    fun extractUrl_findsUrlInsideSharedText() {
        val text = "Check out this amazing recipe! https://www.instagram.com/reel/C123abc/?igsh=xyz for dinner tonight"
        assertEquals("https://www.instagram.com/reel/C123abc/?igsh=xyz", UrlNormalizer.extractUrl(text))

        val plainUrl = "https://example.com/recipe"
        assertEquals("https://example.com/recipe", UrlNormalizer.extractUrl(plainUrl))

        assertNull(UrlNormalizer.extractUrl("No url here just some recipe notes"))
    }

    @Test
    fun normalizeWebUrl_normalizesHostPathAndTracking() {
        val messy = "HTTP://Example.COM/recipes/Chicken-Curry/?utm_source=twitter&utm_medium=social"
        assertEquals("http://example.com/recipes/Chicken-Curry/", UrlNormalizer.normalizeWebUrl(messy))
    }

    @Test
    fun stripTrackingParams_preservesPercentEncoding() {
        assertEquals(
            "https://example.com/search?q=chicken%20curry",
            UrlNormalizer.stripTrackingParams("https://example.com/search?q=chicken%20curry&utm_source=x#top")
        )
        assertEquals("https://example.com/s?q=a%26b", UrlNormalizer.stripTrackingParams("https://example.com/s?q=a%26b"))
    }

    @Test
    fun normalizeWebUrl_preservesPercentEncodingAndDropsDefaultPort() {
        assertEquals(
            "https://example.com/a%2Fb?q=a%26b",
            UrlNormalizer.normalizeWebUrl("https://Example.com:443/a%2Fb?q=a%26b&fbclid=1")
        )
        assertEquals("https://example.com/r?x=%C3%A9", UrlNormalizer.normalizeWebUrl("https://example.com/r?x=%C3%A9"))
        assertEquals("http://example.com:8080/", UrlNormalizer.normalizeWebUrl("http://example.com:8080"))
    }

    @Test
    fun extractUrl_dropsTrailingSentencePunctuation() {
        assertEquals("https://example.com/recipe", UrlNormalizer.extractUrl("Made this: https://example.com/recipe."))
        assertEquals("https://example.com/recipe", UrlNormalizer.extractUrl("(see https://example.com/recipe)"))
    }
}
