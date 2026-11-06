package com.example.gemini.ui.bubble

import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.AppViewMode
import com.example.gemini.MainActivity
import com.example.gemini.assistant.actions.DeviceActionDispatcher
import com.example.gemini.assistant.overlay.AssistantToolType
import com.example.gemini.assistant.overlay.MultiToolBubblePalette
import com.example.gemini.assistant.overlay.ScreenDrawCanvasOverlay
import com.example.gemini.assistant.share.UniversalShareDialog
import com.example.gemini.assistant.tasks.SmartTaskPanel
import com.example.gemini.assistant.vision.AntiBotAccessibilityService
import com.example.gemini.assistant.voice.FloatingCaptionOverlay
import com.example.gemini.assistant.voice.HumanVoiceEngine
import com.example.gemini.theme.ClaudeTerracotta
import com.example.gemini.theme.GeminiTheme
import com.example.gemini.theme.isSystemInDarkThemeRobust
import com.example.gemini.ui.browser.BrowserScreen
import com.example.gemini.ui.chat.ChatScreen
import com.example.gemini.ui.chat.ChatViewModel
import com.example.gemini.ui.chat.ChatViewModelHolder
import com.example.gemini.ui.components.LocalTerminalContent
import com.example.gemini.ui.ide.IdeScreen

enum class AssistantWindowMode {
    PALETTE,
    WORKSPACE,
    DRAW_SCREEN,
    SMART_TASKS,
    SHARE,
    DEVICE_ACTIONS
}

class FloatingChatActivity : ComponentActivity() {

    private val chatViewModel: ChatViewModel by lazy { ChatViewModelHolder.get(application) }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        overridePendingTransition(0, 0)
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))

        setContent {
            val initialThemeMode = remember { chatViewModel.authPreferences.getThemeModeSync() }
            val themeMode by chatViewModel.themeMode.collectAsState(initial = initialThemeMode)
            val isSystemDark = isSystemInDarkThemeRobust()
            val useDarkTheme = when (themeMode) {
                "DARK" -> true
                "LIGHT" -> false
                else -> isSystemDark
            }

            GeminiTheme(darkTheme = useDarkTheme) {
                FloatingAssistantHost(
                    viewModel = chatViewModel,
                    onOpenFullApp = {
                        val intent = Intent(this, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                        finish()
                        overridePendingTransition(0, 0)
                    },
                    onMinimize = {
                        finish()
                    }
                )
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(0, 0)
    }
}

@Composable
fun FloatingAssistantHost(
    viewModel: ChatViewModel,
    onOpenFullApp: () -> Unit,
    onMinimize: () -> Unit
) {
    val context = LocalContext.current
    val voiceEngine = remember { HumanVoiceEngine.getInstance(context) }
    val captionState by voiceEngine.captionState.collectAsState()

    var assistantMode by rememberSaveable { mutableStateOf(AssistantWindowMode.PALETTE) }

    BackHandler(enabled = assistantMode != AssistantWindowMode.PALETTE) {
        assistantMode = AssistantWindowMode.PALETTE
    }

    BackHandler(enabled = assistantMode == AssistantWindowMode.PALETTE) {
        onMinimize()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
    ) {
        when (assistantMode) {
            AssistantWindowMode.PALETTE -> {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onMinimize
                        )
                ) {
                    MultiToolBubblePalette(
                        isExpanded = true,
                        onSelectTool = { tool ->
                            when (tool) {
                                AssistantToolType.DRAW_SCREEN -> {
                                    assistantMode = AssistantWindowMode.DRAW_SCREEN
                                }
                                AssistantToolType.READ_SCREEN -> {
                                    val screenText = AntiBotAccessibilityService.extractVisibleScreenText()
                                    if (screenText.isBlank()) {
                                        Toast.makeText(context, "No screen text detected. Verify AntiBot Accessibility service is turned ON.", Toast.LENGTH_LONG).show()
                                        voiceEngine.speak("No text detected on screen. Please ensure AntiBot Accessibility is enabled.")
                                    } else {
                                        voiceEngine.speak("Reading visible screen content. Analyzing with AI.")
                                        viewModel.sendMessage("Here is what is currently visible on my phone screen:\n\n$screenText\n\nPlease summarize and explain what is on screen.")
                                        assistantMode = AssistantWindowMode.WORKSPACE
                                    }
                                }
                                AssistantToolType.SMART_TASKS -> {
                                    assistantMode = AssistantWindowMode.SMART_TASKS
                                }
                                AssistantToolType.DEVICE_ACTIONS -> {
                                    assistantMode = AssistantWindowMode.DEVICE_ACTIONS
                                }
                                AssistantToolType.UNIVERSAL_SHARE -> {
                                    assistantMode = AssistantWindowMode.SHARE
                                }
                                AssistantToolType.CHAT_WINDOW -> {
                                    assistantMode = AssistantWindowMode.WORKSPACE
                                }
                                AssistantToolType.VOICE_ASSISTANT -> {
                                    voiceEngine.speak("AntiBot Voice Assistant is ready. How can I help you?")
                                    assistantMode = AssistantWindowMode.WORKSPACE
                                }
                            }
                        },
                        onDismiss = onMinimize
                    )
                }
            }

            AssistantWindowMode.DRAW_SCREEN -> {
                ScreenDrawCanvasOverlay(
                    onDismiss = { assistantMode = AssistantWindowMode.PALETTE },
                    onQueryAi = { query, text ->
                        voiceEngine.speak("Analyzing selected area: " + text.take(120))
                        viewModel.sendMessage(query)
                        assistantMode = AssistantWindowMode.WORKSPACE
                    }
                )
            }

            AssistantWindowMode.SMART_TASKS -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { assistantMode = AssistantWindowMode.PALETTE }
                        )
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.background,
                        tonalElevation = 8.dp,
                        shadowElevation = 20.dp,
                        modifier = Modifier
                            .fillMaxWidth(0.96f)
                            .fillMaxHeight(0.88f)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {}
                            )
                    ) {
                        SmartTaskPanel(
                            onClose = { assistantMode = AssistantWindowMode.PALETTE }
                        )
                    }
                }
            }

            AssistantWindowMode.SHARE -> {
                UniversalShareDialog(
                    onDismiss = { assistantMode = AssistantWindowMode.PALETTE }
                )
            }

            AssistantWindowMode.DEVICE_ACTIONS -> {
                DeviceQuickActionsDialog(
                    onDismiss = { assistantMode = AssistantWindowMode.PALETTE }
                )
            }

            AssistantWindowMode.WORKSPACE -> {
                FloatingChatWindow(
                    viewModel = viewModel,
                    onOpenFullApp = onOpenFullApp,
                    onMinimize = onMinimize,
                    onOpenPalette = { assistantMode = AssistantWindowMode.PALETTE }
                )
            }
        }

        // Live Floating Speech Subtitle Captions
        FloatingCaptionOverlay(
            captionState = captionState,
            onStopSpeech = { voiceEngine.stop() },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
fun DeviceQuickActionsDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.FlashOn,
                        contentDescription = null,
                        tint = Color(0xFFEAB308),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Quick Device Actions",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(18.dp))
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                ActionItem(
                    icon = Icons.Default.Alarm,
                    title = "Set 8:00 AM Alarm",
                    onClick = {
                        DeviceActionDispatcher.setAlarm(context, 8, 0, "AntiBot Morning Alarm")
                        onDismiss()
                    }
                )

                ActionItem(
                    icon = Icons.Default.Timer,
                    title = "Start 5 Min Timer",
                    onClick = {
                        DeviceActionDispatcher.setTimer(context, 300, "5 Min Timer")
                        onDismiss()
                    }
                )

                ActionItem(
                    icon = Icons.Default.FlashlightOn,
                    title = "Toggle Flashlight",
                    onClick = {
                        DeviceActionDispatcher.toggleFlashlight(context, true)
                        onDismiss()
                    }
                )

                ActionItem(
                    icon = Icons.Default.Phone,
                    title = "Open Phone Dialer",
                    onClick = {
                        scope.launch {
                            DeviceActionDispatcher.makeCall(context, "")
                            onDismiss()
                        }
                    }
                )

                ActionItem(
                    icon = Icons.Default.Sms,
                    title = "Send SMS Message",
                    onClick = {
                        scope.launch {
                            DeviceActionDispatcher.sendSms(context, "", "")
                            onDismiss()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ActionItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(12.dp)
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = ClaudeTerracotta, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold))
        }
    }
}

@Composable
fun FloatingChatWindow(
    viewModel: ChatViewModel,
    onOpenFullApp: () -> Unit,
    onMinimize: () -> Unit,
    onOpenPalette: () -> Unit = {}
) {
    var currentViewMode by rememberSaveable { mutableStateOf(AppViewMode.CHAT) }
    var hasEverOpenedTerminal by rememberSaveable { mutableStateOf(false) }

    if (currentViewMode == AppViewMode.TERMINAL) {
        hasEverOpenedTerminal = true
    }

    BackHandler(enabled = currentViewMode == AppViewMode.IDE || currentViewMode == AppViewMode.TERMINAL) {
        currentViewMode = AppViewMode.CHAT
    }

    BackHandler(enabled = currentViewMode == AppViewMode.CHAT) {
        onOpenPalette()
    }

    val density = LocalDensity.current
    val imeInsets = WindowInsets.ime
    val navInsets = WindowInsets.navigationBars
    val isKeyboardOpen by remember(imeInsets, density) {
        derivedStateOf { imeInsets.getBottom(density) > navInsets.getBottom(density) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { onMinimize() }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    top = 8.dp,
                    bottom = if (isKeyboardOpen) 0.dp else 16.dp
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = if (isKeyboardOpen) {
                    Modifier
                        .fillMaxWidth(0.98f)
                        .weight(1f)
                } else {
                    Modifier
                        .fillMaxWidth(0.98f)
                        .fillMaxHeight(0.92f)
                }
            ) {
                // Multitasking Floating Window Card
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {} // Consume clicks inside card
                        ),
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.background,
                    tonalElevation = 0.dp,
                    shadowElevation = 18.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // Persistent Floating Chat Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.CHAT
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else -20000f
                                }
                        ) {
                            ChatScreen(
                                viewModel = viewModel,
                                isInFloatingWindow = true,
                                onOpenFullScreen = onOpenFullApp,
                                onMinimizeWindow = onMinimize,
                                onNavigateToIde = { currentViewMode = AppViewMode.IDE },
                                onNavigateToTerminal = { currentViewMode = AppViewMode.TERMINAL },
                                onNavigateToBrowser = { currentViewMode = AppViewMode.BROWSER }
                            )
                        }

                        // Persistent Floating IDE Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.IDE
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else 20000f
                                }
                        ) {
                            IdeScreen(
                                viewModel = viewModel,
                                isVisible = currentViewMode == AppViewMode.IDE,
                                onNavigateToChat = { currentViewMode = AppViewMode.CHAT },
                                onExecuteRunCommand = {},
                                onNavigateToTerminal = { currentViewMode = AppViewMode.TERMINAL },
                                onNavigateToBrowser = { currentViewMode = AppViewMode.BROWSER }
                            )
                        }

                        // Persistent Floating Terminal Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.TERMINAL
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else 20000f
                                }
                        ) {
                            val terminalSessions by com.example.gemini.data.local.LocalTerminalManager.sessions.collectAsState()
                            if (currentViewMode == AppViewMode.TERMINAL || (hasEverOpenedTerminal && terminalSessions.isNotEmpty())) {
                                LocalTerminalContent(
                                    onClose = { currentViewMode = AppViewMode.CHAT }
                                )
                            }
                        }

                        // Persistent Floating Browser Screen
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val isVisible = currentViewMode == AppViewMode.BROWSER
                                    alpha = if (isVisible) 1f else 0f
                                    translationX = if (isVisible) 0f else 20000f
                                }
                        ) {
                            BrowserScreen(
                                isVisible = currentViewMode == AppViewMode.BROWSER,
                                onClose = { currentViewMode = AppViewMode.CHAT }
                            )
                        }

                        // Top Palette Switcher Floating Button
                        IconButton(
                            onClick = onOpenPalette,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Apps,
                                contentDescription = "Tools Palette",
                                tint = ClaudeTerracotta,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
