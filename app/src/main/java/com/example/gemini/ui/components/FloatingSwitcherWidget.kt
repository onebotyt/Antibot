package com.example.gemini.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.gemini.AppViewMode
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun FloatingSwitcherWidget(
    currentViewMode: AppViewMode,
    orientation: String, // "VERTICAL" or "HORIZONTAL"
    items: List<String>, // e.g. ["chat", "ide", "terminal", "browser"]
    autoCollapseTimeoutSec: Int, // 0 = never, >0 = seconds
    savedPosXRatio: Float, // 0.0 .. 1.0 (default ~0.95f)
    savedPosYRatio: Float, // 0.0 .. 1.0 (default ~0.50f)
    onNavigateToChat: () -> Unit,
    onNavigateToIde: () -> Unit,
    onNavigateToBrowser: () -> Unit,
    onNavigateToTerminal: () -> Unit,
    onNavigateToNotes: () -> Unit = {},
    onPositionSaved: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }

    var widgetSize by remember { mutableStateOf(IntSize.Zero) }
    var isExpanded by remember { mutableStateOf(false) }
    var lastInteractionTimestamp by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var isDragging by remember { mutableStateOf(false) }

    // Auto-collapse timer
    if (autoCollapseTimeoutSec > 0 && isExpanded && !isDragging) {
        LaunchedEffect(isExpanded, lastInteractionTimestamp, autoCollapseTimeoutSec) {
            delay(autoCollapseTimeoutSec * 1000L)
            isExpanded = false
        }
    }

    // Stable in-memory pixel offset position & anchors
    var isPositionInitialized by remember { mutableStateOf(false) }
    var anchorRightX by remember { mutableFloatStateOf(0f) }
    var anchorLeftX by remember { mutableFloatStateOf(0f) }
    val isVertical = orientation.equals("VERTICAL", ignoreCase = true)

    // Directional arrows:
    // Vertical layout extends vertically -> UP / DOWN arrows (^ / v)
    // Horizontal layout extends horizontally -> LEFT / RIGHT arrows (< / >)
    val expandArrowIcon: ImageVector = if (isVertical) {
        if (anchorBottomY > (screenHeightPx / 2)) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore
    } else {
        if (anchorRightX > (screenWidthPx / 2)) Icons.Outlined.ChevronLeft else Icons.Outlined.ChevronRight
    }

    val collapseArrowIcon: ImageVector = if (isVertical) {
        if (anchorBottomY > (screenHeightPx / 2)) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess
    } else {
        if (anchorRightX > (screenWidthPx / 2)) Icons.Outlined.ChevronRight else Icons.Outlined.ChevronLeft
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(9999f)
    ) {
        val containerWidthPx = constraints.maxWidth.toFloat()
        val containerHeightPx = constraints.maxHeight.toFloat()
        val statusBarTopPx = WindowInsets.statusBars.getTop(density).toFloat()
        val navBarsBottomPx = WindowInsets.navigationBars.getBottom(density).toFloat()

        val minSafeY = (statusBarTopPx + 8f).coerceAtLeast(0f)
        val maxSafeY = (containerHeightPx - widgetSize.height - navBarsBottomPx - 8f).coerceAtLeast(minSafeY)
        val minSafeX = 4f
        val maxSafeX = (containerWidthPx - widgetSize.width - 4f).coerceAtLeast(minSafeX)

        val isNearRightEdge = anchorRightX > (containerWidthPx / 2)
        val isNearBottom = anchorBottomY > (containerHeightPx / 2)

        LaunchedEffect(containerWidthPx, containerHeightPx) {
            if (containerWidthPx > 0 && containerHeightPx > 0 && (!isPositionInitialized || !isDragging)) {
                val posX = (savedPosXRatio * containerWidthPx).coerceIn(minSafeX, maxSafeX)
                val posY = (savedPosYRatio * containerHeightPx).coerceIn(minSafeY, maxSafeY)
                anchorLeftX = posX
                anchorRightX = posX + (if (widgetSize.width > 0) widgetSize.width else 40)
                anchorTopY = posY
                anchorBottomY = posY + (if (widgetSize.height > 0) widgetSize.height else 40)
                isPositionInitialized = true
            }
        }

        Box(
            modifier = Modifier
                .offset {
                    val targetX = if (isNearRightEdge) (anchorRightX - widgetSize.width) else anchorLeftX
                    val targetY = if (isNearBottom) (anchorBottomY - widgetSize.height) else anchorTopY
                    val clampedX = targetX.coerceIn(minSafeX, maxSafeX)
                    val clampedY = targetY.coerceIn(minSafeY, maxSafeY)
                    IntOffset(clampedX.roundToInt(), clampedY.roundToInt())
                }
                .onSizeChanged { newSize ->
                    if (newSize.width > 0 && newSize.height > 0) {
                        if (!isDragging) {
                            if (isNearRightEdge) {
                                anchorLeftX = anchorRightX - newSize.width
                            } else {
                                anchorRightX = anchorLeftX + newSize.width
                            }
                            if (isNearBottom) {
                                anchorTopY = anchorBottomY - newSize.height
                            } else {
                                anchorBottomY = anchorTopY + newSize.height
                            }
                        }
                        widgetSize = newSize
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = {
                            isDragging = true
                            lastInteractionTimestamp = System.currentTimeMillis()
                            val currentX = (if (isNearRightEdge) (anchorRightX - widgetSize.width) else anchorLeftX).coerceIn(minSafeX, maxSafeX)
                            val currentY = (if (isNearBottom) (anchorBottomY - widgetSize.height) else anchorTopY).coerceIn(minSafeY, maxSafeY)
                            anchorLeftX = currentX
                            anchorRightX = currentX + widgetSize.width
                            anchorTopY = currentY
                            anchorBottomY = currentY + widgetSize.height
                        },
                        onDragEnd = {
                            isDragging = false
                            val targetX = if (isNearRightEdge) (anchorRightX - widgetSize.width) else anchorLeftX
                            val targetY = if (isNearBottom) (anchorBottomY - widgetSize.height) else anchorTopY
                            val clampedX = targetX.coerceIn(minSafeX, maxSafeX)
                            val clampedY = targetY.coerceIn(minSafeY, maxSafeY)
                            anchorLeftX = clampedX
                            anchorRightX = clampedX + widgetSize.width
                            anchorTopY = clampedY
                            anchorBottomY = clampedY + widgetSize.height
                            val ratioX = if (containerWidthPx > 0) (clampedX / containerWidthPx).coerceIn(0f, 1f) else 0.95f
                            val ratioY = if (containerHeightPx > 0) (clampedY / containerHeightPx).coerceIn(0f, 1f) else 0.50f
                            onPositionSaved(ratioX, ratioY)
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            lastInteractionTimestamp = System.currentTimeMillis()
                            anchorLeftX = (anchorLeftX + dragAmount.x).coerceIn(minSafeX, maxSafeX)
                            anchorRightX = anchorLeftX + widgetSize.width
                            anchorTopY = (anchorTopY + dragAmount.y).coerceIn(minSafeY, maxSafeY)
                            anchorBottomY = anchorTopY + widgetSize.height
                        }
                    )
                }
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)),
                shadowElevation = 8.dp,
                modifier = Modifier.shadow(8.dp, RoundedCornerShape(24.dp))
            ) {
                if (!isExpanded) {
                    // Collapsed Compact Pill with Directional Arrow
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(24.dp))
                            .clickable {
                                lastInteractionTimestamp = System.currentTimeMillis()
                                isExpanded = true
                            }
                            .padding(horizontal = 9.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = expandArrowIcon,
                            contentDescription = "Expand Switcher",
                            tint = com.example.gemini.theme.AppColors.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    // Expanded Full Switcher Pill
                    if (isVertical) {
                        Column(
                            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Collapse Arrow Button (Up / Down for vertical)
                            IconButton(
                                onClick = {
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                    isExpanded = false
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = collapseArrowIcon,
                                    contentDescription = "Collapse Switcher",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            HorizontalDivider(
                                modifier = Modifier
                                    .width(22.dp)
                                    .padding(vertical = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )

                            // Configured Items
                            items.forEach { itemId ->
                                val isSelected = when (itemId.lowercase()) {
                                    "chat" -> currentViewMode == AppViewMode.CHAT
                                    "ide" -> currentViewMode == AppViewMode.IDE
                                    "browser" -> currentViewMode == AppViewMode.BROWSER
                                    "terminal" -> currentViewMode == AppViewMode.TERMINAL
                                    "notes" -> currentViewMode == AppViewMode.NOTES
                                    else -> false
                                }

                                val iconVector: ImageVector = when (itemId.lowercase()) {
                                    "chat" -> Icons.Outlined.ChatBubbleOutline
                                    "ide" -> Icons.Outlined.Code
                                    "terminal" -> Icons.Outlined.Terminal
                                    "browser" -> Icons.Outlined.Language
                                    "notes" -> Icons.Outlined.EditNote
                                    else -> Icons.Outlined.ChatBubbleOutline
                                }

                                val contentDesc = when (itemId.lowercase()) {
                                    "chat" -> "Chat"
                                    "ide" -> "IDE"
                                    "terminal" -> "Terminal"
                                    "browser" -> "Browser"
                                    "notes" -> "Notes"
                                    else -> itemId
                                }

                                FloatingSwitcherItemButton(
                                    icon = iconVector,
                                    contentDescription = contentDesc,
                                    isSelected = isSelected,
                                    onClick = {
                                        lastInteractionTimestamp = System.currentTimeMillis()
                                        when (itemId.lowercase()) {
                                            "chat" -> onNavigateToChat()
                                            "ide" -> onNavigateToIde()
                                            "browser" -> onNavigateToBrowser()
                                            "terminal" -> onNavigateToTerminal()
                                            "notes" -> onNavigateToNotes()
                                        }
                                    }
                                )
                            }
                        }
                    } else {
                        // Horizontal Layout: Icons on left, collapse arrow on right
                        Row(
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            // Configured Items
                            items.forEach { itemId ->
                                val isSelected = when (itemId.lowercase()) {
                                    "chat" -> currentViewMode == AppViewMode.CHAT
                                    "ide" -> currentViewMode == AppViewMode.IDE
                                    "browser" -> currentViewMode == AppViewMode.BROWSER
                                    "terminal" -> currentViewMode == AppViewMode.TERMINAL
                                    "notes" -> currentViewMode == AppViewMode.NOTES
                                    else -> false
                                }

                                val iconVector: ImageVector = when (itemId.lowercase()) {
                                    "chat" -> Icons.Outlined.ChatBubbleOutline
                                    "ide" -> Icons.Outlined.Code
                                    "terminal" -> Icons.Outlined.Terminal
                                    "browser" -> Icons.Outlined.Language
                                    "notes" -> Icons.Outlined.EditNote
                                    else -> Icons.Outlined.ChatBubbleOutline
                                }

                                val contentDesc = when (itemId.lowercase()) {
                                    "chat" -> "Chat"
                                    "ide" -> "IDE"
                                    "terminal" -> "Terminal"
                                    "browser" -> "Browser"
                                    "notes" -> "Notes"
                                    else -> itemId
                                }

                                FloatingSwitcherItemButton(
                                    icon = iconVector,
                                    contentDescription = contentDesc,
                                    isSelected = isSelected,
                                    onClick = {
                                        lastInteractionTimestamp = System.currentTimeMillis()
                                        when (itemId.lowercase()) {
                                            "chat" -> onNavigateToChat()
                                            "ide" -> onNavigateToIde()
                                            "browser" -> onNavigateToBrowser()
                                            "terminal" -> onNavigateToTerminal()
                                            "notes" -> onNavigateToNotes()
                                        }
                                    }
                                )
                            }

                            VerticalDivider(
                                modifier = Modifier
                                    .height(22.dp)
                                    .padding(horizontal = 2.dp),
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                            )

                            // Collapse Arrow Button on the Right Side
                            IconButton(
                                onClick = {
                                    lastInteractionTimestamp = System.currentTimeMillis()
                                    isExpanded = false
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = collapseArrowIcon,
                                    contentDescription = "Collapse Switcher",
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
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

@Composable
private fun FloatingSwitcherItemButton(
    icon: ImageVector,
    contentDescription: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val accentColor = com.example.gemini.theme.AppColors.primary
    Surface(
        shape = CircleShape,
        color = if (isSelected) accentColor.copy(alpha = 0.20f) else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, accentColor.copy(alpha = 0.5f)) else null,
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
