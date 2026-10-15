package org.eu.angsumanray.recipecatcher.core.capture

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
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

    private const val MAX_ENTRY_BYTES = 50 * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 100 * 1024 * 1024
    private const val MAX_STRINGIFIED_JSON_DEPTH = 3

    private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /**
     * Reads an export zip, a `saved_posts.json` / `saved_collections.json` file, or any text,
     * telling a zip apart by its signature rather than by file name or MIME type.
     */
    fun parseStream(inputStream: InputStream): List<ImportedPost> {
        val buffered = BufferedInputStream(inputStream)
        buffered.mark(ZIP_MAGIC.size)
        val head = ByteArray(ZIP_MAGIC.size)
        val headLength = buffered.readNBytes(head, 0, head.size)
        buffered.reset()
        return if (headLength == ZIP_MAGIC.size && head.contentEquals(ZIP_MAGIC)) {
            parseZip(buffered)
        } else {
            buffered.use { parse(readCapped(it, MAX_ENTRY_BYTES)) }
        }
    }

    /**
     * Parses an Instagram export, which may be JSON (saved_posts.json or saved_collections.json)
     * or arbitrary plain text containing Instagram post/reel URLs.
     */
    fun parse(text: String): List<ImportedPost> {
        val trimmed = text.trim().removePrefix("﻿").trim()
        if (trimmed.isEmpty()) return emptyList()

        val postsFromJson = tryParseJson(trimmed, depth = 0)
        if (!postsFromJson.isNullOrEmpty()) {
            return deduplicate(postsFromJson)
        }
        // Damaged or truncated JSON still has its links, though escaped slashes need undoing first.
        return deduplicate(extractPosts(trimmed.replace("\\/", "/"), collection = null))
    }

    /**
     * Reads the saved-posts files of an export zip. Only entries named `saved_*` are read, since the
     * rest of the export (likes, comments, messages) also holds post links that aren't saves.
     * Entries are read in memory and never written to disk, so entry paths can't escape anywhere.
     * A damaged archive keeps the posts read before the damage, or throws if there are none.
     */
    fun parseZip(inputStream: InputStream): List<ImportedPost> {
        val allPosts = mutableListOf<ImportedPost>()
        var totalRead = 0
        ZipInputStream(inputStream).use { zis ->
            try {
                var entry = zis.nextEntry
                while (entry != null && totalRead < MAX_TOTAL_BYTES) {
                    if (!entry.isDirectory && isSavedPostsEntry(entry.name)) {
                        val limit = minOf(MAX_ENTRY_BYTES, MAX_TOTAL_BYTES - totalRead)
                        val content = readCapped(zis, limit)
                        totalRead += content.length
                        allPosts.addAll(parse(content))
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            } catch (e: IOException) {
                if (allPosts.isEmpty()) throw e
            }
        }
        return deduplicate(allPosts)
    }

    internal fun isSavedPostsEntry(entryName: String?): Boolean {
        val baseName = entryName.orEmpty().replace('\\', '/').substringAfterLast('/').lowercase()
        return baseName.startsWith("saved_") && (baseName.endsWith(".json") || baseName.endsWith(".html"))
    }

    private fun readCapped(input: InputStream, maxBytes: Int): String {
        val buffer = ByteArray(8192)
        val out = ByteArrayOutputStream()
        while (out.size() < maxBytes) {
            val read = input.read(buffer, 0, minOf(buffer.size, maxBytes - out.size()))
            if (read == -1) break
            out.write(buffer, 0, read)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Null when [jsonString] isn't JSON at all. */
    private fun tryParseJson(jsonString: String, depth: Int): List<ImportedPost>? {
        if (depth > MAX_STRINGIFIED_JSON_DEPTH) return null
        val root = try {
            json.parseToJsonElement(jsonString)
        } catch (_: Exception) {
            return null
        }
        val results = mutableListOf<ImportedPost>()
        walk(root, collection = null, inCollections = false, depth = depth, results = results)
        return results
    }

    private fun walk(
        element: JsonElement,
        collection: String?,
        inCollections: Boolean,
        depth: Int,
        results: MutableList<ImportedPost>
    ) {
        when (element) {
            is JsonArray -> {
                // Newer collection exports are flat: a "Collection" header row, then that collection's posts.
                var current = collection
                for (item in element) {
                    val header = if (inCollections) collectionHeaderName(item) else null
                    if (header != null) {
                        current = header
                    } else {
                        walk(item, current, inCollections, depth, results)
                    }
                }
            }
            is JsonObject -> {
                val effective = if (inCollections) nestedCollectionName(element) ?: collection else collection
                for ((key, value) in element) {
                    val childInCollections = inCollections || key.contains("collection", ignoreCase = true)
                    walk(value, effective, childInCollections, depth, results)
                }
            }
            is JsonPrimitive -> if (element.isString) {
                val content = element.content.trim()
                val nested = if (content.startsWith("{") || content.startsWith("[")) {
                    tryParseJson(content, depth + 1)
                } else null
                if (nested != null) {
                    results.addAll(nested.map { if (it.collection == null) it.copy(collection = collection) else it })
                } else {
                    results.addAll(extractPosts(content, collection))
                }
            }
        }
    }

    /** `{"title": "Collection", "string_map_data": {"Name": {"value": "Desserts"}}}` with no post link. */
    private fun collectionHeaderName(element: JsonElement): String? {
        val obj = element as? JsonObject ?: return null
        val title = obj.stringValue("title") ?: return null
        if (!title.equals("Collection", ignoreCase = true)) return null
        if (containsInstagramPost(obj)) return null
        val nameField = (obj["string_map_data"] as? JsonObject)
            ?.entries
            ?.firstOrNull { it.key.equals("Name", ignoreCase = true) }
            ?.value
        val name = when (nameField) {
            is JsonObject -> nameField.stringValue("value")
            is JsonPrimitive -> nameField.takeIf { it.isString }?.content
            else -> null
        } ?: obj.stringValue("name")
        return name?.trim()?.takeUnless { it.isEmpty() || isGenericTitle(it) }
    }

    /** `{"title": "Desserts", "media": [...]}`: a collection that nests its posts. */
    private fun nestedCollectionName(obj: JsonObject): String? {
        val hasMediaArray = obj["media"] is JsonArray || obj["saved_media"] is JsonArray || obj["items"] is JsonArray
        if (!hasMediaArray) return null
        val candidate = (obj.stringValue("title") ?: obj.stringValue("name"))?.trim()
        return candidate?.takeUnless { it.isEmpty() || isGenericTitle(it) }
    }

    private fun JsonObject.stringValue(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun containsInstagramPost(element: JsonElement): Boolean = when (element) {
        is JsonObject -> element.values.any { containsInstagramPost(it) }
        is JsonArray -> element.any { containsInstagramPost(it) }
        is JsonPrimitive -> element.isString && extractPosts(element.content, null).isNotEmpty()
    }

    private fun isGenericTitle(title: String): Boolean {
        val lower = title.lowercase()
        return lower == "saved" || lower == "saved posts" || lower == "saved media" ||
            lower == "collection" || lower.startsWith("http")
    }

    private fun extractPosts(text: String, collection: String?): List<ImportedPost> =
        UrlNormalizer.extractUrls(text).mapNotNull { url ->
            UrlNormalizer.extractInstagramShortcode(url)?.let { shortcode ->
                ImportedPost(
                    url = UrlNormalizer.canonicalizeInstagramUrl(shortcode),
                    shortcode = shortcode,
                    collection = collection
                )
            }
        }

    private fun deduplicate(posts: List<ImportedPost>): List<ImportedPost> {
        val map = linkedMapOf<String, ImportedPost>()
        for (post in posts) {
            val existing = map[post.shortcode]
            if (existing == null || (existing.collection.isNullOrBlank() && !post.collection.isNullOrBlank())) {
                map[post.shortcode] = post
            }
        }
        return map.values.toList()
    }
}
