package com.example.gemini.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.gemini.data.daemon.*
import com.example.gemini.theme.ClaudeTerracotta
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

private val GitGreen = Color(0xFF2EA043)
private val GitRed = Color(0xFFDA3633)
private val GitBlue = Color(0xFF388BFD)
private val GitOrange = Color(0xFFD29922)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocalVersionControlDialog(
    activeProject: ProjectItem?,
    existingProjects: List<ProjectItem> = emptyList(),
    onDismiss: () -> Unit,
    onProjectSwitched: ((ProjectItem) -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentProject by remember { mutableStateOf(activeProject) }
    var selectedTab by remember { mutableIntStateOf(0) } // 0: Timeline, 1: Changes, 2: Branches

    var status by remember { mutableStateOf<GitStatusResponse?>(null) }
    var branches by remember { mutableStateOf<List<GitBranchInfo>>(emptyList()) }
    var commits by remember { mutableStateOf<List<GitCommitLog>>(emptyList()) }
    var commitDetailsCache by remember { mutableStateOf<Map<String, GitCommitDetails>>(emptyMap()) }
    var expandedCommitHash by remember { mutableStateOf<String?>(null) }

    var isLoading by remember { mutableStateOf(false) }
    var isOperating by remember { mutableStateOf(false) }
    var operationStatus by remember { mutableStateOf<String?>(null) }

    // Quick Checkpoint State
    var checkpointMessage by remember { mutableStateOf("") }
    var showQuickCheckpointDialog by remember { mutableStateOf(false) }

    // Rollback / Revert Confirmation State
    var pendingRollbackCommit by remember { mutableStateOf<GitCommitLog?>(null) }
    var pendingRevertCommit by remember { mutableStateOf<GitCommitLog?>(null) }
    var pendingRestoreFile by remember { mutableStateOf<Pair<String, String>?>(null) } // commitHash to filePath

    // Diff Viewer State
    var activeDiffData by remember { mutableStateOf<Triple<String, String, Boolean>?>(null) } // title to diffText to isDiff

    // Branch creation
    var showNewBranchDialog by remember { mutableStateOf(false) }
    var newBranchName by remember { mutableStateOf("") }
    var showBranchDropdown by remember { mutableStateOf(false) }

    fun refresh() {
        val proj = currentProject?.path ?: return
        scope.launch {
            isLoading = true
            val s = GitApiClient.getStatus(proj)
            status = s
            if (s != null && s.isGitRepo) {
                branches = GitApiClient.getBranches(proj)
                commits = GitApiClient.getLog(proj, limit = 30)
            } else {
                branches = emptyList()
                commits = emptyList()
            }
            isLoading = false
        }
    }

    LaunchedEffect(currentProject?.path) {
        refresh()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.92f)
                .clip(RoundedCornerShape(20.dp)),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // --- TOP APP HEADER ---
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(ClaudeTerracotta.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "Local Version Control",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = GitGreen.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = "Offline Git",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GitGreen,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                            Text(
                                text = "GitHub-style commits, diffs, checkpoints & instant rollback",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(
                            onClick = { refresh() },
                            modifier = Modifier.size(32.dp),
                            enabled = !isLoading && !isOperating
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                // --- PROJECT & BRANCH BAR ---
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Project Selector Chip
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(13.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = currentProject?.name ?: "No Workspace",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }

                        // Branch Selector Dropdown
                        if (status?.isGitRepo != false) {
                            Box {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = ClaudeTerracotta.copy(alpha = 0.12f),
                                    border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.35f)),
                                    modifier = Modifier.clickable { showBranchDropdown = true }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AltRoute,
                                            contentDescription = null,
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = status?.branch ?: "HEAD",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = ClaudeTerracotta,
                                            maxLines = 1
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = ClaudeTerracotta,
                                            modifier = Modifier.size(15.dp)
                                        )
                                    }
                                }

                                DropdownMenu(
                                    expanded = showBranchDropdown,
                                    onDismissRequest = { showBranchDropdown = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("+ Create Branch", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary) },
                                        onClick = {
                                            showBranchDropdown = false
                                            newBranchName = ""
                                            showNewBranchDialog = true
                                        }
                                    )
                                    HorizontalDivider()
                                    branches.forEach { b ->
                                        DropdownMenuItem(
                                            text = {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    if (b.isCurrent) {
                                                        Icon(Icons.Default.Check, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(14.dp))
                                                        Spacer(modifier = Modifier.width(4.dp))
                                                    }
                                                    Text(
                                                        text = b.name,
                                                        fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (b.isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                                                    )
                                                }
                                            },
                                            onClick = {
                                                showBranchDropdown = false
                                                val proj = currentProject?.path ?: return@DropdownMenuItem
                                                scope.launch {
                                                    isOperating = true
                                                    val ok = GitApiClient.checkoutBranch(proj, b.name, create = false)
                                                    if (ok) {
                                                        Toast.makeText(context, "Switched to ${b.name}", Toast.LENGTH_SHORT).show()
                                                        refresh()
                                                    } else {
                                                        Toast.makeText(context, "Failed to switch branch", Toast.LENGTH_SHORT).show()
                                                    }
                                                    isOperating = false
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.weight(1f))

                        // Quick "Save Checkpoint" Button
                        Button(
                            onClick = { showQuickCheckpointDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp),
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isOperating
                        ) {
                            Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Checkpoint", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // Status message or banner
                if (operationStatus != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = operationStatus!!,
                            fontSize = 11.5.sp,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }

                // Non-Git Workspace Banner
                if (status != null && !status!!.isGitRepo) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                            modifier = Modifier.fillMaxWidth(0.9f)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    imageVector = Icons.Default.FolderOff,
                                    contentDescription = null,
                                    tint = ClaudeTerracotta,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = "Local Version Control Not Initialized",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "Track your project offline, save checkpoints before edits, view file diffs, and rollback code anytime without needing GitHub.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(18.dp))
                                Button(
                                    onClick = {
                                        val proj = currentProject?.path ?: return@Button
                                        scope.launch {
                                            isOperating = true
                                            operationStatus = "Initializing local repository..."
                                            val res = GitApiClient.initRepo(proj)
                                            if (res.success) {
                                                operationStatus = "Initialized! Creating initial checkpoint..."
                                                GitApiClient.saveCheckpoint(proj, "Initial Project Checkpoint")
                                                Toast.makeText(context, "Local Version Control ready!", Toast.LENGTH_SHORT).show()
                                                refresh()
                                            } else {
                                                Toast.makeText(context, "Init error: ${res.error}", Toast.LENGTH_LONG).show()
                                            }
                                            isOperating = false
                                            operationStatus = null
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Initialize Local Version Control", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    // --- TAB ROW ---
                    TabRow(
                        selectedTabIndex = selectedTab,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = ClaudeTerracotta,
                        divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)) }
                    ) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Timeline (${commits.size})", fontSize = 12.sp, fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = {
                                val changeCount = status?.totalChangesCount ?: 0
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.PendingActions, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Changes ($changeCount)", fontSize = 12.sp, fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal)
                                    if (changeCount > 0) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Box(
                                            modifier = Modifier
                                                .size(6.dp)
                                                .clip(CircleShape)
                                                .background(GitOrange)
                                        )
                                    }
                                }
                            }
                        )
                        Tab(
                            selected = selectedTab == 2,
                            onClick = { selectedTab = 2 },
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AltRoute, contentDescription = null, modifier = Modifier.size(15.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Branches (${branches.size})", fontSize = 12.sp, fontWeight = if (selectedTab == 2) FontWeight.Bold else FontWeight.Normal)
                                }
                            }
                        )
                    }

                    // --- TAB CONTENT ---
                    Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        when (selectedTab) {
                            0 -> TimelineTabContent(
                                projectPath = currentProject?.path ?: "",
                                commits = commits,
                                expandedHash = expandedCommitHash,
                                onToggleExpand = { hash ->
                                    expandedCommitHash = if (expandedCommitHash == hash) null else hash
                                    if (expandedCommitHash != null && !commitDetailsCache.containsKey(hash)) {
                                        val proj = currentProject?.path ?: return@TimelineTabContent
                                        scope.launch {
                                            val d = GitApiClient.getCommitDetails(proj, hash)
                                            if (d != null) {
                                                commitDetailsCache = commitDetailsCache + (hash to d)
                                            }
                                        }
                                    }
                                },
                                commitDetailsCache = commitDetailsCache,
                                onRollback = { c -> pendingRollbackCommit = c },
                                onRevert = { c -> pendingRevertCommit = c },
                                onViewDiff = { filePath, hash ->
                                    val proj = currentProject?.path ?: return@TimelineTabContent
                                    scope.launch {
                                        val diff = GitApiClient.getCommitFileDiff(proj, hash, filePath) ?: ""
                                        activeDiffData = Triple("$filePath at ${hash.take(7)}", diff, true)
                                    }
                                },
                                onRestoreFile = { filePath, hash ->
                                    pendingRestoreFile = Pair(hash, filePath)
                                }
                            )

                            1 -> ChangesTabContent(
                                projectPath = currentProject?.path ?: "",
                                status = status,
                                onSaveCheckpoint = { msg ->
                                    val proj = currentProject?.path ?: return@ChangesTabContent
                                    scope.launch {
                                        isOperating = true
                                        operationStatus = "Saving checkpoint..."
                                        val res = GitApiClient.saveCheckpoint(proj, msg)
                                        if (res.success) {
                                            Toast.makeText(context, "Checkpoint saved!", Toast.LENGTH_SHORT).show()
                                            refresh()
                                        } else {
                                            Toast.makeText(context, "Error: ${res.error}", Toast.LENGTH_LONG).show()
                                        }
                                        isOperating = false
                                        operationStatus = null
                                    }
                                },
                                onViewFileDiff = { filePath, staged ->
                                    val proj = currentProject?.path ?: return@ChangesTabContent
                                    scope.launch {
                                        val d = GitApiClient.getDiff(proj, filePath, staged)
                                        activeDiffData = Triple(filePath, d?.diff ?: "No differences", false)
                                    }
                                },
                                onDiscardFile = { filePath ->
                                    val proj = currentProject?.path ?: return@ChangesTabContent
                                    scope.launch {
                                        isOperating = true
                                        val ok = GitApiClient.discard(proj, listOf(filePath))
                                        if (ok) refresh()
                                        isOperating = false
                                    }
                                },
                                onDiscardAll = {
                                    val proj = currentProject?.path ?: return@ChangesTabContent
                                    scope.launch {
                                        isOperating = true
                                        val ok = GitApiClient.discard(proj, emptyList())
                                        if (ok) refresh()
                                        isOperating = false
                                    }
                                }
                            )

                            2 -> BranchesTabContent(
                                projectPath = currentProject?.path ?: "",
                                branches = branches,
                                onSwitchBranch = { branchName ->
                                    val proj = currentProject?.path ?: return@BranchesTabContent
                                    scope.launch {
                                        isOperating = true
                                        val ok = GitApiClient.checkoutBranch(proj, branchName, create = false)
                                        if (ok) {
                                            Toast.makeText(context, "Switched to $branchName", Toast.LENGTH_SHORT).show()
                                            refresh()
                                        } else {
                                            Toast.makeText(context, "Failed to switch branch", Toast.LENGTH_SHORT).show()
                                        }
                                        isOperating = false
                                    }
                                },
                                onCreateBranch = { showNewBranchDialog = true }
                            )
                        }
                    }
                }
            }
        }
    }

    // --- QUICK CHECKPOINT DIALOG ---
    if (showQuickCheckpointDialog) {
        var msg by remember { mutableStateOf(checkpointMessage) }
        val presets = listOf("Auto-save before changes", "Feature update", "Bug fix & patch", "Code cleanup")

        AlertDialog(
            onDismissRequest = { showQuickCheckpointDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BookmarkAdd, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save Local Checkpoint", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                }
            },
            text = {
                Column {
                    Text(
                        text = "Creates an offline commit snapshot of all current workspace modifications. You can rollback to this exact state at any time.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = msg,
                        onValueChange = { msg = it },
                        label = { Text("Checkpoint Description") },
                        placeholder = { Text("e.g. Added user profile screen") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Suggestions:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presets) { p ->
                            FilterChip(
                                selected = msg == p,
                                onClick = { msg = p },
                                label = { Text(p, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val proj = currentProject?.path ?: return@Button
                        val finalMsg = msg.ifBlank { "Checkpoint " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) }
                        showQuickCheckpointDialog = false
                        scope.launch {
                            isOperating = true
                            operationStatus = "Saving checkpoint..."
                            val res = GitApiClient.saveCheckpoint(proj, finalMsg)
                            if (res.success) {
                                Toast.makeText(context, "Checkpoint saved!", Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                Toast.makeText(context, "Error: ${res.error}", Toast.LENGTH_LONG).show()
                            }
                            isOperating = false
                            operationStatus = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Save Checkpoint", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showQuickCheckpointDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- ROLLBACK CONFIRMATION DIALOG ---
    if (pendingRollbackCommit != null) {
        val targetCommit = pendingRollbackCommit!!
        AlertDialog(
            onDismissRequest = { pendingRollbackCommit = null },
            icon = { Icon(Icons.Default.Restore, contentDescription = null, tint = GitRed, modifier = Modifier.size(28.dp)) },
            title = { Text("Rollback Workspace to This Version?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "This will restore ALL files in your project to the exact state at commit:",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(text = targetCommit.message, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(text = "SHA: ${targetCommit.shortHash} • ${targetCommit.date}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "⚠️ Uncommitted edits will be discarded. You will be reset to this version.",
                        fontSize = 11.5.sp,
                        color = GitRed,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val proj = currentProject?.path ?: return@Button
                        val hash = targetCommit.hash
                        pendingRollbackCommit = null
                        scope.launch {
                            isOperating = true
                            operationStatus = "Rolling back workspace..."
                            val res = GitApiClient.rollback(proj, hash, hard = true)
                            if (res.success) {
                                Toast.makeText(context, "Workspace rolled back to ${targetCommit.shortHash}!", Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                Toast.makeText(context, "Rollback failed: ${res.error}", Toast.LENGTH_LONG).show()
                            }
                            isOperating = false
                            operationStatus = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GitRed)
                ) {
                    Text("Yes, Rollback Workspace", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRollbackCommit = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- REVERT COMMIT DIALOG ---
    if (pendingRevertCommit != null) {
        val targetCommit = pendingRevertCommit!!
        AlertDialog(
            onDismissRequest = { pendingRevertCommit = null },
            icon = { Icon(Icons.Default.Undo, contentDescription = null, tint = GitOrange, modifier = Modifier.size(28.dp)) },
            title = { Text("Revert This Commit?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text(
                        text = "Creates a new reverse commit that safely undoes the changes introduced by '${targetCommit.message}' without rewriting history.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val proj = currentProject?.path ?: return@Button
                        val hash = targetCommit.hash
                        pendingRevertCommit = null
                        scope.launch {
                            isOperating = true
                            operationStatus = "Reverting commit..."
                            val res = GitApiClient.revert(proj, hash)
                            if (res.success) {
                                Toast.makeText(context, "Commit reverted!", Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                Toast.makeText(context, "Revert failed: ${res.error}", Toast.LENGTH_LONG).show()
                            }
                            isOperating = false
                            operationStatus = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GitOrange)
                ) {
                    Text("Revert Changes", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevertCommit = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- RESTORE SINGLE FILE DIALOG ---
    if (pendingRestoreFile != null) {
        val (hash, filePath) = pendingRestoreFile!!
        AlertDialog(
            onDismissRequest = { pendingRestoreFile = null },
            title = { Text("Restore File to Version?", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Text(
                    text = "Overwrite '$filePath' with its contents at version ${hash.take(7)}?",
                    fontSize = 12.5.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val proj = currentProject?.path ?: return@Button
                        pendingRestoreFile = null
                        scope.launch {
                            isOperating = true
                            val res = GitApiClient.restoreFile(proj, hash, filePath)
                            if (res.success) {
                                Toast.makeText(context, "Restored $filePath!", Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                Toast.makeText(context, "Failed: ${res.error}", Toast.LENGTH_SHORT).show()
                            }
                            isOperating = false
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Restore File", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRestoreFile = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- NEW BRANCH DIALOG ---
    if (showNewBranchDialog) {
        AlertDialog(
            onDismissRequest = { showNewBranchDialog = false },
            title = { Text("Create & Switch to Branch", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = {
                Column {
                    Text("Create a new isolated local branch from the current HEAD:", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(10.dp))
                    OutlinedTextField(
                        value = newBranchName,
                        onValueChange = { newBranchName = it.replace(" ", "-") },
                        label = { Text("Branch Name") },
                        placeholder = { Text("e.g. experiment-feature") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val name = newBranchName.trim()
                        if (name.isBlank()) return@Button
                        val proj = currentProject?.path ?: return@Button
                        showNewBranchDialog = false
                        scope.launch {
                            isOperating = true
                            val ok = GitApiClient.checkoutBranch(proj, name, create = true)
                            if (ok) {
                                Toast.makeText(context, "Created branch $name", Toast.LENGTH_SHORT).show()
                                refresh()
                            } else {
                                Toast.makeText(context, "Failed to create branch", Toast.LENGTH_SHORT).show()
                            }
                            isOperating = false
                        }
                    },
                    enabled = newBranchName.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Create Branch", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNewBranchDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // --- VISUAL DIFF VIEWER DIALOG ---
    if (activeDiffData != null) {
        val (title, diffContent, isCommitDiff) = activeDiffData!!
        Dialog(
            onDismissRequest = { activeDiffData = null },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .clip(RoundedCornerShape(16.dp)),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Difference, contentDescription = null, tint = GitBlue, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { activeDiffData = null }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (diffContent.isBlank()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No difference detected (identical files)", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                                .padding(8.dp)
                        ) {
                            val lines = diffContent.lines()
                            items(lines) { line ->
                                val (bg, fg) = when {
                                    line.startsWith("+") && !line.startsWith("+++") -> Pair(GitGreen.copy(alpha = 0.15f), GitGreen)
                                    line.startsWith("-") && !line.startsWith("---") -> Pair(GitRed.copy(alpha = 0.15f), GitRed)
                                    line.startsWith("@@") -> Pair(GitBlue.copy(alpha = 0.12f), GitBlue)
                                    else -> Pair(Color.Transparent, MaterialTheme.colorScheme.onSurface)
                                }
                                Surface(
                                    color = bg,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = line,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.5.sp,
                                        color = fg,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
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

@Composable
private fun TimelineTabContent(
    projectPath: String,
    commits: List<GitCommitLog>,
    expandedHash: String?,
    onToggleExpand: (String) -> Unit,
    commitDetailsCache: Map<String, GitCommitDetails>,
    onRollback: (GitCommitLog) -> Unit,
    onRevert: (GitCommitLog) -> Unit,
    onViewDiff: (filePath: String, commitHash: String) -> Unit,
    onRestoreFile: (filePath: String, commitHash: String) -> Unit
) {
    if (commits.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                Spacer(modifier = Modifier.height(8.dp))
                Text("No commits recorded yet", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text("Save a checkpoint above to record your first local version.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(commits) { c ->
            val isExpanded = expandedHash == c.hash
            val details = commitDetailsCache[c.hash]

            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                border = BorderStroke(
                    1.dp,
                    if (isExpanded) ClaudeTerracotta.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggleExpand(c.hash) }
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = ClaudeTerracotta.copy(alpha = 0.15f),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            Text(
                                text = c.shortHash,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = ClaudeTerracotta,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = c.message,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = c.author.ifBlank { "Local" },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = " • ${c.date}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(visible = isExpanded) {
                        Column(modifier = Modifier.padding(top = 10.dp)) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                            Spacer(modifier = Modifier.height(8.dp))

                            // File Changes List
                            if (details != null && details.changedFiles.isNotEmpty()) {
                                Text("Changed Files (${details.changedFiles.size}):", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.height(4.dp))
                                details.changedFiles.forEach { f ->
                                    val statusColor = when (f.status) {
                                        "A" -> GitGreen
                                        "D" -> GitRed
                                        else -> GitBlue
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = statusColor.copy(alpha = 0.15f)
                                        ) {
                                            Text(
                                                text = f.status,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = statusColor,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = f.path,
                                            fontSize = 11.5.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        // View Diff
                                        IconButton(
                                            onClick = { onViewDiff(f.path, c.hash) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Difference, contentDescription = "Diff", modifier = Modifier.size(14.dp), tint = GitBlue)
                                        }
                                        // Restore File
                                        IconButton(
                                            onClick = { onRestoreFile(f.path, c.hash) },
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Default.Restore, contentDescription = "Restore File", modifier = Modifier.size(14.dp), tint = ClaudeTerracotta)
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                            }

                            // Commit Action Buttons
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { onRollback(c) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GitRed),
                                    border = BorderStroke(1.dp, GitRed.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Rollback Workspace", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { onRevert(c) },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = GitOrange),
                                    border = BorderStroke(1.dp, GitOrange.copy(alpha = 0.5f)),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                    modifier = Modifier.height(30.dp),
                                    shape = RoundedCornerShape(6.dp)
                                ) {
                                    Icon(Icons.Default.Undo, contentDescription = null, modifier = Modifier.size(13.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Revert Commit", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
private fun ChangesTabContent(
    projectPath: String,
    status: GitStatusResponse?,
    onSaveCheckpoint: (String) -> Unit,
    onViewFileDiff: (filePath: String, isStaged: Boolean) -> Unit,
    onDiscardFile: (filePath: String) -> Unit,
    onDiscardAll: () -> Unit
) {
    var msgInput by remember { mutableStateOf("") }
    val presets = listOf("Auto-save before changes", "Feature implemented", "Bug fix & patch", "Code cleanup")

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Quick Save Checkpoint Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("Save New Version Checkpoint", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    OutlinedTextField(
                        value = msgInput,
                        onValueChange = { msgInput = it },
                        placeholder = { Text("Describe changes...", fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presets) { p ->
                            FilterChip(
                                selected = msgInput == p,
                                onClick = { msgInput = p },
                                label = { Text(p, fontSize = 10.5.sp) }
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            val finalMsg = msgInput.ifBlank { "Checkpoint " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) }
                            onSaveCheckpoint(finalMsg)
                            msgInput = ""
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        modifier = Modifier.fillMaxWidth().height(36.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save Version Checkpoint", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }

        // Uncommitted Changes Section
        val unstaged = status?.unstagedFiles ?: emptyList()
        val staged = status?.stagedFiles ?: emptyList()
        val untracked = status?.untrackedFiles ?: emptyList()
        val total = unstaged.size + staged.size + untracked.size

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Working Changes ($total)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (total > 0) {
                    TextButton(onClick = onDiscardAll) {
                        Text("Discard All Changes", color = GitRed, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (total == 0) {
            item {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = GitGreen.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = GitGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Working tree clean — all changes are committed!", fontSize = 12.sp, color = GitGreen, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        } else {
            items(staged) { f ->
                FileChangeRow(file = f.path, status = f.status, isStaged = true, onViewDiff = { onViewFileDiff(f.path, true) }, onDiscard = { onDiscardFile(f.path) })
            }
            items(unstaged) { f ->
                FileChangeRow(file = f.path, status = f.status, isStaged = false, onViewDiff = { onViewFileDiff(f.path, false) }, onDiscard = { onDiscardFile(f.path) })
            }
            items(untracked) { f ->
                FileChangeRow(file = f.path, status = "U", isStaged = false, onViewDiff = { onViewFileDiff(f.path, false) }, onDiscard = { onDiscardFile(f.path) })
            }
        }
    }
}

@Composable
private fun FileChangeRow(
    file: String,
    status: String,
    isStaged: Boolean,
    onViewDiff: () -> Unit,
    onDiscard: () -> Unit
) {
    val statusColor = when (status) {
        "A", "U" -> GitGreen
        "D" -> GitRed
        else -> GitBlue
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = statusColor.copy(alpha = 0.15f)
            ) {
                Text(
                    text = if (status == "U") "?" else status,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = file,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onViewDiff, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Difference, contentDescription = "View Diff", modifier = Modifier.size(15.dp), tint = GitBlue)
            }
            IconButton(onClick = onDiscard, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.DeleteOutline, contentDescription = "Discard", modifier = Modifier.size(15.dp), tint = GitRed)
            }
        }
    }
}

@Composable
private fun BranchesTabContent(
    projectPath: String,
    branches: List<GitBranchInfo>,
    onSwitchBranch: (String) -> Unit,
    onCreateBranch: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Local Branches (${branches.size})", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.weight(1f))
            Button(
                onClick = onCreateBranch,
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                modifier = Modifier.height(30.dp),
                shape = RoundedCornerShape(6.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("New Branch", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(branches) { b ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (b.isCurrent) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    border = BorderStroke(1.dp, if (b.isCurrent) ClaudeTerracotta.copy(alpha = 0.4f) else Color.Transparent),
                    modifier = Modifier.fillMaxWidth().clickable { onSwitchBranch(b.name) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.AltRoute,
                            contentDescription = null,
                            tint = if (b.isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = b.name,
                            fontWeight = if (b.isCurrent) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 13.sp,
                            color = if (b.isCurrent) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (b.isCurrent) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
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
