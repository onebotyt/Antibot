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

val ClaudeAccent = Color(0xFFD97757)

/** Antigravity's color across the app (same blue as its settings group). */
val AgyAccent = Color(0xFF3B82F6)

/** OpenCode's color across the app. */
val OpenCodeAccent = Color(0xFF10B981)

/** Codex's color across the app. */
val CodexAccent = Color(0xFF8B5CF6)

/** DeepSeek Harness's color across the app. */
val DshAccent = Color(0xFF4D6BFE)

fun AgentKind.label(): String = when (this) {
    AgentKind.AGY -> "Antigravity"
    AgentKind.CLAUDE -> "Claude Code"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.CODEX -> "Codex"
    AgentKind.DSH -> "DeepSeek Harness"
}

fun AgentKind.shortLabel(): String = when (this) {
    AgentKind.AGY -> "Antigravity"
    AgentKind.CLAUDE -> "Claude"
    AgentKind.OPENCODE -> "OpenCode"
    AgentKind.CODEX -> "Codex"
    AgentKind.DSH -> "DeepSeek"
}

fun AgentKind.accent(): Color = when (this) {
    AgentKind.AGY -> AgyAccent
    AgentKind.CLAUDE -> ClaudeAccent
    AgentKind.OPENCODE -> OpenCodeAccent
    AgentKind.CODEX -> CodexAccent
    AgentKind.DSH -> DshAccent
}

