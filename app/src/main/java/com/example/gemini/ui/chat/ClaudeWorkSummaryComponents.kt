package com.example.gemini.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val ClaudeTerracotta = Color(0xFFD97757)
private val ClaudeDarkSurface = Color(0xFF1E1E20)
private val ClaudeLineGray = Color(0xFF333336)
private val ClaudeSubtleText = Color(0xFF9E9E9E)
private val ArtifactPurple = Color(0xFF6366F1)

/**
 * Compact single-line summary pill in the main chat feed.
 * Matches Claude Android app:
 * - While running: "•• Planning a lightweight animated portf... >"
 * - When completed: "[⏱️] Checking whether the portfolio file alr... >"
 */
@Composable
fun ClaudeWorkSummaryRow(
    summary: TurnWorkSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(850, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (summary.isActivelyRunning) {
            // Pulsing terracotta dots
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                modifier = Modifier.alpha(dotAlpha)
            ) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta)
                )
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta)
                )
            }
            Spacer(modifier = Modifier.width(7.dp))
        } else {
            // Subtle completed icon
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = ClaudeSubtleText.copy(alpha = 0.8f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }

        Text(
            text = summary.latestTitle,
            fontSize = 12.5.sp,
            fontWeight = if (summary.isActivelyRunning) FontWeight.Medium else FontWeight.Normal,
            color = if (summary.isActivelyRunning) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )

        Spacer(modifier = Modifier.width(4.dp))

        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = "Expand Summary",
            tint = if (summary.isActivelyRunning) ClaudeTerracotta else ClaudeSubtleText,
            modifier = Modifier.size(15.dp)
        )
    }
}

/**
 * Claude-style Artifact Card in the main chat.
 * Matches Image 12:
 * ┌──────────────────────────────────────────────┐
 * │  [🗂️]   Girdhar Dhami · Portfolio            │
 * │         Artifact                             │
 * └──────────────────────────────────────────────┘
 */
@Composable
fun ClaudeArtifactCard(
    artifact: ClaudeArtifactItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(ArtifactPurple.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (artifact.isWebHtml) Icons.Outlined.Widgets else Icons.Default.Description,
                    contentDescription = null,
                    tint = ArtifactPurple,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = artifact.title,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Artifact",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "·",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (artifact.isWebHtml) "Live Preview & Code" else "Tap to view code",
                        fontSize = 11.5.sp,
                        color = ArtifactPurple,
                        fontWeight = FontWeight.Medium
                    )
                }
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

/**
 * Slide-up Summary Bottom Sheet matching Images 6, 7, 8, 9 from the official Claude Android App.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeSummaryBottomSheet(
    summary: TurnWorkSummary,
    onDismiss: () -> Unit,
    onOpenArtifact: ((String) -> Unit)? = null
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedDetailStep by remember { mutableStateOf<WorkSummaryStep?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ClaudeDarkSurface,
        scrimColor = Color.Black.copy(alpha = 0.65f),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(
                modifier = Modifier
                    .padding(vertical = 10.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f))
            )
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .padding(horizontal = 20.dp)
        ) {
            // Header: [X] Summary (or [Back] Step Title when in detail view)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedDetailStep != null) {
                    IconButton(onClick = { selectedDetailStep = null }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = selectedDetailStep!!.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Summary",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            Divider(color = ClaudeLineGray.copy(alpha = 0.6f))
            Spacer(modifier = Modifier.height(14.dp))

            if (selectedDetailStep != null) {
                // Step Detail View (matching Image 8)
                val step = selectedDetailStep!!
                LazyColumn(modifier = Modifier.fillMaxSize().padding(vertical = 8.dp)) {
                    item {
                        Text(
                            text = step.title,
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color.Black.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, ClaudeLineGray),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = step.detailText?.ifBlank { "Executed successfully." } ?: "No detailed log available.",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 17.sp,
                                color = Color.White.copy(alpha = 0.85f),
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                }
            } else {
                // Vertical Timeline View (matching Images 6, 7, 9)
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(summary.steps, key = { _, s -> s.id }) { index, step ->
                        val isLast = index == summary.steps.lastIndex
                        TimelineStepNode(
                            step = step,
                            isLast = isLast,
                            onClick = { selectedDetailStep = step }
                        )
                    }

                    if (summary.isActivelyRunning) {
                        item {
                            TimelineActiveNode(title = "Thinking")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineStepNode(
    step: WorkSummaryStep,
    isLast: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Vertical Timeline Column (Node Icon + connecting line)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                when {
                    step.status == WorkStepStatus.FAILED -> {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFEF4444),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    step.type == WorkStepType.SKILL_READ -> {
                        Icon(
                            imageVector = Icons.Outlined.Book,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    step.type == WorkStepType.FILE_CREATE -> {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.NoteAdd,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    step.type == WorkStepType.COMMAND_EXEC -> {
                        Icon(
                            imageVector = Icons.Default.Code,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    else -> {
                        // Standard gray timeline bullet dot
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.35f))
                        )
                    }
                }
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(26.dp)
                        .background(Color.White.copy(alpha = 0.15f))
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(top = 2.dp)
        ) {
            Text(
                text = step.title,
                fontSize = 13.5.sp,
                lineHeight = 18.sp,
                fontWeight = if (step.type == WorkStepType.SKILL_READ || step.type == WorkStepType.FILE_CREATE) FontWeight.Bold else FontWeight.Normal,
                color = if (step.status == WorkStepStatus.FAILED) Color(0xFFEF4444) else Color.White.copy(alpha = 0.90f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun TimelineActiveNode(title: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "activeDot"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .alpha(dotAlpha)
                        .clip(CircleShape)
                        .background(ClaudeTerracotta)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = title,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.Medium,
            color = ClaudeTerracotta,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}
