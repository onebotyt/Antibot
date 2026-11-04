package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.context.TaskFlightRecorder

/**
 * TaskRecoveryBanner: Interactive banner shown when an interrupted task checkpoint is detected.
 *
 * Allows the user to resume an interrupted agent session directly from the exact tool or step
 * where it stopped, without having to re-scan old history or guess what was in progress.
 */
@Composable
fun TaskRecoveryBanner(
    checkpoint: TaskFlightRecorder.Checkpoint?,
    onResume: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = checkpoint != null, modifier = modifier) {
        if (checkpoint == null) return@AnimatedVisibility

        val toolName = checkpoint.inFlightTool.ifBlank { "Tool Execution" }
        val detail = checkpoint.inFlightDetail.ifBlank { checkpoint.userObjective }.take(60)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF2C2416))
                .border(1.dp, Color(0xFFD97706).copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .clickable {
                    val prompt = TaskFlightRecorder.generateResumePrompt(checkpoint)
                    onResume(prompt)
                }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.WarningAmber,
                    contentDescription = null,
                    tint = Color(0xFFF59E0B),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Task Halted at $toolName",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFCD34D)
                    )
                    if (detail.isNotBlank()) {
                        Text(
                            text = detail,
                            fontSize = 11.sp,
                            color = Color(0xFFE5E7EB).copy(alpha = 0.8f),
                            maxLines = 1
                        )
                    }
                }
            }
            FilledTonalButton(
                onClick = {
                    val prompt = TaskFlightRecorder.generateResumePrompt(checkpoint)
                    onResume(prompt)
                },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = Color(0xFFD97706),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(30.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Resume", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}
