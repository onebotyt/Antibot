package com.example.gemini.assistant.overlay

import android.graphics.Rect
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.assistant.vision.AntiBotAccessibilityService
import com.example.gemini.theme.ClaudeTerracotta
import kotlin.math.max
import kotlin.math.min

@Composable
fun ScreenDrawCanvasOverlay(
    onDismiss: () -> Unit,
    onQueryAi: (query: String, extractedText: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val clipboardManager = LocalClipboardManager.current
    var touchPoints by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var selectedBounds by remember { mutableStateOf<Rect?>(null) }
    var extractedText by remember { mutableStateOf<String?>(null) }
    var isDrawing by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.35f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        isDrawing = true
                        selectedBounds = null
                        extractedText = null
                        touchPoints = listOf(offset)
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        touchPoints = touchPoints + change.position
                    },
                    onDragEnd = {
                        isDrawing = false
                        if (touchPoints.size > 2) {
                            var minX = Float.MAX_VALUE
                            var minY = Float.MAX_VALUE
                            var maxX = Float.MIN_VALUE
                            var maxY = Float.MIN_VALUE

                            for (p in touchPoints) {
                                minX = min(minX, p.x)
                                minY = min(minY, p.y)
                                maxX = max(maxX, p.x)
                                maxY = max(maxY, p.y)
                            }

                            // Add small padding
                            val rect = Rect(
                                (minX - 12).toInt().coerceAtLeast(0),
                                (minY - 12).toInt().coerceAtLeast(0),
                                (maxX + 12).toInt(),
                                (maxY + 12).toInt()
                            )
                            selectedBounds = rect

                            // Extract text inside drawn bounds using Accessibility
                            val text = AntiBotAccessibilityService.extractTextInBounds(rect)
                            extractedText = if (text.isNotBlank()) text else "Selected screen area (${rect.width()}x${rect.height()}px)"
                        }
                    },
                    onDragCancel = {
                        isDrawing = false
                    }
                )
            }
    ) {
        // Glowing Canvas drawing layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (touchPoints.size > 1) {
                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(touchPoints.first().x, touchPoints.first().y)
                    for (i in 1 until touchPoints.size) {
                        lineTo(touchPoints[i].x, touchPoints[i].y)
                    }
                }

                // Draw glowing outer line
                drawPath(
                    path = path,
                    color = Color(0xFF38BDF8).copy(alpha = 0.5f),
                    style = Stroke(width = 14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )

                // Draw sharp inner line
                drawPath(
                    path = path,
                    color = Color(0xFFF97316),
                    style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            // Draw bounding box if completed
            selectedBounds?.let { bounds ->
                drawRoundRect(
                    color = Color(0x3338BDF8),
                    topLeft = Offset(bounds.left.toFloat(), bounds.top.toFloat()),
                    size = Size(bounds.width().toFloat(), bounds.height().toFloat()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f)
                )
                drawRoundRect(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFF38BDF8), Color(0xFFF97316))
                    ),
                    topLeft = Offset(bounds.left.toFloat(), bounds.top.toFloat()),
                    size = Size(bounds.width().toFloat(), bounds.height().toFloat()),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(16f, 16f),
                    style = Stroke(width = 4f)
                )
            }
        }

        // Top Navigation & Instruction Pill
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedBounds == null) "Circle or point to anything on screen" else "Region selected",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.width(12.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Bottom Action Card when a selection has been made
        selectedBounds?.let { bounds ->
            val textContent = extractedText.orEmpty()

            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceColorAtElevation(4.dp),
                shadowElevation = 16.dp,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 20.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Detected Screen Content",
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = {
                                touchPoints = emptyList()
                                selectedBounds = null
                                extractedText = null
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Redraw",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Text snippet preview
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = textContent,
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Action buttons
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                onQueryAi("Explain what this is and how to help: $textContent", textContent)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = ClaudeTerracotta
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1.3f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Ask AI", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(textContent))
                            },
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copy", fontSize = 13.sp)
                        }

                        // Autonomous tap button if accessibility is active
                        if (AntiBotAccessibilityService.isServiceRunning) {
                            OutlinedButton(
                                onClick = {
                                    val centerX = bounds.centerX().toFloat()
                                    val centerY = bounds.centerY().toFloat()
                                    AntiBotAccessibilityService.performTapAt(centerX, centerY) {
                                        onDismiss()
                                    }
                                },
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.TouchApp,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Tap", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}
