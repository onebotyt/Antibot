package com.example.gemini.ui.harness

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.harness.agy.HarnessItem
import com.example.gemini.data.harness.agy.HarnessRunner
import com.example.gemini.data.harness.agy.HarnessSessionSummary
import com.example.gemini.data.harness.agy.HarnessUiState
import com.example.gemini.data.harness.model.AgyEffort
import com.example.gemini.data.harness.model.AgyMode
import com.example.gemini.data.harness.model.HarnessConfig
import com.example.gemini.data.remote.services.AgyCliUpdateTracker
import com.example.gemini.ui.components.MarkdownContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The app's main shell: a native client for the Antigravity CLI.
 *
 * The CLI runs the agent loop and its tools; this screen shows the transcript it
 * streams, keeps the session log, and holds the settings that need a restart.
 */
@Composable
fun HarnessScreen(
    isVisible: Boolean,
    onOpenSettings: () -> Unit
) {
    val context = LocalContext.current
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }
    val runner = remember { HarnessRunner(context, scope) }
    val state by runner.state.collectAsState()

    DisposableEffect(Unit) {
        onDispose {
            runner.stop()
            scope.cancel()
        }
    }

    var draft by remember { mutableStateOf("") }
    var showSessions by remember { mutableStateOf(false) }
    var showConfig by remember { mutableStateOf(false) }
    var showAnalysis by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current

    // A new CLI release or a model this app has not shown yet opens the analysis by itself,
    // once per release/model set so it does not nag.
    val tracker = remember { AgyCliUpdateTracker(context) }
    val analysis by tracker.analysisReport.collectAsState()
    val isCheckingAnalysis by tracker.isChecking.collectAsState()
    LaunchedEffect(isVisible) {
        if (!isVisible) return@LaunchedEffect
        runCatching { tracker.checkUpdatesAndAnalyzeModels(forceRefresh = false) }
        val report = tracker.analysisReport.value ?: return@LaunchedEffect
        if (!report.hasAnythingToShow) return@LaunchedEffect
        val fingerprint = analysisFingerprint(report)
        if (fingerprint != runner.prefs.seenAnalysisFingerprint()) {
            runner.prefs.markAnalysisSeen(fingerprint)
            showAnalysis = true
        }
    }

    LaunchedEffect(state.items.size, state.items.lastOrNull()) {
        if (state.items.isNotEmpty()) {
            listState.animateScrollToItem(state.items.lastIndex)
        }
    }

    val send: (String) -> Unit = { text ->
        runner.send(text)
        draft = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .imePadding()
    ) {
        HarnessTopBar(
            state = state,
            onNewSession = { runner.startNewSession() },
            onSessions = { showSessions = true },
            onConfig = { showConfig = true },
            onOpenSettings = onOpenSettings
        )

        HarnessConfigBar(
            config = state.config,
            hasCliNews = analysis?.hasAnythingToShow == true,
            onClick = { showConfig = true }
        )

        state.status?.let { status ->
            StatusNotice(
                text = status,
                signInUrl = state.signInUrl,
                onSignIn = { url ->
                    runCatching { uriHandler.openUri(url) }
                }
            )
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (state.items.isEmpty()) {
                HarnessEmptyState(
                    onSuggestion = { suggestion -> send(suggestion) }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.items) { item -> HarnessRow(item) }
                }
            }
        }

        HarnessComposer(
            value = draft,
            onValueChange = { draft = it },
            running = state.running,
            onSend = { if (draft.isNotBlank()) send(draft) },
            onStop = { runner.cancel() }
        )
    }

    if (showSessions) {
        SessionPickerDialog(
            runner = runner,
            currentSessionId = state.sessionId,
            onPick = { id ->
                runner.openSession(id)
                showSessions = false
            },
            onNew = {
                runner.startNewSession()
                showSessions = false
            },
            onDismiss = { showSessions = false }
        )
    }

    if (showConfig) {
        HarnessConfigDialog(
            config = state.config,
            onApply = { config ->
                runner.applyConfig(config)
                showConfig = false
            },
            onOpenAnalysis = {
                showConfig = false
                showAnalysis = true
            },
            onDismiss = { showConfig = false }
        )
    }

    if (showAnalysis) {
        analysis?.let { report ->
            com.example.gemini.ui.updater.AgyCliAnalysisDialog(
                report = report,
                isChecking = isCheckingAnalysis,
                onRefresh = { scope.launch { tracker.checkUpdatesAndAnalyzeModels(forceRefresh = true) } },
                onDismiss = {
                    showAnalysis = false
                    tracker.acknowledgeNewModels()
                }
            )
        }
    }
}

/** Identifies one "CLI version + model set" so the analysis shows once for each. */
private fun analysisFingerprint(report: com.example.gemini.data.remote.services.AgyCliAnalysisReport): String =
    report.latestCliVersion.orEmpty() + "|" +
        report.currentCliVersion + "|" +
        report.newModelsDetected.joinToString(",") { it.id }.ifBlank {
            report.allActiveModels.size.toString()
        }

@Composable
private fun HarnessTopBar(
    state: HarnessUiState,
    onNewSession: () -> Unit,
    onSessions: () -> Unit,
    onConfig: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Harness",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                if (state.running) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(13.dp),
                        strokeWidth = 2.dp
                    )
                }
            }
            Text(
                text = state.conversationId?.let { "Antigravity CLI · ${it.take(8)}" }
                    ?: "Antigravity CLI",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onSessions) {
            Icon(Icons.Outlined.History, contentDescription = "Sessions", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onNewSession) {
            Icon(Icons.Default.Add, contentDescription = "New session", modifier = Modifier.size(21.dp))
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", modifier = Modifier.size(20.dp))
        }
    }
}

/** The settings that decide how the next turn runs. */
@Composable
private fun HarnessConfigBar(config: HarnessConfig, hasCliNews: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ConfigPill(text = config.model?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "CLI default", onClick = onClick)
        ConfigPill(text = config.effort.label, onClick = onClick)
        ConfigPill(text = config.mode.label, onClick = onClick)
        if (config.skipPermissions) ConfigPill(text = "Skip permissions", onClick = onClick, accent = true)
        if (config.sandbox) ConfigPill(text = "Sandbox", onClick = onClick)
        if (hasCliNews) ConfigPill(text = "CLI news", onClick = onClick)
    }
}

@Composable
private fun ConfigPill(text: String, onClick: () -> Unit, accent: Boolean = false) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = if (accent) {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        }
    ) {
        Text(
            text = text,
            fontSize = 11.5.sp,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            color = if (accent) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun StatusNotice(text: String, signInUrl: String?, onSignIn: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                fontSize = 12.5.sp,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (signInUrl != null) {
                TextButton(onClick = { onSignIn(signInUrl) }) {
                    Text("Sign in", fontSize = 12.5.sp)
                }
            }
        }
    }
}

@Composable
private fun HarnessEmptyState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "What does this project do?",
        "List the files in this workspace",
        "Run the tests and summarise failures"
    )
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(52.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "Antigravity CLI",
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "The agent runs in this workspace with its own tools. Ask for a task and it works turn by turn.",
            fontSize = 12.5.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        Spacer(Modifier.height(18.dp))
        suggestions.forEach { suggestion ->
            Surface(
                onClick = { onSuggestion(suggestion) },
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
            ) {
                Text(
                    text = suggestion,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Composable
private fun HarnessRow(item: HarnessItem) {
    when (item) {
        is HarnessItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(14.dp, 14.dp, 4.dp, 14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Text(
                    text = item.text,
                    fontSize = 14.sp,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }
        }

        is HarnessItem.Assistant -> Column(Modifier.fillMaxWidth()) {
            MarkdownContent(
                content = if (item.streaming) item.text + " ▍" else item.text,
                modifier = Modifier.fillMaxWidth()
            )
        }

        is HarnessItem.Tool -> ToolCard(item)

        is HarnessItem.Notice -> Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = item.text,
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(10.dp)
            )
        }
    }
}

@Composable
private fun ToolCard(item: HarnessItem.Tool) {
    var expanded by remember(item) { mutableStateOf(false) }
    val output = item.output.orEmpty()
    val shown = if (expanded || output.length <= 400) output else output.take(400) + "…"
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.16f)
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
    ) {
        Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Terminal,
                    contentDescription = null,
                    modifier = Modifier.size(13.dp),
                    tint = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(7.dp))
                Text(
                    text = item.name,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    fontFamily = FontFamily.Monospace,
                    color = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                )
                if (!item.arguments.isNullOrBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = item.arguments,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
            if (shown.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.12f))
                Spacer(Modifier.height(6.dp))
                Text(
                    text = shown,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HarnessComposer(
    value: String,
    onValueChange: (String) -> Unit,
    running: Boolean,
    onSend: () -> Unit,
    onStop: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 46.dp, max = 140.dp),
                placeholder = {
                    Text(
                        text = if (running) "The agent is working…" else "Ask the Antigravity CLI",
                        fontSize = 13.5.sp
                    )
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                shape = RoundedCornerShape(14.dp),
                maxLines = 5
            )
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = { if (running) onStop() else onSend() },
                shape = CircleShape,
                color = if (running) {
                    MaterialTheme.colorScheme.error
                } else if (value.isBlank()) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier
                    .size(46.dp)
                    .align(Alignment.Bottom)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (running) Icons.Default.Stop else Icons.Default.Send,
                        contentDescription = if (running) "Stop" else "Send",
                        modifier = Modifier.size(19.dp),
                        tint = if (running || value.isNotBlank()) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionPickerDialog(
    runner: HarnessRunner,
    currentSessionId: String?,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit
) {
    var sessions by remember { mutableStateOf<List<HarnessSessionSummary>?>(null) }
    LaunchedEffect(Unit) {
        sessions = runner.sessions()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sessions", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            val list = sessions
            when {
                list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                list.isEmpty() -> Text("No sessions yet.", fontSize = 13.sp)

                else -> LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list) { session ->
                        val selected = session.id == currentSessionId
                        Surface(
                            onClick = { onPick(session.id) },
                            shape = RoundedCornerShape(9.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        ) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                Text(
                                    text = session.title,
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = session.id,
                                    fontSize = 10.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onNew) { Text("New session") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun HarnessConfigDialog(
    config: HarnessConfig,
    onApply: (HarnessConfig) -> Unit,
    onOpenAnalysis: () -> Unit,
    onDismiss: () -> Unit
) {
    var model by remember { mutableStateOf(config.model) }
    var effort by remember { mutableStateOf(config.effort) }
    var mode by remember { mutableStateOf(config.mode) }
    var skipPermissions by remember { mutableStateOf(config.skipPermissions) }
    var sandbox by remember { mutableStateOf(config.sandbox) }

    val models = remember {
        AgyCliUpdateTracker.instance.analysisReport.value?.allActiveModels.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("How the CLI runs", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Surface(
                    onClick = onOpenAnalysis,
                    shape = RoundedCornerShape(9.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                ) {
                    Text(
                        text = "CLI updates & new models",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 9.dp)
                    )
                }
                Text("Model", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                ConfigChoiceRow(
                    options = listOf<Pair<String, Any?>>("CLI default" to null) +
                        models.map { it.displayName to it.id },
                    selected = model,
                    onSelect = { model = it as String? }
                )

                Spacer(Modifier.height(14.dp))
                Text("Effort", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                ConfigChoiceRow(
                    options = AgyEffort.entries.map { it.label to it as Any },
                    selected = effort,
                    onSelect = { effort = it as AgyEffort }
                )

                Spacer(Modifier.height(14.dp))
                Text("Mode", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(5.dp))
                ConfigChoiceRow(
                    options = AgyMode.entries.map { it.label to it as Any },
                    selected = mode,
                    onSelect = { mode = it as AgyMode }
                )

                Spacer(Modifier.height(14.dp))
                ToggleRow(
                    title = "Skip permissions",
                    subtitle = "Approve every tool call without asking. The CLI can write files and run commands.",
                    checked = skipPermissions,
                    onCheckedChange = { skipPermissions = it }
                )
                ToggleRow(
                    title = "Sandbox",
                    subtitle = "Run with the CLI's terminal restrictions.",
                    checked = sandbox,
                    onCheckedChange = { sandbox = it }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "These apply from the next turn; the CLI is restarted and resumes this conversation.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onApply(
                    config.copy(
                        model = model,
                        effort = effort,
                        mode = mode,
                        skipPermissions = skipPermissions,
                        sandbox = sandbox
                    )
                )
            }) { Text("Apply") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun ConfigChoiceRow(
    options: List<Pair<String, Any?>>,
    selected: Any?,
    onSelect: (Any?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        options.forEach { (label, value) ->
            val isSelected = value == selected
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(8.dp),
                color = if (isSelected) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                },
                border = if (isSelected) {
                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.55f))
                } else {
                    null
                }
            ) {
                Text(
                    text = label,
                    fontSize = 12.sp,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = subtitle,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                .border(
                    1.dp,
                    if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                    RoundedCornerShape(6.dp)
                ),
            contentAlignment = Alignment.Center
        ) {
            if (checked) Text("✓", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary)
        }
    }
}
