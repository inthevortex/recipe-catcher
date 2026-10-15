package org.eu.angsumanray.recipecatcher.capture

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Instagram Login") },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(Icons.Default.Close, contentDescription = "Close")
                                }
                            },
                            actions = {
                                TextButton(onClick = {
                                    checkAndPersistLogin()
                                    finish()
                                }) {
                                    Text("Done")
                                }
                            }
                        )
                    }
                ) { innerPadding ->
                    InstagramLoginWebView(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onPageFinished = {
                            checkAndPersistLogin()
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun InstagramLoginWebView(
    modifier: Modifier = Modifier,
    onPageFinished: () -> Unit
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        onPageFinished()
                    }
                }

                loadUrl(LoginWebViewActivity.LOGIN_URL)
            }
        },
        onRelease = { webView ->
            webView.stopLoading()
            webView.destroy()
        }
    )
}
