package com.example.gemini.ui.claude

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.data.agent.claude.ClaudeAccountManager
import com.example.gemini.data.agent.claude.ClaudeChatBackend
import com.example.gemini.data.agent.claude.contextLabel
import com.example.gemini.data.agent.claude.thinkingLabel
import com.example.gemini.data.agent.claude.ClaudePreferences
import com.example.gemini.data.agent.claude.ClaudeQuotaGroups
import com.example.gemini.theme.QuotaAmber
import com.example.gemini.theme.QuotaGreen
import com.example.gemini.theme.QuotaRed
import com.example.gemini.domain.model.AgentKind
import com.example.gemini.domain.model.ModelGroupPrefs
import com.example.gemini.domain.model.QuotaAgent
import com.example.gemini.ui.agents.AgentSheetHeader
import com.example.gemini.ui.agents.SegmentedSelector
import com.example.gemini.ui.agents.SheetSectionLabel
import com.example.gemini.ui.components.ClaudeAccent
import kotlinx.coroutines.delay

/** Accent of the model sheet's n-th plan group: Claude's own for "All models", then one hue per model limit. */
private fun claudeGroupColor(index: Int): Color = when (index) {
    0 -> ClaudeAccent
    1 -> Color(0xFF8E6CEF)
    2 -> Color(0xFF14B8A6)
    else -> Color(0xFFE0A100)
}

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> "${n / 1000}k"
    else -> n.toString()
}

/**
 * Claude chat controls shown above the input: permission mode, effort, thinking, and the live indicators
 * (prompt-cache timer, context window, plan usage).
 */
@Composable
fun ClaudeControlsStrip(
    backend: ClaudeChatBackend,
    prefs: ClaudePreferences,
    modifier: Modifier = Modifier
) {
    val mode by backend.permissionMode.collectAsState()
    val effort by backend.effort.collectAsState()
    val thinking by backend.thinkingEnabled.collectAsState()
    val cache by backend.cacheInfo.collectAsState()
    val context by backend.contextUsage.collectAsState()
    val limits by backend.rateLimits.collectAsState()
    val modelInfos by backend.modelInfos.collectAsState()
    val selectedModel by backend.selectedModelId.collectAsState()
    val appliedEffort by backend.appliedEffort.collectAsState()
    val androidContext = androidx.compose.ui.platform.LocalContext.current
    // a control the CLI refused is reverted; say why
    LaunchedEffect(Unit) {
        backend.controlMessages.collect { android.widget.Toast.makeText(androidContext, it, android.widget.Toast.LENGTH_LONG).show() }
    }

    // a custom provider's model has none of Claude's model options
    val custom = ClaudeChatBackend.providerOf(selectedModel).isNotEmpty()
    val info = if (custom) null else modelInfos.find { it.value == selectedModel } ?: modelInfos.firstOrNull()
    var modeMenu by remember { mutableStateOf(false) }
    var effortMenu by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    var showCache by remember { mutableStateOf(false) }

    // re-evaluate the cache timer every 30 s
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // permission mode
        ControlChip(
            text = permissionModeLabel(mode),
            icon = permissionModeIcon(mode),
            color = permissionModeColor(mode),
            highlighted = mode != "default",
            onClick = { modeMenu = true }
        )

        // effort
        val levels = info?.supportedEffortLevels.orEmpty()
        if (levels.isNotEmpty()) {
            ControlChip(
                // "Default" shows the level the process really runs when the CLI reported it
                text = "Effort · ${effortLabel(effort)}" + if (effort == null && appliedEffort != null) " (${effortLabel(appliedEffort)})" else "",
                icon = Icons.Outlined.Speed,
                color = if (effort != null) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
                highlighted = effort != null,
                onClick = { effortMenu = true }
            )
        }

        // thinking
        ControlChip(
            text = if (thinking) "Thinking" else "No thinking",
            icon = Icons.Outlined.Lightbulb,
            color = if (thinking) ClaudeAccent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            highlighted = thinking,
            onClick = { backend.setThinking(!thinking) }
        )

        // prompt cache
        cache?.let { c ->
            val left = c.anchorMs + c.ttlMs - now
            val warm = !c.compacted && c.ttlMs > 0 && left > 0
            ControlChip(
                text = if (warm) "Cache ${((left + 59_999) / 60_000)}m" else "Cache cold",
                icon = Icons.Outlined.Timer,
                color = if (warm) QuotaGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                onClick = { showCache = true }
            )
        }

        // context window
        context?.let { ctx ->
            val pct = ctx.percentage
            ControlChip(
                text = "Context ${pct.toInt()}%",
                icon = Icons.Outlined.DataUsage,
                color = usageColor(pct / 100.0),
                onClick = { showContext = true }
            )
        }

        // plan usage (live), only for Claude's own models
        limits["five_hour"]?.takeIf { !custom }?.let { w ->
            ControlChip(text = "5h ${(w.utilization * 100).toInt()}%", icon = Icons.Outlined.Bolt, color = usageColor(w.utilization))
        }
    }

    if (modeMenu) {
        val autoSupported = !custom && info?.supportsAutoMode != false
        ClaudePermissionModeSheet(
            current = mode,
            modes = ClaudeChatBackend.PERMISSION_MODES.filter { (value, _) -> value != "auto" || autoSupported },
            note = if (autoSupported) {
                "Auto asks only for risky actions. For fewer prompts without giving up checks, turn on Sandbox commands in Settings → Claude Code → Permissions."
            } else {
                "This model has no Auto mode; chats on it start in Manual."
            },
            onSelect = { backend.setPermissionMode(it) },
            onDismiss = { modeMenu = false }
        )
    }
    if (effortMenu) {
        ClaudeEffortSheet(
            levels = info?.supportedEffortLevels.orEmpty(),
            current = effort,
            onSelect = { backend.setEffort(it) },
            onDismiss = { effortMenu = false }
        )
    }

    if (showContext) ClaudeContextDialog(backend) { showContext = false }

    if (showCache) {
        val c = cache
        AlertDialog(
            onDismissRequest = { showCache = false },
            title = { Text("Prompt cache", fontWeight = FontWeight.Bold) },
            text = {
                val left = c?.let { it.anchorMs + it.ttlMs - now } ?: 0
                Text(
                    when {
                        c == null -> "No cache information yet."
                        c.compacted -> "The conversation was compacted, so your next message will re-cache it."
                        left > 0 -> "Cache warm, about ${(left + 59_999) / 60_000} min left (${if (c.ttlMs >= 3_600_000) "1-hour" else "5-minute"} cache). " +
                            "Messages sent before it expires reuse it and cost less."
                        else -> "The prompt cache has likely expired, so your next message will re-cache about ${compactTokens(c.recacheTokens)} tokens."
                    },
                    fontSize = 13.5.sp
                )
            },
            confirmButton = { TextButton(onClick = { showCache = false }) { Text("OK") } }
        )
    }
}


/** Context window usage of the open chat (the `/context` command). */
@Composable
fun ClaudeContextDialog(backend: ClaudeChatBackend, onDismiss: () -> Unit) {
    val ctx by backend.contextUsage.collectAsState()
    LaunchedEffect(Unit) { backend.refreshContextUsage() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Context window", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val c = ctx
                if (c == null) {
                    Text("Context usage shows once this chat has started (send a message first).", fontSize = 13.sp)
                } else {
                    Text("${compactTokens(c.totalTokens)} of ${compactTokens(c.maxTokens)} tokens used (${c.percentage.toInt()}%)", fontSize = 13.sp)
                    UsageBar("Used", c.percentage / 100.0, null)
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    c.categories.forEach { cat ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(cat.name, fontSize = 12.5.sp)
                            Text(compactTokens(cat.tokens), fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                    Text(
                        "Send /compact to summarize the conversation and free space.",
                        fontSize = 11.5.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { backend.refreshContextUsage() }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/** Plan limits and this chat's cost (the `/usage` command). */
@Composable
fun ClaudeUsageDialog(account: ClaudeAccountManager, backend: ClaudeChatBackend, onDismiss: () -> Unit) {
    val usage by account.usage.collectAsState()
    val status by account.status.collectAsState()
    val live by backend.rateLimits.collectAsState()
    val cost by backend.sessionCost.collectAsState()
    val selected by backend.selectedModelId.collectAsState()
    // usage and cost are shown for Claude's own models only (a provider's cost from the CLI is a guess)
    if (ClaudeChatBackend.providerOf(selected).isNotEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Usage", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "This chat runs on ${backend.modelLabel(selected)}, a custom provider. Plan usage and cost are only shown for " +
                        "Claude's own models; check the provider's dashboard for this chat's usage.",
                    fontSize = 13.sp
                )
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
        )
        return
    }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        account.refreshUsage(force = true)
        delay(1_500)
        loading = false
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Usage", fontWeight = FontWeight.Bold)
                val plan = (usage?.subscription_type ?: status?.auth?.subscriptionType)?.let { com.example.gemini.ui.agents.claudePlanLabel(it) }
                Text(
                    listOfNotNull<String>("Claude Code", plan?.let { "$it plan" }).joinToString(" · "),
                    fontSize = 12.sp,
                    color = ClaudeAccent
                )
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val rl = usage?.rate_limits
                // live windows from the chat stream are fresher than the cached call
                val five = live["five_hour"]?.let { it.utilization to formatReset(null, it.resetsAt) }
                    ?: rl?.five_hour?.let { (it.utilization ?: 0.0) / 100.0 to formatReset(it.resets_at) }
                val week = live["seven_day"]?.let { it.utilization to formatReset(null, it.resetsAt) }
                    ?: rl?.seven_day?.let { (it.utilization ?: 0.0) / 100.0 to formatReset(it.resets_at) }
                when {
                    five != null || week != null -> {
                        five?.let { UsageBar("Current session (5-hour)", it.first, it.second) }
                        week?.let { UsageBar("This week (all models)", it.first, it.second) }
                        // a model's own weekly share (Fable on Max), on top of the all-models limit
                        ClaudeQuotaGroups.scopedLimits(rl).forEach { UsageBar("This week (${it.name})", it.used, formatReset(it.resetsAt)) }
                    }
                    loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = ClaudeAccent)
                        Spacer(Modifier.width(10.dp))
                        Text("Loading plan usage…", fontSize = 13.sp)
                    }
                    usage != null && !usage!!.rate_limits_available ->
                        Text("Plan limits do not apply to this account (API billing has no 5-hour or weekly limits).", fontSize = 13.sp)
                    else -> Text("Plan usage is not available right now.", fontSize = 13.sp)
                }
                HorizontalDivider()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("This chat", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                    Text(if (cost > 0) String.format(java.util.Locale.US, "$%.2f", cost) else "–", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    "Cost is what this chat would cost at API prices; on a subscription it counts toward the limits above.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
        },
        confirmButton = { TextButton(onClick = { account.refreshUsage(force = true) }) { Text("Refresh") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}

/**
 * Asked before a chat with messages moves to another provider: the process restarts and the whole conversation is
 * sent again without cache. A new chat is the recommended way; switching anyway unlocks after a short delay.
 */
@Composable
fun ClaudeProviderSwitchDialog(backend: ClaudeChatBackend) {
    val sw by backend.pendingProviderSwitch.collectAsState()
    val pending = sw ?: return
    val running by backend.isCurrentChatActivelyRunning.collectAsState()
    var wait by remember(pending) { mutableStateOf(3) }
    LaunchedEffect(pending) {
        while (wait > 0) {
            delay(1_000)
            wait--
        }
    }
    val muted = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    AlertDialog(
        onDismissRequest = { backend.cancelProviderSwitch() },
        title = { Text("⚠️ Switch provider in this chat?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This chat runs on ${pending.fromLabel}. Moving it to ${pending.toLabel} is not a good idea:", fontSize = 13.5.sp)
                Text("• Claude Code is stopped and started again for this chat.", fontSize = 13.sp, color = muted)
                Text(
                    "• The whole conversation" + (pending.resendTokens?.let { " (about ${compactTokens(it)} tokens)" } ?: "") +
                        " is sent to the new provider again, without cache, so the next reply costs far more than usual.",
                    fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = QuotaRed
                )
                Text("• Thinking, effort and Auto mode may not carry over, and the new provider may reject or misread earlier replies.", fontSize = 13.sp, color = muted)
                Text("Starting a new chat is usually the better choice.", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(
                    onClick = { backend.startNewChatWithPendingModel() },
                    colors = ButtonDefaults.buttonColors(containerColor = ClaudeAccent)
                ) { Text("Start a new chat with it") }
                TextButton(onClick = { backend.cancelProviderSwitch() }) { Text("Cancel") }
                TextButton(onClick = { backend.confirmProviderSwitch() }, enabled = wait == 0) {
                    Text(
                        (if (running) "Switch after this reply" else "Switch anyway") + if (wait > 0) " ($wait)" else "",
                        color = if (wait == 0) QuotaRed else QuotaRed.copy(alpha = 0.4f),
                        fontSize = 12.5.sp
                    )
                }
            }
        }
    )
}
