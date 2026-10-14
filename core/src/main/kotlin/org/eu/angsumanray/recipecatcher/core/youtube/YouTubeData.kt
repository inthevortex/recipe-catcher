package org.eu.angsumanray.recipecatcher.core.youtube

data class YouTubeData(
    val videoId: String,
    val title: String?,
    val author: String?,
    val description: String?,
    val thumbnailUrl: String,
    val isPlayerResponse: Boolean
)
