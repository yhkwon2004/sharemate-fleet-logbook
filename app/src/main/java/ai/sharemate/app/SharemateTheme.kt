package ai.sharemate.app

import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
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
    gleoBubble = Color(0xFF545F93), gleoBubbleSecondary = Color(0xFF3E4976), gleoSpeaker = Color(0xFFB6C1F3), gleoSurface = Color(0xFF2A3549),
    surfaceHigh = Color(0xFF1D2738), surfaceAccent = Color(0xFF2A3549), textTertiary = Color(0xFF97A3B6),
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

/** The parked screen's motion budget: one card replaces another in 160 ms, a bar slides up in 120 ms. */
internal const val MOTION_SWAP_MS = 160
internal const val MOTION_BAR_MS = 120

/**
 * How one home card gives way to the next: a 160 ms cross-fade with an 8 dp lift, so the driver sees that the question
 * changed rather than that the screen jumped. Read it once in composition and hand it to `AnimatedContent`'s
 * `transitionSpec`; with reduced motion, or while driving, it snaps.
 */
@Composable
fun cardSwap(snap: Boolean = false): ContentTransform {
    val density = LocalDensity.current
    val lift = with(density) { 8.dp.roundToPx() }
    if (snap || LocalReduceMotion.current) return snapSwap()
    val ease = FastOutSlowInEasing
    // `using` is only available inside AnimatedContentTransitionScope, so the transform is assembled directly.
    return ContentTransform(
        targetContentEnter = fadeIn(tween(MOTION_SWAP_MS, easing = ease)) + slideInVertically(tween(MOTION_SWAP_MS, easing = ease)) { lift },
        initialContentExit = fadeOut(tween(MOTION_SWAP_MS, easing = ease)) + slideOutVertically(tween(MOTION_SWAP_MS, easing = ease)) { -lift },
        sizeTransform = SizeTransform(clip = false) { _, _ -> tween(MOTION_SWAP_MS, easing = ease) },
    )
}

/** Page change between tabs: 220 ms. */
internal const val MOTION_PAGE_MS = 220

/** Tab highlight colors move on the same 220 ms clock as the page they open; immediate under reduced motion. */
@Composable
fun <T> tabColorSpec(): androidx.compose.animation.core.AnimationSpec<T> = if (LocalReduceMotion.current) snap() else tween(MOTION_PAGE_MS, easing = FastOutSlowInEasing)

/**
 * One page transition for every tab change on the car and the phone: the new page fades in while sliding 24 dp from the
 * side it lies on (right for a later tab, left for an earlier one), the old page fades out sliding 12 dp the other way.
 * The header, the tab bar and the backdrop stay put, so only the content moves. Snaps under reduced motion.
 */
@Composable
fun pageSwap(forward: Boolean): ContentTransform {
    if (LocalReduceMotion.current) return snapSwap()
    val density = LocalDensity.current
    val enter = with(density) { 24.dp.roundToPx() }
    val exit = with(density) { 12.dp.roundToPx() }
    val ease = FastOutSlowInEasing
    return ContentTransform(
        targetContentEnter = fadeIn(tween(MOTION_PAGE_MS, delayMillis = 40, easing = ease)) +
            slideInHorizontally(tween(MOTION_PAGE_MS, easing = ease)) { if (forward) enter else -enter },
        initialContentExit = fadeOut(tween(140, easing = ease)) + slideOutHorizontally(tween(MOTION_PAGE_MS, easing = ease)) { if (forward) -exit else exit },
        sizeTransform = null,
    )
}

/** An immediate card replacement: the blocked (driving) transition and reduced motion use this. */
fun snapSwap(): ContentTransform =
    ContentTransform(fadeIn(snap()), fadeOut(snap()), sizeTransform = SizeTransform(clip = false) { _, _ -> snap() })

/** A follow-up bar arriving: 120 ms ease-out slide from the bottom edge with a fade; nothing when motion is reduced. */
@Composable
fun barEnter(): EnterTransition {
    if (LocalReduceMotion.current) return fadeIn(snap())
    return slideInVertically(tween(MOTION_BAR_MS, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(MOTION_BAR_MS, easing = FastOutSlowInEasing))
}

/** A follow-up bar leaving the same way it came. */
@Composable
fun barExit(): ExitTransition {
    if (LocalReduceMotion.current) return fadeOut(snap())
    return slideOutVertically(tween(MOTION_BAR_MS, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(MOTION_BAR_MS, easing = FastOutSlowInEasing))
}

/** Caps display-size numbers so a 1.3x+ font scale still fits one line on the car window. */
@Composable
fun scaledDisplay(large: TextStyle, smaller: TextStyle): TextStyle = if (LocalDensity.current.fontScale > 1.25f) smaller else large

// Neutral, high-contrast primary like Pleos filled buttons; color is kept for status only. 2.8: the night palette is a deep
// navy (Midnight) instead of neutral gray, so the car home hero, the rail and every pane read as one surface.
private val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF), onPrimary = Color(0xFF0B111C),
    primaryContainer = Color(0xFF1D2738), onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF0082FF), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF12304F), onSecondaryContainer = Color(0xFFD6E8FF),
    tertiary = Color(0xFF00E074), onTertiary = Color(0xFF00391C),
    tertiaryContainer = Color(0xFF0F3A24), onTertiaryContainer = Color(0xFFB4F5D0),
    background = Color(0xFF0B111C), onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF131B2A), onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1D2738), onSurfaceVariant = Color(0xFFC3CCDA),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFFF7F8FA), inverseOnSurface = Color(0xFF0B111C),
    outline = Color(0xFF5E6A7E), outlineVariant = Color(0xFF243045),
    error = Color(0xFFFF4C28), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF4A1A10), onErrorContainer = Color(0xFFFFDAD1),
    scrim = Color.Black, surfaceBright = Color(0xFF2A3549), surfaceDim = Color(0xFF0B111C),
    surfaceContainerLowest = Color(0xFF0B111C), surfaceContainerLow = Color(0xFF131B2A),
    surfaceContainer = Color(0xFF131B2A), surfaceContainerHigh = Color(0xFF1D2738), surfaceContainerHighest = Color(0xFF2A3549),
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
