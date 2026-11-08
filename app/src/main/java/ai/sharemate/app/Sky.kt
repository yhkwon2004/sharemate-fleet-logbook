package ai.sharemate.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import kotlin.math.abs
import kotlin.random.Random

/*
 * The city across the river, in three lights. One seeded skyline (the same towers every launch, denser and taller to the
 * right so the greeting on the left stays calm) is painted as a clear morning, a dusk with an orange horizon, or a deep
 * night, and [HomeBackdrop] cross-fades between them when the scene changes. Decorative only: no semantics.
 */

private class SkyTower(val left: Float, val width: Float, val height: Float, val lit: List<Offset>, val crown: Int, val cool: Boolean, val reflect: Float)
private class SkyCloud(val x: Float, val y: Float, val scale: Float)
private class SkyScene(val stars: List<Triple<Float, Float, Float>>, val clouds: List<SkyCloud>, val far: List<SkyTower>, val near: List<SkyTower>, val ridge: List<Offset>)

private fun skylineScene(): SkyScene {
    val r = Random(2026)
    val stars = List(70) { Triple(r.nextFloat(), r.nextFloat() * .40f, .25f + r.nextFloat() * .55f) }
    fun towers(from: Float, maxH: Float, minW: Float, maxW: Float, density: Float, peak: Float): List<SkyTower> {
        val out = mutableListOf<SkyTower>()
        var x = from
        while (x < 1.02f) {
            val w = minW + r.nextFloat() * (maxW - minW)
            val cluster = 1f - (abs(x - peak) / .40f).coerceIn(0f, 1f)
            val h = maxH * (.22f + .78f * cluster) * (.55f + r.nextFloat() * .45f)
            val cols = (w * 150).toInt().coerceIn(2, 5)
            val rows = (h * 44).toInt().coerceIn(3, 18)
            val lit = buildList { for (c in 0 until cols) for (row in 0 until rows) if (r.nextFloat() < density) add(Offset((c + .5f) / cols, (row + .5f) / rows)) }
            out += SkyTower(x, w, h, lit, r.nextInt(4), r.nextFloat() < .28f, if (r.nextFloat() < .45f) .06f + r.nextFloat() * .16f else 0f)
            x += w + r.nextFloat() * .005f
        }
        return out
    }
    val ridge = List(9) { i -> Offset(.30f + i * .09f, .06f + r.nextFloat() * .10f) }
    val far = towers(.34f, .32f, .012f, .028f, .16f, .76f)
    val near = towers(.44f, .44f, .016f, .036f, .30f, .82f)
    // A few soft clouds high in the day sky, kept left of the dense towers and away from the greeting block's top.
    val clouds = List(5) { i -> SkyCloud(.08f + i * .19f + r.nextFloat() * .08f, .08f + r.nextFloat() * .16f, .7f + r.nextFloat() * .6f) }
    return SkyScene(stars, clouds, far, near, ridge)
}

/** How one phase paints the scene. */
private class SkyPaint(
    val sky: List<Pair<Float, Color>>, val horizonGlow: List<Color>, val water: List<Pair<Float, Color>>,
    val ridge: Color, val farBody: Color, val farWarm: Color, val farCool: Color, val nearBody: Color, val bloom: Color, val nearWarm: Color, val nearCool: Color,
    val antenna: Color, val beacon: Color, val deck: Color, val post: Color, val lamp: Color, val lampReflect: Color, val lines: Color,
    val reflectWarm: Color, val reflectCool: Color, val stars: Float, val orb: Color, val orbHalo: Color, val orbShade: Color?, val orbRadius: Float, val clouds: Color?,
)

private val DuskPaint = SkyPaint(
    sky = listOf(0f to Color(0xFF070C1E), .38f to Color(0xFF17193F), .58f to Color(0xFF3A2852), .66f to Color(0xFF7A4560), .70f to Color(0xFFC9785A)),
    horizonGlow = listOf(Color(0x80FF9C6B), Color(0x26B05A8A), Color(0x00FF9C6B)),
    water = listOf(0f to Color(0xFF231B3A), .35f to Color(0xFF121633), 1f to Color(0xFF0B111C)),
    ridge = Color(0xFF2A2350), farBody = Color(0xFF1A1C3C), farWarm = Color(0x66FFCF8A), farCool = Color(0x557FC8FF), nearBody = Color(0xFF0E1128),
    bloom = Color(0x14FFD9A0), nearWarm = Color(0xD9FFDDA0), nearCool = Color(0xB39FD8FF), antenna = Color(0xFF0E1128), beacon = Color(0xFFFF5A4E),
    deck = Color(0xFF262A4E), post = Color(0xFF1C1F3E), lamp = Color(0xF2FFE7B8), lampReflect = Color(0x55FFE7B8), lines = Color(0x0BFFFFFF),
    reflectWarm = Color(0x66FFD49A), reflectCool = Color(0x559FD8FF), stars = 1f,
    orb = Color(0xFFF6EBD3), orbHalo = Color(0x40F6EBD3), orbShade = Color(0x1A0B111C), orbRadius = .038f, clouds = null,
)

private val NightPaint = SkyPaint(
    sky = listOf(0f to Color(0xFF04081A), .45f to Color(0xFF0C1130), .72f to Color(0xFF1A1A40), .88f to Color(0xFF2E2448), 1f to Color(0xFF4A3350)),
    horizonGlow = listOf(Color(0x4DE08A5C), Color(0x1A8A4F80), Color(0x00FF9C6B)),
    water = listOf(0f to Color(0xFF1C1733), .35f to Color(0xFF10142E), 1f to Color(0xFF0B111C)),
    ridge = Color(0xFF231E45), farBody = Color(0xFF161836), farWarm = Color(0x66FFCF8A), farCool = Color(0x557FC8FF), nearBody = Color(0xFF0B0E24),
    bloom = Color(0x14FFD9A0), nearWarm = Color(0xD9FFDDA0), nearCool = Color(0xB39FD8FF), antenna = Color(0xFF0B0E24), beacon = Color(0xFFFF5A4E),
    deck = Color(0xFF22264A), post = Color(0xFF1A1D3A), lamp = Color(0xF2FFE7B8), lampReflect = Color(0x55FFE7B8), lines = Color(0x0BFFFFFF),
    reflectWarm = Color(0x66FFD49A), reflectCool = Color(0x559FD8FF), stars = 1.3f,
    orb = Color(0xFFF8F0DC), orbHalo = Color(0x4DF6EBD3), orbShade = Color(0x1A0B111C), orbRadius = .040f, clouds = null,
)

/** A clear morning: blue overhead fading to a warm haze at the horizon, a low sun, white clouds, pale towers with a few glints. */
private val DayPaint = SkyPaint(
    sky = listOf(0f to Color(0xFF7FB6EE), .42f to Color(0xFFBFDAF5), .78f to Color(0xFFE6EEF7), 1f to Color(0xFFF6EADA)),
    horizonGlow = listOf(Color(0x55FFE3B8), Color(0x1FFFD9A0), Color(0x00FFD9A0)),
    water = listOf(0f to Color(0xFFCFE0F2), .4f to Color(0xFFDEE8F4), 1f to Color(0xFFEEF3FA)),
    ridge = Color(0xFFB7C8DD), farBody = Color(0xFFC7D5E6), farWarm = Color(0x59FFFFFF), farCool = Color(0x40FFFFFF), nearBody = Color(0xFF9AACC4),
    bloom = Color.Transparent, nearWarm = Color(0x8CFFFFFF), nearCool = Color(0x66FFFFFF), antenna = Color(0xFF8E9FB8), beacon = Color(0xFFE06A5A),
    deck = Color(0xFFA7B8CE), post = Color(0xFFB9C7D9), lamp = Color(0x99FFFFFF), lampReflect = Color(0x33FFFFFF), lines = Color(0x40FFFFFF),
    reflectWarm = Color(0x40FFFFFF), reflectCool = Color(0x40FFFFFF), stars = 0f,
    orb = Color(0xFFFFF6DC), orbHalo = Color(0x59FFE4A8), orbShade = null, orbRadius = .044f, clouds = Color(0x9EFFFFFF),
)

private fun paintFor(phase: ScenePhase) = when (phase) { ScenePhase.DAY -> DayPaint; ScenePhase.DUSK -> DuskPaint; ScenePhase.NIGHT -> NightPaint }

/** The decorative hero background for one [phase]: sky, city across the river, bridge, reflections. No semantics. */
@Composable internal fun Skyline(phase: ScenePhase, modifier: Modifier = Modifier, horizonAt: Float = .62f, heightScale: Float = 1f, orbAt: Offset = Offset(.53f, .40f)) {
    val scene = remember { skylineScene() }
    val p = paintFor(phase)
    Spacer(modifier.clearAndSetSemantics {}.drawWithCache {
        val w = size.width
        val h = size.height
        val horizon = h * horizonAt
        // Tower and ridge heights scale separately from the frame, so a full-screen backdrop keeps a low, wide skyline.
        val ht = h * heightScale
        val sky = Brush.verticalGradient(*p.sky.toTypedArray(), endY = horizon)
        val water = Brush.verticalGradient(*p.water.toTypedArray(), startY = horizon, endY = h)
        val glow = Brush.radialGradient(p.horizonGlow, center = Offset(w * .76f, horizon), radius = w * .52f)
        val ridge = Path().apply {
            moveTo(w * .26f, horizon)
            scene.ridge.forEach { lineTo(w * it.x, horizon - ht * it.y) }
            lineTo(w, horizon - ht * .08f); lineTo(w, horizon); close()
        }
        fun bodies(list: List<SkyTower>) = Path().apply {
            list.forEach { t ->
                val left = w * t.left; val right = left + w * t.width; val top = horizon - ht * t.height
                when (t.crown) {
                    2 -> { moveTo(left, horizon); lineTo(left, top + ht * .03f); lineTo(left + (right - left) * .25f, top + ht * .03f)
                        lineTo(left + (right - left) * .25f, top); lineTo(right - (right - left) * .25f, top); lineTo(right - (right - left) * .25f, top + ht * .03f)
                        lineTo(right, top + ht * .03f); lineTo(right, horizon); close() }
                    3 -> { moveTo(left, horizon); lineTo(left, top + ht * .025f); lineTo(right, top); lineTo(right, horizon); close() }
                    else -> addRect(Rect(left, top, right, horizon))
                }
            }
        }
        val farBodies = bodies(scene.far)
        val nearBodies = bodies(scene.near)
        fun windows(list: List<SkyTower>, cool: Boolean) = Path().apply {
            list.filter { it.cool == cool }.forEach { t ->
                val left = w * t.left; val tw = w * t.width; val th = ht * t.height * .86f; val top = horizon - ht * t.height + ht * t.height * .1f
                t.lit.forEach { pt -> addRect(Rect(Offset(left + tw * pt.x - 1.6f, top + th * pt.y - 2.2f), Size(3.2f, 4.4f))) }
            }
        }
        val farWarm = windows(scene.far, false); val farCool = windows(scene.far, true)
        val nearWarm = windows(scene.near, false); val nearCool = windows(scene.near, true)
        val antennas = scene.near.filter { it.crown == 1 && it.height > .26f }
        val orb = Offset(w * orbAt.x, h * orbAt.y)
        val orbHalo = Brush.radialGradient(listOf(p.orbHalo, Color.Transparent), center = orb, radius = ht * (if (p.clouds != null) .26f else .16f))
        // A soft glow around every few warm windows so the towers read as lit from inside rather than dotted.
        val bloom = Path().apply {
            scene.near.filter { !it.cool }.forEach { t ->
                val left = w * t.left; val tw = w * t.width; val th = ht * t.height * .86f; val top = horizon - ht * t.height + ht * t.height * .1f
                t.lit.forEachIndexed { i, pt -> if (i % 4 == 0) addOval(Rect(Offset(left + tw * pt.x, top + th * pt.y), 6f)) }
            }
        }
        // Day clouds: three overlapping ovals per cloud, slightly flattened, drawn before the towers.
        val clouds = Path().apply {
            p.clouds ?: return@apply
            scene.clouds.forEach { c ->
                val cx = w * c.x; val cy = horizon * c.y; val s = ht * .07f * c.scale
                addOval(Rect(Offset(cx, cy), s * .5f))
                addOval(Rect(Offset(cx - s * .55f, cy + s * .12f), s * .38f))
                addOval(Rect(Offset(cx + s * .5f, cy + s * .1f), s * .42f))
                addRect(Rect(cx - s * .8f, cy + s * .05f, cx + s * .8f, cy + s * .45f))
            }
        }
        onDrawBehind {
            drawRect(sky, size = Size(w, horizon))
            drawRect(glow, size = Size(w, horizon))
            if (p.stars > 0f) scene.stars.forEach { (x, y, a) -> drawCircle(Color.White.copy(alpha = (a * p.stars).coerceAtMost(1f)), radius = 1.2f + a, center = Offset(w * x, h * y)) }
            // The sun or the moon rests just above the skyline, right of the search bar so it never sits behind text or chips.
            drawCircle(orbHalo, radius = ht * (if (p.clouds != null) .26f else .16f), center = orb)
            drawCircle(p.orb, radius = ht * p.orbRadius, center = orb)
            p.orbShade?.let { drawCircle(it, radius = ht * .030f, center = orb + Offset(ht * .012f, -ht * .006f)) }
            p.clouds?.let { drawPath(clouds, it) }
            drawPath(ridge, p.ridge)
            drawPath(farBodies, p.farBody)
            drawPath(farWarm, p.farWarm); drawPath(farCool, p.farCool)
            drawPath(nearBodies, p.nearBody)
            if (p.bloom.alpha > 0f) drawPath(bloom, p.bloom)
            drawPath(nearWarm, p.nearWarm); drawPath(nearCool, p.nearCool)
            antennas.forEach { t ->
                val x = w * (t.left + t.width / 2); val top = horizon - ht * t.height
                drawLine(p.antenna, Offset(x, top), Offset(x, top - ht * .05f), strokeWidth = 2.5f)
                drawCircle(p.beacon, radius = 3.2f, center = Offset(x, top - ht * .05f))
            }
            drawRect(water, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
            scene.near.filter { it.reflect > 0f }.forEach { t ->
                val left = w * (t.left + t.width * .3f); val streak = h * t.reflect
                drawRect(Brush.verticalGradient(listOf(if (t.cool) p.reflectCool else p.reflectWarm, Color.Transparent), startY = horizon, endY = horizon + streak),
                    topLeft = Offset(left, horizon), size = Size(w * t.width * .4f, streak))
            }
            // The bridge: a deck with a string of lights and their wavering reflection.
            val deck = horizon - h * .012f
            drawLine(p.deck, Offset(w * .22f, deck), Offset(w, deck), strokeWidth = 5f, cap = StrokeCap.Round)
            var x = w * .23f
            while (x < w) {
                drawLine(p.post, Offset(x, deck), Offset(x, horizon + h * .015f), strokeWidth = 3f)
                drawCircle(p.lamp, radius = 2.6f, center = Offset(x + w * .008f, deck - 3f))
                drawLine(p.lampReflect, Offset(x + w * .008f, horizon + h * .03f), Offset(x + w * .008f, horizon + h * .07f), strokeWidth = 2f)
                x += w * .034f
            }
            for (i in 1..5) drawLine(p.lines, Offset(w * .2f, horizon + h * .05f * i), Offset(w, horizon + h * .05f * i), strokeWidth = 1.5f)
        }
    })
}

/** The scene cross-fade: 900 ms, so a sunrise is a change of light rather than a flip. */
internal const val MOTION_SCENE_MS = 900

/**
 * The whole home screen's background: the city edge to edge in the current scene's light, with a scrim from the left for
 * the greeting and one from the bottom so the widgets sit on the calm base colour. Cross-fades when the scene changes.
 */
@Composable internal fun HomeBackdrop(modifier: Modifier = Modifier, horizonAt: Float = .52f, heightScale: Float = .72f, moonAt: Offset = Offset(.56f, .22f),
    leftScrim: Boolean = true) {
    val bg = Scene.bg
    val reduce = LocalReduceMotion.current
    Box(modifier) {
        Crossfade(LocalScenePhase.current, Modifier.matchParentSize(), animationSpec = if (reduce) snap() else tween(MOTION_SCENE_MS), label = "sky-phase") { phase ->
            Skyline(phase, Modifier.matchParentSize(), horizonAt, heightScale, moonAt)
        }
        if (leftScrim) Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = .92f), .38f to bg.copy(alpha = .62f), .74f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(0f to bg.copy(alpha = .35f), .14f to Color.Transparent,
            (horizonAt - .04f).coerceAtLeast(.2f) to Color.Transparent, (horizonAt + .16f).coerceAtMost(.98f) to bg.copy(alpha = .90f), 1f to bg)))
    }
}

/** The backdrop at [level] (1 on home, dimmed elsewhere, 0 hidden), fading on the page clock inside its own recomposition scope. */
@Composable internal fun SkyLayer(level: Float, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(level, if (LocalReduceMotion.current) snap() else tween(MOTION_PAGE_MS), label = "sky")
    if (level > 0f || alpha > 0f) HomeBackdrop(modifier.graphicsLayer { this.alpha = alpha })
}
