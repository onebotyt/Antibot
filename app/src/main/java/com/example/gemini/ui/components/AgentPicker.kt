package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.AgentKind

val LocalAccent = Color(0xFF10B981)

val ClaudeAccent = Color(0xFFD97757)

/** Antigravity's color across the app (same blue as its settings group). */
val AgyAccent = Color(0xFF3B82F6)

/** OpenCode's color across the app. */
val OpenCodeAccent = Color(0xFF10B981)

/** Codex's color across the app. */
val CodexAccent = Color(0xFF8B5CF6)

fun AgentKind.label(): String = when (this) {
    AgentKind.LOCAL -> "AntiBot Local Model (0 Tokens)"
    AgentKind.AGY -> "Antigravity"
    AgentKind.CLAUDE -> "Claude Code"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.CODEX -> "Codex"
}

fun AgentKind.shortLabel(): String = when (this) {
    AgentKind.LOCAL -> "Local"
    AgentKind.AGY -> "Antigravity"
    AgentKind.CLAUDE -> "Claude"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.CODEX -> "Codex"
}

fun AgentKind.accent(): Color = when (this) {
    AgentKind.AGY -> AgyAccent
    AgentKind.LOCAL -> LocalAccent
    AgentKind.CLAUDE -> ClaudeAccent
    AgentKind.OPENCODE -> OpenCodeAccent
    AgentKind.CODEX -> CodexAccent
}

/**
 * Top-corner dropdown selector for switching between enabled CLI agents.
 */
@Composable
fun AgentTopBarDropdown(
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    modifier: Modifier = Modifier,
    agents: Collection<AgentKind> = AgentKind.entries,
    statusColors: Map<AgentKind, Color> = emptyMap()
) {
    var expanded by remember { mutableStateOf(false) }
    val displayAgents = AgentKind.entries.filter { it in agents }
    if (displayAgents.isEmpty()) return

    val accent = selected.accent()
    val dotColor = statusColors[selected] ?: accent

    Box(modifier = modifier) {
        Surface(
            onClick = { expanded = true },
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
            modifier = Modifier.height(32.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
                Text(
                    text = selected.shortLabel(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1
                )
                Icon(
                    imageVector = Icons.Default.ArrowDropDown,
                    contentDescription = "Switch Agent",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
            shadowElevation = 8.dp,
            modifier = Modifier.width(230.dp)
        ) {
            Text(
                text = "SELECT CLI AGENT",
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.8.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            HorizontalDivider(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                modifier = Modifier.padding(bottom = 4.dp)
            )
            displayAgents.forEach { agent ->
                val isCurrent = agent == selected
                val itemAccent = agent.accent()
                val itemDot = statusColors[agent] ?: itemAccent
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(itemDot)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = agent.label(),
                                    fontSize = 13.sp,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isCurrent) itemAccent else MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (isCurrent) {
                                Text(
                                    text = "✓",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = itemAccent
                                )
                            }
                        }
                    },
                    onClick = {
                        expanded = false
                        if (agent != selected) {
                            onSelect(agent)
                        }
                    },
                    modifier = Modifier
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (isCurrent) itemAccent.copy(alpha = 0.10f) else Color.Transparent)
                )
            }
        }
    }
}

/**
 * Segmented picker for the agent of a new chat. Shows only [agents] (the enabled ones) and nothing when there is
 * a single one; [statusColors] adds each agent's status dot.
 */
@Composable
fun AgentPicker(
    selected: AgentKind,
    onSelect: (AgentKind) -> Unit,
    modifier: Modifier = Modifier,
    agents: Collection<AgentKind> = AgentKind.entries,
    statusColors: Map<AgentKind, Color> = emptyMap()
) {
    val displayAgents = AgentKind.entries.filter { it in agents && it != AgentKind.LOCAL }
    if (displayAgents.size < 2) return
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f), shape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        displayAgents.forEach { agent ->
            val isSelected = agent == selected
            val accent = agent.accent()
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(17.dp))
                    .background(if (isSelected) accent.copy(alpha = 0.16f) else Color.Transparent)
                    .clickable { onSelect(agent) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                statusColors[agent]?.let { dot ->
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
                    Spacer(Modifier.width(7.dp))
                }
                Text(
                    text = agent.label(),
                    fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}



/** Small badge marking a Local 0-token conversation in lists. */
@Composable
fun LocalBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LocalAccent.copy(alpha = 0.16f))
            .padding(horizontal = 5.dp, vertical = 1.dp)
    ) {
        Text(text = "Local (0 Tokens)", fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = LocalAccent)
    }
}
