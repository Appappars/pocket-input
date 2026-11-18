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

/**
 * Собственная тема фазы 1: холодный графит + бирюзовый акцент.
 * Тёмная схема объявлена явно (не дефолтная фиолетовая material3).
 *
 * @param theme "system" | "dark" | "light" из [SettingsState.theme].
 */
private val Teal = Color(0xFF2ED3B7)
private val TealLight = Color(0xFF0E8F7C)
private val TealDark = Color(0xFF6BE8CE)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF00352C),
    primaryContainer = Color(0xFF10514A),
    onPrimaryContainer = Color(0xFFB8F5E7),
    secondary = Color(0xFF9AC7BE),
    onSecondary = Color(0xFF0B3730),
    secondaryContainer = Color(0xFF21322F),
    onSecondaryContainer = Color(0xFFD3E8E3),
    tertiary = Color(0xFFB6C6D4),
    onTertiary = Color(0xFF1B2A34),
    background = Color(0xFF0E1214),
    onBackground = Color(0xFFE2E8E6),
    surface = Color(0xFF12181A),
    onSurface = Color(0xFFE2E8E6),
    surfaceVariant = Color(0xFF26302F),
    onSurfaceVariant = Color(0xFFB2BFBC),
    surfaceContainer = Color(0xFF161D1F),
    outline = Color(0xFF6C7A78),
    outlineVariant = Color(0xFF3A4644),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
)

private val LightColors = lightColorScheme(
    primary = TealLight,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFB8F5E7),
    onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635E),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDE8E1),
    onSecondaryContainer = Color(0xFF06201B),
    tertiary = Color(0xFF435C6B),
    onTertiary = Color.White,
    background = Color(0xFFF6FAF9),
    onBackground = Color(0xFF171D1C),
    surface = Color(0xFFFBFDFC),
    onSurface = Color(0xFF171D1C),
    surfaceVariant = Color(0xFFDBE5E2),
    onSurfaceVariant = Color(0xFF3F4947),
    surfaceContainer = Color(0xFFEDF3F1),
    outline = Color(0xFF6F7977),
    outlineVariant = Color(0xFFBEC9C6),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
)

/** Тело текста чуть крупнее дефолта — читаемость на телефоне, который держат одной рукой. */
private val PocketTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = base.titleLarge.copy(fontSize = 24.sp, fontWeight = FontWeight.SemiBold),
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