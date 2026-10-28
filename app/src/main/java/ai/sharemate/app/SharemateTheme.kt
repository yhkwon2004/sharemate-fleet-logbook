package ai.sharemate.app

import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Asta Sans is the Pleos Connect system typeface (42dot, SIL OFL 1.1).
@OptIn(ExperimentalTextApi::class)
private val AstaSans = FontFamily((300..800 step 100).map { weight ->
    Font(R.font.asta_sans_variable, weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = -0.2) =
    TextStyle(fontFamily = AstaSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp)

// Car text sizes stay above the Pleos minimum (12 sp, 18 px line) with room for viewing distance.
private val CarTypography = Typography(
    displayLarge = type(72, 80, FontWeight.Medium, -2.0),
    displayMedium = type(56, 64, FontWeight.Medium, -1.5),
    displaySmall = type(44, 52, FontWeight.Medium, -1.0),
    headlineLarge = type(36, 46, FontWeight.SemiBold, -0.8),
    headlineMedium = type(30, 40, FontWeight.SemiBold, -0.6),
    headlineSmall = type(26, 36, FontWeight.SemiBold, -0.4),
    titleLarge = type(24, 32, FontWeight.SemiBold, -0.3),
    titleMedium = type(21, 30, FontWeight.SemiBold),
    titleSmall = type(19, 28, FontWeight.SemiBold),
    bodyLarge = type(20, 30),
    bodyMedium = type(18, 27),
    bodySmall = type(16, 24),
    labelLarge = type(19, 26, FontWeight.SemiBold, 0.0),
    labelMedium = type(16, 22, FontWeight.Medium, 0.0),
    labelSmall = type(14, 20, FontWeight.Medium, 0.2),
)

/** Pleos Connect status colors that Material has no slot for. */
@Immutable
data class PleosColors(
    val active: Color, val positive: Color, val negative: Color,
    val gleoBubble: Color, val gleoBubbleSecondary: Color, val gleoSpeaker: Color, val gleoSurface: Color,
    val surfaceHigh: Color, val surfaceAccent: Color, val textTertiary: Color,
    // Rings, bars and dots need 3:1 against surfaces (WCAG 1.4.11); the day-mode active token alone falls short.
    val chartActive: Color,
)

// Grayscale basic_00..basic_900 and informative tokens from the Pleos Connect design guide (Foundations > Colors).
private val DarkPleos = PleosColors(
    active = Color(0xFF00E074), positive = Color(0xFF0082FF), negative = Color(0xFFFF4C28),
    gleoBubble = Color(0xFF545F93), gleoBubbleSecondary = Color(0xFF3E4976), gleoSpeaker = Color(0xFFB6C1F3), gleoSurface = Color(0xFF44464E),
    surfaceHigh = Color(0xFF313236), surfaceAccent = Color(0xFF44464E), textTertiary = Color(0xFFA4A8AE),
    chartActive = Color(0xFF00E074),
)
private val LightPleos = PleosColors(
    active = Color(0xFF02C265), positive = Color(0xFF0064FF), negative = Color(0xFFFE3D16),
    gleoBubble = Color(0xFF9BA8E2), gleoBubbleSecondary = Color(0xFF8896D3), gleoSpeaker = Color(0xFF3F4B81), gleoSurface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEEF2), surfaceAccent = Color(0xFFE0E1E6), textTertiary = Color(0xFF686A72),
    chartActive = Color(0xFF008A4B),
)

/** True when the system animator scale is off, so page fades snap instead of animating (reduced motion). */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) <= 0f
    }
}

/** Page transition for parked screens: 160 ms fade, or an immediate switch when motion is reduced. */
@Composable
fun <T> pageFade(): FiniteAnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(160)

/** Caps display-size numbers so a 1.3x+ font scale still fits one line on the car window. */
@Composable
fun scaledDisplay(large: TextStyle, smaller: TextStyle): TextStyle = if (LocalDensity.current.fontScale > 1.25f) smaller else large

// Neutral, high-contrast primary like Pleos filled buttons; color is kept for status only.
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF131417),
    primaryContainer = Color(0xFF313236), onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF0082FF), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF12304F), onSecondaryContainer = Color(0xFFD6E8FF),
    tertiary = Color(0xFF00E074), onTertiary = Color(0xFF00391C),
    tertiaryContainer = Color(0xFF0F3A24), onTertiaryContainer = Color(0xFFB4F5D0),
    background = Color(0xFF131417), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF1D1E21), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF313236), onSurfaceVariant = Color(0xFFC7C9CE),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFFF7F8FA), inverseOnSurface = Color(0xFF131417),
    outline = Color(0xFF686A72), outlineVariant = Color(0xFF313236),
    error = Color(0xFFFF4C28), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF4A1A10), onErrorContainer = Color(0xFFFFDAD1),
    scrim = Color.Black, surfaceBright = Color(0xFF313236), surfaceDim = Color(0xFF131417),
    surfaceContainerLowest = Color(0xFF131417), surfaceContainerLow = Color(0xFF1D1E21),
    surfaceContainer = Color(0xFF1D1E21), surfaceContainerHigh = Color(0xFF313236), surfaceContainerHighest = Color(0xFF44464E),
)
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF131417), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E1E6), onPrimaryContainer = Color(0xFF131417),
    secondary = Color(0xFF0064FF), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3EEFF), onSecondaryContainer = Color(0xFF00296B),
    tertiary = Color(0xFF02C265), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF7E9), onTertiaryContainer = Color(0xFF004222),
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF131417),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF131417),
    surfaceVariant = Color(0xFFEDEEF2), onSurfaceVariant = Color(0xFF44464E),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFF1D1E21), inverseOnSurface = Color(0xFFFFFFFF),
    outline = Color(0xFFA4A8AE), outlineVariant = Color(0xFFE0E1E6),
    error = Color(0xFFFE3D16), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE6E0), onErrorContainer = Color(0xFF5C1305),
    scrim = Color.Black, surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFEDEEF2),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF7F8FA),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEDEEF2), surfaceContainerHighest = Color(0xFFE0E1E6),
)

val LocalPleosColors = staticCompositionLocalOf { DarkPleos }

/** Follows the vehicle day/night mode, as Pleos Connect adjusts brightness and contrast to lighting. */
@Composable
fun SharemateTheme(compact: Boolean = false, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPleosColors provides if (dark) DarkPleos else LightPleos, LocalReduceMotion provides rememberReduceMotion()) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = if (!compact) CarTypography else CarTypography.copy(
                displayLarge = type(52, 60, FontWeight.Medium, -1.5), displayMedium = type(40, 48, FontWeight.Medium, -1.0),
                headlineLarge = type(28, 36, FontWeight.SemiBold, -0.5), headlineMedium = type(24, 32, FontWeight.SemiBold, -0.4),
                headlineSmall = type(22, 30, FontWeight.SemiBold), bodyLarge = type(16, 24), bodyMedium = type(15, 22), bodySmall = type(13, 19),
                titleLarge = type(21, 29, FontWeight.SemiBold), titleMedium = type(18, 26, FontWeight.SemiBold),
                titleSmall = type(16, 24, FontWeight.SemiBold), labelLarge = type(15, 22, FontWeight.SemiBold),
                labelMedium = type(13, 18, FontWeight.Medium), labelSmall = type(12, 16, FontWeight.Medium)),
            shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
                medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp)),
            content = content,
        )
    }
}
