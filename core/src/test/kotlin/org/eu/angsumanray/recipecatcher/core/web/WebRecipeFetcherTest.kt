package org.eu.angsumanray.recipecatcher.core.web

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

class WebRecipeFetcherTest {

    private fun readFixture(filename: String): String {
        val stream = javaClass.getResourceAsStream("/web/$filename")
            ?: throw IllegalArgumentException("Fixture /web/$filename not found")
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
    fun `fixture 1 simple JSON-LD extracts recipe, ingredients, steps, and ISO durations`() = runBlocking {
        val html = readFixture("recipe_jsonld_simple.html")
        val url = "https://italianhomekitchen.com/recipes/margherita-pizza"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch(url)
        assertNotNull(data)
        checkNotNull(data)

        assertTrue(data.isJsonLd)
        assertEquals("Classic Margherita Pizza", data.title)
        assertEquals("Italian Home Kitchen", data.author)
        assertEquals("https://example.com/pizza.jpg", data.imageUrl)
        assertNotNull(data.rawLdJson)
        assertNull(data.readableText)

        // Verify direct mapping
        val direct = data.directMapping
        assertNotNull(direct)
        checkNotNull(direct)
        assertTrue(direct.isRecipe)
        assertEquals("Classic Margherita Pizza", direct.title)
        assertEquals(4, direct.servings)
        assertEquals(20, direct.prepMinutes)
        assertEquals(15, direct.cookMinutes)
        assertEquals(35, direct.totalMinutes)
        assertEquals("Italian", direct.cuisine)
        assertEquals(5, direct.ingredientGroups.first().items.size)
        assertEquals("250g pizza dough", direct.ingredientGroups.first().items[0].rawText)
        assertEquals(5, direct.steps.size)
        assertEquals("Preheat oven to 250C with pizza stone inside.", direct.steps[0].text)
    }

    @Test
    fun `fixture 2 JSON-LD with graph and HowToSection flattens nested steps and extracts ImageObject`() = runBlocking {
        val html = readFixture("recipe_jsonld_graph.html")
        val url = "https://currywonders.com/dal-makhani"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch(url)
        assertNotNull(data)
        checkNotNull(data)

        assertTrue(data.isJsonLd)
        assertEquals("Rich Dal Makhani", data.title)
        assertEquals("Curry Wonders", data.author)
        assertEquals("https://example.com/dal.jpg", data.imageUrl)

        val direct = data.directMapping
        assertNotNull(direct)
        checkNotNull(direct)
        assertEquals(6, direct.servings)
        assertEquals(15, direct.prepMinutes)
        assertEquals(45, direct.cookMinutes)
        assertEquals(60, direct.totalMinutes) // prep + cook calculated when totalTime absent
        assertEquals(5, direct.ingredientGroups.first().items.size)
        // 2 steps in section 1 + 3 steps in section 2 = 5 steps total
        assertEquals(5, direct.steps.size)
        assertEquals("Wash lentils and beans, then soak overnight.", direct.steps[0].text)
        assertEquals("Stir in cream before serving.", direct.steps[4].text)
    }

    @Test
    fun `fixture 3 JSON-LD with string instructions splits on newlines`() = runBlocking {
        val html = readFixture("recipe_jsonld_string_instructions.html")
        val url = "https://pancakehouse.com/fluffy-pancakes"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch(url)
        assertNotNull(data)
        checkNotNull(data)

        assertTrue(data.isJsonLd)
        assertEquals("Fluffy Buttermilk Pancakes", data.title)
        assertEquals(4, data.directMapping?.servings)
        assertEquals(25, data.directMapping?.totalMinutes)
        assertEquals(5, data.directMapping?.ingredientGroups?.first()?.items?.size)
        assertEquals(4, data.directMapping?.steps?.size)
        assertEquals("1. Sift flour and baking powder into a bowl.", data.directMapping?.steps?.get(0)?.text)
    }

    @Test
    fun `fixture 4 no JSON-LD falls back to readable text and strips navigation, ads, and footers`() = runBlocking {
        val html = readFixture("recipe_no_jsonld_article.html")
        val url = "https://bistrogazette.com/french-onion-soup"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch(url)
        assertNotNull(data)
        checkNotNull(data)

        assertFalse(data.isJsonLd)
        assertEquals("Rustic French Onion Soup", data.title)
        assertEquals("Bistro Gazette", data.author)
        assertEquals("https://example.com/soup.jpg", data.imageUrl)
        assertNull(data.rawLdJson)
        assertNotNull(data.readableText)
        assertNull(data.directMapping)

        val text = data.readableText.orEmpty()
        assertTrue("Contains onion soup text", text.contains("Rustic French Onion Soup"))
        assertTrue("Contains ingredients", text.contains("5 large yellow onions"))
        assertFalse("Strips ads", text.contains("Buy our cooking pans"))
        assertFalse("Strips footer", text.contains("All rights reserved"))
        assertFalse("Strips nav", text.contains("Home | Recipes"))
    }

    @Test
    fun `fixture 5 JSON-LD quirks normalizes unescaped HTML entities, duration formats, and image arrays`() = runBlocking {
        val html = readFixture("recipe_jsonld_quirks.html")
        val url = "https://thecountrybaker.com/apple-crisp"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        val data = fetcher.fetch(url)
        assertNotNull(data)
        checkNotNull(data)

        assertTrue(data.isJsonLd)
        assertEquals("Mom's Apple & Cinnamon Crisp", data.title)
        assertEquals("The Country Baker", data.author)
        assertEquals("https://example.com/crisp1.jpg", data.imageUrl)

        val direct = data.directMapping
        assertNotNull(direct)
        checkNotNull(direct)
        assertEquals(6, direct.servings)
        assertEquals(20, direct.prepMinutes)
        assertEquals(45, direct.cookMinutes)
        assertEquals(65, direct.totalMinutes) // 1H5M = 65 min
        assertEquals("1 ½ tsp ground cinnamon", direct.ingredientGroups.first().items[1].rawText)
        assertEquals(3, direct.steps.size)
    }

    @Test
    fun `HTTP 403 throws WebFetchBlockedException`() = runBlocking {
        val url = "https://blocked-site.com/recipe"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(403, "Forbidden"))))

        try {
            fetcher.fetch(url)
            fail("Expected WebFetchBlockedException")
        } catch (e: WebFetchBlockedException) {
            assertEquals(403, e.statusCode)
            assertEquals(url, e.url)
        }
    }

    @Test
    fun `Cloudflare challenge marker throws WebFetchBlockedException`() = runBlocking {
        val url = "https://cf-site.com/recipe"
        val html = "<html><head><title>Just a moment...</title></head><body>cf-browser-verification</body></html>"
        val fetcher = WebRecipeFetcher(mockHttpFetcher(mapOf(url to HttpResponseData(200, html))))

        try {
            fetcher.fetch(url)
            fail("Expected WebFetchBlockedException")
        } catch (e: WebFetchBlockedException) {
            assertTrue(e.message?.contains("Cloudflare") == true)
        }
    }

    @Test
    fun `readable text extractor caps text at 12000 characters`() {
        val hugeParagraph = "<p>" + "A".repeat(15000) + "</p>"
        val html = "<html><body><article>$hugeParagraph</article></body></html>"
        val doc = org.jsoup.Jsoup.parse(html)
        val extracted = ReadableTextExtractor.extract(doc)
        assertEquals(ReadableTextExtractor.MAX_CHARACTERS, extracted.length)
    }
}
