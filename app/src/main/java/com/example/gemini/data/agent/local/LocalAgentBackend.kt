package com.example.gemini.data.agent.local

import android.app.Application
import android.content.Context
import com.example.gemini.data.agent.ChatSessionStore
import com.example.gemini.data.ide.RunConfigManager
import com.example.gemini.domain.context.LocalTurnSynthesizer
import com.example.gemini.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * LocalAgentBackend: Runs conversations 100% on-device using the local intelligence engine.
 * Consumes zero cloud tokens, works completely offline, and can start local dev servers,
 * run terminal commands, inspect project files, and synthesize turn recaps.
 */
class LocalAgentBackend(
    private val application: Application,
    private val backendScope: CoroutineScope,
    private val store: ChatSessionStore
) {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations.asStateFlow()

    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _selectedModelId = MutableStateFlow("local-backend")
    val selectedModelId: StateFlow<String> = _selectedModelId.asStateFlow()

    fun selectModel(modelId: String) {
        if (com.example.gemini.domain.model.LocalCodingDomain.isLocalModel(modelId)) {
            _selectedModelId.value = modelId
            val cur = _currentConversation.value
            if (cur != null) {
                val updated = cur.copy(modelId = modelId)
                _currentConversation.value = updated
                store.currentConversation.value = updated
                persistConversations()
            }
        }
    }

    private var activeJob: Job? = null
    private val storageDir = File(application.filesDir, "local_chats").apply { mkdirs() }

    init {
        loadPersistedConversations()
    }

    private fun loadPersistedConversations() {
        val indexFile = File(storageDir, "conversations_index.json")
        if (!indexFile.exists()) return
        try {
            val jsonArray = JSONArray(indexFile.readText())
            val list = mutableListOf<Conversation>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    Conversation(
                        id = obj.getString("id"),
                        title = obj.optString("title", "Local Chat"),
                        modelId = obj.optString("modelId", "local-model-only"),
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        updatedAt = obj.optLong("updatedAt", System.currentTimeMillis()),
                        agent = AgentKind.LOCAL
                    )
                )
            }
            _conversations.value = list
        } catch (_: Exception) {}
    }

    private fun persistConversations() {
        try {
            val jsonArray = JSONArray()
            _conversations.value.forEach { conv ->
                val obj = JSONObject().apply {
                    put("id", conv.id)
                    put("title", conv.title)
                    put("modelId", conv.modelId)
                    put("createdAt", conv.createdAt)
                    put("updatedAt", conv.updatedAt)
                }
                jsonArray.put(obj)
            }
            File(storageDir, "conversations_index.json").writeText(jsonArray.toString(2))
        } catch (_: Exception) {}
    }

    private fun loadMessagesFor(conversationId: String): List<ChatMessage> {
        val file = File(storageDir, "chat_${conversationId}.json")
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            val list = mutableListOf<ChatMessage>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val roleStr = obj.optString("role", "USER")
                val role = if (roleStr == "ASSISTANT") MessageRole.ASSISTANT else MessageRole.USER
                list.add(
                    ChatMessage(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        conversationId = conversationId,
                        role = role,
                        content = obj.optString("content", ""),
                        thoughtText = obj.optString("thoughtText").takeIf { it.isNotBlank() },
                        createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                        modelId = obj.optString("modelId", "local-model-only"),
                        tokenUsage = TokenUsage(
                            promptTokens = 0,
                            outputTokens = 0,
                            cachedTokens = 0,
                            totalTokens = 0,
                            durationMs = obj.optLong("durationMs", 25L)
                        )
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun persistMessagesFor(conversationId: String, msgList: List<ChatMessage>) {
        try {
            val arr = JSONArray()
            msgList.forEach { m ->
                val obj = JSONObject().apply {
                    put("id", m.id)
                    put("role", m.role.name)
                    put("content", m.content)
                    if (m.thoughtText != null) put("thoughtText", m.thoughtText)
                    put("createdAt", m.createdAt)
                    put("modelId", m.modelId)
                    put("durationMs", m.tokenUsage?.durationMs ?: 25L)
                }
                arr.put(obj)
            }
            File(storageDir, "chat_${conversationId}.json").writeText(arr.toString(2))
        } catch (_: Exception) {}
    }

    fun startNewChat(modelId: String? = null) {
        val effectiveModel = modelId ?: _selectedModelId.value
        val domain = com.example.gemini.domain.model.LocalCodingDomain.fromModelId(effectiveModel)
        val newConv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat (${domain.shortName})",
            modelId = domain.modelId,
            agent = AgentKind.LOCAL,
            workspaceUri = "/data/user/0/com.termux/files/home/projects"
        )
        _selectedModelId.value = domain.modelId
        _currentConversation.value = newConv
        _conversations.value = listOf(newConv) + _conversations.value.filter { it.id != newConv.id }
        _messages.value = emptyList()

        store.currentConversation.value = newConv
        store.messages.value = emptyList()
        persistConversations()
    }

    fun selectConversation(id: String) {
        val conv = _conversations.value.find { it.id == id } ?: return
        _currentConversation.value = conv
        _selectedModelId.value = conv.modelId.ifBlank { "local-backend" }
        val loaded = loadMessagesFor(id)
        _messages.value = loaded

        store.currentConversation.value = conv
        store.messages.value = loaded
    }

    fun deleteConversation(id: String) {
        _conversations.value = _conversations.value.filter { it.id != id }
        File(storageDir, "chat_${id}.json").delete()
        persistConversations()

        if (_currentConversation.value?.id == id) {
            val next = _conversations.value.firstOrNull()
            if (next != null) {
                selectConversation(next.id)
            } else {
                startNewChat()
            }
        }
    }

    fun renameConversation(id: String, title: String) {
        _conversations.value = _conversations.value.map {
            if (it.id == id) it.copy(title = title, updatedAt = System.currentTimeMillis()) else it
        }
        if (_currentConversation.value?.id == id) {
            _currentConversation.value = _currentConversation.value?.copy(title = title)
            store.currentConversation.value = _currentConversation.value
        }
        persistConversations()
    }

    fun forkConversation(id: String) {
        val original = _conversations.value.find { it.id == id } ?: return
        val originalMsgs = loadMessagesFor(id)
        val forkedId = UUID.randomUUID().toString()
        val forkedConv = original.copy(
            id = forkedId,
            title = "${original.title} (Fork)",
            updatedAt = System.currentTimeMillis()
        )
        val forkedMsgs = originalMsgs.map { it.copy(id = UUID.randomUUID().toString(), conversationId = forkedId) }

        _conversations.value = listOf(forkedConv) + _conversations.value
        persistConversations()
        persistMessagesFor(forkedId, forkedMsgs)
        selectConversation(forkedId)
    }

    fun detach() {
        activeJob?.cancel()
        activeJob = null
    }

    fun stopStreaming() {
        activeJob?.cancel()
        activeJob = null
        val cur = _currentConversation.value ?: return
        val updated = cur.copy(isRunning = false)
        _currentConversation.value = updated
        store.currentConversation.value = updated
    }

    fun sendMessage(prompt: String) {
        val curConv = _currentConversation.value ?: run {
            startNewChat()
            _currentConversation.value!!
        }

        val effectiveModel = curConv.modelId.ifBlank { _selectedModelId.value }
        val startTime = System.currentTimeMillis()
        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = curConv.id,
            role = MessageRole.USER,
            content = prompt,
            createdAt = startTime,
            modelId = effectiveModel
        )

        val updatedList = _messages.value + userMsg
        _messages.value = updatedList
        store.messages.value = updatedList

        // Update title if still default
        if (curConv.title.startsWith("New Chat")) {
            val newTitle = prompt.trim().lines().firstOrNull()?.take(40) ?: "Local Task"
            renameConversation(curConv.id, newTitle)
        }

        val runningConv = curConv.copy(isRunning = true, updatedAt = System.currentTimeMillis())
        _currentConversation.value = runningConv
        store.currentConversation.value = runningConv

        activeJob = backendScope.launch(Dispatchers.IO) {
            val assistantId = UUID.randomUUID().toString()
            try {
                val result = LocalIntelligenceEngine.generateResponse(
                    prompt = prompt,
                    messages = _messages.value,
                    workspaceUri = curConv.workspaceUri,
                    context = application,
                    modelId = effectiveModel
                )

                if (!isActive) return@launch

                // 1. Initial emission with thoughtText and empty content
                val initialMsg = ChatMessage(
                    id = assistantId,
                    conversationId = curConv.id,
                    role = MessageRole.ASSISTANT,
                    content = "",
                    thoughtText = result.thoughtText,
                    toolCalls = result.toolCalls,
                    isStreaming = true,
                    createdAt = System.currentTimeMillis(),
                    modelId = effectiveModel
                )

                withContext(Dispatchers.Main) {
                    val list = _messages.value + initialMsg
                    _messages.value = list
                    store.messages.value = list
                }

                // 2. Stream content in natural chunks
                val fullContent = result.content
                val totalLength = fullContent.length
                val chunkSize = when {
                    totalLength > 2000 -> 60
                    totalLength > 1000 -> 40
                    totalLength > 500 -> 25
                    else -> 12
                }

                var index = 0
                while (index < totalLength && isActive) {
                    val nextIndex = (index + chunkSize).coerceAtMost(totalLength)
                    val partialContent = fullContent.substring(0, nextIndex)
                    index = nextIndex

                    withContext(Dispatchers.Main) {
                        val updatedList = _messages.value.map { m ->
                            if (m.id == assistantId) {
                                m.copy(
                                    content = partialContent,
                                    isStreaming = index < totalLength
                                )
                            } else m
                        }
                        _messages.value = updatedList
                        store.messages.value = updatedList
                    }

                    if (index < totalLength) {
                        delay(16)
                    }
                }

                val elapsed = System.currentTimeMillis() - startTime
                val estPromptTokens = prompt.length / 4
                val estOutputTokens = fullContent.length / 4
                val totalTokens = estPromptTokens + estOutputTokens

                withContext(Dispatchers.Main) {
                    val completedList = _messages.value.map { m ->
                        if (m.id == assistantId) {
                            m.copy(
                                content = fullContent,
                                isStreaming = false,
                                tokenUsage = TokenUsage(
                                    promptTokens = estPromptTokens,
                                    outputTokens = estOutputTokens,
                                    cachedTokens = 0,
                                    totalTokens = totalTokens,
                                    durationMs = elapsed
                                )
                            )
                        } else m
                    }
                    _messages.value = completedList
                    store.messages.value = completedList

                    val idleConv = runningConv.copy(isRunning = false)
                    _currentConversation.value = idleConv
                    store.currentConversation.value = idleConv

                    persistMessagesFor(curConv.id, completedList)
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                withContext(Dispatchers.Main) {
                    val errorMsg = ChatMessage(
                        id = assistantId,
                        conversationId = curConv.id,
                        role = MessageRole.ASSISTANT,
                        content = "⚠️ **Local Execution Error:** ${e.localizedMessage ?: e.message}",
                        createdAt = System.currentTimeMillis(),
                        modelId = "local-model-only"
                    )
                    val finalMsgs = _messages.value.filter { it.id != assistantId } + errorMsg
                    _messages.value = finalMsgs
                    store.messages.value = finalMsgs

                    val idleConv = runningConv.copy(isRunning = false)
                    _currentConversation.value = idleConv
                    store.currentConversation.value = idleConv
                }
            }
        }
    }
}
