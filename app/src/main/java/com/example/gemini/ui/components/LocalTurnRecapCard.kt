package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.context.LocalTurnRecap

/**
 * LocalTurnRecapCard: Displays on-device turn intelligence with zero remote token overhead.
 * Offers 1-tap next-step prompt insertions so Antigravity doesn't need to spend tokens on follow-ups.
 */
@Composable
fun LocalTurnRecapCard(
    recap: LocalTurnRecap?,
    onSelectSuggestion: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = recap != null, modifier = modifier) {
        if (recap == null) return@AnimatedVisibility

        val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        val bgColor = if (isDark) Color(0xFF131F1C) else Color(0xFFE8F5E9)
        val borderColor = if (isDark) Color(0xFF10B981).copy(alpha = 0.35f) else Color(0xFF81C784)
        val accentColor = if (isDark) Color(0xFF34D399) else Color(0xFF2E7D32)
        val textColor = if (isDark) Color(0xFFE5E7EB) else Color(0xFF1F2937)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(bgColor)
                .border(1.dp, borderColor, RoundedCornerShape(12.dp))
                .padding(10.dp)
        ) {
            // Header: Status + Zero Token Badge + Close
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Done,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = recap.summary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = textColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Zero-Token Badge
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = accentColor.copy(alpha = 0.15f),
                        border = androidx.compose.foundation.BorderStroke(0.5.dp, accentColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "0 Tokens (Local)",
                            fontSize = 9.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = accentColor,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }

                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier
                            .size(16.dp)
                            .clip(CircleShape)
                            .clickable { onDismiss() }
                    )
                }
            }

            // Modified files chips if any
            if (recap.modifiedFiles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recap.modifiedFiles.take(5).forEach { fileName ->
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            )
                        ) {
                            Text(
                                text = fileName,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            // Suggested Next Actions
            if (recap.suggestedNextSteps.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    recap.suggestedNextSteps.forEach { suggestion ->
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = accentColor.copy(alpha = if (isDark) 0.2f else 0.12f),
                            border = androidx.compose.foundation.BorderStroke(
                                0.8.dp,
                                accentColor.copy(alpha = 0.45f)
                            ),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSelectSuggestion(suggestion) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "↳ $suggestion",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) Color(0xFFA7F3D0) else Color(0xFF1B5E20)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
