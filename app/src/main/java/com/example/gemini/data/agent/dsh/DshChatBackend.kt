package com.example.gemini.data.agent.dsh

import android.app.Application
import android.util.Log
import com.example.gemini.data.agent.AgentChatBackend
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * DeepSeek Harness (DSH) Chat Backend.
 * Connects AntiBot directly to the DeepSeek Harness daemon running in Termux,
 * supporting Cordis plugins, tools, deep reasoning (<think>), and multi-model turns.
 */
class DshChatBackend(
    private val application: Application,
    private val scope: CoroutineScope,
    private val store: ChatSessionStore,
    val prefs: DshPreferences = DshPreferences(application),
    val client: DshBridgeClient = DshBridgeClient(application, prefs)
) : AgentChatBackend {

    override val kind = AgentKind.DSH

    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    override val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    override val currentConversation: StateFlow<Conversation?> = store.currentConversation.asStateFlow()
    override val messages: StateFlow<List<ChatMessage>> = store.messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    override val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _isLoadingConversation = MutableStateFlow(false)
    override val isLoadingConversation: StateFlow<Boolean> = _isLoadingConversation.asStateFlow()

    private val _conversationError = MutableStateFlow<String?>(null)
    override val conversationError: StateFlow<String?> = _conversationError.asStateFlow()

    val isCurrentChatActivelyRunning = isStreaming

    private val _selectedModelId = MutableStateFlow(prefs.modelId.value)
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    private var activeTurnJob: Job? = null

    fun selectModel(id: String) {
        prefs.setModelId(id)
        _selectedModelId.value = id
    }

    fun detach() {
        // Disconnect or unhook listeners without killing conversation state
    }

    override fun startNewChat() {
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            agent = AgentKind.DSH,
            modelId = _selectedModelId.value,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        store.currentConversation.value = newConv
        store.messages.value = emptyList()
        _conversations.update { listOf(newConv) + it.filterNot { c -> c.id == newConv.id } }
    }

    override fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id } ?: return
        store.currentConversation.value = conv
    }

    override fun sendMessage(content: String) {
        val conv = store.currentConversation.value ?: return
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = conv.id,
            role = MessageRole.USER,
            content = content,
            agentKind = AgentKind.DSH,
            createdAt = System.currentTimeMillis()
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val assistantMsg = ChatMessage(
            id = assistantMsgId,
            conversationId = conv.id,
            role = MessageRole.ASSISTANT,
            content = "",
            isStreaming = true,
            modelId = _selectedModelId.value,
            agentKind = AgentKind.DSH,
            createdAt = System.currentTimeMillis()
        )

        store.messages.update { it + userMsg + assistantMsg }
        _isStreaming.value = true
        _conversationError.value = null

        activeTurnJob?.cancel()
        activeTurnJob = scope.launch(Dispatchers.IO) {
            val startTime = System.currentTimeMillis()
            var textAcc = StringBuilder()
            var thoughtAcc = StringBuilder()
            var toolCallsAcc = mutableListOf<ToolCall>()

            try {
                client.sendTurnStream(
                    conversationId = conv.id,
                    prompt = content,
                    modelId = _selectedModelId.value,
                    workspacePath = conv.workspaceUri.takeIf { it.isNotBlank() }
                ).collect { event ->
                    when (event) {
                        is DshStreamEvent.Token -> {
                            textAcc.append(event.text)
                            updateAssistant(conv.id, assistantMsgId, textAcc.toString(), thoughtAcc.toString(), toolCallsAcc, true)
                        }
                        is DshStreamEvent.Thought -> {
                            thoughtAcc.append(event.thought)
                            updateAssistant(conv.id, assistantMsgId, textAcc.toString(), thoughtAcc.toString(), toolCallsAcc, true)
                        }
                        is DshStreamEvent.ToolStart -> {
                            val tc = ToolCall(
                                id = event.id,
                                name = event.toolName,
                                command = event.input,
                                status = "RUNNING"
                            )
                            toolCallsAcc.add(tc)
                            updateAssistant(conv.id, assistantMsgId, textAcc.toString(), thoughtAcc.toString(), toolCallsAcc, true)
                        }
                        is DshStreamEvent.ToolFinish -> {
                            toolCallsAcc = toolCallsAcc.map { tc ->
                                if (tc.id == event.id) {
                                    tc.copy(status = "SUCCESS", output = event.output, exitCode = event.exitCode)
                                } else tc
                            }.toMutableList()
                            updateAssistant(conv.id, assistantMsgId, textAcc.toString(), thoughtAcc.toString(), toolCallsAcc, true)
                        }
                        is DshStreamEvent.Done -> {
                            val duration = System.currentTimeMillis() - startTime
                            val finalContent = if (event.fullReply.isNotBlank()) event.fullReply else textAcc.toString()
                            updateAssistant(conv.id, assistantMsgId, finalContent, thoughtAcc.toString(), toolCallsAcc, false, duration)
                        }
                        is DshStreamEvent.Error -> {
                            _conversationError.value = event.error
                            Log.e("DshChatBackend", "[DSH Turn Error]: ${event.error}")
                            updateAssistant(conv.id, assistantMsgId, textAcc.toString() + "\n\n⚠️ Error: ${event.error}", thoughtAcc.toString(), toolCallsAcc, false)
                        }
                        is DshStreamEvent.StepFinished -> {
                            // Step progress notification
                        }
                    }
                }
            } catch (e: CancellationException) {
                Log.d("DshChatBackend", "[DSH Turn Cancelled]")
            } catch (e: Exception) {
                Log.e("DshChatBackend", "[DSH Turn Exception]", e)
                _conversationError.value = e.message
            } finally {
                _isStreaming.value = false
            }
        }
    }

    private fun updateAssistant(
        convId: String,
        msgId: String,
        content: String,
        thought: String,
        toolCalls: List<ToolCall>,
        streaming: Boolean,
        duration: Long? = null
    ) {
        store.messages.update { list ->
            list.map { m ->
                if (m.id == msgId) {
                    m.copy(
                        content = content,
                        thoughtText = thought.takeIf { it.isNotBlank() },
                        thoughtDurationMs = duration,
                        toolCalls = toolCalls,
                        isStreaming = streaming,
                        modelId = _selectedModelId.value,
                        agentKind = AgentKind.DSH
                    )
                } else m
            }
        }
    }

    override fun stopStreaming() {
        activeTurnJob?.cancel()
        activeTurnJob = null
        _isStreaming.value = false
    }

    override fun deleteConversation(id: String) {
        _conversations.update { it.filterNot { c -> c.id == id } }
        if (store.currentConversation.value?.id == id) {
            store.currentConversation.value = null
            store.messages.value = emptyList()
        }
    }

    fun renameConversation(id: String, title: String) {
        _conversations.update { list ->
            list.map { if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it }
        }
        if (store.currentConversation.value?.id == id) {
            store.currentConversation.update { it?.copy(title = title) }
        }
    }

    override fun forkConversation(id: String) {
        val original = _conversations.value.find { it.id == id } ?: return
        val currentMsgs = store.messages.value
        val forked = original.copy(
            id = UUID.randomUUID().toString(),
            title = "${original.title} (Fork)",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        _conversations.update { listOf(forked) + it }
        store.currentConversation.value = forked
        store.messages.value = currentMsgs.map { it.copy(id = UUID.randomUUID().toString(), conversationId = forked.id) }
    }

    fun start() {
        retryConnections()
    }

    fun refreshStatus() {
        retryConnections()
    }

    override fun retryConnections() {
        scope.launch {
            client.isDaemonOnline()
        }
    }

    override fun onAppForegrounded() {
        retryConnections()
    }
}
