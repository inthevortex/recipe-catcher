package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstagramFetchStrategiesTest {

    private fun loadResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Resource not found: $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private class FakeHttpFetcher(
        private val handler: (url: String) -> HttpResponseData
    ) : HttpFetcher {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
            return handler(url)
        }
    }

    @Test
    fun `OEmbedFetchStrategy parses oEmbed json fixture`() = runBlocking {
        val jsonFixture = loadResource("insta/oembed_sample.json")
        val fetcher = FakeHttpFetcher { url ->
            assertTrue(url.contains("graph.facebook.com"))
            HttpResponseData(200, jsonFixture)
        }
        val strategy = OEmbedFetchStrategy(fetcher)
        val result = strategy.fetch("C1234567890")

        assertNotNull(result)
        assertEquals("indianculinary", result?.author)
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-15/thumb1.jpg", result?.imageUrl)
        assertTrue(result?.caption?.contains("Paneer Butter Masala") == true)
        assertTrue(result?.caption?.contains("200g Paneer") == true)
    }

    @Test
    fun `EmbedPageFetchStrategy parses embed html fixture`() = runBlocking {
        val htmlFixture = loadResource("insta/embed_captioned_sample.html")
        val fetcher = FakeHttpFetcher { url ->
            assertTrue(url.contains("/embed/captioned/"))
            HttpResponseData(200, htmlFixture)
        }
        val strategy = EmbedPageFetchStrategy(fetcher)
        val result = strategy.fetch("C1234567890")

        assertNotNull(result)
        assertEquals("chefpaneer", result?.author)
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-15/embed_thumb.jpg", result?.imageUrl)
        assertTrue(result?.caption?.contains("Quick Kadai Paneer in 20 minutes!") == true)
        assertTrue(result?.caption?.contains("250g paneer cubes") == true)
    }

    @Test
    fun `PostPageMetaFetchStrategy parses og meta tags and strips prefix`() = runBlocking {
        val htmlFixture = loadResource("insta/post_page_meta_sample.html")
        val fetcher = FakeHttpFetcher { url ->
            assertTrue(url.endsWith("/p/C1234567890/"))
            HttpResponseData(200, htmlFixture)
        }
        val strategy = PostPageMetaFetchStrategy(fetcher)
        val result = strategy.fetch("C1234567890")

        assertNotNull(result)
        assertEquals("ananyacooks", result?.author)
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-15/meta_cover.jpg", result?.imageUrl)
        assertTrue(result?.caption?.startsWith("Authentic Kolkata Chicken Biryani!") == true)
        assertTrue(result?.caption?.contains("Cook on dum for 25 mins.") == true)
    }

    @Test
    fun `InstagramPostFetcher orchestrator stops at first caption and cleans hashtags`() = runBlocking {
        val strat1 = object : PostFetchStrategy {
            override val name: String = "Dummy1"
            override suspend fun fetch(shortcode: String): PostData {
                return PostData(
                    caption = "Great pasta recipe!\nIngredients: 100g pasta, garlic.\n\n#food #yummy\n#pasta",
                    author = "pastachef",
                    imageUrl = null
                )
            }
        }
        val strat2 = object : PostFetchStrategy {
            override val name: String = "Dummy2"
            override suspend fun fetch(shortcode: String): PostData {
                return PostData(
                    caption = "Other caption",
                    author = "otherchef",
                    imageUrl = "https://example.com/pasta.jpg"
                )
            }
        }

        val orchestrator = InstagramPostFetcher(listOf(strat1, strat2))
        val post = orchestrator.fetch("xyz")

        assertNotNull(post)
        assertEquals("pastachef", post?.author)
        assertEquals("https://example.com/pasta.jpg", post?.imageUrl)
        val expectedCaption = "Great pasta recipe!\nIngredients: 100g pasta, garlic."
        assertEquals(expectedCaption, post?.caption)
    }
}
