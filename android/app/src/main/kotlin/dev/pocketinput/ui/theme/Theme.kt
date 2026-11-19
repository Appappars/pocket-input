package dev.pocketinput.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.pocketinput.data.SettingsState

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C8F8),
    onPrimary = Color(0xFF132B4C),
    primaryContainer = Color(0xFF28466C),
    onPrimaryContainer = Color(0xFFD9E8FB),
    secondary = Color(0xFFABC0D9),
    onSecondary = Color(0xFF1D3046),
    secondaryContainer = Color(0xFF2B3E55),
    onSecondaryContainer = Color(0xFFE4EEF9),
    tertiary = Color(0xFF80CDA2),
    onTertiary = Color(0xFF163827),
    background = Color(0xFF131C29),
    onBackground = Color(0xFFE7EFF8),
    surface = Color(0xFF1B2939),
    onSurface = Color(0xFFE7EFF8),
    surfaceVariant = Color(0xFF24354A),
    onSurfaceVariant = Color(0xFFB5C9DF),
    surfaceContainer = Color(0xFF26384E),
    surfaceContainerHigh = Color(0xFF2A3C52),
    outline = Color(0xFF92A8C0),
    outlineVariant = Color(0xFF667F9A),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF642D23),
    onErrorContainer = Color(0xFFFFDBD0),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF214F96),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD4E3F8),
    onPrimaryContainer = Color(0xFF152E52),
    secondary = Color(0xFF405B75),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2EAF4),
    onSecondaryContainer = Color(0xFF12263D),
    tertiary = Color(0xFF2B7955),
    onTertiary = Color.White,
    background = Color(0xFFEDF2F7),
    onBackground = Color(0xFF12263D),
    surface = Color(0xFFFDFEFF),
    onSurface = Color(0xFF12263D),
    surfaceVariant = Color(0xFFDDE7F1),
    onSurfaceVariant = Color(0xFF405B75),
    surfaceContainer = Color(0xFFE2EAF4),
    surfaceContainerHigh = Color(0xFFD9E4F0),
    outline = Color(0xFF596E87),
    outlineVariant = Color(0xFF72869F),
    error = Color(0xFF963A25),
    onError = Color.White,
    errorContainer = Color(0xFFF8E0D5),
    onErrorContainer = Color(0xFF532218),
)

/** Тело текста чуть крупнее дефолта — читаемость на телефоне, который держат одной рукой. */
private val PocketTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontSize = 18.sp, fontWeight = FontWeight.Medium),
        bodyLarge = base.bodyLarge.copy(fontSize = 17.sp, lineHeight = 24.sp),
        bodyMedium = base.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = base.labelLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun PocketInputTheme(theme: String = SettingsState.THEME_SYSTEM, content: @Composable () -> Unit) {
    val dark = when (theme) {
        SettingsState.THEME_DARK -> true
        SettingsState.THEME_LIGHT -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = PocketTypography,
        content = content,
    )
}
