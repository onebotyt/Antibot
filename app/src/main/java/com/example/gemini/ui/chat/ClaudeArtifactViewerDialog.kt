package com.example.gemini.ui.chat

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.browser.BrowserSessionManager
import java.io.File

enum class ArtifactViewMode {
    PREVIEW,
    CODE
}

/**
 * Fullscreen Claude-style Artifact & Web Preview Viewer matching Images 10 & 11.
 * - Toggle between Live Preview (HTML5/Canvas WebView) and Code (syntax layout).
 * - Overflow menu with Preview, Code, Open in Inbuilt Browser, Copy, Download, Share.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun ClaudeArtifactViewerDialog(
    artifact: ClaudeArtifactItem,
    onDismiss: () -> Unit,
    initialMode: ArtifactViewMode = if (artifact.isWebHtml) ArtifactViewMode.PREVIEW else ArtifactViewMode.CODE
) {
    val context = LocalContext.current
    var viewMode by remember { mutableStateOf(initialMode) }
    var showMenu by remember { mutableStateOf(false) }
    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    var codeText by remember(artifact) {
        val f = File(artifact.filePath)
        val text = if (f.exists() && f.isFile) {
            try { f.readText(Charsets.UTF_8) } catch (_: Exception) { artifact.codeContent.orEmpty() }
        } else {
            artifact.codeContent.orEmpty()
        }
        mutableStateOf(text)
    }

    val cleanUrl = remember(artifact, codeText) {
        val p = artifact.filePath
        if (p.isNotBlank() && (p.startsWith("http://", ignoreCase = true) || p.startsWith("https://", ignoreCase = true) || p.startsWith("file://", ignoreCase = true))) {
            p
        } else if (p.isNotBlank() && File(p).exists()) {
            "file://$p"
        } else if (codeText.isNotBlank()) {
            try {
                val previewFile = File(context.cacheDir, "preview_artifact.html")
                previewFile.writeText(codeText)
                "file://${previewFile.absolutePath}"
            } catch (_: Exception) {
                "file://$p"
            }
        } else {
            "file://$p"
        }
    }

    BackHandler {
        if (viewMode == ArtifactViewMode.PREVIEW && webViewInstance?.canGoBack() == true) {
            webViewInstance?.goBack()
        } else {
            onDismiss()
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color(0xFF131316)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                // Top Header matching Images 10 & 11
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = artifact.fileName,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (viewMode == ArtifactViewMode.PREVIEW) "Live Web Preview" else "Source Code (${artifact.fileExtension.uppercase()})",
                            fontSize = 11.5.sp,
                            color = Color(0xFF9E9E9E),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    // Reload button (in Preview mode)
                    if (viewMode == ArtifactViewMode.PREVIEW) {
                        IconButton(
                            onClick = {
                                isLoading = true
                                val f = File(artifact.filePath)
                                if (f.exists()) {
                                    try {
                                        codeText = f.readText(Charsets.UTF_8)
                                        webViewInstance?.loadDataWithBaseURL("file://${f.parent}/", codeText, "text/html", "UTF-8", null)
                                    } catch (_: Exception) {
                                        webViewInstance?.reload()
                                    }
                                } else {
                                    webViewInstance?.reload()
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Reload",
                                tint = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Inbuilt Browser quick button
                    IconButton(
                        onClick = {
                            BrowserSessionManager.instance.openUrl(cleanUrl, newTab = true)
                            Toast.makeText(context, "Opened in Inbuilt Browser tab", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.OpenInBrowser,
                            contentDescription = "Open in Inbuilt Browser",
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 3-dot overflow menu matching Image 11
                    Box {
                        IconButton(
                            onClick = { showMenu = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menu",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(Color(0xFF222226))
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Preview", color = Color.White, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                        if (viewMode == ArtifactViewMode.PREVIEW) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                onClick = {
                                    viewMode = ArtifactViewMode.PREVIEW
                                    showMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Code, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Code", color = Color.White, fontSize = 13.5.sp, modifier = Modifier.weight(1f))
                                        if (viewMode == ArtifactViewMode.CODE) {
                                            Icon(Icons.Default.Check, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                onClick = {
                                    viewMode = ArtifactViewMode.CODE
                                    showMenu = false
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.OpenInBrowser, contentDescription = null, tint = Color(0xFF10B981), modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Open in Inbuilt Browser", color = Color.White, fontSize = 13.5.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    BrowserSessionManager.instance.openUrl(cleanUrl, newTab = true)
                                    Toast.makeText(context, "Opened in Inbuilt Browser", Toast.LENGTH_SHORT).show()
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Copy Code", color = Color.White, fontSize = 13.5.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    cm.setPrimaryClip(ClipData.newPlainText(artifact.fileName, codeText))
                                    Toast.makeText(context, "Code copied to clipboard!", Toast.LENGTH_SHORT).show()
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Download", color = Color.White, fontSize = 13.5.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    try {
                                        val downloadDir = File("/sdcard/Download")
                                        if (downloadDir.exists()) {
                                            File(downloadDir, artifact.fileName).writeText(codeText, Charsets.UTF_8)
                                            Toast.makeText(context, "Saved to /sdcard/Download/${artifact.fileName}", Toast.LENGTH_LONG).show()
                                        } else {
                                            Toast.makeText(context, "Saved at ${artifact.filePath}", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Toast.makeText(context, "File available at ${artifact.filePath}", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )

                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(10.dp))
                                        Text("Share", color = Color.White, fontSize = 13.5.sp)
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, codeText)
                                        type = "text/plain"
                                    }
                                    context.startActivity(Intent.createChooser(sendIntent, "Share ${artifact.fileName}"))
                                }
                            )
                        }
                    }
                }

                // Segmented tab switch for instant Preview <-> Code switching
                TabRow(
                    selectedTabIndex = if (viewMode == ArtifactViewMode.PREVIEW) 0 else 1,
                    containerColor = Color(0xFF1E1E22),
                    contentColor = Color.White,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            Modifier.tabIndicatorOffset(tabPositions[if (viewMode == ArtifactViewMode.PREVIEW) 0 else 1]),
                            color = Color(0xFF6366F1),
                            height = 2.5.dp
                        )
                    },
                    modifier = Modifier.fillMaxWidth().height(40.dp)
                ) {
                    Tab(
                        selected = viewMode == ArtifactViewMode.PREVIEW,
                        onClick = { viewMode = ArtifactViewMode.PREVIEW },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Visibility, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Preview", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                    Tab(
                        selected = viewMode == ArtifactViewMode.CODE,
                        onClick = { viewMode = ArtifactViewMode.CODE },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Code, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Code", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    )
                }

                // Main Content View
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    if (viewMode == ArtifactViewMode.PREVIEW) {
                        // Live WebView Preview
                        AndroidView(
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        allowFileAccess = true
                                        allowContentAccess = true
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                        cacheMode = WebSettings.LOAD_NO_CACHE
                                    }
                                    webViewClient = object : WebViewClient() {
                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isLoading = false
                                        }
                                    }
                                    webChromeClient = object : WebChromeClient() {
                                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                            return true
                                        }
                                    }
                                    webViewInstance = this
                                    val f = if (artifact.filePath.isNotBlank()) File(artifact.filePath) else null
                                    if (f != null && f.exists()) {
                                        try {
                                            val html = f.readText(Charsets.UTF_8)
                                            loadDataWithBaseURL("file://${f.parent}/", html, "text/html", "UTF-8", null)
                                        } catch (_: Exception) {
                                            loadUrl(cleanUrl)
                                        }
                                    } else if (codeText.isNotBlank()) {
                                        loadDataWithBaseURL("https://localhost/", codeText, "text/html", "UTF-8", null)
                                    } else {
                                        loadUrl(cleanUrl)
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        if (isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(color = Color(0xFF6366F1), modifier = Modifier.size(32.dp))
                            }
                        }
                    } else {
                        // Code View with Line Numbers & Monospace Font
                        val scrollState = rememberScrollState()
                        val hScrollState = rememberScrollState()
                        val lines = remember(codeText) { codeText.lines() }

                        SelectionContainer {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color(0xFF0F0F12))
                                    .verticalScroll(scrollState)
                            ) {
                                // Line Numbers Column
                                Column(
                                    modifier = Modifier
                                        .background(Color(0xFF18181C))
                                        .padding(horizontal = 8.dp, vertical = 12.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    lines.indices.forEach { lineIdx ->
                                        Text(
                                            text = "${lineIdx + 1}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = Color.White.copy(alpha = 0.25f),
                                            lineHeight = 16.sp
                                        )
                                    }
                                }

                                // Code Content Column
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .horizontalScroll(hScrollState)
                                        .padding(horizontal = 12.dp, vertical = 12.dp)
                                ) {
                                    Text(
                                        text = codeText,
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color(0xFFE2E8F0),
                                        lineHeight = 16.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
