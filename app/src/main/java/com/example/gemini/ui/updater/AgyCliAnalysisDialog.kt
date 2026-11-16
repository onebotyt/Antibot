package com.example.gemini.ui.updater

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.remote.services.AgyCliAnalysisReport
import com.example.gemini.theme.GeminiBlue
import com.example.gemini.theme.QuotaGreen

/**
 * Shows the result of an Antigravity CLI + model analysis pass: whether a CLI
 * update is available, which models were newly introduced, and what the release
 * notes say about them.
 */
@Composable
fun AgyCliAnalysisDialog(
    report: AgyCliAnalysisReport,
    isChecking: Boolean = false,
    onRefresh: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val surfaceColor = MaterialTheme.colorScheme.surface
    val onSurfaceColor = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariantColor = MaterialTheme.colorScheme.onSurfaceVariant
    val surfaceVariantColor = MaterialTheme.colorScheme.surfaceVariant
    val uriHandler = LocalUriHandler.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = true)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = surfaceColor,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
                .border(
                    BorderStroke(1.dp, onSurfaceColor.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(20.dp)
                )
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
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(GeminiBlue.copy(alpha = 0.15f))
                            .border(1.dp, GeminiBlue.copy(alpha = 0.3f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Antigravity",
                            tint = GeminiBlue,
                            modifier = Modifier.size(22.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Antigravity CLI & Models",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = onSurfaceColor
                            )
                            if (report.hasCliUpdate || report.newModelsDetected.isNotEmpty()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = CircleShape,
                                    color = QuotaGreen.copy(alpha = 0.15f),
                                    contentColor = QuotaGreen
                                ) {
                                    Text(
                                        text = "NEW",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Text(
                            text = buildString {
                                append("CLI ")
                                append(report.currentCliVersion)
                                val latest = report.latestCliVersion
                                if (latest != null && latest != report.currentCliVersion) {
                                    append("  →  ")
                                    append(latest)
                                } else {
                                    append("  ·  up to date")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = onSurfaceVariantColor
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (isChecking) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                        color = GeminiBlue
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Staged-update banner
                    if (!report.updateMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = GeminiBlue.copy(alpha = 0.10f),
                            border = BorderStroke(1.dp, GeminiBlue.copy(alpha = 0.25f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.SystemUpdateAlt,
                                    contentDescription = null,
                                    tint = GeminiBlue,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = report.updateMessage,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = onSurfaceColor
                                )
                            }
                        }
                    }

                    // Newly detected models
                    if (report.newModelsDetected.isNotEmpty()) {
                        SectionLabel("✨ NEWLY INTRODUCED MODELS", QuotaGreen)
                        report.newModelsDetected.forEach { model ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = surfaceVariantColor.copy(alpha = 0.5f),
                                border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.3f)),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = model.displayName,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
                                            color = onSurfaceColor
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        if (model.thinkingLevels.isNotEmpty()) {
                                            Text(
                                                text = model.thinkingLevels.joinToString(" · "),
                                                fontSize = 11.sp,
                                                color = GeminiBlue,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                    if (model.description.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = model.description,
                                            fontSize = 12.sp,
                                            color = onSurfaceVariantColor
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        MetaChip(model.quotaGroup)
                                        if (model.supportsTools) MetaChip("Tools")
                                        model.tier?.let { MetaChip(it) }
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    // Models named in the release notes
                    if (report.mentionedModels.isNotEmpty()) {
                        SectionLabel("MODELS MENTIONED IN RELEASE", onSurfaceVariantColor)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = surfaceVariantColor.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                report.mentionedModels.forEach { name ->
                                    Text(
                                        text = "• $name",
                                        fontSize = 12.sp,
                                        color = onSurfaceColor.copy(alpha = 0.9f)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Release notes
                    if (!report.releaseNotesSummary.isNullOrBlank()) {
                        SectionLabel("RELEASE NOTES", onSurfaceVariantColor)
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = surfaceVariantColor.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = report.releaseNotesSummary,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = onSurfaceColor.copy(alpha = 0.9f),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    Text(
                        text = "Active models in daemon: ${report.allActiveModels.size}",
                        fontSize = 12.sp,
                        color = onSurfaceVariantColor
                    )
                }

                Spacer(modifier = Modifier.height(18.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!report.releaseUrl.isNullOrBlank()) {
                        TextButton(
                            onClick = {
                                runCatching { uriHandler.openUri(report.releaseUrl!!) }
                            }
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Release notes")
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TextButton(onClick = onRefresh, enabled = !isChecking) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Refresh")
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    Button(
                        onClick = onDismiss,
                        colors = ButtonDefaults.buttonColors(containerColor = GeminiBlue)
                    ) {
                        Text("Done")
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = color,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 8.dp)
    )
}

@Composable
private fun MetaChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
    ) {
        Text(
            text = text,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
