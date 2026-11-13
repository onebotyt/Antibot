package com.example.gemini.ui.harness

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gemini.data.agent.dsh.HarnessController
import com.example.gemini.theme.AppColors

/**
 * The app's main shell: the DeepSeek Harness web UI rendered full screen.
 *
 * The harness page gets a small theme-matched floating button (injected from
 * the app) that calls back through [HarnessJsBridge] to open the app's own
 * settings, so the harness owns the screen while the old UI settings stay
 * reachable.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HarnessScreen(
    isVisible: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by HarnessController.state.collectAsState()

    // Plain holders (not Compose state) so writes from the AndroidView factory/update
    // never happen during composition.
    val webViewHolder = remember { arrayOfNulls<WebView>(1) }
    val loadedUrlHolder = remember { arrayOfNulls<String>(1) }

    val backgroundColor = AppColors.background
    val readyUrl = (state as? HarnessController.UiState.Ready)?.url
    var canGoBack by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        HarnessController.refresh(context)
    }

    Box(modifier = modifier.fillMaxSize().background(backgroundColor)) {
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    setBackgroundColor(backgroundColor.toArgb())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mediaPlaybackRequiresUserGesture = false
                    // The launch URL stores the harness session cookie; keep it so the
                    // bare URL keeps working after the first load.
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    addJavascriptInterface(HarnessJsBridge(onOpenSettings), "AntiBotBridge")
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean = false

                        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                            super.doUpdateVisitedHistory(view, url, isReload)
                            canGoBack = view?.canGoBack() == true
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            canGoBack = view?.canGoBack() == true
                            CookieManager.getInstance().flush()
                            view?.evaluateJavascript(HarnessFabScript.build(backgroundColor), null)
                        }
                    }
                    webViewHolder[0] = this
                }
            },
            update = { view ->
                val target = readyUrl
                if (target != null && target != loadedUrlHolder[0]) {
                    loadedUrlHolder[0] = target
                    view.loadUrl(target)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Status overlay until the harness answers; hidden once it is ready.
        when (val current = state) {
            is HarnessController.UiState.Ready -> Unit
            HarnessController.UiState.Idle, HarnessController.UiState.Starting -> HarnessStatusOverlay(
                title = "Starting DeepSeek Harness",
                body = "Launching the harness and waiting for it to answer…",
                busy = true,
                onRetry = null
            )

            is HarnessController.UiState.Error -> HarnessStatusOverlay(
                title = "DeepSeek Harness unavailable",
                body = current.message,
                busy = false,
                onRetry = { HarnessController.refresh(context, force = true) }
            )
        }
    }

    LaunchedEffect(isVisible) {
        if (isVisible) {
            webViewHolder[0]?.onResume()
            // Re-check the harness when returning to it: it may have exited while
            // another view was open (a dead harness is restarted by refresh()).
            HarnessController.refresh(context)
        } else {
            webViewHolder[0]?.onPause()
        }
    }

    BackHandler(enabled = isVisible && canGoBack) {
        webViewHolder[0]?.goBack()
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                webViewHolder[0]?.stopLoading()
                webViewHolder[0]?.destroy()
            } catch (_: Exception) {
            }
            webViewHolder[0] = null
        }
    }
}

@Composable
private fun HarnessStatusOverlay(
    title: String,
    body: String,
    busy: Boolean,
    onRetry: (() -> Unit)?
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (busy) {
            CircularProgressIndicator(color = AppColors.primary)
        } else {
            Text("\u26A0", fontSize = 30.sp, color = AppColors.warning)
        }
        Spacer(Modifier.height(18.dp))
        Text(
            text = title,
            color = AppColors.onBackground,
            fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = body,
            color = AppColors.textSecondary,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary)
            ) {
                Text("Retry")
            }
        }
    }
}

/** Bridge the injected harness button calls into the app. */
private class HarnessJsBridge(private val onOpenSettings: () -> Unit) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun openSettings() {
        mainHandler.post { onOpenSettings() }
    }

    @JavascriptInterface
    fun openSettingsSection(section: String?) {
        mainHandler.post { onOpenSettings() }
    }
}

/** Injects a theme-matched floating settings button into the harness page. */
private object HarnessFabScript {
    fun build(color: Color): String {
        val hex = String.format("#%06X", 0xFFFFFF and color.toArgb())
        return """
            (function(){
              function inject(){
                try{
                  if(document.getElementById('antibot-settings-fab')) return;
                  var b=document.createElement('div');
                  b.id='antibot-settings-fab';
                  b.setAttribute('role','button');
                  b.setAttribute('aria-label','AntiBot Settings');
                  b.title='AntiBot Settings';
                  b.innerHTML='&#9881;';
                  b.style.cssText='position:fixed;right:12px;top:45%;transform:translateY(-50%);width:42px;height:42px;border-radius:21px;z-index:2147483647;display:flex;align-items:center;justify-content:center;font-size:20px;line-height:1;color:#fff;background:$hex;box-shadow:0 4px 16px rgba(0,0,0,.45);cursor:pointer;opacity:.92;-webkit-tap-highlight-color:transparent;user-select:none;';
                  b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();try{if(window.AntiBotBridge&&window.AntiBotBridge.openSettings){window.AntiBotBridge.openSettings();}}catch(err){}},true);
                  (document.body||document.documentElement).appendChild(b);
                }catch(err){}
              }
              inject();
              if(!window.__antibotFabObserver){
                try{
                  window.__antibotFabObserver=new MutationObserver(function(){if(!document.getElementById('antibot-settings-fab'))inject();});
                  window.__antibotFabObserver.observe(document.documentElement,{childList:true,subtree:true});
                }catch(err){}
              }
            })();
        """.trimIndent()
    }
}
