package org.eu.angsumanray.recipecatcher.core.web

import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DuckDuckGoSearchProviderTest {

    private val sampleHtml = """
    <!DOCTYPE html>
    <html>
    <body>
      <div class="result results_links results_links_deep web-result">
        <h2 class="result__title">
          <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.vegrecipesofindia.com%2Fpalak-paneer%2F&rut=123">Palak Paneer Recipe</a>
        </h2>
        <a class="result__snippet">Creamy and restaurant style spinach curry with cottage cheese cubes.</a>
      </div>
      <div class="result results_links results_links_deep web-result">
        <h2 class="result__title">
          <a class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3Dxyz123&rut=123">Palak Paneer Video</a>
        </h2>
        <a class="result__snippet">Watch how to make it on YouTube.</a>
      </div>
      <div class="result results_links results_links_deep web-result">
        <h2 class="result__title">
          <a class="result__a" href="https://hebbarskitchen.com/palak-paneer-recipe/">Easy Palak Paneer at Home</a>
        </h2>
        <a class="result__snippet">Step by step recipe for palak paneer.</a>
      </div>
    </body>
    </html>
    """.trimIndent()

    @Test
    fun testParseHtmlResultsExtractsCleanUrlsAndFiltersVideos() {
        val fakeFetcher = object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
                return HttpResponseData(200, sampleHtml)
            }
        }

        val provider = DuckDuckGoSearchProvider(fakeFetcher)
        val results = provider.parseHtmlResults(sampleHtml, maxResults = 3)

        assertEquals(2, results.size)
        assertEquals("Palak Paneer Recipe", results[0].title)
        assertEquals("https://www.vegrecipesofindia.com/palak-paneer/", results[0].url)
        assertEquals("vegrecipesofindia.com", results[0].site)
        assertEquals("Creamy and restaurant style spinach curry with cottage cheese cubes.", results[0].snippet)

        // YouTube video result was skipped!
        assertEquals("Easy Palak Paneer at Home", results[1].title)
        assertEquals("https://hebbarskitchen.com/palak-paneer-recipe/", results[1].url)
        assertEquals("hebbarskitchen.com", results[1].site)
    }

    @Test
    fun testSearchExecutesGetAndParses() = runBlocking {
        val fakeFetcher = object : HttpFetcher {
            override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
                assertTrue(url.contains("q=palak+paneer") || url.contains("q=palak%20paneer"))
                return HttpResponseData(200, sampleHtml)
            }
        }

        val provider = DuckDuckGoSearchProvider(fakeFetcher)
        val results = provider.search("palak paneer", maxResults = 1)
        assertEquals(1, results.size)
        assertEquals("Palak Paneer Recipe", results[0].title)
    }
}
