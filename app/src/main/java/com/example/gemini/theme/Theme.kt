package com.example.gemini.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import android.app.UiModeManager
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
private val DarkColorScheme = darkColorScheme(
    primary = AntiBotCyan,
    secondary = AntiBotBlue,
    background = AntiBotSpaceBg,
    surface = AntiBotSurfaceSlate,
    surfaceTint = Color.Transparent,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = AntiBotTextWhite,
    onSurface = AntiBotTextWhite,
    surfaceVariant = AntiBotCardSurface,
    onSurfaceVariant = AntiBotTextMuted,
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B)
)

private val LightColorScheme = lightColorScheme(
    primary = AntiBotBlue,
    secondary = AntiBotCyan,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceTint = Color.Transparent,
    onPrimary = Color.White,
    onSecondary = Color.Black,
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFF1F5F9),
    onSurfaceVariant = Color(0xFF64748B),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0)
)

private val NeonColorScheme = darkColorScheme(
    primary = NeonCyan,
    secondary = NeonCyanDark,
    background = NeonCarbonBg,
    surface = NeonCarbonSurface,
    surfaceTint = Color.Transparent,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color(0xFFF0F6FC),
    onSurface = Color(0xFFF0F6FC),
    surfaceVariant = NeonBubbleDark,
    onSurfaceVariant = Color(0xFF8B949E),
    outline = Color(0xFF30363D),
    outlineVariant = Color(0xFF21262D)
)

private val SapphireColorScheme = darkColorScheme(
    primary = SapphireBlue,
    secondary = SapphireBlueDark,
    background = SapphireNavyBg,
    surface = SapphireNavySurface,
    surfaceTint = Color.Transparent,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    surfaceVariant = SapphireBubbleDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF1E293B),
    outlineVariant = Color(0xFF0F172A)
)

private val EmeraldColorScheme = darkColorScheme(
    primary = EmeraldGreen,
    secondary = EmeraldGreenDark,
    background = EmeraldMatrixBg,
    surface = EmeraldMatrixSurface,
    surfaceTint = Color.Transparent,
    onPrimary = Color.Black,
    onSecondary = Color.White,
    onBackground = Color(0xFFECFDF5),
    onSurface = Color(0xFFECFDF5),
    surfaceVariant = EmeraldBubbleDark,
    onSurfaceVariant = Color(0xFF6EE7B7).copy(alpha = 0.7f),
    outline = Color(0xFF064E3B),
    outlineVariant = Color(0xFF022C22)
)

private val AmethystColorScheme = darkColorScheme(
    primary = AmethystPurple,
    secondary = AmethystPurpleDark,
    background = AmethystCosmicBg,
    surface = AmethystCosmicSurface,
    surfaceTint = Color.Transparent,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = Color(0xFFFAF5FF),
    onSurface = Color(0xFFFAF5FF),
    surfaceVariant = AmethystBubbleDark,
    onSurfaceVariant = Color(0xFFC084FC).copy(alpha = 0.7f),
    outline = Color(0xFF581C87),
    outlineVariant = Color(0xFF3B0764)
)

private val OledColorScheme = darkColorScheme(
    primary = OledAccent,
    secondary = OledAccent,
    background = OledBlackBg,
    surface = OledBlackSurface,
    surfaceTint = Color.Transparent,
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = OledBubbleDark,
    onSurfaceVariant = Color(0xFFA3A3A3),
    outline = Color(0xFF262626),
    outlineVariant = Color(0xFF171717)
)

val LocalIsDarkTheme = staticCompositionLocalOf { false }

@Composable
fun isAppInDarkTheme(): Boolean = LocalIsDarkTheme.current

@Composable
fun isSystemInDarkThemeRobust(): Boolean {
    val context = LocalContext.current
    val composeIsDark = isSystemInDarkTheme()

    var secureNightMode by remember {
        mutableIntStateOf(
            try {
                Settings.Secure.getInt(context.contentResolver, "ui_night_mode", -1)
            } catch (e: Exception) {
                -1
            }
        )
    }

    DisposableEffect(context) {
        val uri = Settings.Secure.getUriFor("ui_night_mode")
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                try {
                    secureNightMode = Settings.Secure.getInt(context.contentResolver, "ui_night_mode", -1)
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }
        try {
            context.contentResolver.registerContentObserver(uri, false, observer)
        } catch (e: Exception) {
            // Ignore
        }
        onDispose {
            try {
                context.contentResolver.unregisterContentObserver(observer)
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    val uiModeManager = remember(context) {
        context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
    }
    val isMgrDark = uiModeManager?.nightMode == UiModeManager.MODE_NIGHT_YES

    return when {
        secureNightMode == 2 -> true
        secureNightMode == 1 -> false
        isMgrDark -> true
        else -> composeIsDark
    }
}

@Composable
fun GeminiTheme(
    themeMode: String = "SYSTEM",
    darkTheme: Boolean = isSystemInDarkThemeRobust(),
    content: @Composable () -> Unit
) {
    val colorScheme = when (themeMode.uppercase()) {
        "LIGHT" -> LightColorScheme
        "DARK" -> DarkColorScheme
        "NEON" -> NeonColorScheme
        "SAPPHIRE" -> SapphireColorScheme
        "EMERALD" -> EmeraldColorScheme
        "AMETHYST" -> AmethystColorScheme
        "OLED" -> OledColorScheme
        else -> if (darkTheme) DarkColorScheme else LightColorScheme
    }

    val effectiveDark = when (themeMode.uppercase()) {
        "LIGHT" -> false
        "DARK", "NEON", "SAPPHIRE", "EMERALD", "AMETHYST", "OLED" -> true
        else -> darkTheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        val activity = view.context as? Activity
        if (activity != null && activity !is com.example.gemini.ui.bubble.FloatingChatActivity) {
            SideEffect {
                val window = activity.window
                window.statusBarColor = colorScheme.background.toArgb()
                window.navigationBarColor = colorScheme.background.toArgb()
                val insetsController = WindowCompat.getInsetsController(window, view)
                insetsController.isAppearanceLightStatusBars = !effectiveDark
                insetsController.isAppearanceLightNavigationBars = !effectiveDark
            }
        }
    }

    CompositionLocalProvider(
        LocalIsDarkTheme provides effectiveDark
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
