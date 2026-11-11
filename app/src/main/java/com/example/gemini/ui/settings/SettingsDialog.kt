package com.example.gemini.ui.settings

import com.example.gemini.ui.components.SettingsGroupHeader
import com.example.gemini.ui.components.AgyAccent
import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import com.example.gemini.theme.isAppInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ModelQuota
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.local.LocalEnvironmentManager
import com.example.gemini.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.gemini.BuildConfig
import com.example.gemini.data.updater.AppUpdateManager
import com.example.gemini.data.updater.AppUpdateInfo
import com.example.gemini.ui.updater.AppUpdateDialog
import com.example.gemini.data.local.LocalServerManager
import android.app.DownloadManager
import android.widget.Toast
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import com.example.gemini.domain.model.AgentKind
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import android.util.Log
import com.example.gemini.data.admin.AntiGemDeviceAdminReceiver
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipFile
import org.json.JSONObject
import org.json.JSONArray
import com.example.gemini.data.daemon.ProjectItem
import com.example.gemini.data.daemon.TermuxDaemonManager
import com.example.gemini.data.daemon.IdeApiClient

enum class SettingsSection(val title: String, val subtitle: String) {
    MAIN("Settings & Preferences", "Configure AntiBot, engines, tools and app"),
    LLM_MANAGER("Smart LLM Manager", "Task-to-model routing, limit protection & auto-recovery"),
    APPEARANCE("Appearance & Theme", "Theme, dark mode, and chat font scaling"),
    SERVERS("Servers & Connectivity", "Configure AGY Hub and IDE Bridge endpoints"),
    WEBVIEW_EXTENSIONS("Custom AntiBot Plugins", "Install and manage plugins: MCP tools and skills"),
    SLASH_COMMANDS("Slash Commands", "Your own / commands and what they expand to"),
    MCP("MCP Servers", "Model Context Protocol tools & integrations"),
    SKILLS_PLUGINS("Skills & Plugins", "Agent capabilities, Google plugins, and extensions"),
    TERMINAL("Terminal & Shell", "SSH configuration, local tools, and styling"),
    BACKUPS("Backups & Restore", "Backup rootfs environment and AGY chat histories"),
    DATA_PROTECTION("Data Loss Protection", "Uninstall shield & App Info clear data protection"),
    COMMANDS("Commands & Permissions", "Auto-run policy, sandbox mode, and tool approvals"),
    ANTIGRAVITY("Antigravity", "CLI, MCP servers, skills & plugins, commands & permissions"),
    AGY_CLI("Antigravity CLI", "agy version and install, on this device or the server"),
    AGY_CLOUD("Cloud agents", "Antigravity chats on a Google cloud machine (Remote Agents)"),
    CLAUDE("Claude Code", "Account, models, permissions, MCP, plugins & memory"),
    CLAUDE_PAGE("Claude Code", "Account, models, permissions, MCP, plugins & memory"),
    AGENTS("Agents", "Configure active intelligence engine"),
    DIAGNOSTICS("Diagnostics & Performance", "Live network connections, active streams & thread HUD"),
    UTILITIES("Utilities", "Handy device tools"),
    GITHUB("GitHub", "Sign in with the GitHub CLI, your repositories and git credentials"),
    ADB_CONNECT("ADB Connect", "Connect the terminal's adb to this phone over Wireless debugging"),
    ANDROID_AUTOMATION("Android Automation", "Let AI agents see and control this phone"),
    X11_DESKTOP("X11 Desktop", "Linux desktop apps with Termux:X11"),
    CHANGELOG("What's new", "Every AntiBot version and what changed"),
    ABOUT("About & Info", "App version, package details, and bridge export"),
    JARVIS("Personal Assistant", "Autonomous voice interaction & hands-free assistant"),
    JARVIS_AGENT("Personal Assistant · Agent", "Which agent and model runs your assistant"),
    JARVIS_PERSONALITY("Personal Assistant · Personality & Name", "Custom name, voice engine, speed, quiet mode"),
    JARVIS_SETUP("Personal Assistant · Setup", "Verify connection, mic permission, and tools"),
    JARVIS_WAKE("Personal Assistant · Wake Word", "Wake phrase and hotword triggers"),
    JARVIS_LISTEN("Personal Assistant · Listening & Silence", "Speech timeout, auto-send and silence threshold"),
    JARVIS_VOICE("Personal Assistant · Voice & Audio", "TTS engines and speech parameters"),
    JARVIS_TASKS("Personal Assistant · Tasks & Automation", "Autonomous tasks and background monitoring"),
    JARVIS_APPROVALS("Personal Assistant · Approvals & Security", "Execution safety and tool confirmation"),
    JARVIS_SCREEN("Personal Assistant · Screen & Context", "Screen awareness and visual context"),
    JARVIS_CHAT("Personal Assistant · Chat & Logs", "Voice session history and transcript logs"),
    REA("Reverse engineering (REA)", "JADX, APKTool, Hermes, native binaries & MCP sandbox"),
    REA_GROUPS("REA tool groups", "Choose active tool groups for agents"),
    ANDROID_DEV("Android Development", "Gradle, Jetpack Compose, Flutter & React Native on phone"),
    CODEX("Codex", "OpenAI Codex CLI, models & permissions"),
    CODEX_PAGE("Codex", "Codex sub-page"),
    OPENCODE("OpenCode", "OpenCode CLI, custom providers & endpoints"),
    OPENCODE_PAGE("OpenCode", "OpenCode sub-page");

    val jarvisPage: com.example.gemini.ui.jarvis.JarvisSettingsPage? get() = when (this) {
        JARVIS_AGENT -> com.example.gemini.ui.jarvis.JarvisSettingsPage.AGENT
        JARVIS_PERSONALITY -> com.example.gemini.ui.jarvis.JarvisSettingsPage.PERSONALITY
        JARVIS_SETUP -> com.example.gemini.ui.jarvis.JarvisSettingsPage.SETUP
        JARVIS_WAKE -> com.example.gemini.ui.jarvis.JarvisSettingsPage.WAKE
        JARVIS_LISTEN -> com.example.gemini.ui.jarvis.JarvisSettingsPage.LISTEN
        JARVIS_VOICE -> com.example.gemini.ui.jarvis.JarvisSettingsPage.VOICE
        JARVIS_TASKS -> com.example.gemini.ui.jarvis.JarvisSettingsPage.TASKS
        JARVIS_APPROVALS -> com.example.gemini.ui.jarvis.JarvisSettingsPage.APPROVALS
        JARVIS_SCREEN -> com.example.gemini.ui.jarvis.JarvisSettingsPage.SCREEN
        JARVIS_CHAT -> com.example.gemini.ui.jarvis.JarvisSettingsPage.CHAT
        else -> null
    }

    /** Where Back goes from this section. */
    val parent: SettingsSection get() = when (this) {
        AGY_CLI, AGY_CLOUD, MCP, SKILLS_PLUGINS, COMMANDS -> ANTIGRAVITY
        CLAUDE_PAGE -> CLAUDE
        CODEX_PAGE -> CODEX
        OPENCODE_PAGE -> OPENCODE
        ADB_CONNECT, ANDROID_AUTOMATION, X11_DESKTOP, ANDROID_DEV -> UTILITIES
        REA_GROUPS -> REA
        JARVIS_AGENT, JARVIS_PERSONALITY, JARVIS_SETUP, JARVIS_WAKE, JARVIS_LISTEN,
        JARVIS_VOICE, JARVIS_TASKS, JARVIS_APPROVALS, JARVIS_SCREEN, JARVIS_CHAT -> JARVIS
        else -> MAIN
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsDialog(
    userEmail: String?,
    projectId: String,
    tier: String,
    availableModels: List<AiModel> = AiModel.DEFAULT_MODELS,
    enabledModelIds: Set<String>?,
    quotas: List<ModelQuota>,
    isServerListening: Boolean = false,
    isServerLoading: Boolean = false,
    contextWindowLimit: Int = 10,
    summaryModelId: String = "always_ask",
    isDevModeEnabled: Boolean = false,
    chatFontScale: Float = 1.0f,
    themeMode: String = "SYSTEM",
    agyHubUrl: String = com.example.gemini.data.preferences.AuthPreferences.currentHubUrl,
    agyBridgeHttpUrl: String = com.example.gemini.data.preferences.AuthPreferences.currentBridgeHttpUrl,
    isServerOnline: Boolean = true,
    isBridgeOnline: Boolean = false,
    useSshTerminal: Boolean = false,
    sshHost: String = "127.0.0.1",
    sshPort: Int = 8022,
    sshUser: String = "root",
    sshPass: String = "root",
    terminalFontSize: Int = 14,
    terminalCursorStyle: String = "BAR",
    terminalBufferSize: Int = 20000,
    terminalTheme: String = "DEFAULT",
    isLocalToolsEnabled: Boolean = false,
    isLocalToolsInstalled: Boolean = false,
    onLoginWithGoogle: () -> Unit,
    onToggleServer: (Boolean) -> Unit = {},
    onLogout: () -> Unit = {},
    onManualTokenEntered: (String) -> Unit,
    onToggleModelEnabled: (String, Boolean) -> Unit,
    onEnableAllModels: () -> Unit,
    onRefreshQuotas: () -> Unit,
    onSetContextWindowLimit: (Int) -> Unit = {},
    onSetSummaryModelId: (String) -> Unit = {},
    onSetChatFontScale: (Float) -> Unit = {},
    onSetThemeMode: (String) -> Unit = {},
    onSaveServerUrls: (String, String) -> Unit = { _, _ -> },
    onSaveTerminalPreferences: (Int, String, Int, String) -> Unit = { _, _, _, _ -> },
    onToggleDevMode: (Boolean) -> Unit = {},
    onToggleUseSshTerminal: (Boolean) -> Unit = {},
    onSaveSshSettings: (String, Int, String, String) -> Unit = { _, _, _, _ -> },
    onToggleLocalTools: (Boolean) -> Unit = {},
    onInstallLocalTools: () -> Unit = {},
    onOpenLocalTerminal: () -> Unit = {},
    onResetLocalTools: () -> Unit = {},
    mcpServers: List<com.example.gemini.domain.model.McpServerState> = emptyList(),
    isMcpLoading: Boolean = false,
    isMcpRefreshing: Boolean = false,
    refreshingMcpServer: String? = null,
    mcpErrorMessage: String? = null,
    mcpStatusMessage: String? = null,
    onClearMcpStatus: () -> Unit = {},
    onRefreshMcpServers: () -> Unit = {},
    onRefreshMcpServer: (String) -> Unit = {},
    onToggleMcpServer: (String, Boolean) -> Unit = { _, _ -> },
    onSaveMcpServer: (com.example.gemini.domain.model.McpServerSpec, String?) -> Unit = { _, _ -> },
    onDeleteMcpServer: (String) -> Unit = {},
    availableCascadePlugins: List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto> = emptyList(),
    isCascadePluginsLoading: Boolean = false,
    installingCascadePluginId: String? = null,
    onSearchCascadePlugins: (String) -> Unit = {},
    onInstallCascadePlugin: (com.example.gemini.data.remote.dto.AvailableCascadePluginDto) -> Unit = {},
    allSkills: List<com.example.gemini.data.remote.dto.SkillDefinitionDto> = emptyList(),
    isSkillsLoading: Boolean = false,
    skillsFilterScope: String = "GLOBAL",
    onSetSkillsFilterScope: (String) -> Unit = {},
    onRefreshSkills: () -> Unit = {},
    installedPlugins: List<com.example.gemini.data.remote.dto.InstalledPluginDto> = emptyList(),
    isInstalledPluginsLoading: Boolean = false,
    onRefreshInstalledPlugins: () -> Unit = {},
    googlePluginsCatalog: List<com.example.gemini.data.remote.dto.BuildWithGooglePluginItemDto> = emptyList(),
    isGooglePluginsLoading: Boolean = false,
    installingGooglePluginId: String? = null,
    deletingPluginId: String? = null,
    onRefreshGooglePlugins: () -> Unit = {},
    onInstallGooglePlugin: (String, String) -> Unit = { _, _ -> },
    onDeletePlugin: (String, String) -> Unit = { _, _ -> },
    pluginActionStatusMessage: String? = null,
    pluginActionErrorMessage: String? = null,
    onClearPluginStatus: () -> Unit = {},
    isFloatingSwitcherEnabled: Boolean = true,
    floatingSwitcherOrientation: String = "HORIZONTAL",
    floatingSwitcherItems: List<String> = listOf("chat", "ide", "terminal", "browser", "notes"),
    floatingSwitcherAutoCollapseSec: Int = 0,
    onToggleFloatingSwitcher: (Boolean) -> Unit = {},
    onSetFloatingSwitcherOrientation: (String) -> Unit = {},
    onSetFloatingSwitcherItems: (List<String>) -> Unit = {},
    onSetFloatingSwitcherAutoCollapseSec: (Int) -> Unit = {},
    onResetFloatingSwitcherPosition: () -> Unit = {},
    commandAutoExecutionPolicy: String = "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER",
    commandSandboxEnabled: Boolean = false,
    requireApprovalForFileEdits: Boolean = false,
    defaultApprovalScope: String = "PERMISSION_SCOPE_ONCE",
    groupChatsByWorkspace: Boolean = false,
    onToggleGroupChatsByWorkspace: (Boolean) -> Unit = {},
    globalSecuritySettings: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings? = null,
    globalSettingsError: String? = null,
    isGlobalSettingsLoading: Boolean = false,
    projectsList: List<com.example.gemini.data.remote.AgyHubClient.ProjectItem> = emptyList(),
    isProjectsLoading: Boolean = false,
    onSetGlobalArtifactReviewMode: (String) -> Unit = {},
    onSetGlobalSecurityPreset: (autoExec: String, fileAccess: String) -> Unit = { _, _ -> },
    onSetGlobalCustomTerminalPolicy: (String) -> Unit = {},
    onSetGlobalCustomFileAccessPolicy: (String) -> Unit = {},
    onSetGlobalTerminalSandbox: (Boolean) -> Unit = {},
    onSetProjectInheritGlobal: (com.example.gemini.data.remote.AgyHubClient.ProjectItem) -> Unit = {},
    onSetProjectPreset: (com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String) -> Unit = { _, _, _ -> },
    onRefreshSecurityAndProjects: () -> Unit = {},
    onSetCommandAutoExecutionPolicy: (String) -> Unit = {},
    onSetCommandSandboxEnabled: (Boolean) -> Unit = {},
    onSetRequireApprovalForFileEdits: (Boolean) -> Unit = {},
    onSetDefaultApprovalScope: (String) -> Unit = {},
    onAddPermissionRule: (action: String, pattern: String, decision: String) -> Unit = { _, _, _ -> },
    onRemovePermissionRule: (rawRule: String) -> Unit = {},
    onChangePermissionRuleDecision: (rawRule: String, newDecision: String) -> Unit = { _, _ -> },
    isFloatingDiagnosticsEnabled: Boolean = false,
    onToggleFloatingDiagnostics: (Boolean) -> Unit = {},
    isNetworkInspectorEnabled: Boolean = false,
    onToggleNetworkInspector: (Boolean) -> Unit = {},
    isFloatingNetworkInspectorEnabled: Boolean = false,
    onToggleFloatingNetworkInspector: (Boolean) -> Unit = {},
    onOpenNetworkInspector: () -> Unit = {},
    claude: com.example.gemini.ui.claude.ClaudeSettingsDeps? = null,
    initialClaudePage: com.example.gemini.ui.claude.ClaudeSettingsPage? = null,
    openCode: com.example.gemini.data.agent.opencode.OpenCodeChatBackend? = null,
    initialOpenCodePage: com.example.gemini.ui.opencode.OpenCodeSettingsPage? = null,
    codex: com.example.gemini.data.agent.codex.CodexChatBackend? = null,
    initialCodexPage: com.example.gemini.ui.codex.CodexSettingsPage? = null,
    initialSection: SettingsSection? = null,
    enabledAgents: Set<com.example.gemini.domain.model.AgentKind> = setOf(com.example.gemini.domain.model.AgentKind.AGY),
    isApplyingAgents: Boolean = false,
    onApplyAgents: (Set<com.example.gemini.domain.model.AgentKind>) -> Unit = {},
    onOpenProject: ((String) -> Unit)? = null,
    onSwitchAgent: ((com.example.gemini.domain.model.AgentKind) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authPrefs = remember(context) { AuthPreferences(context) }
    var currentSection by remember { mutableStateOf(initialSection ?: if (initialClaudePage != null && claude != null) SettingsSection.CLAUDE_PAGE else SettingsSection.MAIN) }
    var claudePage by remember { mutableStateOf(initialClaudePage ?: com.example.gemini.ui.claude.ClaudeSettingsPage.ACCOUNT) }
    var openCodePage by remember { mutableStateOf(initialOpenCodePage ?: com.example.gemini.ui.opencode.OpenCodeSettingsPage.SERVER) }
    var codexPage by remember { mutableStateOf(initialCodexPage ?: com.example.gemini.ui.codex.CodexSettingsPage.ACCOUNT) }
    // Hoisted so the main list keeps its position while a sub-section is open
    val mainMenuScrollState = rememberScrollState()

    // Intercept system Back button
    BackHandler(enabled = true) {
        if (currentSection != SettingsSection.MAIN) {
            currentSection = currentSection.parent
        } else {
            onDismiss()
        }
    }

    val isDark = isAppInDarkTheme()
    val cardBg = if (isDark) Color(0xFF16181D) else Color.White
    val cardBorder = BorderStroke(1.dp, if (isDark) Color(0xFF282C37) else Color(0xFFE2E8F0))

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .windowInsetsPadding(WindowInsets.imeAnimationTarget)
        ) {
            // Top App Bar (Image 6)
            val isClaudePage = currentSection == SettingsSection.CLAUDE_PAGE && claude != null
            val isOpenCodePage = currentSection == SettingsSection.OPENCODE_PAGE && openCode != null
            val isCodexPage = currentSection == SettingsSection.CODEX_PAGE && codex != null
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = if (isClaudePage) "Claude Code · ${claudePage.title}"
                                else if (isOpenCodePage) "OpenCode · ${openCodePage.title}"
                                else if (isCodexPage) "Codex · ${codexPage.title}"
                                else currentSection.title,
                            fontSize = 17.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (isClaudePage) claudePage.subtitle
                                else if (isOpenCodePage) openCodePage.subtitle
                                else if (isCodexPage) codexPage.subtitle
                                else currentSection.subtitle,
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (currentSection != SettingsSection.MAIN) {
                            currentSection = currentSection.parent
                        } else {
                            onDismiss()
                        }
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                actions = {
                    if (currentSection == SettingsSection.MAIN) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)),
                            modifier = Modifier.padding(end = 12.dp)
                        ) {
                            Text(
                                text = "v${BuildConfig.VERSION_NAME}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp)
                            )
                        }
                    } else {
                        TextButton(onClick = onDismiss) {
                            Text(
                                text = "Done",
                                color = ClaudeTerracotta,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )

            HorizontalDivider(
                thickness = 0.8.dp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
            )

            // Animated Screen Content
            AnimatedContent(
                targetState = currentSection,
                transitionSpec = {
                    if (targetState != initialState.parent) {
                        (slideInHorizontally { width -> width } + fadeIn(animationSpec = tween(220)))
                            .togetherWith(slideOutHorizontally { width -> -width / 4 } + fadeOut(animationSpec = tween(180)))
                    } else {
                        (slideInHorizontally { width -> -width / 4 } + fadeIn(animationSpec = tween(220)))
                            .togetherWith(slideOutHorizontally { width -> width } + fadeOut(animationSpec = tween(180)))
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                label = "SettingsSectionTransition"
            ) { section ->
                when (section) {
                    SettingsSection.MAIN -> MainSettingsMenu(
                        themeMode = themeMode,
                        chatFontScale = chatFontScale,
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        useSshTerminal = useSshTerminal,
                        mcpServers = mcpServers,
                        allSkills = allSkills,
                        installedPlugins = installedPlugins,
                        commandAutoExecutionPolicy = commandAutoExecutionPolicy,
                        commandSandboxEnabled = commandSandboxEnabled,
                        isFloatingDiagnosticsEnabled = isFloatingDiagnosticsEnabled,
                        isLocalToolsInstalled = isLocalToolsInstalled,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        scrollState = mainMenuScrollState,
                        enabledAgents = enabledAgents,
                        onNavigate = { currentSection = it }
                    )

                    SettingsSection.LLM_MANAGER -> LlmManagerSubScreen(
                        authPrefs = authPrefs,
                        availableModels = availableModels,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSwitchAgent = onSwitchAgent
                    )

                    SettingsSection.APPEARANCE -> AppearanceSubScreen(
                        themeMode = themeMode,
                        chatFontScale = chatFontScale,
                        groupChatsByWorkspace = groupChatsByWorkspace,
                        isFloatingSwitcherEnabled = isFloatingSwitcherEnabled,
                        floatingSwitcherOrientation = floatingSwitcherOrientation,
                        floatingSwitcherItems = floatingSwitcherItems,
                        floatingSwitcherAutoCollapseSec = floatingSwitcherAutoCollapseSec,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetThemeMode = onSetThemeMode,
                        onSetChatFontScale = onSetChatFontScale,
                        onToggleGroupChatsByWorkspace = onToggleGroupChatsByWorkspace,
                        onToggleFloatingSwitcher = onToggleFloatingSwitcher,
                        onSetFloatingSwitcherOrientation = onSetFloatingSwitcherOrientation,
                        onSetFloatingSwitcherItems = onSetFloatingSwitcherItems,
                        onSetFloatingSwitcherAutoCollapseSec = onSetFloatingSwitcherAutoCollapseSec,
                        onResetFloatingSwitcherPosition = onResetFloatingSwitcherPosition
                    )

                    SettingsSection.SERVERS -> ServersSubScreen(
                        currentHubUrl = agyHubUrl,
                        currentBridgeUrl = agyBridgeHttpUrl,
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSaveServerUrls = onSaveServerUrls
                    )

                    SettingsSection.AGY_CLOUD -> Column(
                        Modifier.fillMaxSize().padding(16.dp)
                    ) {
                        com.example.gemini.ui.cloud.AgyCloudSettingsCard()
                    }

                    SettingsSection.WEBVIEW_EXTENSIONS -> WebViewExtensionsScreen(onChanged = onRefreshMcpServers)

                    SettingsSection.SLASH_COMMANDS -> CustomSlashCommandsScreen()

                    SettingsSection.MCP -> McpSubScreen(
                        mcpServers = mcpServers,
                        isLoading = isMcpLoading,
                        isRefreshing = isMcpRefreshing,
                        refreshingServerName = refreshingMcpServer,
                        errorMessage = mcpErrorMessage,
                        statusMessage = mcpStatusMessage,
                        onClearStatus = onClearMcpStatus,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onRefreshAll = onRefreshMcpServers,
                        onRefreshServer = onRefreshMcpServer,
                        onToggleServer = onToggleMcpServer,
                        onSaveServer = onSaveMcpServer,
                        onDeleteServer = onDeleteMcpServer,
                        availableCascadePlugins = availableCascadePlugins,
                        isCascadePluginsLoading = isCascadePluginsLoading,
                        installingCascadePluginId = installingCascadePluginId,
                        onSearchCascadePlugins = onSearchCascadePlugins,
                        onInstallCascadePlugin = onInstallCascadePlugin
                    )

                    SettingsSection.SKILLS_PLUGINS -> SkillsAndPluginsSubScreen(
                        skills = allSkills,
                        isSkillsLoading = isSkillsLoading,
                        skillsFilterScope = skillsFilterScope,
                        onSetSkillsFilterScope = onSetSkillsFilterScope,
                        onRefreshSkills = onRefreshSkills,
                        installedPlugins = installedPlugins,
                        isInstalledPluginsLoading = isInstalledPluginsLoading,
                        onRefreshInstalledPlugins = onRefreshInstalledPlugins,
                        googlePlugins = googlePluginsCatalog,
                        isGooglePluginsLoading = isGooglePluginsLoading,
                        installingGooglePluginId = installingGooglePluginId,
                        deletingPluginId = deletingPluginId,
                        onRefreshGooglePlugins = onRefreshGooglePlugins,
                        onInstallGooglePlugin = onInstallGooglePlugin,
                        onDeletePlugin = onDeletePlugin,
                        statusMessage = pluginActionStatusMessage,
                        errorMessage = pluginActionErrorMessage,
                        onClearStatus = onClearPluginStatus,
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )


                    SettingsSection.TERMINAL -> TerminalSubScreen(
                        useSshTerminal = useSshTerminal,
                        sshHost = sshHost,
                        sshPort = sshPort,
                        sshUser = sshUser,
                        sshPass = sshPass,
                        isLocalToolsEnabled = isLocalToolsEnabled,
                        isLocalToolsInstalled = isLocalToolsInstalled,
                        terminalFontSize = terminalFontSize,
                        terminalCursorStyle = terminalCursorStyle,
                        terminalBufferSize = terminalBufferSize,
                        terminalTheme = terminalTheme,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onToggleUseSshTerminal = onToggleUseSshTerminal,
                        onSaveSshSettings = onSaveSshSettings,
                        onToggleLocalTools = onToggleLocalTools,
                        onInstallLocalTools = onInstallLocalTools,
                        onResetLocalTools = onResetLocalTools,
                        onSaveTerminalPreferences = onSaveTerminalPreferences,
                        onOpenLocalTerminal = onOpenLocalTerminal
                    )

                    SettingsSection.BACKUPS -> BackupsSubScreen(
                        isServerOnline = isServerOnline,
                        isBridgeOnline = isBridgeOnline,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onStopServer = {
                            LocalServerManager.stopServer()
                            onToggleServer(false)
                        }
                    )

                    SettingsSection.DATA_PROTECTION -> DataProtectionSubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )

                    SettingsSection.COMMANDS -> CommandsSubScreen(
                        commandAutoExecutionPolicy = commandAutoExecutionPolicy,
                        commandSandboxEnabled = commandSandboxEnabled,
                        requireApprovalForFileEdits = requireApprovalForFileEdits,
                        defaultApprovalScope = defaultApprovalScope,
                        globalSecuritySettings = globalSecuritySettings,
                        globalSettingsError = globalSettingsError,
                        isGlobalSettingsLoading = isGlobalSettingsLoading,
                        projectsList = projectsList,
                        isProjectsLoading = isProjectsLoading,
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onSetGlobalArtifactReviewMode = onSetGlobalArtifactReviewMode,
                        onSetGlobalSecurityPreset = onSetGlobalSecurityPreset,
                        onSetGlobalCustomTerminalPolicy = onSetGlobalCustomTerminalPolicy,
                        onSetGlobalCustomFileAccessPolicy = onSetGlobalCustomFileAccessPolicy,
                        onSetGlobalTerminalSandbox = onSetGlobalTerminalSandbox,
                        onSetProjectInheritGlobal = onSetProjectInheritGlobal,
                        onSetProjectPreset = onSetProjectPreset,
                        onRefreshSecurityAndProjects = onRefreshSecurityAndProjects,
                        onSetCommandAutoExecutionPolicy = onSetCommandAutoExecutionPolicy,
                        onSetCommandSandboxEnabled = onSetCommandSandboxEnabled,
                        onSetRequireApprovalForFileEdits = onSetRequireApprovalForFileEdits,
                        onSetDefaultApprovalScope = onSetDefaultApprovalScope,
                        onAddPermissionRule = onAddPermissionRule,
                        onRemovePermissionRule = onRemovePermissionRule,
                        onChangePermissionRuleDecision = onChangePermissionRuleDecision
                    )

                    SettingsSection.DIAGNOSTICS -> DiagnosticsSubScreen(
                        isFloatingDiagnosticsEnabled = isFloatingDiagnosticsEnabled,
                        onToggleFloatingDiagnostics = onToggleFloatingDiagnostics,
                        isNetworkInspectorEnabled = isNetworkInspectorEnabled,
                        onToggleNetworkInspector = onToggleNetworkInspector,
                        isFloatingNetworkInspectorEnabled = isFloatingNetworkInspectorEnabled,
                        onToggleFloatingNetworkInspector = onToggleFloatingNetworkInspector,
                        onOpenNetworkInspector = onOpenNetworkInspector,
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )

                    SettingsSection.AGENTS -> com.example.gemini.ui.agents.AgentsSettingsPage(
                        enabled = enabledAgents,
                        isApplying = isApplyingAgents,
                        onApply = onApplyAgents
                    )
                    SettingsSection.UTILITIES -> UtilitiesSubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onNavigate = { currentSection = it }
                    )
                    SettingsSection.ADB_CONNECT -> AdbConnectScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.ANDROID_AUTOMATION -> AndroidAutomationScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT }
                    )
                    SettingsSection.GITHUB -> GitHubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenProject = { item ->
                            onOpenProject?.invoke(item.path)
                            onDismiss()
                        }
                    )
                    SettingsSection.X11_DESKTOP -> X11DesktopScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT }
                    )
                    SettingsSection.REA -> com.example.gemini.ui.settings.ReaScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenGroups = { currentSection = SettingsSection.REA_GROUPS },
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT },
                        onOpenPlugins = { currentSection = SettingsSection.WEBVIEW_EXTENSIONS }
                    )
                    SettingsSection.ANDROID_DEV -> com.example.gemini.ui.settings.AndroidDevScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenAdbConnect = { currentSection = SettingsSection.ADB_CONNECT },
                        onOpenPlugins = { currentSection = SettingsSection.WEBVIEW_EXTENSIONS }
                    )
                    SettingsSection.REA_GROUPS -> com.example.gemini.ui.settings.ReaGroupsScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpenRea = { currentSection = SettingsSection.REA }
                    )
                    SettingsSection.ANTIGRAVITY -> AntigravitySettingsMenu(
                        mcpServers = mcpServers,
                        allSkills = allSkills,
                        installedPlugins = installedPlugins,
                        commandAutoExecutionPolicy = commandAutoExecutionPolicy,
                        cardBg = cardBg,
                        onNavigate = { currentSection = it }
                    )
                    SettingsSection.AGY_CLI -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
                    ) {
                        com.example.gemini.ui.agents.AgyCliCard()
                    }
                    SettingsSection.CLAUDE -> if (claude != null) {
                        com.example.gemini.ui.claude.ClaudeSettingsGroup(claude, cardBg) { page ->
                            claudePage = page
                            currentSection = SettingsSection.CLAUDE_PAGE
                        }
                    }
                    SettingsSection.CLAUDE_PAGE -> if (claude != null) {
                        com.example.gemini.ui.claude.ClaudeSettingsScreen(
                            claude, claudePage,
                            onOpenRea = { currentSection = SettingsSection.REA },
                            onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
                        ) { currentSection = SettingsSection.CLAUDE }
                    }
                    SettingsSection.CODEX -> {
                        val backend = codex ?: remember {
                            val app = context.applicationContext as android.app.Application
                            val store = com.example.gemini.data.agent.ChatSessionStore()
                            val codexPrefs = com.example.gemini.data.agent.codex.CodexPreferences(app)
                            com.example.gemini.data.agent.codex.CodexChatBackend(app, coroutineScope, store, codexPrefs)
                        }
                        com.example.gemini.ui.codex.CodexSettingsGroup(backend, cardBg) { page ->
                            codexPage = page
                            currentSection = SettingsSection.CODEX_PAGE
                        }
                    }
                    SettingsSection.CODEX_PAGE -> {
                        val backend = codex ?: remember {
                            val app = context.applicationContext as android.app.Application
                            val store = com.example.gemini.data.agent.ChatSessionStore()
                            val codexPrefs = com.example.gemini.data.agent.codex.CodexPreferences(app)
                            com.example.gemini.data.agent.codex.CodexChatBackend(app, coroutineScope, store, codexPrefs)
                        }
                        com.example.gemini.ui.codex.CodexSettingsScreen(
                            backend, codexPage,
                            onOpenRea = { currentSection = SettingsSection.REA },
                            onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
                        )
                    }
                    SettingsSection.OPENCODE -> {
                        val backend = openCode ?: remember {
                            val app = context.applicationContext as android.app.Application
                            val store = com.example.gemini.data.agent.ChatSessionStore()
                            val openCodePrefs = com.example.gemini.data.agent.opencode.OpenCodePreferences(app)
                            com.example.gemini.data.agent.opencode.OpenCodeChatBackend(app, coroutineScope, store, openCodePrefs)
                        }
                        com.example.gemini.ui.opencode.OpenCodeSettingsGroup(backend, cardBg) { page ->
                            openCodePage = page
                            currentSection = SettingsSection.OPENCODE_PAGE
                        }
                    }
                    SettingsSection.OPENCODE_PAGE -> {
                        val backend = openCode ?: remember {
                            val app = context.applicationContext as android.app.Application
                            val store = com.example.gemini.data.agent.ChatSessionStore()
                            val openCodePrefs = com.example.gemini.data.agent.opencode.OpenCodePreferences(app)
                            com.example.gemini.data.agent.opencode.OpenCodeChatBackend(app, coroutineScope, store, openCodePrefs)
                        }
                        com.example.gemini.ui.opencode.OpenCodeSettingsScreen(
                            backend, openCodePage,
                            onOpenRea = { currentSection = SettingsSection.REA },
                            onOpenReaGroups = { currentSection = SettingsSection.REA_GROUPS }
                        )
                    }
                    SettingsSection.CHANGELOG -> ChangelogScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.ABOUT -> AboutSubScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder
                    )
                    SettingsSection.JARVIS -> com.example.gemini.ui.jarvis.JarvisSettingsScreen(
                        cardBg = cardBg,
                        cardBorder = cardBorder,
                        onOpen = { page ->
                            val target = when (page) {
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.AGENT -> SettingsSection.JARVIS_AGENT
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.PERSONALITY -> SettingsSection.JARVIS_PERSONALITY
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.SETUP -> SettingsSection.JARVIS_SETUP
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.WAKE -> SettingsSection.JARVIS_WAKE
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.LISTEN -> SettingsSection.JARVIS_LISTEN
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.VOICE -> SettingsSection.JARVIS_VOICE
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.TASKS -> SettingsSection.JARVIS_TASKS
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.APPROVALS -> SettingsSection.JARVIS_APPROVALS
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.SCREEN -> SettingsSection.JARVIS_SCREEN
                                com.example.gemini.ui.jarvis.JarvisSettingsPage.CHAT -> SettingsSection.JARVIS_CHAT
                            }
                            currentSection = target
                        }
                    )
                    SettingsSection.JARVIS_AGENT, SettingsSection.JARVIS_PERSONALITY, SettingsSection.JARVIS_SETUP,
                    SettingsSection.JARVIS_WAKE, SettingsSection.JARVIS_LISTEN, SettingsSection.JARVIS_VOICE,
                    SettingsSection.JARVIS_TASKS, SettingsSection.JARVIS_APPROVALS, SettingsSection.JARVIS_SCREEN,
                    SettingsSection.JARVIS_CHAT -> {
                        currentSection.jarvisPage?.let { page ->
                            com.example.gemini.ui.jarvis.JarvisSettingsSectionPage(
                                page = page,
                                cardBg = cardBg,
                                cardBorder = cardBorder
                            )
                        }
                    }
                }
            }
        }
    }
}


enum class SettingsBadgeStyle {
    GREEN, LAVENDER, AMBER, BLUE, GREY
}

@Composable
private fun MainSettingsMenu(
    themeMode: String,
    chatFontScale: Float,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean,
    useSshTerminal: Boolean,
    mcpServers: List<com.example.gemini.domain.model.McpServerState>,
    allSkills: List<com.example.gemini.data.remote.dto.SkillDefinitionDto> = emptyList(),
    installedPlugins: List<com.example.gemini.data.remote.dto.InstalledPluginDto> = emptyList(),
    commandAutoExecutionPolicy: String,
    commandSandboxEnabled: Boolean,
    isFloatingDiagnosticsEnabled: Boolean = false,
    isLocalToolsInstalled: Boolean = false,
    cardBg: Color,
    cardBorder: BorderStroke,
    scrollState: androidx.compose.foundation.ScrollState,
    enabledAgents: Set<com.example.gemini.domain.model.AgentKind> = setOf(com.example.gemini.domain.model.AgentKind.AGY),
    onNavigate: (SettingsSection) -> Unit
) {
    val agyOn = com.example.gemini.domain.model.AgentKind.AGY in enabledAgents
    val localOn = com.example.gemini.domain.model.AgentKind.LOCAL in enabledAgents

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Agents Category (Image 3)
        val serverBadge = when {
            isServerOnline && isBridgeOnline -> "Online"
            isServerOnline || isBridgeOnline -> "Partial"
            else -> "Offline"
        }
        val serverBadgeStyle = when {
            isServerOnline && isBridgeOnline -> SettingsBadgeStyle.GREEN
            isServerOnline || isBridgeOnline -> SettingsBadgeStyle.AMBER
            else -> SettingsBadgeStyle.GREY
        }
        SettingsCategoryContainer(
            headerIcon = Icons.Outlined.Code,
            headerTitle = "Agents",
            headerSubtitle = "Models, Antigravity and local engine",
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            SettingsGroupItemRow(
                icon = Icons.Outlined.SmartToy,
                title = "Agents",
                subtitle = "Antigravity and Local Engine",
                badgeText = "${enabledAgents.size} On",
                badgeStyle = SettingsBadgeStyle.GREEN,
                onClick = { onNavigate(SettingsSection.AGENTS) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Smart LLM Manager",
                subtitle = "Task routing, quota defense & offline recovery",
                badgeText = "Auto Active",
                badgeStyle = SettingsBadgeStyle.BLUE,
                onClick = { onNavigate(SettingsSection.LLM_MANAGER) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Dns,
                title = "Servers & Connectivity",
                subtitle = "AGY Hub & IDE Bridge endpoints",
                badgeText = serverBadge,
                badgeStyle = serverBadgeStyle,
                onClick = { onNavigate(SettingsSection.SERVERS) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            val cloudOn by com.example.gemini.data.agent.agy.AgyCloud.enabled.collectAsState()
            SettingsGroupItemRow(
                icon = Icons.Outlined.Cloud,
                title = "Cloud agents",
                subtitle = "Google VM Remote Agents & Web Preview",
                badgeText = if (cloudOn) "On" else "Off",
                badgeStyle = if (cloudOn) SettingsBadgeStyle.GREEN else SettingsBadgeStyle.GREY,
                onClick = { onNavigate(SettingsSection.AGY_CLOUD) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "Claude Code",
                subtitle = "Anthropic Claude CLI, account, models & permissions",
                onClick = { onNavigate(SettingsSection.CLAUDE) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Terminal,
                title = "Codex",
                subtitle = "OpenAI Codex CLI, models & permissions",
                onClick = { onNavigate(SettingsSection.CODEX) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Code,
                title = "OpenCode",
                subtitle = "OpenCode CLI, custom providers & endpoints",
                onClick = { onNavigate(SettingsSection.OPENCODE) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Extension,
                title = "Custom AntiBot Plugins",
                subtitle = "Install ZIP, update, rename, enable or disable",
                onClick = { onNavigate(SettingsSection.WEBVIEW_EXTENSIONS) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Terminal,
                title = "Slash Commands",
                subtitle = "Create commands that expand into longer tasks",
                onClick = { onNavigate(SettingsSection.SLASH_COMMANDS) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            val asstName = com.example.gemini.data.jarvis.JarvisPreferences.assistantName.value.ifBlank { "Jarvis" }
            SettingsGroupItemRow(
                icon = Icons.Outlined.RecordVoiceOver,
                title = "Personal Assistant",
                subtitle = "Live voice interaction, tasks & voice orb ($asstName)",
                badgeText = if (com.example.gemini.data.jarvis.JarvisPreferences.enabled.value) "Active" else "Off",
                badgeStyle = if (com.example.gemini.data.jarvis.JarvisPreferences.enabled.value) SettingsBadgeStyle.GREEN else SettingsBadgeStyle.GREY,
                onClick = { onNavigate(SettingsSection.JARVIS) }
            )
        }

        // Section 2: Antigravity Category (Image 3)
        if (agyOn) {
            val activeMcpCount = mcpServers.count { it.isEnabled }
            val totalMcpCount = mcpServers.size
            val mcpBadge = when {
                totalMcpCount == 0 -> "0 Configured"
                activeMcpCount > 0 -> "$activeMcpCount Active"
                else -> "Disabled"
            }
            val totalSkillsCount = allSkills.size
            val totalPluginsCount = installedPlugins.size
            val skillsBadge = "$totalSkillsCount Skills • $totalPluginsCount Plugins"
            val policyBadge = when {
                commandAutoExecutionPolicy.contains("EAGER", ignoreCase = true) -> "⚡ Auto-Run"
                commandAutoExecutionPolicy.contains("AUTO", ignoreCase = true) -> "🛡️ Smart Safety"
                else -> "✋ Ask User"
            }
            val cli by com.example.gemini.data.remote.BridgeInfo.agyCli.collectAsState()
            LaunchedEffect(Unit) { if (cli == null) com.example.gemini.data.remote.BridgeInfo.refreshAgyCli() }
            val (cliBadgeText, cliBadgeStyle) = when {
                cli == null -> "Checking…" to SettingsBadgeStyle.GREY
                cli!!.installed -> cli!!.version.ifBlank { "Installed" } to SettingsBadgeStyle.GREEN
                else -> "Not Installed" to SettingsBadgeStyle.AMBER
            }

            SettingsCategoryContainer(
                headerIcon = Icons.Outlined.ChangeHistory,
                headerTitle = "Antigravity",
                headerSubtitle = "Google Antigravity agent (AGY Hub)",
                cardBg = cardBg,
                cardBorder = cardBorder
            ) {
                SettingsGroupItemRow(
                    icon = Icons.Outlined.Terminal,
                    title = "Antigravity CLI (agy)",
                    subtitle = "Official Google CLI binary, version and installer",
                    badgeText = cliBadgeText,
                    badgeStyle = cliBadgeStyle,
                    onClick = { onNavigate(SettingsSection.AGY_CLI) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                SettingsGroupItemRow(
                    icon = Icons.Outlined.ViewInAr,
                    title = "MCP Servers & Tools",
                    subtitle = if (totalMcpCount > 0) "$totalMcpCount server(s) • global mcp_config" else "Model Context Protocol tools & integrations",
                    badgeText = mcpBadge,
                    badgeStyle = SettingsBadgeStyle.GREEN,
                    onClick = { onNavigate(SettingsSection.MCP) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                SettingsGroupItemRow(
                    icon = Icons.Outlined.AutoAwesome,
                    title = "Skills & Plugins",
                    subtitle = "Installed skills, Google skills and plugins",
                    badgeText = skillsBadge,
                    badgeStyle = SettingsBadgeStyle.LAVENDER,
                    onClick = { onNavigate(SettingsSection.SKILLS_PLUGINS) }
                )
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                SettingsGroupItemRow(
                    icon = Icons.Outlined.Security,
                    title = "Commands & Permissions",
                    subtitle = "Policy, auto-run and sandbox controls",
                    badgeText = policyBadge,
                    badgeStyle = SettingsBadgeStyle.AMBER,
                    onClick = { onNavigate(SettingsSection.COMMANDS) }
                )
            }
        }

        // Section 3: App Category (Image 3)
        val themeLabel = when (themeMode) {
            "DARK" -> "Dark Mode"
            "LIGHT" -> "Light Mode"
            else -> "Auto (System)"
        }
        val diagBadge = if (isFloatingDiagnosticsEnabled) "HUD On" else "HUD Off"

        SettingsCategoryContainer(
            headerIcon = Icons.Outlined.PhoneAndroid,
            headerTitle = "App",
            headerSubtitle = "General app settings",
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            SettingsGroupItemRow(
                icon = Icons.Outlined.Palette,
                title = "Appearance & Theme",
                subtitle = "Theme, color and text size",
                badgeText = themeLabel,
                badgeStyle = SettingsBadgeStyle.GREY,
                onClick = { onNavigate(SettingsSection.APPEARANCE) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Terminal,
                title = "Terminal & Shell Execution",
                subtitle = "Local Linux environment",
                badgeText = if (useSshTerminal) "SSH" else "Local",
                badgeStyle = SettingsBadgeStyle.GREY,
                onClick = { onNavigate(SettingsSection.TERMINAL) }
            )
            if (isLocalToolsInstalled) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                SettingsGroupItemRow(
                    icon = Icons.Outlined.CloudUpload,
                    title = "Backups & Restore",
                    subtitle = "Backup rootfs environment and app data",
                    badgeText = "Rootfs Active",
                    badgeStyle = SettingsBadgeStyle.BLUE,
                    onClick = { onNavigate(SettingsSection.BACKUPS) }
                )
            }
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Storage,
                title = "Data Loss Protection",
                subtitle = "Accidental uninstall shield & app data safety",
                badgeText = "Shield",
                badgeStyle = SettingsBadgeStyle.GREEN,
                onClick = { onNavigate(SettingsSection.DATA_PROTECTION) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.ShowChart,
                title = "Diagnostics & Performance",
                subtitle = "Live network, connections and performance",
                badgeText = diagBadge,
                badgeStyle = SettingsBadgeStyle.GREY,
                onClick = { onNavigate(SettingsSection.DIAGNOSTICS) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.Code,
                title = "GitHub",
                subtitle = "Sign in, repositories and credentials",
                onClick = { onNavigate(SettingsSection.GITHUB) }
            )
        }

        // Section 4: Utilities Category (Image 3)
        SettingsCategoryContainer(
            headerIcon = Icons.Outlined.Handyman,
            headerTitle = "Utilities",
            headerSubtitle = "Handy device tools",
            cardBg = cardBg,
            cardBorder = cardBorder
        ) {
            SettingsGroupItemRow(
                icon = Icons.Outlined.GridView,
                title = "Utilities",
                subtitle = "ADB Connect, Android automation and tools",
                onClick = { onNavigate(SettingsSection.UTILITIES) }
            )
        }

        // Section 5: About & Info Single Card (Image 3)
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            SettingsGroupItemRow(
                icon = Icons.Outlined.Info,
                title = "About & Info",
                subtitle = "App details, version and credits",
                badgeText = "v${BuildConfig.VERSION_NAME}",
                badgeStyle = SettingsBadgeStyle.LAVENDER,
                onClick = { onNavigate(SettingsSection.ABOUT) }
            )
            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            SettingsGroupItemRow(
                icon = Icons.Outlined.AutoAwesome,
                title = "What's new",
                subtitle = "Release changelog and updates",
                onClick = { onNavigate(SettingsSection.CHANGELOG) }
            )
        }
    }
}

/** Antigravity's own page: its CLI, MCP servers, skills & plugins, and commands & permissions. */
@Composable
private fun AntigravitySettingsMenu(
    mcpServers: List<com.example.gemini.domain.model.McpServerState>,
    allSkills: List<com.example.gemini.data.remote.dto.SkillDefinitionDto>,
    installedPlugins: List<com.example.gemini.data.remote.dto.InstalledPluginDto>,
    commandAutoExecutionPolicy: String,
    cardBg: Color,
    onNavigate: (SettingsSection) -> Unit
) {
    val c = com.example.gemini.theme.AppColors
    val activeMcp = mcpServers.count { it.isEnabled }
    val (mcpValue, mcpColor) = when {
        mcpServers.isEmpty() -> "None" to null
        activeMcp > 0 -> "$activeMcp active" to QuotaGreen
        else -> "All off" to c.warning
    }
    val (policyValue, policyColor) = when {
        commandAutoExecutionPolicy.contains("EAGER", ignoreCase = true) -> "Auto-run" to QuotaGreen
        commandAutoExecutionPolicy.contains("AUTO", ignoreCase = true) -> "Smart safety" to c.accentTeal
        else -> "Ask first" to c.warning
    }
    // the CLI row shows the installed version (like Claude Code's Account & CLI row)
    val cli by com.example.gemini.data.remote.BridgeInfo.agyCli.collectAsState()
    LaunchedEffect(Unit) { if (cli == null) com.example.gemini.data.remote.BridgeInfo.refreshAgyCli() }
    val (cliValue, cliColor) = when {
        cli == null -> null to null
        cli!!.installed -> cli!!.version.ifBlank { "Installed" } to QuotaGreen
        else -> "Not installed" to c.warning
    }
    val cloudOn by com.example.gemini.data.agent.agy.AgyCloud.enabled.collectAsState()
    SettingsRowList(
        cardBg = cardBg,
        groups = listOf(
            listOf(
                SettingsRowItem(Icons.Outlined.Terminal, com.example.gemini.theme.GeminiBlue, SettingsSection.AGY_CLI.title, cliValue, cliColor) { onNavigate(SettingsSection.AGY_CLI) },
                SettingsRowItem(Icons.Outlined.Cloud, com.example.gemini.theme.GeminiBlue, SettingsSection.AGY_CLOUD.title,
                    if (cloudOn) "On" else "Off", if (cloudOn) QuotaGreen else null) { onNavigate(SettingsSection.AGY_CLOUD) }
            ),
            listOf(
                SettingsRowItem(Icons.Outlined.Hub, com.example.gemini.theme.GeminiBlue, SettingsSection.MCP.title, mcpValue, mcpColor) { onNavigate(SettingsSection.MCP) },
                SettingsRowItem(Icons.Outlined.AutoAwesome, c.accentAlt, SettingsSection.SKILLS_PLUGINS.title,
                    "${allSkills.size} skills · ${installedPlugins.size} plugins") { onNavigate(SettingsSection.SKILLS_PLUGINS) },
                SettingsRowItem(Icons.Outlined.Security, c.warning, SettingsSection.COMMANDS.title, policyValue, policyColor) { onNavigate(SettingsSection.COMMANDS) }
            )
        )
    )
}

@Composable
private fun SettingsCategoryContainer(
    headerIcon: ImageVector,
    headerTitle: String,
    headerSubtitle: String? = null,
    cardBg: Color,
    cardBorder: BorderStroke,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isAppInDarkTheme()
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isDark) Color(0xFF222630) else Color(0xFFF1F5F9)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = headerIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = headerTitle,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (headerSubtitle != null) {
                    Text(
                        text = headerSubtitle,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsGroupItemRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeStyle: SettingsBadgeStyle = SettingsBadgeStyle.GREY,
    onClick: () -> Unit
) {
    val isDark = isAppInDarkTheme()
    val (badgeBg, badgeTextClr) = when (badgeStyle) {
        SettingsBadgeStyle.GREEN -> (if (isDark) Color(0xFF143823) else Color(0xFFDCFCE7)) to (if (isDark) Color(0xFF4ADE80) else Color(0xFF16A34A))
        SettingsBadgeStyle.LAVENDER -> (if (isDark) Color(0xFF2E1F4D) else Color(0xFFEDE9FE)) to (if (isDark) Color(0xFFC084FC) else Color(0xFF7C3AED))
        SettingsBadgeStyle.AMBER -> (if (isDark) Color(0xFF3D2E14) else Color(0xFFFEF3C7)) to (if (isDark) Color(0xFFFBBF24) else Color(0xFFD97706))
        SettingsBadgeStyle.BLUE -> (if (isDark) Color(0xFF1E2E4A) else Color(0xFFDBEAFE)) to (if (isDark) Color(0xFF60A5FA) else Color(0xFF2563EB))
        SettingsBadgeStyle.GREY -> (if (isDark) Color(0xFF272A34) else Color(0xFFF1F5F9)) to (if (isDark) Color(0xFF94A3B8) else Color(0xFF475569))
    }

    Surface(
        onClick = onClick,
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                modifier = Modifier.size(22.dp)
            )

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(1.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.8.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badgeText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = badgeTextClr,
                        modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun UtilitiesSubScreen(
    cardBg: Color,
    cardBorder: BorderStroke,
    onNavigate: (SettingsSection) -> Unit
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        com.example.gemini.data.adb.AdbConnectManager.init(context)
        com.example.gemini.data.adb.AdbConnectManager.refresh()
    }
    val adb by com.example.gemini.data.adb.AdbConnectManager.state.collectAsState()
    val connected = adb.connectedDevice
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SettingsCategoryCard(
            icon = Icons.Outlined.PhonelinkSetup,
            iconTint = if (connected != null) QuotaGreen else Color(0xFF00ACC1),
            title = "ADB Connect",
            subtitle = connected?.serial ?: "Use adb in the terminal over Wireless debugging",
            badgeText = when {
                connected != null -> "Connected"
                adb.adbInstalled == false -> "Not installed"
                else -> null
            },
            badgeColor = if (connected != null) QuotaGreen else Color(0xFFF59E0B),
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.ADB_CONNECT) }
        )
        var automationAllowed by remember { mutableStateOf(com.example.gemini.data.automation.AndroidAutomation.isServiceRunning()) }
        LaunchedEffect(Unit) {
            while (true) {
                automationAllowed = com.example.gemini.data.automation.AndroidAutomation.isServiceRunning()
                delay(1000)
            }
        }
        SettingsCategoryCard(
            icon = Icons.Outlined.TouchApp,
            iconTint = if (automationAllowed) QuotaGreen else Color(0xFF8B5CF6),
            title = "Android Automation",
            subtitle = "Let AI agents read the screen, tap, type and open apps (MCP)",
            badgeText = if (automationAllowed) "Allowed" else "Off",
            badgeColor = if (automationAllowed) QuotaGreen else Color.Gray,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.ANDROID_AUTOMATION) }
        )
        val x11State by com.example.gemini.data.x11.X11DesktopManager.state.collectAsState()
        SettingsCategoryCard(
            icon = Icons.Outlined.DesktopWindows,
            iconTint = if (x11State.running) QuotaGreen else Color(0xFF3B82F6),
            title = "X11 Desktop",
            subtitle = if (x11State.running) "Running on display :0 (${x11State.desktop.label})" else "Run graphical Linux tools with Termux:X11",
            badgeText = if (x11State.running) "Running" else if (x11State.app == null) "Install app" else null,
            badgeColor = if (x11State.running) QuotaGreen else Color(0xFFF59E0B),
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.X11_DESKTOP) }
        )

        LaunchedEffect(Unit) {
            com.example.gemini.data.rea.ReaManager.init(context)
            com.example.gemini.data.rea.ReaManager.refresh()
            com.example.gemini.data.androiddev.AndroidDevManager.init(context)
            com.example.gemini.data.androiddev.AndroidDevManager.refresh()
        }

        val reaState by com.example.gemini.data.rea.ReaManager.state.collectAsState()
        val reaBadge = when {
            reaState.busy -> "Installing…"
            reaState.usable && reaState.requiredDone -> reaState.reaVersion?.let { "v$it" } ?: "Ready"
            reaState.installed.isNotEmpty() -> "Partly set up"
            else -> null
        }
        val reaColor = if (reaState.usable && reaState.requiredDone) QuotaGreen else Color(0xFFF59E0B)
        SettingsCategoryCard(
            icon = Icons.Outlined.BugReport,
            iconTint = Color(0xFFEC4899),
            title = "Reverse engineering (REA)",
            subtitle = "JADX, APKTool, Hermes, native binaries & MCP sandbox",
            badgeText = reaBadge,
            badgeColor = reaColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.REA) }
        )

        val devState by com.example.gemini.data.androiddev.AndroidDevManager.state.collectAsState()
        val devBadge = when {
            devState.busy -> "Working…"
            devState.anyReady -> com.example.gemini.data.androiddev.AndroidDevManager.Framework.entries
                .filter { devState.ready(it) }
                .joinToString(" · ") { when (it) {
                    com.example.gemini.data.androiddev.AndroidDevManager.Framework.KOTLIN -> "Kotlin"
                    com.example.gemini.data.androiddev.AndroidDevManager.Framework.FLUTTER -> "Flutter"
                    com.example.gemini.data.androiddev.AndroidDevManager.Framework.REACT_NATIVE -> "RN"
                } }
            devState.installed.any { it == com.example.gemini.data.androiddev.AndroidDevManager.Component.SDK || it == com.example.gemini.data.androiddev.AndroidDevManager.Component.LAUNCHER } -> "Partly set up"
            else -> null
        }
        val devColor = if (devState.anyReady) QuotaGreen else Color(0xFFF59E0B)
        SettingsCategoryCard(
            icon = Icons.Outlined.Android,
            iconTint = Color(0xFF10B981),
            title = "Android Development",
            subtitle = "Gradle, Jetpack Compose, Flutter & React Native on phone",
            badgeText = devBadge,
            badgeColor = devColor,
            cardBg = cardBg,
            cardBorder = cardBorder,
            onClick = { onNavigate(SettingsSection.ANDROID_DEV) }
        )
    }
}

@Composable
private fun SettingsCategoryCard(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    badgeText: String? = null,
    badgeColor: Color = ClaudeTerracotta,
    cardBg: Color,
    cardBorder: BorderStroke,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable { onClick() },
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = cardBorder
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(iconTint.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (badgeText != null) {
                Spacer(modifier = Modifier.width(8.dp))
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = badgeText,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeColor,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ==========================================
// SUB-SCREEN: SMART LLM MANAGER
// ==========================================
@Composable
private fun LlmManagerSubScreen(
    authPrefs: com.example.gemini.data.preferences.AuthPreferences,
    availableModels: List<com.example.gemini.domain.model.AiModel>,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSwitchAgent: ((com.example.gemini.domain.model.AgentKind) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()

    val archModel by authPrefs.llmManagerArchitectureModel.collectAsState(initial = authPrefs.getLlmManagerArchitectureModelSync())
    val codeModel by authPrefs.llmManagerCodingModel.collectAsState(initial = authPrefs.getLlmManagerCodingModelSync())
    val fastQaModel by authPrefs.llmManagerFastQaModel.collectAsState(initial = authPrefs.getLlmManagerFastQaModelSync())
    val shellModel by authPrefs.llmManagerShellModel.collectAsState(initial = authPrefs.getLlmManagerShellModelSync())
    val fallbackModel by authPrefs.llmManagerFallbackModel.collectAsState(initial = authPrefs.getLlmManagerFallbackModelSync())
    val preferredCli by authPrefs.llmManagerPreferredCli.collectAsState(initial = authPrefs.getLlmManagerPreferredCliSync())
    val localModelActivity by com.example.gemini.data.agent.local.LocalModelManager.activityState.collectAsState()

    var activeDialogTask by remember { mutableStateOf<String?>(null) } // "ARCH", "CODE", "QA", "SHELL", "FALLBACK"

    fun modelDisplayName(id: String): String {
        return when {
            id == "auto" -> "Auto Dynamic"
            id == "cli:agy" -> "⚡ Antigravity CLI (Gemini Pro/Flash)"
            id == "cli:claude" -> "🟣 Claude Code CLI (Claude 3.5 Sonnet)"
            id == "cli:codex" -> "🟢 Codex CLI (OpenAI Code)"
            id == "cli:opencode" -> "🔷 OpenCode CLI (Multi-Provider)"
            id == "cli:local" -> "🧠 Local Qwen CLI (On-Device)"
            id == "claude" -> "Claude (Sonnet / Opus)"
            id == "gemini" -> "Gemini 3.8 Flash"
            id == "local-qwen-coder-1.5b" -> "Local Qwen 3.5 Coder 1.5B (1B Model)"
            id == "local-qwen-coder-2b" -> "Local Qwen 3.5 Coder 2B (2B Model)"
            id == "local-qwen-coder-3b" -> "Local Qwen 3.5 Coder 3B (3B Model)"
            id == "local-qwen-coder-4b" -> "Local Qwen 3.5 Coder 4B (4B Model)"
            id.startsWith("local") -> "Local Qwen 3.5 Coder 0.5B (0 Tokens)"
            else -> availableModels.find { it.id == id }?.displayName ?: id
        }
    }

    fun modelCliBadge(id: String): Pair<String, Color> {
        val lower = id.lowercase(Locale.ROOT)
        return when {
            lower == "cli:claude" || lower.contains("claude") -> "🟣 Claude Code" to ClaudeTerracotta
            lower == "cli:codex" || lower.contains("codex") || lower.contains("gpt") -> "🟢 Codex CLI" to Color(0xFF10B981)
            lower == "cli:opencode" -> "🔷 OpenCode" to Color(0xFF6366F1)
            lower == "cli:local" || lower.startsWith("local") -> "🧠 Local Qwen" to Color(0xFF10B981)
            else -> "⚡ Antigravity" to Color(0xFF3B82F6)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 🧠 Smart Manager Brain Status Card (Qwen 3.5 Coder On-Device Neural Engine)
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF10B981).copy(alpha = 0.08f),
            border = BorderStroke(1.2.dp, Color(0xFF10B981).copy(alpha = 0.45f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Psychology,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Smart Manager Brain",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(0xFF10B981))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "LOCAL QWEN",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Powered by On-Device Qwen 3.5 Coder 0.5B neural engine. Analyzes intent in <15ms with 0 cloud tokens to seamlessly dispatch tasks across all 5 CLI managers.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Neural Engine Real-Time Metric Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    BrainMetricChip(
                        title = "0 Tokens",
                        subtitle = "Zero Cloud Cost",
                        color = Color(0xFF10B981),
                        modifier = Modifier.weight(1f)
                    )
                    BrainMetricChip(
                        title = "<15ms",
                        subtitle = "Zero Latency",
                        color = Color(0xFF3B82F6),
                        modifier = Modifier.weight(1f)
                    )
                    BrainMetricChip(
                        title = "5 CLIs",
                        subtitle = "Auto Switcher",
                        color = Color(0xFF8B5CF6),
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // 🛡️ Zero-RAM Idle Standby & On-Demand Engine Policy Card
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Zero-RAM Idle Engine Standby",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Local models and CLIs only load into RAM when actively doing work. Zero memory load when inactive.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Live status chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val localActive = localModelActivity is com.example.gemini.data.agent.local.LocalModelActivityState.Active
                    val localRam = (localModelActivity as? com.example.gemini.data.agent.local.LocalModelActivityState.Active)?.allocatedRamMb ?: 0L

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (localActive) Color(0xFF10B981).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "Local Qwen Model",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (localActive) "● Active (~${localRam}MB)" else "💤 Inactive (0 MB RAM)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = if (localActive) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Column {
                            Text(
                                text = "CLI Engines (4)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "💤 Standby (On-Demand)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // ==========================================
        // MULTI-CLI MANAGERS SWITCHER & PREFERENCE
        // ==========================================
        Text(
            text = "CLI Managers & Execution Engines",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // Preferred CLI Filter Chips
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Default / Preferred CLI Engine (Current: ${preferredCli.uppercase(Locale.ROOT)})",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val cliPreferences = listOf(
                "auto" to "Auto (Qwen Decides)",
                "AGY" to "⚡ Antigravity",
                "LOCAL" to "🧠 Local Qwen",
                "CLAUDE" to "🟣 Claude Code",
                "CODEX" to "🟢 Codex",
                "OPENCODE" to "🔷 OpenCode"
            )

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(cliPreferences) { (key, label) ->
                    val isSelected = preferredCli.equals(key, ignoreCase = true)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            coroutineScope.launch {
                                authPrefs.setLlmManagerPreferredCli(key)
                            }
                        },
                        label = {
                            Text(
                                text = label,
                                fontSize = 11.5.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        leadingIcon = if (isSelected) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        } else null
                    )
                }
            }
        }

        // 5 CLI Manager Interactive Switcher Cards
        val cliEngines = listOf(
            Triple(AgentKind.AGY, "Antigravity CLI", "Google Cloud Code Gemini backend · Multimodal · Fast code tools"),
            Triple(AgentKind.LOCAL, "Local Qwen CLI", "On-Device Qwen 3.5 Coder 0.5B · 100% offline · 0 cloud tokens consumed"),
            Triple(AgentKind.CLAUDE, "Claude Code CLI", "Anthropic Claude 3.5 Sonnet · Deep reasoning & architectural design"),
            Triple(AgentKind.CODEX, "Codex CLI", "OpenAI Codex CLI engine · Advanced script & test generation"),
            Triple(AgentKind.OPENCODE, "OpenCode CLI", "Multi-Provider CLI backend · Flexible local/remote models")
        )

        cliEngines.forEach { (agent, name, desc) ->
            CliManagerCard(
                name = name,
                description = desc,
                agent = agent,
                isPreferred = preferredCli.equals(agent.name, ignoreCase = true),
                onSelectPreferred = {
                    coroutineScope.launch {
                        authPrefs.setLlmManagerPreferredCli(agent.name)
                    }
                },
                onSwitchNow = {
                    onSwitchAgent?.invoke(agent)
                },
                cardBg = cardBg,
                cardBorder = cardBorder
            )
        }

        // ==========================================
        // TASK-TO-MODEL ROUTING CONFIGURATION
        // ==========================================
        Text(
            text = "Task Category Routing",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 1. Architecture & Deep Logic
        val archBadge = modelCliBadge(archModel)
        LlmTaskCard(
            title = "Architecture & Deep Logic",
            subtitle = "System design, security audits & high-level architecture",
            selectedModelName = modelDisplayName(archModel),
            cliBadgeText = archBadge.first,
            cliBadgeColor = archBadge.second,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = ClaudeTerracotta,
            onClick = { activeDialogTask = "ARCH" }
        )

        // 2. Complex Coding & Debugging
        val codeBadge = modelCliBadge(codeModel)
        LlmTaskCard(
            title = "Coding & Debugging",
            subtitle = "Refactoring, stack trace investigation & logic fixing",
            selectedModelName = modelDisplayName(codeModel),
            cliBadgeText = codeBadge.first,
            cliBadgeColor = codeBadge.second,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = Color(0xFF8B5CF6),
            onClick = { activeDialogTask = "CODE" }
        )

        // 3. Fast Q&A & General Assistance
        val fastQaBadge = modelCliBadge(fastQaModel)
        LlmTaskCard(
            title = "Fast Q&A & Conversation",
            subtitle = "General questions, summaries & fast explanations",
            selectedModelName = modelDisplayName(fastQaModel),
            cliBadgeText = fastQaBadge.first,
            cliBadgeColor = fastQaBadge.second,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = GeminiBlue,
            onClick = { activeDialogTask = "QA" }
        )

        // 4. Shell & Terminal Execution
        val shellBadge = modelCliBadge(shellModel)
        LlmTaskCard(
            title = "Shell & Terminal Inspection",
            subtitle = "Bash execution, ports & process checks (0 tokens on device)",
            selectedModelName = modelDisplayName(shellModel),
            cliBadgeText = shellBadge.first,
            cliBadgeColor = shellBadge.second,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = Color(0xFF10B981),
            onClick = { activeDialogTask = "SHELL" }
        )

        Text(
            text = "Rate Limit & Quota Defense",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 5. Fallback Model
        val fallbackBadge = modelCliBadge(fallbackModel)
        LlmTaskCard(
            title = "Quota Limit Fallback Model",
            subtitle = "When a premium model hits a 429 rate limit or quota ceiling, Smart Manager automatically swaps to this model seamlessly",
            selectedModelName = modelDisplayName(fallbackModel),
            cliBadgeText = fallbackBadge.first,
            cliBadgeColor = fallbackBadge.second,
            cardBg = cardBg,
            cardBorder = cardBorder,
            accentColor = Color(0xFFF59E0B),
            onClick = { activeDialogTask = "FALLBACK" }
        )

        Text(
            text = "Interruption & Resilient Recovery",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF10B981).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Auto-Record & Resume on Network Loss",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "If internet drops during chat, Smart Manager saves a flight checkpoint and automatically resumes when connection returns.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF3B82F6).copy(alpha = 0.16f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AutoAwesome,
                            contentDescription = null,
                            tint = Color(0xFF3B82F6),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model Error Auto-Retry",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "If an LLM returns a transient generation error, Smart Manager retries execution automatically with the optimal manager.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Model & CLI Engine Picker Dialog
    if (activeDialogTask != null) {
        val task = activeDialogTask!!

        val cliCandidates = listOf(
            "cli:agy" to "⚡ Antigravity CLI (Gemini Cloud Code)",
            "cli:claude" to "🟣 Claude Code CLI (Anthropic Claude 3.5 Sonnet)",
            "cli:codex" to "🟢 Codex CLI (OpenAI Code Execution)",
            "cli:opencode" to "🔷 OpenCode CLI (Multi-Provider Assistant)",
            "cli:local" to "🧠 Local Qwen CLI (On-Device Neural Engine)"
        )

        val modelCandidates = remember(availableModels) {
            val list = mutableListOf<Pair<String, String>>()
            list.add("gemini" to "Gemini 3.8 Flash (High Speed & Large Context)")
            list.add("claude" to "Claude 3.5 Sonnet (Deep Reasoning)")
            list.add("local-qwen-coder-0.5b" to "Local Qwen 3.5 Coder 0.5B (0 Tokens • Built-in)")
            list.add("local-qwen-coder-1.5b" to "Local Qwen 3.5 Coder 1.5B (0 Tokens • 1B Model)")
            list.add("local-qwen-coder-2b" to "Local Qwen 3.5 Coder 2B (0 Tokens • 2B Model)")
            list.add("local-qwen-coder-3b" to "Local Qwen 3.5 Coder 3B (0 Tokens • 3B Model)")
            list.add("local-qwen-coder-4b" to "Local Qwen 3.5 Coder 4B (0 Tokens • 4B Model)")
            availableModels.filter { !it.id.startsWith("local-") }.forEach {
                if (it.id !in list.map { pair -> pair.first }) {
                    list.add(it.id to it.displayName)
                }
            }
            list
        }

        AlertDialog(
            onDismissRequest = { activeDialogTask = null },
            title = {
                Text(
                    text = when (task) {
                        "ARCH" -> "Select Architecture Engine"
                        "CODE" -> "Select Coding Engine"
                        "QA" -> "Select Fast Q&A Engine"
                        "SHELL" -> "Select Shell Engine"
                        else -> "Select Quota Fallback Engine"
                    },
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Category 1: CLI Managers
                    Text(
                        text = "CLI MANAGERS & ENGINES",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        letterSpacing = 0.5.sp
                    )

                    cliCandidates.forEach { (id, name) ->
                        Surface(
                            onClick = {
                                coroutineScope.launch {
                                    when (task) {
                                        "ARCH" -> authPrefs.setLlmManagerArchitectureModel(id)
                                        "CODE" -> authPrefs.setLlmManagerCodingModel(id)
                                        "QA" -> authPrefs.setLlmManagerFastQaModel(id)
                                        "SHELL" -> authPrefs.setLlmManagerShellModel(id)
                                        "FALLBACK" -> authPrefs.setLlmManagerFallbackModel(id)
                                    }
                                    activeDialogTask = null
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Category 2: Cloud & Dedicated Models
                    Text(
                        text = "CLOUD & DEDICATED MODELS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.5.sp
                    )

                    modelCandidates.forEach { (id, name) ->
                        Surface(
                            onClick = {
                                coroutineScope.launch {
                                    when (task) {
                                        "ARCH" -> authPrefs.setLlmManagerArchitectureModel(id)
                                        "CODE" -> authPrefs.setLlmManagerCodingModel(id)
                                        "QA" -> authPrefs.setLlmManagerFastQaModel(id)
                                        "SHELL" -> authPrefs.setLlmManagerShellModel(id)
                                        "FALLBACK" -> authPrefs.setLlmManagerFallbackModel(id)
                                    }
                                    activeDialogTask = null
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = name,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { activeDialogTask = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun BrainMetricChip(
    title: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = subtitle,
                fontSize = 9.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun CliManagerCard(
    name: String,
    description: String,
    agent: com.example.gemini.domain.model.AgentKind,
    isPreferred: Boolean,
    onSelectPreferred: () -> Unit,
    onSwitchNow: () -> Unit,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val accentColor = when (agent) {
        com.example.gemini.domain.model.AgentKind.AGY -> Color(0xFF3B82F6)
        com.example.gemini.domain.model.AgentKind.LOCAL -> Color(0xFF10B981)
        com.example.gemini.domain.model.AgentKind.CLAUDE -> ClaudeTerracotta
        com.example.gemini.domain.model.AgentKind.CODEX -> Color(0xFF10B981)
        com.example.gemini.domain.model.AgentKind.OPENCODE -> Color(0xFF6366F1)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = if (isPreferred) BorderStroke(1.5.dp, accentColor) else cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = name,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isPreferred) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(accentColor.copy(alpha = 0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "PREFERRED",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilledTonalButton(
                        onClick = onSwitchNow,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(30.dp)
                    ) {
                        Text(text = "Switch", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                    }

                    if (!isPreferred) {
                        OutlinedButton(
                            onClick = onSelectPreferred,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            modifier = Modifier.height(30.dp)
                        ) {
                            Text(text = "Set Default", fontSize = 11.sp)
                        }
                    }
                }
            }

            Text(
                text = description,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 15.sp
            )
        }
    }
}

@Composable
private fun LlmTaskCard(
    title: String,
    subtitle: String,
    selectedModelName: String,
    cliBadgeText: String,
    cliBadgeColor: Color,
    cardBg: Color,
    cardBorder: BorderStroke,
    accentColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = cardBg,
        border = cardBorder,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(accentColor.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = selectedModelName,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = accentColor
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(cliBadgeColor.copy(alpha = 0.12f))
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = cliBadgeText,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = cliBadgeColor
                        )
                    }
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

// ==========================================
// SUB-SCREEN 1: APPEARANCE & THEME
// ==========================================
@Composable
private fun AppearanceSubScreen(
    themeMode: String,
    chatFontScale: Float,
    groupChatsByWorkspace: Boolean,
    isFloatingSwitcherEnabled: Boolean,
    floatingSwitcherOrientation: String,
    floatingSwitcherItems: List<String>,
    floatingSwitcherAutoCollapseSec: Int,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetThemeMode: (String) -> Unit,
    onSetChatFontScale: (Float) -> Unit,
    onToggleGroupChatsByWorkspace: (Boolean) -> Unit,
    onToggleFloatingSwitcher: (Boolean) -> Unit,
    onSetFloatingSwitcherOrientation: (String) -> Unit,
    onSetFloatingSwitcherItems: (List<String>) -> Unit,
    onSetFloatingSwitcherAutoCollapseSec: (Int) -> Unit,
    onResetFloatingSwitcherPosition: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Display Mode Header
        Text(
            text = "Display Mode",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        // 3 Clean Mode Options Cards: Auto, Dark, Light
        val themeModes = listOf(
            Triple("SYSTEM", "Auto (System)", Icons.Outlined.BrightnessAuto to Color(0xFF3B82F6)),
            Triple("DARK", "Dark Mode", Icons.Outlined.DarkMode to ClaudeTerracotta),
            Triple("LIGHT", "Light Mode", Icons.Outlined.LightMode to Color(0xFFF59E0B))
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            themeModes.forEach { (mode, label, iconAndColor) ->
                val (icon, accentColor) = iconAndColor
                val isSelected = themeMode.equals(mode, ignoreCase = true)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSetThemeMode(mode) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (isSelected) accentColor.copy(alpha = 0.14f) else cardBg,
                    border = BorderStroke(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) accentColor.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surface)
                                .border(1.dp, accentColor, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = label,
                                tint = accentColor,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) accentColor else MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }
                }
            }
        }

        ThemeGallery(cardBg = cardBg, themeMode = themeMode, onSetThemeMode = onSetThemeMode)

        Spacer(modifier = Modifier.height(6.dp))

        // Font Scaling Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Chat Text Scaling (${(chatFontScale * 100).toInt()}%)",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Scales messages, markdown, and code blocks",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (chatFontScale != 1.0f) {
                TextButton(
                    onClick = { onSetChatFontScale(1.0f) },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("Reset (1.0x)", fontSize = 12.sp, color = ClaudeTerracotta)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("A", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Spacer(modifier = Modifier.width(10.dp))
                    Slider(
                        value = chatFontScale,
                        onValueChange = onSetChatFontScale,
                        valueRange = 0.75f..1.60f,
                        steps = 16,
                        modifier = Modifier.weight(1f),
                        colors = SliderDefaults.colors(
                            thumbColor = ClaudeTerracotta,
                            activeTrackColor = ClaudeTerracotta
                        )
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("A", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }

                Spacer(modifier = Modifier.height(8.dp))

                val presets = listOf(0.85f to "Small", 1.00f to "Normal", 1.15f to "Medium", 1.30f to "Large", 1.50f to "Huge")
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { (scale, label) ->
                        val isSelected = kotlin.math.abs(chatFontScale - scale) < 0.04f
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSetChatFontScale(scale) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                        ) {
                            Box(
                                modifier = Modifier.padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) ClaudeTerracottaOnPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Chat Message Preview Box (Directly under Chat Text Scaling)
        Text(
            text = "Live Preview",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Simulated User message
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = ClaudeTerracotta.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = "How does AntiBot connect to AGY Hub?",
                            fontSize = (13 * chatFontScale).sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                        )
                    }
                }

                // Simulated Assistant reply
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "AntiBot connects via gRPC-Web to stream live agent steps and tool executions.",
                                fontSize = (13 * chatFontScale).sp,
                                lineHeight = (18 * chatFontScale).sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }

        // Group Chats By Workspace Switch
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Group Chats by Workspace",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Categorize chat history in drawer under workspace folders matching repository paths",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = groupChatsByWorkspace,
                    onCheckedChange = onToggleGroupChatsByWorkspace,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ClaudeTerracotta
                    )
                )
            }
        }

        // Working animation switch
        val appearanceContext = LocalContext.current
        val appearancePrefs = remember { AuthPreferences(appearanceContext.applicationContext) }
        val workingAnimation by appearancePrefs.workingAnimationEnabled.collectAsState(initial = true)
        val appearanceScope = rememberCoroutineScope()
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Working Animation",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Animated star and status word under the reply for as long as the agent works. Off: only the \"…\" indicator before the first output.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = workingAnimation,
                    onCheckedChange = { enabled -> appearanceScope.launch { appearancePrefs.saveWorkingAnimationEnabled(enabled) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ClaudeTerracotta
                    )
                )
            }
        }

        // Date & model dividers switch
        val chatDividers by appearancePrefs.chatDividersEnabled.collectAsState(initial = true)
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = "Date & Model Dividers",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "A wavy-line divider in chats when a new day starts (e.g. \"Sat, Oct 10\") and when the model changes (\"Switched to …\").",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
                Switch(
                    checked = chatDividers,
                    onCheckedChange = { enabled -> appearanceScope.launch { appearancePrefs.saveChatDividersEnabled(enabled) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = ClaudeTerracotta
                    )
                )
            }
        }

        // Floating App Switcher Card
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Floating App Switcher",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "A glassy floating pill across the whole app for instant switching between Chat, IDE, Terminal, and Browser",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isFloatingSwitcherEnabled,
                        onCheckedChange = onToggleFloatingSwitcher,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                if (isFloatingSwitcherEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Spacer(modifier = Modifier.height(14.dp))

                    // Layout Orientation Selector
                    Text(
                        text = "Layout Orientation",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val isVert = floatingSwitcherOrientation.equals("VERTICAL", ignoreCase = true)
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSetFloatingSwitcherOrientation("VERTICAL") },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isVert) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Outlined.ExpandLess, contentDescription = null, tint = if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Vertical", fontSize = 12.sp, fontWeight = if (isVert) FontWeight.Bold else FontWeight.Normal, color = if (isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                            }
                        }

                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { onSetFloatingSwitcherOrientation("HORIZONTAL") },
                            shape = RoundedCornerShape(10.dp),
                            color = if (!isVert) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 8.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Horizontal", fontSize = 12.sp, fontWeight = if (!isVert) FontWeight.Bold else FontWeight.Normal, color = if (!isVert) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Auto-Collapse Timeout Selector
                    Text(
                        text = "Auto-Collapse Timeout",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Automatically collapses into a small arrow handle after inactivity",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    val timeouts = listOf(0 to "Never", 3 to "3s", 5 to "5s", 10 to "10s", 15 to "15s")
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        timeouts.forEach { (sec, label) ->
                            val isSelected = floatingSwitcherAutoCollapseSec == sec
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSetFloatingSwitcherAutoCollapseSec(sec) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                                border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f))
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) ClaudeTerracottaOnPrimary else MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Customize Items Order & Visibility
                    Text(
                        text = "Customize Icons & Order",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    val allAvailable = listOf("chat", "ide", "terminal", "browser", "notes")
                    val currentItems = floatingSwitcherItems.toMutableList()
                    val displayedList = currentItems + allAvailable.filterNot { currentItems.contains(it) }

                    displayedList.forEach { itemId ->
                        val isEnabled = currentItems.contains(itemId)
                        val itemLabel = when (itemId) {
                            "chat" -> "Chat"
                            "ide" -> "IDE (Code Editor)"
                            "terminal" -> "Terminal"
                            "browser" -> "Web Browser"
                            "notes" -> "Notes & To-dos"
                            else -> itemId
                        }
                        val itemIcon = when (itemId) {
                            "chat" -> Icons.Outlined.ChatBubbleOutline
                            "ide" -> Icons.Outlined.Code
                            "terminal" -> Icons.Outlined.Terminal
                            "browser" -> Icons.Outlined.Language
                            "notes" -> Icons.Outlined.EditNote
                            else -> Icons.Outlined.ChatBubbleOutline
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 3.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                            border = BorderStroke(0.8.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = isEnabled,
                                        onCheckedChange = { checked ->
                                            val updated = if (checked) {
                                                currentItems + itemId
                                            } else {
                                                if (currentItems.size > 1) currentItems.filter { it != itemId } else currentItems
                                            }
                                            onSetFloatingSwitcherItems(updated)
                                        },
                                        colors = CheckboxDefaults.colors(checkedColor = ClaudeTerracotta),
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(itemIcon, contentDescription = null, tint = if (isEnabled) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f), modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = itemLabel,
                                        fontSize = 12.sp,
                                        color = if (isEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                                    )
                                }

                                if (isEnabled) {
                                    val itemOrderIndex = currentItems.indexOf(itemId)
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                if (itemOrderIndex > 0) {
                                                    val copy = currentItems.toMutableList()
                                                    val temp = copy[itemOrderIndex]
                                                    copy[itemOrderIndex] = copy[itemOrderIndex - 1]
                                                    copy[itemOrderIndex - 1] = temp
                                                    onSetFloatingSwitcherItems(copy)
                                                }
                                            },
                                            enabled = itemOrderIndex > 0,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Outlined.ExpandLess, contentDescription = "Move Up", modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                if (itemOrderIndex < currentItems.size - 1) {
                                                    val copy = currentItems.toMutableList()
                                                    val temp = copy[itemOrderIndex]
                                                    copy[itemOrderIndex] = copy[itemOrderIndex + 1]
                                                    copy[itemOrderIndex + 1] = temp
                                                    onSetFloatingSwitcherItems(copy)
                                                }
                                            },
                                            enabled = itemOrderIndex < currentItems.size - 1,
                                            modifier = Modifier.size(26.dp)
                                        ) {
                                            Icon(Icons.Outlined.ExpandMore, contentDescription = "Move Down", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Reset Position Button
                    OutlinedButton(
                        onClick = onResetFloatingSwitcherPosition,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeTerracotta)
                    ) {
                        Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Reset Screen Position to Right-Center", fontSize = 12.sp)
                    }
                }
            }
        }

        // Standalone Terminal Launcher Icon & Style Settings (PackageManager as source of truth)
        val context = LocalContext.current
        val authPrefs = remember(context) { com.example.gemini.data.preferences.AuthPreferences(context) }
        var isTerminalLauncherEnabled by remember(context) {
            mutableStateOf(com.example.gemini.data.preferences.TerminalLauncherManager.isLauncherEnabled(context))
        }
        var terminalLauncherStyle by remember(context) {
            mutableStateOf(com.example.gemini.data.preferences.TerminalLauncherManager.getLauncherStyle(context))
        }
        val coroutineScope = rememberCoroutineScope()

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = cardBg,
            border = cardBorder,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Separate Terminal App Icon",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Show a dedicated terminal icon on your Android home screen and app drawer",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = isTerminalLauncherEnabled,
                        onCheckedChange = { enable ->
                            isTerminalLauncherEnabled = enable
                            com.example.gemini.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                context = context,
                                enabled = enable,
                                style = terminalLauncherStyle
                            )
                            coroutineScope.launch {
                                authPrefs.setTerminalLauncherEnabled(enable)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                AnimatedVisibility(
                    visible = isTerminalLauncherEnabled,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp)
                    ) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f)
                        )

                        Text(
                            text = "Launcher Icon & Name Style",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        // Option 1: AntiTerm
                        val isAntiTerm = terminalLauncherStyle == com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isAntiTerm) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(
                                1.dp,
                                if (isAntiTerm) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    terminalLauncherStyle = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                    com.example.gemini.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                        context = context,
                                        enabled = true,
                                        style = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                    )
                                    coroutineScope.launch {
                                        authPrefs.setTerminalLauncherStyle(com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.White)
                                        .padding(3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(id = com.example.gemini.R.drawable.ic_antibot_logo),
                                        contentDescription = "AntiBot",
                                        modifier = Modifier.size(26.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "AntiTerm",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Modern AntiBot styled icon & name",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                RadioButton(
                                    selected = isAntiTerm,
                                    onClick = {
                                        terminalLauncherStyle = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                        com.example.gemini.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                            context = context,
                                            enabled = true,
                                            style = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM
                                        )
                                        coroutineScope.launch {
                                            authPrefs.setTerminalLauncherStyle(com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_ANTITERM)
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Option 2: Termux Classic
                        val isTermux = terminalLauncherStyle == com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (isTermux) ClaudeTerracotta.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            border = BorderStroke(
                                1.dp,
                                if (isTermux) ClaudeTerracotta else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    terminalLauncherStyle = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                    com.example.gemini.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                        context = context,
                                        enabled = true,
                                        style = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                    )
                                    coroutineScope.launch {
                                        authPrefs.setTerminalLauncherStyle(com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX)
                                    }
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Color.Black)
                                        .padding(3.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    androidx.compose.foundation.Image(
                                        painter = androidx.compose.ui.res.painterResource(id = com.example.gemini.R.drawable.ic_termux_foreground),
                                        contentDescription = "Termux",
                                        modifier = Modifier.size(24.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(10.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Termux",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "Classic >_ prompt icon & name",
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }

                                RadioButton(
                                    selected = isTermux,
                                    onClick = {
                                        terminalLauncherStyle = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                        com.example.gemini.data.preferences.TerminalLauncherManager.applyLauncherSetting(
                                            context = context,
                                            enabled = true,
                                            style = com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX
                                        )
                                        coroutineScope.launch {
                                            authPrefs.setTerminalLauncherStyle(com.example.gemini.data.preferences.TerminalLauncherManager.STYLE_TERMUX)
                                        }
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN 2: SERVERS & CONNECTIVITY
// ==========================================
@Composable
private fun LegacyServersSubScreen(
    currentHubUrl: String,
    currentBridgeUrl: String,
    isServerOnline: Boolean,
    isBridgeOnline: Boolean = false,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSaveServerUrls: (String, String) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()

    // Helper to split "http://host:port" into host and port
    fun parseHostPort(url: String, fallbackUrl: String): Pair<String, String> {
        val target = url.ifBlank { fallbackUrl }
        val clean = target.replace("http://", "").replace("https://", "").trimEnd('/')
        val parts = clean.split(":")
        val host = parts.getOrNull(0)?.ifBlank { "127.0.0.1" } ?: "127.0.0.1"
        val port = parts.getOrNull(1) ?: ""
        return host to port
    }

    val context = LocalContext.current
    val authPrefs = remember(context) { AuthPreferences(context) }
    val initSecurityToken = remember { authPrefs.getSecurityTokenSync() }
    var securityToken by remember { mutableStateOf(initSecurityToken) }

    val (initHubHost, initHubPort) = remember { parseHostPort(currentHubUrl, AuthPreferences.DEFAULT_HUB_URL) }
    val (initBridgeHost, initBridgePort) = remember { parseHostPort(currentBridgeUrl, AuthPreferences.DEFAULT_BRIDGE_HTTP_URL) }

    val initialSharedHost = remember { initBridgeHost.ifBlank { initHubHost.ifBlank { "127.0.0.1" } } }
    var sharedHost by remember { mutableStateOf(initialSharedHost) }
    var hubPort by remember { mutableStateOf(initHubPort.ifBlank { com.example.gemini.BuildConfig.HUB_PORT.toString() }) }
    var bridgePort by remember { mutableStateOf(initBridgePort.ifBlank { com.example.gemini.BuildConfig.BRIDGE_PORT.toString() }) }

    val initialBridgeBinPath = remember { authPrefs.getAgyBridgeBinaryPathSync() }
    val initialAgyBinPath = remember { authPrefs.getAgyBinaryPathSync() }
    var bridgeBinPath by remember { mutableStateOf(initialBridgeBinPath) }
    var agyBinPath by remember { mutableStateOf(initialAgyBinPath) }

    var hubTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingHub by remember { mutableStateOf(false) }

    var bridgeTestStatus by remember { mutableStateOf<String?>(null) }
    var isTestingBridge by remember { mutableStateOf(false) }

    var saveFeedback by remember { mutableStateOf<String?>(null) }

    var showRestartDialog by remember { mutableStateOf(false) }
    var showUnsavedDialog by remember { mutableStateOf(false) }
    var showServerLogsDialog by remember { mutableStateOf(false) }
    var showDaemonLogsDialog by remember { mutableStateOf(false) }
    var showInstallerDialog by remember { mutableStateOf(false) }

    val hasUnsavedChanges = remember(sharedHost, hubPort, bridgePort, bridgeBinPath, agyBinPath, securityToken) {
        sharedHost.trim() != initialSharedHost.trim() ||
            hubPort.trim() != initHubPort.trim() ||
            bridgePort.trim() != initBridgePort.trim() ||
            bridgeBinPath.trim() != initialBridgeBinPath.trim() ||
            agyBinPath.trim() != initialAgyBinPath.trim() ||
            securityToken.trim() != initSecurityToken.trim()
    }

    androidx.activity.compose.BackHandler(enabled = hasUnsavedChanges) {
        showUnsavedDialog = true
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = { showUnsavedDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Unsaved Server Settings",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "You have unsaved changes in Server & Connectivity settings. Please save them manually using 'Save Server & Security Settings' below or discard your modifications.",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = { showUnsavedDialog = false },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Keep Editing", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showUnsavedDialog = false
                        sharedHost = initialSharedHost
                        hubPort = initHubPort.ifBlank { com.example.gemini.BuildConfig.HUB_PORT.toString() }
                        bridgePort = initBridgePort.ifBlank { com.example.gemini.BuildConfig.BRIDGE_PORT.toString() }
                        bridgeBinPath = initialBridgeBinPath
                        agyBinPath = initialAgyBinPath
                        securityToken = initSecurityToken
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Discard Changes", color = MaterialTheme.colorScheme.error)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showRestartDialog) {
        AlertDialog(
            onDismissRequest = { showRestartDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Outlined.RestartAlt,
                    contentDescription = null,
                    tint = ClaudeTerracotta,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Reopen AntiBot?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp
                )
            },
            text = {
                Text(
                    text = "Server settings and security configuration have been saved. Reopening the app restarts local bridge and daemon processes so all connections cleanly synchronize with the new parameters.",
                    fontSize = 13.5.sp,
                    lineHeight = 19.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRestartDialog = false
                        val pm = context.packageManager
                        val intent = pm.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            val restartIntent = Intent.makeRestartActivityTask(intent.component)
                            context.startActivity(restartIntent)
                            Runtime.getRuntime().exit(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Reopen Now", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showRestartDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showServerLogsDialog) {
        com.example.gemini.ui.components.LocalServerOutputDialog(
            onDismiss = { showServerLogsDialog = false }
        )
    }

    if (showDaemonLogsDialog) {
        com.example.gemini.ui.ide.DaemonLogsDialog(
            onDismiss = { showDaemonLogsDialog = false }
        )
    }

    if (showInstallerDialog) {
        com.example.gemini.ui.settings.LocalToolsInstallDialog(
            authPreferences = authPrefs,
            onDismiss = { showInstallerDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Server Supervisor & Live Diagnostics Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.Terminal, contentDescription = null, tint = AntiBotCyan, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Server Supervisor & Diagnostics", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Live supervisor output logs, WebSocket monitor & rootfs setup", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                val isInstalled = remember { com.example.gemini.data.local.LocalEnvironmentManager.isInstalled(context) }
                val supervisorStatus by com.example.gemini.data.local.LocalServerManager.status.collectAsState()
                val daemonStatus by com.example.gemini.data.daemon.TermuxDaemonManager.status.collectAsState()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Supervisor chip
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (supervisorStatus is com.example.gemini.data.local.LocalServerStatus.Running) QuotaGreen else if (supervisorStatus is com.example.gemini.data.local.LocalServerStatus.Starting) ClaudeTerracotta else Color.Red)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Supervisor: ${if (supervisorStatus is com.example.gemini.data.local.LocalServerStatus.Running) "Running" else if (supervisorStatus is com.example.gemini.data.local.LocalServerStatus.Starting) "Starting" else "Stopped"}",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // Daemon chip
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                    ) {
                        Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (daemonStatus == com.example.gemini.data.daemon.DaemonStatus.RUNNING) QuotaGreen else Color.Red)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Socket: ${daemonStatus.name}",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Actions: Terminal Logs & Socket Monitor
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { showServerLogsDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Outlined.Terminal, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Server Logs", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    Button(
                        onClick = { showDaemonLogsDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
                    ) {
                        Icon(imageVector = Icons.Outlined.Dns, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Socket Monitor", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            com.example.gemini.data.local.LocalServerManager.startServer(context, forceRestart = true)
                            saveFeedback = "Restarting local server supervisor..."
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restart Server", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showInstallerDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (isInstalled) AntiBotCyan else ClaudeTerracotta),
                        border = BorderStroke(1.dp, if (isInstalled) AntiBotCyan.copy(alpha = 0.5f) else ClaudeTerracotta)
                    ) {
                        Icon(imageVector = Icons.Default.Download, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isInstalled) "Manage Rootfs" else "Install (350MB)", fontSize = 12.sp)
                    }
                }
            }
        }
        // Unified Server Endpoints (Shared Host & Individual Ports)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.Dns, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Server & Connectivity Endpoints", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Shared server host address with dedicated daemon & bridge ports", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = sharedHost,
                    onValueChange = { sharedHost = it; saveFeedback = null },
                    label = { Text("Server Host / IP Address (Shared)") },
                    placeholder = { Text("127.0.0.1") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = hubPort,
                        onValueChange = { hubPort = it; saveFeedback = null },
                        label = { Text("AGY Hub Port") },
                        placeholder = { Text(com.example.gemini.BuildConfig.HUB_PORT.toString()) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                    OutlinedTextField(
                        value = bridgePort,
                        onValueChange = { bridgePort = it; saveFeedback = null },
                        label = { Text("IDE Bridge Port") },
                        placeholder = { Text(com.example.gemini.BuildConfig.BRIDGE_PORT.toString()) },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        shape = RoundedCornerShape(10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Endpoints status & preview
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isServerOnline || hubTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Hub: http://${sharedHost.trim()}:${hubPort.trim()}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (hubTestStatus != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = hubTestStatus ?: "",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (hubTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (isBridgeOnline || bridgeTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Bridge: http://${sharedHost.trim()}:${bridgePort.trim()}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                if (bridgeTestStatus != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bridgeTestStatus ?: "",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (bridgeTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            isTestingHub = true
                            hubTestStatus = "Pinging http://${sharedHost.trim()}:${hubPort.trim()}..."
                            coroutineScope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    try {
                                        val u = URL("http://${sharedHost.trim()}:${hubPort.trim()}")
                                        val conn = u.openConnection() as HttpURLConnection
                                        conn.connectTimeout = 3000
                                        conn.readTimeout = 3000
                                        val code = conn.responseCode
                                        conn.disconnect()
                                        code in 200..499
                                    } catch (e: Exception) {
                                        false
                                    }
                                }
                                isTestingHub = false
                                hubTestStatus = if (ok) "✓ Hub Connected!" else "✗ Hub unreachable (${hubPort.trim()})"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isTestingHub
                    ) {
                        if (isTestingHub) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Test Hub", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            isTestingBridge = true
                            bridgeTestStatus = "Pinging http://${sharedHost.trim()}:${bridgePort.trim()}..."
                            coroutineScope.launch {
                                val base = "http://${sharedHost.trim()}:${bridgePort.trim()}"
                                val ok = com.example.gemini.data.remote.AgyBridgeService.instance.checkServerHealth(base)
                                isTestingBridge = false
                                bridgeTestStatus = if (ok) "✓ Bridge Connected!" else "✗ Bridge unreachable (${bridgePort.trim()})"
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isTestingBridge
                    ) {
                        if (isTestingBridge) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text("Test Bridge", fontSize = 12.sp)
                    }
                }
            }
        }

        // Binary Executable Paths Section (Auto-Launch & --bin arguments)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.Terminal, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Binary Executable Paths", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "Custom paths used when auto-starting IDE Bridge and AGY Hub", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = bridgeBinPath,
                    onValueChange = { bridgeBinPath = it; saveFeedback = null },
                    label = { Text("IDE Bridge Binary Path") },
                    placeholder = { Text(authPrefs.getDefaultBridgeBinaryPath()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = agyBinPath,
                    onValueChange = { agyBinPath = it; saveFeedback = null },
                    label = { Text("AGY Hub Binary Path") },
                    placeholder = { Text(authPrefs.getDefaultAgyBinaryPath()) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp)
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "• Passed to the bridge on launch (--bin for AGY) so auto-start executes the designated binary directly without interactive prompts.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    onClick = {
                        sharedHost = "127.0.0.1"
                        hubPort = com.example.gemini.BuildConfig.HUB_PORT.toString()
                        bridgePort = com.example.gemini.BuildConfig.BRIDGE_PORT.toString()
                        bridgeBinPath = authPrefs.getDefaultBridgeBinaryPath()
                        agyBinPath = authPrefs.getDefaultAgyBinaryPath()
                        saveFeedback = "Settings reset to defaults (click Save to apply)."
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(imageVector = Icons.Outlined.RestartAlt, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Reset All to Defaults", fontSize = 12.sp)
                }
            }
        }

        // Security Header & CSRF Obfuscation Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = Icons.Outlined.Security, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(22.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "Security Token (Header Obfuscation)", fontSize = 14.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        Text(text = "12-char token framing localhost requests to prevent unauthorized app access", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = securityToken,
                    onValueChange = {
                        val filtered = it.take(AuthPreferences.TOKEN_LENGTH).filter { c -> c.isLetterOrDigit() }
                        securityToken = filtered
                        saveFeedback = null
                    },
                    label = { Text("12-Character Secret Token") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    trailingIcon = {
                        Text(
                            text = "${securityToken.length}/12",
                            fontSize = 11.sp,
                            color = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                val framedHeader = AuthPreferences.buildFramedHeader(securityToken)
                Text(
                    text = "HTTP Framed Header: $framedHeader",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            securityToken = AuthPreferences.generateRandomToken()
                            saveFeedback = null
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.Shuffle, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Randomize", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                            val clip = android.content.ClipData.newPlainText("Framed Security Header", framedHeader)
                            clipboard?.setPrimaryClip(clip)
                            Toast.makeText(context, "Copied $framedHeader to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(imageVector = Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Header", fontSize = 12.sp)
                    }
                }
            }
        }

        if (saveFeedback != null) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = QuotaGreen.copy(alpha = 0.12f)
            ) {
                Text(
                    text = saveFeedback ?: "",
                    fontSize = 12.sp,
                    color = QuotaGreen,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }

        Button(
            onClick = {
                val cleanHost = sharedHost.trim().ifBlank { "127.0.0.1" }
                val cleanHubPort = hubPort.trim().ifBlank { com.example.gemini.BuildConfig.HUB_PORT.toString() }
                val cleanBridgePort = bridgePort.trim().ifBlank { com.example.gemini.BuildConfig.BRIDGE_PORT.toString() }
                val fullHub = "http://$cleanHost:$cleanHubPort"
                val fullBridge = "http://$cleanHost:$cleanBridgePort"
                onSaveServerUrls(fullHub, fullBridge)
                val tokenToSave = if (securityToken.length == AuthPreferences.TOKEN_LENGTH) securityToken else AuthPreferences.generateRandomToken()
                coroutineScope.launch {
                    authPrefs.saveSecurityToken(tokenToSave)
                    authPrefs.saveAgyBridgeBinaryPath(bridgeBinPath.trim().ifBlank { authPrefs.getDefaultBridgeBinaryPath() })
                    authPrefs.saveAgyBinaryPath(agyBinPath.trim().ifBlank { authPrefs.getDefaultAgyBinaryPath() })
                }
                saveFeedback = "✓ Server, Binaries and Security settings saved!"
                showRestartDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save Server & Security Settings", fontWeight = FontWeight.Bold)
        }
    }
}

// ==========================================
// SUB-SCREEN 3: MCP SERVERS & TOOLS SETTINGS
// ==========================================
private data class McpPreset(
    val name: String,
    val subtitle: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val accent: Color,
    val spec: com.example.gemini.domain.model.McpServerSpec?
)

/** One preset in the MCP screens' grid. */
@Composable
internal fun McpPresetTile(
    name: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    added: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    addedLabel: String = "Added · tap to edit",
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (added) accent.copy(alpha = 0.45f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)),
        modifier = modifier.clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(accent.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(19.dp))
            }
            Spacer(Modifier.width(9.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        name,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (added) {
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Filled.CheckCircle, contentDescription = "Added", tint = QuotaGreen, modifier = Modifier.size(13.dp))
                    }
                }
                Text(
                    if (added) addedLabel else subtitle,
                    fontSize = 10.5.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun McpSubScreen(
    mcpServers: List<com.example.gemini.domain.model.McpServerState>,
    isLoading: Boolean,
    isRefreshing: Boolean = false,
    refreshingServerName: String? = null,
    errorMessage: String? = null,
    statusMessage: String? = null,
    onClearStatus: () -> Unit = {},
    cardBg: Color,
    cardBorder: BorderStroke,
    onRefreshAll: () -> Unit,
    onRefreshServer: (String) -> Unit,
    onToggleServer: (String, Boolean) -> Unit,
    onSaveServer: (com.example.gemini.domain.model.McpServerSpec, String?) -> Unit,
    onDeleteServer: (String) -> Unit,
    availableCascadePlugins: List<com.example.gemini.data.remote.dto.AvailableCascadePluginDto> = emptyList(),
    isCascadePluginsLoading: Boolean = false,
    installingCascadePluginId: String? = null,
    onSearchCascadePlugins: (String) -> Unit = {},
    onInstallCascadePlugin: (com.example.gemini.data.remote.dto.AvailableCascadePluginDto) -> Unit = {}
) {
    var showDialog by remember { mutableStateOf(false) }
    var chooseWebViewExtension by remember { mutableStateOf(false) }
    var showCascadeCatalogDialog by remember { mutableStateOf(false) }
    var serverToEdit by remember { mutableStateOf<com.example.gemini.domain.model.McpServerSpec?>(null) }
    var serverToDelete by remember { mutableStateOf<String?>(null) }
    var expandedTools by remember { mutableStateOf(setOf<String>()) }
    var expandedErrors by remember { mutableStateOf(setOf<String>()) }

    // Auto-dismiss status toast after 3.5 seconds
    LaunchedEffect(statusMessage) {
        if (!statusMessage.isNullOrBlank()) {
            delay(3500)
            onClearStatus()
        }
    }

    LaunchedEffect(Unit) {
        if (mcpServers.isEmpty()) {
            onRefreshAll()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp)
                .padding(bottom = 68.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Error banner when servers are already loaded in the list
            if (!errorMessage.isNullOrBlank() && mcpServers.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = errorMessage,
                            fontSize = 12.5.sp,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onClearStatus,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Dismiss",
                                tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

        // Banner card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ClaudeTerracotta.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Model Context Protocol (MCP)",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Global config: ~/.gemini/config/mcp_config.json",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Text(
                    text = "Configure local process (Stdio) or remote (SSE) MCP servers. Discovered tools are dynamically available to all models during chat turns.",
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                // Top action row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val isRefreshingAll = isRefreshing && refreshingServerName == null
                    OutlinedButton(
                        onClick = onRefreshAll,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        enabled = !isRefreshing && !isLoading,
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                    ) {
                        if (isRefreshingAll) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = ClaudeTerracotta
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isRefreshingAll) "Testing All..." else "Test & Refresh", fontSize = 13.sp)
                    }

                    Button(
                        onClick = {
                            serverToEdit = null
                            showDialog = true
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                        contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Server", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // Browse Cascade MCP Marketplace button
                OutlinedButton(
                    onClick = { showCascadeCatalogDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeTerracotta),
                    border = BorderStroke(1.dp, ClaudeTerracotta.copy(alpha = 0.5f)),
                    contentPadding = PaddingValues(vertical = 10.dp, horizontal = 12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Storefront,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = ClaudeTerracotta
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Browse MCP Catalog / Marketplace (1-Click)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ClaudeTerracotta)
                }
            }
        }

        // Quick presets: a two-column grid of tiles, all visible without scrolling sideways
        Text(
            text = "Quick Presets & Templates",
            fontSize = 12.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        run {
            val context = LocalContext.current
            val deviceId = remember(context) {
                try {
                    android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID) ?: ""
                } catch (_: Exception) { "" }
            }
            val extensionManager = remember { com.example.gemini.ui.browser.BrowserExtensionManager.instance }
            if (chooseWebViewExtension) WebViewExtensionMcpPicker(onDismiss = { chooseWebViewExtension = false }) { extension ->
                serverToEdit = com.example.gemini.domain.model.McpServerSpec(
                    serverName = extensionManager.mcpServerName(extension.id),
                    serverUrl = extensionManager.mcpUrl(extension.id),
                    headers = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
                )
                chooseWebViewExtension = false
                showDialog = true
            }
            val bridge = com.example.gemini.data.remote.AndroidLocalBridgeServer
            val local = "http://127.0.0.1:${bridge.DEFAULT_PORT}"
            val deviceHeaders = if (deviceId.isNotBlank()) mapOf("X-Device-Id" to deviceId) else emptyMap()
            fun localSpec(name: String, path: String) =
                com.example.gemini.domain.model.McpServerSpec(serverName = name, serverUrl = "$local$path", headers = deviceHeaders)
            // Already added (the plugin developer also under its old name): edit that one instead of adding a duplicate
            fun existing(spec: com.example.gemini.domain.model.McpServerSpec) = mcpServers.firstOrNull {
                it.name == spec.serverName || (bridge.isDeveloperMcpName(spec.serverName) && bridge.isDeveloperMcpName(it.name))
            }
            val presets = listOf(
                // No spec: opens the picker for an installed plugin's own MCP
                McpPreset("Custom Plugin MCP", "Pick an installed plugin", Icons.Outlined.Extension, ClaudeTerracotta, null),
                McpPreset("Plugin Developer", "Build & test AntiBot plugins", Icons.Outlined.DeveloperMode, GeminiPurple,
                    localSpec(bridge.DEVELOPER_MCP_NAME, "/extension_developer")),
                McpPreset("Browser", "Control the in-app browser", Icons.Outlined.Language, GeminiBlue, localSpec("browser_mcp", "/browser")),
                McpPreset("Terminal", "Run commands in the terminal", Icons.Outlined.Terminal, QuotaGreen, localSpec("terminal_mcp", "/terminal")),
                McpPreset("Android", "Tap, type & read this phone", Icons.Outlined.PhoneAndroid, Color(0xFF14B8A6),
                    localSpec(bridge.ANDROID_AUTOMATION_MCP_NAME, "/android")),
                McpPreset("Notes & To-dos", "Read & edit your notes", Icons.Outlined.EditNote, Color(0xFFF59E0B), localSpec(bridge.NOTES_MCP_NAME, "/notes")),
                McpPreset("Jarvis Voice", "Hands-free voice assistant tools", Icons.Outlined.RecordVoiceOver, Color(0xFF6366F1), localSpec(bridge.JARVIS_MCP_NAME, "/jarvis")),
                McpPreset("Agentic Skills", "2,955+ skills for any AI or project", Icons.Outlined.Psychology, Color(0xFF8B5CF6), localSpec(bridge.SKILLS_MCP_NAME, "/skills")),
                McpPreset("Stitch", "Google Stitch UI design · API key", Icons.Outlined.Palette, Color(0xFFEC4899),
                    com.example.gemini.domain.model.McpServerSpec(serverName = "stitch", serverUrl = "https://stitch.googleapis.com/mcp", headers = mapOf("X-Goog-Api-Key" to "")))
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                presets.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { p ->
                            val found = p.spec?.let { existing(it) }
                            McpPresetTile(p.name, p.subtitle, p.icon, p.accent, added = found != null, modifier = Modifier.weight(1f).fillMaxHeight()) {
                                if (p.spec == null) {
                                    chooseWebViewExtension = true
                                } else {
                                    serverToEdit = found?.spec ?: p.spec
                                    showDialog = true
                                }
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // Server list header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Configured Servers (${mcpServers.size})",
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (mcpServers.isNotEmpty()) {
                val totalTools = mcpServers.sumOf { it.tools.size }
                Text(
                    text = "$totalTools tools available",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }

        // Empty / Error / Loading state
        if (mcpServers.isEmpty()) {
            if (isLoading) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = cardBorder
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(28.dp),
                            strokeWidth = 2.5.dp,
                            color = ClaudeTerracotta
                        )
                        Text(
                            text = "Loading MCP Servers...",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            } else if (!errorMessage.isNullOrBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.08f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(42.dp)
                        )
                        Text(
                            text = "Unable to Load MCP Servers",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = errorMessage,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "Could not retrieve MCP server states. Ensure the Antigravity daemon is running on this device or accessible at the configured Hub URL.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        OutlinedButton(
                            onClick = onRefreshAll,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = ClaudeTerracotta),
                            border = BorderStroke(1.dp, ClaudeTerracotta)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Retry Connection", fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = cardBg),
                    border = cardBorder
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Extension,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                            modifier = Modifier.size(44.dp)
                        )
                        Text(
                            text = "No MCP Servers Configured",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Add an MCP server to connect SQLite, filesystem, browser automation, or custom local tools to the assistant.",
                            fontSize = 12.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Server Cards
        mcpServers.forEach { server ->
            val isExpanded = expandedTools.contains(server.name)
            val isErrorExpanded = expandedErrors.contains(server.name)
            val isSse = server.spec?.serverUrl?.isNotBlank() == true

            val statusColor = when {
                !server.isEnabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                server.status.contains("RUNNING", ignoreCase = true) || server.status.contains("OK", ignoreCase = true) || server.tools.isNotEmpty() -> QuotaGreen
                server.status.contains("ERROR", ignoreCase = true) || !server.error.isNullOrBlank() -> Color.Red
                else -> Color(0xFFFFA000)
            }
            val statusLabel = when {
                !server.isEnabled -> "Disabled"
                server.status.contains("RUNNING", ignoreCase = true) || server.status.contains("OK", ignoreCase = true) || server.tools.isNotEmpty() -> "Ready"
                server.status.contains("ERROR", ignoreCase = true) || !server.error.isNullOrBlank() -> "Error"
                else -> server.status.removePrefix("MCP_SERVER_STATUS_").lowercase().replaceFirstChar { it.uppercase() }
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = cardBg),
                border = cardBorder
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Title and toggle row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSse) Color(0xFF1976D2).copy(alpha = 0.12f) else Color(0xFF7B1FA2).copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Extension,
                                contentDescription = null,
                                tint = if (isSse) Color(0xFF1976D2) else Color(0xFF7B1FA2),
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = server.name,
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Transport badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isSse) Color(0xFF1976D2).copy(alpha = 0.15f) else Color(0xFF7B1FA2).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isSse) "SSE" else "Stdio",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSse) Color(0xFF1976D2) else Color(0xFF7B1FA2),
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }

                                // Status badge
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = statusColor.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = statusLabel,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }

                        Switch(
                            checked = server.isEnabled,
                            onCheckedChange = { onToggleServer(server.name, it) },
                            colors = SwitchDefaults.colors(checkedThumbColor = ClaudeTerracotta, checkedTrackColor = ClaudeTerracotta.copy(alpha = 0.4f))
                        )
                    }

                    // Command or URL info
                    val cmdText = if (isSse) {
                        server.spec?.serverUrl ?: ""
                    } else {
                        val baseCmd = server.spec?.command ?: ""
                        val argsJoined = server.spec?.args?.joinToString(" ") ?: ""
                        "$baseCmd $argsJoined".trim()
                    }
                    if (cmdText.isNotBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        ) {
                            Text(
                                text = if (isSse) "URL: $cmdText" else "> $cmdText",
                                fontSize = 11.5.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    if (isSse && !server.spec?.headers.isNullOrEmpty()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFF1976D2).copy(alpha = 0.08f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Key,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = Color(0xFF1976D2)
                                )
                                Text(
                                    text = "Headers: ${server.spec!!.headers.keys.joinToString(", ")}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color(0xFF1976D2)
                                )
                            }
                        }
                    } else if (!isSse && !server.spec?.cwd.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                                Text(
                                    text = "cwd: ${server.spec!!.cwd}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                        }
                    }

                    // Error banner
                    if (!server.error.isNullOrBlank()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    expandedErrors = if (isErrorExpanded) expandedErrors - server.name else expandedErrors + server.name
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = Color.Red.copy(alpha = 0.1f),
                            border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Icon(imageVector = Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.size(14.dp))
                                        Text("Connection Issue", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Red)
                                    }
                                    Icon(
                                        imageVector = if (isErrorExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = Color.Red,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Text(
                                    text = server.error,
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = Color.Red,
                                    maxLines = if (isErrorExpanded) 10 else 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }

                    // Discovered tools accordion
                    if (server.tools.isNotEmpty()) {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    expandedTools = if (isExpanded) expandedTools - server.name else expandedTools + server.name
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Icon(imageVector = Icons.Default.Build, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(14.dp))
                                        Text(
                                            text = "${server.tools.size} Discovered Tool${if (server.tools.size > 1) "s" else ""}",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                    Icon(
                                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                if (isExpanded) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                    server.tools.forEach { tool ->
                                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                            Text(
                                                text = tool.name,
                                                fontSize = 12.sp,
                                                fontFamily = FontFamily.Monospace,
                                                fontWeight = FontWeight.Bold,
                                                color = ClaudeTerracotta
                                            )
                                            if (tool.description.isNotBlank()) {
                                                Text(
                                                    text = tool.description,
                                                    fontSize = 11.5.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                                    modifier = Modifier.padding(top = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Card Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isThisRefreshing = isRefreshing && (refreshingServerName == server.name)
                        TextButton(
                            onClick = { onRefreshServer(server.name) },
                            enabled = !isRefreshing && !isLoading,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            if (isThisRefreshing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 1.5.dp,
                                    color = ClaudeTerracotta
                                )
                            } else {
                                Icon(imageVector = Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(if (isThisRefreshing) "Testing..." else "Refresh", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(
                            onClick = {
                                serverToEdit = server.spec ?: com.example.gemini.domain.model.McpServerSpec(serverName = server.name)
                                showDialog = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Edit", fontSize = 12.sp)
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        TextButton(
                            onClick = { serverToDelete = server.name },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Remove", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }

    // Floating Success Toast (Zero Layout Shift)
    AnimatedVisibility(
        visible = !statusMessage.isNullOrBlank(),
        enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp),
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.4f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = QuotaGreen,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = statusMessage.orEmpty(),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                IconButton(
                    onClick = onClearStatus,
                    modifier = Modifier.size(22.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

    // Delete confirmation dialog
    if (serverToDelete != null) {
        val targetName = serverToDelete ?: ""
        AlertDialog(
            onDismissRequest = { serverToDelete = null },
            title = { Text("Remove MCP Server?") },
            text = {
                Text("Are you sure you want to remove \"$targetName\" from global mcp_config.json? The server and its tools will be disconnected.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        onDeleteServer(targetName)
                        serverToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) {
                    Text("Remove", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { serverToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Add / Edit Dialog
    if (showDialog) {
        AddEditMcpServerDialog(
            initialSpec = serverToEdit,
            onDismiss = {
                showDialog = false
                serverToEdit = null
            },
            onSave = { spec, rawJson ->
                onSaveServer(spec, rawJson)
                showDialog = false
                serverToEdit = null
            }
        )
    }

    // Cascade Marketplace Dialog
    if (showCascadeCatalogDialog) {
        CascadeMcpCatalogDialog(
            availablePlugins = availableCascadePlugins,
            isLoading = isCascadePluginsLoading,
            installingPluginId = installingCascadePluginId,
            existingServers = mcpServers,
            onSearch = onSearchCascadePlugins,
            onInstallPlugin = onInstallCascadePlugin,
            onDismiss = { showCascadeCatalogDialog = false }
        )
    }
}

private enum class McpEditMode {
    STDIO,
    SSE,
    RAW
}

@Composable
private fun AddEditMcpServerDialog(
    initialSpec: com.example.gemini.domain.model.McpServerSpec?,
    onDismiss: () -> Unit,
    onSave: (com.example.gemini.domain.model.McpServerSpec, String?) -> Unit
) {
    var mode by remember {
        mutableStateOf(
            if (initialSpec?.serverUrl?.isNotBlank() == true) McpEditMode.SSE
            else McpEditMode.STDIO
        )
    }
    var name by remember { mutableStateOf(initialSpec?.serverName ?: "") }
    var command by remember { mutableStateOf(initialSpec?.command ?: "") }
    var argsText by remember { mutableStateOf(initialSpec?.args?.joinToString(" ") ?: "") }
    var cwdText by remember { mutableStateOf(initialSpec?.cwd ?: "") }
    var serverUrl by remember { mutableStateOf(initialSpec?.serverUrl ?: "") }
    var headersText by remember { mutableStateOf(initialSpec?.headers?.map { "${it.key}: ${it.value}" }?.joinToString("\n") ?: "") }
    var envText by remember { mutableStateOf(initialSpec?.env?.map { "${it.key}=${it.value}" }?.joinToString("\n") ?: "") }
    var validationError by remember { mutableStateOf<String?>(null) }

    fun buildJsonFromFields(): String {
        val obj = org.json.JSONObject()
        if (mode == McpEditMode.SSE || serverUrl.isNotBlank()) {
            obj.put("serverUrl", serverUrl.trim())
            val headersMap = mutableMapOf<String, String>()
            headersText.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotBlank()) {
                    val delimiter = if (trimmed.contains(':')) ':' else if (trimmed.contains('=')) '=' else null
                    if (delimiter != null) {
                        val parts = trimmed.split(delimiter, limit = 2)
                        val k = parts[0].trim()
                        val v = parts[1].trim()
                        if (k.isNotBlank()) headersMap[k] = v
                    }
                }
            }
            if (headersMap.isNotEmpty()) {
                val hObj = org.json.JSONObject()
                headersMap.forEach { (k, v) -> hObj.put(k, v) }
                obj.put("headers", hObj)
            }
        } else {
            obj.put("command", command.trim())
            val argsList = mutableListOf<String>()
            val regex = """[^\s"']+|"([^"]*)"|'([^']*)'""".toRegex()
            regex.findAll(argsText.trim()).forEach { m ->
                val arg = m.groups[1]?.value ?: m.groups[2]?.value ?: m.value
                if (arg.isNotBlank()) argsList.add(arg)
            }
            if (argsList.isNotEmpty()) {
                val arr = org.json.JSONArray()
                argsList.forEach { arr.put(it) }
                obj.put("args", arr)
            }
            if (cwdText.trim().isNotBlank()) {
                obj.put("cwd", cwdText.trim())
            }
            val envMap = mutableMapOf<String, String>()
            envText.lines().forEach { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                    envMap[parts[0].trim()] = parts[1].trim()
                }
            }
            if (envMap.isNotEmpty()) {
                val envObj = org.json.JSONObject()
                envMap.forEach { (k, v) -> envObj.put(k, v) }
                obj.put("env", envObj)
            }
        }
        if (initialSpec?.disabled == true) {
            obj.put("disabled", true)
        }
        return obj.toString(2)
    }

    fun parseFieldsFromJson(rawJson: String) {
        try {
            val obj = org.json.JSONObject(rawJson)
            val sUrl = obj.optString("serverUrl", "")
            if (sUrl.isNotBlank()) {
                serverUrl = sUrl
                val hObj = obj.optJSONObject("headers")
                if (hObj != null) {
                    val lines = mutableListOf<String>()
                    val keys = hObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        lines.add("$k: ${hObj.optString(k)}")
                    }
                    headersText = lines.joinToString("\n")
                }
            } else {
                val cmd = obj.optString("command", "")
                if (cmd.isNotBlank()) command = cmd
                val argsArr = obj.optJSONArray("args")
                if (argsArr != null) {
                    val list = mutableListOf<String>()
                    for (i in 0 until argsArr.length()) {
                        list.add(argsArr.optString(i))
                    }
                    argsText = list.joinToString(" ")
                }
                val cwd = obj.optString("cwd", "")
                if (cwd.isNotBlank()) cwdText = cwd
                val envObj = obj.optJSONObject("env")
                if (envObj != null) {
                    val lines = mutableListOf<String>()
                    val keys = envObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        lines.add("$k=${envObj.optString(k)}")
                    }
                    envText = lines.joinToString("\n")
                }
            }
        } catch (_: Exception) {}
    }

    var rawJsonText by remember {
        mutableStateOf(
            if (initialSpec != null) {
                val obj = org.json.JSONObject()
                if (initialSpec.serverUrl.isNotBlank()) {
                    obj.put("serverUrl", initialSpec.serverUrl)
                    if (initialSpec.headers.isNotEmpty()) {
                        val h = org.json.JSONObject()
                        initialSpec.headers.forEach { (k, v) -> h.put(k, v) }
                        obj.put("headers", h)
                    }
                } else {
                    obj.put("command", initialSpec.command)
                    if (initialSpec.args.isNotEmpty()) {
                        val arr = org.json.JSONArray()
                        initialSpec.args.forEach { arr.put(it) }
                        obj.put("args", arr)
                    }
                    if (initialSpec.cwd.isNotBlank()) obj.put("cwd", initialSpec.cwd)
                    if (initialSpec.env.isNotEmpty()) {
                        val envObj = org.json.JSONObject()
                        initialSpec.env.forEach { (k, v) -> envObj.put(k, v) }
                        obj.put("env", envObj)
                    }
                }
                if (initialSpec.disabled) obj.put("disabled", true)
                obj.toString(2)
            } else {
                """{
  "command": "npx",
  "args": [
    "-y",
    "@modelcontextprotocol/server-filesystem",
    "."
  ]
}"""
            }
        )
    }

    LaunchedEffect(initialSpec) {
        if (initialSpec != null && initialSpec.serverName.isNotBlank()) {
            try {
                val configRaw = com.example.gemini.data.daemon.IdeApiClient.getMcpConfig()
                if (!configRaw.isNullOrBlank()) {
                    val cfgJson = org.json.JSONObject(configRaw)
                    val sObj = cfgJson.optJSONObject("mcpServers")?.optJSONObject(initialSpec.serverName)
                    if (sObj != null) {
                        rawJsonText = sObj.toString(2)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialSpec != null && initialSpec.serverName.isNotBlank()) "Edit MCP Server" else "Add MCP Server",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Mode Selector: Stdio | SSE | Raw
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        McpEditMode.STDIO to "Stdio",
                        McpEditMode.SSE to "SSE",
                        McpEditMode.RAW to "Raw"
                    ).forEach { (m, label) ->
                        val isSelected = mode == m
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    if (mode != m) {
                                        if (m == McpEditMode.RAW) {
                                            rawJsonText = buildJsonFromFields()
                                        } else if (mode == McpEditMode.RAW) {
                                            parseFieldsFromJson(rawJsonText)
                                        }
                                        mode = m
                                        validationError = null
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(1.dp, if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(
                                    text = label,
                                    fontSize = 12.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) ClaudeTerracottaOnPrimary else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                // Server Name
                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        validationError = null
                    },
                    label = { Text("Server Name *") },
                    placeholder = { Text("e.g. stitch, filesystem, github") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (mode == McpEditMode.RAW) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Server Configuration JSON",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            TextButton(
                                onClick = {
                                    try {
                                        val parsed = org.json.JSONObject(rawJsonText)
                                        rawJsonText = parsed.toString(2)
                                        validationError = null
                                    } catch (e: Exception) {
                                        validationError = "Invalid JSON: ${e.message}"
                                    }
                                },
                                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text("Format", fontSize = 11.sp, color = ClaudeTerracotta)
                            }
                        }

                        OutlinedTextField(
                            value = rawJsonText,
                            onValueChange = {
                                rawJsonText = it
                                validationError = null
                            },
                            placeholder = {
                                Text("{\n  \"command\": \"npx\",\n  \"args\": [...]\n}")
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 16.sp
                            ),
                            minLines = 8,
                            maxLines = 14,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (mode == McpEditMode.STDIO) {
                    // Command
                    OutlinedTextField(
                        value = command,
                        onValueChange = {
                            command = it
                            validationError = null
                        },
                        label = { Text("Command *") },
                        placeholder = { Text("e.g. npx, python3, node, uvx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Arguments
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (space separated)") },
                        placeholder = { Text("e.g. -y @modelcontextprotocol/server-filesystem .") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Working Directory
                    OutlinedTextField(
                        value = cwdText,
                        onValueChange = { cwdText = it },
                        label = { Text("Working Directory (optional)") },
                        placeholder = { Text("e.g. ~/projects/my-app") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Environment Variables
                    OutlinedTextField(
                        value = envText,
                        onValueChange = { envText = it },
                        label = { Text("Environment Variables (KEY=VALUE per line)") },
                        placeholder = { Text("API_KEY=xyz\nDEBUG=true") },
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Server URL
                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = {
                            serverUrl = it
                            validationError = null
                        },
                        label = { Text("SSE Endpoint URL *") },
                        placeholder = { Text("e.g. https://stitch.googleapis.com/mcp or http://127.0.0.1:8000/sse") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // HTTP Headers
                    OutlinedTextField(
                        value = headersText,
                        onValueChange = { headersText = it },
                        label = { Text("HTTP Headers (Header: Value per line)") },
                        placeholder = { Text("X-Goog-Api-Key: AQ.Ab8RN6...\nAuthorization: Bearer token") },
                        minLines = 3,
                        maxLines = 6,
                        supportingText = {
                            Text(
                                text = "Set custom headers like API keys or tokens required by the remote endpoint.",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (validationError != null) {
                    Text(
                        text = validationError ?: "",
                        fontSize = 12.sp,
                        color = Color.Red,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanName = name.trim()
                    if (cleanName.isBlank()) {
                        validationError = "Server name is required"
                        return@Button
                    }

                    if (mode == McpEditMode.RAW) {
                        try {
                            val sObj = org.json.JSONObject(rawJsonText)
                            val sUrl = sObj.optString("serverUrl", "").trim()
                            val sCmd = sObj.optString("command", "").trim()
                            if (sUrl.isBlank() && sCmd.isBlank()) {
                                validationError = "JSON must specify either 'command' or 'serverUrl'"
                                return@Button
                            }
                            val isDis = sObj.optBoolean("disabled", initialSpec?.disabled ?: false)
                            val spec = com.example.gemini.domain.model.McpServerSpec(
                                serverName = cleanName,
                                command = sCmd,
                                serverUrl = sUrl,
                                disabled = isDis
                            )
                            onSave(spec, rawJsonText.trim())
                        } catch (e: Exception) {
                            validationError = "Invalid JSON: ${e.message}"
                            return@Button
                        }
                    } else if (mode == McpEditMode.STDIO) {
                        val cleanCmd = command.trim()
                        if (cleanCmd.isBlank()) {
                            validationError = "Command is required"
                            return@Button
                        }
                        // Parse arguments, supporting quoted strings
                        val argsList = mutableListOf<String>()
                        val regex = """[^\s"']+|"([^"]*)"|'([^']*)'""".toRegex()
                        regex.findAll(argsText.trim()).forEach { m ->
                            val arg = m.groups[1]?.value ?: m.groups[2]?.value ?: m.value
                            if (arg.isNotBlank()) argsList.add(arg)
                        }

                        val envMap = mutableMapOf<String, String>()
                        envText.lines().forEach { line ->
                            val parts = line.split("=", limit = 2)
                            if (parts.size == 2 && parts[0].trim().isNotBlank()) {
                                envMap[parts[0].trim()] = parts[1].trim()
                            }
                        }

                        val spec = com.example.gemini.domain.model.McpServerSpec(
                            serverName = cleanName,
                            command = cleanCmd,
                            args = argsList,
                            env = envMap,
                            cwd = cwdText.trim(),
                            disabled = initialSpec?.disabled ?: false
                        )
                        onSave(spec, null)
                    } else {
                        val cleanUrl = serverUrl.trim()
                        if (cleanUrl.isBlank()) {
                            validationError = "Server URL is required"
                            return@Button
                        }

                        val headersMap = mutableMapOf<String, String>()
                        headersText.lines().forEach { line ->
                            val trimmed = line.trim()
                            if (trimmed.isNotBlank()) {
                                val delimiter = if (trimmed.contains(':')) ':' else if (trimmed.contains('=')) '=' else null
                                if (delimiter != null) {
                                    val parts = trimmed.split(delimiter, limit = 2)
                                    val k = parts[0].trim()
                                    val v = parts[1].trim()
                                    if (k.isNotBlank()) {
                                        headersMap[k] = v
                                    }
                                }
                            }
                        }

                        val spec = com.example.gemini.domain.model.McpServerSpec(
                            serverName = cleanName,
                            serverUrl = cleanUrl,
                            headers = headersMap,
                            disabled = initialSpec?.disabled ?: false
                        )
                        onSave(spec, null)
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Text("Save", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// SUB-SCREEN 4: TERMINAL & SHELL SETTINGS
// ==========================================
@Composable
private fun TerminalSubScreen(
    useSshTerminal: Boolean,
    sshHost: String,
    sshPort: Int,
    sshUser: String,
    sshPass: String,
    isLocalToolsEnabled: Boolean,
    isLocalToolsInstalled: Boolean,
    terminalFontSize: Int,
    terminalCursorStyle: String,
    terminalBufferSize: Int,
    terminalTheme: String,
    cardBg: Color,
    cardBorder: BorderStroke,
    onToggleUseSshTerminal: (Boolean) -> Unit,
    onSaveSshSettings: (String, Int, String, String) -> Unit,
    onToggleLocalTools: (Boolean) -> Unit,
    onInstallLocalTools: () -> Unit,
    onResetLocalTools: () -> Unit,
    onSaveTerminalPreferences: (Int, String, Int, String) -> Unit,
    onOpenLocalTerminal: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showResetWarningDialog by remember { mutableStateOf(false) }
    var showResetPasswordDialog by remember { mutableStateOf(false) }
    var resetPasswordInput by remember { mutableStateOf("") }
    var showShutdownCountdownDialog by remember { mutableStateOf(false) }
    var countdownSeconds by remember { mutableIntStateOf(5) }

    var hostState by remember { mutableStateOf(sshHost) }
    var portState by remember { mutableStateOf(sshPort.toString()) }
    var userState by remember { mutableStateOf(sshUser) }
    var passState by remember { mutableStateOf(sshPass) }
    var isTestingSsh by remember { mutableStateOf(false) }
    var sshTestStatus by remember { mutableStateOf<String?>(null) }
    var sshSaveFeedback by remember { mutableStateOf<String?>(null) }

    var fontSizeState by remember { mutableIntStateOf(terminalFontSize) }
    var cursorStyleState by remember { mutableStateOf(terminalCursorStyle) }
    var bufferSizeState by remember { mutableIntStateOf(terminalBufferSize) }
    var themeState by remember { mutableStateOf(terminalTheme) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Mode Selector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Terminal,
                        contentDescription = null,
                        tint = if (useSshTerminal) QuotaGreen else ClaudeTerracotta,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = "Use Terminal via SSH", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = if (useSshTerminal) QuotaGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                            ) {
                                Text(
                                    text = if (useSshTerminal) "SSH Mode" else "Local Mode",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (useSshTerminal) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)
                                )
                            }
                        }
                        Text(
                            text = if (useSshTerminal) "Direct SSH to Termux sshd without local rootfs" else "Built-in chroot rootfs environment",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = useSshTerminal,
                        onCheckedChange = { onToggleUseSshTerminal(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = QuotaGreen
                        )
                    )
                }

                if (useSshTerminal) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = hostState,
                            onValueChange = {
                                hostState = it
                                sshTestStatus = null
                                sshSaveFeedback = null
                            },
                            label = { Text("SSH Host") },
                            modifier = Modifier.weight(2f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = portState,
                            onValueChange = {
                                portState = it
                                sshTestStatus = null
                                sshSaveFeedback = null
                            },
                            label = { Text("Port") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = userState,
                            onValueChange = {
                                userState = it
                                sshTestStatus = null
                                sshSaveFeedback = null
                            },
                            label = { Text("User") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                        OutlinedTextField(
                            value = passState,
                            onValueChange = {
                                passState = it
                                sshTestStatus = null
                                sshSaveFeedback = null
                            },
                            label = { Text("Password") },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(8.dp)
                        )
                    }

                    if (sshTestStatus != null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = sshTestStatus ?: "",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (sshTestStatus?.startsWith("✓") == true) QuotaGreen else Color.Red
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            isTestingSsh = true
                            sshTestStatus = "Testing SSH connection to $hostState..."
                            coroutineScope.launch {
                                val res = com.example.gemini.data.ssh.TermuxSshManager.testConnection(
                                    host = hostState.trim(),
                                    port = portState.toIntOrNull() ?: sshPort,
                                    user = userState.trim(),
                                    pass = passState
                                )
                                isTestingSsh = false
                                sshTestStatus = if (res.isSuccess) "✓ SSH connection successful!" else "✗ Failed: ${res.exceptionOrNull()?.localizedMessage ?: "Connection refused"}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isTestingSsh
                    ) {
                        if (isTestingSsh) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Test SSH Connection", fontSize = 12.sp)
                    }

                    if (sshSaveFeedback != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            color = QuotaGreen.copy(alpha = 0.12f)
                        ) {
                            Text(
                                text = sshSaveFeedback ?: "",
                                fontSize = 12.sp,
                                color = QuotaGreen,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            val p = portState.toIntOrNull() ?: sshPort
                            onSaveSshSettings(hostState.trim(), p, userState.trim(), passState)
                            sshSaveFeedback = "✓ SSH settings saved successfully!"
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Save SSH Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                } else {
                    // Local tools mode details
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(thickness = 0.6.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
                    Spacer(modifier = Modifier.height(14.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column {
                            Text("Embedded Linux Rootfs", fontSize = 13.5.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                            Text(text = if (isLocalToolsInstalled) "Installed and ready" else "Not installed (>30MB download)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = if (isLocalToolsInstalled) QuotaGreen.copy(alpha = 0.15f) else ClaudeTerracotta.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = if (isLocalToolsInstalled) "Installed ✓" else "Not Installed",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isLocalToolsInstalled) QuotaGreen else ClaudeTerracotta,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(modifier = Modifier.fillMaxWidth()) {
                        if (isLocalToolsInstalled) {
                            OutlinedButton(
                                onClick = { showResetWarningDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.4f))
                            ) {
                                Icon(imageVector = Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(15.dp), tint = Color.Red)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Delete All & Factory Reset", fontSize = 12.5.sp, color = Color.Red, fontWeight = FontWeight.SemiBold)
                            }
                        } else {
                            Button(
                                onClick = onInstallLocalTools,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                            ) {
                                Text("Download & Install Local Tools", fontSize = 12.5.sp)
                            }
                        }
                    }
                }
            }
        }

        // Terminal Appearance Customization Card
        Text(
            text = "Terminal Customization",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                // Font Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Font Size", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                    Text("${fontSizeState}pt", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = ClaudeTerracotta)
                }
                Slider(
                    value = fontSizeState.toFloat(),
                    onValueChange = {
                        fontSizeState = it.toInt()
                        onSaveTerminalPreferences(fontSizeState, cursorStyleState, bufferSizeState, themeState)
                    },
                    valueRange = 10f..24f,
                    steps = 14,
                    colors = SliderDefaults.colors(thumbColor = ClaudeTerracotta, activeTrackColor = ClaudeTerracotta)
                )

                // Cursor Style
                Text("Cursor Style", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val cursorOptions = listOf("BLOCK" to "Block █", "UNDERLINE" to "Underline  ", "BAR" to "Bar |")
                    cursorOptions.forEach { (style, label) ->
                        val isSel = cursorStyleState == style
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    cursorStyleState = style
                                    onSaveTerminalPreferences(fontSizeState, style, bufferSizeState, themeState)
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(text = label, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) ClaudeTerracottaOnPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // Buffer Size
                Text("Scrollback Buffer", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val buffers = listOf(
                        1000 to "1k",
                        5000 to "5k",
                        10000 to "10k",
                        20000 to "20k",
                        50000 to "50k",
                        100000 to "Unlimited"
                    )
                    buffers.forEach { (buf, label) ->
                        val isSel = bufferSizeState == buf || (buf == 100000 && bufferSizeState >= 100000)
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    bufferSizeState = buf
                                    onSaveTerminalPreferences(fontSizeState, cursorStyleState, buf, themeState)
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        ) {
                            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(text = label, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) ClaudeTerracottaOnPrimary else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }
            }
        }

        // Action Button: Open Terminal
        Button(
            onClick = onOpenLocalTerminal,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
        ) {
            Icon(imageVector = Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Launch Terminal Now", fontWeight = FontWeight.Bold)
        }
    }

    if (showResetWarningDialog) {
        AlertDialog(
            onDismissRequest = { showResetWarningDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Delete All & Factory Reset?",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = "Warning: This will perform an Android OS-level data wipe. All installed packages, rootfs, dotfiles, app settings, and history will be completely cleared.\n\nThe app will close and start completely fresh on next launch. Are you sure you want to proceed?",
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showResetWarningDialog = false
                        resetPasswordInput = ""
                        showResetPasswordDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Yes, Continue", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetWarningDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showResetPasswordDialog) {
        val isMatch = resetPasswordInput.trim() == "iknowwhatiamdoing"
        AlertDialog(
            onDismissRequest = { showResetPasswordDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color.Red,
                    modifier = Modifier.size(28.dp)
                )
            },
            title = {
                Text(
                    text = "Confirm Full App Wipe",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "To confirm and permanently wipe all app data and local rootfs, type the confirmation phrase exactly as shown below:",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "iknowwhatiamdoing",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            color = ClaudeTerracotta,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    OutlinedTextField(
                        value = resetPasswordInput,
                        onValueChange = { resetPasswordInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        isError = resetPasswordInput.isNotEmpty() && !isMatch
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (isMatch) {
                            showResetPasswordDialog = false
                            countdownSeconds = 5
                            showShutdownCountdownDialog = true
                        }
                    },
                    enabled = isMatch,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Red,
                        disabledContainerColor = Color.Red.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Delete All Data", fontWeight = FontWeight.Bold, color = Color.White)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showResetPasswordDialog = false },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Cancel")
                }
            },
            shape = RoundedCornerShape(16.dp),
            containerColor = MaterialTheme.colorScheme.surface
        )
    }

    if (showShutdownCountdownDialog) {
        LaunchedEffect(Unit) {
            for (i in 5 downTo 1) {
                countdownSeconds = i
                delay(1000)
            }
            countdownSeconds = 0
            delay(300)
            LocalEnvironmentManager.clearAppDataAndReset(context)
        }

        Dialog(
            onDismissRequest = {},
            properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 10.dp,
                border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.35f)),
                modifier = Modifier.fillMaxWidth(0.92f)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .background(Color.Red.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${countdownSeconds}s",
                            fontSize = 24.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.Red,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    Text(
                        text = "Factory Reset in Progress",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = "AntiBot is wiping all local app data, rootfs, and settings.\n\nThe app will automatically close in $countdownSeconds seconds. Simply re-open AntiBot to start fresh.",
                        fontSize = 13.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 19.sp
                    )

                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        strokeWidth = 3.dp,
                        color = Color.Red
                    )
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN: BACKUPS & RESTORE
// ==========================================

data class ScriptExecutionInfo(
    val title: String,
    val scriptName: String,
    val args: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupsSubScreen(
    isServerOnline: Boolean,
    isBridgeOnline: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onStopServer: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val systemConnectionState by com.example.gemini.data.remote.AgyBridgeService.instance.systemConnectionState.collectAsState()
    val isAnyServerRunning = systemConnectionState !is com.example.gemini.data.remote.SystemConnectionState.Offline

    var activeScriptExecution by remember { mutableStateOf<ScriptExecutionInfo?>(null) }
    var showServerRunningAlert by remember { mutableStateOf<String?>(null) }

    var showBackupChatsPasswordDialog by remember { mutableStateOf(false) }
    var backupChatsPassword by remember { mutableStateOf("") }
    var showBackupPasswordText by remember { mutableStateOf(false) }

    var showRestoreChatsDialog by remember { mutableStateOf(false) }
    var restoreFilePath by remember { mutableStateOf("") }
    var restorePassword by remember { mutableStateOf("") }
    var showRestorePasswordText by remember { mutableStateOf(false) }

    var showProjectsBackupRestoreDialog by remember { mutableStateOf(false) }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(context.cacheDir, "restore_payload_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        restoreFilePath = destFile.absolutePath
                    }
                } catch (e: Exception) {
                    Log.e("BackupsSubScreen", "Failed copying selected backup file", e)
                }
            }
        }
    }

    val requestStoragePermission = { onGranted: () -> Unit ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                onGranted()
            }
        } else {
            val permission = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (permission != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(context, "Storage permission needed to save to /sdcard/Download", Toast.LENGTH_SHORT).show()
            }
            onGranted()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Server Online Guard / Warning Banner
        if (isAnyServerRunning) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color(0xFFF59E0B).copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Color(0xFFF59E0B).copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B),
                        modifier = Modifier.size(24.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AGY Server Is Running",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "The daemon is actively accessing databases and processes. Please stop the server before performing backups or restores to prevent corrupted archives.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                            lineHeight = 16.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = onStopServer,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48)),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Server Now", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Card 1: Rootfs Environment Backup
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF00ACC1).copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Inventory2,
                                contentDescription = null,
                                tint = Color(0xFF00ACC1),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Rootfs Environment Backup",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Package entire Linux /usr system and \$HOME into a bootable zip",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Creates a full, portable backup of all installed packages, libraries, binaries, shell configurations, and user home dotfiles. Excludes active sockets, volatile caches, and auth tokens.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saves to: /sdcard/Download/Antigem/backups/bootrapz_<timestamp>.zip",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (isAnyServerRunning) {
                            showServerRunningAlert = "Backup Rootfs"
                        } else {
                            requestStoragePermission {
                                activeScriptExecution = ScriptExecutionInfo(
                                    title = "Rootfs System Packager",
                                    scriptName = "backup_bootstrap",
                                    args = emptyList()
                                )
                            }
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Run Rootfs Backup (backup_bootstrap)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Card 2: AGY Chats & Credentials Backup
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = ClaudeTerracotta.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Chat,
                                contentDescription = null,
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "AGY Chats & Credentials Backup",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Archive conversations, transcripts, auth tokens & configs",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Safely packages your entire ~/.gemini folder (SQLite chat history databases, brain transcripts, summaries index, custom skills, and authentication token). Supports optional zip encryption.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFEF4444).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                        Text(
                            text = "⚠️ Security Warning: This backup includes your authentication credentials and login tokens. Never share this archive with anyone, as anyone with this file can log in to your account.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Saves to: /sdcard/Download/Antigem/backups/agy_chats_backup_<timestamp>.zip",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = {
                        if (isAnyServerRunning) {
                            showServerRunningAlert = "Backup Chats"
                        } else {
                            showBackupChatsPasswordDialog = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Backup AGY Chats (backup_chats)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Card 3: Restore AGY Chats & Credentials
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = QuotaGreen.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.SettingsBackupRestore,
                                contentDescription = null,
                                tint = QuotaGreen,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Restore AGY Chats & Credentials",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Restore previous chats and login from a backup zip archive",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Restores your chats, conversations index, and credentials. To prevent any accidental data loss, the restore script automatically creates a safety backup of your existing ~/.gemini directory to ~/.gemini.bak first.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                OutlinedButton(
                    onClick = {
                        if (isAnyServerRunning) {
                            showServerRunningAlert = "Restore Chats"
                        } else {
                            showRestoreChatsDialog = true
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, QuotaGreen),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = QuotaGreen),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Restore AGY Chats (restore_chats)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }

        // Card 4: IDE & AGY Projects Backup & Restore
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = Color(0xFF6366F1).copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.Workspaces,
                                contentDescription = null,
                                tint = Color(0xFF6366F1),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "IDE & AGY Projects Backup & Restore",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "Archive selected projects, workspaces & restore from zip",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Export and backup projects open in the IDE and AGY conversation workspaces. Choose clean git-aware packaging (excluding node_modules and build caches) or full raw folder archives. Restore with automatic location mapping and safety backup protection.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                Button(
                    onClick = { showProjectsBackupRestoreDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.FolderZip, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Manage Projects Backup & Restore", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }

    // 1. Server Running Alert Dialog
    showServerRunningAlert?.let { actionName ->
        AlertDialog(
            onDismissRequest = { showServerRunningAlert = null },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null, tint = Color(0xFFF59E0B)) },
            title = { Text("Server Must Be Stopped", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "The AGY IDE Bridge and Hub server must be offline before running '$actionName'. Would you like to stop the server now?",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        onStopServer()
                        showServerRunningAlert = null
                        Toast.makeText(context, "Stopping server... Please re-run $actionName once stopped.", Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                ) {
                    Text("Stop Server", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showServerRunningAlert = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 2. Backup Chats Password Dialog
    if (showBackupChatsPasswordDialog) {
        AlertDialog(
            onDismissRequest = { showBackupChatsPasswordDialog = false },
            icon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null, tint = ClaudeTerracotta) },
            title = { Text("Backup AGY Chats", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Optionally enter a password to encrypt your backup zip archive. Leave empty for an unencrypted standard zip.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFEF4444).copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, Color(0xFFEF4444).copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(Icons.Default.Security, contentDescription = null, tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                            Text(
                                text = "Do not share this file. Anyone with it can log into your account.",
                                fontSize = 10.5.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                    OutlinedTextField(
                        value = backupChatsPassword,
                        onValueChange = { backupChatsPassword = it },
                        label = { Text("Encryption Password (Optional)") },
                        singleLine = true,
                        visualTransformation = if (showBackupPasswordText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showBackupPasswordText = !showBackupPasswordText }) {
                                Icon(
                                    imageVector = if (showBackupPasswordText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val pass = backupChatsPassword.trim()
                        showBackupChatsPasswordDialog = false
                        backupChatsPassword = ""
                        requestStoragePermission {
                            activeScriptExecution = ScriptExecutionInfo(
                                title = "AGY Chats & Credentials Backup",
                                scriptName = "backup_chats",
                                args = if (pass.isNotEmpty()) listOf("", pass) else emptyList()
                            )
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                ) {
                    Text("Start Backup", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBackupChatsPasswordDialog = false
                    backupChatsPassword = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 3. Restore Chats Dialog
    if (showRestoreChatsDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreChatsDialog = false },
            icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, tint = QuotaGreen) },
            title = { Text("Restore AGY Chats", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Select your backup zip archive to restore. Note: your existing ~/.gemini directory will be safely renamed to ~/.gemini.bak first.",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                    )

                    OutlinedTextField(
                        value = restoreFilePath,
                        onValueChange = { restoreFilePath = it },
                        label = { Text("Backup Archive Path") },
                        placeholder = { Text("/sdcard/Download/agy_chats_backup_....zip") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                Icon(Icons.Outlined.FolderOpen, contentDescription = "Choose File", tint = QuotaGreen)
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("Archive Password (if encrypted)") },
                        singleLine = true,
                        visualTransformation = if (showRestorePasswordText) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showRestorePasswordText = !showRestorePasswordText }) {
                                Icon(
                                    imageVector = if (showRestorePasswordText) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val path = restoreFilePath.trim()
                        val pass = restorePassword.trim()
                        showRestoreChatsDialog = false
                        restoreFilePath = ""
                        restorePassword = ""
                        activeScriptExecution = ScriptExecutionInfo(
                            title = "Restore AGY Chats & Credentials",
                            scriptName = "restore_chats",
                            args = if (pass.isNotEmpty()) listOf(path, pass) else listOf(path)
                        )
                    },
                    enabled = restoreFilePath.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen)
                ) {
                    Text("Start Restore", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showRestoreChatsDialog = false
                    restoreFilePath = ""
                    restorePassword = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }

    // 4. Live Script Execution Dialog
    activeScriptExecution?.let { executionInfo ->
        ScriptExecutionDialog(
            info = executionInfo,
            onDismiss = { activeScriptExecution = null }
        )
    }

    // 5. Projects Backup & Restore Dialog
    if (showProjectsBackupRestoreDialog) {
        ProjectsBackupRestoreDialog(
            onDismiss = { showProjectsBackupRestoreDialog = false },
            onRequestStoragePermission = requestStoragePermission,
            onExecuteScript = { executionInfo ->
                activeScriptExecution = executionInfo
            }
        )
    }
}

data class ProjectBackupManifestItem(
    val name: String,
    val originalPath: String,
    val backupSubpath: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectsBackupRestoreDialog(
    onDismiss: () -> Unit,
    onRequestStoragePermission: (() -> Unit) -> Unit,
    onExecuteScript: (ScriptExecutionInfo) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) } // 0 = Backup, 1 = Restore
    // Projects list from TermuxDaemonManager
    val allProjects by TermuxDaemonManager.projects.collectAsState()
    val homeDir = remember { LocalEnvironmentManager.getHomeDir(context).absolutePath.trimEnd('/') }

    fun normalizeProjectPath(p: String, home: String): String {
        var clean = p.trim().removePrefix("file://").trim()
        if (clean.startsWith("~/")) {
            clean = home + clean.removePrefix("~")
        } else if (clean == "~") {
            clean = home
        }
        if (clean.startsWith("/data/user/0/")) {
            clean = "/data/data/" + clean.removePrefix("/data/user/0/")
        }
        clean = clean.replace(Regex("/+"), "/")
        return clean.trimEnd('/')
    }

    // Filter out root $HOME, root filesystem paths, and duplicates
    val validProjects = remember(allProjects, homeDir) {
        val normHome = normalizeProjectPath(homeDir, homeDir)
        allProjects.filter { proj ->
            val p = normalizeProjectPath(proj.path, normHome)
            p.isNotBlank() && p != normHome && p != "/" && p != "/data/data/com.termux/files" && p != "/data/data/com.termux"
        }.distinctBy { normalizeProjectPath(it.path, normHome) }
    }

    LaunchedEffect(Unit) {
        TermuxDaemonManager.loadProjects()
    }

    // --- Backup Tab State ---
    val selectedProjectPaths = remember { mutableStateListOf<String>() }
    var hasAutoSelected by remember { mutableStateOf(false) }
    var backupMode by remember { mutableStateOf("git") } // "git" or "full"
    var searchQuery by remember { mutableStateOf("") }

    // Auto-select valid projects by default only once on initial load
    LaunchedEffect(validProjects) {
        if (!hasAutoSelected && validProjects.isNotEmpty()) {
            selectedProjectPaths.clear()
            selectedProjectPaths.addAll(validProjects.map { it.path })
            hasAutoSelected = true
        }
    }

    val filteredProjects = remember(validProjects, searchQuery) {
        if (searchQuery.isBlank()) validProjects
        else validProjects.filter { it.name.contains(searchQuery, ignoreCase = true) || it.path.contains(searchQuery, ignoreCase = true) }
    }

    val selectedCount = remember(selectedProjectPaths.toList(), validProjects) {
        validProjects.count { selectedProjectPaths.contains(it.path) }
    }

    // Build nested tree list with path normalization
    val flattenedTree = remember(filteredProjects, homeDir) {
        val normHome = normalizeProjectPath(homeDir, homeDir)
        val normMap = filteredProjects.associateWith { normalizeProjectPath(it.path, normHome) }
        val sorted = filteredProjects.sortedBy { normMap[it]?.length ?: 0 }

        fun findParent(proj: ProjectItem): ProjectItem? {
            val childNorm = normMap[proj] ?: return null
            return sorted.filter { parent ->
                val parentNorm = normMap[parent] ?: ""
                parentNorm.isNotBlank() && parentNorm != childNorm && childNorm.startsWith("$parentNorm/")
            }.maxByOrNull { normMap[it]?.length ?: 0 }
        }

        val parentMap = mutableMapOf<String, ProjectItem?>()
        filteredProjects.forEach { proj ->
            parentMap[proj.path] = findParent(proj)
        }

        val result = mutableListOf<Triple<ProjectItem, Int, ProjectItem?>>() // item, level, parent
        fun addNodeAndChildren(proj: ProjectItem, level: Int) {
            result.add(Triple(proj, level, parentMap[proj.path]))
            val children = filteredProjects.filter { parentMap[it.path]?.path == proj.path }
            children.forEach { child ->
                addNodeAndChildren(child, level + 1)
            }
        }

        val roots = filteredProjects.filter { parentMap[it.path] == null }
        roots.forEach { root ->
            addNodeAndChildren(root, 0)
        }
        result
    }

    val effectiveBackupPaths = remember(selectedProjectPaths.toList(), validProjects, homeDir) {
        val normHome = normalizeProjectPath(homeDir, homeDir)
        val normMap = validProjects.associateWith { normalizeProjectPath(it.path, normHome) }
        val selected = validProjects.filter { selectedProjectPaths.contains(it.path) }
        selected.filter { item ->
            val itemNorm = normMap[item] ?: ""
            selected.none { other ->
                val otherNorm = normMap[other] ?: ""
                other.path != item.path && otherNorm.isNotBlank() && itemNorm.startsWith("$otherNorm/")
            }
        }.map { it.path }
    }

    // --- Restore Tab State ---
    var restoreZipPath by remember { mutableStateOf("") }
    var conflictStrategy by remember { mutableStateOf("bak") } // "bak" or "overwrite"
    var customRestoreDir by remember { mutableStateOf("") }
    var useCustomRestoreDir by remember { mutableStateOf(false) }
    var manifestProjects by remember { mutableStateOf<List<ProjectBackupManifestItem>>(emptyList()) }
    var manifestBackupMode by remember { mutableStateOf<String?>(null) }
    var isInspectingArchive by remember { mutableStateOf(false) }
    var archiveError by remember { mutableStateOf<String?>(null) }

    val inspectArchive: (String) -> Unit = { path ->
        if (path.isBlank()) {
            manifestProjects = emptyList()
            manifestBackupMode = null
            archiveError = null
        } else {
            scope.launch(Dispatchers.IO) {
                isInspectingArchive = true
                archiveError = null
                try {
                    val file = File(path)
                    if (!file.exists()) {
                        withContext(Dispatchers.Main) {
                            archiveError = "Archive file not found at: $path"
                            manifestProjects = emptyList()
                            isInspectingArchive = false
                        }
                    } else {
                        val zip = ZipFile(file)
                        val entry = zip.getEntry("antigem_project_manifest.json")
                            ?: zip.getEntry(".antigem_project_manifest.json")
                        if (entry != null) {
                            val stream = zip.getInputStream(entry)
                            val content = stream.bufferedReader().use { it.readText() }
                            val json = JSONObject(content)
                            val projectsArr = json.optJSONArray("projects") ?: JSONArray()
                            val list = mutableListOf<ProjectBackupManifestItem>()
                            for (i in 0 until projectsArr.length()) {
                                val obj = projectsArr.getJSONObject(i)
                                list.add(
                                    ProjectBackupManifestItem(
                                        name = obj.optString("name", "Project"),
                                        originalPath = obj.optString("original_path", ""),
                                        backupSubpath = obj.optString("backup_subpath", "")
                                    )
                                )
                            }
                            val mode = json.optString("backup_mode", "git")
                            zip.close()
                            withContext(Dispatchers.Main) {
                                manifestProjects = list
                                manifestBackupMode = mode
                                isInspectingArchive = false
                            }
                        } else {
                            val entries = zip.entries().asSequence().map { it.name }.toList()
                            zip.close()
                            val roots = entries.map { it.substringBefore('/') }.filter { it.isNotBlank() && !it.startsWith("__") }.distinct()
                            val list = roots.map { ProjectBackupManifestItem(name = it, originalPath = "", backupSubpath = it) }
                            withContext(Dispatchers.Main) {
                                manifestProjects = list
                                manifestBackupMode = "raw"
                                isInspectingArchive = false
                            }
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        archiveError = "Error reading archive: ${e.message}"
                        manifestProjects = emptyList()
                        isInspectingArchive = false
                    }
                }
            }
        }
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    val destFile = File(context.cacheDir, "project_restore_payload_${System.currentTimeMillis()}.zip")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        destFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    withContext(Dispatchers.Main) {
                        restoreZipPath = destFile.absolutePath
                        inspectArchive(destFile.absolutePath)
                    }
                } catch (e: Exception) {
                    Log.e("ProjectsBackup", "Failed copying selected backup file", e)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFF6366F1).copy(alpha = 0.15f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Workspaces,
                                    contentDescription = null,
                                    tint = Color(0xFF6366F1),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Projects Backup & Restore",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Backup IDE & AGY workspaces or restore from zip",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Tab Switcher
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    contentColor = Color(0xFF6366F1),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Backup Projects", fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal) },
                        icon = { Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Restore Projects", fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal) },
                        icon = { Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // ==========================================
                    // TAB 0: BACKUP PROJECTS
                    // ==========================================
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Backup Mode Selection Card
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    text = "Backup Format & Packaging Mode:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // Git-Aware (Clean)
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (backupMode == "git") Color(0xFF6366F1).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (backupMode == "git") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { backupMode = "git" }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (backupMode == "git") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (backupMode == "git") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Column {
                                                Text("Git-Aware (Clean)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (backupMode == "git") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurface)
                                                Text("Excludes node_modules & build caches", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }

                                    // Full Raw Archive
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (backupMode == "full") Color(0xFF6366F1).copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                        border = BorderStroke(1.dp, if (backupMode == "full") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { backupMode = "full" }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (backupMode == "full") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                                contentDescription = null,
                                                tint = if (backupMode == "full") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Column {
                                                Text("Full Raw Archive", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (backupMode == "full") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurface)
                                                Text("All files, symlinks & permissions as-is", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Select All / Deselect Bar + Count
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Select Projects ($selectedCount/${validProjects.size}):",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                TextButton(
                                    onClick = {
                                        selectedProjectPaths.clear()
                                        selectedProjectPaths.addAll(validProjects.map { it.path })
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Select All", fontSize = 11.5.sp, color = Color(0xFF6366F1))
                                }
                                TextButton(
                                    onClick = { selectedProjectPaths.clear() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text("Deselect All", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }

                        // Project Items Tree List
                        if (validProjects.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No open projects or workspaces detected in the IDE.",
                                    fontSize = 12.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            val normHome = remember(homeDir) { normalizeProjectPath(homeDir, homeDir) }
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(flattenedTree, key = { it.first.path }) { (proj, level, parentProj) ->
                                    val isParentSelected = parentProj != null && selectedProjectPaths.contains(parentProj.path)
                                    val isDirectlySelected = selectedProjectPaths.contains(proj.path)
                                    val isCoveredByParent = isParentSelected
                                    val isSelected = isDirectlySelected || isCoveredByParent
                                    val projNorm = normalizeProjectPath(proj.path, normHome)
                                    val childProjectsCount = validProjects.count {
                                        val otherNorm = normalizeProjectPath(it.path, normHome)
                                        it.path != proj.path && otherNorm.startsWith("$projNorm/")
                                    }

                                    val toggleNode: () -> Unit = {
                                        if (isCoveredByParent && parentProj != null) {
                                            selectedProjectPaths.remove(parentProj.path)
                                            val parentNorm = normalizeProjectPath(parentProj.path, normHome)
                                            val siblings = validProjects.filter {
                                                val otherNorm = normalizeProjectPath(it.path, normHome)
                                                it.path != parentProj.path && otherNorm.startsWith("$parentNorm/")
                                            }
                                            siblings.forEach { sib ->
                                                if (sib.path != proj.path && !selectedProjectPaths.contains(sib.path)) {
                                                    selectedProjectPaths.add(sib.path)
                                                }
                                            }
                                            selectedProjectPaths.remove(proj.path)
                                        } else {
                                            if (isDirectlySelected) {
                                                selectedProjectPaths.remove(proj.path)
                                                val descendants = validProjects.filter {
                                                    val otherNorm = normalizeProjectPath(it.path, normHome)
                                                    otherNorm.startsWith("$projNorm/")
                                                }
                                                descendants.forEach { selectedProjectPaths.remove(it.path) }
                                            } else {
                                                selectedProjectPaths.add(proj.path)
                                            }
                                        }
                                    }

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(start = (level * 22).dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (level > 0) {
                                            Text(
                                                text = "└──",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                fontFamily = FontFamily.Monospace,
                                                color = Color(0xFF6366F1).copy(alpha = 0.8f),
                                                modifier = Modifier.padding(end = 6.dp)
                                            )
                                        }

                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isSelected) Color(0xFF6366F1).copy(alpha = 0.08f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                            border = BorderStroke(
                                                1.dp,
                                                if (isSelected) Color(0xFF6366F1).copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                            ),
                                            modifier = Modifier
                                                .weight(1f)
                                                .clickable { toggleNode() }
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                                            ) {
                                                Checkbox(
                                                    checked = isSelected,
                                                    onCheckedChange = { toggleNode() },
                                                    colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6366F1)),
                                                    modifier = Modifier.size(20.dp)
                                                )
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                    ) {
                                                        Text(
                                                            text = proj.name,
                                                            fontSize = 13.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                        if (childProjectsCount > 0) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF6366F1).copy(alpha = 0.15f)
                                                            ) {
                                                                Text(
                                                                    text = "$childProjectsCount ${if (childProjectsCount == 1) "sub-project" else "sub-projects"}",
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Color(0xFF6366F1),
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                        if (isCoveredByParent && parentProj != null) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF10B981).copy(alpha = 0.15f)
                                                            ) {
                                                                Text(
                                                                    text = "Included with ${parentProj.name}",
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = Color(0xFF10B981),
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        } else if (parentProj != null) {
                                                            Surface(
                                                                shape = RoundedCornerShape(4.dp),
                                                                color = Color(0xFF6366F1).copy(alpha = 0.12f)
                                                            ) {
                                                                Text(
                                                                    text = "Inside ${parentProj.name}",
                                                                    fontSize = 9.5.sp,
                                                                    fontWeight = FontWeight.Medium,
                                                                    color = Color(0xFF6366F1),
                                                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                    Text(
                                                        text = proj.path,
                                                        fontSize = 10.5.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Backup Action Button
                        Button(
                            onClick = {
                                if (effectiveBackupPaths.isEmpty()) {
                                    Toast.makeText(context, "Please select at least one project to backup", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onRequestStoragePermission {
                                    val targetsFile = File(context.cacheDir, "backup_targets_${System.currentTimeMillis()}.txt")
                                    targetsFile.writeText(effectiveBackupPaths.joinToString("\n"))
                                    onDismiss()
                                    onExecuteScript(
                                        ScriptExecutionInfo(
                                            title = "Projects Backup Packager",
                                            scriptName = "backup_projects",
                                            args = listOf("", backupMode, "--paths-file", targetsFile.absolutePath)
                                        )
                                    )
                                }
                            },
                            enabled = selectedCount > 0,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF6366F1)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.Archive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (effectiveBackupPaths.size == selectedCount) "Backup $selectedCount Selected Projects"
                                       else "Backup $selectedCount Selected Projects (${effectiveBackupPaths.size} workspace roots)",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                        }
                    }
                } else {
                    // ==========================================
                    // TAB 1: RESTORE PROJECTS
                    // ==========================================
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Select a projects backup archive (.zip) to restore projects to their workspace locations.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )

                        // File Picker Input
                        OutlinedTextField(
                            value = restoreZipPath,
                            onValueChange = {
                                restoreZipPath = it
                                inspectArchive(it.trim())
                            },
                            label = { Text("Projects Archive File Path") },
                            placeholder = { Text("/sdcard/Download/Antigem/backups/projects_backup_....zip") },
                            singleLine = true,
                            trailingIcon = {
                                IconButton(onClick = { filePickerLauncher.launch("*/*") }) {
                                    Icon(Icons.Outlined.FolderOpen, contentDescription = "Choose File", tint = Color(0xFF6366F1))
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        // Archive Inspection Summary
                        if (isInspectingArchive) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF6366F1))
                                Text("Inspecting archive contents & manifest...", fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }

                        archiveError?.let { err ->
                            Text(text = err, color = Color(0xFFEF4444), fontSize = 11.5.sp)
                        }

                        if (manifestProjects.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Projects in Archive (${manifestProjects.size}):",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        manifestBackupMode?.let { mode ->
                                            Text("Mode: $mode", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }

                                    manifestProjects.forEach { item ->
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        ) {
                                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = Color(0xFF6366F1), modifier = Modifier.size(16.dp))
                                            Column {
                                                Text(item.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                                if (item.originalPath.isNotBlank()) {
                                                    Text(
                                                        "Target: ${item.originalPath}",
                                                        fontSize = 10.sp,
                                                        fontFamily = FontFamily.Monospace,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Conflict Strategy Option
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "If Target Project Folder Already Exists:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )

                                // Option A: Move to .bak
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { conflictStrategy = "bak" }
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (conflictStrategy == "bak") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (conflictStrategy == "bak") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text("Move existing to .bak.<timestamp> (Safe & Recommended)", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Preserves existing code in a backup folder before extracting", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }

                                // Option B: Overwrite
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(6.dp))
                                        .clickable { conflictStrategy = "overwrite" }
                                        .padding(4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (conflictStrategy == "overwrite") Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                                        contentDescription = null,
                                        tint = if (conflictStrategy == "overwrite") Color(0xFF6366F1) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Column {
                                        Text("Overwrite files in-place", fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                                        Text("Overwrites matching files directly inside target directory", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }

                        // Optional: Custom Root Target Directory
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Checkbox(
                                checked = useCustomRestoreDir,
                                onCheckedChange = { useCustomRestoreDir = it },
                                colors = CheckboxDefaults.colors(checkedColor = Color(0xFF6366F1)),
                                modifier = Modifier.size(20.dp)
                            )
                            Text("Restore to custom parent directory", fontSize = 12.sp)
                        }

                        if (useCustomRestoreDir) {
                            OutlinedTextField(
                                value = customRestoreDir,
                                onValueChange = { customRestoreDir = it },
                                label = { Text("Custom Restore Directory") },
                                placeholder = { Text("/data/data/com.termux/files/home/restored_projects") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        // Restore Action Button
                        Button(
                            onClick = {
                                val zipPath = restoreZipPath.trim()
                                if (zipPath.isBlank()) {
                                    Toast.makeText(context, "Please enter or pick a backup zip file", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                onDismiss()
                                onExecuteScript(
                                    ScriptExecutionInfo(
                                        title = "Projects Archive Restorer",
                                        scriptName = "restore_projects",
                                        args = listOf(zipPath, conflictStrategy, if (useCustomRestoreDir) customRestoreDir.trim() else "")
                                    )
                                )
                                scope.launch(Dispatchers.IO) {
                                    delay(1500)
                                    TermuxDaemonManager.loadProjects()
                                }
                            },
                            enabled = restoreZipPath.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Outlined.SettingsBackupRestore, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Restore (restore_projects)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScriptExecutionDialog(
    info: ScriptExecutionInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val logs = remember { mutableStateListOf<String>() }
    var isRunning by remember { mutableStateOf(true) }
    var exitCode by remember { mutableStateOf<Int?>(null) }
    var durationMs by remember { mutableStateOf(0L) }
    val listState = rememberLazyListState()
    var executionJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    val activity = (context as? android.app.Activity) ?: ((context as? android.content.ContextWrapper)?.baseContext as? android.app.Activity)
    DisposableEffect(isRunning) {
        val window = activity?.window
        if (isRunning) {
            window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    LaunchedEffect(info) {
        executionJob?.cancel()
        executionJob = scope.launch {
            isRunning = true
            logs.clear()
            logs.add("⚡ Initializing ${info.scriptName}...")
            val result = LocalEnvironmentManager.executeScriptLive(
                context = context,
                scriptName = info.scriptName,
                args = info.args
            ) { line ->
                val trimmed = line.trim()
                val isProgressBar = (trimmed.startsWith("[") || trimmed.contains("%")) && trimmed.contains("/")
                val prevIsProgressBar = logs.isNotEmpty() && logs.last().let { (it.trim().startsWith("[") || it.contains("%")) && it.contains("/") }
                if (isProgressBar && prevIsProgressBar) {
                    logs[logs.size - 1] = line
                } else {
                    logs.add(line)
                }
            }
            exitCode = result.exitCode
            durationMs = result.durationMs
            isRunning = false
        }
    }

    LaunchedEffect(logs.size, if (logs.isNotEmpty()) logs.last() else "") {
        if (logs.isNotEmpty()) {
            listState.scrollToItem(logs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = {
            if (!isRunning) onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = !isRunning,
            dismissOnClickOutside = !isRunning,
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.80f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (isRunning) {
                            Icon(
                                imageVector = Icons.Default.Terminal,
                                contentDescription = null,
                                tint = GeminiBlue,
                                modifier = Modifier.size(22.dp)
                            )
                        } else if (exitCode == 0) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = QuotaGreen, modifier = Modifier.size(22.dp))
                        } else {
                            Icon(Icons.Default.Error, contentDescription = null, tint = Color.Red, modifier = Modifier.size(22.dp))
                        }

                        Column {
                            Text(
                                text = info.title,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = if (isRunning) "Executing in rootfs environment..." else if (exitCode == 0) "Completed in ${(durationMs / 1000.0)}s (Exit: 0)" else "Finished with error (Exit: $exitCode)",
                                fontSize = 11.5.sp,
                                color = if (isRunning) GeminiBlue else if (exitCode == 0) QuotaGreen else Color.Red
                            )
                        }
                    }

                    if (!isRunning) {
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Terminal output container
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF090D16),
                    border = BorderStroke(1.dp, Color(0xFF1E293B)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(10.dp)
                    ) {
                        items(logs) { line ->
                            val cleanLine = line.replace(Regex("\u001B\\[[;\\d]*m"), "")
                            val lineColor = when {
                                cleanLine.startsWith("❌") || cleanLine.contains("Error", ignoreCase = true) -> Color(0xFFFF5252)
                                cleanLine.startsWith("✅") || cleanLine.startsWith("🎉") || cleanLine.contains("Success", ignoreCase = true) -> Color(0xFF4CAF50)
                                cleanLine.startsWith("⚠️") || cleanLine.contains("Note:", ignoreCase = true) || cleanLine.contains("Warning:", ignoreCase = true) -> Color(0xFFFFB74D)
                                cleanLine.startsWith("=") || cleanLine.startsWith("[") -> Color(0xFF64B5F6)
                                else -> Color(0xFFE2E8F0)
                            }
                            Text(
                                text = cleanLine,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = lineColor,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Bottom Action Button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (isRunning) {
                        Button(
                            onClick = {
                                executionJob?.cancel()
                                isRunning = false
                                exitCode = 130
                                logs.add("🛑 Execution cancelled by user.")
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE11D48))
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Stop Execution", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    } else {
                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (exitCode == 0) QuotaGreen else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Done", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ==========================================
// SUB-SCREEN: DATA LOSS PROTECTION
// ==========================================
@Composable
private fun DataProtectionSubScreen(
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isAdminActive by remember { mutableStateOf(AntiGemDeviceAdminReceiver.isAdminActive(context)) }
    var isShieldEnabled by remember { mutableStateOf(ManageSpaceActivity.isShieldEnabled(context)) }
    var showDeactivateAdminDialog by remember { mutableStateOf(false) }

    val adminLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        isAdminActive = AntiGemDeviceAdminReceiver.isAdminActive(context)
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                isAdminActive = AntiGemDeviceAdminReceiver.isAdminActive(context)
                isShieldEnabled = ManageSpaceActivity.isShieldEnabled(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Banner info
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF10B981).copy(alpha = 0.1f),
            border = BorderStroke(1.dp, Color(0xFF10B981).copy(alpha = 0.3f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Outlined.Security,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(26.dp)
                )
                Column {
                    Text(
                        text = "Data Loss Prevention",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Protect your local Linux environment, CLI tools, databases, and AI coding workspaces from accidental wipe or uninstall.",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        lineHeight = 16.sp
                    )
                }
            }
        }

        // Feature 1: Accidental Uninstall Protection (Device Administrator)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isAdminActive) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isAdminActive) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                                contentDescription = null,
                                tint = if (isAdminActive) QuotaGreen else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Accidental Uninstall Protection",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isAdminActive) "Status: Protected (Device Admin Active)" else "Status: Inactive (Optional)",
                            fontSize = 12.sp,
                            color = if (isAdminActive) QuotaGreen else Color.Gray,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Why enable this? AntiBot hosts a full local Linux environment with your Node/Python packages, Git repos, and active coding projects. An accidental drag-to-uninstall from the home screen wipes all of it permanently.\n\nActivating Android Device Administrator greys out the 'Uninstall' button at the OS level across phone launchers. You can safely disable this anytime if you wish to uninstall.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))
                if (isAdminActive) {
                    OutlinedButton(
                        onClick = { showDeactivateAdminDialog = true },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Deactivate Uninstall Protection", fontSize = 13.sp)
                    }
                } else {
                    Button(
                        onClick = {
                            try {
                                val intent = AntiGemDeviceAdminReceiver.createActivationIntent(context)
                                adminLauncher.launch(intent)
                            } catch (e: Exception) {
                                Log.w("SettingsDialog", "Direct admin prompt failed, opening Device Admin settings", e)
                                try {
                                    context.startActivity(AntiGemDeviceAdminReceiver.createDeviceAdminSettingsIntent())
                                } catch (e2: Exception) {
                                    Toast.makeText(context, "Could not open Device Admin: ${e2.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen)
                    ) {
                        Icon(Icons.Outlined.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Activate Uninstall Protection", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            try {
                                context.startActivity(AntiGemDeviceAdminReceiver.createDeviceAdminSettingsIntent())
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open settings: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Open System Device Admin Apps List", fontSize = 12.5.sp)
                    }

                    if (Build.VERSION.SDK_INT >= 33) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    text = "💡 Sideloaded App Tip (Android 13+): If your phone shows 'Restricted setting', open App Info > tap the 3 dots in the top right > select 'Allow restricted settings'.",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                    lineHeight = 15.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                TextButton(
                                    onClick = {
                                        try {
                                            val appInfoIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                                data = Uri.fromParts("package", context.packageName, null)
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            context.startActivity(appInfoIntent)
                                        } catch (_: Exception) {}
                                    },
                                    modifier = Modifier.align(Alignment.End)
                                ) {
                                    Text("Open App Info", fontSize = 11.5.sp)
                                }
                            }
                        }
                    }
                }
            }
        }

        // Feature 2: Clear Data Shield (ManageSpaceActivity)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isShieldEnabled) GeminiBlue.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Outlined.CleaningServices,
                                contentDescription = null,
                                tint = if (isShieldEnabled) GeminiBlue else Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "App Info Clear Data Shield",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isShieldEnabled) "Enabled (Replaces Clear Data with Space Manager)" else "Disabled (Standard OS Clear Data)",
                            fontSize = 12.sp,
                            color = if (isShieldEnabled) GeminiBlue else Color.Gray
                        )
                    }
                    Switch(
                        checked = isShieldEnabled,
                        onCheckedChange = { enabled ->
                            ManageSpaceActivity.setShieldEnabled(context, enabled)
                            isShieldEnabled = ManageSpaceActivity.isShieldEnabled(context)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "When enabled (default), tapping 'Clear Data' in Android's phone Settings > App Info opens AntiBot's Safe Space Manager instead of wiping everything instantly with one click. This prevents accidental wipes and allows safe cache-only cleanup.\n\nYou can toggle this off anytime if you prefer the standard raw Android 'Clear Data' button.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        try {
                            context.startActivity(Intent(context, ManageSpaceActivity::class.java))
                        } catch (e: Exception) {
                            Toast.makeText(context, "Could not open Space Manager: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Storage, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Open Space Manager Preview", fontSize = 13.sp)
                }
            }
        }
    }

    if (showDeactivateAdminDialog) {
        AlertDialog(
            onDismissRequest = { showDeactivateAdminDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text("Disable Uninstall Protection?") },
            text = {
                Text(
                    "This will remove AntiBot's Device Admin status. The app will become vulnerable to accidental home-screen uninstallation.",
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeactivateAdminDialog = false
                        AntiGemDeviceAdminReceiver.deactivateAdmin(context)
                        isAdminActive = false
                        scope.launch {
                            delay(300)
                            isAdminActive = AntiGemDeviceAdminReceiver.isAdminActive(context)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Deactivate")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeactivateAdminDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ==========================================
// SUB-SCREEN 5: COMMANDS & PERMISSIONS
// ==========================================
@Composable
private fun CommandsSubScreen(
    commandAutoExecutionPolicy: String,
    commandSandboxEnabled: Boolean,
    requireApprovalForFileEdits: Boolean,
    defaultApprovalScope: String,
    globalSecuritySettings: com.example.gemini.data.remote.AgyHubClient.GlobalUserSettings?,
    globalSettingsError: String? = null,
    isGlobalSettingsLoading: Boolean,
    projectsList: List<com.example.gemini.data.remote.AgyHubClient.ProjectItem>,
    isProjectsLoading: Boolean,
    cardBg: Color,
    cardBorder: BorderStroke,
    onSetGlobalArtifactReviewMode: (String) -> Unit,
    onSetGlobalSecurityPreset: (autoExec: String, fileAccess: String) -> Unit,
    onSetGlobalCustomTerminalPolicy: (String) -> Unit,
    onSetGlobalCustomFileAccessPolicy: (String) -> Unit,
    onSetGlobalTerminalSandbox: (Boolean) -> Unit,
    onSetProjectInheritGlobal: (com.example.gemini.data.remote.AgyHubClient.ProjectItem) -> Unit,
    onSetProjectPreset: (com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String) -> Unit,
    onRefreshSecurityAndProjects: () -> Unit,
    onSetCommandAutoExecutionPolicy: (String) -> Unit,
    onSetCommandSandboxEnabled: (Boolean) -> Unit,
    onSetRequireApprovalForFileEdits: (Boolean) -> Unit,
    onSetDefaultApprovalScope: (String) -> Unit,
    onAddPermissionRule: (action: String, pattern: String, decision: String) -> Unit,
    onRemovePermissionRule: (rawRule: String) -> Unit,
    onChangePermissionRuleDecision: (rawRule: String, newDecision: String) -> Unit
) {
    if (globalSecuritySettings == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Global Security & Policies",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Live daemon settings applied across all workspaces via Jetbox",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onRefreshSecurityAndProjects,
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isGlobalSettingsLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = ClaudeTerracotta
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            if (isGlobalSettingsLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 56.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            strokeWidth = 2.5.dp,
                            color = ClaudeTerracotta
                        )
                        Text(
                            text = "Connecting to AGY Hub & loading permissions...",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = "Unable to Load Permissions from AGY Hub",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Text(
                            text = "Could not retrieve daemon permissions and security state:\n${globalSettingsError ?: "RPC connection failed"}\n\nMake sure AGY is running (e.g. agy --hub) and check your Server settings.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            lineHeight = 17.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = onRefreshSecurityAndProjects,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Retry Connection", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        return
    }

    val activeAutoExec = globalSecuritySettings.autoExecutionPolicy
    val activeFileAccess = globalSecuritySettings?.nonWorkspaceFileAccessPolicy ?: "AGENT_SETTING_POLICY_ASK"
    val activeArtifactReview = globalSecuritySettings?.artifactReviewMode ?: "ARTIFACT_REVIEW_MODE_ALWAYS"
    val activeSandbox = globalSecuritySettings?.enableTerminalSandbox ?: commandSandboxEnabled

    val isDefaultMode = activeAutoExec.contains("OFF", ignoreCase = true) && activeFileAccess.contains("ASK", ignoreCase = true)
    val isMachineMode = activeAutoExec.contains("OFF", ignoreCase = true) && activeFileAccess.contains("ALLOW", ignoreCase = true)
    val isTurboMode = activeAutoExec.contains("EAGER", ignoreCase = true) && activeFileAccess.contains("ALLOW", ignoreCase = true)
    var customExpanded by remember { mutableStateOf(!isDefaultMode && !isMachineMode && !isTurboMode) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Top Header with Sync Info & Refresh Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Global Security & Policies",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Live daemon settings applied across all workspaces via Jetbox",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onRefreshSecurityAndProjects,
                modifier = Modifier.size(36.dp)
            ) {
                if (isGlobalSettingsLoading || isProjectsLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = ClaudeTerracotta
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = ClaudeTerracotta,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // ==========================================
        // 1. ARTIFACT REVIEW POLICY
        // ==========================================
        Column {
            Text(
                text = "Artifact Review Policy",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Controls whether the assistant asks you to review and approve documents it creates.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val isAutoReview = activeArtifactReview.contains("TURBO", ignoreCase = true)
            val isAlwaysReview = activeArtifactReview.contains("ALWAYS", ignoreCase = true) || !isAutoReview

            // Auto Option
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_TURBO") },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAutoReview) ClaudeTerracotta.copy(alpha = 0.08f) else cardBg
                ),
                border = BorderStroke(
                    if (isAutoReview) 1.5.dp else 1.dp,
                    if (isAutoReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "⚡ Auto (Turbo)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAutoReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(
                            selected = isAutoReview,
                            onClick = { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_TURBO") },
                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Edits and creates documents immediately without asking.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }

            // Always Ask Option
            Card(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_ALWAYS") },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isAlwaysReview) ClaudeTerracotta.copy(alpha = 0.08f) else cardBg
                ),
                border = BorderStroke(
                    if (isAlwaysReview) 1.5.dp else 1.dp,
                    if (isAlwaysReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "✋ Always Ask",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAlwaysReview) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                        RadioButton(
                            selected = isAlwaysReview,
                            onClick = { onSetGlobalArtifactReviewMode("ARTIFACT_REVIEW_MODE_ALWAYS") },
                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Suspends and requests your approval before saving document edits.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 15.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 2. SECURITY PRESETS
        // ==========================================
        Column {
            Text(
                text = "Security Presets",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Controls terminal command execution and file access outside working directory.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        val presets = listOf(
            Triple(
                "DEFAULT",
                "🛡️ Default Mode",
                "Terminal commands require confirmation. Files outside workspace require confirmation."
            ) to ("Recommended" to Color(0xFF00ACC1)),
            Triple(
                "FULL_MACHINE",
                "💻 Full Machine Mode",
                "Terminal commands require confirmation. Full read/write access to all files on device."
            ) to ("Open Files" to Color(0xFFF59E0B)),
            Triple(
                "TURBO",
                "⚡ Turbo Mode",
                "Full auto-run: terminal commands execute immediately and all files can be accessed."
            ) to ("Full Auto" to QuotaGreen),
            Triple(
                "CUSTOM",
                "⚙️ Custom Granular Mode",
                "Configure terminal command execution and external filesystem permissions separately."
            ) to ("Custom" to Color(0xFF8B5CF6))
        )

        presets.forEach { (presetInfo, badgeInfo) ->
            val (presetKey, title, desc) = presetInfo
            val (badgeText, badgeColor) = badgeInfo
            val isSelected = when (presetKey) {
                "DEFAULT" -> isDefaultMode
                "FULL_MACHINE" -> isMachineMode
                "TURBO" -> isTurboMode
                "CUSTOM" -> customExpanded || (!isDefaultMode && !isMachineMode && !isTurboMode)
                else -> false
            }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        when (presetKey) {
                            "DEFAULT" -> {
                                customExpanded = false
                                onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ASK")
                            }
                            "FULL_MACHINE" -> {
                                customExpanded = false
                                onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ALLOW")
                            }
                            "TURBO" -> {
                                customExpanded = false
                                onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER", "AGENT_SETTING_POLICY_ALLOW")
                            }
                            "CUSTOM" -> {
                                customExpanded = true
                            }
                        }
                    },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) ClaudeTerracotta.copy(alpha = 0.08f) else cardBg
                ),
                border = BorderStroke(
                    if (isSelected) 1.5.dp else 1.dp,
                    if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = isSelected,
                            onClick = {
                                when (presetKey) {
                                    "DEFAULT" -> {
                                        customExpanded = false
                                        onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ASK")
                                    }
                                    "FULL_MACHINE" -> {
                                        customExpanded = false
                                        onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ALLOW")
                                    }
                                    "TURBO" -> {
                                        customExpanded = false
                                        onSetGlobalSecurityPreset("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER", "AGENT_SETTING_POLICY_ALLOW")
                                    }
                                    "CUSTOM" -> {
                                        customExpanded = true
                                    }
                                }
                            },
                            colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = title,
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = badgeColor.copy(alpha = 0.15f)
                        ) {
                            Text(
                                text = badgeText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = badgeColor,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = desc,
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(start = 32.dp)
                    )

                    // Granular Controls inside Custom Mode
                    if (presetKey == "CUSTOM" && isSelected) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Spacer(modifier = Modifier.height(10.dp))

                        // Granular: Terminal Execution
                        Text(
                            text = "Terminal Command Execution:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val isTermOff = activeAutoExec.contains("OFF", ignoreCase = true)
                            val isTermEager = activeAutoExec.contains("EAGER", ignoreCase = true)

                            FilterChip(
                                selected = isTermOff,
                                onClick = { onSetGlobalCustomTerminalPolicy("CASCADE_COMMANDS_AUTO_EXECUTION_OFF") },
                                label = { Text("✋ Ask Every Time", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isTermEager,
                                onClick = { onSetGlobalCustomTerminalPolicy("CASCADE_COMMANDS_AUTO_EXECUTION_EAGER") },
                                label = { Text("⚡ Auto-Run (Eager)", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Granular: Files Outside Workspace
                        Text(
                            text = "Files Outside Workspace:",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val isFileAsk = activeFileAccess.contains("ASK", ignoreCase = true)
                            val isFileAllow = activeFileAccess.contains("ALLOW", ignoreCase = true)
                            val isFileDeny = activeFileAccess.contains("DENY", ignoreCase = true)

                            FilterChip(
                                selected = isFileAsk,
                                onClick = { onSetGlobalCustomFileAccessPolicy("AGENT_SETTING_POLICY_ASK") },
                                label = { Text("✋ Ask", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isFileAllow,
                                onClick = { onSetGlobalCustomFileAccessPolicy("AGENT_SETTING_POLICY_ALLOW") },
                                label = { Text("✅ Allow", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = isFileDeny,
                                onClick = { onSetGlobalCustomFileAccessPolicy("AGENT_SETTING_POLICY_DENY") },
                                label = { Text("🚫 Deny", fontSize = 11.sp) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 3. SECURITY & SANDBOX TOGGLE
        // ==========================================
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Terminal Sandbox Isolation",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (activeSandbox) "Enabled: Isolates commands in sandbox without host network access."
                            else "Disabled (Recommended for Hub): Commands execute directly in your Termux / Linux environment.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = activeSandbox,
                        onCheckedChange = { onSetGlobalTerminalSandbox(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }

                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text(
                            text = "Review File Edits & Writes",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Prompt for confirmation before assistant applies file edits or writes code to disk.",
                            fontSize = 11.5.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 15.sp
                        )
                    }
                    Switch(
                        checked = requireApprovalForFileEdits,
                        onCheckedChange = onSetRequireApprovalForFileEdits,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = ClaudeTerracotta
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ==========================================
        // 4. GRANULAR PERMISSION GRANTS (globalPermissionGrants)
        // ==========================================
        val grants = globalSecuritySettings.globalPermissionGrants
        val allRules = remember(grants) {
            val list = mutableListOf<PermissionGrantRule>()
            grants.allow.forEach { list.add(PermissionGrantRule.parse(it, "ALLOW")) }
            grants.deny.forEach { list.add(PermissionGrantRule.parse(it, "DENY")) }
            grants.ask.forEach { list.add(PermissionGrantRule.parse(it, "ASK")) }
            list
        }

        var selectedFilter by remember { mutableStateOf("ALL") }
        var showAddRuleDialog by remember { mutableStateOf(false) }

        val filteredRules = remember(allRules, selectedFilter) {
            if (selectedFilter == "ALL") allRules
            else allRules.filter { it.action.equals(selectedFilter, ignoreCase = true) }
        }

        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Granular Permission Grants",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Explicit Allow, Deny, and Ask rules for commands, MCP tools, and file paths (globalPermissionGrants).",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { showAddRuleDialog = true },
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Add Rule", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Filter Chips
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val filters = listOf(
                    "ALL" to "All (${allRules.size})",
                    "command" to "⌨️ Command (${allRules.count { it.action.equals("command", ignoreCase = true) }})",
                    "mcp" to "🔌 MCP (${allRules.count { it.action.equals("mcp", ignoreCase = true) }})",
                    "read_file" to "📖 Read (${allRules.count { it.action.equals("read_file", ignoreCase = true) }})",
                    "write_file" to "✏️ Write (${allRules.count { it.action.equals("write_file", ignoreCase = true) }})"
                )

                filters.forEach { (key, label) ->
                    val isSel = selectedFilter == key
                    FilterChip(
                        selected = isSel,
                        onClick = { selectedFilter = key },
                        label = { Text(label, fontSize = 11.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal) }
                    )
                }
            }
        }

        if (filteredRules.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = if (allRules.isEmpty()) "No custom permission rules configured." else "No rules match the selected filter.",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Tools and file accesses will use your global presets above. Click \"Add Rule\" to whitelist, blacklist, or prompt for specific commands, MCP tools, or file paths.",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 15.sp
                    )
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                filteredRules.forEach { rule ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                                    ) {
                                        Text(
                                            text = "${rule.actionIcon} ${rule.actionLabel}",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = rule.pattern,
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                IconButton(
                                    onClick = { onRemovePermissionRule(rule.rawString) },
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.DeleteOutline,
                                        contentDescription = "Delete Rule",
                                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Decision segmented selector: Allow | Deny | Ask
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val decisions = listOf(
                                    Triple("ALLOW", "✅ Allow", QuotaGreen),
                                    Triple("DENY", "🚫 Deny", MaterialTheme.colorScheme.error),
                                    Triple("ASK", "❓ Ask", Color(0xFFF59E0B))
                                )

                                decisions.forEach { (decKey, decLabel, decColor) ->
                                    val isSelected = rule.decision.equals(decKey, ignoreCase = true)
                                    Surface(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(8.dp))
                                            .clickable {
                                                if (!isSelected) {
                                                    onChangePermissionRuleDecision(rule.rawString, decKey)
                                                }
                                            },
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isSelected) decColor.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                        border = BorderStroke(
                                            if (isSelected) 1.5.dp else 0.5.dp,
                                            if (isSelected) decColor else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                                        )
                                    ) {
                                        Box(
                                            modifier = Modifier.padding(vertical = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = decLabel,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) decColor else MaterialTheme.colorScheme.onSurfaceVariant
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

        if (showAddRuleDialog) {
            AddPermissionRuleDialog(
                onDismiss = { showAddRuleDialog = false },
                onAdd = { act, pat, dec ->
                    onAddPermissionRule(act, pat, dec)
                    showAddRuleDialog = false
                }
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // ==========================================
        // 5. PROJECT OVERRIDES & INHERITANCE (NEW!)
        // ==========================================
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Project Overrides & Inheritance",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                if (projectsList.isNotEmpty()) {
                    Text(
                        text = "${projectsList.count { it.isInheritingGlobal }}/${projectsList.size} Inheriting",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Projects can inherit the global policy above or maintain specific overrides.",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (isProjectsLoading && projectsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = ClaudeTerracotta, modifier = Modifier.size(24.dp))
            }
        } else if (projectsList.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "No workspace projects registered yet on AGY Hub.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp)
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                projectsList.forEach { project ->
                    var projectExpanded by remember { mutableStateOf(false) }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { projectExpanded = !projectExpanded },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        border = cardBorder
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = project.name.ifBlank { project.id },
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = project.id,
                                        fontSize = 10.5.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (project.isInheritingGlobal) QuotaGreen.copy(alpha = 0.15f) else Color(0xFFF59E0B).copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (project.isInheritingGlobal) "🌐 Inherits Global" else "⚠️ Custom Override",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (project.isInheritingGlobal) QuotaGreen else Color(0xFFF59E0B),
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            if (projectExpanded) {
                                Spacer(modifier = Modifier.height(10.dp))
                                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                                Spacer(modifier = Modifier.height(10.dp))

                                // Quick button to Inherit Global
                                if (!project.isInheritingGlobal) {
                                    Button(
                                        onClick = {
                                            onSetProjectInheritGlobal(project)
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = QuotaGreen)
                                    ) {
                                        Text("🌐 Set to Inherit Global Settings", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                Text(
                                    text = "Set Project Override:",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Spacer(modifier = Modifier.height(6.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            onSetProjectPreset(project, "CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ASK")
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("🛡️ Default", fontSize = 10.5.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onSetProjectPreset(project, "CASCADE_COMMANDS_AUTO_EXECUTION_OFF", "AGENT_SETTING_POLICY_ALLOW")
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("💻 Machine", fontSize = 10.5.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            onSetProjectPreset(project, "CASCADE_COMMANDS_AUTO_EXECUTION_EAGER", "AGENT_SETTING_POLICY_ALLOW")
                                            projectExpanded = false
                                        },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp)
                                    ) {
                                        Text("⚡ Turbo", fontSize = 10.5.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // ==========================================
        // 5. APPROVAL SCOPE & PERSISTENCE BANNER
        // ==========================================
        Column {
            Text(
                text = "Default Approval Scope",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "When you click approve on a tool prompt, how long should permission remain granted?",
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val scopes = listOf(
                Triple("PERMISSION_SCOPE_ONCE", "Run Once", "Single call"),
                Triple("PERMISSION_SCOPE_CONVERSATION", "This Chat", "Current session"),
                Triple("PERMISSION_SCOPE_WORKSPACE", "Workspace", "Always")
            )

            scopes.forEach { (scopeKey, title, subtitle) ->
                val isSelected = defaultApprovalScope == scopeKey
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSetDefaultApprovalScope(scopeKey) },
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) ClaudeTerracotta.copy(alpha = 0.12f) else cardBg,
                    border = BorderStroke(
                        1.5.dp,
                        if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 12.dp, horizontal = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(10.dp),
            color = QuotaGreen.copy(alpha = 0.08f),
            border = BorderStroke(1.dp, QuotaGreen.copy(alpha = 0.25f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = QuotaGreen,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = "Live synced with AGY Hub via Jetbox & Project APIs with zero local-storage desync.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    lineHeight = 16.sp
                )
            }
        }
    }
}

private data class PermissionGrantRule(
    val action: String,
    val pattern: String,
    val decision: String,
    val rawString: String
) {
    val actionLabel: String
        get() = when (action.lowercase()) {
            "command" -> "Command"
            "mcp" -> "MCP Tool"
            "read_file" -> "Read File"
            "write_file" -> "Write File"
            else -> action
        }

    val actionIcon: String
        get() = when (action.lowercase()) {
            "command" -> "⌨️"
            "mcp" -> "🔌"
            "read_file" -> "📖"
            "write_file" -> "✏️"
            else -> "⚙️"
        }

    companion object {
        fun parse(raw: String, decision: String): PermissionGrantRule {
            val parenStart = raw.indexOf('(')
            val parenEnd = raw.lastIndexOf(')')
            if (parenStart != -1 && parenEnd > parenStart) {
                val action = raw.substring(0, parenStart).trim()
                val pattern = raw.substring(parenStart + 1, parenEnd).trim()
                return PermissionGrantRule(action, pattern, decision.uppercase(), raw)
            }
            return PermissionGrantRule("command", raw, decision.uppercase(), raw)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddPermissionRuleDialog(
    onDismiss: () -> Unit,
    onAdd: (action: String, pattern: String, decision: String) -> Unit
) {
    var selectedAction by remember { mutableStateOf("command") }
    var patternText by remember { mutableStateOf("") }
    var selectedDecision by remember { mutableStateOf("ALLOW") }

    val quickTemplates = remember(selectedAction) {
        when (selectedAction) {
            "command" -> listOf("*", "npm*", "git*", "python3*", "cargo*", "rm*")
            "mcp" -> listOf("*", "local_tools/*", "filesystem/*", "test_memory/*")
            "read_file" -> listOf("*", "/home/cat/.gemini", "/opt/*", "/tmp/*")
            "write_file" -> listOf("*", "/home/cat/extra/*", "/tmp/*")
            else -> listOf("*")
        }
    }

    val actionOptions = listOf(
        Triple("command", "⌨️ Command", "Terminal bash command pattern"),
        Triple("mcp", "🔌 MCP Tool", "MCP server or tool pattern"),
        Triple("read_file", "📖 Read File", "Filesystem directory or file read path"),
        Triple("write_file", "✏️ Write File", "Filesystem directory or file write path")
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "Add Permission Grant Rule", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Step 1: Select Action
                Text("1. Operation Type", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    actionOptions.forEach { (key, label, desc) ->
                        val isSel = selectedAction == key
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    selectedAction = key
                                    if (patternText.isBlank() || patternText == "*") {
                                        patternText = if (key == "mcp" || key == "command") "*" else ""
                                    }
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) ClaudeTerracotta.copy(alpha = 0.1f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                if (isSel) 1.5.dp else 1.dp,
                                if (isSel) ClaudeTerracotta else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSel,
                                    onClick = { selectedAction = key },
                                    colors = RadioButtonDefaults.colors(selectedColor = ClaudeTerracotta),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(label, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
                                    Text(desc, fontSize = 10.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                // Step 2: Target Pattern
                Text("2. Target Pattern", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                OutlinedTextField(
                    value = patternText,
                    onValueChange = { patternText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = {
                        Text(
                            when (selectedAction) {
                                "command" -> "e.g. npm*, git*, *"
                                "mcp" -> "e.g. *, local_tools/*"
                                "read_file" -> "e.g. /home/cat/.gemini, *"
                                "write_file" -> "e.g. /home/cat/extra/*, *"
                                else -> "e.g. *"
                            },
                            fontSize = 12.sp
                        )
                    },
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
                    shape = RoundedCornerShape(10.dp)
                )

                // Quick Templates
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickTemplates.forEach { tpl ->
                        SuggestionChip(
                            onClick = { patternText = tpl },
                            label = { Text(tpl, fontSize = 10.5.sp, fontFamily = FontFamily.Monospace) }
                        )
                    }
                }

                // Step 3: Decision
                Text("3. Policy Decision", fontWeight = FontWeight.SemiBold, fontSize = 12.5.sp)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val decs = listOf(
                        Triple("ALLOW", "✅ Allow", QuotaGreen),
                        Triple("DENY", "🚫 Deny", MaterialTheme.colorScheme.error),
                        Triple("ASK", "❓ Ask", Color(0xFFF59E0B))
                    )
                    decs.forEach { (key, label, col) ->
                        val isSel = selectedDecision == key
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { selectedDecision = key },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) col.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            border = BorderStroke(
                                if (isSel) 1.5.dp else 0.5.dp,
                                if (isSel) col else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                            )
                        ) {
                            Box(modifier = Modifier.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontSize = 11.5.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium, color = if (isSel) col else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                    }
                }

                // Preview
                if (patternText.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Rule: ${selectedAction}(${patternText.trim()}) → $selectedDecision",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onAdd(selectedAction, patternText.trim(), selectedDecision) },
                enabled = patternText.isNotBlank(),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ClaudeTerracotta)
            ) {
                Text("Save Rule", fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ==========================================
// SUB-SCREEN 8: DIAGNOSTICS & PERFORMANCE
// ==========================================
@Composable
private fun DiagnosticsSubScreen(
    isFloatingDiagnosticsEnabled: Boolean,
    onToggleFloatingDiagnostics: (Boolean) -> Unit,
    isNetworkInspectorEnabled: Boolean,
    onToggleNetworkInspector: (Boolean) -> Unit,
    isFloatingNetworkInspectorEnabled: Boolean,
    onToggleFloatingNetworkInspector: (Boolean) -> Unit,
    onOpenNetworkInspector: () -> Unit,
    cardBg: Color,
    cardBorder: BorderStroke
) {
    DisposableEffect(isFloatingDiagnosticsEnabled) {
        com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.start()
        onDispose {
            if (!isFloatingDiagnosticsEnabled) {
                com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.stop()
            }
        }
    }

    val snapshot by com.example.gemini.data.remote.core.AntiGemLiveDiagnostics.snapshot.collectAsState()
    val logs by com.example.gemini.data.remote.inspector.NetworkInspectorManager.logs.collectAsState()
    val activeStreams by com.example.gemini.data.remote.inspector.NetworkInspectorManager.activeStreamsCount.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var pendingInspectorRestart by remember { mutableStateOf<Boolean?>(null) }

    if (pendingInspectorRestart != null) {
        val targetState = pendingInspectorRestart!!
        AlertDialog(
            onDismissRequest = { pendingInspectorRestart = null },
            title = {
                Text(
                    text = "Restart Required",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Text(
                    text = if (targetState) {
                        "Enabling Network Inspector requires restarting the app so OkHttp can attach live routing interceptors. Restart now?"
                    } else {
                        "Disabling Network Inspector requires restarting the app to restore 100% native network performance with zero debug interceptors. Restart now?"
                    },
                    fontSize = 13.5.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val toEnable = targetState
                        pendingInspectorRestart = null
                        com.example.gemini.data.preferences.AuthPreferences(context).saveNetworkInspectorEnabledSync(toEnable)
                        val pm = context.packageManager
                        val intent = pm.getLaunchIntentForPackage(context.packageName)
                        if (intent != null) {
                            val restartIntent = android.content.Intent.makeRestartActivityTask(intent.component)
                            context.startActivity(restartIntent)
                            Runtime.getRuntime().exit(0)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Restart App", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingInspectorRestart = null }
                ) {
                    Text("Cancel")
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section 1: Network Inspector Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = (if (isNetworkInspectorEnabled) Color(0xFF0284C7) else Color.Gray).copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Language,
                                    contentDescription = null,
                                    tint = if (isNetworkInspectorEnabled) Color(0xFF0284C7) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Network Inspector (Live Logs)",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isNetworkInspectorEnabled) Color(0xFF0284C7).copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isNetworkInspectorEnabled) "ACTIVE" else "OFF",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isNetworkInspectorEnabled) Color(0xFF0284C7) else Color.Gray,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Inspect IDE Bridge HTTP & AGY Daemon gRPC calls in real-time",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Switch(
                        checked = isNetworkInspectorEnabled,
                        onCheckedChange = { targetState ->
                            pendingInspectorRestart = targetState
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF0284C7)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Zero-overhead architecture: When disabled, OkHttp calls execute at 100% native speed with zero allocations or buffering.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                    lineHeight = 16.sp
                )

                if (isNetworkInspectorEnabled) {
                    Spacer(modifier = Modifier.height(14.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.8.dp)
                    Spacer(modifier = Modifier.height(14.dp))

                    // Floating Inspector Bubble toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Floating Network Bubble",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Show on-screen draggable pill with live call count & streams",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                        Switch(
                            checked = isFloatingNetworkInspectorEnabled,
                            onCheckedChange = onToggleFloatingNetworkInspector,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF0284C7)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Open Inspector Button
                    Button(
                        onClick = onOpenNetworkInspector,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0284C7)
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Open Network Inspector (${logs.size} calls recorded)",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }
        }
        // Toggle Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = (if (isFloatingDiagnosticsEnabled) QuotaGreen else Color.Gray).copy(alpha = 0.12f),
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Outlined.Speed,
                                    contentDescription = null,
                                    tint = if (isFloatingDiagnosticsEnabled) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Floating Diagnostics HUD",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = if (isFloatingDiagnosticsEnabled) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                                ) {
                                    Text(
                                        text = if (isFloatingDiagnosticsEnabled) "VISIBLE" else "DISABLED",
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isFloatingDiagnosticsEnabled) QuotaGreen else Color.Gray,
                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                                    )
                                }
                            }
                            Text(
                                text = "Live on-screen thread, queue & connection monitor",
                                fontSize = 11.5.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                    Switch(
                        checked = isFloatingDiagnosticsEnabled,
                        onCheckedChange = onToggleFloatingDiagnostics,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = QuotaGreen
                        )
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Displays a draggable, real-time floating status pill showing active JVM threads, running and queued gRPC/HTTP calls, connection pool socket counts, and main-thread IO latency. Tap the floating pill anytime to expand detailed metrics.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                    lineHeight = 17.sp
                )
            }
        }

        // Live Snapshot Metric Preview Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Current Telemetry Snapshot",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), thickness = 0.8.dp)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Active JVM Threads:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${snapshot.activeJvmThreads} threads",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "AgyHub Stream / RPCs:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "Run=${snapshot.agyRunning} | Q=${snapshot.agyQueued}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (snapshot.agyQueued > 0) Color(0xFFFF5252) else MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "AgyHub Connection Pool:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${snapshot.agyConns} sockets (${snapshot.agyIdleConns} idle)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "IDE Bridge Sockets:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = "${snapshot.ideConns} sockets (${snapshot.ideIdleConns} idle)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Event Loop IO Latency:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    Text(
                        text = if (snapshot.ioLagMs >= 0) "${snapshot.ioLagMs} ms" else "ERR",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = if (snapshot.isLagging) Color(0xFFFF5252) else QuotaGreen
                    )
                }

                if (snapshot.threadGroupSummary.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Thread Breakdown:",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = snapshot.threadGroupSummary,
                            fontSize = 10.5.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AboutSubScreen(
    cardBg: Color,
    cardBorder: BorderStroke
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdate by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<AppUpdateInfo?>(null) }
    var updateCheckResultText by remember { mutableStateOf<String?>(null) }
    var isUpdateCheckError by remember { mutableStateOf(false) }

    // Bridge file tracking in public Downloads directory
    val downloadDir = remember { Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) }
    val bridgeFile = remember { File(downloadDir, "agy_ide_bridge") }
    var bridgeExists by remember { mutableStateOf(bridgeFile.exists() && bridgeFile.length() > 0) }
    var bridgeSize by remember { mutableStateOf(if (bridgeFile.exists()) bridgeFile.length() else 0L) }
    var bridgeLastModified by remember { mutableStateOf(if (bridgeFile.exists()) bridgeFile.lastModified() else 0L) }
    var isExportingBridge by remember { mutableStateOf(false) }

    fun refreshBridgeStatus() {
        val f = File(downloadDir, "agy_ide_bridge")
        bridgeExists = f.exists() && f.length() > 0
        bridgeSize = if (f.exists()) f.length() else 0L
        bridgeLastModified = if (f.exists()) f.lastModified() else 0L
    }

    if (updateInfo != null) {
        AppUpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { updateInfo = null }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. App Header & Branding Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFF8B5CF6).copy(alpha = 0.15f),
                    border = BorderStroke(1.5.dp, Color(0xFF8B5CF6).copy(alpha = 0.4f)),
                    modifier = Modifier.size(64.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Color(0xFF8B5CF6),
                            modifier = Modifier.size(32.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "AntiBot",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Autonomous AI Pair Programmer & Development Workspace",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = Color(0xFF8B5CF6).copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, Color(0xFF8B5CF6).copy(alpha = 0.3f))
                ) {
                    Text(
                        text = "Version ${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        color = Color(0xFF8B5CF6),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // 2. Package & Environment Details
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Build & Package Information",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Application ID",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = BuildConfig.APPLICATION_ID,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Build Type",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = BuildConfig.BUILD_TYPE.uppercase(Locale.ROOT),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Native Architecture",
                        fontSize = 12.5.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // 3. Updates Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.SystemUpdateAlt,
                            contentDescription = null,
                            tint = ClaudeTerracotta,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "Software Updates",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Text(
                    text = "Checks GitHub releases directly for new commits and updates. Supports in-app daemon sync (no APK install required) and in-place APK updates.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                if (updateCheckResultText != null) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUpdateCheckError) Color(0xFFFF5252).copy(alpha = 0.12f) else QuotaGreen.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, if (isUpdateCheckError) Color(0xFFFF5252).copy(alpha = 0.3f) else QuotaGreen.copy(alpha = 0.3f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = if (isUpdateCheckError) Icons.Default.Warning else Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = if (isUpdateCheckError) Color(0xFFFF5252) else QuotaGreen,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = updateCheckResultText!!,
                                fontSize = 12.sp,
                                color = if (isUpdateCheckError) Color(0xFFFF5252) else QuotaGreen
                            )
                        }
                    }
                }

                Button(
                    onClick = {
                        if (!isCheckingUpdate) {
                            isCheckingUpdate = true
                            updateCheckResultText = null
                            isUpdateCheckError = false
                            coroutineScope.launch {
                                val updateManager = AppUpdateManager(context)
                                val checkResult = updateManager.checkForUpdates(forceCheck = true)
                                isCheckingUpdate = false
                                checkResult.onSuccess { info ->
                                    if (info != null) {
                                        updateInfo = info
                                        updateCheckResultText = null
                                    } else {
                                        isUpdateCheckError = false
                                        updateCheckResultText = "AntiBot is up to date (v${BuildConfig.VERSION_NAME})."
                                        Toast.makeText(context, "AntiBot is up to date!", Toast.LENGTH_SHORT).show()
                                    }
                                }.onFailure { err ->
                                    isUpdateCheckError = true
                                    val msg = err.localizedMessage ?: "Could not connect to GitHub"
                                    updateCheckResultText = msg
                                    Toast.makeText(context, "Update check failed: $msg", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    enabled = !isCheckingUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ClaudeTerracotta,
                        contentColor = ClaudeTerracottaOnPrimary
                    )
                ) {
                    if (isCheckingUpdate) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = ClaudeTerracottaOnPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Checking for Updates...", fontSize = 12.sp)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Check for Updates", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        // 4. AGY IDE Bridge Server & Downloads Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.FolderZip,
                            contentDescription = null,
                            tint = Color(0xFF00ACC1),
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "AGY IDE Bridge Binary",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = if (bridgeExists) QuotaGreen.copy(alpha = 0.15f) else Color.Gray.copy(alpha = 0.15f)
                    ) {
                        Text(
                            text = if (bridgeExists) "IN DOWNLOADS" else "NOT EXPORTED",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (bridgeExists) QuotaGreen else Color.Gray,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Text(
                    text = "Export the compiled agy_ide_bridge daemon binary to your device's public Download folder to execute in custom chroots, Termux environments, or external terminals.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Path: ${bridgeFile.absolutePath}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
                        )
                        if (bridgeExists) {
                            val sizeKb = bridgeSize / 1024
                            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(bridgeLastModified))
                            Text(
                                text = "Size: $sizeKb KB • Modified: $dateStr",
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = QuotaGreen
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            isExportingBridge = true
                            coroutineScope.launch(Dispatchers.IO) {
                                val exported = LocalServerManager.saveBridgeToDownloads(context)
                                withContext(Dispatchers.Main) {
                                    isExportingBridge = false
                                    refreshBridgeStatus()
                                    if (exported != null && exported.exists()) {
                                        Toast.makeText(context, "Saved agy_ide_bridge to Downloads!", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Failed to export bridge binary", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        },
                        enabled = !isExportingBridge,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00ACC1))
                    ) {
                        if (isExportingBridge) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Export to Downloads", fontSize = 12.5.sp)
                        }
                    }

                    if (bridgeExists) {
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Downloads: ${bridgeFile.absolutePath}", Toast.LENGTH_LONG).show()
                                }
                            },
                            modifier = Modifier.weight(0.7f),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF00ACC1).copy(alpha = 0.5f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.OpenInNew,
                                contentDescription = null,
                                tint = Color(0xFF00ACC1),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("View File", fontSize = 12.5.sp, color = Color(0xFF00ACC1))
                        }
                    }
                }
            }
        }

        // 5. GitHub & External Links
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = cardBg),
            border = cardBorder
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Resources & Repository",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/onebotyt/Antibot"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Code,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "GitHub Repository",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "github.com/onebotyt/Antibot",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/onebotyt/Antibot/releases"))
                            context.startActivity(intent)
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(20.dp)
                            )
                            Column {
                                Text(
                                    text = "Release Changelogs",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "View tag notes & APK downloads",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            imageVector = Icons.Default.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}
