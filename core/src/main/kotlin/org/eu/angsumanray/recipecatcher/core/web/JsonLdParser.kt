package org.eu.angsumanray.recipecatcher.core.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.eu.angsumanray.recipecatcher.core.domain.IsoDuration
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedIngredient
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedIngredientGroup
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe
import org.eu.angsumanray.recipecatcher.core.llm.ExtractedStep
import org.eu.angsumanray.recipecatcher.core.llm.LlmUsage
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.util.regex.Pattern

object JsonLdParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val DIGITS_PATTERN = Pattern.compile("(\\d+)")

    data class ParsedJsonLd(
        val recipeJsonObject: JsonObject,
        val normalizedJsonString: String,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
        val servings: Int?,
        val prepMinutes: Int?,
        val cookMinutes: Int?,
        val totalMinutes: Int?,
        val ingredientLines: List<String>,
        val stepTexts: List<String>,
        val directMapping: ExtractedRecipe
    )

    /**
     * Finds and extracts the first schema.org Recipe from script[type="application/ld+json"] tags.
     */
    fun findRecipe(document: Document): ParsedJsonLd? {
        val scripts = document.select("script[type=application/ld+json]")
        for (script in scripts) {
            val content = script.data().trim()
            if (content.isBlank()) continue

            val recipeObject = try {
                val element = json.parseToJsonElement(content)
                findRecipeNode(element)
            } catch (_: Exception) {
                null
            }

            if (recipeObject != null) {
                return parseRecipeObject(recipeObject)
            }
        }
        return null
    }

    /**
     * Recursively searches for an object where @type is or contains "Recipe".
     */
    fun findRecipeNode(element: JsonElement): JsonObject? {
        when (element) {
            is JsonObject -> {
                if (isRecipeType(element["@type"])) {
                    return element
                }
                // Check @graph array
                element["@graph"]?.let { graph ->
                    val found = findRecipeNode(graph)
                    if (found != null) return found
                }
                // Check all object values for nested graphs or recipes
                for ((_, value) in element) {
                    if (value is JsonObject || value is JsonArray) {
                        val found = findRecipeNode(value)
                        if (found != null) return found
                    }
                }
            }
            is JsonArray -> {
                for (item in element) {
                    val found = findRecipeNode(item)
                    if (found != null) return found
                }
            }
            else -> Unit
        }
        return null
    }

    private fun isRecipeType(typeElement: JsonElement?): Boolean {
        if (typeElement == null) return false
        return when (typeElement) {
            is JsonPrimitive -> {
                val typeStr = typeElement.content.trim()
                typeStr.equals("Recipe", ignoreCase = true) ||
                    typeStr.endsWith("/Recipe", ignoreCase = true) ||
                    typeStr.endsWith("#Recipe", ignoreCase = true)
            }
            is JsonArray -> {
                typeElement.any { isRecipeType(it) }
            }
            else -> false
        }
    }

    fun parseRecipeObject(recipeObj: JsonObject): ParsedJsonLd {
        val rawTitle = extractString(recipeObj["name"])
        val title = rawTitle?.let { unescape(it) }

        val rawDesc = extractString(recipeObj["description"])
        val description = rawDesc?.let { unescape(it) }

        val imageUrl = extractImageUrl(recipeObj["image"])

        val servings = extractYield(recipeObj["recipeYield"])

        val prepMinutes = IsoDuration.parseToMinutes(extractString(recipeObj["prepTime"]))
        val cookMinutes = IsoDuration.parseToMinutes(extractString(recipeObj["cookTime"]))
        var totalMinutes = IsoDuration.parseToMinutes(extractString(recipeObj["totalTime"]))
        if (totalMinutes == null && prepMinutes != null && cookMinutes != null) {
            totalMinutes = prepMinutes + cookMinutes
        }

        val ingredientLines = extractIngredients(recipeObj["recipeIngredient"])
        val stepTexts = extractInstructions(recipeObj["recipeInstructions"])

        val cuisine = extractString(recipeObj["recipeCuisine"])

        // Normalized JSON-LD for LLM
        val normalizedJson = buildJsonObject {
            put("@type", "Recipe")
            title?.let { put("name", it) }
            description?.let { put("description", it) }
            servings?.let { put("recipeYield", it) }
            prepMinutes?.let { put("prepTimeMinutes", it) }
            cookMinutes?.let { put("cookTimeMinutes", it) }
            totalMinutes?.let { put("totalTimeMinutes", it) }
            cuisine?.let { put("recipeCuisine", it) }
            imageUrl?.let { put("image", it) }

            put("recipeIngredient", buildJsonArray {
                for (line in ingredientLines) {
                    add(JsonPrimitive(line))
                }
            })

            put("recipeInstructions", buildJsonArray {
                for (step in stepTexts) {
                    add(JsonPrimitive(step))
                }
            })
        }.toString()

        val directMapping = ExtractedRecipe(
            isRecipe = true,
            title = title,
            description = description,
            servings = servings,
            prepMinutes = prepMinutes,
            cookMinutes = cookMinutes,
            totalMinutes = totalMinutes,
            cuisine = cuisine,
            complexity = "medium",
            complexityReason = "Imported directly from web recipe data",
            spiceLevel = "mild",
            dietType = "unknown",
            kcalPerServing = null,
            makeAhead = false,
            freezerFriendly = false,
            ingredientGroups = listOf(
                ExtractedIngredientGroup(
                    name = null,
                    items = ingredientLines.map { line ->
                        ExtractedIngredient(
                            quantity = null,
                            quantityMax = null,
                            unit = null,
                            name = line,
                            baseName = line,
                            category = "other",
                            note = null,
                            rawText = line
                        )
                    }
                )
            ),
            steps = stepTexts.map { ExtractedStep(text = it, timerSeconds = null) },
            tags = emptyList(),
            contains = emptyList(),
            equipment = emptyList(),
            consumables = emptyList(),
            newListSuggestions = emptyList(),
            confidence = 1.0,
            usage = LlmUsage(0, 0, 0, 0, "direct_jsonld")
        )

        return ParsedJsonLd(
            recipeJsonObject = recipeObj,
            normalizedJsonString = normalizedJson,
            title = title,
            description = description,
            imageUrl = imageUrl,
            servings = servings,
            prepMinutes = prepMinutes,
            cookMinutes = cookMinutes,
            totalMinutes = totalMinutes,
            ingredientLines = ingredientLines,
            stepTexts = stepTexts,
            directMapping = directMapping
        )
    }

    fun extractImageUrl(imageElement: JsonElement?): String? {
        if (imageElement == null) return null
        return when (imageElement) {
            is JsonPrimitive -> imageElement.content.trim().takeIf { it.isNotBlank() }
            is JsonArray -> {
                for (item in imageElement) {
                    val url = extractImageUrl(item)
                    if (!url.isNullOrBlank()) return url
                }
                null
            }
            is JsonObject -> {
                extractString(imageElement["url"])
                    ?: extractString(imageElement["contentUrl"])
                    ?: extractImageUrl(imageElement["image"])
            }
        }
    }

    fun extractYield(yieldElement: JsonElement?): Int? {
        if (yieldElement == null) return null
        when (yieldElement) {
            is JsonPrimitive -> {
                val str = yieldElement.content.trim()
                val matcher = DIGITS_PATTERN.matcher(str)
                if (matcher.find()) {
                    return matcher.group(1).toIntOrNull()
                }
            }
            is JsonArray -> {
                for (item in yieldElement) {
                    val y = extractYield(item)
                    if (y != null) return y
                }
            }
            is JsonObject -> {
                return extractYield(yieldElement["value"])
            }
        }
        return null
    }

    fun extractIngredients(ingElement: JsonElement?): List<String> {
        if (ingElement == null) return emptyList()
        val results = mutableListOf<String>()
        when (ingElement) {
            is JsonPrimitive -> {
                val line = unescape(ingElement.content.trim())
                if (line.isNotBlank()) results.add(line)
            }
            is JsonArray -> {
                for (item in ingElement) {
                    when (item) {
                        is JsonPrimitive -> {
                            val line = unescape(item.content.trim())
                            if (line.isNotBlank()) results.add(line)
                        }
                        is JsonObject -> {
                            val text = extractString(item["text"])
                                ?: extractString(item["name"])
                            if (!text.isNullOrBlank()) {
                                results.add(unescape(text.trim()))
                            }
                        }
                        else -> Unit
                    }
                }
            }
            else -> Unit
        }
        return results
    }

    fun extractInstructions(instructionsElement: JsonElement?): List<String> {
        if (instructionsElement == null) return emptyList()
        val steps = mutableListOf<String>()

        fun processElement(el: JsonElement) {
            when (el) {
                is JsonPrimitive -> {
                    val text = unescape(el.content.trim())
                    if (text.isNotBlank()) {
                        // If it has multiple newlines with step numbering or separate lines, split them
                        val lines = text.split(Regex("\r?\n+")).map { it.trim() }.filter { it.isNotBlank() }
                        if (lines.size > 1) {
                            steps.addAll(lines)
                        } else {
                            steps.add(text)
                        }
                    }
                }
                is JsonArray -> {
                    for (item in el) {
                        processElement(item)
                    }
                }
                is JsonObject -> {
                    val type = extractString(el["@type"])
                    if (type != null && type.contains("HowToSection", ignoreCase = true)) {
                        el["itemListElement"]?.let { processElement(it) }
                    } else {
                        val text = extractString(el["text"])
                            ?: extractString(el["name"])
                            ?: extractString(el["description"])
                        if (!text.isNullOrBlank()) {
                            steps.add(unescape(text.trim()))
                        } else if (el["itemListElement"] != null) {
                            processElement(el["itemListElement"]!!)
                        }
                    }
                }
            }
        }

        processElement(instructionsElement)
        return steps
    }

    private fun extractString(element: JsonElement?): String? {
        if (element == null) return null
        return when (element) {
            is JsonPrimitive -> element.content.trim().takeIf { it.isNotBlank() }
            is JsonArray -> element.firstOrNull()?.let { extractString(it) }
            is JsonObject -> extractString(element["name"]) ?: extractString(element["text"]) ?: extractString(element["url"])
        }
    }

    private fun unescape(raw: String): String = Parser.unescapeEntities(raw, false)
}
