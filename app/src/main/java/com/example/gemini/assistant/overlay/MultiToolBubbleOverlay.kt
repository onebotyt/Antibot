package com.example.gemini.assistant.overlay

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ClaudeTerracotta

enum class AssistantToolType {
    DRAW_SCREEN,
    READ_SCREEN,
    SMART_TASKS,
    DEVICE_ACTIONS,
    UNIVERSAL_SHARE,
    CHAT_WINDOW,
    VOICE_ASSISTANT
}

data class PaletteToolItem(
    val type: AssistantToolType,
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconColor: Color
)

@Composable
fun MultiToolBubblePalette(
    isExpanded: Boolean,
    onSelectTool: (AssistantToolType) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tools = remember {
        listOf(
            PaletteToolItem(
                type = AssistantToolType.DRAW_SCREEN,
                title = "Point & Draw Screen",
                subtitle = "Circle anything to ask AI",
                icon = Icons.Default.Edit,
                iconColor = Color(0xFF38BDF8)
            ),
            PaletteToolItem(
                type = AssistantToolType.READ_SCREEN,
                title = "Read Entire Screen",
                subtitle = "Extract text & summarize",
                icon = Icons.Default.Visibility,
                iconColor = Color(0xFF10B981)
            ),
            PaletteToolItem(
                type = AssistantToolType.SMART_TASKS,
                title = "Smart Tasks & Notes",
                subtitle = "To-Do list & alarm reminders",
                icon = Icons.Default.Checklist,
                iconColor = ClaudeTerracotta
            ),
            PaletteToolItem(
                type = AssistantToolType.DEVICE_ACTIONS,
                title = "Quick Device Actions",
                subtitle = "Set alarm, call, SMS, torch",
                icon = Icons.Default.FlashOn,
                iconColor = Color(0xFFEAB308)
            ),
            PaletteToolItem(
                type = AssistantToolType.UNIVERSAL_SHARE,
                title = "Cross-Device Share",
                subtitle = "PC / iPhone / LAN Web Drop",
                icon = Icons.Default.Share,
                iconColor = Color(0xFFA855F7)
            ),
            PaletteToolItem(
                type = AssistantToolType.CHAT_WINDOW,
                title = "Assistant Chat & Code",
                subtitle = "Open full floating workspace",
                icon = Icons.Default.Chat,
                iconColor = MaterialTheme.colorScheme.primary
            )
        )
    }

    AnimatedVisibility(
        visible = isExpanded,
        enter = scaleIn(initialScale = 0.85f, animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
        exit = scaleOut(targetScale = 0.85f, animationSpec = tween(150)) + fadeOut(),
        modifier = modifier
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 18.dp,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                Brush.linearGradient(
                    colors = listOf(
                        ClaudeTerracotta.copy(alpha = 0.4f),
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
                    )
                )
            ),
            modifier = Modifier
                .widthIn(max = 340.dp)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(ClaudeTerracotta.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "AntiBot Tools",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close Palette",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tool Items Grid / List
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (tool in tools) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onSelectTool(tool.type)
                                }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Box(
                                    contentAlignment = Alignment.Center,
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(tool.iconColor.copy(alpha = 0.15f))
                                ) {
                                    Icon(
                                        imageVector = tool.icon,
                                        contentDescription = null,
                                        tint = tool.iconColor,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tool.title,
                                        style = MaterialTheme.typography.bodyMedium.copy(
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                    )
                                    Text(
                                        text = tool.subtitle,
                                        style = MaterialTheme.typography.labelSmall.copy(
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 11.5.sp
                                        )
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
