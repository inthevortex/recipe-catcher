package org.eu.angsumanray.recipecatcher.core.insta

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CarouselExtractorTest {

    @Test
    fun `extractSlideUrls parses edge_sidecar_to_children display_url in order`() {
        val sampleHtml = """
            <html>
            <head>
            <script type="application/json">
            {
                "entry_data": {
                    "PostPage": [{
                        "graphql": {
                            "shortcode_media": {
                                "edge_sidecar_to_children": {
                                    "edges": [
                                        {"node": {"display_url": "https:\/\/instagram.com\/slide1.jpg?_nc=1\u0026v=2"}},
                                        {"node": {"display_url": "https:\/\/instagram.com\/slide2.jpg?_nc=1\u0026v=2"}},
                                        {"node": {"display_url": "https:\/\/instagram.com\/slide3.jpg?_nc=1\u0026v=2"}}
                                    ]
                                }
                            }
                        }
                    }]
                }
            }
            </script>
            </head>
            <body></body>
            </html>
        """.trimIndent()

        val urls = CarouselExtractor.extractSlideUrls(sampleHtml)
        assertEquals(3, urls.size)
        assertEquals("https://instagram.com/slide1.jpg?_nc=1&v=2", urls[0])
        assertEquals("https://instagram.com/slide2.jpg?_nc=1&v=2", urls[1])
        assertEquals("https://instagram.com/slide3.jpg?_nc=1&v=2", urls[2])
    }

    @Test
    fun `extractSlideUrls falls back to EmbeddedMediaImage elements`() {
        val sampleHtml = """
            <html>
            <body>
                <div class="EmbeddedMedia">
                    <img class="EmbeddedMediaImage" src="https://instagram.com/media1.jpg" />
                    <img class="EmbeddedMediaImage" src="https://instagram.com/media2.jpg" />
                </div>
            </body>
            </html>
        """.trimIndent()

        val urls = CarouselExtractor.extractSlideUrls(sampleHtml)
        assertEquals(2, urls.size)
        assertEquals("https://instagram.com/media1.jpg", urls[0])
        assertEquals("https://instagram.com/media2.jpg", urls[1])
    }

    @Test
    fun `hasFewerThan3QuantityLines detects captions with and without quantities`() {
        val captionNoQuantities = """
            Delicious butter chicken made with love!
            Check out the slides for the step by step recipe.
            Save this for later! #recipe #food
        """.trimIndent()
        assertTrue(InstagramPostFetcher.hasFewerThan3QuantityLines(captionNoQuantities))

        val captionWithQuantities = """
            Butter Chicken:
            - 500g chicken thigh
            - 2 tbsp butter
            - 1 cup heavy cream
            - 1 tsp garam masala
            Cook well and enjoy!
        """.trimIndent()
        assertFalse(InstagramPostFetcher.hasFewerThan3QuantityLines(captionWithQuantities))
    }
}
