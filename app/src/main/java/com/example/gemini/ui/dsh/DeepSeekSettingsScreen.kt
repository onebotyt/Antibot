package com.example.gemini.ui.dsh

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.dsh.DshChatBackend
import com.example.gemini.data.agent.dsh.DshPluginInfo
import com.example.gemini.data.agent.dsh.DshPreferences
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.ui.components.DshAccent
import kotlinx.coroutines.launch

@Composable
fun DeepSeekSettingsScreen(
    backend: DshChatBackend,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = backend.prefs

    val apiKey by prefs.apiKey.collectAsState()
    val baseUrl by prefs.baseUrl.collectAsState()
    val modelId by prefs.modelId.collectAsState()
    val daemonPort by prefs.daemonPort.collectAsState()
    val autoStart by prefs.autoStartDaemon.collectAsState()
    val dshCommand by prefs.dshCommand.collectAsState()
    val harnessPort by prefs.harnessPort.collectAsState()
    val harnessCommand by prefs.harnessCommand.collectAsState()

    var isDaemonOnline by remember { mutableStateOf(false) }
    var isCheckingOnline by remember { mutableStateOf(false) }
    var pluginsList by remember { mutableStateOf<List<DshPluginInfo>>(emptyList()) }

    var apiKeyInput by remember(apiKey) { mutableStateOf(apiKey) }
    var showApiKey by remember { mutableStateOf(false) }
    var baseUrlInput by remember(baseUrl) { mutableStateOf(baseUrl) }
    var portInput by remember(daemonPort) { mutableStateOf(daemonPort.toString()) }
    var harnessPortInput by remember(harnessPort) { mutableStateOf(harnessPort.toString()) }
    var harnessCommandInput by remember(harnessCommand) { mutableStateOf(harnessCommand) }
    var isSaving by remember { mutableStateOf(false) }

    fun refreshHealth() {
        scope.launch {
            isCheckingOnline = true
            isDaemonOnline = backend.client.isDaemonOnline()
            val plugs = backend.client.listPlugins()
            pluginsList = plugs
            isCheckingOnline = false
        }
    }

    LaunchedEffect(Unit) {
        refreshHealth()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.Outlined.ArrowBack, contentDescription = "Back")
                }
                Spacer(modifier = Modifier.width(8.dp))
            }
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(DshAccent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("🐋", fontSize = 20.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "DeepSeek Harness",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Cordis Meta-Framework & CLI Agent",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = { refreshHealth() }) {
                if (isCheckingOnline) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = DshAccent)
                } else {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", tint = DshAccent)
                }
            }
        }

        // Daemon Status Card
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDaemonOnline) Color(0xFF142B20) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (isDaemonOnline) Color(0xFF10B981) else Color(0xFFEF4444))
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isDaemonOnline) "Daemon Online (127.0.0.1:$daemonPort)" else "Daemon Offline",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = if (isDaemonOnline) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "Port $daemonPort",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = if (isDaemonOnline)
                        "DeepSeek Harness is actively running in Termux, ready to handle tool calls, Cordis plugins, and reasoning turns."
                    else
                        "Run 'npx @deepseek-ai/dsh web --port $daemonPort' in Termux to start the DeepSeek Harness background daemon.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.3f))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "$ dsh web -p $daemonPort",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = Color(0xFF8BE9FD)
                    )
                }
            }
        }

        // Harness UI Shell (the full screen harness the app opens into)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Harness UI Shell",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "AntiBot opens straight into this harness. It runs the app's own copy when nothing answers on the port; a harness already running on that port is reused.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = harnessPortInput,
                    onValueChange = { harnessPortInput = it },
                    label = { Text("Harness UI Port") },
                    placeholder = { Text("4011") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = harnessCommandInput,
                    onValueChange = { harnessCommandInput = it },
                    label = { Text("Harness Launch Command") },
                    placeholder = { Text("npx -y @deepseek-ai/dsh@next web") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        prefs.setHarnessPort(harnessPortInput.toIntOrNull() ?: DshPreferences.DEFAULT_HARNESS_PORT)
                        prefs.setHarnessCommand(harnessCommandInput.trim().ifEmpty { DshPreferences.DEFAULT_HARNESS_COMMAND })
                        Toast.makeText(context, "Harness shell settings saved", Toast.LENGTH_SHORT).show()
                        com.example.gemini.data.agent.dsh.HarnessController.refresh(context, force = true)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DshAccent),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save & Restart Harness")
                }
            }
        }

        // API Credentials Section
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "API Credentials",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                // API Key
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = { Text("DeepSeek API Key") },
                    placeholder = { Text("sk-...") },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = "Toggle Key"
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Base URL
                OutlinedTextField(
                    value = baseUrlInput,
                    onValueChange = { baseUrlInput = it },
                    label = { Text("Base URL (Optional)") },
                    placeholder = { Text("https://api.deepseek.com") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // Port
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = { Text("Daemon Port") },
                    placeholder = { Text("3080") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = {
                        val port = portInput.toIntOrNull() ?: 3080
                        prefs.setApiKey(apiKeyInput.trim())
                        prefs.setBaseUrl(baseUrlInput.trim().ifEmpty { "https://api.deepseek.com" })
                        prefs.setDaemonPort(port)
                        Toast.makeText(context, "DeepSeek settings saved", Toast.LENGTH_SHORT).show()
                        refreshHealth()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DshAccent),
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Save Credentials")
                }
            }
        }

        // Model Selection
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Default Model",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                val models = listOf(
                    Triple("deepseek-reasoner", "DeepSeek R1 (Reasoner)", "Deep Chain-of-Thought reasoning with <think> tokens"),
                    Triple("deepseek-chat", "DeepSeek V3 (Chat)", "High-performance coding agent with Cordis tool calling")
                )

                models.forEach { (id, name, desc) ->
                    val isSelected = modelId == id
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isSelected) DshAccent.copy(alpha = 0.12f) else Color.Transparent)
                            .clickable {
                                prefs.setModelId(id)
                                backend.selectModel(id)
                            }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                prefs.setModelId(id)
                                backend.selectModel(id)
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = DshAccent)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(name, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(desc, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }

        // Master Harness Tools & Execution Engine
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "🛠️ Master Harness Tools",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(DshAccent.copy(alpha = 0.12f))
                            .border(BorderStroke(1.dp, DshAccent.copy(alpha = 0.3f)), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "7 Built-in Tools",
                            fontSize = 11.sp,
                            color = DshAccent,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Text(
                    text = "Autonomous agent tools equipped for DeepSeek-V3 in Termux. When prompted in chat, the model executes these tools live via ReAct cycles.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                val coreTools = listOf(
                    Triple("execute_bash", "Termux shell runner & command execution (`bash.exec`)", Icons.Outlined.Terminal),
                    Triple("read_file", "Read workspace files, configurations, and source code", Icons.Outlined.FolderOpen),
                    Triple("write_file", "Generate, update, or patch files directly in workspace", Icons.Outlined.EditNote),
                    Triple("list_directory", "Inspect folders, directories, and project tree structures", Icons.Outlined.Folder),
                    Triple("git_status", "Check git status, branch changes, and safety checkpoints", Icons.Outlined.CheckCircle),
                    Triple("dispatch_subagent", "Delegate subtasks to @claude, @codex, @opencode, or @gemini", Icons.Outlined.Code),
                    Triple("mcp.call_tool", "Model Context Protocol gateway & connected tool servers", Icons.Outlined.Extension)
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    coreTools.forEach { (toolName, toolDesc, icon) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.Black.copy(alpha = 0.2f))
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(icon, contentDescription = null, tint = DshAccent, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = toolName,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF8BE9FD)
                                )
                                Text(
                                    text = toolDesc,
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Cordis Plugins & Extensions
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Cordis Plugins & Registered Tools",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${pluginsList.size} Plugins",
                        fontSize = 12.sp,
                        color = DshAccent,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "DeepSeek Harness uses the Cordis plugin ecosystem (`dsh-plugin-*`). Installed plugins register modular capabilities and tools:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (pluginsList.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isDaemonOnline) "No custom plugins installed. Standard tools are active." else "Connect to daemon or enter API key to inspect registered tools.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        pluginsList.forEach { plugin ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color.Black.copy(alpha = 0.22f))
                                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)), RoundedCornerShape(10.dp))
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Outlined.Extension, contentDescription = null, tint = DshAccent, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = plugin.name,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color.Black.copy(alpha = 0.35f))
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    ) {
                                        Text("v${plugin.version}", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (plugin.enabled) Color(0xFF10B981) else Color.Gray)
                                    )
                                }

                                if (plugin.description.isNotBlank()) {
                                    Text(
                                        text = plugin.description,
                                        fontSize = 11.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                if (plugin.tools.isNotEmpty()) {
                                    Text(
                                        text = "Tools Provided (${plugin.tools.size}):",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                                    )
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        plugin.tools.forEach { tool ->
                                            Row(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(6.dp))
                                                    .background(DshAccent.copy(alpha = 0.08f))
                                                    .border(BorderStroke(1.dp, DshAccent.copy(alpha = 0.25f)), RoundedCornerShape(6.dp))
                                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Icon(Icons.Outlined.Build, contentDescription = null, tint = DshAccent, modifier = Modifier.size(11.dp))
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Text(
                                                    text = tool,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontSize = 11.sp,
                                                    color = DshAccent
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
