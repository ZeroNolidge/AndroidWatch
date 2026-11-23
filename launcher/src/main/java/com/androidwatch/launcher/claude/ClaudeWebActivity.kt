package com.androidwatch.launcher.claude

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.androidwatch.launcher.RoundScreen
import com.androidwatch.launcher.WatchDensity
import com.androidwatch.launcher.isRoundScreen

/**
 * A browser for claude.ai that fits round screens: the page is drawn inside
 * the largest square that fits in the circle, so nothing is cut off. The
 * curved strips above and below hold the title and the controls.
 */
class ClaudeWebActivity : ComponentActivity() {

    /** How much of a round screen the page may use. */
    private enum class Fit(val label: String, val fraction: Float) {
        SQUARE("▢", 0.707f),   // largest square inside the circle: nothing cut off
        LARGER("◻", 0.82f),    // bigger page, only the very corners clipped
        FULL("◯", 1f),         // whole screen, like a normal browser
    }

    private lateinit var web: WebView
    private val prefs by lazy { getSharedPreferences("claude_web", MODE_PRIVATE) }
    private var fit by mutableStateOf(Fit.SQUARE)
    private var textZoom by mutableIntStateOf(100)
    private var pageTitle by mutableStateOf("Claude")
    private var loading by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(WatchDensity.wrap(newBase))
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RoundScreen.load(this)
        fit = runCatching { Fit.valueOf(prefs.getString("fit", Fit.SQUARE.name)!!) }.getOrDefault(Fit.SQUARE)
        textZoom = prefs.getInt("text_zoom", 100)

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.builtInZoomControls = true   // pinch to zoom
            settings.displayZoomControls = false
            settings.textZoom = textZoom
            setBackgroundColor(android.graphics.Color.BLACK)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) { loading = true }
                override fun onPageFinished(view: WebView, url: String?) {
                    loading = false
                    CookieManager.getInstance().flush() // stay signed in
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, t: String?) { pageTitle = t?.ifBlank { null } ?: "Claude" }
            }
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        if (savedInstanceState != null) web.restoreState(savedInstanceState)
        else web.loadUrl(intent?.dataString ?: HOME)

        setContent { Screen() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let(web::loadUrl)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun chooseFit(f: Fit) {
        fit = f
        prefs.edit().putString("fit", f.name).apply()
    }

    private fun changeTextZoom(delta: Int) {
        textZoom = (textZoom + delta).coerceIn(60, 200)
        web.settings.textZoom = textZoom
        prefs.edit().putInt("text_zoom", textZoom).apply()
    }

    @Composable
    private fun Screen() {
        BackHandler { if (web.canGoBack()) web.goBack() else finish() }
        val round = isRoundScreen()
        val cfg = LocalConfiguration.current
        val d = minOf(cfg.screenWidthDp, cfg.screenHeightDp).toFloat()

        if (!round) {
            // Square watch: page fills the screen, controls in a slim bar.
            Column(Modifier.fillMaxSize().background(Color.Black), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.fillMaxWidth().weight(1f)) { AndroidView({ web }, Modifier.fillMaxSize()) }
                Controls(key = 30.dp, gap = 6.dp, withFit = false, modifier = Modifier.padding(vertical = 4.dp))
            }
            return
        }

        val side = (d * fit.fraction).dp
        val strip = ((d - d * fit.fraction) / 2f).dp // curved strip left above/below the page
        val key = 26.dp
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Box(Modifier.align(Alignment.Center).size(side)) { AndroidView({ web }, Modifier.fillMaxSize()) }
            if (strip > 30.dp) {
                Text(
                    if (loading) "Loading…" else pageTitle,
                    color = Color(0xFFD97757), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.TopCenter)
                        .padding(top = strip - 18.dp, start = (d * 0.25f).dp, end = (d * 0.25f).dp),
                )
            }
            // Buttons sit just below the page (inside the strip when there's room, else over the page).
            val bottom = if (strip >= key + 12.dp) strip - key - 2.dp else (d * 0.07f).dp
            Controls(
                key = key, gap = 4.dp, withFit = true,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = bottom),
            )
        }
    }

    @Composable
    private fun Controls(key: Dp, gap: Dp, withFit: Boolean, modifier: Modifier) {
        Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
            Key("◀", key) { if (web.canGoBack()) web.goBack() else finish() }
            Key("⟳", key) { web.reload() }
            Key("A−", key) { changeTextZoom(-10) }
            Key("A+", key) { changeTextZoom(+10) }
            if (withFit) Key(fit.label, key) { chooseFit(Fit.entries[(fit.ordinal + 1) % Fit.entries.size]) }
        }
    }

    @Composable
    private fun Key(label: String, size: Dp, onClick: () -> Unit) {
        Box(
            Modifier
                .size(size)
                .background(Color(0xFF2E2E2E), RoundedCornerShape(50))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Text(label, color = Color.White, fontSize = (size.value * 0.4f).sp) }
    }

    companion object {
        const val HOME = "https://claude.ai/"

        fun open(context: Context, url: String = HOME) {
            context.startActivity(
                Intent(context, ClaudeWebActivity::class.java)
                    .setData(android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
    }
}
