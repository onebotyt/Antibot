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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
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
import com.example.gemini.data.agent.local.LocalModelActivityState
import com.example.gemini.data.agent.local.LocalModelManager
import com.example.gemini.data.agent.local.LocalModelSpec
import com.example.gemini.data.agent.local.ModelDownloadState
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.LocalCodingDomain
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.domain.model.SmartModelRouter
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
        val isLocal: Boolean = false,
        val isAuto: Boolean = false,
        val isDownloadable: Boolean = false,
        val isDownloaded: Boolean = true,
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
    enabledAgents: List<AgentKind> = listOf(AgentKind.AGY, AgentKind.LOCAL, AgentKind.CLAUDE, AgentKind.CODEX),
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

    val downloadState by LocalModelManager.downloadState.collectAsState()
    val localActivity by LocalModelManager.activityState.collectAsState()
    val installedModelIds = remember(downloadState) {
        LocalModelManager.AVAILABLE_MODELS.filter { LocalModelManager.isModelInstalled(context, it) }.map { it.id }.toSet()
    }
    var showDownloadDialogForModel by remember { mutableStateOf<LocalModelSpec?>(null) }
    var showDeleteDialogForModel by remember { mutableStateOf<LocalModelSpec?>(null) }

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
        installedModelIds,
        localActivity,
        claudeBackendModels,
        codexBackendModels,
        openCodeBackendModels,
        enabledAgents
    ) {
        val sections = mutableListOf<EngineSection>()

        // 1. ⚡ Antigravity (Gemini) Engine
        if (AgentKind.AGY in enabledAgents) {
            val agyList = mutableListOf<ModelItemEntry>()
            // Smart LLM Manager (Auto Dynamic Routing)
            agyList.add(
                ModelItemEntry.Single(
                    id = SmartModelRouter.AUTO_MODEL_ID,
                    title = "Auto (Smart LLM Manager)",
                    badge = "Smart Auto",
                    badgeColor = GeminiBlue,
                    subtitle = "Automatic dynamic engine selection",
                    isAuto = true,
                    agentKind = AgentKind.AGY
                )
            )

            // Cloud models grouped by baseName to consolidate High/Medium/Low into 1 card
            val cloudModels = availableModels.filter { !LocalCodingDomain.isLocalModel(it.id) && !SmartModelRouter.isAuto(it.id) }
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
        if (AgentKind.CLAUDE in enabledAgents) {
            val claudeList = mutableListOf<ModelItemEntry>()
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
            } else {
                // High-grade fallback roster
                claudeList.add(
                    ModelItemEntry.Single(
                        id = "claude-3-5-sonnet",
                        title = "Claude 3.5 Sonnet",
                        badge = "Flagship",
                        badgeColor = ClaudeTerracotta,
                        subtitle = "State-of-the-art coding and architectural reasoning",
                        agentKind = AgentKind.CLAUDE
                    )
                )
                claudeList.add(
                    ModelItemEntry.Single(
                        id = "claude-3-5-haiku",
                        title = "Claude 3.5 Haiku",
                        badge = "Fast",
                        badgeColor = ClaudeTerracotta,
                        subtitle = "Rapid subtask execution and quick refactoring",
                        agentKind = AgentKind.CLAUDE
                    )
                )
                claudeList.add(
                    ModelItemEntry.Single(
                        id = "claude-3-opus",
                        title = "Claude 3 Opus",
                        badge = "Deep Logic",
                        badgeColor = ClaudeTerracotta,
                        subtitle = "Complex system design and multi-layered analysis",
                        agentKind = AgentKind.CLAUDE
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.CLAUDE,
                    title = "Claude Code CLI",
                    badge = "🟣 Anthropic",
                    accentColor = ClaudeTerracotta,
                    entries = claudeList
                )
            )
        }

        // 3. 🟢 Codex CLI Engine
        if (AgentKind.CODEX in enabledAgents) {
            val codexList = mutableListOf<ModelItemEntry>()
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
            } else {
                codexList.add(
                    ModelItemEntry.Single(
                        id = "gpt-4o",
                        title = "GPT-4o",
                        badge = "Omni",
                        badgeColor = CodexAccent,
                        subtitle = "Versatile high-speed coding and analysis",
                        agentKind = AgentKind.CODEX
                    )
                )
                codexList.add(
                    ModelItemEntry.Single(
                        id = "o1-preview",
                        title = "o1-preview",
                        badge = "Reasoning",
                        badgeColor = CodexAccent,
                        subtitle = "Deep step-by-step problem solver",
                        agentKind = AgentKind.CODEX
                    )
                )
                codexList.add(
                    ModelItemEntry.Single(
                        id = "o1-mini",
                        title = "o1-mini",
                        badge = "Fast Math",
                        badgeColor = CodexAccent,
                        subtitle = "Efficient targeted code generation",
                        agentKind = AgentKind.CODEX
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.CODEX,
                    title = "Codex CLI",
                    badge = "🟢 OpenAI Engine",
                    accentColor = CodexAccent,
                    entries = codexList
                )
            )
        }

        // 4. 🧠 On-Device Local Qwen Engine
        if (AgentKind.LOCAL in enabledAgents) {
            val localList = mutableListOf<ModelItemEntry>()
            val activeWorkingId = LocalModelManager.activeModelId.value
            LocalModelManager.AVAILABLE_MODELS.forEach { spec ->
                val isInstalled = spec.id in installedModelIds || spec.isDefault
                val sizeMb = spec.approximateSizeBytes / (1024 * 1024)
                val isActivelyRunning = activeWorkingId == spec.id && localActivity is LocalModelActivityState.Active
                val subtitle = if (isInstalled) {
                    if (isActivelyRunning) {
                        "● Active Working (~${LocalModelManager.getActiveRamMb()}MB RAM)"
                    } else {
                        "💤 Inactive (0 MB RAM · Starts On-Demand)"
                    }
                } else {
                    "~$sizeMb MB · 1-Click Install"
                }

                localList.add(
                    ModelItemEntry.Single(
                        id = spec.id,
                        title = spec.displayName,
                        badge = if (!isInstalled) "1-Click" else if (isActivelyRunning) "Active" else "0 MB Idle",
                        badgeColor = if (!isInstalled) Color(0xFF8B5CF6) else if (isActivelyRunning) Color(0xFF10B981) else Color(0xFF6B7280),
                        subtitle = subtitle,
                        isLocal = true,
                        isDownloadable = !spec.isDefault,
                        isDownloaded = isInstalled,
                        agentKind = AgentKind.LOCAL
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.LOCAL,
                    title = "On-Device Local Qwen",
                    badge = "🧠 100% Offline · 0 MB Idle",
                    accentColor = Color(0xFF8B5CF6),
                    entries = localList
                )
            )
        }

        // 5. 🌐 OpenCode CLI Engine
        if (AgentKind.OPENCODE in enabledAgents) {
            val ocList = mutableListOf<ModelItemEntry>()
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
            } else {
                ocList.add(
                    ModelItemEntry.Single(
                        id = "opencode-default",
                        title = "OpenCode Default",
                        badge = "Custom",
                        badgeColor = OpenCodeAccent,
                        subtitle = "Connected provider models",
                        agentKind = AgentKind.OPENCODE
                    )
                )
            }

            sections.add(
                EngineSection(
                    agentKind = AgentKind.OPENCODE,
                    title = "OpenCode CLI",
                    badge = "🌐 Multi-Provider",
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
        if (onSelectAgentAndModel != null) {
            onSelectAgentAndModel(agent, modelId)
        } else {
            onSelectModel(modelId)
        }
        onDismiss()
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
                                                AgentKind.AGY -> currentAgent == AgentKind.AGY && (
                                                    if (entry.isAuto) SmartModelRouter.isAuto(selectedModelId)
                                                    else !SmartModelRouter.isAuto(selectedModelId) && selectedModelId == entry.id
                                                )
                                                AgentKind.LOCAL -> currentAgent == AgentKind.LOCAL && (
                                                    !SmartModelRouter.isAuto(selectedModelId) && (
                                                        selectedModelId == entry.id || (entry.id == "local-qwen-coder-0.5b" && (selectedModelId == "local-qwen-coder" || selectedModelId == "cli:local"))
                                                    )
                                                )
                                                AgentKind.CLAUDE -> currentAgent == AgentKind.CLAUDE && (
                                                    claudeSelModelId == entry.id || (claudeSelModelId.isBlank() && entry.id.contains("sonnet"))
                                                )
                                                AgentKind.CODEX -> currentAgent == AgentKind.CODEX && (
                                                    codexSelModelId == entry.id || (codexSelModelId.isBlank() && entry.id.contains("4o"))
                                                )
                                                AgentKind.OPENCODE -> currentAgent == AgentKind.OPENCODE && (
                                                    openCodeSelModelId == entry.id
                                                )
                                            }

                                            val currentDownload = downloadState
                                            val isThisDownloading = currentDownload is ModelDownloadState.Downloading && currentDownload.modelId == entry.id

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable(enabled = !isThisDownloading) {
                                                        if (entry.isDownloadable && !entry.isDownloaded) {
                                                            showDownloadDialogForModel = LocalModelManager.AVAILABLE_MODELS.find { it.id == entry.id }
                                                        } else {
                                                            selectModelAction(entry.agentKind, entry.id)
                                                        }
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
                                                                        text = if (isThisDownloading) "Downloading..." else entry.badge,
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

                                                        if (!entry.subtitle.isNullOrBlank() && !isThisDownloading) {
                                                            Spacer(modifier = Modifier.height(2.dp))
                                                            Text(
                                                                text = entry.subtitle,
                                                                fontSize = 11.5.sp,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }
                                                    }

                                                    if (entry.isDownloadable && entry.isDownloaded) {
                                                        IconButton(
                                                            onClick = { showDeleteDialogForModel = LocalModelManager.AVAILABLE_MODELS.find { it.id == entry.id } },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Delete,
                                                                contentDescription = "Delete downloaded model",
                                                                tint = MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    } else if (entry.isDownloadable && !entry.isDownloaded && !isThisDownloading) {
                                                        IconButton(
                                                            onClick = { showDownloadDialogForModel = LocalModelManager.AVAILABLE_MODELS.find { it.id == entry.id } },
                                                            modifier = Modifier.size(32.dp)
                                                        ) {
                                                            Icon(
                                                                imageVector = Icons.Default.Download,
                                                                contentDescription = "Download model",
                                                                tint = Color(0xFF8B5CF6),
                                                                modifier = Modifier.size(18.dp)
                                                            )
                                                        }
                                                    } else if (isSelected) {
                                                        Icon(
                                                            imageVector = Icons.Default.Check,
                                                            contentDescription = "Selected",
                                                            tint = section.accentColor,
                                                            modifier = Modifier.size(20.dp)
                                                        )
                                                    }
                                                }

                                                // Download progress indicator for local models
                                                if (isThisDownloading) {
                                                    val dl = currentDownload as ModelDownloadState.Downloading
                                                    Spacer(modifier = Modifier.height(8.dp))
                                                    LinearProgressIndicator(
                                                        progress = { dl.progress },
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(6.dp)
                                                            .clip(RoundedCornerShape(3.dp)),
                                                        color = Color(0xFF8B5CF6),
                                                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                                                    )
                                                    Spacer(modifier = Modifier.height(4.dp))
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = "${(dl.progress * 100).toInt()}% • ${dl.speedText} (${dl.downloadedBytes / (1024 * 1024)}MB / ${dl.totalBytes / (1024 * 1024)}MB)",
                                                            fontSize = 11.sp,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                        Text(
                                                            text = "Cancel",
                                                            fontSize = 11.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = Color.Red,
                                                            modifier = Modifier
                                                                .clip(RoundedCornerShape(4.dp))
                                                                .clickable { LocalModelManager.cancelDownload() }
                                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }

                                        is ModelItemEntry.TierGroup -> {
                                            val isGroupActive = currentAgent == AgentKind.AGY && !SmartModelRouter.isAuto(selectedModelId) && entry.tiers.any { it.id == selectedModelId }

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
                                                            val isTierSelected = currentAgent == AgentKind.AGY && !SmartModelRouter.isAuto(selectedModelId) && selectedModelId == tierModel.id
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

    if (showDownloadDialogForModel != null) {
        val modelSpec = showDownloadDialogForModel!!
        AlertDialog(
            onDismissRequest = { showDownloadDialogForModel = null },
            title = {
                Text(
                    text = "Install ${modelSpec.displayName}?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = modelSpec.description,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "• Parameter Count: ${modelSpec.parameterCount}\n• Storage Needed: ~${modelSpec.approximateSizeBytes / (1024 * 1024)} MB\n• Runs 100% offline on Android with 0 cloud tokens",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val spec = modelSpec
                        showDownloadDialogForModel = null
                        coroutineScope.launch {
                            Toast.makeText(context, "Starting download of ${spec.shortName}...", Toast.LENGTH_SHORT).show()
                            val ok = LocalModelManager.startDownload(context, spec)
                            if (ok) {
                                Toast.makeText(context, "${spec.shortName} installed successfully!", Toast.LENGTH_LONG).show()
                                selectModelAction(AgentKind.LOCAL, spec.id)
                            } else {
                                val err = (LocalModelManager.downloadState.value as? ModelDownloadState.Failed)?.error ?: "Download failed"
                                Toast.makeText(context, "Download failed: $err", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8B5CF6))
                ) {
                    Text("Download (1-Click)")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDownloadDialogForModel = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showDeleteDialogForModel != null) {
        val modelSpec = showDeleteDialogForModel!!
        AlertDialog(
            onDismissRequest = { showDeleteDialogForModel = null },
            title = {
                Text("Delete ${modelSpec.displayName}?", fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "Are you sure you want to remove the downloaded ${modelSpec.shortName} file? This will free ~${modelSpec.approximateSizeBytes / (1024 * 1024)} MB of device storage.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val spec = modelSpec
                        showDeleteDialogForModel = null
                        val deleted = LocalModelManager.deleteModel(context, spec)
                        if (deleted) {
                            Toast.makeText(context, "${spec.shortName} removed.", Toast.LENGTH_SHORT).show()
                            if (selectedModelId == spec.id) {
                                selectModelAction(AgentKind.LOCAL, "local-qwen-coder-0.5b")
                            }
                        }
                    }
                ) {
                    Text("Delete", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialogForModel = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
