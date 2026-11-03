package app.kinframe.core.design

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * kinframe's calm, warm visual language. Shared by the frame app and the family app.
 * Colours are a paper-and-terracotta palette with WCAG AA contrast for text on [KinColors.paper].
 */
@Immutable
data class KinColors(
    val paper: Color = Color(0xFFFAF5EE),
    val paperDeep: Color = Color(0xFFF1E8DC),
    val surface: Color = Color(0xFFFFFDFA),
    val ink: Color = Color(0xFF2E2621),
    val inkSoft: Color = Color(0xFF6B5E55),
    val inkFaint: Color = Color(0xFFA39588),
    val line: Color = Color(0xFFE6DACB),
    val accent: Color = Color(0xFFC0623E),
    val accentPressed: Color = Color(0xFFA2502F),
    val onAccent: Color = Color(0xFFFFFFFF),
    val accentSoft: Color = Color(0xFFF6DED2),
    val sage: Color = Color(0xFF5F7F68),
    val sageSoft: Color = Color(0xFFDDE8DF),
    val danger: Color = Color(0xFFB3412F),
    val love: Color = Color(0xFFE5484D),
    val night: Color = Color(0xFF1C1714),
    val scrim: Color = Color(0xB3000000),
    /** Text on [accentSoft]. */
    val onAccentSoft: Color = Color(0xFF7C3412),
    /** Text on [sageSoft]. */
    val onSageSoft: Color = Color(0xFF2F5A3C),
    val warn: Color = Color(0xFF8A5A00),
    val warnSoft: Color = Color(0xFFFBEFD3),
    val dangerSoft: Color = Color(0xFFFBE4E1),
    val sun: Color = Color(0xFFE7A23A),
    /** Plugin views' `info` tone: grid consumption on the P1 dial. */
    val violet: Color = Color(0xFF6E46A8),
    /** Plugin views' quantity tones (docs/plugins.md §4.3): ≥ 3:1 on paper, told apart from the others with an icon. */
    val water: Color = Color(0xFF1B7FD1),
    val gas: Color = Color(0xFFA8306E),
    val heat: Color = Color(0xFFD2691E),
    val isDark: Boolean = false,
)

/** Palettes for phones and tablets, matching the web app's tokens (web/src/app.css). */
object KinPalettes {
    /** The family app's light palette: slightly deeper accent than the frame for small text. */
    val light = KinColors(
        paper = Color(0xFFF8F3EC),
        paperDeep = Color(0xFFF3EBE0),
        surface = Color(0xFFFFFDF9),
        ink = Color(0xFF2A221D),
        inkSoft = Color(0xFF675A50),
        inkFaint = Color(0xFF8B7D71),
        line = Color(0xFFE4D8CA),
        accent = Color(0xFFA7471E),
        accentPressed = Color(0xFF8D3B18),
        onAccent = Color(0xFFFFFFFF),
        accentSoft = Color(0xFFF7E4D8),
        onAccentSoft = Color(0xFF7C3412),
        sage = Color(0xFF3C6A49),
        sageSoft = Color(0xFFE2EEE3),
        onSageSoft = Color(0xFF2F5A3C),
        danger = Color(0xFFB3261E),
        dangerSoft = Color(0xFFFBE4E1),
        warn = Color(0xFF8A5A00),
        warnSoft = Color(0xFFFBEFD3),
    )

    val dark = KinColors(
        paper = Color(0xFF191512),
        paperDeep = Color(0xFF2C2621),
        surface = Color(0xFF231E1A),
        ink = Color(0xFFF3EBE3),
        inkSoft = Color(0xFFBDAEA1),
        inkFaint = Color(0xFF94867A),
        line = Color(0xFF3A322B),
        accent = Color(0xFFF09A6D),
        accentPressed = Color(0xFFF6B08B),
        onAccent = Color(0xFF2A1206),
        accentSoft = Color(0xFF3D2519),
        onAccentSoft = Color(0xFFF6C1A3),
        sage = Color(0xFF8CC39B),
        sageSoft = Color(0xFF1F3226),
        onSageSoft = Color(0xFFA6D6B2),
        danger = Color(0xFFF28B82),
        dangerSoft = Color(0xFF3D1D1A),
        warn = Color(0xFFF0C066),
        warnSoft = Color(0xFF3A2D12),
        love = Color(0xFFFF6B6F),
        night = Color(0xFF0F0C0A),
        sun = Color(0xFFE7A23A),
        violet = Color(0xFFC3A6F0),
        water = Color(0xFF88DDF7),
        gas = Color(0xFFE36FA9),
        heat = Color(0xFFE8752A),
        isDark = true,
    )
}

@Immutable
data class KinType(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodyStrong: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val code: TextStyle,
)

@Immutable
data class KinSpacing(
    val xs: Dp = 4.dp,
    val s: Dp = 8.dp,
    val m: Dp = 16.dp,
    val l: Dp = 24.dp,
    val xl: Dp = 40.dp,
    val xxl: Dp = 64.dp,
    /** Minimum touch target on the frame (larger than the 48 dp phone minimum). */
    val touch: Dp = 64.dp,
)

/** Size presets: the frame is read from across the room, phones from 30 cm. */
enum class KinDensity { Frame, Phone }

private fun kinType(scale: Float): KinType {
    val f = FontFamily.SansSerif
    return KinType(
        display = TextStyle(fontFamily = f, fontWeight = FontWeight.Light, fontSize = (56 * scale).sp, lineHeight = (64 * scale).sp),
        headline = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = (34 * scale).sp, lineHeight = (42 * scale).sp),
        title = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = (24 * scale).sp, lineHeight = (32 * scale).sp),
        body = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = (19 * scale).sp, lineHeight = (28 * scale).sp),
        bodyStrong = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = (19 * scale).sp, lineHeight = (28 * scale).sp),
        label = TextStyle(fontFamily = f, fontWeight = FontWeight.Medium, fontSize = (18 * scale).sp, lineHeight = (24 * scale).sp, letterSpacing = (0.2).sp),
        caption = TextStyle(fontFamily = f, fontWeight = FontWeight.Normal, fontSize = (15 * scale).sp, lineHeight = (20 * scale).sp),
        code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = (72 * scale).sp, lineHeight = (80 * scale).sp, letterSpacing = (4).sp),
    )
}

/**
 * Phone type scale: body 16 sp like the web app, headings in a serif for warmth (the web uses Fraunces).
 * Sizes are in sp, so the system font size setting (dynamic type) applies.
 */
private fun phoneType(): KinType {
    val sans = FontFamily.SansSerif
    val serif = FontFamily.Serif
    return KinType(
        display = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp),
        headline = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = (-0.2).sp),
        title = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 19.sp, lineHeight = 25.sp),
        body = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 23.sp),
        bodyStrong = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 23.sp),
        label = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = (0.1).sp),
        caption = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 18.sp),
        code = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 30.sp, lineHeight = 38.sp, letterSpacing = 3.sp),
    )
}

val LocalKinColors = staticCompositionLocalOf { KinColors() }
val LocalKinType = staticCompositionLocalOf { kinType(1f) }
val LocalKinSpacing = staticCompositionLocalOf { KinSpacing() }

/** Accessor: `Kin.colors.accent`, `Kin.type.title`, `Kin.space.m`. */
object Kin {
    val colors: KinColors @Composable get() = LocalKinColors.current
    val type: KinType @Composable get() = LocalKinType.current
    val space: KinSpacing @Composable get() = LocalKinSpacing.current
}

private fun materialColors(c: KinColors): ColorScheme =
    if (c.isDark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.onAccentSoft,
            secondary = c.sage, onSecondary = c.paper, secondaryContainer = c.sageSoft, onSecondaryContainer = c.onSageSoft,
            background = c.paper, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.paperDeep, onSurfaceVariant = c.inkSoft, surfaceContainer = c.surface,
            surfaceContainerHigh = c.paperDeep, surfaceContainerHighest = c.paperDeep, surfaceContainerLow = c.surface,
            outline = c.line, outlineVariant = c.line, error = c.danger, errorContainer = c.dangerSoft,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, primaryContainer = c.accentSoft, onPrimaryContainer = c.ink,
            secondary = c.sage, secondaryContainer = c.sageSoft, onSecondaryContainer = c.onSageSoft,
            background = c.paper, onBackground = c.ink, surface = c.surface, onSurface = c.ink,
            surfaceVariant = c.paperDeep, onSurfaceVariant = c.inkSoft, surfaceContainer = c.surface,
            surfaceContainerHigh = c.paperDeep, surfaceContainerHighest = c.paperDeep, surfaceContainerLow = c.surface,
            outline = c.line, outlineVariant = c.line, error = c.danger, errorContainer = c.dangerSoft,
        )
    }

/** The frame's scheme, unchanged since v0.1. */
private fun frameColors(c: KinColors): ColorScheme = lightColorScheme(
    primary = c.accent,
    onPrimary = c.onAccent,
    primaryContainer = c.accentSoft,
    onPrimaryContainer = c.ink,
    secondary = c.sage,
    secondaryContainer = c.sageSoft,
    background = c.paper,
    onBackground = c.ink,
    surface = c.surface,
    onSurface = c.ink,
    onSurfaceVariant = c.inkSoft,
    outline = c.line,
    error = c.danger,
)

private fun materialType(t: KinType) = Typography(
    displaySmall = t.display, headlineMedium = t.headline, headlineSmall = t.headline, titleLarge = t.title,
    titleMedium = t.bodyStrong, titleSmall = t.label, bodyLarge = t.body, bodyMedium = t.body, bodySmall = t.caption,
    labelLarge = t.label, labelMedium = t.caption, labelSmall = t.caption,
)

/**
 * Root theme. [textScale] implements the frame's text-size setting (1.0 / 1.15 / 1.3).
 * The family app passes [density] = Phone and its light or dark palette.
 */
@Composable
fun KinframeTheme(
    textScale: Float = 1f,
    density: KinDensity = KinDensity.Frame,
    colors: KinColors = KinColors(),
    content: @Composable () -> Unit,
) {
    val type = if (density == KinDensity.Phone) phoneType() else kinType(textScale)
    val spacing = if (density == KinDensity.Phone) KinSpacing(touch = 48.dp) else KinSpacing()
    CompositionLocalProvider(
        LocalKinColors provides colors,
        LocalKinType provides type,
        LocalKinSpacing provides spacing,
    ) {
        MaterialTheme(
            colorScheme = if (density == KinDensity.Phone) materialColors(colors) else frameColors(colors),
            typography = if (density == KinDensity.Phone) materialType(type) else MaterialTheme.typography,
            content = content,
        )
    }
}
