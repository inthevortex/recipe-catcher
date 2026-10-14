package org.eu.angsumanray.recipecatcher.core.insta

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InstagramFetchStrategiesTest {

    private fun loadResource(path: String): String {
        val stream = javaClass.classLoader.getResourceAsStream(path)
            ?: throw IllegalArgumentException("Resource not found: $path")
        return stream.bufferedReader().use { it.readText() }
    }

    private class FakeHttpFetcher(
        private val handler: (url: String, headers: Map<String, String>) -> HttpResponseData
    ) : HttpFetcher {
        override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
            return handler(url, headers)
        }
    }

    private fun strategy(name: String, data: PostData?): PostFetchStrategy = object : PostFetchStrategy {
        override val name: String = name
        override suspend fun fetch(shortcode: String): PostData? = data
    }

    @Test
    fun `OEmbedFetchStrategy parses captioned embed html with line breaks`() = runBlocking {
        val jsonFixture = loadResource("insta/oembed_sample.json")
        val fetcher = FakeHttpFetcher { url, _ ->
            assertTrue(url.contains("graph.facebook.com"))
            HttpResponseData(200, jsonFixture)
        }
        val result = OEmbedFetchStrategy(fetcher).fetch("C1234567890")

        assertNotNull(result)
        assertEquals("indianculinary", result?.author)
        assertEquals("https://scontent.cdninstagram.com/v/t51.2885-15/thumb1.jpg", result?.imageUrl)
        val caption = result?.caption.orEmpty()
        assertTrue(caption.startsWith("Creamy restaurant-style Paneer Butter Masala recipe!"))
        assertTrue(caption.contains("Ingredients:\n- 200g Paneer\n- 2 tbsp Butter"))
        assertTrue(caption.contains("2. Add tomato puree & spices."))
        assertFalse(caption.contains("A post shared by"))
        assertFalse(caption.contains("View this post"))
    }

    @Test
    fun `OEmbedFetchStrategy falls back to title when embed html carries no caption`() = runBlocking {
        val jsonFixture = loadResource("insta/oembed_title_only.json")
        val result = OEmbedFetchStrategy(FakeHttpFetcher { _, _ -> HttpResponseData(200, jsonFixture) })
            .fetch("C1234567890")

        assertNotNull(result)
        val caption = result?.caption.orEmpty()
        assertTrue(caption.startsWith("Masala Chai at home"))
        assertTrue(caption.contains("\n2 cups water\n1 cup milk\n"))
        assertFalse(caption.contains("View this post"))
        assertNull(result?.author)
        assertNull(result?.imageUrl)
    }

    @Test
    fun `OEmbedFetchStrategy returns null on HTTP error or bad json`() = runBlocking {
        assertNull(OEmbedFetchStrategy(FakeHttpFetcher { _, _ -> HttpResponseData(400, "{}") }).fetch("x"))
        assertNull(OEmbedFetchStrategy(FakeHttpFetcher { _, _ -> HttpResponseData(200, "<html>") }).fetch("x"))
        assertNull(OEmbedFetchStrategy(FakeHttpFetcher { _, _ -> throw java.io.IOException("offline") }).fetch("x"))
    }

    @Test
    fun `EmbedPageFetchStrategy parses embed html fixture with mobile user agent`() = runBlocking {
        val htmlFixture = loadResource("insta/embed_captioned_sample.html")
        val fetcher = FakeHttpFetcher { url, headers ->
            assertTrue(url.endsWith("/p/C1234567890/embed/captioned/"))
            assertTrue(headers["User-Agent"].orEmpty().contains("Mobile"))
            HttpResponseData(200, htmlFixture)
        }
        val result = EmbedPageFetchStrategy(fetcher).fetch("C1234567890")

        assertNotNull(result)
        assertEquals("chefpaneer", result?.author)
        assertEquals(
            "https://scontent.cdninstagram.com/v/t51.2885-15/embed_thumb.jpg?stp=dst-jpg_e35&_nc_ht=scontent.cdninstagram.com",
            result?.imageUrl
        )
        assertEquals(
            "Quick Kadai Paneer in 20 minutes!\n\n" +
                "Ingredients:\n- 250g paneer cubes\n- 1 bell pepper diced\n- 1 tbsp kadai masala\n- Salt & pepper\n\n" +
                "Toss together in a hot kadai for 5 minutes. Enjoy with roti!\n\n" +
                "#food #recipe #paneer",
            result?.caption
        )
    }

    @Test
    fun `PostPageMetaFetchStrategy parses og meta tags and strips prefix and quotes`() = runBlocking {
        val htmlFixture = loadResource("insta/post_page_meta_sample.html")
        val fetcher = FakeHttpFetcher { url, headers ->
            assertTrue(url.endsWith("/p/C1234567890/"))
            assertTrue(headers["User-Agent"].orEmpty().contains("Mobile"))
            HttpResponseData(200, htmlFixture)
        }
        val result = PostPageMetaFetchStrategy(fetcher).fetch("C1234567890")

        assertNotNull(result)
        assertEquals("ananyacooks", result?.author)
        assertEquals(
            "https://scontent.cdninstagram.com/v/t51.2885-15/meta_cover.jpg?stp=dst-jpg_e35&_nc_ht=scontent.cdninstagram.com",
            result?.imageUrl
        )
        assertEquals(
            "Authentic Kolkata Chicken Biryani! Layered fragrant basmati rice with marinated chicken and " +
                "fried potatoes. Cook on dum for 25 mins. #biryani #kolkata #foodie",
            result?.caption
        )
    }

    @Test
    fun `PostPageMetaFetchStrategy keeps a caption that has no likes prefix`() {
        val html = """<html><head><meta property="og:description" content="Ingredients: 2 eggs, 1 cup flour"></head></html>"""
        val result = PostPageMetaFetchStrategy(FakeHttpFetcher { _, _ -> error("unused") }).parseHtml(html)

        assertEquals("Ingredients: 2 eggs, 1 cup flour", result?.caption)
        assertNull(result?.author)
    }

    @Test
    fun `PostPageMetaFetchStrategy strips prefix without comment count`() {
        val html = """<html><head><meta property="og:description" content="12K likes - dal.diaries on March 3, 2026: &quot;Dal tadka&quot;."></head></html>"""
        val result = PostPageMetaFetchStrategy(FakeHttpFetcher { _, _ -> error("unused") }).parseHtml(html)

        assertEquals("Dal tadka", result?.caption)
        assertEquals("dal.diaries", result?.author)
    }

    @Test
    fun `InstagramPostFetcher stops at first caption and keeps hashtags for caption_raw`() = runBlocking {
        val calls = mutableListOf<String>()
        val first = object : PostFetchStrategy {
            override val name: String = "Dummy1"
            override suspend fun fetch(shortcode: String): PostData {
                calls += name
                return PostData(
                    caption = "Great pasta recipe!\nIngredients: 100g pasta, garlic &amp; oil.\n\n#food #yummy\n#pasta",
                    author = "pastachef",
                    imageUrl = "https://example.com/first.jpg"
                )
            }
        }
        val second = object : PostFetchStrategy {
            override val name: String = "Dummy2"
            override suspend fun fetch(shortcode: String): PostData {
                calls += name
                return PostData("Other caption", "otherchef", "https://example.com/pasta.jpg")
            }
        }

        val post = InstagramPostFetcher(listOf(first, second)).fetch("xyz")

        assertEquals(listOf("Dummy1"), calls)
        assertEquals("pastachef", post?.author)
        assertEquals("https://example.com/first.jpg", post?.imageUrl)
        assertEquals(
            "Great pasta recipe!\nIngredients: 100g pasta, garlic & oil.\n\n#food #yummy\n#pasta",
            post?.caption
        )
        assertEquals(
            "Great pasta recipe!\nIngredients: 100g pasta, garlic & oil.",
            InstagramPostFetcher.stripTrailingHashtags(post?.caption.orEmpty())
        )
    }

    @Test
    fun `InstagramPostFetcher merges image from a later strategy and skips failing ones`() = runBlocking {
        val failing = object : PostFetchStrategy {
            override val name: String = "Broken"
            override suspend fun fetch(shortcode: String): PostData = throw IllegalStateException("markup changed")
        }
        val logs = mutableListOf<String>()
        val post = InstagramPostFetcher(
            listOf(
                failing,
                strategy("CaptionOnly", PostData("Caption text", null, null)),
                strategy("ImageOnly", PostData(null, "chef", "https://example.com/img.jpg"))
            ),
            log = { logs += it }
        ).fetch("abc")

        assertEquals("Caption text", post?.caption)
        assertEquals("chef", post?.author)
        assertEquals("https://example.com/img.jpg", post?.imageUrl)
        assertTrue(logs.any { it.contains("CaptionOnly") })
    }

    @Test
    fun `InstagramPostFetcher returns null when no strategy finds a caption`() = runBlocking {
        val post = InstagramPostFetcher(
            listOf(strategy("A", null), strategy("B", PostData("  ", "x", "https://example.com/a.jpg")))
        ).fetch("abc")
        assertNull(post)
    }

    @Test
    fun `InstagramPostFetcher propagates cancellation instead of trying the next strategy`() = runBlocking {
        val cancelling = object : PostFetchStrategy {
            override val name: String = "Cancelled"
            override suspend fun fetch(shortcode: String): PostData = throw CancellationException("stopped")
        }
        try {
            InstagramPostFetcher(listOf(cancelling, strategy("Next", PostData("c", null, null)))).fetch("abc")
            fail("Expected CancellationException")
        } catch (_: CancellationException) {
        }
    }

    @Test
    fun `stripTrailingHashtags keeps inline hashtags and handles hashtag-only captions`() {
        assertEquals(
            "Make #paneer tikka tonight\nServes 2",
            InstagramPostFetcher.stripTrailingHashtags("Make #paneer tikka tonight\nServes 2\n\n#food #dinner")
        )
        assertEquals("", InstagramPostFetcher.stripTrailingHashtags("#food\n#yum"))
    }
}
