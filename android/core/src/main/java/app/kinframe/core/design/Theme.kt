package app.kinframe.core.design

import androidx.compose.material3.MaterialTheme
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
 * kinframe's calm, warm visual language. Shared by the frame app and (later) the family app.
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
)

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

val LocalKinColors = staticCompositionLocalOf { KinColors() }
val LocalKinType = staticCompositionLocalOf { kinType(1f) }
val LocalKinSpacing = staticCompositionLocalOf { KinSpacing() }

/** Accessor: `Kin.colors.accent`, `Kin.type.title`, `Kin.space.m`. */
object Kin {
    val colors: KinColors @Composable get() = LocalKinColors.current
    val type: KinType @Composable get() = LocalKinType.current
    val space: KinSpacing @Composable get() = LocalKinSpacing.current
}

/**
 * Root theme. [textScale] implements the frame's text-size setting (1.0 / 1.15 / 1.3).
 */
@Composable
fun KinframeTheme(textScale: Float = 1f, content: @Composable () -> Unit) {
    val colors = KinColors()
    val type = kinType(textScale)
    val material = lightColorScheme(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentSoft,
        onPrimaryContainer = colors.ink,
        secondary = colors.sage,
        secondaryContainer = colors.sageSoft,
        background = colors.paper,
        onBackground = colors.ink,
        surface = colors.surface,
        onSurface = colors.ink,
        onSurfaceVariant = colors.inkSoft,
        outline = colors.line,
        error = colors.danger,
    )
    CompositionLocalProvider(
        LocalKinColors provides colors,
        LocalKinType provides type,
        LocalKinSpacing provides KinSpacing(),
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}
