package com.example.gemini.ui.models

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*
import com.example.gemini.ui.components.ClaudeAccent
import com.example.gemini.ui.components.CodexAccent
import com.example.gemini.ui.components.OpenCodeAccent
import kotlinx.coroutines.launch

sealed interface ModelItemEntry {
    val agentKind: AgentKind

    data class Single(
        val id: String,
        val title: String,
        val badge: String,
        val badgeColor: Color,
        val subtitle: String? = null,
        override val agentKind: AgentKind = AgentKind.AGY
    ) : ModelItemEntry

    data class TierGroup(
        val baseName: String,
        val badge: String,
        val badgeColor: Color,
        val tiers: List<AiModel>,
        override val agentKind: AgentKind = AgentKind.AGY
    ) : ModelItemEntry
}

data class EngineSection(
    val agentKind: AgentKind,
    val title: String,
    val badge: String,
    val accentColor: Color,
    val entries: List<ModelItemEntry>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    selectedModelId: String,
    availableModels: List<AiModel>,
    quotas: List<ModelQuota>,
    quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    currentAgent: AgentKind = AgentKind.AGY,
    enabledAgents: Collection<AgentKind> = listOf(AgentKind.AGY, AgentKind.CLAUDE, AgentKind.CODEX, AgentKind.OPENCODE),
    claudeBackend: com.example.gemini.data.agent.claude.ClaudeChatBackend? = null,
    claudeAccount: com.example.gemini.data.agent.claude.ClaudeAccountManager? = null,
    codexBackend: com.example.gemini.data.agent.codex.CodexChatBackend? = null,
    openCodeBackend: com.example.gemini.data.agent.opencode.OpenCodeChatBackend? = null,
    onSelectAgentAndModel: ((AgentKind, String) -> Unit)? = null,
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    val devTeamMode by authPrefs.devTeamExecutionMode.collectAsState(initial = authPrefs.getDevTeamExecutionModeSync())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // External agent models from backends
    val claudeBackendModels by claudeBackend?.availableModels?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val claudeSelModelId by claudeBackend?.selectedModelId?.collectAsState() ?: remember { mutableStateOf("") }
    val codexBackendModels by codexBackend?.availableModels?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val codexSelModelId by codexBackend?.selectedModelId?.collectAsState() ?: remember { mutableStateOf("") }
    val openCodeBackendModels by openCodeBackend?.availableModels?.collectAsState() ?: remember { mutableStateOf(emptyList()) }
    val openCodeSelModelId by openCodeBackend?.selectedModelId?.collectAsState() ?: remember { mutableStateOf("") }

    var selectedFilter by remember { mutableStateOf<AgentKind?>(null) }

    // Build the clean unified list of models per engine
    val engineSections = remember(
        availableModels,
        claudeBackendModels,
        codexBackendModels,
        openCodeBackendModels,
        enabledAgents
    ) {
        val sections = mutableListOf<EngineSection>()

        // 1. ⚡ Antigravity (Gemini) Engine
        if (AgentKind.AGY in enabledAgents) {
            val agyList = mutableListOf<ModelItemEntry>()

            // Cloud models grouped by baseName to consolidate High/Medium/Low into 1 card
            val cloudModels = availableModels
            val grouped = cloudModels.groupBy { it.baseName }
            grouped.forEach { (baseName, models) ->
                val badgeColor = when (models.first().family) {
                    ModelFamily.CLAUDE -> ClaudeTerracotta
                    ModelFamily.GEMINI -> GeminiBlue
                    else -> Color(0xFF8B5CF6)
                }

                if (models.size > 1 && models.any { it.tier != null }) {
                    agyList.add(
                        ModelItemEntry.TierGroup(
                            baseName = baseName,
                            badge = "Cloud",
                            badgeColor = badgeColor,
                            tiers = models.sortedBy { m ->
                                when (m.tier?.lowercase()) {
                                    "high" -> 1
                                    "medium", "med" -> 2
                                    "low" -> 3
                                    else -> 4
                                }
                            },
                            agentKind = AgentKind.AGY
                        )
                    )
                } else {
                    models.forEach { model ->
                        agyList.add(
                            ModelItemEntry.Single(
                                id = model.id,
                                title = model.displayName,
                                badge = "Cloud",
                                badgeColor = badgeColor,
                                agentKind = AgentKind.AGY
                            )
                        )
                    }
                }
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.AGY,
                    title = "Antigravity (Gemini)",
                    badge = "⚡ Fast & Multimodal",
                    accentColor = GeminiBlue,
                    entries = agyList
                )
            )
        }

        // 2. 🟣 Claude Code CLI Engine
        // 2. 🟣 Claude Code CLI Engine
        if (AgentKind.CLAUDE in enabledAgents) {
            val claudeList = mutableListOf<ModelItemEntry>()
            val isClaudeLoggedIn = claudeAccount?.isLoggedIn == true
            if (claudeBackendModels.isNotEmpty()) {
                claudeBackendModels.forEach { model ->
                    claudeList.add(
                        ModelItemEntry.Single(
                            id = model.id,
                            title = model.displayName.ifBlank { model.id },
                            badge = "Claude Code",
                            badgeColor = ClaudeTerracotta,
                            subtitle = model.description.ifBlank { null },
                            agentKind = AgentKind.CLAUDE
                        )
                    )
                }
            } else if (isClaudeLoggedIn) {
                claudeList.add(
                    ModelItemEntry.Single(
                        id = "claude-sync",
                        title = "Sync Claude Models",
                        badge = "Connected",
                        badgeColor = ClaudeTerracotta,
                        subtitle = "Claude CLI authenticated. Tap to fetch installed models.",
                        agentKind = AgentKind.CLAUDE
                    )
                )
            } else {
                claudeList.add(
                    ModelItemEntry.Single(
                        id = "claude-signin",
                        title = "Not signed in",
                        badge = "Sign In Required",
                        badgeColor = ClaudeTerracotta,
                        subtitle = "Sign in to your Anthropic account in the Agents drawer to access Claude models.",
                        agentKind = AgentKind.CLAUDE
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.CLAUDE,
                    title = "Claude Code CLI",
                    badge = if (isClaudeLoggedIn) "🟣 Connected" else "🟣 Not Signed In",
                    accentColor = ClaudeTerracotta,
                    entries = claudeList
                )
            )
        }

        // 3. 🟢 Codex CLI Engine
        if (AgentKind.CODEX in enabledAgents) {
            val codexList = mutableListOf<ModelItemEntry>()
            val isCodexReady = codexBackend?.state?.value == com.example.gemini.data.agent.codex.CodexState.READY
            if (codexBackendModels.isNotEmpty()) {
                codexBackendModels.forEach { model ->
                    codexList.add(
                        ModelItemEntry.Single(
                            id = model.id,
                            title = model.displayName.ifBlank { model.id },
                            badge = "Codex",
                            badgeColor = CodexAccent,
                            subtitle = model.description.ifBlank { null },
                            agentKind = AgentKind.CODEX
                        )
                    )
                }
            } else if (isCodexReady) {
                codexList.add(
                    ModelItemEntry.Single(
                        id = "codex-sync",
                        title = "Sync Codex Models",
                        badge = "Connected",
                        badgeColor = CodexAccent,
                        subtitle = "Codex CLI authenticated. Tap to fetch models.",
                        agentKind = AgentKind.CODEX
                    )
                )
            } else {
                codexList.add(
                    ModelItemEntry.Single(
                        id = "codex-signin",
                        title = "Not signed in",
                        badge = "Sign In Required",
                        badgeColor = CodexAccent,
                        subtitle = "Sign in with ChatGPT in the Agents drawer to access Codex models.",
                        agentKind = AgentKind.CODEX
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.CODEX,
                    title = "Codex CLI",
                    badge = if (isCodexReady) "🟢 Connected" else "🟢 Not Signed In",
                    accentColor = CodexAccent,
                    entries = codexList
                )
            )
        }

        // 4. 🌐 OpenCode CLI Engine
        if (AgentKind.OPENCODE in enabledAgents) {
            val ocList = mutableListOf<ModelItemEntry>()
            val isOpenCodeReady = openCodeBackend?.state?.value == com.example.gemini.data.agent.opencode.OpenCodeState.READY
            if (openCodeBackendModels.isNotEmpty()) {
                openCodeBackendModels.forEach { model ->
                    ocList.add(
                        ModelItemEntry.Single(
                            id = model.id,
                            title = model.displayName.ifBlank { model.id },
                            badge = "OpenCode",
                            badgeColor = OpenCodeAccent,
                            subtitle = model.description.ifBlank { null },
                            agentKind = AgentKind.OPENCODE
                        )
                    )
                }
            } else if (isOpenCodeReady) {
                ocList.add(
                    ModelItemEntry.Single(
                        id = "opencode-sync",
                        title = "No OpenCode Providers",
                        badge = "Configure",
                        badgeColor = OpenCodeAccent,
                        subtitle = "OpenCode is ready. Add provider API keys in Settings → OpenCode.",
                        agentKind = AgentKind.OPENCODE
                    )
                )
            } else {
                ocList.add(
                    ModelItemEntry.Single(
                        id = "opencode-setup",
                        title = "OpenCode Not Set Up",
                        badge = "Setup Required",
                        badgeColor = OpenCodeAccent,
                        subtitle = "Install and start OpenCode CLI from the Agents drawer.",
                        agentKind = AgentKind.OPENCODE
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.OPENCODE,
                    title = "OpenCode CLI",
                    badge = if (isOpenCodeReady) "🌐 Ready" else "🌐 Not Set Up",
                    accentColor = OpenCodeAccent,
                    entries = ocList
                )
            )
        }

        sections
    }

    val visibleSections = remember(engineSections, selectedFilter) {
        if (selectedFilter == null) engineSections
        else engineSections.filter { it.agentKind == selectedFilter }
    }

    val selectModelAction = { agent: AgentKind, modelId: String ->
        when (modelId) {
            "claude-signin" -> {
                android.widget.Toast.makeText(context, "Open Agents drawer (top-left) to sign in to Claude Code", android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }
            "claude-sync" -> {
                claudeAccount?.refreshStatus()
                coroutineScope.launch { claudeBackend?.refreshInfo(force = true) }
            }
            "codex-signin" -> {
                android.widget.Toast.makeText(context, "Open Agents drawer (top-left) to sign in to Codex", android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }
            "codex-sync" -> {
                coroutineScope.launch {
                    codexBackend?.refreshStatus()
                    codexBackend?.refreshModels()
                }
            }
            "opencode-setup", "opencode-sync" -> {
                android.widget.Toast.makeText(context, "Open Agents drawer or Settings to configure OpenCode", android.widget.Toast.LENGTH_LONG).show()
                onDismiss()
            }
            else -> {
                if (onSelectAgentAndModel != null) {
                    onSelectAgentAndModel(agent, modelId)
                } else {
                    onSelectModel(modelId)
                }
                onDismiss()
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = { BottomSheetDefaults.DragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header: [✕] on left, "Select Model" in center bold
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Text(
                    text = "Select Model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp
                )
            }

            // Quick Filter Pills Row across all active CLIs
            if (engineSections.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // "All" filter pill
                    FilterChip(
                        selected = selectedFilter == null,
                        onClick = { selectedFilter = null },
                        label = { Text("All (${engineSections.sumOf { it.entries.size }})", fontSize = 12.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    )

                    engineSections.forEach { section ->
                        val isSelected = selectedFilter == section.agentKind
                        FilterChip(
                            selected = isSelected,
                            onClick = { selectedFilter = if (isSelected) null else section.agentKind },
                            label = {
                                Text(
                                    text = "${section.title} (${section.entries.size})",
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = section.accentColor.copy(alpha = 0.2f),
                                selectedLabelColor = section.accentColor
                            )
                        )
                    }
                }
            }

            // Scrollable Content showing grouped models per engine
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                visibleSections.forEach { section ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Section Header
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(section.accentColor)
                                )
                                Text(
                                    text = section.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Text(
                                text = section.badge,
                                fontSize = 11.sp,
                                color = section.accentColor,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Grouped Rounded Container for Models
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                section.entries.forEachIndexed { index, entry ->
                                    when (entry) {
                                        is ModelItemEntry.Single -> {
                                            val isSelected = when (entry.agentKind) {
                                                AgentKind.AGY -> currentAgent == AgentKind.AGY && selectedModelId == entry.id
                                                AgentKind.CLAUDE -> currentAgent == AgentKind.CLAUDE && (
                                                    claudeSelModelId == entry.id || (claudeSelModelId.isBlank() && entry.id.contains("sonnet"))
                                                )
                                                AgentKind.CODEX -> currentAgent == AgentKind.CODEX && (
                                                    codexSelModelId == entry.id || (codexSelModelId.isBlank() && entry.id.contains("4o"))
                                                )
                                                AgentKind.OPENCODE -> currentAgent == AgentKind.OPENCODE && (
                                                    openCodeSelModelId == entry.id
                                                )
                                                else -> false
                                            }

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        selectModelAction(entry.agentKind, entry.id)
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp)
                                            ) {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(modifier = Modifier.weight(1f)) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Text(
                                                                text = entry.title,
                                                                style = MaterialTheme.typography.bodyLarge,
                                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                                                color = MaterialTheme.colorScheme.onSurface,
                                                                fontSize = 15.sp
                                                            )

                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(6.dp))
                                                                    .background(entry.badgeColor.copy(alpha = 0.16f))
                                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                                            ) {
                                                                Text(
                                                                    text = entry.badge,
                                                                    fontSize = 10.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = entry.badgeColor
                                                                )
                                                            }

                                                            if (isSelected) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(6.dp))
                                                                        .background(Color(0xFF10B981).copy(alpha = 0.18f))
                                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                                ) {
                                                                    Text(
                                                                        text = "Active",
                                                                        fontSize = 10.5.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = Color(0xFF10B981)
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        if (!entry.subtitle.isNullOrBlank()) {
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = entry.subtitle,
                                                                fontSize = 11.5.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = section.accentColor,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        is ModelItemEntry.TierGroup -> {
                                            val isGroupActive = currentAgent == AgentKind.AGY && entry.tiers.any { it.id == selectedModelId }

                                            Surface(
                                                shape = RoundedCornerShape(14.dp),
                                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                                border = BorderStroke(1.dp, if (isGroupActive) Color(0xFF3B82F6).copy(alpha = 0.3f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f)),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                            ) {
                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 12.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Text(
                                                            text = entry.baseName,
                                                            style = MaterialTheme.typography.bodyLarge,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface,
                                                            fontSize = 15.5.sp
                                                        )

                                                        if (isGroupActive) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(8.dp))
                                                                    .background(Color(0xFF3B82F6).copy(alpha = 0.18f))
                                                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                                                            ) {
                                                                Text(
                                                                    text = "Active",
                                                                    fontSize = 11.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color(0xFF60A5FA)
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(10.dp))

                                                    // Selectable mode chips (e.g. Flash High / Med / Low)
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                    ) {
                                                        entry.tiers.forEach { tierModel ->
                                                            val isTierSelected = currentAgent == AgentKind.AGY && selectedModelId == tierModel.id
                                                            val tierLabel = tierModel.tier ?: tierModel.displayName

                                                            Surface(
                                                                onClick = {
                                                                    selectModelAction(entry.agentKind, tierModel.id)
                                                                },
                                                                shape = RoundedCornerShape(10.dp),
                                                                color = if (isTierSelected) Color(0xFF3B82F6) else MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                                                border = BorderStroke(
                                                                    1.dp,
                                                                    if (isTierSelected) Color(0xFF3B82F6) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
                                                                ),
                                                                modifier = Modifier.defaultMinSize(minHeight = 36.dp)
                                                            ) {
                                                                Row(
                                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                                                    verticalAlignment = Alignment.CenterVertically,
                                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                                ) {
                                                                    if (isTierSelected) {
                                                                        Icon(
                                                                            imageVector = Icons.Default.Check,
                                                                            contentDescription = null,
                                                                            tint = Color.White,
                                                                            modifier = Modifier.size(15.dp)
                                                                        )
                                                                    }
                                                                    Text(
                                                                        text = tierLabel,
                                                                        fontSize = 13.sp,
                                                                        fontWeight = if (isTierSelected) FontWeight.Bold else FontWeight.Medium,
                                                                        color = if (isTierSelected) Color.White else MaterialTheme.colorScheme.onSurface
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    if (index < section.entries.lastIndex) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            thickness = 0.8.dp,
                                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Dev Team Execution Mode Control (Sequential low-RAM vs Parallel high-end)
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            coroutineScope.launch {
                                val nextMode = if (devTeamMode == "SEQUENTIAL") "PARALLEL" else "SEQUENTIAL"
                                authPrefs.setDevTeamExecutionMode(nextMode)
                                Toast.makeText(
                                    context,
                                    if (nextMode == "PARALLEL") "Dev Team Mode: Parallel (Multi-Model Together)" else "Dev Team Mode: Sequential (1-by-1 · Low RAM)",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Dev Team Execution Mode",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )

                        Text(
                            text = if (devTeamMode == "PARALLEL") "Parallel" else "Sequential",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = if (devTeamMode == "PARALLEL") Color(0xFF10B981) else Color(0xFF3B82F6)
                        )
                    }
                }
            }
        }
    }
}
