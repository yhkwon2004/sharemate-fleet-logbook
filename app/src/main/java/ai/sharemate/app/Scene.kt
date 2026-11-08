package ai.sharemate.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime
import java.time.LocalTime

/*
 * The scene (2.10): the same composition in three lights. Night and dusk keep the Midnight look of 2.8; day is a bright,
 * clear version of it with dark text on white glass. The phase follows the clock (approximate Seoul sunrise and sunset) unless
 * the driver picks 낮 or 밤 in settings. Every screen reads its colours through [Scene] so the whole app moves together, and
 * [SceneTheme] fades the content through the base colour and swaps the palette once while it is hidden (a fade-through:
 * no token is ever lerped, so no text is drawn half-way between two palettes).
 */

enum class ScenePhase { DAY, DUSK, NIGHT }

enum class SceneMode(val label: String) { AUTO("자동"), DAY("낮"), NIGHT("밤") }

/** The colour tokens of one scene. Names are the 2.8 Midnight names; the extra ones replace hard-coded whites and blues. */
@Immutable
data class SceneColors(
    val bg: Color,
    val rail: Color,
    val glass: Color,
    val glassBorder: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val glow: Color,
    val warn: Color,
    val badge: Color,
    val brand: Color,
    /** The lit top edge of every glass surface: three stops from the top down. */
    val edgeTop: Color, val edgeMid: Color, val edgeBottom: Color,
    /** Icon tiles on the destination cards and what sits on them. */
    val tileStart: Color, val tileEnd: Color, val onTile: Color,
    /** Hairlines and ring tracks on glass. */
    val divider: Color, val ringTrack: Color,
    /** A barely-there fill for rows and steppers that sit on glass. */
    val fill: Color,
    /** The two soft radial glows of the screen backdrop. */
    val glowCool: Color, val glowWarm: Color,
    /** The sweep that starts the completion ring and the tile halo. */
    val accent: Color,
    val isDay: Boolean,
    /** 2.10 spec: status colours, selection and scrim tokens that differ by scene (night defaults). */
    val positive: Color = Color(0xFF00E074), val negative: Color = Color(0xFFFF4C28), val outline: Color = Color(0xFF5E6A7E),
    val selectionFill: Color = Color(0x244CE3F0), val selectionRing: Color = Color(0x734CE3F0),
    /** The scrim colour laid over the sky behind text (bg by night, white by day), the sun or moon, and the drop shadow under tappable cards. */
    val haze: Color = Color(0xFF0B111C), val sun: Color = Color(0xFFF6EBD3), val shadow: Color = Color.Transparent,
) {
    val edge: BorderStroke get() = BorderStroke(1.dp, Brush.verticalGradient(listOf(edgeTop, edgeMid, edgeBottom)))
}

/** 2.8 Midnight, unchanged: deep navy, white text, cyan for what is active. */
internal val NightSceneColors = SceneColors(
    bg = Color(0xFF0B111C), rail = Color(0xFF0E1625), glass = Color(0xFF16213A), glassBorder = Color(0x24FFFFFF),
    text = Color(0xFFFFFFFF), textSecondary = Color(0xFFC3CCDA), textMuted = Color(0xFF97A3B6),
    glow = Color(0xFF4CE3F0), warn = Color(0xFFFFB45C),
    // Count badges: white digits need 4.5:1, which the brighter status red (#FF4C28, about 3.3:1) does not give.
    badge = Color(0xFFC8321A), brand = Color(0xFFE4BFA6),
    edgeTop = Color(0x52FFFFFF), edgeMid = Color(0x14FFFFFF), edgeBottom = Color(0x0AFFFFFF),
    tileStart = Color(0x803B6FFF), tileEnd = Color(0x6639D5F0), onTile = Color(0xFFF2F8FF),
    divider = Color(0x24FFFFFF), ringTrack = Color(0x1AFFFFFF),
    fill = Color(0x0DFFFFFF),
    glowCool = Color(0x3D2D5BFF), glowWarm = Color(0x2B8A4FD8), accent = Color(0xFF3B6FFF),
    isDay = false,
)

/**
 * Day: a clear morning. White glass on a pale sky, ink-navy text, a deep teal for what is active. Every text token clears
 * WCAG AA on the day background and on white glass: text 15.9:1, textSecondary 8.1:1, textMuted 5.4:1, glow 6.1:1,
 * warn 6.1:1, brand 6.2:1 against #FFFFFF, and all of them at least 4.9:1 against #EEF3FA (checked in SceneTest).
 */
internal val DaySceneColors = SceneColors(
    bg = Color(0xFFEEF3FA), rail = Color(0xFFE3EAF4), glass = Color(0xFFFFFFFF), glassBorder = Color(0x241B2B4D),
    text = Color(0xFF121A2B), textSecondary = Color(0xFF44506A), textMuted = Color(0xFF5A6678),
    glow = Color(0xFF0B6B7E), warn = Color(0xFF8A4B00), badge = Color(0xFFC8321A), brand = Color(0xFF7E4F33),
    edgeTop = Color(0x2E1B2B4D), edgeMid = Color(0x141B2B4D), edgeBottom = Color(0x0A1B2B4D),
    tileStart = Color(0xFF2456D6), tileEnd = Color(0xFF0B6B7E), onTile = Color(0xFFFFFFFF),
    divider = Color(0x29121A2B), ringTrack = Color(0x24121A2B),
    fill = Color(0x0F121A2B),
    glowCool = Color(0x40A8C8FF), glowWarm = Color(0x33FFC9A0), accent = Color(0xFF2F6BFF),
    isDay = true,
    positive = Color(0xFF00703C), negative = Color(0xFFC62B0C), outline = Color(0xFF6B7786),
    selectionFill = Color(0x1F0B6B7E), selectionRing = Color(0xFF0B6B7E),
    haze = Color(0xFFFFFFFF), sun = Color(0xFFFFD27A), shadow = Color(0x1F121A2B),
)

/** The tokens of one phase: day has its own palette; dusk shares the night one (only the sky tells them apart). Never lerped. */
internal fun sceneColorsFor(phase: ScenePhase): SceneColors = if (phase == ScenePhase.DAY) DaySceneColors else NightSceneColors

/** The palette in effect, provided by [SceneTheme]; night when nothing provides it (previews, the splash). */
val LocalScene = compositionLocalOf { NightSceneColors }

/** The phase in effect (the sky to draw), provided by [SceneTheme]. */
val LocalScenePhase = staticCompositionLocalOf { ScenePhase.NIGHT }

/** The scene's colours, read where they are drawn: `Scene.glass`, `Scene.text`, `Scene.edge`. */
internal object Scene {
    val colors: SceneColors @Composable @ReadOnlyComposable get() = LocalScene.current
    val bg: Color @Composable @ReadOnlyComposable get() = LocalScene.current.bg
    val rail: Color @Composable @ReadOnlyComposable get() = LocalScene.current.rail
    val glass: Color @Composable @ReadOnlyComposable get() = LocalScene.current.glass
    val glassBorder: Color @Composable @ReadOnlyComposable get() = LocalScene.current.glassBorder
    val text: Color @Composable @ReadOnlyComposable get() = LocalScene.current.text
    val textSecondary: Color @Composable @ReadOnlyComposable get() = LocalScene.current.textSecondary
    val textMuted: Color @Composable @ReadOnlyComposable get() = LocalScene.current.textMuted
    val glow: Color @Composable @ReadOnlyComposable get() = LocalScene.current.glow
    val warn: Color @Composable @ReadOnlyComposable get() = LocalScene.current.warn
    val badge: Color @Composable @ReadOnlyComposable get() = LocalScene.current.badge
    val brand: Color @Composable @ReadOnlyComposable get() = LocalScene.current.brand
    val divider: Color @Composable @ReadOnlyComposable get() = LocalScene.current.divider
    val ringTrack: Color @Composable @ReadOnlyComposable get() = LocalScene.current.ringTrack
    val fill: Color @Composable @ReadOnlyComposable get() = LocalScene.current.fill
    val positive: Color @Composable @ReadOnlyComposable get() = LocalScene.current.positive
    val negative: Color @Composable @ReadOnlyComposable get() = LocalScene.current.negative
    val outline: Color @Composable @ReadOnlyComposable get() = LocalScene.current.outline
    val selectionFill: Color @Composable @ReadOnlyComposable get() = LocalScene.current.selectionFill
    val selectionRing: Color @Composable @ReadOnlyComposable get() = LocalScene.current.selectionRing
    val haze: Color @Composable @ReadOnlyComposable get() = LocalScene.current.haze
    val sun: Color @Composable @ReadOnlyComposable get() = LocalScene.current.sun
    val shadow: Color @Composable @ReadOnlyComposable get() = LocalScene.current.shadow
    val accent: Color @Composable @ReadOnlyComposable get() = LocalScene.current.accent
    val onTile: Color @Composable @ReadOnlyComposable get() = LocalScene.current.onTile
    val edge: BorderStroke @Composable @ReadOnlyComposable get() = LocalScene.current.edge
    val isDay: Boolean @Composable @ReadOnlyComposable get() = LocalScene.current.isDay
    /** The icon-tile gradient (blue to cyan), lit from the top left. */
    val tile: Brush @Composable @ReadOnlyComposable get() = LocalScene.current.let { Brush.linearGradient(listOf(it.tileStart, it.tileEnd)) }
}

/** The screen backdrop: the scene's base colour with a cool glow from the upper left and a warm one from the lower right. */
@Composable internal fun Modifier.sceneBackdrop(): Modifier {
    val colors = LocalScene.current
    return drawWithCache {
        val cool = Brush.radialGradient(listOf(colors.glowCool, Color.Transparent), center = Offset(size.width * .18f, 0f), radius = size.maxDimension * .62f)
        val warm = Brush.radialGradient(listOf(colors.glowWarm, Color.Transparent), center = Offset(size.width, size.height), radius = size.maxDimension * .55f)
        onDrawBehind { drawRect(colors.bg); drawRect(cool); drawRect(warm) }
    }
}

// ───────────────────────── when is it day? (pure, unit-tested) ─────────────────────────

// Approximate Seoul sunrise and sunset, by month, in minutes after midnight (mid-month, local time).
private val SUNRISE = intArrayOf(7 * 60 + 45, 7 * 60 + 25, 6 * 60 + 50, 6 * 60 + 10, 5 * 60 + 35, 5 * 60 + 15, 5 * 60 + 25, 5 * 60 + 50, 6 * 60 + 15, 6 * 60 + 40, 7 * 60 + 10, 7 * 60 + 35)
private val SUNSET = intArrayOf(17 * 60 + 30, 18 * 60, 18 * 60 + 30, 19 * 60, 19 * 60 + 30, 19 * 60 + 55, 19 * 60 + 50, 19 * 60 + 20, 18 * 60 + 40, 18 * 60, 17 * 60 + 25, 17 * 60 + 15)

/** Day from half an hour after sunrise to 45 minutes before sunset; dusk for the 45 minutes either side of sunset and the half hour around sunrise. */
internal fun scenePhaseAt(time: LocalDateTime, mode: SceneMode = SceneMode.AUTO): ScenePhase {
    if (mode == SceneMode.DAY) return ScenePhase.DAY
    if (mode == SceneMode.NIGHT) return ScenePhase.NIGHT
    val m = time.hour * 60 + time.minute
    val rise = SUNRISE[time.monthValue - 1]
    val set = SUNSET[time.monthValue - 1]
    return when {
        m in (rise + 30) until (set - 45) -> ScenePhase.DAY
        m in (rise - 30) until (rise + 30) || m in (set - 45) until (set + 45) -> ScenePhase.DUSK
        else -> ScenePhase.NIGHT
    }
}

/** The next minute at which the phase can change, so the ticker wakes only when it matters. */
internal fun nextSceneChangeIn(time: LocalDateTime, mode: SceneMode = SceneMode.AUTO): Long {
    if (mode != SceneMode.AUTO) return 60L * 60_000
    val current = scenePhaseAt(time, mode)
    var probe = time.withSecond(0).withNano(0).plusMinutes(1)
    var steps = 0
    while (scenePhaseAt(probe, mode) == current && steps < 24 * 60) { probe = probe.plusMinutes(1); steps++ }
    return (steps + 1) * 60_000L
}

// ───────────────────────── the driver's choice ─────────────────────────

/** 자동 · 낮 · 밤, kept in plain preferences (not in the logbook file, so a scene choice never changes a record). */
object ScenePrefs {
    private const val PREFS = "scene"
    private const val KEY = "mode"
    private val mode = MutableStateFlow<SceneMode?>(null)

    fun modeFlow(context: Context): StateFlow<SceneMode?> {
        if (mode.value == null) {
            val saved = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            mode.value = SceneMode.entries.firstOrNull { it.name == saved } ?: SceneMode.AUTO
        }
        return mode
    }

    fun set(context: Context, value: SceneMode) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value.name).apply()
        mode.value = value
    }
}

@Composable internal fun rememberSceneMode(): SceneMode {
    val context = LocalContext.current
    val mode by ScenePrefs.modeFlow(context).collectAsState()
    return mode ?: SceneMode.AUTO
}

/** The copy under the scene choice in settings. */
internal fun sceneModeHint(mode: SceneMode, phase: ScenePhase): String = when (mode) {
    SceneMode.AUTO -> when (phase) {
        ScenePhase.DAY -> "지금은 낮 화면이에요 · 해 질 무렵 밤 화면으로 바뀌어요"
        ScenePhase.DUSK -> "지금은 해 질 무렵이에요 · 곧 밤 화면으로 바뀌어요"
        ScenePhase.NIGHT -> "지금은 밤 화면이에요 · 해가 뜨면 낮 화면으로 바뀌어요"
    }
    SceneMode.DAY -> "항상 밝은 낮 화면을 써요"
    SceneMode.NIGHT -> "항상 어두운 밤 화면을 써요"
}

/** The settings row for the scene, shared by the car (설정 › 화면) and the phone (설정): a segmented 자동 · 낮 · 밤 with one line under it. */
@Composable internal fun SceneModeCard(compact: Boolean = false) {
    val context = LocalContext.current
    val mode = rememberSceneMode()
    val phase = LocalScenePhase.current
    Panel(padding = if (compact) 20.dp else 28.dp) {
        Eyebrow("화면 분위기")
        Column {
            Segmented(SceneMode.entries.map { it.label }, SceneMode.entries.indexOf(mode)) { ScenePrefs.set(context, SceneMode.entries[it]) }
        }
        Muted(sceneModeHint(mode, phase))
    }
}
