package org.eu.angsumanray.recipecatcher.core.capture

import org.eu.angsumanray.recipecatcher.core.model.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceClassifierTest {

    @Test
    fun classify_identifiesInstagramPosts() {
        val url = "https://www.instagram.com/reel/C123abc/?igsh=tracking123"
        val result = SourceClassifier.classify(url)

        assertNotNull(result)
        assertEquals(SourceType.INSTAGRAM, result!!.sourceType)
        assertEquals("https://www.instagram.com/p/C123abc/", result.canonicalUrl)
        assertEquals("C123abc", result.sourceKey)
        assertTrue(SourceClassifier.isInstagram(url))
    }

    @Test
    fun classify_identifiesYouTubeVideos() {
        val url = "https://youtu.be/dQw4w9WgXcQ?si=tracking456"
        val result = SourceClassifier.classify(url)

        assertNotNull(result)
        assertEquals(SourceType.YOUTUBE, result!!.sourceType)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", result.canonicalUrl)
        assertEquals("dQw4w9WgXcQ", result.sourceKey)
        assertTrue(SourceClassifier.isYouTube(url))
    }

    @Test
    fun classify_identifiesWebPages() {
        val url = "https://cooking.nytimes.com/recipes/1020-paneer?utm_source=email"
        val result = SourceClassifier.classify(url)

        assertNotNull(result)
        assertEquals(SourceType.WEB, result!!.sourceType)
        assertEquals("https://cooking.nytimes.com/recipes/1020-paneer", result.canonicalUrl)
        assertEquals("https://cooking.nytimes.com/recipes/1020-paneer", result.sourceKey)
        assertTrue(SourceClassifier.isWeb(url))
    }

    @Test
    fun classify_handlesSharedTextWithUrl() {
        val sharedText = "Try this recipe: https://www.instagram.com/p/B456xyz/ It looks delicious!"
        val result = SourceClassifier.classify(sharedText)

        assertNotNull(result)
        assertEquals(SourceType.INSTAGRAM, result!!.sourceType)
        assertEquals("https://www.instagram.com/p/B456xyz/", result.canonicalUrl)
        assertEquals("B456xyz", result.sourceKey)
    }

    @Test
    fun classify_returnsNullForNonUrlText() {
        assertNull(SourceClassifier.classify("Random text without a link"))
        assertNull(SourceClassifier.classify(""))
    }
}
