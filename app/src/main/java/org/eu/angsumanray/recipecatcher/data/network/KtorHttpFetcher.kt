package org.eu.angsumanray.recipecatcher.data.network

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.core.ports.HttpResponseData
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KtorHttpFetcher @Inject constructor(
    private val httpClient: HttpClient
) : HttpFetcher {

    override suspend fun get(url: String, headers: Map<String, String>): HttpResponseData {
        val response = httpClient.get(url) {
            headers {
                headers.forEach { (k, v) -> append(k, v) }
            }
        }
        val responseHeaders = mutableMapOf<String, String>()
        response.headers.forEach { k, v ->
            responseHeaders[k] = v.joinToString(", ")
        }
        return HttpResponseData(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            headers = responseHeaders
        )
    }

    override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponseData {
        val response = httpClient.post(url) {
            headers {
                headers.forEach { (k, v) -> append(k, v) }
            }
            setBody(body)
        }
        val responseHeaders = mutableMapOf<String, String>()
        response.headers.forEach { k, v ->
            responseHeaders[k] = v.joinToString(", ")
        }
        return HttpResponseData(
            statusCode = response.status.value,
            body = response.bodyAsText(),
            headers = responseHeaders
        )
    }
}
