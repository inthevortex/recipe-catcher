package org.eu.angsumanray.recipecatcher.core.ports

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String = "",
    val site: String = ""
)

interface WebSearchProvider {
    suspend fun search(query: String, maxResults: Int = 3): List<SearchResult>
}
