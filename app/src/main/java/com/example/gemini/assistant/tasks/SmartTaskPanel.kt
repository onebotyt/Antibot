package com.example.gemini.assistant.tasks

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.AntiBotCyan
import kotlinx.coroutines.launch
import java.util.Calendar

enum class TaskFilterTab(val label: String) {
    PENDING("Active"),
    ALL("All"),
    COMPLETED("Done")
}

@Composable
fun SmartTaskPanel(
    repository: SmartTaskRepository? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val repo = remember(context, repository) { repository ?: SmartTaskRepository(context) }
    val tasks by repo.tasks.collectAsState()
    val categories by repo.categories.collectAsState()
    val scope = rememberCoroutineScope()

    var filterTab by remember { mutableStateOf(TaskFilterTab.PENDING) }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var isCreatingTask by remember { mutableStateOf(false) }

    // Task Creation State
    var newTitle by remember { mutableStateOf("") }
    var newNotes by remember { mutableStateOf("") }
    var newPriority by remember { mutableStateOf(TaskPriority.NORMAL) }
    var newRecurrence by remember { mutableStateOf(TaskRecurrence.NONE) }
    var newDueAt by remember { mutableStateOf<Long?>(null) }
    var newCategory by remember { mutableStateOf("") }

    val filteredTasks = remember(tasks, filterTab, selectedCategory, searchQuery) {
        tasks.filter { task ->
            if (task.deleted) return@filter false
            val matchesFilter = when (filterTab) {
                TaskFilterTab.PENDING -> !task.completed
                TaskFilterTab.ALL -> true
                TaskFilterTab.COMPLETED -> task.completed
            }
            val matchesCategory = selectedCategory == null || task.category.equals(selectedCategory, ignoreCase = true)
            val matchesQuery = searchQuery.isBlank() || task.title.contains(searchQuery, ignoreCase = true) || task.notes.contains(searchQuery, ignoreCase = true)
            matchesFilter && matchesCategory && matchesQuery
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Surface(
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 2.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = AntiBotCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Smart Tasks",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "${tasks.count { !it.completed && !it.deleted }} pending tasks · Offline Sync",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }

                if (onClose != null) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                }
            }
        }

        // Filter Tabs & Categories
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TaskFilterTab.entries.forEach { tab ->
                val isSelected = filterTab == tab
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    border = BorderStroke(
                        1.dp,
                        if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent
                    ),
                    modifier = Modifier.clickable { filterTab = tab }
                ) {
                    Text(
                        text = tab.label,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }
        }

        // Quick Task Creator Row / Expandable Creator
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        textStyle = TextStyle(
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 14.5.sp
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            if (newTitle.isNotBlank()) {
                                scope.launch {
                                    repo.createTask(
                                        title = newTitle,
                                        notes = newNotes,
                                        dueAt = newDueAt,
                                        priority = newPriority,
                                        category = newCategory,
                                        recurrence = newRecurrence
                                    )
                                    newTitle = ""
                                    newNotes = ""
                                    newDueAt = null
                                }
                            }
                        }),
                        decorationBox = { innerTextField ->
                            if (newTitle.isEmpty()) {
                                Text(
                                    text = "Add task or natural reminder (e.g. Call client at 4pm)...",
                                    fontSize = 13.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.weight(1f)
                    )

                    IconButton(
                        onClick = {
                            if (newTitle.isNotBlank()) {
                                scope.launch {
                                    repo.createTask(
                                        title = newTitle,
                                        notes = newNotes,
                                        dueAt = newDueAt,
                                        priority = newPriority,
                                        category = newCategory,
                                        recurrence = newRecurrence
                                    )
                                    newTitle = ""
                                    newNotes = ""
                                    newDueAt = null
                                }
                            }
                        },
                        enabled = newTitle.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AddCircle,
                            contentDescription = "Add Task",
                            tint = if (newTitle.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                        )
                    }
                }

                // Quick presets: Today, Tomorrow, Priority
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Due Date Pill
                    val isDueSet = newDueAt != null
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isDueSet) Color(0xFF10B981).copy(alpha = 0.15f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (isDueSet) Color(0xFF10B981) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable {
                            // Quick toggle today 8pm vs tomorrow 9am vs clear
                            val cal = Calendar.getInstance()
                            if (newDueAt == null) {
                                cal.add(Calendar.HOUR_OF_DAY, 2)
                                newDueAt = cal.timeInMillis
                            } else {
                                newDueAt = null
                            }
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Alarm,
                                contentDescription = null,
                                tint = if (isDueSet) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDueSet) "Reminder Set" else "Set Reminder",
                                fontSize = 11.sp,
                                color = if (isDueSet) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Priority Pill
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (newPriority == TaskPriority.HIGH) Color(0xFFEF4444).copy(alpha = 0.15f) else Color.Transparent,
                        border = BorderStroke(1.dp, if (newPriority == TaskPriority.HIGH) Color(0xFFEF4444) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                        modifier = Modifier.clickable {
                            newPriority = when (newPriority) {
                                TaskPriority.LOW -> TaskPriority.NORMAL
                                TaskPriority.NORMAL -> TaskPriority.HIGH
                                TaskPriority.HIGH -> TaskPriority.LOW
                            }
                        }
                    ) {
                        Text(
                            text = "Priority: ${newPriority.label}",
                            fontSize = 11.sp,
                            color = if (newPriority == TaskPriority.HIGH) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
        }

        // Task List
        if (filteredTasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Outlined.Checklist,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (filterTab == TaskFilterTab.COMPLETED) "No completed tasks yet" else "All tasks caught up!",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    SmartTaskCard(
                        task = task,
                        onToggle = { scope.launch { repo.toggleComplete(task.id) } },
                        onDelete = { scope.launch { repo.deleteTask(task.id) } }
                    )
                }
            }
        }
    }
}

@Composable
fun SmartTaskCard(
    task: SmartTask,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val isOverdue = task.dueAt != null && task.dueAt < System.currentTimeMillis() && !task.completed

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (isOverdue) Color(0xFFEF4444).copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
        ),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Checkbox
            IconButton(
                onClick = onToggle,
                modifier = Modifier.size(28.dp)
            ) {
                if (task.completed) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = "Completed",
                        tint = Color(0xFF10B981),
                        modifier = Modifier.size(22.dp)
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.RadioButtonUnchecked,
                        contentDescription = "Pending",
                        tint = when (task.priority) {
                            TaskPriority.HIGH -> Color(0xFFEF4444)
                            TaskPriority.NORMAL -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            TaskPriority.LOW -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.displayHeadline,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (task.completed) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (task.notes.isNotBlank() && task.notes != task.title) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = task.notes,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (task.dueAt != null || task.recurrence != TaskRecurrence.NONE || task.category.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (task.dueAt != null) {
                            val dueText = DateUtils.getRelativeDateTimeString(
                                context,
                                task.dueAt,
                                DateUtils.MINUTE_IN_MILLIS,
                                DateUtils.WEEK_IN_MILLIS,
                                0
                            ).toString()
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Outlined.Alarm,
                                    contentDescription = null,
                                    tint = if (isOverdue) Color(0xFFEF4444) else AntiBotCyan,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = dueText,
                                    fontSize = 10.5.sp,
                                    color = if (isOverdue) Color(0xFFEF4444) else AntiBotCyan,
                                    fontWeight = if (isOverdue) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }

                        if (task.recurrence != TaskRecurrence.NONE) {
                            Text(
                                text = "↻ ${task.recurrence.name.lowercase()}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            )
                        }

                        if (task.category.isNotBlank()) {
                            Text(
                                text = "#${task.category}",
                                fontSize = 10.sp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                            )
                        }
                    }
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(28.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.DeleteOutline,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
