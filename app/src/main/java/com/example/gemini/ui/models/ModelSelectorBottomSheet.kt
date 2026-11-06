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

data class CompactModelItem(
    val id: String,
    val title: String,
    val badge: String,
    val badgeColor: Color,
    val subtitle: String,
    val isLocal: Boolean = false
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
    onSelectModel: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }
    val coroutineScope = rememberCoroutineScope()
    val devTeamMode by authPrefs.devTeamExecutionMode.collectAsState(initial = authPrefs.getDevTeamExecutionModeSync())

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Build the clean unified list of models: Auto at top, followed by Local Qwen, followed directly by all real Cloud models
    val allModelItems = remember(availableModels) {
        val list = mutableListOf<CompactModelItem>()

        // 1. Smart LLM Manager (Auto Dynamic Routing)
        list.add(
            CompactModelItem(
                id = SmartModelRouter.AUTO_MODEL_ID,
                title = "Auto (Smart LLM Manager)",
                badge = "Smart Auto",
                badgeColor = Color(0xFF3B82F6),
                subtitle = "Dynamic routing · Qwen for shell/offline, Flash for Q&A, Claude for complex logic",
                isLocal = false
            )
        )

        // 2. Single unified on-device model: Qwen 2.5 Coder 0.5B
        list.add(
            CompactModelItem(
                id = "local-qwen-coder-0.5b",
                title = "Qwen 2.5 Coder 0.5B",
                badge = "Local",
                badgeColor = Color(0xFF10B981),
                subtitle = "On-device code engine · Fullstack, UI/UX, APIs & security (0 tokens)",
                isLocal = true
            )
        )

        // 3. All real Cloud models fetched from Antigravity server
        val cloudModels = availableModels.filter { !LocalCodingDomain.isLocalModel(it.id) && !SmartModelRouter.isAuto(it.id) }
        cloudModels.forEach { model ->
            val badgeColor = when (model.family) {
                ModelFamily.CLAUDE -> ClaudeTerracotta
                ModelFamily.GEMINI -> GeminiBlue
                else -> Color(0xFF8B5CF6)
            }
            list.add(
                CompactModelItem(
                    id = model.id,
                    title = model.displayName,
                    badge = "Cloud",
                    badgeColor = badgeColor,
                    subtitle = model.description.ifBlank { "Antigravity Cloud Model · Advanced Reasoning" },
                    isLocal = false
                )
            )
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

            // Scrollable Content showing ALL models in one unified list (no expander)
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
                        allModelItems.forEachIndexed { index, item ->
                            val isSelected = when {
                                item.id == SmartModelRouter.AUTO_MODEL_ID -> SmartModelRouter.isAuto(selectedModelId)
                                item.isLocal -> !SmartModelRouter.isAuto(selectedModelId) && LocalCodingDomain.isLocalModel(selectedModelId)
                                else -> !SmartModelRouter.isAuto(selectedModelId) && selectedModelId == item.id
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSelectModel(item.id)
                                        onDismiss()
                                    }
                                    .padding(horizontal = 16.dp, vertical = 13.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 15.sp
                                        )

                                        // Badge Pill: [Local] or [Cloud]
                                        Box(
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(item.badgeColor.copy(alpha = 0.16f))
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = item.badge,
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = item.badgeColor
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Text(
                                        text = item.subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Blue Checkmark if selected
                                if (isSelected) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = Color(0xFF3B82F6),
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }

                            if (index < allModelItems.lastIndex) {
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
                            text = "Dev Team Mode",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.5.sp
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.16f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = if (devTeamMode == "SEQUENTIAL") "Recommended" else "High-End",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        Text(
                            text = if (devTeamMode == "PARALLEL") "Parallel (Together) >" else "Sequential (1-by-1) >",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
