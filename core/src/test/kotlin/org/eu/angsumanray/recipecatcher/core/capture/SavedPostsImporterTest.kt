package org.eu.angsumanray.recipecatcher.core.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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
    @Test
    fun parse_flatCollectionExportMapsPostsToPrecedingHeader() {
        val json = """
            {
              "saved_saved_collections": [
                {
                  "title": "Collection",
                  "string_map_data": {
                    "Name": { "value": "Desserts" },
                    "Creation Time": { "timestamp": 1700000000 }
                  }
                },
                {
                  "title": "baker_jane",
                  "string_map_data": {
                    "Name": { "href": "https://www.instagram.com/p/CAKE1/", "value": "baker_jane" },
                    "Added Time": { "timestamp": 1700000001 }
                  }
                },
                {
                  "title": "Collection",
                  "string_map_data": { "Name": { "value": "Weeknight" } }
                },
                {
                  "title": "chef_raj",
                  "string_map_data": {
                    "Name": { "href": "https://www.instagram.com/reel/DAL2/", "value": "chef_raj" }
                  }
                }
              ]
            }
        """.trimIndent()

        val results = SavedPostsImporter.parse(json)
        assertEquals(listOf("CAKE1", "DAL2"), results.map { it.shortcode })
        assertEquals("Desserts", results[0].collection)
        assertEquals("Weeknight", results[1].collection)
    }

    @Test
    fun parse_authorTitleInSavedPostsIsNotACollection() {
        val json = """
            {"saved_saved_media": [{"title": "chef_john", "media": [],
              "string_map_data": {"Saved on": {"href": "https://www.instagram.com/p/AUTH1/"}}}]}
        """.trimIndent()

        val results = SavedPostsImporter.parse(json)
        assertEquals(1, results.size)
        assertNull(results[0].collection)
    }

    @Test
    fun parse_stringifiedJsonKeepsCollections() {
        val inner = """{"saved_collections":[{"title":"Soups","media":[{"href":"https://www.instagram.com/p/SOUP1/"}]}]}"""
        val outer = "{\"payload\": ${kotlinx.serialization.json.JsonPrimitive(inner)}}"

        val results = SavedPostsImporter.parse(outer)
        assertEquals(1, results.size)
        assertEquals("SOUP1", results[0].shortcode)
        assertEquals("Soups", results[0].collection)
    }

    @Test
    fun parse_stringWithSeveralLinksYieldsAllOfThem() {
        val json = """{"note": "https://www.instagram.com/p/ONE1/ then https://www.instagram.com/reel/TWO2/"}"""

        val results = SavedPostsImporter.parse(json)
        assertEquals(listOf("ONE1", "TWO2"), results.map { it.shortcode })
    }

    @Test
    fun parse_nestedListsAndByteOrderMark() {
        val json = "\uFEFF" + """[[["https://www.instagram.com/p/DEEP1/"]], {"x": [["https://instagr.am/p/DEEP2/"]]}]"""

        val results = SavedPostsImporter.parse(json)
        assertEquals(listOf("DEEP1", "DEEP2"), results.map { it.shortcode })
    }

    @Test
    fun parse_truncatedJsonFallsBackToLinksWithEscapedSlashes() {
        val truncated = """{"saved_saved_media": [{"string_map_data": {"Saved on": {"href": "https:\/\/www.instagram.com\/p\/CUT1\/"}}}, {"str"""

        val results = SavedPostsImporter.parse(truncated)
        assertEquals(listOf("CUT1"), results.map { it.shortcode })
    }

    @Test
    fun parseZip_ignoresNonSavedJsonAndTraversalNamesStayInMemory() {
        val bytes = zipOf(
            "your_instagram_activity/likes/liked_posts.json" to """{"likes": ["https://www.instagram.com/p/LIKED1/"]}""",
            "../../evil/saved_posts.json" to """{"a": "https://www.instagram.com/p/SAVED1/"}""",
            "your_instagram_activity/saved/saved_posts.html" to """<a href="https://www.instagram.com/p/HTML1/">post</a>"""
        )

        val results = SavedPostsImporter.parseZip(ByteArrayInputStream(bytes))
        assertEquals(listOf("SAVED1", "HTML1"), results.map { it.shortcode })
    }

    @Test
    fun parseZip_entryWithEmptyNameIsSkipped() {
        val bytes = zipOf("" to "https://www.instagram.com/p/NONAME/")

        assertTrue(SavedPostsImporter.parseZip(ByteArrayInputStream(bytes)).isEmpty())
    }

    @Test
    fun parseZip_garbageThrows() {
        val garbage = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(64) { 0x7F }

        assertThrows(java.io.IOException::class.java) {
            SavedPostsImporter.parseZip(ByteArrayInputStream(garbage))
        }
    }

    @Test
    fun parseZip_truncatedArchiveKeepsPostsReadBeforeTheDamage() {
        val bytes = zipOf(
            "saved/saved_posts.json" to """{"a": "https://www.instagram.com/p/GOOD1/"}""",
            "saved/saved_collections.json" to """{"b": "https://www.instagram.com/p/LOST2/", "pad": "${"x".repeat(4000)}"}"""
        )
        val cut = bytes.copyOf(bytes.size - 200)

        val results = SavedPostsImporter.parseZip(ByteArrayInputStream(cut))
        assertTrue(results.any { it.shortcode == "GOOD1" })
    }

    @Test
    fun parseStream_detectsZipBySignatureAndTextOtherwise() {
        val zip = zipOf("saved_posts.json" to """{"a": "https://www.instagram.com/p/ZIPPED/"}""")
        assertEquals("ZIPPED", SavedPostsImporter.parseStream(ByteArrayInputStream(zip)).single().shortcode)

        val text = "see https://www.instagram.com/p/TEXTED/".toByteArray()
        assertEquals("TEXTED", SavedPostsImporter.parseStream(ByteArrayInputStream(text)).single().shortcode)

        assertTrue(SavedPostsImporter.parseStream(ByteArrayInputStream(ByteArray(0))).isEmpty())
    }

    @Test
    fun isSavedPostsEntry_matchesOnlySavedFiles() {
        assertTrue(SavedPostsImporter.isSavedPostsEntry("your_instagram_activity/saved/saved_posts.json"))
        assertTrue(SavedPostsImporter.isSavedPostsEntry("saved\\saved_collections.json"))
        assertFalse(SavedPostsImporter.isSavedPostsEntry("your_instagram_activity/likes/liked_posts.json"))
        assertFalse(SavedPostsImporter.isSavedPostsEntry(null))
        assertFalse(SavedPostsImporter.isSavedPostsEntry("saved/"))
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            for ((name, content) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(content.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return baos.toByteArray()
    }
}
