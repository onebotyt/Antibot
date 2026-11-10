package com.example.gemini.data.agent

import android.content.Context
import com.example.gemini.domain.model.AgentKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Which agents the app runs (Antigravity and Local Offline Model).
 */
class AgentPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(read(prefs))
    val enabled: StateFlow<Set<AgentKind>> = _enabled.asStateFlow()

    private val _hasChosen = MutableStateFlow(prefs.getBoolean(KEY_CHOSEN, false))
    val hasChosen: StateFlow<Boolean> = _hasChosen.asStateFlow()
    private val _newChatAgent = MutableStateFlow(
        runCatching { AgentKind.valueOf(prefs.getString(KEY_NEW_CHAT_AGENT, AgentKind.AGY.name)!!) }.getOrDefault(AgentKind.AGY)
    )
    val newChatAgent: StateFlow<AgentKind> = _newChatAgent.asStateFlow()

    fun setNewChatAgent(agent: AgentKind) {
        prefs.edit().putString(KEY_NEW_CHAT_AGENT, agent.name).apply()
        _newChatAgent.value = agent
    }

    /** Saves the agent selection (ignored when empty). */
    fun setEnabled(agents: Set<AgentKind>) {
        if (agents.isEmpty()) return
        prefs.edit().apply {
            AgentKind.entries.forEach { putBoolean(key(it), it in agents) }
            putBoolean(KEY_CHOSEN, true)
        }.apply()
        _enabled.value = agents
        _hasChosen.value = true
    }

    companion object {
        private const val PREFS = "agent_prefs"
        private const val KEY_CHOSEN = "agents_chosen"
        private const val KEY_NEW_CHAT_AGENT = "new_chat_agent"

        private fun key(agent: AgentKind) = "enabled_" + agent.name.lowercase()

        private fun default(agent: AgentKind) = agent == AgentKind.AGY || agent == AgentKind.LOCAL

        private fun read(prefs: android.content.SharedPreferences): Set<AgentKind> {
            val chosen = prefs.getBoolean(KEY_CHOSEN, false)
            if (!chosen) {
                return AgentKind.entries.filter { default(it) }.toSet()
            }
            val set = AgentKind.entries.filter { prefs.getBoolean(key(it), default(it)) }.toSet()
            return if (set.isEmpty()) setOf(AgentKind.AGY) else set
        }

        /** The first-launch agent choice was made, read synchronously (the server does not start before it). */
        fun hasChosenSync(context: Context): Boolean {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return prefs.getBoolean(KEY_CHOSEN, false)
        }

        /** Enabled agents, read synchronously (bridge launch). */
        fun enabledSync(context: Context): Set<AgentKind> {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            return read(prefs)
        }

        /** Bridge flags for launcher. */
        fun bridgeFlags(context: Context): String {
            val enabled = enabledSync(context)
            val flags = StringBuilder()
            if (AgentKind.AGY !in enabled) flags.append(" --no-hub")
            if (AgentKind.CLAUDE !in enabled) flags.append(" --no-claude")
            return flags.toString()
        }
    }
}
