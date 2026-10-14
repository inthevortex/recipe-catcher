package org.eu.angsumanray.recipecatcher.core.ports

data class HttpResponseData(
    val statusCode: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    val isSuccessful: Boolean get() = statusCode in 200..299
}

interface HttpFetcher {
    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): HttpResponseData
    suspend fun post(url: String, body: String, headers: Map<String, String> = emptyMap()): HttpResponseData =
        throw UnsupportedOperationException("POST not supported by this HttpFetcher")
}
