package org.eu.angsumanray.recipecatcher.capture

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.eu.angsumanray.recipecatcher.data.settings.SecretStore
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommentFetcherTest {

    private lateinit var context: Context
    private lateinit var settingsStore: SettingsStore
    private lateinit var commentFetcher: CommentFetcher

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        val secretStore = SecretStore(context)
        settingsStore = SettingsStore(context, secretStore)
        commentFetcher = CommentFetcher(context, settingsStore)
    }

    @Test
    fun instaSelectors_detectsRecipeCues() {
        assertTrue(InstaSelectors.hasRecipeInCommentsCue("Ingredients and full recipe in comment below!"))
        assertTrue(InstaSelectors.hasRecipeInCommentsCue("Recipe below 👇"))
        assertTrue(InstaSelectors.hasRecipeInCommentsCue("Check comments for the exact measurements"))
        assertFalse(InstaSelectors.hasRecipeInCommentsCue("Just had a great lunch today!"))
        assertFalse(InstaSelectors.hasRecipeInCommentsCue(null))
    }

    @Test
    fun instaSelectors_buildsNonBlankJs() {
        val js = InstaSelectors.buildExtractionJs()
        assertTrue(js.isNotBlank())
        assertTrue(js.contains("document.querySelectorAll"))
    }

    @Test
    fun fetchTopComment_whenLoggedOut_returnsNull() = runBlocking {
        settingsStore.setInstagramLoggedIn(false)
        settingsStore.setFetchTopComment(true)

        val result = commentFetcher.fetchTopComment("https://www.instagram.com/p/ABC123xyz/")
        assertNull(result)
    }

    @Test
    fun fetchTopComment_whenFetchDisabled_returnsNull() = runBlocking {
        settingsStore.setInstagramLoggedIn(true)
        settingsStore.setFetchTopComment(false)

        val result = commentFetcher.fetchTopComment("https://www.instagram.com/p/ABC123xyz/")
        assertNull(result)
    }
}
