package org.eu.angsumanray.recipecatcher.core.youtube

import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeFetcherTest {

    private fun readFixture(filename: String): String {
        val stream = javaClass.getResourceAsStream("/youtube/$filename")
            ?: throw IllegalArgumentException("Fixture /youtube/$filename not found")
        return stream.bufferedReader().use { it.readText() }
    }

    private fun mockHttpFetcher(responseMap: Map<String, HttpResponseData>): HttpFetcher {
        return object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
                return responseMap[url] ?: HttpResponseData(404, "Not Found")
            }
        }
    }

    @Test
    fun `fixture 1 parses ytInitialPlayerResponse JSON videoDetails`() = runBlocking {
        val html = readFixture("youtube_watch_initial_player_response.html")
        val videoId = "abc123xyz"
        val url = "https://www.youtube.com/watch?v=$videoId"
        val fetcher = YouTubeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch("https://youtu.be/$videoId")
        assertNotNull(data)
        checkNotNull(data)

        assertTrue(data.isPlayerResponse)
        assertEquals(videoId, data.videoId)
        assertEquals("Quick Creamy Tomato Pasta", data.title)
        assertEquals("Chef John", data.author)
        assertTrue(data.description?.contains("200g rigatoni pasta") == true)
        assertEquals("https://i.ytimg.com/vi/abc123xyz/hqdefault.jpg", data.thumbnailUrl)
    }

    @Test
    fun `fixture 2 meta tag fallback works for shorts with no player response`() = runBlocking {
        val html = readFixture("youtube_shorts_meta_fallback.html")
        val videoId = "short123"
        val url = "https://www.youtube.com/watch?v=$videoId"
        val fetcher = YouTubeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch("https://www.youtube.com/shorts/$videoId")
        assertNotNull(data)
        checkNotNull(data)

        assertFalse(data.isPlayerResponse)
        assertEquals(videoId, data.videoId)
        assertEquals("Viral Crispy Potatoes #shorts", data.title)
        assertEquals("Crispy King", data.author)
        assertTrue(data.description?.contains("2 lbs potatoes") == true)
        assertEquals("https://i.ytimg.com/vi/short123/hqdefault.jpg", data.thumbnailUrl)
    }

    @Test
    fun `fixture 3 extracts non-social links from description for linked-page fallback`() = runBlocking {
        val html = readFixture("youtube_with_description_links.html")
        val videoId = "garlic789"
        val url = "https://www.youtube.com/watch?v=$videoId"
        val fetcher = YouTubeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch(videoId)
        assertNotNull(data)
        checkNotNull(data)

        val description = data.description.orEmpty()
        val candidateLinks = YouTubeFetcher.findCandidateRecipeLinks(description, maxLinks = 2)

        assertEquals(2, candidateLinks.size)
        assertEquals("https://myfoodblog.com/garlic-noodles", candidateLinks[0])
        assertEquals("https://altblog.org/noodle-variations", candidateLinks[1])

        // Verify social/shop links were filtered out
        assertFalse(candidateLinks.any { it.contains("instagram.com") })
        assertFalse(candidateLinks.any { it.contains("tiktok.com") })
        assertFalse(candidateLinks.any { it.contains("amazon.com") })
    }

    @Test
    fun `extracts videoId from different YouTube URL variants`() {
        assertEquals("abcdef12345", org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer.extractYouTubeVideoId("https://www.youtube.com/watch?v=abcdef12345"))
        assertEquals("abcdef12345", org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer.extractYouTubeVideoId("https://youtu.be/abcdef12345"))
        assertEquals("abcdef12345", org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer.extractYouTubeVideoId("https://www.youtube.com/shorts/abcdef12345"))
        assertEquals("abcdef12345", org.eu.angsumanray.recipecatcher.core.capture.UrlNormalizer.extractYouTubeVideoId("https://m.youtube.com/watch?v=abcdef12345&feature=share"))
    }
}
