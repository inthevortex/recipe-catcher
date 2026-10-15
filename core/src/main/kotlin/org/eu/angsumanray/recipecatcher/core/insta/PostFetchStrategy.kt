package org.eu.angsumanray.recipecatcher.core.insta

data class PostData(
    val caption: String?,
    val author: String?,
    val imageUrl: String?,
    val slideImageUrls: List<String> = emptyList()
)

interface PostFetchStrategy {
    val name: String
    suspend fun fetch(shortcode: String): PostData?
}
