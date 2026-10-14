package org.eu.angsumanray.recipecatcher.core.web

import org.eu.angsumanray.recipecatcher.core.llm.ExtractedRecipe

/**
 * Result of fetching and parsing a recipe webpage.
 */
data class WebRecipeData(
    val title: String?,
    val author: String?,
    val imageUrl: String?,
    val rawLdJson: String?,
    val readableText: String?,
    val sourceText: String,
    val isJsonLd: Boolean,
    val directMapping: ExtractedRecipe? = null
)

class WebFetchBlockedException(
    val url: String,
    val statusCode: Int,
    message: String = "Web fetch blocked by host (HTTP $statusCode)"
) : Exception(message)
