package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.AppTheme
import com.example.gemini.theme.BuiltInThemes
import com.example.gemini.theme.ThemeManager

/**
 * Settings → Appearance: Unified theme gallery.
 * Pick a theme from previews of every built-in and custom theme.
 * Each theme supports both Auto, Dark, and Light modes.
 */
@Composable
fun ThemeGallery(cardBg: Color, themeMode: String, onSetThemeMode: (String) -> Unit) {
    val lightId = ThemeManager.lightId
    val darkId = ThemeManager.darkId
    val custom = ThemeManager.customThemes
    val all = remember(custom) { ThemeManager.all() }
    var toDelete by remember { mutableStateOf<AppTheme?>(null) }

    fun pick(t: AppTheme) {
        ThemeManager.select(t, switchMode = false)
    }

    val activeSelectedId = if (ThemeManager.isDark) darkId else lightId

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GalleryHeader("Theme Gallery", "Select a design. Each theme provides comprehensive Dark & Light support.")
        ThemeGrid(all, activeSelectedId, cardBg, ::pick) { toDelete = it }
        Text(
            "Make your own: add the Theme preset in MCP Servers and ask any chat, e.g. " +
                "“create a neon cyberpunk dark theme and apply it”. Jarvis can switch themes by voice too.",
            fontSize = 11.5.sp, lineHeight = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    toDelete?.let { t ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Delete “${t.name}”?") },
            text = { Text("This custom theme is removed. If it is in use, the default theme takes its place.") },
            confirmButton = { TextButton(onClick = { ThemeManager.deleteCustom(t.id); toDelete = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun GalleryHeader(title: String, hint: String) {
    Column {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(hint, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}

@Composable
private fun ThemeGrid(themes: List<AppTheme>, selectedId: String, cardBg: Color, onPick: (AppTheme) -> Unit, onDelete: (AppTheme) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    ThemeCard(t, t.id == selectedId, cardBg, Modifier.weight(1f), { onPick(t) }, if (t.builtIn) null else ({ onDelete(t) }))
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * A small live preview: the theme's background, a card with text lines and a button, your bubble, and status dots.
 * Also used by the floating [ThemeQuickPanel].
 */
@Composable
internal fun ThemeCard(t: AppTheme, selected: Boolean, cardBg: Color, modifier: Modifier, onClick: () -> Unit, onDelete: (() -> Unit)?) {
    val c = remember(t) { ThemeManager.resolvedColors(t).mapValues { Color(it.value.toInt()) } }
    fun col(k: String, d: Color = Color.Gray) = c[k] ?: d
    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .clip(shape)
            .background(cardBg)
            .border(if (selected) 2.dp else 1.dp, if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f), shape)
            .clickable { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Preview box
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(col("background"))
                .padding(8.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // simulated assistant bubble
                Row(
                    Modifier
                        .fillMaxWidth(0.85f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(col("card"))
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(5.dp).clip(CircleShape).background(col("primary")))
                    Box(Modifier.height(4.dp).weight(1f).clip(RoundedCornerShape(2.dp)).background(col("on_surface").copy(alpha = 0.5f)))
                }
                // simulated user bubble
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(col("user_bubble"))
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    ) {
                        Box(Modifier.height(4.dp).fillMaxWidth(0.7f).clip(RoundedCornerShape(2.dp)).background(col("on_surface").copy(alpha = 0.7f)))
                    }
                }
            }
            // miniature badge dots
            Row(
                Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("syntax_keyword", "syntax_string", "syntax_number", "secondary").forEach { key ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(col(key)))
                }
            }
        }

        // Title row with select indicator
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    t.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (t.builtIn) "Built-in" else "Custom",
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            } else if (onDelete != null) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickable { onDelete() }
                )
            }
        }
    }
}
