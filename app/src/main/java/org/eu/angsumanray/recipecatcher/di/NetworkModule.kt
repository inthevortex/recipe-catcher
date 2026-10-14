package org.eu.angsumanray.recipecatcher.di

import android.util.Log
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.eu.angsumanray.recipecatcher.core.insta.EmbedPageFetchStrategy
import org.eu.angsumanray.recipecatcher.core.insta.InstagramPostFetcher
import org.eu.angsumanray.recipecatcher.core.insta.OEmbedFetchStrategy
import org.eu.angsumanray.recipecatcher.core.insta.PostPageMetaFetchStrategy
import org.eu.angsumanray.recipecatcher.core.llm.ClaudeExtractor
import org.eu.angsumanray.recipecatcher.core.llm.RecipeExtractor
import org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher
import org.eu.angsumanray.recipecatcher.data.network.KtorHttpFetcher
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton

/** Ktor client for the Anthropic API, with a read timeout long enough for a non-streaming extraction. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class AnthropicHttpClient

@Module
@InstallIn(SingletonComponent::class)
abstract class NetworkBindingModule {

    @Binds
    @Singleton
    abstract fun bindHttpFetcher(impl: KtorHttpFetcher): HttpFetcher
}

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient {
        return OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    @Provides
    @Singleton
    fun provideKtorClient(okHttpClient: OkHttpClient): HttpClient = ktorClient(okHttpClient)

    // A non-streaming Messages call sends nothing until the whole response is ready.
    @Provides
    @Singleton
    @AnthropicHttpClient
    fun provideAnthropicKtorClient(okHttpClient: OkHttpClient): HttpClient =
        ktorClient(okHttpClient.newBuilder().readTimeout(60, TimeUnit.SECONDS).build())

    private fun ktorClient(okHttpClient: OkHttpClient): HttpClient = HttpClient(OkHttp) {
        engine {
            preconfigured = okHttpClient
        }
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
    }

    @Provides
    @Singleton
    fun provideInstagramPostFetcher(httpFetcher: HttpFetcher): InstagramPostFetcher {
        val strategies = listOf(
            OEmbedFetchStrategy(httpFetcher),
            EmbedPageFetchStrategy(httpFetcher),
            PostPageMetaFetchStrategy(httpFetcher)
        )
        return InstagramPostFetcher(strategies) { Log.i("InstagramPostFetcher", it) }
    }

    @Provides
    @Singleton
    fun provideWebRecipeFetcher(httpFetcher: org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher): org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher {
        return org.eu.angsumanray.recipecatcher.core.web.WebRecipeFetcher(httpFetcher) { Log.i("WebRecipeFetcher", it) }
    }

    @Provides
    @Singleton
    fun provideYouTubeFetcher(httpFetcher: org.eu.angsumanray.recipecatcher.core.ports.HttpFetcher): org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher {
        return org.eu.angsumanray.recipecatcher.core.youtube.YouTubeFetcher(httpFetcher) { Log.i("YouTubeFetcher", it) }
    }

    @Provides
    @Singleton
    fun provideRecipeExtractor(
        @AnthropicHttpClient httpClient: HttpClient,
        settingsStore: SettingsStore
    ): RecipeExtractor {
        return ClaudeExtractor(
            httpClient = httpClient,
            apiKeyProvider = { settingsStore.getApiKey() },
            modelIdProvider = { settingsStore.modelIdFlow.first() }
        )
    }
}
