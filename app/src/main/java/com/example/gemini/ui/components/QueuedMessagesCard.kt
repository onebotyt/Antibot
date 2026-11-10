package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.domain.model.QueuedMessage
import com.example.gemini.theme.AppColors

/**
 * Messages sent while the agent was working, waiting to be delivered (above the input box).
 * Each row offers:
 * - "Edit" (removes from queue and loads text into the chat text box)
 * - "Send Immediately" (interrupts the current work, checks context, and delivers immediately)
 * - "Delete" (discards message from queue)
 */
@Composable
fun QueuedMessagesCard(
    messages: List<QueuedMessage>,
    onSendNow: (String) -> Unit,
    onEdit: (String) -> Unit = {},
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = messages.isNotEmpty(),
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = AppColors.card,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
        ) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Rounded.Schedule,
                        contentDescription = null,
                        tint = AppColors.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (messages.size == 1) "1 queued message" else "${messages.size} queued messages",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.onBackground
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "Auto-sends when work finishes",
                        fontSize = 12.sp,
                        color = AppColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                messages.forEach { msg -> QueuedRow(msg, onSendNow, onEdit, onDelete) }
            }
        }
    }
}

@Composable
private fun QueuedRow(
    msg: QueuedMessage,
    onSendNow: (String) -> Unit,
    onEdit: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val label = msg.text.ifBlank {
            if (msg.attachmentCount == 1) "1 attachment" else "${msg.attachmentCount} attachments"
        }
        Text(
            text = label.replace(Regex("\\s+"), " ").trim(),
            fontSize = 13.sp,
            color = if (msg.text.isBlank()) AppColors.textSecondary else AppColors.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = 4.dp)
        )
        if (msg.isPending) {
            // sent from here, not confirmed by the agent yet
            CircularProgressIndicator(
                strokeWidth = 1.5.dp,
                color = AppColors.textSecondary,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .size(14.dp)
            )
        }
        if (msg.canSendNow) {
            IconButton(
                onClick = { onEdit(msg.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Edit,
                    contentDescription = "Edit message",
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
            IconButton(
                onClick = { onSendNow(msg.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Bolt,
                    contentDescription = "Send immediately",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        if (msg.canDelete) {
            IconButton(
                onClick = { onDelete(msg.id) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Delete from queue",
                    tint = AppColors.textSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}
