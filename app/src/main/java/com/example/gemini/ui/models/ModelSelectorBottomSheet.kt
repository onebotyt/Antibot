package com.example.gemini.ui.models

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.LocalCodingDomain
import com.example.gemini.domain.model.ModelFamily
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.theme.*
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class CompactModelItem(
    val id: String,
    val title: String,
    val badge: String,
    val badgeColor: Color,
    val subtitle: String,
    val isLocal: Boolean = false,
    val isDevTeam: Boolean = false
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
    var showMoreModels by remember { mutableStateOf(false) }

    // Pre-populate core primary models matching Claude app style
    val primaryItems = remember(availableModels) {
        listOf(
            CompactModelItem(
                id = "local-dev-team",
                title = "AntiBot Dev Team",
                badge = "3 Models · Sync",
                badgeColor = Color(0xFF8B5CF6),
                subtitle = "Backend + Frontend + Cyber Security working together",
                isLocal = true,
                isDevTeam = true
            ),
            CompactModelItem(
                id = "local-qwen-coder-0.5b",
                title = "Qwen 2.5 Coder 0.5B",
                badge = "<500MB Local",
                badgeColor = Color(0xFF10B981),
                subtitle = "Ultra-compact on-device neural code model",
                isLocal = true
            ),
            CompactModelItem(
                id = "local-backend",
                title = "Backend Architect",
                badge = "Local",
                badgeColor = Color(0xFF06B6D4),
                subtitle = "REST APIs, database schemas & server endpoints",
                isLocal = true
            ),
            CompactModelItem(
                id = "local-frontend",
                title = "Frontend UI/UX",
                badge = "Local",
                badgeColor = Color(0xFFF59E0B),
                subtitle = "Jetpack Compose, React & modern web design",
                isLocal = true
            ),
            CompactModelItem(
                id = "local-security",
                title = "Cybersecurity Specialist",
                badge = "Local",
                badgeColor = Color(0xFFEF4444),
                subtitle = "OWASP Top 10, security audit & code hardening",
                isLocal = true
            ),
            CompactModelItem(
                id = availableModels.find { it.id.contains("sonnet", ignoreCase = true) }?.id ?: "claude-3-7-sonnet",
                title = "Claude 3.7 Sonnet",
                badge = "Cloud",
                badgeColor = ClaudeTerracotta,
                subtitle = "Most capable reasoning & full-stack development"
            ),
            CompactModelItem(
                id = availableModels.find { it.id.contains("haiku", ignoreCase = true) }?.id ?: "claude-3-5-haiku",
                title = "Claude 3.5 Haiku",
                badge = "Fastest",
                badgeColor = Color(0xFF3B82F6),
                subtitle = "Fastest for quick answers & scripting"
            ),
            CompactModelItem(
                id = availableModels.find { it.id.contains("flash", ignoreCase = true) }?.id ?: "gemini-2.5-flash",
                title = "Gemini 2.5 Flash",
                badge = "Fastest",
                badgeColor = GeminiBlue,
                subtitle = "Speedy orchestration & multimodal tasks"
            )
        )
    }

    // Remaining models for "More models >" expander
    val additionalModels = remember(availableModels, primaryItems) {
        val primaryIds = primaryItems.map { it.id }.toSet()
        availableModels.filter { it.id !in primaryIds && !LocalCodingDomain.isLocalModel(it.id) }
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

            // Scrollable Content
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Grouped Rounded Container for Primary Models
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        primaryItems.forEachIndexed { index, item ->
                            val isSelected = selectedModelId == item.id ||
                                (item.id == "local-dev-team" && selectedModelId == "local-dev-team")

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

                                        // Badge Pill (e.g. "Dev Team", "<500MB Local", "Cloud", "Fastest")
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

                            if (index < primaryItems.lastIndex) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(horizontal = 16.dp),
                                    thickness = 0.8.dp,
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Dev Team Execution Strategy Control (like Claude's Effort row)
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

                Spacer(modifier = Modifier.height(10.dp))

                // "More models >" Expandable Row
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { showMoreModels = !showMoreModels },
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = if (showMoreModels) "Hide extra models" else "More models",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.5.sp
                        )

                        Text(
                            text = if (showMoreModels) "▲" else ">",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Expanded Additional Models List
                AnimatedVisibility(
                    visible = showMoreModels && additionalModels.isNotEmpty(),
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                    ) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                        ) {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                additionalModels.forEachIndexed { index, model ->
                                    val isSelected = selectedModelId == model.id

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                onSelectModel(model.id)
                                                onDismiss()
                                            }
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = model.displayName,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            if (model.description.isNotBlank()) {
                                                Text(
                                                    text = model.description,
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = "Selected",
                                                tint = Color(0xFF3B82F6),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    if (index < additionalModels.lastIndex) {
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

                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
