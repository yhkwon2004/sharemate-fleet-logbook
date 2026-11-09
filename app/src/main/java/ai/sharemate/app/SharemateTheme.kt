package ai.sharemate.app

import android.content.res.Configuration
import android.os.SystemClock
import android.provider.Settings
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Asta Sans is the Pleos Connect system typeface (42dot, SIL OFL 1.1).
@OptIn(ExperimentalTextApi::class)
private val AstaSans = FontFamily((300..800 step 100).map { weight ->
    Font(R.font.asta_sans_variable, weight = FontWeight(weight),
        variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
})

/*
 * Korean is the app's language. Every style breaks lines between phrases (어절, at the spaces) instead of between any two
 * syllables, so a word never splits across lines ('맞춰' / '요.'), with the strict rules for punctuation, and is set in
 * Korean so those rules hold on a device set to another language. Display, headline and title styles also balance their
 * lines, so a heading that wraps does not leave one word alone below. Android applies the phrase breaking and the
 * strictness on API 33+ and ignores them below (the strategy works everywhere). Declared before the typographies that use them.
 */
private val KoreanText = LocaleList("ko-KR")
private val TextLineBreak = LineBreak(strategy = LineBreak.Strategy.Simple, strictness = LineBreak.Strictness.Strict, wordBreak = LineBreak.WordBreak.Phrase)
private val HeadingLineBreak = TextLineBreak.copy(strategy = LineBreak.Strategy.Balanced)

private fun type(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = -0.2, heading: Boolean = false) =
    TextStyle(fontFamily = AstaSans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
        lineBreak = if (heading) HeadingLineBreak else TextLineBreak, localeList = KoreanText)

// Car text sizes stay above the Pleos minimum (12 sp, 18 px line) with room for viewing distance.
private val CarTypography = Typography(
    displayLarge = type(72, 80, FontWeight.Medium, -2.0, heading = true),
    displayMedium = type(56, 64, FontWeight.Medium, -1.5, heading = true),
    displaySmall = type(44, 52, FontWeight.Medium, -1.0, heading = true),
    headlineLarge = type(36, 46, FontWeight.SemiBold, -0.8, heading = true),
    headlineMedium = type(30, 40, FontWeight.SemiBold, -0.6, heading = true),
    headlineSmall = type(26, 36, FontWeight.SemiBold, -0.4, heading = true),
    titleLarge = type(24, 32, FontWeight.SemiBold, -0.3, heading = true),
    titleMedium = type(21, 30, FontWeight.SemiBold, heading = true),
    titleSmall = type(19, 28, FontWeight.SemiBold, heading = true),
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
    // The same outline as the night scene's token (3.7:1 on night glass), so Material's own outlined buttons and fields match.
    outline = Color(0xFF6E7A8F), outlineVariant = Color(0xFF243045),
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

// The day scene's Material scheme (2.10): the same navy, now as ink on white glass over a pale sky. Text on white: onSurface
// 15.9:1, onSurfaceVariant 8.1:1, error 4.7:1; the snackbar action (inversePrimary) reads 10:1 on inverseSurface.
private val DayColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF121A2B), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F5), onPrimaryContainer = Color(0xFF121A2B), inversePrimary = Color(0xFF8FCBFF),
    secondary = Color(0xFF0064FF), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3EEFF), onSecondaryContainer = Color(0xFF00296B),
    tertiary = Color(0xFF00703C), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDF7E9), onTertiaryContainer = Color(0xFF004222),
    background = Color(0xFFEEF3FA), onBackground = Color(0xFF121A2B),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF121A2B),
    surfaceVariant = Color(0xFFE3EAF4), onSurfaceVariant = Color(0xFF44506A),
    surfaceTint = Color.Transparent, inverseSurface = Color(0xFF121A2B), inverseOnSurface = Color(0xFFFFFFFF),
    outline = Color(0xFF6B7786), outlineVariant = Color(0xFFD5DDE8),
    error = Color(0xFFC62B0C), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE6E0), onErrorContainer = Color(0xFF5C1305),
    scrim = Color.Black, surfaceBright = Color(0xFFFFFFFF), surfaceDim = Color(0xFFE3EAF4),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF6F8FC),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFE9EFF7), surfaceContainerHighest = Color(0xFFDCE4EF),
)
private val DayPleos = LightPleos.copy(active = Color(0xFF00703C), chartActive = Color(0xFF00703C), negative = Color(0xFFC62B0C),
    surfaceHigh = Color(0xFFE3EAF4), surfaceAccent = Color(0xFFD5DDE8), textTertiary = Color(0xFF5A6678))

val LocalPleosColors = staticCompositionLocalOf { DarkPleos }

// ───────────────────────── the scene change: a fade-through (pure, unit-tested) ─────────────────────────

/**
 * The clock of one scene change, in ms. The content leaves over [outMs] (FastOutLinearIn) behind a scrim of the base
 * colour; every token, the Material scheme, the Pleos colours and the sky's phase swap once at [swapAtMs]; the base colour
 * travels from the old scene's to the new one's over [hiddenMs] while the two skylines cross-fade for [crossfadeMs]; the
 * content returns over [inMs] (LinearOutSlowIn). Nothing is lerped between palettes, so no text is ever drawn in a
 * half-way colour on a half-way background.
 */
@Immutable
data class SceneChangeTiming(val outMs: Int, val hiddenMs: Int, val inMs: Int) {
    val swapAtMs: Int get() = outMs
    val crossfadeMs: Int get() = hiddenMs + inMs
    val totalMs: Int get() = outMs + hiddenMs + inMs
}

/** 800 ms when the clock changes the scene (200 out · 360 hidden · 240 in); 480 ms when the driver tapped 자동 · 낮 · 밤 and is watching. */
internal fun sceneChangeTiming(override: Boolean): SceneChangeTiming = if (override) SceneChangeTiming(120, 216, 144) else SceneChangeTiming(200, 360, 240)

/**
 * The scrim over the content [ms] into a change: 0 → 1 as the content leaves, 1 while hidden, 1 → 0 as it returns; 0 once the
 * change is over, and always 0 on a clock that never hides the content ([skyOnlyTiming]).
 */
internal fun sceneScrimAlpha(ms: Float, timing: SceneChangeTiming): Float = when {
    ms <= 0f || timing.outMs <= 0 -> 0f
    ms < timing.outMs -> FastOutLinearInEasing.transform(ms / timing.outMs)
    ms < timing.outMs + timing.hiddenMs -> 1f
    ms < timing.totalMs -> 1f - LinearOutSlowInEasing.transform((ms - timing.outMs - timing.hiddenMs) / timing.inMs)
    else -> 0f
}

/** How far the base colour under the scrim has travelled from the old scene's to the new one's: it moves only while the content is hidden. */
internal fun sceneBaseFraction(ms: Float, timing: SceneChangeTiming): Float = when {
    ms <= timing.outMs -> 0f
    ms >= timing.outMs + timing.hiddenMs -> 1f
    else -> FastOutSlowInEasing.transform((ms - timing.outMs) / timing.hiddenMs)
}

/**
 * The clock (ms) at which the scrim of a change on [timing] is [alpha] thick while the content leaves ([leaving]) or while it
 * returns: the inverse of [sceneScrimAlpha], so a change that interrupts another carries on from the scrim as it stands
 * instead of snapping.
 */
internal fun sceneClockAt(alpha: Float, timing: SceneChangeTiming, leaving: Boolean): Float {
    val a = alpha.coerceIn(0f, 1f)
    var lo = if (leaving) 0f else (timing.outMs + timing.hiddenMs).toFloat()
    var hi = if (leaving) timing.outMs.toFloat() else timing.totalMs.toFloat()
    // The scrim rises with the clock on the way out and falls on the way back: bisect accordingly.
    repeat(24) {
        val mid = (lo + hi) / 2
        if ((sceneScrimAlpha(mid, timing) < a) == leaving) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

/**
 * The sky's crossfade clock [t] (0–1) mapped onto the part the driver can see: the scrim still hides the content for the
 * first [SceneChangeTiming.hiddenMs] of the crossfade, so the orb rises and the stars fade while the content returns.
 */
internal fun sceneRevealProgress(t: Float, timing: SceneChangeTiming): Float {
    val hidden = timing.hiddenMs.toFloat() / timing.crossfadeMs
    return ((t - hidden) / (1f - hidden)).coerceIn(0f, 1f)
}

/** The timing of the scene change in progress (or of the last one), provided by [SceneTheme]; the sky's crossfade runs on it. */
val LocalSceneChange = compositionLocalOf { sceneChangeTiming(false) }

/**
 * Whether a change of phase needs the fade-through (pure, unit-tested): only when the palette changes. Dusk and night share
 * the Midnight palette, so between them nothing on the content changes, and blanking the whole screen to flat navy twice a
 * day would be motion for nothing: the shown phase moves at once and the sky's own crossfade is the whole change.
 */
internal fun sceneChangeNeedsScrim(from: ScenePhase, to: ScenePhase): Boolean = sceneColorsFor(from) != sceneColorsFor(to)

/**
 * The clock of a change that keeps the palette (dusk ↔ night): nothing leaves and nothing is hidden, so the sky's crossfade
 * runs as long as it does inside a fade-through (600 ms, 360 on a tap) and its orb and stars move over all of it.
 */
internal fun skyOnlyTiming(change: SceneChangeTiming): SceneChangeTiming = SceneChangeTiming(outMs = 0, hiddenMs = 0, inMs = change.crossfadeMs)

/** Spec A's quiet moment: a change by the clock or the light sensor waits until the window has gone this long without a touch. */
internal const val SCENE_QUIET_MS = 3_000L
/** While a finger is down, how often the wait looks again. */
internal const val SCENE_TOUCH_POLL_MS = 250L
/** A press with no event for this long is a resting finger, or a lift the window never heard: it stops holding a change back. */
internal const val SCENE_TOUCH_STALE_MS = 10_000L

/**
 * How long a scene change must still wait before it starts (pure, unit-tested; spec A): 0 = now; null = until the window has
 * focus again and no field is focused (both observed, not polled). An unfocused window means a dialog, a sheet or a menu is
 * open (they take the window's focus) or another window is in front: nothing starts then, not even the driver's change. The
 * driver's own 자동 · 낮 · 밤 tap otherwise applies at once; the clock and the light sensor also wait for no field to be
 * focused and for [SCENE_QUIET_MS] without a touch ([idleMs] since the last one).
 */
internal fun sceneChangeWait(byTap: Boolean, windowFocused: Boolean, fieldFocused: Boolean, touching: Boolean, idleMs: Long): Long? = when {
    !windowFocused -> null
    byTap -> 0L
    fieldFocused -> null
    touching && idleMs < SCENE_TOUCH_STALE_MS -> SCENE_TOUCH_POLL_MS
    else -> (SCENE_QUIET_MS - idleMs).coerceAtLeast(0L)
}

/**
 * True in the phone build (a theme with compact = true). The sky leaves its day clouds out there, and the phone home and the
 * sign-in lay their own day haze over it.
 */
val LocalCompactApp = staticCompositionLocalOf { false }

// The phone's sizes, built once (displaySmall stays the car's).
private val PhoneTypography = CarTypography.copy(
    displayLarge = type(52, 60, FontWeight.Medium, -1.5, heading = true), displayMedium = type(40, 48, FontWeight.Medium, -1.0, heading = true),
    headlineLarge = type(28, 36, FontWeight.SemiBold, -0.5, heading = true), headlineMedium = type(24, 32, FontWeight.SemiBold, -0.4, heading = true),
    headlineSmall = type(22, 30, FontWeight.SemiBold, heading = true), bodyLarge = type(16, 24), bodyMedium = type(15, 22), bodySmall = type(13, 19),
    titleLarge = type(21, 29, FontWeight.SemiBold, heading = true), titleMedium = type(18, 26, FontWeight.SemiBold, heading = true),
    titleSmall = type(16, 24, FontWeight.SemiBold, heading = true), labelLarge = type(15, 22, FontWeight.SemiBold),
    labelMedium = type(13, 18, FontWeight.Medium), labelSmall = type(12, 16, FontWeight.Medium))

/** The type scale of the car or the phone (unit-tested: every style breaks Korean between phrases). */
internal fun typographyFor(compact: Boolean): Typography = if (compact) PhoneTypography else CarTypography

private val SharemateShapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(32.dp))

/**
 * A fixed day or night Material scheme, for screens that stay in one light. Its last caller is SharemateApp (SharemateUi.kt),
 * which nothing calls any more; this and [LightColors] go with it.
 */
@Composable
fun SharemateTheme(compact: Boolean = false, dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPleosColors provides if (dark) DarkPleos else LightPleos, LocalReduceMotion provides rememberReduceMotion(), LocalCompactApp provides compact) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = typographyFor(compact), shapes = SharemateShapes, content = content)
    }
}

/**
 * One requested scene change: the phase to show and whether the driver asked for it (a 자동 · 낮 · 밤 tap) or the clock or the
 * light sensor did. Identity, not value: every change is its own.
 */
private class SceneChange(val phase: ScenePhase, val byTap: Boolean)

/** Plain memory for [SceneTheme] (no snapshot state, so nothing is written backwards during composition): the mode as of the last composition, so a change can tell a tap from the clock. */
private class SceneLedger(var mode: SceneMode)

/**
 * The last touch on the window, written by [SceneTheme]'s root observer on every press, drag, lift, scroll or key: plain
 * fields, not snapshot state, so a finger moving across the screen recomposes nothing. Uptime ms; a theme starts as if just touched.
 */
private class SceneTouches(var touching: Boolean = false, var lastAt: Long = SystemClock.uptimeMillis())

/** Whether a change of phase is the driver's doing: it arrives in the same composition as a new 자동 · 낮 · 밤 choice (pure, unit-tested). */
internal fun sceneChangeIsTap(modeNow: SceneMode, modeBefore: SceneMode): Boolean = modeNow != modeBefore

/**
 * The app's theme (2.10): the scene follows the clock (approximate Seoul sunrise and sunset), on the car the light sensor
 * ([sensorVetoStep]), or the driver's 자동 · 낮 · 밤 choice. A change of palette is a fade-through on [sceneChangeTiming]: the
 * content fades to the base colour, every token swaps once while it is hidden (the sky cross-fades underneath, see
 * [HomeBackdrop]), and the content fades back in: 800 ms for the clock, 480 ms for a tap, an instant swap under reduced
 * motion. Night and dusk share the 2.8 Midnight palette (day is its bright twin), so between them only the sky crossfades
 * ([sceneChangeNeedsScrim]).
 *
 * When (spec A): the driver's tap applies at once; the clock and the sensor wait for a quiet moment ([sceneChangeWait]: 3 s
 * without a touch, no field focused, no dialog, sheet or menu open), and nothing starts while the window is unfocused. While
 * [hold] is true (the car is driving) the screen keeps what it shows: a change on its way lands at once, with no scrim over
 * the driving screen, and whatever is asked for follows once hold drops. What is shown and held is kept in [SceneController],
 * so a recreated activity picks it up instead of starting again from the clock. Provides [LocalScene], [LocalScenePhase],
 * [LocalSceneVeto], [LocalPleosColors], [LocalSceneChange], [LocalCompactApp] and Material.
 */
@Composable
fun SceneTheme(compact: Boolean = false, hold: Boolean = false, content: @Composable () -> Unit) {
    val mode = rememberSceneMode()
    // The clock looks again each time the app comes to the front and whenever the phase can next change (sunrise, dusk), at
    // least every hour; no per-second ticking. The phase itself is worked out in composition, so a 자동 · 낮 · 밤 tap changes it
    // in the very frame the mode changes (that is how a tap is told from the clock below).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(mode, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                tick++
                delay(nextSceneChangeIn(LocalDateTime.now(), mode).coerceIn(15_000L, 3_600_000L))
            }
        }
    }
    val clockPhase = remember(mode, tick) { scenePhaseAt(LocalDateTime.now(), mode) }

    // The car's light sensor: Pleos turns the system night mode on in a tunnel or a car park. The manifest keeps the activity
    // through that flip (configChanges = uiMode), so it arrives here as a new configuration; the veto's clock is kept in
    // SceneController and wakes only when a hold or a release can fall due. The phone follows the clock only.
    val sensorNight = !compact && (LocalConfiguration.current.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    var veto by remember { mutableStateOf(SceneController.currentVeto()) }
    LaunchedEffect(compact, sensorNight, clockPhase, mode) {
        if (compact) return@LaunchedEffect
        while (true) {
            val now = SystemClock.elapsedRealtime()
            val step = SceneController.stepVeto(now, sensorNight, clockPhase, mode)
            veto = step.veto
            val wake = step.wakeAt ?: break
            delay((wake - now).coerceAtLeast(1L))
        }
    }
    val vetoing = !compact && sensorVetoes(veto, clockPhase, mode)
    // The phase asked for: the clock's (or the driver's pick), turned to night while the sensor vetoes.
    val requested = if (vetoing) ScenePhase.NIGHT else clockPhase
    val reduce = rememberReduceMotion()

    // What is on screen. A theme that starts while the car drives (every car theme does, until the restriction service answers)
    // picks up what the last one showed, so a recreated activity never changes the driving screen's palette.
    var shown by remember { mutableStateOf(SceneController.seed(compact, requested, hold)) }
    // The phase a fade-through is on its way to, while one runs.
    var going by remember { mutableStateOf<ScenePhase?>(null) }
    // While driving the screen keeps what it shows (and where a change on its way was going); parked, it follows the request.
    val target = if (hold) going ?: shown else requested
    val ledger = remember { SceneLedger(mode) }
    // A change that arrives with a new mode is the driver's tap (480 ms, at once); with the same mode it is the clock or the
    // sensor (800 ms, at a quiet moment). Keyed on the mode too, so a tap that asks for what a waiting clock change already
    // asks for still applies at once.
    val change = remember(target, mode) { SceneChange(target, byTap = sceneChangeIsTap(mode, ledger.mode)) }
    SideEffect {
        ledger.mode = mode
        SceneController.record(compact, shown, if (hold) target else null)
    }

    // The quiet moment: the root below notes every touch and key in [touches] and whether anything inside holds focus.
    val touches = remember { SceneTouches() }
    val fieldFocused = remember { mutableStateOf(false) }
    val window = LocalWindowInfo.current
    val inputModes = LocalInputModeManager.current
    // Focus counts in touch mode only: with a rotary or a keyboard something always holds focus, and its keys count as touches.
    val fieldBusy = { fieldFocused.value && inputModes.inputMode == InputMode.Touch }

    // The clock (ms) of the change in progress, its timing, and the base colour the scrim is leaving.
    var timing by remember { mutableStateOf(sceneChangeTiming(false)) }
    var from by remember { mutableStateOf(sceneColorsFor(shown).bg) }
    val clock = remember { Animatable(timing.totalMs.toFloat()) }
    LaunchedEffect(change) {
        if (clock.value >= timing.totalMs) {
            // Nothing running: start only if this changes anything, and only when it may.
            if (change.phase == shown) return@LaunchedEffect
            awaitSceneQuiet(change.byTap, touches, window, fieldBusy)
            if (reduce || !sceneChangeNeedsScrim(shown, change.phase)) {
                // An instant swap under reduced motion; between dusk and night (one palette) the sky's crossfade is the whole change.
                if (!reduce) timing = skyOnlyTiming(sceneChangeTiming(change.byTap))
                shown = change.phase
                from = sceneColorsFor(change.phase).bg
                clock.snapTo(timing.totalMs.toFloat())
                return@LaunchedEffect
            }
        }
        // A fade-through, or a second change meeting one on its way: it carries on from the scrim as it stands, so it never snaps.
        val alpha = sceneScrimAlpha(clock.value, timing)
        from = lerp(from, sceneColorsFor(shown).bg, sceneBaseFraction(clock.value, timing))
        val t = sceneChangeTiming(change.byTap)
        timing = t
        if (!sceneChangeNeedsScrim(shown, change.phase)) {
            // The target has the palette on screen (it came back, or dusk ↔ night): let the content back in.
            going = null
            shown = change.phase
            val at = sceneClockAt(alpha, t, leaving = false)
            clock.snapTo(at)
            clock.animateTo(t.totalMs.toFloat(), tween((t.totalMs - at).toInt().coerceAtLeast(1), easing = LinearEasing))
            return@LaunchedEffect
        }
        going = change.phase
        val at = sceneClockAt(alpha, t, leaving = true)
        clock.snapTo(at)
        clock.animateTo(t.outMs.toFloat(), tween((t.outMs - at).toInt().coerceAtLeast(1), easing = LinearEasing))
        // The swap: tokens, Material, Pleos and the sky's phase change together in this one frame, behind the scrim.
        shown = change.phase
        clock.animateTo(t.totalMs.toFloat(), tween(t.hiddenMs + t.inMs, easing = LinearEasing))
        going = null
    }
    LaunchedEffect(hold) {
        if (!hold) return@LaunchedEffect
        // Driving began: a change on its way lands at once and leaves no scrim over the driving screen (spec C: nothing animates
        // while blocked). Snapping the clock also stops the change's own animation.
        going?.let { shown = it; from = sceneColorsFor(it).bg }
        going = null
        if (clock.value < timing.totalMs) clock.snapTo(timing.totalMs.toFloat())
    }

    val colors = sceneColorsFor(shown)
    val scheme = if (colors.isDay) DayColors else DarkColors
    val pleos = if (colors.isDay) DayPleos else DarkPleos
    val to = colors.bg
    CompositionLocalProvider(LocalScene provides colors, LocalScenePhase provides shown, LocalPleosColors provides pleos, LocalReduceMotion provides reduce,
        LocalSceneChange provides timing, LocalCompactApp provides compact, LocalSceneVeto provides (vetoing && shown == ScenePhase.NIGHT)) {
        MaterialTheme(colorScheme = scheme, typography = typographyFor(compact), shapes = SharemateShapes) {
            Box(Modifier
                // Every touch on the window is noted on its way down (the Initial pass) and never consumed; a hovering mouse is no touch.
                .pointerInput(touches) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val down = event.changes.any { it.pressed }
                            if (down || event.type == PointerEventType.Release || event.type == PointerEventType.Scroll) {
                                touches.touching = down
                                touches.lastAt = SystemClock.uptimeMillis()
                            }
                        }
                    }
                }
                .onPreviewKeyEvent { touches.lastAt = SystemClock.uptimeMillis(); false }
                .onFocusChanged { fieldFocused.value = it.hasFocus }
                // The fade-through scrim: the base colour over everything, thickening as the content leaves and thinning as it
                // returns. Only the draw reads the clock, so the content does not recompose per frame.
                .drawWithContent {
                    drawContent()
                    val ms = clock.value
                    val alpha = sceneScrimAlpha(ms, timing)
                    if (alpha > 0f) drawRect(lerp(from, to, sceneBaseFraction(ms, timing)).copy(alpha = alpha))
                }) { content() }
        }
    }
}

/** Waits for the moment [sceneChangeWait] allows: it observes window and field focus, and sleeps out the rest of a quiet spell. */
private suspend fun awaitSceneQuiet(byTap: Boolean, touches: SceneTouches, window: WindowInfo, fieldBusy: () -> Boolean) {
    while (true) {
        val wait = sceneChangeWait(byTap, window.isWindowFocused, fieldBusy(), touches.touching, SystemClock.uptimeMillis() - touches.lastAt)
        when {
            wait == null -> {
                snapshotFlow { window.isWindowFocused && (byTap || !fieldBusy()) }.first { it }
                // Focus coming back (a dialog or a menu closed, a field let go) is a touch of its own: the quiet starts here.
                touches.lastAt = SystemClock.uptimeMillis()
            }
            wait <= 0L -> return
            else -> delay(wait)
        }
    }
}
