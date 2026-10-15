package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.eu.angsumanray.recipecatcher.data.settings.SettingsStore
import org.eu.angsumanray.recipecatcher.ui.theme.RecipeCatcherTheme
import javax.inject.Inject

@AndroidEntryPoint
class LoginWebViewActivity : ComponentActivity() {

    @Inject
    lateinit var settingsStore: SettingsStore

    companion object {
        const val LOGIN_URL = "https://www.instagram.com/accounts/login/"
    }

    private var loginDetected = false

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            RecipeCatcherTheme {
                var loadingProgress by remember { mutableIntStateOf(0) }
                var webView by remember { mutableStateOf<WebView?>(null) }

                Scaffold(
                    topBar = {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            TopAppBar(
                                title = { Text("Instagram Login") },
                                navigationIcon = {
                                    IconButton(onClick = { finish() }) {
                                        Icon(Icons.Default.Close, contentDescription = "Close")
                                    }
                                },
                                actions = {
                                    IconButton(onClick = { webView?.reload() }) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                                    }
                                    TextButton(onClick = {
                                        checkAndPersistLogin()
                                        finish()
                                    }) {
                                        Text("Done")
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
                    }
                ) { innerPadding ->
                    InstagramLoginWebView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onProgressChanged = { progress ->
                            loadingProgress = progress
                        },
                        onPageFinished = {
                            checkAndPersistLogin()
                        },
                        onWebViewCreated = { wv ->
                            webView = wv
                        }
                    )
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // CookieManager writes to disk lazily; flush so the session survives the app being killed.
        CookieManager.getInstance().flush()
    }

    private fun checkAndPersistLogin() {
        val cookies = CookieManager.getInstance().getCookie(LOGIN_URL)
        if (loginDetected || cookies == null) return
        if (cookies.contains("sessionid=") || cookies.contains("ds_user_id=")) {
            loginDetected = true
            CookieManager.getInstance().flush()
            lifecycleScope.launch {
                settingsStore.setInstagramLoggedIn(true)
                Toast.makeText(this@LoginWebViewActivity, "Instagram login detected", Toast.LENGTH_SHORT).show()
            }
        }
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

private fun injectCssFix(view: WebView?) {
    view?.evaluateJavascript(CSS_FIX_SCRIPT, null)
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InstagramLoginWebView(
    modifier: Modifier = Modifier,
    onProgressChanged: (Int) -> Unit,
    onPageFinished: () -> Unit,
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
                            injectCssFix(view)
                        }
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        onProgressChanged(1)
                        injectCssFix(view)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        injectCssFix(view)
                        onProgressChanged(100)
                        onPageFinished()
                    }
                }

                onWebViewCreated(this)
                loadUrl(LoginWebViewActivity.LOGIN_URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        }
    )
}
