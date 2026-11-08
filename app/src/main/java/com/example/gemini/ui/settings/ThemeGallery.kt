package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.example.gemini.theme.ThemeTemplate

/**
 * Settings → Appearance: Unified theme gallery.
 * Pick a theme from unified templates where each single template holds both Dark & Light modes together.
 * The active preview dynamically adapts to the selected display mode (Auto / Dark / Light).
 */
@Composable
fun ThemeGallery(cardBg: Color, themeMode: String, onSetThemeMode: (String) -> Unit) {
    val templates = remember { BuiltInThemes.templates }
    val activeTemplate = ThemeManager.activeTemplate()
    val isDark = ThemeManager.isDark
    val custom = ThemeManager.customThemes
    var toDelete by remember { mutableStateOf<AppTheme?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GalleryHeader("Unified Theme Templates", "Each template pairs matching Dark & Light modes together and changes dynamically with your display mode.")
        TemplateGrid(templates, activeTemplate.id, cardBg) { template ->
            ThemeManager.selectTemplate(template)
        }

        if (custom.isNotEmpty()) {
            Spacer(modifier = Modifier.height(4.dp))
            GalleryHeader("Custom Extracted Themes", "Themes generated or imported via MCP tools.")
            CustomThemesGrid(custom, if (isDark) ThemeManager.darkId else ThemeManager.lightId, cardBg,
                onPick = { ThemeManager.select(it, switchMode = false) },
                onDelete = { toDelete = it }
            )
        }

        Text(
            "Make your own: add the Theme preset in MCP Servers and ask any chat, e.g. " +
                "“create a minimalist monochrome dark theme and apply it”. AntiBot switches themes seamlessly.",
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
private fun TemplateGrid(
    templates: List<ThemeTemplate>,
    selectedId: String,
    cardBg: Color,
    onPick: (ThemeTemplate) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        templates.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { template ->
                    ThemeTemplateCard(
                        template = template,
                        selected = template.id == selectedId,
                        cardBg = cardBg,
                        modifier = Modifier.weight(1f),
                        onClick = { onPick(template) }
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CustomThemesGrid(
    themes: List<AppTheme>,
    selectedId: String,
    cardBg: Color,
    onPick: (AppTheme) -> Unit,
    onDelete: (AppTheme) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        themes.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                row.forEach { t ->
                    ThemeCard(t, t.id == selectedId, cardBg, Modifier.weight(1f), { onPick(t) }, { onDelete(t) })
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * Live preview card for a unified ThemeTemplate.
 * Previews the template in the active display mode (dark or light) and shows dual-mode dots.
 */
@Composable
fun ThemeTemplateCard(
    template: ThemeTemplate,
    selected: Boolean,
    cardBg: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = ThemeManager.isDark
    val currentTheme = remember(template, isDark) { ThemeManager.themeForTemplate(template, isDark) }
    val darkTheme = remember(template) { ThemeManager.find(template.darkThemeId) ?: BuiltInThemes.default(true) }
    val lightTheme = remember(template) { ThemeManager.find(template.lightThemeId) ?: BuiltInThemes.default(false) }

    val c = remember(currentTheme) { ThemeManager.resolvedColors(currentTheme).mapValues { Color(it.value.toInt()) } }
    fun col(k: String, d: Color = Color.Gray) = c[k] ?: d
    val darkBg = remember(darkTheme) { Color(ThemeManager.resolvedColors(darkTheme)["background"]?.toInt() ?: 0xFF080B12.toInt()) }
    val lightBg = remember(lightTheme) { Color(ThemeManager.resolvedColors(lightTheme)["background"]?.toInt() ?: 0xFFFFFFFF.toInt()) }

    val accent = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)

    Column(
        modifier
            .clip(shape)
            .background(cardBg)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                shape
            )
            .clickable { onClick() }
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Preview box rendered with active mode colors
        Box(
            Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(col("background"))
                .border(0.5.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
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
                        .padding(start = 20.dp),
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

            // Dual mode dots preview (dark & light pair preview at bottom right)
            Row(
                Modifier.align(Alignment.BottomEnd),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(darkBg)
                        .border(0.5.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                )
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(lightBg)
                        .border(0.5.dp, Color.Black.copy(alpha = 0.2f), CircleShape)
                )
            }

            // Miniature badge dots at bottom left
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
                    template.name,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    template.about,
                    fontSize = 10.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selected) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Selected",
                    tint = accent,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * A small live preview for custom or individual themes.
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
            Row(
                Modifier.align(Alignment.BottomStart),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf("syntax_keyword", "syntax_string", "syntax_number", "secondary").forEach { key ->
                    Box(Modifier.size(6.dp).clip(CircleShape).background(col(key)))
                }
            }
        }

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
