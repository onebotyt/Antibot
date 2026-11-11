package com.example.gemini.domain.model

import android.util.Log
import com.example.gemini.data.agent.local.LocalModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Execution activity states for AntiBot CLI engines and Local Neural Models.
 */
enum class EngineActivityState {
    INACTIVE_STANDBY, // 0 MB RAM, dormant, detached
    STARTING,         // Spinning up on-demand for turn
    ACTIVE_WORKING,   // Currently executing inference or agentic turn
    UNLOADING         // Freeing memory and returning to standby
}

data class EngineStatus(
    val agent: AgentKind,
    val displayName: String,
    val state: EngineActivityState,
    val ramUsageMb: Long = 0L,
    val lastActiveTimestamp: Long = 0L
)

/**
 * AntiBot Smart Manager Orchestrator.
 *
 * In Manager Mode, the Smart Manager controls all AI models and CLIs (Local Qwen, Antigravity,
 * Claude Code, Codex, and OpenCode).
 *
 * Strict Zero-RAM Policy:
 * 1. Local models and CLIs ONLY start when it is their designated turn to do work.
 * 2. When an engine is not selected, or after a turn completes, it stays INACTIVE with 0 memory load.
 * 3. Enforces single-active-worker isolation: whenever one engine is chosen, all other engines
 *    are immediately detached and local neural weights are unloaded from RAM.
 */
object SmartManagerOrchestrator {
    private const val TAG = "SmartManagerOrchestrator"

    private val initialStatuses = mapOf(
        AgentKind.LOCAL to EngineStatus(AgentKind.LOCAL, "Local Qwen Model", EngineActivityState.INACTIVE_STANDBY),
        AgentKind.AGY to EngineStatus(AgentKind.AGY, "Antigravity CLI", EngineActivityState.INACTIVE_STANDBY),
        AgentKind.CLAUDE to EngineStatus(AgentKind.CLAUDE, "Claude Code CLI", EngineActivityState.INACTIVE_STANDBY),
        AgentKind.CODEX to EngineStatus(AgentKind.CODEX, "Codex CLI", EngineActivityState.INACTIVE_STANDBY),
        AgentKind.OPENCODE to EngineStatus(AgentKind.OPENCODE, "OpenCode CLI", EngineActivityState.INACTIVE_STANDBY)
    )

    private val _engineStatuses = MutableStateFlow<Map<AgentKind, EngineStatus>>(initialStatuses)
    val engineStatuses: StateFlow<Map<AgentKind, EngineStatus>> = _engineStatuses.asStateFlow()

    private val _activeWorkingAgent = MutableStateFlow<AgentKind?>(null)
    val activeWorkingAgent: StateFlow<AgentKind?> = _activeWorkingAgent.asStateFlow()

    /**
     * Prepares the chosen engine for an upcoming work turn in Manager Mode.
     * Deactivates and detaches all other engines and ensures local model memory is unloaded
     * if the chosen engine is not Local.
     */
    fun prepareEngineForTurn(
        targetAgent: AgentKind,
        targetModelId: String,
        onDetachOthers: () -> Unit
    ) {
        Log.i(TAG, "🎯 Manager preparing engine for turn: $targetAgent (Model: $targetModelId)")

        // 1. Detach all other engines immediately
        onDetachOthers()

        // 2. Local model RAM isolation: if Local is not the target, ensure it is completely unloaded
        if (targetAgent != AgentKind.LOCAL) {
            LocalModelManager.deactivateAndUnload()
        } else {
            // Target is Local Model: activate on-demand strictly for this work
            LocalModelManager.activateForWork(modelId = targetModelId)
        }

        val now = System.currentTimeMillis()
        val localRam = if (targetAgent == AgentKind.LOCAL) LocalModelManager.getActiveRamMb() else 0L

        // 3. Update status map: only targetAgent is ACTIVE_WORKING; all others remain INACTIVE_STANDBY
        _activeWorkingAgent.value = targetAgent
        _engineStatuses.update { current ->
            current.mapValues { (agent, status) ->
                if (agent == targetAgent) {
                    status.copy(
                        state = EngineActivityState.ACTIVE_WORKING,
                        ramUsageMb = if (agent == AgentKind.LOCAL) localRam else 0L,
                        lastActiveTimestamp = now
                    )
                } else {
                    status.copy(
                        state = EngineActivityState.INACTIVE_STANDBY,
                        ramUsageMb = 0L
                    )
                }
            }
        }
    }

    /**
     * Called when a turn or response generation completes.
     * Guarantees that local models unload memory and the active engine transitions
     * back to INACTIVE_STANDBY with 0 memory load while waiting for user input.
     */
    fun onTurnCompleted(completedAgent: AgentKind) {
        Log.i(TAG, "🏁 Engine completed turn: $completedAgent. Transitioning to inactive standby.")

        if (completedAgent == AgentKind.LOCAL) {
            LocalModelManager.deactivateAndUnload()
        }

        _activeWorkingAgent.value = null
        _engineStatuses.update { current ->
            current.mapValues { (agent, status) ->
                if (agent == completedAgent) {
                    status.copy(
                        state = EngineActivityState.INACTIVE_STANDBY,
                        ramUsageMb = 0L,
                        lastActiveTimestamp = System.currentTimeMillis()
                    )
                } else {
                    status.copy(
                        state = EngineActivityState.INACTIVE_STANDBY,
                        ramUsageMb = 0L
                    )
                }
            }
        }

        // Garbage collect any lingering buffers
        try {
            System.gc()
        } catch (_: Throwable) {}
    }

    /**
     * Completely deactivates and unloads all engines to zero memory load.
     */
    fun deactivateAllEngines(onDetachAll: () -> Unit) {
        Log.i(TAG, "🛑 Deactivating all engines and unloading local models.")
        onDetachAll()
        LocalModelManager.deactivateAndUnload()
        _activeWorkingAgent.value = null
        _engineStatuses.value = initialStatuses
        try {
            System.gc()
            System.runFinalization()
        } catch (_: Throwable) {}
    }

    fun isEngineActive(agent: AgentKind): Boolean {
        return _activeWorkingAgent.value == agent
    }
}
