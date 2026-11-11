package com.example.gemini.ui.chat

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.gemini.data.preferences.AuthPreferences
import com.example.gemini.data.remote.AgyHubClient
import com.example.gemini.data.remote.AntigravityApiService
import com.example.gemini.data.remote.GoogleOAuthManager
import com.example.gemini.data.remote.PromptExpansion
import com.example.gemini.data.remote.StreamEvent
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.AiModel
import com.example.gemini.domain.model.ChatMessage
import com.example.gemini.domain.model.ChatAttachment
import com.example.gemini.domain.model.Conversation
import com.example.gemini.domain.model.MessageRole
import com.example.gemini.domain.model.ModelQuota
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import com.example.gemini.data.daemon.GitApiClient
import com.example.gemini.data.daemon.GitCommitLog
import android.util.Log
import org.json.JSONObject
import java.util.UUID

data class PendingToolApproval(
    val toolCall: com.example.gemini.domain.model.ToolCall,
    val messageId: String
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    val authPreferences = AuthPreferences(application)
    private val authPrefs get() = authPreferences
    private val apiService = AntigravityApiService()
    private val store = com.example.gemini.data.agent.ChatSessionStore()
    val agyBackend: com.example.gemini.data.agent.agy.AgyChatBackend = com.example.gemini.data.agent.agy.AgyChatBackend(
        application = application,
        backendScope = viewModelScope,
        authPrefs = authPrefs,
        store = store,
        isNetworkConnectedProvider = { isNetworkConnected() },
        onHubOnline = { loadMcpServers() }
    )
    private val mcpManager = com.example.gemini.data.agent.agy.AgyMcpManager(viewModelScope, agyBackend.agyHubClient)
    private val pluginsManager = com.example.gemini.data.agent.agy.AgyPluginsManager(viewModelScope, agyBackend.agyHubClient, store, mcpManager)
    private val securityManager = com.example.gemini.data.agent.agy.AgySecuritySettingsManager(viewModelScope, agyBackend.agyHubClient, authPrefs, store)
    private val agyHubClient get() = agyBackend.agyHubClient
    private val agyBridgeService get() = agyBackend.agyBridgeService

    // ==================== AGENT ROUTING (Antigravity / Local) ====================

    /** Agent of the conversation on screen; chat actions go to that agent's backend. */
    val activeAgent: StateFlow<AgentKind> = store.currentConversation
        .map { it?.agent ?: AgentKind.AGY }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AgentKind.AGY)

    val localBackend = com.example.gemini.data.agent.local.LocalAgentBackend(application, viewModelScope, store)
    val isLocalActive get() = store.currentConversation.value?.agent == AgentKind.LOCAL

    val claudePrefs = com.example.gemini.data.agent.claude.ClaudePreferences(application)
    private val claudeClient = com.example.gemini.data.agent.claude.ClaudeBridgeClient()
    val claudeBackend = com.example.gemini.data.agent.claude.ClaudeChatBackend(application, viewModelScope, store, claudePrefs, claudeClient)
    val claudeAccount = com.example.gemini.data.agent.claude.ClaudeAccountManager(viewModelScope, claudeClient)
    val claudeConfig = com.example.gemini.data.agent.claude.ClaudeConfigManager(viewModelScope, claudeClient)
    val claudeVoice = com.example.gemini.data.agent.claude.ClaudeVoiceTranscriber(claudeClient) { claudePrefs.voiceLanguage.value }

    val openCodePrefs = com.example.gemini.data.agent.opencode.OpenCodePreferences(application)
    val openCodeBackend = com.example.gemini.data.agent.opencode.OpenCodeChatBackend(application, viewModelScope, store, openCodePrefs)

    val codexPrefs = com.example.gemini.data.agent.codex.CodexPreferences(application)
    val codexBackend = com.example.gemini.data.agent.codex.CodexChatBackend(application, viewModelScope, store, codexPrefs)

    val isClaudeActive get() = store.currentConversation.value?.agent == AgentKind.CLAUDE
    val isOpenCodeActive get() = store.currentConversation.value?.agent == AgentKind.OPENCODE
    val isCodexActive get() = store.currentConversation.value?.agent == AgentKind.CODEX

    private val agentPrefs = com.example.gemini.data.agent.AgentPreferences(application)
    /** Agent used for new chats, picked on the empty chat screen (remembered). */
    val newChatAgent: StateFlow<AgentKind> = agentPrefs.newChatAgent

    /** Agents the app runs. */
    val enabledAgents: StateFlow<Set<AgentKind>> = agentPrefs.enabled
    /** The first-launch agent choice was made. */
    val hasChosenAgents: StateFlow<Boolean> = agentPrefs.hasChosen

    private val _isApplyingAgents = MutableStateFlow(false)
    /** The bridge is restarting for a new agent selection. */
    val isApplyingAgents: StateFlow<Boolean> = _isApplyingAgents.asStateFlow()

    /**
     * Applies an agent selection: saves it, leaves a chat of a disabled
     * agent, and restarts the bridge when its flags change.
     */
    fun applyAgents(agents: Set<AgentKind>) {
        if (agents.isEmpty()) return
        val before = enabledAgents.value
        val firstChoice = !hasChosenAgents.value
        agentPrefs.setEnabled(agents)
        if (newChatAgent.value !in agents) agentPrefs.setNewChatAgent(if (AgentKind.AGY in agents) AgentKind.AGY else agents.first())
        val cur = store.currentConversation.value
        if (cur == null || cur.agent !in agents) startNewChat()
        if (firstChoice) {
            // first launch: the server waited for this choice and now starts with it
            com.example.gemini.data.local.LocalServerManager.autoStartOnAppLaunch(getApplication())
        } else if (before != agents) {
            // stop the server completely, then restart the app: it starts the server again with the new agents
            viewModelScope.launch {
                _isApplyingAgents.value = true
                runCatching { com.example.gemini.data.local.LocalServerManager.stopServerCompletely() }
                val app = getApplication<Application>()
                app.packageManager.getLaunchIntentForPackage(app.packageName)?.component?.let { component ->
                    app.startActivity(android.content.Intent.makeRestartActivityTask(component))
                }
                Runtime.getRuntime().exit(0)
            }
        }
    }

    private fun agentOf(conversationId: String): AgentKind =
        conversations.value.find { it.id == conversationId }?.agent
            ?: store.currentConversation.value?.takeIf { it.id == conversationId }?.agent
            ?: AgentKind.AGY

    fun setNewChatAgent(agent: AgentKind) {
        agentPrefs.setNewChatAgent(agent)
        val cur = store.currentConversation.value
        val isFreshChat = cur == null || (cur.title == "New Chat" && messages.value.isEmpty())
        if (isFreshChat && cur?.agent != agent) startNewChat()
    }

    // ==================== ANTIGRAVITY (AGY) — delegated to data/agent/agy ====================

    val systemConnectionState = agyBackend.systemConnectionState
    val hubStatus = agyBackend.hubStatus
    val trajectoryEngine = agyBackend.trajectoryEngine
    val speechManager = agyBackend.speechManager
    val hasReceivedInitialSync = agyBackend.hasReceivedInitialSync
    val isConversationsLoading = agyBackend.isConversationsLoading
    val artifacts = agyBackend.artifacts
    val pendingApprovals = agyBackend.pendingApprovals
    val isStreaming = agyBackend.isStreaming
    val availableModels = agyBackend.availableModels
    val enabledModelIds = agyBackend.enabledModelIds
    val enabledModels = agyBackend.enabledModels
    val isRefreshingModels = agyBackend.isRefreshingModels
    private val _isAutoRouting = MutableStateFlow(true)
    val isAutoRouting: StateFlow<Boolean> = _isAutoRouting.asStateFlow()

    private val _activeRoutingDecision = MutableStateFlow<com.example.gemini.domain.model.SmartRoutingDecision?>(null)
    val activeRoutingDecision: StateFlow<com.example.gemini.domain.model.SmartRoutingDecision?> = _activeRoutingDecision.asStateFlow()

    val selectedModelId: StateFlow<String> = kotlinx.coroutines.flow.combine(
        store.currentConversation,
        localBackend.selectedModelId,
        agyBackend.selectedModelId,
        _isAutoRouting
    ) { conv, localModel, agyModel, isAuto ->
        if (isAuto) {
            com.example.gemini.domain.model.SmartModelRouter.AUTO_MODEL_ID
        } else if (conv?.agent == AgentKind.LOCAL || isLocalActive) {
            localModel
        } else {
            agyModel
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, com.example.gemini.domain.model.SmartModelRouter.AUTO_MODEL_ID)
    val preferredModelName = agyBackend.preferredModelName
    val quotas = agyBackend.quotas
    val quotaSummary = agyBackend.quotaSummary
    val isLoadingConversation = agyBackend.isLoadingConversation
    fun setModelEnabled(modelId: String, isEnabled: Boolean) = agyBackend.setModelEnabled(modelId, isEnabled)
    fun enableAllModels() = agyBackend.enableAllModels()
    val bridgeStatusMessage = agyBackend.bridgeStatusMessage
    val isServerOnline = agyBackend.isServerOnline
    val isReconnecting = agyBackend.isReconnecting
    val isBridgeOnline = agyBackend.isBridgeOnline
    fun checkBridgeHealth() = agyBackend.checkBridgeHealth()
    val agyAuthInfo = agyBackend.agyAuthInfo
    val isAuthBusy = agyBackend.isAuthBusy
    val authFeedbackMessage = agyBackend.authFeedbackMessage
    val pendingLoginUrl = agyBackend.pendingLoginUrl
    fun clearPendingLoginUrl() = agyBackend.clearPendingLoginUrl()
    val agyLoginIssue = agyBackend.agyLoginIssue
    fun clearAgyLoginIssue() = agyBackend.clearAgyLoginIssue()
    fun onDrawerOpened() = agyBackend.onDrawerOpened()
    fun cancelAgyLogin() = agyBackend.cancelAgyLogin()
    fun checkAgyAuthStatus(userInitiated: Boolean = false) = agyBackend.checkAgyAuthStatus(userInitiated)
    fun loginToAgyHub(force: Boolean = false) {
        com.example.gemini.data.local.LocalServerManager.startServer(getApplication(), forceRestart = false)
        agyBackend.loginToAgyHub(force)
    }
    fun logoutFromAgyHub() = agyBackend.logoutFromAgyHub()
    val activeInstances = agyBackend.activeInstances
    val isAnyGenerationOrTaskActive = agyBackend.isAnyGenerationOrTaskActive
    val isCurrentChatActivelyRunning = agyBackend.isCurrentChatActivelyRunning
    val isCurrentChatBackgroundActive = agyBackend.isCurrentChatBackgroundActive
    val connectionState = agyBackend.connectionState

    private val _queuedMessages = MutableStateFlow<Map<String, List<com.example.gemini.domain.model.QueuedMessage>>>(emptyMap())
    val queuedMessages: StateFlow<List<com.example.gemini.domain.model.QueuedMessage>> = kotlinx.coroutines.flow.combine(
        store.currentConversation,
        _queuedMessages
    ) { conv, map ->
        val key = conv?.id.orEmpty()
        map[key].orEmpty()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            var wasRunning = false
            isCurrentChatActivelyRunning.collect { isRunning ->
                if (wasRunning && !isRunning) {
                    val convId = currentConversation.value?.id.orEmpty()
                    if (convId.isNotEmpty()) {
                        var nextMsg: com.example.gemini.domain.model.QueuedMessage? = null
                        _queuedMessages.update { map ->
                            val list = map[convId].orEmpty()
                            if (list.isNotEmpty()) {
                                nextMsg = list.first()
                                map + (convId to list.drop(1))
                            } else {
                                map
                            }
                        }
                        nextMsg?.let { msg ->
                            kotlinx.coroutines.delay(200)
                            sendMessage(msg.text)
                        }
                    }
                }
                wasRunning = isRunning
            }
        }
    }

    fun enqueueMessage(conversationId: String, text: String, attachmentCount: Int = 0) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachmentCount == 0) return
        val id = java.util.UUID.randomUUID().toString()
        val msg = com.example.gemini.domain.model.QueuedMessage(
            id = id,
            text = trimmed,
            attachmentCount = attachmentCount,
            canSendNow = true,
            canDelete = true,
            isPending = false
        )
        _queuedMessages.update { map ->
            val list = map[conversationId].orEmpty() + msg
            map + (conversationId to list)
        }
    }

    fun editQueuedMessage(conversationId: String, id: String): String? {
        var textFound: String? = null
        _queuedMessages.update { map ->
            val list = map[conversationId].orEmpty()
            val target = list.firstOrNull { it.id == id }
            textFound = target?.text
            map + (conversationId to list.filter { it.id != id })
        }
        return textFound
    }

    fun deleteQueuedMessage(conversationId: String, id: String) {
        _queuedMessages.update { map ->
            val list = map[conversationId].orEmpty()
            map + (conversationId to list.filter { it.id != id })
        }
    }

    fun sendImmediately(conversationId: String, id: String) {
        var targetMsg: com.example.gemini.domain.model.QueuedMessage? = null
        _queuedMessages.update { map ->
            val list = map[conversationId].orEmpty()
            targetMsg = list.firstOrNull { it.id == id }
            map + (conversationId to list.filter { it.id != id })
        }
        val msg = targetMsg ?: return

        stopStreaming()

        val steeringPrompt = "⚡ [IMMEDIATE STEERING DIRECTIVE]\n" +
            "The user interrupted ongoing work to issue this command immediately:\n" +
            "\"${msg.text}\"\n\n" +
            "First, think and determine whether this directive is related to the work you were just doing (a correction, tweak, or continuation) or if it is an entirely new task. State your determination briefly, then act accordingly."

        viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            sendMessage(steeringPrompt)
        }
    }

    fun refreshActiveInstances() = agyBackend.refreshActiveInstances()
    fun terminateInstance(conversationId: String) = agyBackend.terminateInstance(conversationId)
    fun syncAgyConversations(force: Boolean = false) = agyBackend.syncAgyConversations(force)
    fun retryConnections() {
        agyBackend.retryConnections()
    }
    fun onAppForegrounded() {
        agyBackend.onAppForegrounded()
    }
    fun onProjectChanged(projectPath: String) = agyBackend.onProjectChanged(projectPath)
    fun onUserStartedTyping() = agyBackend.onUserStartedTyping()
    fun startNewChat() = startNewChat(
        newChatAgent.value.takeIf { it in enabledAgents.value }
            ?: if (AgentKind.AGY in enabledAgents.value) AgentKind.AGY else AgentKind.LOCAL
    )

    /** New chat with [agent], without changing the new-chat default. */
    fun startNewChat(agent: AgentKind) {
        when (agent) {
            AgentKind.LOCAL -> {
                agyBackend.detach(); claudeBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                localBackend.startNewChat()
            }
            AgentKind.AGY -> {
                localBackend.detach(); claudeBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                agyBackend.startNewChat()
            }
            AgentKind.CLAUDE -> {
                agyBackend.detach(); localBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                claudeBackend.startNewChat()
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach(); localBackend.detach(); claudeBackend.detach(); codexBackend.detach()
                openCodeBackend.startNewChat()
            }
            AgentKind.CODEX -> {
                agyBackend.detach(); localBackend.detach(); claudeBackend.detach(); openCodeBackend.detach()
                codexBackend.startNewChat()
            }
        }
    }
    fun selectConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.LOCAL -> {
                agyBackend.detach(); claudeBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                localBackend.selectConversation(id)
            }
            AgentKind.AGY -> {
                localBackend.detach(); claudeBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                agyBackend.selectConversation(id)
            }
            AgentKind.CLAUDE -> {
                agyBackend.detach(); localBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                claudeBackend.selectConversation(id)
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach(); localBackend.detach(); claudeBackend.detach(); codexBackend.detach()
                openCodeBackend.selectConversation(id)
            }
            AgentKind.CODEX -> {
                agyBackend.detach(); localBackend.detach(); claudeBackend.detach(); openCodeBackend.detach()
                codexBackend.selectConversation(id)
            }
        }
    }
    fun startPersistentStream(conversationId: String) = agyBackend.startPersistentStream(conversationId)
    fun deleteConversation(id: String) = when (agentOf(id)) {
        AgentKind.LOCAL -> localBackend.deleteConversation(id)
        AgentKind.AGY -> agyBackend.deleteConversation(id)
        AgentKind.CLAUDE -> claudeBackend.deleteConversation(id)
        AgentKind.OPENCODE -> openCodeBackend.deleteConversation(id)
        AgentKind.CODEX -> codexBackend.deleteConversation(id)
    }
    fun forkConversation(id: String) {
        when (agentOf(id)) {
            AgentKind.LOCAL -> {
                agyBackend.detach(); claudeBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                localBackend.forkConversation(id)
            }
            AgentKind.AGY -> {
                localBackend.detach(); claudeBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                agyBackend.forkConversation(id)
            }
            AgentKind.CLAUDE -> {
                agyBackend.detach(); localBackend.detach(); openCodeBackend.detach(); codexBackend.detach()
                claudeBackend.forkConversation(id)
            }
            AgentKind.OPENCODE -> {
                agyBackend.detach(); localBackend.detach(); claudeBackend.detach(); codexBackend.detach()
                openCodeBackend.forkConversation(id)
            }
            AgentKind.CODEX -> {
                agyBackend.detach(); localBackend.detach(); claudeBackend.detach(); openCodeBackend.detach()
                codexBackend.forkConversation(id)
            }
        }
    }
    fun selectModel(modelId: String) {
        if (com.example.gemini.domain.model.SmartModelRouter.isAuto(modelId)) {
            _isAutoRouting.value = true
            viewModelScope.launch {
                authPrefs.savePreferredModel(
                    com.example.gemini.domain.model.SmartModelRouter.AUTO_MODEL_ID,
                    displayName = com.example.gemini.domain.model.SmartModelRouter.AUTO_DISPLAY_NAME
                )
            }
            return
        }
        _isAutoRouting.value = false
        if (com.example.gemini.domain.model.LocalCodingDomain.isLocalModel(modelId)) {
            if (!isLocalActive) {
                startNewChat(AgentKind.LOCAL)
            }
            localBackend.selectModel(modelId)
            return
        }
        if (isLocalActive) {
            startNewChat(AgentKind.AGY)
        }
        agyBackend.selectModel(modelId)
    }

    fun getDynamicRoutingDecision(draftText: String): com.example.gemini.domain.model.SmartRoutingDecision {
        val config = com.example.gemini.domain.model.LlmManagerConfig(
            architectureModel = authPrefs.getLlmManagerArchitectureModelSync(),
            codingModel = authPrefs.getLlmManagerCodingModelSync(),
            fastQaModel = authPrefs.getLlmManagerFastQaModelSync(),
            shellModel = authPrefs.getLlmManagerShellModelSync(),
            fallbackModel = authPrefs.getLlmManagerFallbackModelSync(),
            preferredCli = authPrefs.getLlmManagerPreferredCliSync()
        )
        // Synchronous fallback (used in non-suspend contexts)
        return com.example.gemini.domain.model.SmartModelRouter.route(
            prompt = draftText,
            hasAttachments = store.attachments.value.isNotEmpty(),
            isOnline = isNetworkConnected() && agyBackend.systemConnectionState.value.canSend,
            availableModels = enabledModels.value,
            quotas = quotas.value,
            currentSelectedModelId = if (_isAutoRouting.value) com.example.gemini.domain.model.SmartModelRouter.AUTO_MODEL_ID else selectedModelId.value,
            config = config
        )
    }

    /** Bridge-accelerated routing decision — prefers Rust bridge, falls back to in-process. */
    suspend fun getDynamicRoutingDecisionAsync(draftText: String): com.example.gemini.domain.model.SmartRoutingDecision {
        val config = com.example.gemini.domain.model.LlmManagerConfig(
            architectureModel = authPrefs.getLlmManagerArchitectureModelSync(),
            codingModel = authPrefs.getLlmManagerCodingModelSync(),
            fastQaModel = authPrefs.getLlmManagerFastQaModelSync(),
            shellModel = authPrefs.getLlmManagerShellModelSync(),
            fallbackModel = authPrefs.getLlmManagerFallbackModelSync(),
            preferredCli = authPrefs.getLlmManagerPreferredCliSync()
        )
        return com.example.gemini.domain.model.SmartModelRouter.routeViaBridge(
            prompt = draftText,
            hasAttachments = store.attachments.value.isNotEmpty(),
            isOnline = isNetworkConnected() && agyBackend.systemConnectionState.value.canSend,
            availableModels = enabledModels.value,
            quotas = quotas.value,
            currentSelectedModelId = if (_isAutoRouting.value) com.example.gemini.domain.model.SmartModelRouter.AUTO_MODEL_ID else selectedModelId.value,
            config = config
        )
    }


    /** `/plu:` plugin skills and custom slash commands are expanded here, for all agents. */
    fun sendMessage(content: String) = PromptExpansion.expand(content).let { expanded ->
        val moveIntent = com.example.gemini.domain.project.ChatProjectMover.parseMoveChatIntent(expanded)
        if (moveIntent != null && moveIntent.isMatch) {
            handleMoveChatViaIntent(expanded, moveIntent)
            return@let
        }
        val vcIntent = com.example.gemini.domain.project.ChatVersionControlParser.parseIntent(expanded)
        if (vcIntent != null) {
            handleVersionControlViaIntent(expanded, vcIntent)
            return@let
        }
        val skillIntent = com.example.gemini.domain.project.ChatSkillParser.parseIntent(expanded)
        if (skillIntent != null) {
            handleSkillViaIntent(expanded, skillIntent)
            return@let
        }
        if (_isAutoRouting.value || com.example.gemini.domain.model.SmartModelRouter.isAuto(selectedModelId.value)) {
            val decision = getDynamicRoutingDecision(expanded)
            _activeRoutingDecision.value = decision
            when (decision.targetAgent) {
                AgentKind.LOCAL -> {
                    if (!isLocalActive) {
                        startNewChat(AgentKind.LOCAL)
                    }
                    localBackend.selectModel(decision.targetModelId)
                    localBackend.sendMessage(expanded)
                }
                AgentKind.AGY -> {
                    if (isLocalActive || isClaudeActive || isOpenCodeActive || isCodexActive) {
                        startNewChat(AgentKind.AGY)
                    }
                    agyBackend.selectModel(decision.targetModelId)
                    agyBackend.sendMessage(expanded)
                }
                AgentKind.CLAUDE -> {
                    if (!isClaudeActive) {
                        startNewChat(AgentKind.CLAUDE)
                    }
                    claudeBackend.selectModel(decision.targetModelId)
                    claudeBackend.sendMessage(expanded)
                }
                AgentKind.OPENCODE -> {
                    if (!isOpenCodeActive) {
                        startNewChat(AgentKind.OPENCODE)
                    }
                    openCodeBackend.selectModel(decision.targetModelId)
                    openCodeBackend.sendMessage(expanded)
                }
                AgentKind.CODEX -> {
                    if (!isCodexActive) {
                        startNewChat(AgentKind.CODEX)
                    }
                    codexBackend.selectModel(decision.targetModelId)
                    codexBackend.sendMessage(expanded)
                }
            }
        } else {
            when {
                isLocalActive -> localBackend.sendMessage(expanded)
                isClaudeActive -> claudeBackend.sendMessage(expanded)
                isOpenCodeActive -> openCodeBackend.sendMessage(expanded)
                isCodexActive -> codexBackend.sendMessage(expanded)
                else -> agyBackend.sendMessage(expanded)
            }
        }
    }

    private fun handleMoveChatViaIntent(prompt: String, intent: com.example.gemini.domain.project.MoveChatIntent) {
        val conv = _currentConversation.value ?: return
        val userMsg = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.USER,
            content = prompt,
            createdAt = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        viewModelScope.launch(Dispatchers.IO) {
            val result = if (intent.isNewProject || !intent.projectName.isNullOrBlank()) {
                com.example.gemini.domain.project.ChatProjectMover.createProjectAndMoveChat(
                    projectName = intent.projectName,
                    conversation = conv,
                    messages = _messages.value
                )
            } else {
                com.example.gemini.domain.project.ChatProjectMover.createProjectAndMoveChat(
                    projectName = conv.title,
                    conversation = conv,
                    messages = _messages.value
                )
            }

            if (result.success) {
                val updatedConv = conv.copy(
                    workspaceUri = "file://${result.projectItem.path}",
                    updatedAt = System.currentTimeMillis()
                )
                _currentConversation.value = updatedConv
                onProjectChanged(result.projectItem.path)
                com.example.gemini.data.daemon.TermuxDaemonManager.setActiveProject(result.projectItem)
            }

            val responseMarkdown = if (result.success) {
                """
                📁 **Project Workspace Ready & Chat Moved!**

                - **Project**: `${result.projectItem.name}`
                - **Workspace Path**: `${result.projectItem.path}`
                - **Exported Documentation**:
                ${result.filesWritten.joinToString("\n") { "  - `$it`" }}

                *The active workspace has been updated to `${result.projectItem.path}`. All future commands, dev servers, and file edits will run inside this project workspace.*
                """.trimIndent()
            } else {
                "⚠️ **Could not move chat to project**: ${result.message}"
            }

            val assistantMsg = ChatMessage(
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = responseMarkdown,
                thoughtText = "Detected request to move conversation into project '${result.projectItem.name}'. Created project structure, exported PROJECT_CONTEXT.md and docs/CONVERSATION_HISTORY.md, and updated active workspace.",
                createdAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.Main) {
                _messages.value = _messages.value + assistantMsg
            }
        }
    }

    private fun handleVersionControlViaIntent(prompt: String, intent: com.example.gemini.domain.project.VersionControlIntent) {
        val conv = _currentConversation.value ?: return
        val userMsg = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.USER,
            content = prompt,
            createdAt = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        val convPath = conv.workspaceUri?.removePrefix("file://")?.trimEnd('/')
        val activeProjPath = convPath?.takeIf { it.isNotBlank() } ?: com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path
        if (activeProjPath.isNullOrBlank()) {
            val assistantMsg = ChatMessage(
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = "⚠️ **No active project workspace found.** Please open or create a project first using the 'Move to Project' (📁) button in the top bar before using local version control.",
                createdAt = System.currentTimeMillis()
            )
            _messages.value = _messages.value + assistantMsg
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            val (replyMarkdown, thought) = when (intent) {
                is com.example.gemini.domain.project.VersionControlIntent.SaveCheckpoint -> {
                    val finalMsg = if (intent.message.isNotBlank() && intent.message != "Local Checkpoint") {
                        intent.message
                    } else {
                        "Checkpoint " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    }
                    val res = GitApiClient.saveCheckpoint(activeProjPath, finalMsg)
                    if (res.success) {
                        val commitSha = res.commit?.shortHash ?: "HEAD"
                        val md = """
                        🔖 **Local Version Checkpoint Saved!**

                        - **Project**: `${java.io.File(activeProjPath).name}`
                        - **Message**: `${finalMsg}`
                        - **Commit SHA**: `${commitSha}`
                        - **Timestamp**: `${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}`

                        *Your workspace state has been committed locally without GitHub. You can rollback to this checkpoint anytime by asking 'rollback' or using the Version Control icon (🕒) in the top bar.*
                        """.trimIndent()
                        Pair(md, "Created local version checkpoint: $finalMsg")
                    } else {
                        Pair("⚠️ **Could not save checkpoint**: ${res.error}", "Checkpoint creation failed")
                    }
                }

                is com.example.gemini.domain.project.VersionControlIntent.Rollback -> {
                    val targetHash = intent.targetHash
                    if (targetHash != null) {
                        val res = GitApiClient.rollback(activeProjPath, targetHash, hard = true)
                        if (res.success) {
                            val md = """
                            ⏪ **Workspace Rolled Back!**

                            - **Target Version**: `${targetHash}`
                            - **Project**: `${java.io.File(activeProjPath).name}`

                            *All project files have been restored to commit `${targetHash}`.*
                            """.trimIndent()
                            Pair(md, "Rolled back to commit $targetHash")
                        } else {
                            Pair("⚠️ **Rollback failed**: ${res.error}", "Rollback failed")
                        }
                    } else {
                        val logs = GitApiClient.getLog(activeProjPath, limit = 2)
                        if (logs.size < 2) {
                            Pair("⚠️ **Cannot rollback**: There is only 1 commit or no prior commit to roll back to.", "Rollback aborted: no parent commit")
                        } else {
                            val parent = logs[1]
                            val res = GitApiClient.rollback(activeProjPath, parent.hash, hard = true)
                            if (res.success) {
                                val md = """
                                ⏪ **Workspace Rolled Back to Previous Version!**

                                - **Restored Version**: `${parent.shortHash}` — *${parent.message}*
                                - **Project**: `${java.io.File(activeProjPath).name}`
                                - **Date**: `${parent.date}`

                                *All project files have been restored to the previous commit.*
                                """.trimIndent()
                                Pair(md, "Rolled back to previous version ${parent.shortHash}")
                            } else {
                                Pair("⚠️ **Rollback failed**: ${res.error}", "Rollback failed")
                            }
                        }
                    }
                }

                is com.example.gemini.domain.project.VersionControlIntent.ShowHistory -> {
                    val logs = GitApiClient.getLog(activeProjPath, limit = 8)
                    if (logs.isEmpty()) {
                        Pair("📁 **No commit history found for `${java.io.File(activeProjPath).name}`.** Ask me to 'save checkpoint' to create your first local version!", "No commits")
                    } else {
                        val rows = logs.joinToString("\n") { c: GitCommitLog ->
                            "| `${c.shortHash}` | **${c.message}** | `${c.author.ifBlank { "Local" }}` | ${c.date} |"
                        }
                        val md = """
                        📜 **Local Version History for `${java.io.File(activeProjPath).name}`**

                        | SHA | Message | Author | Date |
                        | :--- | :--- | :--- | :--- |
                        $rows

                        *Tap the Version Control icon (🕒) in the top bar to inspect visual file diffs or rollback to any version.*
                        """.trimIndent()
                        Pair(md, "Displayed local commit history")
                    }
                }
            }

            val assistantMsg = ChatMessage(
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = replyMarkdown,
                thoughtText = thought,
                createdAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.Main) {
                _messages.value = _messages.value + assistantMsg
            }
        }
    }

    private fun handleSkillViaIntent(prompt: String, intent: com.example.gemini.domain.project.ChatSkillIntent) {
        val conv = _currentConversation.value ?: return
        val userMsg = ChatMessage(
            conversationId = conv.id,
            role = MessageRole.USER,
            content = prompt,
            createdAt = System.currentTimeMillis()
        )
        _messages.value = _messages.value + userMsg

        val convPath = conv.workspaceUri?.removePrefix("file://")?.trimEnd('/')
        val activeProjPath = convPath?.takeIf { it.isNotBlank() } ?: com.example.gemini.data.daemon.TermuxDaemonManager.activeProject.value?.path

        viewModelScope.launch(Dispatchers.IO) {
            val (replyMarkdown, thought) = when (intent) {
                is com.example.gemini.domain.project.ChatSkillIntent.SearchSkills -> {
                    val results = com.example.gemini.data.skills.AgenticSkillsRepository.searchSkills(intent.query, limit = 8)
                    if (results.isEmpty()) {
                        Pair("🔍 **No skills found matching `${intent.query}`.** Try terms like `debugging`, `android`, `flutter`, `security`, or `architecture`.", "No skills match")
                    } else {
                        val rows = results.joinToString("\n") { s ->
                            "| **`${s.name}`** | `${s.category}` | `${s.risk}` | ${s.description.take(75)}... |"
                        }
                        val md = """
                        🧠 **Found ${results.size} Agentic Skills for `${intent.query}`**

                        | Skill Name | Category | Risk | Description |
                        | :--- | :--- | :--- | :--- |
                        $rows

                        *To install a skill to your project, ask: `install skill <name>` or open the Skills Hub (🧠) in the top bar.*
                        """.trimIndent()
                        Pair(md, "Searched skills for '${intent.query}'")
                    }
                }

                is com.example.gemini.domain.project.ChatSkillIntent.InstallSkill -> {
                    val target = if (!activeProjPath.isNullOrBlank()) {
                        com.example.gemini.data.skills.SkillInstallTarget.PROJECT
                    } else {
                        com.example.gemini.data.skills.SkillInstallTarget.GEMINI
                    }
                    val res = com.example.gemini.data.skills.AgenticSkillsRepository.installSkill(intent.skillId, target, activeProjPath)
                    if (res.success) {
                        val targetLoc = if (target == com.example.gemini.data.skills.SkillInstallTarget.PROJECT) {
                            "Active Project (`${java.io.File(activeProjPath!!).name}/.agents/skills/${intent.skillId}`)"
                        } else {
                            "Global Antigravity (`~/.gemini/config/skills/${intent.skillId}`)"
                        }
                        val md = """
                        ⚡ **Agentic Skill Installed!**

                        - **Skill**: `${intent.skillId}`
                        - **Target**: $targetLoc

                        *All AI models and CLI agents can now automatically execute the `${intent.skillId}` playbook.*
                        """.trimIndent()
                        Pair(md, "Installed skill ${intent.skillId}")
                    } else {
                        Pair("⚠️ **Could not install skill**: ${res.message}", "Skill install failed")
                    }
                }

                is com.example.gemini.domain.project.ChatSkillIntent.InstallBundle -> {
                    val target = if (!activeProjPath.isNullOrBlank()) {
                        com.example.gemini.data.skills.SkillInstallTarget.PROJECT
                    } else {
                        com.example.gemini.data.skills.SkillInstallTarget.GEMINI
                    }
                    val res = com.example.gemini.data.skills.AgenticSkillsRepository.installBundle(intent.bundleName, target, activeProjPath)
                    if (res.success) {
                        val md = """
                        📦 **Skill Bundle Installed!**

                        - **Bundle**: `${intent.bundleName}`
                        - **Result**: ${res.message}

                        *All skills in this suite are ready for immediate use.*
                        """.trimIndent()
                        Pair(md, "Installed bundle ${intent.bundleName}")
                    } else {
                        Pair("⚠️ **Could not install bundle**: ${res.message}", "Bundle install failed")
                    }
                }

                is com.example.gemini.domain.project.ChatSkillIntent.ListInstalledSkills -> {
                    val targetDir = if (!activeProjPath.isNullOrBlank()) {
                        java.io.File(activeProjPath, ".agents/skills")
                    } else {
                        java.io.File("/data/user/0/com.termux/files/home/.gemini/config/skills")
                    }
                    val installed = com.example.gemini.data.skills.AgenticSkillsRepository.listInstalled(targetDir)
                    if (installed.isEmpty()) {
                        Pair("📁 **No skills installed in `${targetDir.name}` yet.** Ask me to `search skills <topic>` or `install bundle essentials` to get started!", "No installed skills")
                    } else {
                        val listMd = installed.joinToString("\n") { "  - `💡 $it`" }
                        val md = """
                        📋 **Installed Skills in `${targetDir.absolutePath}` (${installed.size})**

                        $listMd

                        *Tap the Skills icon (🧠) in the top bar to browse 2,955+ additional skills.*
                        """.trimIndent()
                        Pair(md, "Listed installed skills")
                    }
                }

                is com.example.gemini.domain.project.ChatSkillIntent.ListBundles -> {
                    val bundles = com.example.gemini.data.skills.AgenticSkillsRepository.listBundles()
                    val rows = bundles.take(10).joinToString("\n") { b ->
                        "| **`${b.displayName}`** | `${b.skillCount}` | ${b.description.take(70)}... |"
                    }
                    val md = """
                    📦 **Agentic Awesome Skills Bundles (${bundles.size} Total)**

                    | Bundle Name | Skills Count | Description |
                    | :--- | :--- | :--- |
                    $rows

                    *To install a bundle, ask: `install bundle <name>` or open the Skills Hub (🧠) in the top bar.*
                    """.trimIndent()
                    Pair(md, "Listed skill bundles")
                }
            }

            val assistantMsg = ChatMessage(
                conversationId = conv.id,
                role = MessageRole.ASSISTANT,
                content = replyMarkdown,
                thoughtText = thought,
                createdAt = System.currentTimeMillis()
            )

            withContext(Dispatchers.Main) {
                _messages.value = _messages.value + assistantMsg
            }
        }
    }

    fun moveChatToProject(
        targetProject: com.example.gemini.data.daemon.ProjectItem,
        onCompleted: ((com.example.gemini.domain.project.ProjectExportResult) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = _currentConversation.value
            val msgs = _messages.value
            val result = com.example.gemini.domain.project.ChatProjectMover.exportChatToProject(
                projectDir = java.io.File(targetProject.path),
                conversation = conv,
                messages = msgs,
                customProjectName = targetProject.name
            )
            if (result.success) {
                val updatedConv = conv?.copy(
                    workspaceUri = if (targetProject.path.startsWith("file://")) targetProject.path else "file://${targetProject.path}",
                    updatedAt = System.currentTimeMillis()
                )
                if (updatedConv != null) {
                    _currentConversation.value = updatedConv
                }
                onProjectChanged(targetProject.path)
                com.example.gemini.data.daemon.TermuxDaemonManager.setActiveProject(targetProject)
            }
            withContext(Dispatchers.Main) {
                onCompleted?.invoke(result)
            }
        }
    }

    fun createProjectAndMoveChat(
        projectName: String,
        onCompleted: ((com.example.gemini.domain.project.ProjectExportResult) -> Unit)? = null
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val conv = _currentConversation.value
            val msgs = _messages.value
            val result = com.example.gemini.domain.project.ChatProjectMover.createProjectAndMoveChat(
                projectName = projectName,
                conversation = conv,
                messages = msgs
            )
            if (result.success) {
                val updatedConv = conv?.copy(
                    workspaceUri = "file://${result.projectItem.path}",
                    updatedAt = System.currentTimeMillis()
                )
                if (updatedConv != null) {
                    _currentConversation.value = updatedConv
                }
                onProjectChanged(result.projectItem.path)
                com.example.gemini.data.daemon.TermuxDaemonManager.setActiveProject(result.projectItem)
            }
            withContext(Dispatchers.Main) {
                onCompleted?.invoke(result)
            }
        }
    }
    val isTranscribingAudio = agyBackend.isTranscribingAudio
    fun transcribeAudioFile(file: java.io.File, onDone: (String) -> Unit, onError: (String) -> Unit) = agyBackend.transcribeAudioFile(file, onDone, onError)
    fun retryMessage(messageId: String) = when {
        isLocalActive -> Unit
        else -> agyBackend.retryMessage(messageId)
    }
    fun prepareEditMessage(messageId: String): String? {
        if (isLocalActive) {
            return null
        }
        return agyBackend.prepareEditMessage(messageId)?.let(PromptExpansion::collapse)
    }
    fun revertAndEditLastUserMessage(targetMsg: ChatMessage, onRestored: (String) -> Unit) = agyBackend.revertAndEditLastUserMessage(targetMsg) { onRestored(PromptExpansion.collapse(it)) }

    fun renameConversation(id: String, title: String) {
        when (agentOf(id)) {
            AgentKind.LOCAL -> localBackend.renameConversation(id, title)
            AgentKind.CLAUDE -> claudeBackend.renameConversation(id, title)
            AgentKind.OPENCODE -> openCodeBackend.renameConversation(id, title)
            AgentKind.CODEX -> codexBackend.renameConversation(id, title)
            AgentKind.AGY -> Unit
        }
    }
    fun stopStreaming() = when {
        isClaudeActive -> claudeBackend.stopStreaming()
        isOpenCodeActive -> openCodeBackend.stopStreaming()
        isCodexActive -> codexBackend.stopStreaming()
        isLocalActive -> localBackend.stopStreaming()
        else -> agyBackend.stopStreaming()
    }
    fun applyQuotaSummary(summary: com.example.gemini.domain.model.QuotaSummaryResponse) = agyBackend.applyQuotaSummary(summary)
    fun refreshQuotas(force: Boolean = true, showToastFeedback: Boolean = false) {
        agyBackend.refreshQuotas(force, showToastFeedback)
    }
    fun approveAndExecuteTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, scope: String? = null) = agyBackend.approveAndExecuteTerminalTool(toolCall, messageId, scope)
    fun rejectTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, reason: String? = null) = agyBackend.rejectTerminalTool(toolCall, messageId, reason)
    fun approveAllPendingTools(list: List<PendingToolApproval>) = agyBackend.approveAllPendingTools(list)
    fun rejectAllPendingTools(list: List<PendingToolApproval>, reason: String? = null) = agyBackend.rejectAllPendingTools(list, reason)
    fun terminateRunningTerminalTool(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = agyBackend.terminateRunningTerminalTool(toolCall, messageId)
    fun proceedAfterTermination(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, userInstructions: String?) {
        agyBackend.proceedAfterTermination(toolCall, messageId, userInstructions)
    }
    fun submitUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto>, summaryDisplay: String) = agyBackend.submitUserChoices(toolCall, messageId, responses, summaryDisplay)
    fun skipUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String, responses: List<com.example.gemini.data.remote.dto.AskQuestionResponseItemDto> = emptyList()) = agyBackend.skipUserChoices(toolCall, messageId, responses)
    fun cancelUserChoices(toolCall: com.example.gemini.domain.model.ToolCall, messageId: String) = agyBackend.cancelUserChoices(toolCall, messageId)

    val mcpServers = mcpManager.mcpServers
    val isMcpLoading = mcpManager.isMcpLoading
    val isMcpRefreshing = mcpManager.isMcpRefreshing
    val refreshingMcpServer = mcpManager.refreshingMcpServer
    val mcpErrorMessage = mcpManager.mcpErrorMessage
    val mcpStatusMessage = mcpManager.mcpStatusMessage
    fun clearMcpStatus() = mcpManager.clearMcpStatus()
    val availableCascadePlugins = mcpManager.availableCascadePlugins
    val isCascadePluginsLoading = mcpManager.isCascadePluginsLoading
    val installingCascadePluginId = mcpManager.installingCascadePluginId
    fun loadMcpServers() = mcpManager.loadMcpServers()
    fun refreshMcpServers(targetServer: String? = null) = mcpManager.refreshMcpServers(targetServer)
    fun toggleMcpServer(serverName: String, enabled: Boolean) = mcpManager.toggleMcpServer(serverName, enabled)
    fun saveMcpServer(spec: com.example.gemini.domain.model.McpServerSpec, rawJsonString: String? = null) = mcpManager.saveMcpServer(spec, rawJsonString)
    fun deleteMcpServer(serverName: String) = mcpManager.deleteMcpServer(serverName)
    fun loadAvailableCascadePlugins(query: String = "") = mcpManager.loadAvailableCascadePlugins(query)
    fun installCascadeMcpPlugin(plugin: com.example.gemini.data.remote.dto.AvailableCascadePluginDto) = mcpManager.installCascadeMcpPlugin(plugin)

    val allSkills = pluginsManager.allSkills
    val isSkillsLoading = pluginsManager.isSkillsLoading
    val skillsFilterScope = pluginsManager.skillsFilterScope
    val installedPlugins = pluginsManager.installedPlugins
    val isInstalledPluginsLoading = pluginsManager.isInstalledPluginsLoading
    val googlePluginsCatalog = pluginsManager.googlePluginsCatalog
    val isGooglePluginsLoading = pluginsManager.isGooglePluginsLoading
    val installingGooglePluginId = pluginsManager.installingGooglePluginId
    val deletingPluginId = pluginsManager.deletingPluginId
    val pluginActionStatusMessage = pluginsManager.pluginActionStatusMessage
    val pluginActionErrorMessage = pluginsManager.pluginActionErrorMessage
    fun clearPluginActionStatus() = pluginsManager.clearPluginActionStatus()
    fun setSkillsFilterScope(scope: String) = pluginsManager.setSkillsFilterScope(scope)
    fun loadAllSkills(scope: String = skillsFilterScope.value) = pluginsManager.loadAllSkills(scope)
    fun loadAllInstalledPlugins() = pluginsManager.loadAllInstalledPlugins()
    fun loadGooglePluginsCatalog() = pluginsManager.loadGooglePluginsCatalog()
    fun installGooglePlugin(pluginId: String, pluginName: String = pluginId) = pluginsManager.installGooglePlugin(pluginId, pluginName)
    fun deleteInstalledPlugin(pluginId: String, pluginName: String = pluginId) = pluginsManager.deleteInstalledPlugin(pluginId, pluginName)

    val globalSecuritySettings = securityManager.globalSecuritySettings
    val globalSettingsError = securityManager.globalSettingsError
    val isGlobalSettingsLoading = securityManager.isGlobalSettingsLoading
    val projectsList = securityManager.projectsList
    val isProjectsLoading = securityManager.isProjectsLoading
    fun loadSecurityAndProjectSettings() = securityManager.loadSecurityAndProjectSettings()
    fun addGlobalPermissionGrant(action: String, pattern: String, decision: String) = securityManager.addGlobalPermissionGrant(action, pattern, decision)
    fun removeGlobalPermissionGrant(rawRule: String) = securityManager.removeGlobalPermissionGrant(rawRule)
    fun changeGlobalPermissionGrantDecision(rawRule: String, newDecision: String) = securityManager.changeGlobalPermissionGrantDecision(rawRule, newDecision)
    fun updateGlobalArtifactReviewMode(mode: String) = securityManager.updateGlobalArtifactReviewMode(mode)
    fun updateGlobalSecurityPreset(autoExec: String, fileAccess: String) = securityManager.updateGlobalSecurityPreset(autoExec, fileAccess)
    fun updateGlobalCustomTerminalPolicy(policy: String) = securityManager.updateGlobalCustomTerminalPolicy(policy)
    fun updateGlobalCustomFileAccessPolicy(policy: String) = securityManager.updateGlobalCustomFileAccessPolicy(policy)
    fun updateGlobalTerminalSandbox(enabled: Boolean) = securityManager.updateGlobalTerminalSandbox(enabled)
    fun setProjectInheritGlobal(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem) = securityManager.setProjectInheritGlobal(project)
    fun updateProjectPreset(project: com.example.gemini.data.remote.AgyHubClient.ProjectItem, autoExec: String, fileAccess: String, artifactReview: String? = null) = securityManager.updateProjectPreset(project, autoExec, fileAccess, artifactReview)
    fun setCommandAutoExecutionPolicy(policy: String) = securityManager.setCommandAutoExecutionPolicy(policy)
    fun setCommandSandboxEnabled(enabled: Boolean) = securityManager.setCommandSandboxEnabled(enabled)

    private val oauthManager = GoogleOAuthManager()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _conversations = store.conversations
    /** Sidebar list: All enabled agents' conversations, newest first. */
    val conversations: StateFlow<List<Conversation>> = combine(
        combine(
            _conversations,
            localBackend.conversations,
            claudeBackend.conversations,
            openCodeBackend.conversations,
            codexBackend.conversations
        ) { agy, local, claude, opencode, codex ->
            listOf(
                AgentKind.AGY to agy,
                AgentKind.LOCAL to local,
                AgentKind.CLAUDE to claude,
                AgentKind.OPENCODE to opencode,
                AgentKind.CODEX to codex
            )
        },
        enabledAgents
    ) { pairs, enabled ->
        val list = mutableListOf<Conversation>()
        for ((agent, convs) in pairs) {
            if (agent in enabled) list.addAll(convs)
        }
        list.sortedByDescending { it.updatedAt }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allConversations: StateFlow<List<Conversation>> get() = conversations

    private val _currentConversation = store.currentConversation
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _sharedConversationPreview = MutableStateFlow<com.example.gemini.ui.components.SharedConversationData?>(null)
    val sharedConversationPreview: StateFlow<com.example.gemini.ui.components.SharedConversationData?> = _sharedConversationPreview.asStateFlow()

    private val _isSharedConversationLoading = MutableStateFlow(false)
    val isSharedConversationLoading: StateFlow<Boolean> = _isSharedConversationLoading.asStateFlow()

    private val _incomingMarkdownPreview = MutableStateFlow<Pair<String, String>?>(null)
    val incomingMarkdownPreview: StateFlow<Pair<String, String>?> = _incomingMarkdownPreview.asStateFlow()

    val requestedViewMode = MutableStateFlow<String?>(null)

    fun requestViewMode(mode: String) {
        requestedViewMode.value = mode
    }

    fun consumeRequestedViewMode() {
        requestedViewMode.value = null
    }

    /** Antigravity's chat state (shared store). */
    private val _messages = store.messages
    /** Messages of the chat on screen. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _thinkingPreference = MutableStateFlow(com.example.gemini.domain.model.ThinkingPreference())
    val thinkingPreference: StateFlow<com.example.gemini.domain.model.ThinkingPreference> = _thinkingPreference.asStateFlow()

    private val _userEmail = store.userEmail
    val userEmail: StateFlow<String?> = _userEmail.asStateFlow()

    val isOAuthServerListening: StateFlow<Boolean> = oauthManager.isServerListening.asStateFlow()
    private val _isOAuthServerLoading = MutableStateFlow(false)
    val isOAuthServerLoading: StateFlow<Boolean> = _isOAuthServerLoading.asStateFlow()

    private val _projectId = MutableStateFlow("rising-fact-p41fc")
    val projectId: StateFlow<String> = _projectId.asStateFlow()

    private val _tier = MutableStateFlow("pro")
    val tier: StateFlow<String> = _tier.asStateFlow()

    private val _contextWindowLimit = MutableStateFlow(10)
    val contextWindowLimit: StateFlow<Int> = _contextWindowLimit.asStateFlow()

    private val _summaryModelIdPref = MutableStateFlow("always_ask")
    val summaryModelIdPref: StateFlow<String> = _summaryModelIdPref.asStateFlow()

    private val _isSummarizing = MutableStateFlow(false)
    val isSummarizing: StateFlow<Boolean> = _isSummarizing.asStateFlow()

    private val _summarizingModelName = MutableStateFlow("")
    val summarizingModelName: StateFlow<String> = _summarizingModelName.asStateFlow()

    private val _summaryError = MutableStateFlow<String?>(null)
    val summaryError: StateFlow<String?> = _summaryError.asStateFlow()

    private val _pendingQueuedUserMessage = MutableStateFlow<String?>(null)
    val pendingQueuedUserMessage: StateFlow<String?> = _pendingQueuedUserMessage.asStateFlow()

    private val _postponedThreshold = MutableStateFlow<Int?>(null)
    val postponedThreshold: StateFlow<Int?> = _postponedThreshold.asStateFlow()

    private val _attachments = store.attachments
    val attachments: StateFlow<List<com.example.gemini.domain.model.ChatAttachment>> = _attachments.asStateFlow()

    private val _isUploadingAttachment = MutableStateFlow(false)
    val isUploadingAttachment: StateFlow<Boolean> = _isUploadingAttachment.asStateFlow()

    fun addAttachmentsFromUris(uris: List<android.net.Uri>, context: android.content.Context) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            _isUploadingAttachment.value = true
            try {
                for (uri in uris) {
                    processAndUploadUri(uri, context)
                }
            } finally {
                _isUploadingAttachment.value = false
            }
        }
    }

    fun addAttachmentFromUri(uri: android.net.Uri, context: android.content.Context) {
        addAttachmentsFromUris(listOf(uri), context)
    }

    private suspend fun processAndUploadUri(uri: android.net.Uri, context: android.content.Context) {
        try {
            val contentResolver = context.contentResolver
            var fileName = "attachment_${System.currentTimeMillis()}"
            var fileSize = 0L

            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (nameIndex >= 0) fileName = it.getString(nameIndex) ?: fileName
                    if (sizeIndex >= 0) fileSize = it.getLong(sizeIndex)
                }
            }

            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = contentResolver.getType(uri)
                ?: android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
            val isImg = mimeType.startsWith("image/")
            val isAud = mimeType.startsWith("audio/")

            val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cacheDir = getApplication<Application>().cacheDir.resolve("attachments").apply { mkdirs() }
            val cachedFile = java.io.File(cacheDir, "att_${System.currentTimeMillis()}_$safeFileName")

            val streamSuccess = withContext(Dispatchers.IO) {
                try {
                    contentResolver.openInputStream(uri)?.use { input ->
                        cachedFile.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 64 * 1024)
                        }
                    }
                    true
                } catch (e: Exception) {
                    android.util.Log.e("ChatViewModel", "Streaming attachment to disk failed: ${e.message}")
                    false
                }
            }

            if (streamSuccess && cachedFile.exists()) {
                val finalSize = cachedFile.length()
                val isSmallMedia = (isImg || isAud) && finalSize <= 5 * 1024 * 1024L
                val base64 = if (isSmallMedia) {
                    try {
                        withContext(Dispatchers.IO) {
                            android.util.Base64.encodeToString(cachedFile.readBytes(), android.util.Base64.NO_WRAP)
                        }
                    } catch (oom: OutOfMemoryError) {
                        android.util.Log.w("ChatViewModel", "Base64 encoding skipped due to low memory: ${oom.message}")
                        null
                    }
                } else null

                // For small media with base64, save to hub artifact. For larger files, use cached disk path directly.
                val savedHostUri = when {
                    base64 != null -> {
                        agyHubClient.saveMediaAsArtifact(
                            mimeType = mimeType,
                            base64Data = base64,
                            description = fileName,
                            thumbnailBase64 = if (isImg) base64 else "",
                            hubUrl = AuthPreferences.currentHubUrl
                        ).getOrNull() ?: cachedFile.absolutePath
                    }
                    else -> cachedFile.absolutePath
                }

                val att = com.example.gemini.domain.model.ChatAttachment(
                    id = "att_${System.currentTimeMillis()}_${(0..999).random()}",
                    name = fileName,
                    path = savedHostUri,
                    isImage = isImg,
                    isAudio = isAud,
                    localUri = cachedFile.absolutePath,
                    size = if (fileSize > 0) fileSize else finalSize,
                    mimeType = mimeType,
                    base64 = base64
                )
                _attachments.value = _attachments.value + att
            }
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Failed to add attachment from uri $uri: ${e.message}")
        }
    }

    fun addProjectFileAttachment(filePath: String, fileName: String) {
        viewModelScope.launch {
            val file = java.io.File(filePath)
            val ext = fileName.substringAfterLast('.', "").lowercase()
            val mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
            val isImg = mimeType.startsWith("image/")
            val isAud = mimeType.startsWith("audio/")
            val fileSize = if (file.exists()) file.length() else 0L
            val isSmallMedia = (isImg || isAud) && fileSize in 1L..(5 * 1024 * 1024L)

            val b64 = if (isSmallMedia) {
                try {
                    withContext(Dispatchers.IO) {
                        android.util.Base64.encodeToString(file.readBytes(), android.util.Base64.NO_WRAP)
                    }
                } catch (oom: OutOfMemoryError) {
                    android.util.Log.w("ChatViewModel", "Project file base64 skipped due to low memory: ${oom.message}")
                    ""
                }
            } else ""

            val att = com.example.gemini.domain.model.ChatAttachment(
                id = "att_${System.currentTimeMillis()}_${(0..999).random()}",
                name = fileName,
                path = filePath,
                isImage = isImg,
                isAudio = isAud,
                size = fileSize,
                mimeType = mimeType,
                base64 = b64.ifBlank { null }
            )
            _attachments.value = _attachments.value + att
        }
    }

    fun removeAttachment(attachmentId: String) {
        val target = _attachments.value.find { it.id == attachmentId }
        if (target != null && target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
            viewModelScope.launch(Dispatchers.IO) {
                agyHubClient.deleteMediaArtifact(target.path)
            }
        }
        _attachments.value = _attachments.value.filter { it.id != attachmentId }
    }

    fun clearAttachments() {
        val atts = _attachments.value
        viewModelScope.launch(Dispatchers.IO) {
            for (target in atts) {
                if (target.path.isNotBlank() && !target.path.startsWith("content://") && !target.path.startsWith("/data/")) {
                    agyHubClient.deleteMediaArtifact(target.path)
                }
            }
        }
        _attachments.value = emptyList()
    }

    private val _isDevModeEnabled = MutableStateFlow(false)
    val isDevModeEnabled: StateFlow<Boolean> = _isDevModeEnabled.asStateFlow()

    private val _terminatedToolDialog = store.terminatedToolDialog
    val terminatedToolDialog: StateFlow<Pair<com.example.gemini.domain.model.ToolCall, String>?> = _terminatedToolDialog.asStateFlow()

    fun dismissTerminatedToolDialog() {
        _terminatedToolDialog.value = null
    }

    fun setDevModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setDevModeEnabled(enabled)
        }
    }

    fun updateCustomSystemPrompt(prompt: String?) {
        val conv = _currentConversation.value ?: return
        val updated = conv.copy(customSystemPrompt = if (prompt.isNullOrBlank()) null else prompt.trim())
        _currentConversation.value = updated
        _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
    }

    val chatFontScale = authPrefs.chatFontScale
    val themeMode: StateFlow<String> = authPrefs.themeMode
        .stateIn(viewModelScope, SharingStarted.Eagerly, authPreferences.getThemeModeSync())
    val agyHubUrl = authPrefs.agyHubUrl
    val agyBridgeHttpUrl = authPrefs.agyBridgeHttpUrl
    val terminalFontSize = authPrefs.terminalFontSize
    val terminalCursorStyle = authPrefs.terminalCursorStyle
    val terminalBufferSize = authPrefs.terminalBufferSize
    val terminalTheme = authPrefs.terminalTheme

    val useSshTerminal = authPrefs.useSshTerminal
    val termuxSshHost = authPrefs.termuxSshHost
    val termuxSshPort = authPrefs.termuxSshPort
    val termuxSshUser = authPrefs.termuxSshUser
    val termuxSshPass = authPrefs.termuxSshPass
    val isLocalToolsEnabled = authPrefs.isLocalToolsEnabled
    val isLocalToolsInstalled = authPrefs.isLocalToolsInstalled

    val commandAutoExecutionPolicy = authPrefs.commandAutoExecutionPolicy
    val commandSandboxEnabled = authPrefs.commandSandboxEnabled
    val requireApprovalForFileEdits = authPrefs.requireApprovalForFileEdits
    val defaultApprovalScope = authPrefs.defaultApprovalScope
    val groupChatsByWorkspace: StateFlow<Boolean> = authPrefs.groupChatsByWorkspace
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isFloatingBubbleEnabled: StateFlow<Boolean> = authPrefs.isFloatingBubbleEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingBubbleEnabledSync())

    val autoShowFloatingBubbleOnMinimize: StateFlow<Boolean> = authPrefs.autoShowFloatingBubbleOnMinimize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getAutoShowFloatingBubbleOnMinimizeSync())

    val isFloatingSwitcherEnabled: StateFlow<Boolean> = authPrefs.isFloatingSwitcherEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherEnabledSync())

    val floatingSwitcherOrientation: StateFlow<String> = authPrefs.floatingSwitcherOrientation
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherOrientationSync())

    val floatingSwitcherItems: StateFlow<List<String>> = authPrefs.floatingSwitcherItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherItemsSync())

    val floatingSwitcherAutoCollapseSec: StateFlow<Int> = authPrefs.floatingSwitcherAutoCollapseSec
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherAutoCollapseSecSync())

    val floatingSwitcherPosX: StateFlow<Float> = authPrefs.floatingSwitcherPosX
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherPositionSync().first)

    val floatingSwitcherPosY: StateFlow<Float> = authPrefs.floatingSwitcherPosY
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingSwitcherPositionSync().second)

    fun setFloatingSwitcherEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherEnabled(enabled)
        }
    }

    fun setFloatingSwitcherOrientation(orientation: String) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherOrientation(orientation)
        }
    }

    fun setFloatingSwitcherItems(items: List<String>) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherItems(items)
        }
    }

    fun setFloatingSwitcherAutoCollapseSec(sec: Int) {
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherAutoCollapseSec(sec)
        }
    }

    private var saveFloatingSwitcherJob: kotlinx.coroutines.Job? = null

    fun saveFloatingSwitcherPosition(x: Float, y: Float) {
        saveFloatingSwitcherJob?.cancel()
        saveFloatingSwitcherJob = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            kotlinx.coroutines.delay(250)
            authPrefs.saveFloatingSwitcherPosition(x, y)
        }
    }

    fun resetFloatingSwitcherPosition() {
        saveFloatingSwitcherJob?.cancel()
        viewModelScope.launch {
            authPrefs.saveFloatingSwitcherPosition(0.95f, 0.50f)
        }
    }

    fun setGroupChatsByWorkspace(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveGroupChatsByWorkspace(enabled)
        }
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingBubbleEnabled(enabled)
        }
    }

    fun setAutoShowFloatingBubbleOnMinimize(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveAutoShowFloatingBubbleOnMinimize(enabled)
        }
    }

    val isFloatingDiagnosticsEnabled: StateFlow<Boolean> = authPrefs.isFloatingDiagnosticsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingDiagnosticsEnabledSync())

    fun setFloatingDiagnosticsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingDiagnosticsEnabled(enabled)
        }
    }

    val isNetworkInspectorEnabled: StateFlow<Boolean> = authPrefs.isNetworkInspectorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getNetworkInspectorEnabledSync())

    fun setNetworkInspectorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveNetworkInspectorEnabled(enabled)
        }
    }

    val isFloatingNetworkInspectorEnabled: StateFlow<Boolean> = authPrefs.isFloatingNetworkInspectorEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), authPrefs.getFloatingNetworkInspectorEnabledSync())

    fun setFloatingNetworkInspectorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.saveFloatingNetworkInspectorEnabled(enabled)
        }
    }


    fun setRequireApprovalForFileEdits(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setRequireApprovalForFileEdits(enabled)
        }
    }

    fun setDefaultApprovalScope(scope: String) {
        viewModelScope.launch {
            authPrefs.setDefaultApprovalScope(scope)
        }
    }

    fun setThemeMode(mode: String) {
        viewModelScope.launch {
            authPrefs.saveThemeMode(mode)
        }
    }

    fun saveServerUrls(hubUrl: String, bridgeUrl: String) {
        viewModelScope.launch {
            if (hubUrl.isNotBlank()) {
                authPrefs.saveAgyHubUrl(hubUrl.trim())
                agyHubClient.clearCsrfToken()
            }
            if (bridgeUrl.isNotBlank()) {
                authPrefs.saveAgyBridgeHttpUrl(bridgeUrl.trim())
            }
        }
    }

    fun saveTerminalPreferences(fontSize: Int, cursorStyle: String, bufferSize: Int, theme: String) {
        viewModelScope.launch {
            authPrefs.saveTerminalPreferences(fontSize, cursorStyle, bufferSize, theme)
        }
    }

    fun setUseSshTerminal(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setUseSshTerminal(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun saveSshSettings(host: String, port: Int, user: String, pass: String) {
        viewModelScope.launch {
            authPrefs.saveSshSettings(host, port, user, pass)
        }
    }

    fun setLocalToolsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            authPrefs.setLocalToolsEnabled(enabled)
            if (enabled) {
                authPrefs.setTerminalToolEnabled(true)
            }
        }
    }

    fun setChatFontScale(scale: Float) {
        viewModelScope.launch {
            authPrefs.saveChatFontScale(scale)
        }
    }

    fun setContextWindowLimit(limit: Int) {
        viewModelScope.launch {
            authPrefs.setContextWindowLimit(limit)
        }
    }

    fun setSummaryModelId(modelId: String) {
        viewModelScope.launch {
            authPrefs.setSummaryModelId(modelId)
        }
    }

    fun postponeSummarization(extraCount: Int) {
        val currentCount = _messages.value.size
        _postponedThreshold.value = currentCount + extraCount
    }

    fun updateSummaryMessage(messageId: String, newSummary: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.map {
            if (it.id == messageId) it.copy(content = newSummary) else it
        }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun deleteSummaryMessage(messageId: String) {
        val conv = _currentConversation.value ?: return
        val updated = _messages.value.filterNot { it.id == messageId }
        _messages.value = updated
        val lastSummary = updated.filter { it.role == MessageRole.SUMMARY }.lastOrNull()?.content
        val updatedConv = conv.copy(summary = lastSummary)
        _currentConversation.value = updatedConv
        _postponedThreshold.value = null
        _conversations.value = _conversations.value.map { if (it.id == updatedConv.id) updatedConv else it }
    }

    fun updateSummary(newSummary: String) {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            updateSummaryMessage(latestSummaryMsg.id, newSummary)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = if (newSummary.isBlank()) null else newSummary)
            _currentConversation.value = updated
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun deleteSummary() {
        val latestSummaryMsg = _messages.value.filter { it.role == MessageRole.SUMMARY }.lastOrNull()
        if (latestSummaryMsg != null) {
            deleteSummaryMessage(latestSummaryMsg.id)
        } else {
            val conv = _currentConversation.value ?: return
            val updated = conv.copy(summary = null)
            _currentConversation.value = updated
            _postponedThreshold.value = null
            _conversations.value = _conversations.value.map { if (it.id == updated.id) updated else it }
        }
    }

    fun setThinkingPreference(pref: com.example.gemini.domain.model.ThinkingPreference) {
        _thinkingPreference.value = pref
    }


    private val _isNetworkConnectedState = MutableStateFlow(isNetworkConnected())
    val isNetworkConnectedState: StateFlow<Boolean> = _isNetworkConnectedState.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _isNetworkConnectedState.value = true
            checkAndResumeInterruptedTask()
        }
        override fun onLost(network: Network) {
            _isNetworkConnectedState.value = isNetworkConnected()
        }
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            _isNetworkConnectedState.value = hasInternet
            if (hasInternet) {
                checkAndResumeInterruptedTask()
            }
        }
    }

    private fun checkAndResumeInterruptedTask() {
        val pending = com.example.gemini.domain.model.OfflineRecoveryManager.popPendingTask() ?: return
        viewModelScope.launch(Dispatchers.Main) {
            android.widget.Toast.makeText(
                getApplication(),
                "Internet restored! Smart Manager resuming task with Gemini...",
                android.widget.Toast.LENGTH_LONG
            ).show()
            val conv = _conversations.value.find { it.id == pending.conversationId }
            if (conv != null) {
                selectConversation(conv.id)
            }
            agyBackend.selectModel(pending.preferredModelId)
            sendMessage(pending.prompt)
        }
    }

    fun isNetworkConnected(): Boolean {
        return try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val net = cm?.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (e: Exception) {
            android.util.Log.e("ChatViewModel", "Error checking network connectivity: ${e.message}", e)
            false
        }
    }

    private val _conversationError = store.conversationError
    val conversationError: StateFlow<String?> = _conversationError.asStateFlow()


    private val _conversationDrafts = mutableMapOf<String, androidx.compose.ui.text.input.TextFieldValue>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchingConversations = MutableStateFlow(false)
    val isSearchingConversations: StateFlow<Boolean> = _isSearchingConversations.asStateFlow()

    private var serverJob: Job? = null
    var pendingPkceVerifier: String? = null

    init {
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            android.util.Log.w("ChatViewModel", "Could not register network callback: ${e.message}")
        }

        agyBackend.start()

        viewModelScope.launch {
            authPrefs.userEmail.collect { _userEmail.value = it }
        }

        viewModelScope.launch {
            authPrefs.projectId.collect { it?.let { p -> _projectId.value = p } }
        }

        viewModelScope.launch {
            authPrefs.subscriptionTier.collect { it?.let { t -> _tier.value = t } }
        }

        viewModelScope.launch {
            authPrefs.contextWindowLimit.collect { _contextWindowLimit.value = it }
        }

        viewModelScope.launch {
            authPrefs.summaryModelId.collect { _summaryModelIdPref.value = it }
        }

        viewModelScope.launch {
            authPrefs.isDevModeEnabled.collect { _isDevModeEnabled.value = it }
        }

        viewModelScope.launch {
            authPrefs.preferredModelId.collect { savedModel ->
                _isAutoRouting.value = com.example.gemini.domain.model.SmartModelRouter.isAuto(savedModel)
            }
        }

    }

    fun getDraft(conversationId: String): androidx.compose.ui.text.input.TextFieldValue {
        return _conversationDrafts[conversationId] ?: androidx.compose.ui.text.input.TextFieldValue("")
    }

    fun setDraft(conversationId: String, value: androidx.compose.ui.text.input.TextFieldValue) {
        if (value.text.isEmpty()) {
            _conversationDrafts.remove(conversationId)
        } else {
            _conversationDrafts[conversationId] = value
        }
    }

    fun clearDraft(conversationId: String) {
        _conversationDrafts.remove(conversationId)
    }

    fun searchConversations(query: String) {
        _searchQuery.value = query
    }

    fun addAttachment(attachment: ChatAttachment) {
        _attachments.value = _attachments.value + attachment
    }


    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? {
        val currentAccess = authPrefs.accessToken.firstOrNull()
        val currentRefresh = authPrefs.refreshToken.firstOrNull()
        val expiresAt = authPrefs.expiresAt.firstOrNull() ?: 0L

        val isExpired = forceRefresh || (expiresAt > 0 && System.currentTimeMillis() >= expiresAt - 60_000)

        if (!isExpired && !currentAccess.isNullOrBlank()) {
            return currentAccess
        }

        if (!currentRefresh.isNullOrBlank()) {
            android.util.Log.d("GeminiApp", "[Auth] Refreshing Google OAuth access token using refresh_token...")
            val result = oauthManager.refreshToken(currentRefresh)
            if (result.isSuccess) {
                val tokenData = result.getOrThrow()
                android.util.Log.d("GeminiApp", "[Auth] Token refreshed successfully! New access token acquired.")
                authPrefs.saveTokens(
                    accessToken = tokenData.access_token,
                    refreshToken = tokenData.refresh_token ?: currentRefresh,
                    expiresInSeconds = tokenData.expires_in ?: 3600
                )
                return tokenData.access_token
            } else {
                android.util.Log.e("GeminiApp", "[Auth] Failed to refresh token: ${result.exceptionOrNull()?.message}")
            }
        }

        return currentAccess
    }


    fun applyManualInput(input: String) {
        val trimmed = input.trim()
        android.util.Log.d("GeminiApp", "[OAuth] applyManualInput received: ${trimmed.take(30)}...")
        if (trimmed.contains("code=")) {
            val match = Regex("[?&]code=([^&\\s]+)").find(trimmed)
            val code = match?.groupValues?.get(1)?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            if (code != null) {
                android.util.Log.d("GeminiApp", "[OAuth] Extracted auth code from manual input: ${code.take(15)}...")
                handleOAuthCode(code)
                return
            }
        }

        if (trimmed.startsWith("4/0")) {
            val decoded = try { java.net.URLDecoder.decode(trimmed, "UTF-8") } catch (_: Exception) { trimmed }
            handleOAuthCode(decoded)
            return
        }

        // Direct access token (e.g. ya29...)
        viewModelScope.launch {
            android.util.Log.d("GeminiApp", "[OAuth] Applying direct token...")
            authPrefs.saveTokens(trimmed, null, "Manual Token")
            val assistRes = apiService.loadCodeAssist(trimmed)
            if (assistRes.isSuccess) {
                val (proj, tier) = assistRes.getOrThrow()
                authPrefs.saveProjectInfo(proj, tier)
                _projectId.value = proj
                _tier.value = tier
            }
            val userRes = oauthManager.fetchUserInfo(trimmed)
            if (userRes.isSuccess) {
                userRes.getOrNull()?.email?.let { email ->
                    authPrefs.saveTokens(trimmed, null, email)
                    _userEmail.value = email
                }
            }
            refreshQuotas()
        }
    }

    fun startOAuthServer() {
        if (oauthManager.isServerListening.value && serverJob?.isActive == true) {
            android.util.Log.d("GeminiApp", "[OAuth] Server is already active, ignoring start request")
            return
        }
        serverJob?.cancel()
        serverJob = viewModelScope.launch {
            _isOAuthServerLoading.value = true
            try {
                if (pendingPkceVerifier == null) {
                    val pkce = oauthManager.generatePkce()
                    pendingPkceVerifier = pkce.codeVerifier
                    android.util.Log.d("GeminiApp", "[OAuth] Generated PKCE verifier for server session")
                }
                _isOAuthServerLoading.value = false
                oauthManager.startLocalCallbackServer { code ->
                    handleOAuthCode(code)
                }
            } catch (e: Exception) {
                android.util.Log.e("GeminiApp", "[OAuth] Server start failed: ${e.message}")
                _isOAuthServerLoading.value = false
            }
        }
    }

    fun stopOAuthServer() {
        serverJob?.cancel()
        serverJob = null
        _isOAuthServerLoading.value = false
        oauthManager.stopLocalCallbackServer()
    }

    fun toggleOAuthServer(enable: Boolean) {
        if (enable) {
            startOAuthServer()
        } else {
            stopOAuthServer()
        }
    }

    fun login() {
        loginToAgyHub()
    }

    fun logout() {
        stopOAuthServer()
        pendingPkceVerifier = null
        logoutFromAgyHub()
        viewModelScope.launch {
            authPrefs.clearAuth()
        }
        _userEmail.value = null
        _projectId.value = "rising-fact-p41fc"
        _tier.value = "pro"
        agyBackend.resetQuotaState()
    }

    fun getGoogleOAuthUrl(): String {
        val pkce = oauthManager.generatePkce()
        pendingPkceVerifier = pkce.codeVerifier
        android.util.Log.d("GeminiApp", "[OAuth] getGoogleOAuthUrl generated verifier (len=${pkce.codeVerifier.length})")
        startOAuthServer()
        return pkce.authUrl
    }

    fun handleOAuthCode(code: String) {
        val trimmedCode = code.trim()
        android.util.Log.d("GeminiApp", "[OAuth] handleOAuthCode processing code: ${trimmedCode.take(20)}...")
        val verifier = pendingPkceVerifier
        if (verifier == null) {
            android.util.Log.e("GeminiApp", "[OAuth] ERROR: pendingPkceVerifier is null! Cannot exchange auth code.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            // Brief delay to let the server socket fully close and free the IO thread pool
            // before making outbound network calls. This prevents DNS resolution failures
            // that occur when the token exchange fires from within the server socket callback.
            delay(400L)

            var tokenRes: Result<GoogleOAuthManager.TokenResponse>? = null
            for (attempt in 1..3) {
                android.util.Log.d("GeminiApp", "[OAuth] exchangeCodeForToken attempt $attempt...")
                tokenRes = oauthManager.exchangeCodeForToken(trimmedCode, verifier)
                if (tokenRes.isSuccess) break
                android.util.Log.w("GeminiApp", "[OAuth] Attempt $attempt failed: ${tokenRes.exceptionOrNull()?.message}")
                if (attempt < 3) delay(1000L * attempt)
            }

            if (tokenRes?.isSuccess == true) {
                val tokenData = tokenRes.getOrThrow()
                android.util.Log.d("GeminiApp", "[OAuth] Token exchange SUCCESS! Fetching user profile...")
                val userRes = oauthManager.fetchUserInfo(tokenData.access_token)
                val email = userRes.getOrNull()?.email ?: "Google Account"
                android.util.Log.d("GeminiApp", "[OAuth] User email resolved: $email")

                authPrefs.saveTokens(
                    accessToken = tokenData.access_token,
                    refreshToken = tokenData.refresh_token,
                    email = email,
                    expiresInSeconds = tokenData.expires_in ?: 3600
                )
                withContext(Dispatchers.Main) { _userEmail.value = email }

                val assistRes = apiService.loadCodeAssist(tokenData.access_token)
                if (assistRes.isSuccess) {
                    val (proj, tier) = assistRes.getOrThrow()
                    android.util.Log.d("GeminiApp", "[OAuth] CodeAssist project: $proj, tier: $tier")
                    authPrefs.saveProjectInfo(proj, tier)
                    withContext(Dispatchers.Main) {
                        _projectId.value = proj
                        _tier.value = tier
                    }
                } else {
                    android.util.Log.w("GeminiApp", "[OAuth] CodeAssist loading warning: ${assistRes.exceptionOrNull()?.message}")
                }
                refreshQuotas()
            } else {
                android.util.Log.e("GeminiApp", "[OAuth] Token exchange FAILED after retries: ${tokenRes?.exceptionOrNull()?.message}")
            }
        }
    }


    private var sharedConversationLoadJob: Job? = null

    fun loadSharedConversationFromUri(context: Context, uri: Uri) {
        sharedConversationLoadJob?.cancel()
        _isSharedConversationLoading.value = true
        sharedConversationLoadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                if (bytes != null && bytes.isNotEmpty()) {
                    val restoreRes = agyBridgeService.restoreConversationArchive(bytes)
                    if (restoreRes.isSuccess) {
                        val restored = restoreRes.getOrThrow()
                        if (!isActive) return@launch
                        val newConv = com.example.gemini.domain.model.Conversation(
                            id = restored.id,
                            title = restored.title,
                            updatedAt = System.currentTimeMillis(),
                            createdAt = System.currentTimeMillis(),
                            stepCount = restored.stepsCount
                        )
                        _conversations.value = listOf(newConv) + _conversations.value.filter { it.id != restored.id }
                        withContext(Dispatchers.Main) {
                            selectConversation(restored.id)
                            com.example.gemini.ui.components.AppToastHelper.showToast("Restored chat: ${restored.title}", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                        }
                        return@launch
                    }

                    val parsed = com.example.gemini.ui.components.ConversationShareHelper.parseSharedConversation(bytes.inputStream())
                    if (!isActive) return@launch
                    _sharedConversationPreview.value = parsed
                    withContext(Dispatchers.Main) {
                        com.example.gemini.ui.components.AppToastHelper.showToast("Loaded shared chat: ${parsed.title}", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) return@launch
                Log.e("ChatViewModel", "Failed to load shared conversation from uri $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to load shared chat file", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            } finally {
                _isSharedConversationLoading.value = false
            }
        }
    }

    fun cancelSharedConversationLoading() {
        sharedConversationLoadJob?.cancel()
        sharedConversationLoadJob = null
        _isSharedConversationLoading.value = false
        _sharedConversationPreview.value = null
    }

    fun dismissSharedConversation() {
        _sharedConversationPreview.value = null
    }

    fun dismissIncomingMarkdownPreview() {
        _incomingMarkdownPreview.value = null
    }

    fun loadMarkdownFromUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName: String? = null
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val resolvedName = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "document.md"
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                _incomingMarkdownPreview.value = resolvedName to content
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Opened $resolvedName", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to load markdown from URI $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to load markdown file", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            }
        }
    }

    fun openFileInIdeDirectly(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var fileName: String? = null
                if (uri.scheme == "content") {
                    try {
                        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                                if (nameIdx != -1) {
                                    fileName = cursor.getString(nameIdx)
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
                val resolvedName = fileName ?: uri.lastPathSegment?.substringAfterLast('/') ?: "file.txt"
                val resolvedPath = if (uri.scheme == "file") uri.path ?: resolvedName else uri.toString()
                val content = context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""

                com.example.gemini.data.daemon.TermuxDaemonManager.openOrSelectTab(
                    path = resolvedPath,
                    name = resolvedName,
                    content = content,
                    isExternal = true
                )
                requestViewMode("IDE")
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Opened $resolvedName in IDE", com.example.gemini.ui.components.ChatToastType.SUCCESS)
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Failed to open file in IDE from URI $uri: ${e.message}", e)
                withContext(Dispatchers.Main) {
                    com.example.gemini.ui.components.AppToastHelper.showToast("Failed to open file in IDE", com.example.gemini.ui.components.ChatToastType.ERROR)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        try {
            val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        agyBackend.dispose()
    }
}

private data class ExecutedToolResult(
    val output: String,
    val exitCode: Int,
    val durationMs: Long,
    val status: String
)
