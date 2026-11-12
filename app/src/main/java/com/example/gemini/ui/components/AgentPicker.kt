package com.example.gemini.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
