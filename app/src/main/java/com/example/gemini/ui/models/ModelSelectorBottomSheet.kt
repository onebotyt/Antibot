package com.example.gemini.ui.models

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.LocalCodingDomain
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.domain.model.SmartModelRouter
import com.example.gemini.theme.*
import kotlinx.coroutines.launch

sealed interface ModelItemEntry {
    data class Single(
        val id: String,
        val title: String,
        val badge: String,
        val badgeColor: Color,
        val isLocal: Boolean = false,
        val isAuto: Boolean = false
    ) : ModelItemEntry

    data class TierGroup(
        val baseName: String,
        val badge: String,
        val badgeColor: Color,
        val tiers: List<AiModel>
    ) : ModelItemEntry
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorBottomSheet(
    selectedModelId: String,
    availableModels: List<AiModel>,
    quotas: List<ModelQuota>,
    quotaSummary: com.example.gemini.domain.model.QuotaSummaryResponse? = null,
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    val devTeamMode by authPrefs.devTeamExecutionMode.collectAsState(initial = authPrefs.getDevTeamExecutionModeSync())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Build the clean unified list of models: Auto at top, followed by Local Qwen,
    // followed by consolidated Cloud models (Gemini Flash as 1 card with High/Med/Low mode options)
    val modelEntries = remember(availableModels) {
        val list = mutableListOf<ModelItemEntry>()

        // 1. Smart LLM Manager (Auto Dynamic Routing)
        list.add(
            ModelItemEntry.Single(
                id = SmartModelRouter.AUTO_MODEL_ID,
                title = "Auto (Smart LLM Manager)",
                badge = "Smart Auto",
                badgeColor = Color(0xFF3B82F6),
                isAuto = true
            )
        )

        // 2. Single unified on-device model: Qwen 2.5 Coder 0.5B
        list.add(
            ModelItemEntry.Single(
                id = "local-qwen-coder-0.5b",
                title = "Qwen 2.5 Coder 0.5B",
                badge = "Local",
                badgeColor = Color(0xFF10B981),
                isLocal = true
            )
        )

        // 3. Cloud models grouped by baseName to consolidate High/Medium/Low into 1 card
        val cloudModels = availableModels.filter { !LocalCodingDomain.isLocalModel(it.id) && !SmartModelRouter.isAuto(it.id) }
        val grouped = cloudModels.groupBy { it.baseName }

        grouped.forEach { (baseName, models) ->
            val badgeColor = when (models.first().family) {
                ModelFamily.CLAUDE -> ClaudeTerracotta
                ModelFamily.GEMINI -> GeminiBlue
                else -> Color(0xFF8B5CF6)
            }

            if (models.size > 1 && models.any { it.tier != null }) {
                list.add(
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
                        }
                    )
                )
            } else {
                models.forEach { model ->
                    list.add(
                        ModelItemEntry.Single(
                            id = model.id,
                            title = model.displayName,
                            badge = "Cloud",
                            badgeColor = badgeColor
                        )
                    )
                }
            }
        }

        list
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
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header: [✕] on left, "Select model" in center bold
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 14.dp),
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
                    text = "Select model",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp
                )
            }

            // Scrollable Content showing models container
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Grouped Rounded Container for Models
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        modelEntries.forEachIndexed { index, entry ->
                            when (entry) {
                                is ModelItemEntry.Single -> {
                                    val isSelected = when {
                                        entry.isAuto -> SmartModelRouter.isAuto(selectedModelId)
                                        entry.isLocal -> !SmartModelRouter.isAuto(selectedModelId) && LocalCodingDomain.isLocalModel(selectedModelId)
                                        else -> !SmartModelRouter.isAuto(selectedModelId) && selectedModelId == entry.id
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onSelectModel(entry.id)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = entry.title,
                                                style = MaterialTheme.typography.bodyLarge,
                                                fontWeight = FontWeight.SemiBold,
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
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color(0xFF3B82F6),
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    }
                                }

                                is ModelItemEntry.TierGroup -> {
                                    val isGroupActive = !SmartModelRouter.isAuto(selectedModelId) && entry.tiers.any { it.id == selectedModelId }

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

                                            // Selectable mode chips matching media_1794123636662_b009060f.jpg
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                entry.tiers.forEach { tierModel ->
                                                    val isTierSelected = !SmartModelRouter.isAuto(selectedModelId) && selectedModelId == tierModel.id
                                                    val tierLabel = tierModel.tier ?: tierModel.displayName

                                                    Surface(
                                                        onClick = {
                                                            onSelectModel(tierModel.id)
                                                            onDismiss()
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

                            if (index < modelEntries.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

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
