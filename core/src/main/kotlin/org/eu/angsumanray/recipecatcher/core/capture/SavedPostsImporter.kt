package org.eu.angsumanray.recipecatcher.core.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

data class ImportedPost(
    val url: String,
    val shortcode: String,
    val collection: String? = null
)

object SavedPostsImporter {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private const val MAX_ZIP_ENTRY_BYTES = 50 * 1024 * 1024 // 50MB per entry protection

    /**
     * Parses an Instagram export, which may be JSON (saved_posts.json or saved_collections.json)
     * or arbitrary plain text containing Instagram post/reel URLs.
     */
    fun parse(text: String): List<ImportedPost> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()

        // Try JSON parsing first
        val postsFromJson = tryParseJson(trimmed)
        if (postsFromJson.isNotEmpty()) {
            return deduplicate(postsFromJson)
        }

        // Fallback: extract any Instagram URLs from plain text
        val postsFromText = parsePlainText(trimmed)
        return deduplicate(postsFromText)
    }

    /**
     * Reads an export ZIP stream, parses all JSON files inside it (e.g. saved_posts.json,
     * saved_collections.json), and returns the deduplicated list of imported posts.
     */
    fun parseZip(inputStream: InputStream): List<ImportedPost> {
        val allPosts = mutableListOf<ImportedPost>()
        val zis = ZipInputStream(inputStream)
        try {
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.lowercase()
                if (!entry.isDirectory && name.endsWith(".json")) {
                    val content = readEntryString(zis)
                    allPosts.addAll(parse(content))
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        } finally {
            zis.close()
        }
        return deduplicate(allPosts)
    }

    private fun readEntryString(zis: ZipInputStream): String {
        val buffer = ByteArray(8192)
        val baos = ByteArrayOutputStream()
        var totalRead = 0
        var bytesRead: Int
        while (zis.read(buffer).also { bytesRead = it } != -1) {
            totalRead += bytesRead
            if (totalRead > MAX_ZIP_ENTRY_BYTES) {
                break
            }
            baos.write(buffer, 0, bytesRead)
        }
        return baos.toString(Charsets.UTF_8.name())
    }

    private fun tryParseJson(jsonString: String): List<ImportedPost> {
        return try {
            val root = json.parseToJsonElement(jsonString)
            val results = mutableListOf<ImportedPost>()
            walkJsonElement(root, currentCollection = null, results = results)
            results
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun walkJsonElement(
        element: JsonElement,
        currentCollection: String?,
        results: MutableList<ImportedPost>
    ) {
        when (element) {
            is JsonObject -> walkJsonObject(element, currentCollection, results)
            is JsonArray -> {
                for (item in element) {
                    walkJsonElement(item, currentCollection, results)
                }
            }
            is JsonPrimitive -> {
                if (element.isString) {
                    extractPostsFromString(element.content, currentCollection, results)
                }
            }
        }
    }

    private fun walkJsonObject(
        obj: JsonObject,
        currentCollection: String?,
        results: MutableList<ImportedPost>
    ) {
        // Detect collection containers: e.g. "title": "Dinner Recipes" or "name": "Baking"
        // if this object also has "media" or "saved_media" or "items"
        var effectiveCollection = currentCollection
        val titleProp = obj["title"] as? JsonPrimitive
        val nameProp = obj["name"] as? JsonPrimitive
        val candidateTitle = (titleProp ?: nameProp)?.takeIf { it.isString }?.content?.trim()

        val hasMediaArray = (obj["media"] is JsonArray) ||
            (obj["saved_media"] is JsonArray) ||
            (obj["items"] is JsonArray)

        if (!candidateTitle.isNullOrBlank() && hasMediaArray && !isGenericTitle(candidateTitle)) {
            effectiveCollection = candidateTitle
        }

        // Special handling for saved_collections array
        val savedCollections = obj["saved_collections"] as? JsonArray
        if (savedCollections != null) {
            for (colItem in savedCollections) {
                if (colItem is JsonObject) {
                    val colTitle = (colItem["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                    val collectionName = if (!colTitle.isNullOrBlank() && !isGenericTitle(colTitle)) colTitle else effectiveCollection
                    walkJsonObject(colItem, collectionName, results)
                } else {
                    walkJsonElement(colItem, effectiveCollection, results)
                }
            }
        }

        // Walk all key-value pairs
        for ((key, value) in obj) {
            if (key == "saved_collections") continue // Already handled above

            if (value is JsonPrimitive && value.isString) {
                extractPostsFromString(value.content, effectiveCollection, results)
            } else {
                walkJsonElement(value, effectiveCollection, results)
            }
        }
    }

    private fun isGenericTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower == "saved" || lower == "saved posts" || lower == "saved media" || lower.startsWith("http")
    }

    private fun extractPostsFromString(
        text: String,
        collection: String?,
        results: MutableList<ImportedPost>
    ) {
        val shortcode = UrlNormalizer.extractInstagramShortcode(text)
        if (shortcode != null) {
            val canonicalUrl = UrlNormalizer.canonicalizeInstagramUrl(shortcode)
            results.add(ImportedPost(url = canonicalUrl, shortcode = shortcode, collection = collection))
            return
        }

        val urls = UrlNormalizer.extractUrls(text)
        for (url in urls) {
            val sc = UrlNormalizer.extractInstagramShortcode(url)
            if (sc != null) {
                val canonicalUrl = UrlNormalizer.canonicalizeInstagramUrl(sc)
                results.add(ImportedPost(url = canonicalUrl, shortcode = sc, collection = collection))
            }
        }
    }

    private fun parsePlainText(text: String): List<ImportedPost> {
        val results = mutableListOf<ImportedPost>()
        val urls = UrlNormalizer.extractUrls(text)
        for (url in urls) {
            val sc = UrlNormalizer.extractInstagramShortcode(url)
            if (sc != null) {
                val canonicalUrl = UrlNormalizer.canonicalizeInstagramUrl(sc)
                results.add(ImportedPost(url = canonicalUrl, shortcode = sc, collection = null))
            }
        }
        return results
    }

    private fun deduplicate(posts: List<ImportedPost>): List<ImportedPost> {
        val map = linkedMapOf<String, ImportedPost>()
        for (post in posts) {
            val existing = map[post.shortcode]
            if (existing == null) {
                map[post.shortcode] = post
            } else if (existing.collection.isNullOrBlank() && !post.collection.isNullOrBlank()) {
                // Prefer item with a non-blank collection
                map[post.shortcode] = post
            }
        }
        return map.values.toList()
    }
}
