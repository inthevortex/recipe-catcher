package org.eu.angsumanray.recipecatcher.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class SavedPostsImporterTest {

    @Test
    fun parse_savedPostsJsonModernFormat() {
        val json = """
            {
              "saved_saved_media": [
                {
                  "title": "Post 1",
                  "string_map_data": {
                    "Saved on": {
                      "href": "https://www.instagram.com/p/C_abc123/",
                      "value": "",
                      "timestamp": 1729000000
                    }
                  }
                },
                {
                  "title": "Reel 2",
                  "string_map_data": {
                    "Saved on": {
                      "href": "https://www.instagram.com/reel/D_xyz999/?igsh=tracking123",
                      "value": "",
                      "timestamp": 1729000010
                    }
                  }
                }
              ]
            }
        """.trimIndent()

        val results = SavedPostsImporter.parse(json)
        assertEquals(2, results.size)
        assertEquals("C_abc123", results[0].shortcode)
        assertEquals("https://www.instagram.com/p/C_abc123/", results[0].url)
        assertNull(results[0].collection)

        assertEquals("D_xyz999", results[1].shortcode)
        assertEquals("https://www.instagram.com/p/D_xyz999/", results[1].url)
    }

    @Test
    fun parse_savedCollectionsFormat() {
        val json = """
            {
              "saved_collections": [
                {
                  "title": "Desserts",
                  "media": [
                    {
                      "title": "Cake",
                      "string_map_data": {
                        "Saved on": {
                          "href": "https://www.instagram.com/p/CAKE123/"
                        }
                      }
                    }
                  ]
                },
                {
                  "title": "Quick Dinners",
                  "media": [
                    {
                      "title": "Pasta",
                      "string_map_data": {
                        "Saved on": {
                          "href": "https://www.instagram.com/reel/PASTA456/"
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val results = SavedPostsImporter.parse(json)
        assertEquals(2, results.size)
        assertEquals("CAKE123", results[0].shortcode)
        assertEquals("Desserts", results[0].collection)

        assertEquals("PASTA456", results[1].shortcode)
        assertEquals("Quick Dinners", results[1].collection)
    }

    @Test
    fun parse_plainTextWithInstagramUrls() {
        val text = """
            Check these recipes out:
            https://www.instagram.com/p/POST_ONE/?utm_source=ig_web_copy_link
            and here is a reel: https://instagram.com/reel/REEL_TWO/
            Some non-instagram link: https://example.com/recipe
            Another one: https://www.instagram.com/tv/TV_THREE/
        """.trimIndent()

        val results = SavedPostsImporter.parse(text)
        assertEquals(3, results.size)
        assertEquals("POST_ONE", results[0].shortcode)
        assertEquals("REEL_TWO", results[1].shortcode)
        assertEquals("TV_THREE", results[2].shortcode)
    }

    @Test
    fun parse_deduplicatesAndPrefersCollection() {
        val json = """
            {
              "saved_saved_media": [
                {
                  "string_map_data": {
                    "Saved on": {
                      "href": "https://www.instagram.com/p/DUP123/"
                    }
                  }
                }
              ],
              "saved_collections": [
                {
                  "title": "Salads",
                  "media": [
                    {
                      "string_map_data": {
                        "Saved on": {
                          "href": "https://www.instagram.com/p/DUP123/"
                        }
                      }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val results = SavedPostsImporter.parse(json)
        assertEquals(1, results.size)
        assertEquals("DUP123", results[0].shortcode)
        assertEquals("Salads", results[0].collection)
    }

    @Test
    fun parseZip_readsJsonEntries() {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // Entry 1: saved_posts.json
            zos.putNextEntry(ZipEntry("your_instagram_activity/saved/saved_posts.json"))
            zos.write(
                """
                {
                  "saved_saved_media": [
                    {
                      "string_map_data": {
                        "Saved on": { "href": "https://www.instagram.com/p/ZIP_POST1/" }
                      }
                    }
                  ]
                }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            // Entry 2: saved_collections.json
            zos.putNextEntry(ZipEntry("your_instagram_activity/saved/saved_collections.json"))
            zos.write(
                """
                {
                  "saved_collections": [
                    {
                      "title": "Baking",
                      "media": [
                        {
                          "string_map_data": {
                            "Saved on": { "href": "https://www.instagram.com/p/ZIP_POST2/" }
                          }
                        }
                      ]
                    }
                  ]
                }
                """.trimIndent().toByteArray(Charsets.UTF_8)
            )
            zos.closeEntry()

            // Entry 3: some non-json file
            zos.putNextEntry(ZipEntry("readme.txt"))
            zos.write("Some text https://www.instagram.com/p/IGNORED/".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val bais = ByteArrayInputStream(baos.toByteArray())
        val results = SavedPostsImporter.parseZip(bais)
        assertEquals(2, results.size)
        assertEquals("ZIP_POST1", results[0].shortcode)
        assertNull(results[0].collection)
        assertEquals("ZIP_POST2", results[1].shortcode)
        assertEquals("Baking", results[1].collection)
    }

    @Test
    fun parse_emptyOrBlankReturnsEmpty() {
        assertTrue(SavedPostsImporter.parse("").isEmpty())
        assertTrue(SavedPostsImporter.parse("   \n\t  ").isEmpty())
        assertTrue(SavedPostsImporter.parse("no links here at all").isEmpty())
    }
}
