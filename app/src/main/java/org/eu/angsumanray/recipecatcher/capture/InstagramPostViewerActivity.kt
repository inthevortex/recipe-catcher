package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.eu.angsumanray.recipecatcher.data.db.entity.RecipeEntity
import org.eu.angsumanray.recipecatcher.data.repo.RecipeRepository
import org.eu.angsumanray.recipecatcher.ui.theme.RecipeCatcherTheme
import javax.inject.Inject

@AndroidEntryPoint
class InstagramPostViewerActivity : ComponentActivity() {

    @Inject
    lateinit var recipeRepository: RecipeRepository

    companion object {
        const val EXTRA_RECIPE_ID = "extra_recipe_id"
        const val EXTRA_URL = "extra_url"

        fun createIntent(context: Context, recipeId: Long, url: String): Intent {
            return Intent(context, InstagramPostViewerActivity::class.java).apply {
                putExtra(EXTRA_RECIPE_ID, recipeId)
                putExtra(EXTRA_URL, url)
            }
        }
    }

    private var recipeId: Long = 0
    private var postUrl: String = ""
    private var webView: WebView? = null
    private var isExtracting = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        recipeId = intent.getLongExtra(EXTRA_RECIPE_ID, 0L)
        postUrl = intent.getStringExtra(EXTRA_URL).orEmpty()

        if (postUrl.isBlank()) {
            Toast.makeText(this, "No URL provided", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            RecipeCatcherTheme {
                var loadingProgress by remember { mutableIntStateOf(0) }

                Scaffold(
                    topBar = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TopAppBar(
                                title = { Text("Instagram Post") },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close")
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { webView?.reload() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                }
                            )
                            if (loadingProgress in 1..99) {
                                LinearProgressIndicator(
                                    progress = { loadingProgress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    },
                    bottomBar = {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shadowElevation = 8.dp,
                            color = MaterialTheme.colorScheme.surface
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Button(
                                    onClick = { extractCommentsAndProceed() },
                                    enabled = !isExtracting,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(if (isExtracting) "Extracting Recipe..." else "Extract Recipe from Comments")
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    InstagramViewerWebView(
                        url = postUrl,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onProgressChanged = { loadingProgress = it },
                        onWebViewCreated = { webView = it }
                    )
                }
            }
        }
    }

    private fun extractCommentsAndProceed() {
        val wv = webView ?: return
        isExtracting = true

        // First check if user highlighted / selected text in the WebView
        val jsGetSelection = "(function() { return window.getSelection().toString(); })();"
        wv.evaluateJavascript(jsGetSelection) { selectionRaw ->
            val selection = decodeJsString(selectionRaw)
            if (selection.length >= 20) {
                applyExtractedComment(selection)
                return@evaluateJavascript
            }

            // Otherwise, try extracting using InstaSelectors
            val jsExtraction = InstaSelectors.buildExtractionJs()
            wv.evaluateJavascript(jsExtraction) { extractionRaw ->
                val comment = decodeJsString(extractionRaw)
                if (comment.isNotBlank()) {
                    applyExtractedComment(comment)
                    return@evaluateJavascript
                }

                // Fallback: extract all visible comment list text from page
                val jsFallback = """
                    (function() {
                        var texts = [];
                        var spans = document.querySelectorAll('span[dir="auto"], span._a9zs, span._aacl, article ul li');
                        for (var i = 0; i < spans.length; i++) {
                            var t = (spans[i].innerText || spans[i].textContent || '').trim();
                            if (t.length > 20 && texts.indexOf(t) === -1) {
                                texts.push(t);
                            }
                        }
                        return texts.join('\n\n');
                    })();
                """.trimIndent()
                wv.evaluateJavascript(jsFallback) { fallbackRaw ->
                    val fallbackText = decodeJsString(fallbackRaw)
                    if (fallbackText.isNotBlank()) {
                        applyExtractedComment(fallbackText)
                    } else {
                        isExtracting = false
                        Toast.makeText(this, "Could not find comments. Scroll down to comments or select text and try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    private fun applyExtractedComment(comment: String) {
        lifecycleScope.launch {
            if (recipeId > 0) {
                recipeRepository.updateCommentRaw(recipeId, comment)
                recipeRepository.updateRecipeStatus(recipeId, RecipeEntity.STATUS_PENDING_EXTRACT, null)
                CaptureWorker.enqueue(applicationContext, recipeId)
                Toast.makeText(this@InstagramPostViewerActivity, "Extracting recipe...", Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun decodeJsString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return try {
            Json.decodeFromString<String>(raw)
        } catch (_: Exception) {
            raw.removeSurrounding("\"")
        }.trim()
    }
}

private const val CSS_FIX_SCRIPT = """
    (function() {
      var style = document.getElementById('rc-wv-fix');
      if (!style) {
        style = document.createElement('style');
        style.id = 'rc-wv-fix';
        style.textContent = 'html, body, #mount_0_0_0, div[id^="mount_"] { min-height: 100vh !important; height: auto !important; display: flex !important; flex-direction: column !important; }';
        var target = document.head || document.documentElement;
        if (target) {
          target.appendChild(style);
        }
      }
    })()
"""

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InstagramViewerWebView(
    url: String,
    modifier: Modifier = Modifier,
    onProgressChanged: (Int) -> Unit,
    onWebViewCreated: (WebView) -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.parseColor("#FAFAFA"))

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = false
                settings.javaScriptCanOpenWindowsAutomatically = true

                val defaultUa = settings.userAgentString
                settings.userAgentString = defaultUa
                    .replace("; wv", "")
                    .replace("Version/4.0 ", "")

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        onProgressChanged(newProgress)
                        if (newProgress in 25..95) {
                            view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onProgressChanged(1)
                        view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
                        onProgressChanged(100)
                    }
                }

                onWebViewCreated(this)
                loadUrl(url)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        }
    )
}
