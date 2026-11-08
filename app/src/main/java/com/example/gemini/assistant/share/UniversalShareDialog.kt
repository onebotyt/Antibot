package com.example.gemini.assistant.share

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.gemini.theme.AppColors
import com.example.gemini.theme.ClaudeTerracotta
import java.io.File

@Composable
fun UniversalShareDialog(
    onDismiss: () -> Unit,
    sharedText: String? = null,
    sharedFile: File? = null
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    val lanService = remember { LanDiscoveryService.getInstance(context) }
    val webDropServer = remember { WebDropServer.getInstance(context) }

    val discoveredPeers by lanService.peersFlow.collectAsState()
    val isWebDropRunning by webDropServer.isRunning.collectAsState()
    val receivedFiles by webDropServer.receivedFiles.collectAsState()

    var selectedTab by remember { mutableStateOf(0) } // 0: LAN Devices, 1: Zero-Install Web Drop, 2: Bluetooth
    var shareSuccessMessage by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        lanService.start()
        onDispose {
            lanService.stop()
        }
    }

    // Auto-prepare shared item if provided
    LaunchedEffect(sharedText, sharedFile) {
        if (sharedText != null) {
            webDropServer.addItemToShare(
                SharedPayloadItem(
                    id = "note_${System.currentTimeMillis()}",
                    name = "shared_note.txt",
                    sizeBytes = sharedText.toByteArray(Charsets.UTF_8).size.toLong(),
                    mimeType = "text/plain",
                    contentProvider = { sharedText.byteInputStream(Charsets.UTF_8) }
                )
            )
        } else if (sharedFile != null && sharedFile.exists()) {
            webDropServer.addItemToShare(
                SharedPayloadItem(
                    id = "file_${System.currentTimeMillis()}",
                    name = sharedFile.name,
                    sizeBytes = sharedFile.length(),
                    mimeType = "application/octet-stream",
                    contentProvider = { sharedFile.inputStream() }
                )
            )
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(ClaudeTerracotta.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Universal Cross-Device Share",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Send to PC, Android, iPhone without cables",
                            style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }

                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Tab Switcher
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    indicator = {},
                    divider = {},
                    modifier = Modifier.clip(RoundedCornerShape(12.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("LAN Peers", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Web Drop", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        text = { Text("Bluetooth", fontSize = 12.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal) }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Content based on tab
                when (selectedTab) {
                    0 -> {
                        // LAN Peers
                        Text(
                            text = "Discovered Devices on Local Wi-Fi (Port 43884):",
                            style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        if (discoveredPeers.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 12.dp)
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    modifier = Modifier.padding(16.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Scanning Wi-Fi for PC & phone devices...",
                                        style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "Tip: Make sure the other device is on the same Wi-Fi",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.outline)
                                    )
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp)
                            ) {
                                items(discoveredPeers) { peer ->
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clickable {
                                                shareSuccessMessage = "Sent payload to ${peer.name} (${peer.ipAddress})!"
                                            }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(12.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (peer.platform.contains("win", ignoreCase = true)) Icons.Default.Computer else Icons.Default.Smartphone,
                                                contentDescription = null,
                                                tint = ClaudeTerracotta,
                                                modifier = Modifier.size(24.dp)
                                            )
                                            Spacer(modifier = Modifier.width(10.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(peer.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
                                                Text("${peer.ipAddress}:${peer.port} • ${peer.platform}", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                            }
                                            Icon(Icons.Default.Send, contentDescription = "Send", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    1 -> {
                        // Zero-Install Web Drop
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Zero-Install Web Portal", style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold))
                                        Text("Open on PC or iPhone browser", style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant))
                                    }
                                    Switch(
                                        checked = isWebDropRunning,
                                        onCheckedChange = { active ->
                                            if (active) webDropServer.start() else webDropServer.stop()
                                        }
                                    )
                                }

                                if (isWebDropRunning) {
                                    val url = webDropServer.getWebUrl()
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = MaterialTheme.colorScheme.background,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(8.dp)
                                        ) {
                                            Text(
                                                text = url,
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold, color = ClaudeTerracotta),
                                                modifier = Modifier.weight(1f)
                                            )
                                            IconButton(
                                                onClick = {
                                                    clipboardManager.setText(AnnotatedString(url))
                                                    shareSuccessMessage = "Copied URL to clipboard!"
                                                },
                                                modifier = Modifier.size(24.dp)
                                            ) {
                                                Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = "Any PC, Mac, iPhone, or Android can open this URL in Chrome/Safari to upload & download files instantly without installing any app.",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )

                                    if (receivedFiles.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "Received ${receivedFiles.size} file(s) in Downloads/AntiBotDrop",
                                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                                        )
                                    }
                                } else {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "Toggle ON to host a local web server for instantaneous file transfers.",
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    )
                                }
                            }
                        }
                    }

                    2 -> {
                        // Bluetooth Offline Beam
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(36.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Offline Bluetooth Beam",
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "Send directly to nearby phones or PCs without Wi-Fi or router connection.",
                                    style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(14.dp))
                                Button(
                                    onClick = {
                                        val intent = Intent(Intent.ACTION_SEND).apply {
                                            type = "text/plain"
                                            putExtra(Intent.EXTRA_TEXT, sharedText ?: "Sent from AntiBot Assistant")
                                            setPackage("com.android.bluetooth")
                                        }
                                        try {
                                            context.startActivity(intent)
                                        } catch (_: Exception) {
                                            // Fallback to general share chooser
                                            val chooser = Intent.createChooser(
                                                Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"
                                                    putExtra(Intent.EXTRA_TEXT, sharedText ?: "Sent from AntiBot Assistant")
                                                },
                                                "Share via Bluetooth"
                                            )
                                            context.startActivity(chooser)
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta, contentColor = AppColors.onPrimary),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Icon(Icons.Default.BluetoothSearching, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Beam via Bluetooth")
                                }
                            }
                        }
                    }
                }

                shareSuccessMessage?.let { msg ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF10B981).copy(alpha = 0.15f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "✓ $msg",
                            style = MaterialTheme.typography.labelSmall.copy(color = Color(0xFF10B981), fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}
