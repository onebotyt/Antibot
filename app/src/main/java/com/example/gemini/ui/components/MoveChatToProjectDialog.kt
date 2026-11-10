package com.example.gemini.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.domain.project.ChatProjectMover
import com.example.gemini.theme.ClaudeTerracotta
import java.io.File

@Composable
fun MoveChatToProjectDialog(
    currentProjectPath: String?,
    existingProjects: List<ProjectItem>,
    onMoveToExisting: (ProjectItem) -> Unit,
    onCreateAndMove: (projectName: String) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(if (existingProjects.isEmpty()) 0 else 0) } // 0 = New Project, 1 = Existing
    var newProjectName by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredProjects = remember(searchQuery, existingProjects) {
        if (searchQuery.isBlank()) existingProjects
        else existingProjects.filter {
            it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 580.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {} // Consume inner clicks
                    )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // Header Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(ClaudeTerracotta.copy(alpha = 0.14f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DriveFileMove,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Move Chat to Project",
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Export chat context & docs to workspace",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                                )
                            }
                        }

                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tab Switcher [Create New Project] [Select Existing]
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp)
                        ) {
                            TabButton(
                                title = "New Project",
                                icon = Icons.Outlined.CreateNewFolder,
                                isSelected = selectedTab == 0,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedTab = 0 }
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            TabButton(
                                title = "Existing (${existingProjects.size})",
                                icon = Icons.Outlined.FolderSpecial,
                                isSelected = selectedTab == 1,
                                modifier = Modifier.weight(1f),
                                onClick = { selectedTab = 1 }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Content Based on Tab
                    if (selectedTab == 0) {
                        // TAB 0: CREATE NEW PROJECT
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f, fill = false)
                        ) {
                            Text(
                                text = "PROJECT NAME",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.6.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                modifier = Modifier.padding(bottom = 6.dp)
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                border = BorderStroke(
                                    1.dp,
                                    if (newProjectName.isNotBlank()) ClaudeTerracotta.copy(alpha = 0.65f)
                                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                BasicTextField(
                                    value = newProjectName,
                                    onValueChange = { newProjectName = it },
                                    singleLine = true,
                                    textStyle = TextStyle(
                                        color = MaterialTheme.colorScheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium
                                    ),
                                    cursorBrush = SolidColor(ClaudeTerracotta),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    decorationBox = { innerTextField ->
                                        Box(contentAlignment = Alignment.CenterStart) {
                                            if (newProjectName.isEmpty()) {
                                                Text(
                                                    text = "e.g. my-awesome-app",
                                                    fontSize = 14.sp,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                )
                                            }
                                            innerTextField()
                                        }
                                    }
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            // Target Path Preview
                            val cleanName = if (newProjectName.isNotBlank()) newProjectName.trim().replace(" ", "-") else "my-awesome-app"
                            val previewPath = "${ChatProjectMover.DEFAULT_PROJECTS_DIR}/$cleanName"

                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Folder,
                                        contentDescription = null,
                                        tint = ClaudeTerracotta.copy(alpha = 0.8f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = previewPath,
                                        fontSize = 11.5.sp,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // Features bullet points
                            Text(
                                text = "What will be generated in project:",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            DocFeatureRow(label = "PROJECT_CONTEXT.md", desc = "Overview, architecture state, and requirements")
                            DocFeatureRow(label = "docs/CONVERSATION_HISTORY.md", desc = "Complete conversation log & thoughts")
                            DocFeatureRow(label = "AGENTS.md", desc = "Continuity rules for incoming AI agents")

                            Spacer(modifier = Modifier.height(20.dp))

                            // Submit Button
                            Button(
                                onClick = {
                                    if (newProjectName.isNotBlank()) {
                                        onCreateAndMove(newProjectName.trim())
                                        onDismiss()
                                    }
                                },
                                enabled = newProjectName.isNotBlank(),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = ClaudeTerracotta,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Create & Move Chat",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    } else {
                        // TAB 1: SELECT EXISTING PROJECT
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            if (existingProjects.size > 3) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        BasicTextField(
                                            value = searchQuery,
                                            onValueChange = { searchQuery = it },
                                            singleLine = true,
                                            textStyle = TextStyle(
                                                color = MaterialTheme.colorScheme.onSurface,
                                                fontSize = 13.sp
                                            ),
                                            modifier = Modifier.fillMaxWidth(),
                                            decorationBox = { inner ->
                                                if (searchQuery.isEmpty()) {
                                                    Text(
                                                        text = "Filter workspaces...",
                                                        fontSize = 13.sp,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                                    )
                                                }
                                                inner()
                                            }
                                        )
                                    }
                                }
                            }

                            if (filteredProjects.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No workspaces found",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    items(filteredProjects, key = { it.path }) { proj ->
                                        val isActive = currentProjectPath?.trimEnd('/') == proj.path.trimEnd('/')
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = if (isActive) ClaudeTerracotta.copy(alpha = 0.12f)
                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isActive) ClaudeTerracotta.copy(alpha = 0.7f)
                                                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.25f)
                                            ),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(12.dp))
                                                .clickable {
                                                    onMoveToExisting(proj)
                                                    onDismiss()
                                                }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(32.dp)
                                                        .clip(CircleShape)
                                                        .background(
                                                            if (isActive) ClaudeTerracotta.copy(alpha = 0.2f)
                                                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                                                        ),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Default.Folder,
                                                        contentDescription = null,
                                                        tint = if (isActive) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(17.dp)
                                                    )
                                                }
                                                Spacer(modifier = Modifier.width(10.dp))
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = proj.name,
                                                        fontSize = 13.5.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = MaterialTheme.colorScheme.onSurface,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = proj.path,
                                                        fontSize = 11.sp,
                                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                                if (isActive) {
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Surface(
                                                        shape = RoundedCornerShape(8.dp),
                                                        color = ClaudeTerracotta.copy(alpha = 0.2f)
                                                    ) {
                                                        Text(
                                                            text = "Current",
                                                            fontSize = 10.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = ClaudeTerracotta,
                                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabButton(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) MaterialTheme.colorScheme.surface else Color.Transparent,
        border = if (isSelected) BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) else null,
        shadowElevation = if (isSelected) 2.dp else 0.dp,
        modifier = modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.size(15.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun DocFeatureRow(label: String, desc: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(ClaudeTerracotta)
        )
        Spacer(modifier = Modifier.width(7.dp))
        Text(
            text = label,
            fontSize = 11.5.sp,
            fontWeight = FontWeight.SemiBold,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = "— $desc",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
