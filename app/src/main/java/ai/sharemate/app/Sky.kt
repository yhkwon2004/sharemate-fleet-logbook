package ai.sharemate.app

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min
import kotlin.random.Random

/*
 * The city across the river, in three lights. One seeded skyline (the same towers every launch, denser and taller to the
 * right so the greeting on the left stays calm) is painted as a clear morning, a dusk with an orange horizon, or a deep
 * night, and [HomeBackdrop] cross-fades between them when the scene changes. Decorative only: no semantics. Nothing here
 * reads the clock: the skyline is still, and the only motion is the one crossfade of a parked scene change.
 */

private class SkyTower(val left: Float, val width: Float, val height: Float, val lit: List<Offset>, val crown: Int, val cool: Boolean, val reflect: Float)
/** One day cloud: its centre as fractions of the width ([x]) and of the horizon's height ([y]), and its size ([scale]). */
internal class SkyCloud(val x: Float, val y: Float, val scale: Float)
private class SkyScene(val stars: List<Triple<Float, Float, Float>>, val far: List<SkyTower>, val near: List<SkyTower>, val ridge: List<Offset>)

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
    return SkyScene(stars, far, near, ridge)
}

/**
 * The four day clouds, placed by hand in the free sky right of the car's 1200-dp content column (x .79–.97 of the width,
 * y .08–.34 of the horizon's height), so none sits behind the header chips, which end with the column. Staggered so no two
 * touch and above the towers. The car's sun rests in the same free sky ([freeSkyOrbAt]): on the full window all four clear
 * its disc, and [visibleDayClouds] leaves out any cloud that would come over it on a narrower one, as it leaves out those that
 * would reach the column. Not drawn from the seed, so the stars and the towers stay those of 2.8.
 */
internal val DayClouds = listOf(
    SkyCloud(.83f, .09f, .90f),
    SkyCloud(.95f, .17f, .75f),
    SkyCloud(.81f, .26f, .70f),
    SkyCloud(.96f, .30f, .95f),
)

/** A cloud's size against the towers' height, and how far its underside shade drops (a fraction of the sky's height). */
private const val CLOUD_SIZE = .07f
private const val CLOUD_SHADE_DROP = .012f

/**
 * The box one day [cloud] covers in a [w] × [h] sky whose horizon is at [horizon] px, with the towers scaled to [ht] px: its
 * three ovals, its flat base and the underside shade below them (pure, unit-tested).
 */
internal fun dayCloudBox(cloud: SkyCloud, w: Float, h: Float, horizon: Float, ht: Float): Rect {
    val cx = w * cloud.x; val cy = horizon * cloud.y; val s = ht * CLOUD_SIZE * cloud.scale
    return Rect(cx - s * .93f, cy - s * .5f, cx + s * .92f, cy + s * .52f + h * CLOUD_SHADE_DROP)
}

/**
 * The day clouds whose box lies wholly right of [clearOf] px, where the content column ends, and, while a sun is drawn at
 * [orb], stays farther than [orbClear] px (its disc and a margin) from its centre: a cloud is never painted over the sun
 * (pure, unit-tested).
 */
internal fun visibleDayClouds(w: Float, h: Float, horizon: Float, ht: Float, clearOf: Float, orb: Offset? = null, orbClear: Float = 0f): List<SkyCloud> =
    DayClouds.filter { cloud ->
        val box = dayCloudBox(cloud, w, h, horizon, ht)
        box.left >= clearOf && (orb == null || distanceToBox(orb, box) > orbClear)
    }

/** How far [p] lies from the nearest point of [box]; 0 inside it. */
internal fun distanceToBox(p: Offset, box: Rect): Float = hypot(maxOf(box.left - p.x, 0f, p.x - box.right), maxOf(box.top - p.y, 0f, p.y - box.bottom))

/** The margin a cloud keeps beyond the sun's disc. */
internal val SUN_CLOUD_MARGIN = 12.dp

/** How one phase paints the scene. */
private class SkyPaint(
    val sky: List<Pair<Float, Color>>, val horizonGlow: List<Color>, val water: List<Pair<Float, Color>>,
    val ridge: Color, val farBody: Color, val farWarm: Color, val farCool: Color, val nearBody: Color, val bloom: Color, val nearWarm: Color, val nearCool: Color,
    val antenna: Color, val beacon: Color, val deck: Color, val post: Color, val lamp: Color, val lampReflect: Color, val lines: Color,
    val reflectWarm: Color, val reflectCool: Color, val stars: Float, val orb: Color, val orbHalo: Color, val orbShade: Color?, val orbRadius: Float, val clouds: Color?,
    /** Day extras: the near towers shade to [nearBodyBottom] at the river, the sun's disc runs [orbCore] → [orb] at its rim, the clouds carry a [cloudShade] underside. */
    val nearBodyBottom: Color? = null, val orbCore: Color? = null, val orbHaloRadius: Float = .16f, val cloudShade: Color? = null, val beaconRadius: Float = 3.2f,
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

/** The day sky at its top: the darkest sky any day haze lies over, so the haze's weakest point under text (checked in SceneTest). */
internal val DaySkyTop = Color(0xFF4F9BE8)

/**
 * A clear morning (2.10 spec, section A): a deep blue overhead that warms to a pale haze at the horizon, a low sun with a
 * wide halo and no rays, four still white clouds, a pale-blue ridge and far towers, near towers in a slate blue that keeps
 * 2.2:1 against the mid sky so the city stays present behind white glass, pale water and a slate bridge without lamp
 * reflections. No stars, no bloom, no red beacons: nothing that reads as night.
 */
private val DayPaint = SkyPaint(
    sky = listOf(0f to DaySkyTop, .45f to Color(0xFF8FC1F2), .70f to Color(0xFFD7E9F8), 1f to Color(0xFFF3EEE4)),
    horizonGlow = listOf(Color(0x99FFFFFF), Color(0x40FFFFFF), Color(0x00FFFFFF)),
    water = listOf(0f to Color(0xFFB9D4EC), .40f to Color(0xFFD5E6F4), 1f to Color(0xFFEEF3FA)),
    ridge = Color(0xFFAEC1D6), farBody = Color(0xFFB5C6DA), farWarm = Color(0x8CFFFFFF), farCool = Color(0x80E6EEF7), nearBody = Color(0xFF7B90AC),
    bloom = Color.Transparent, nearWarm = Color(0xCCDCEAF7), nearCool = Color(0x99FFFFFF), antenna = Color(0xFF5B6B82), beacon = Color(0xCCFFFFFF),
    deck = Color(0xFF5B6B82), post = Color(0xFF7F8EA3), lamp = Color(0xFFD8E2EE), lampReflect = Color.Transparent, lines = Color(0x4DFFFFFF),
    reflectWarm = Color(0x59FFFFFF), reflectCool = Color(0x59FFFFFF), stars = 0f,
    orb = Color(0xFFFFD27A), orbHalo = Color(0x8CFFF1C2), orbShade = null, orbRadius = .045f, clouds = Color(0xB3FFFFFF),
    nearBodyBottom = Color(0xFF6A7F9B), orbCore = Color(0xFFFFF3C4), orbHaloRadius = .30f, cloudShade = Color(0x73C9D8E8), beaconRadius = 2.2f,
)

private fun paintFor(phase: ScenePhase) = when (phase) { ScenePhase.DAY -> DayPaint; ScenePhase.DUSK -> DuskPaint; ScenePhase.NIGHT -> NightPaint }

// ───────────────────────── the crossfade's motion (pure, unit-tested) ─────────────────────────

/**
 * Where the sun or moon sits at [t] (0–1) of the crossfade, as a fraction of the sky height below its place: an entering
 * orb rises 8 % into place (LinearOutSlowIn), a leaving one sinks away (FastOutLinearIn), a settled one does not move.
 */
internal fun orbLift(entering: Boolean, leaving: Boolean, t: Float): Float = when {
    entering -> .08f * (1f - LinearOutSlowInEasing.transform(t.coerceIn(0f, 1f)))
    leaving -> .08f * FastOutLinearInEasing.transform(t.coerceIn(0f, 1f))
    else -> 0f
}

// ───────────────────────── where the car's sun or moon rests (pure, unit-tested) ─────────────────────────

/** The least sky right of the car's content column that takes the sun or moon clear of it (the full Pleos window keeps 408 dp). */
internal val FREE_SKY_MIN = 280.dp

/** How high the sun or moon rests in the free sky, as a fraction of the window's height: above the tallest towers' antennas. */
internal const val FREE_SKY_ORB_Y = .15f

/**
 * Where the car's sun or moon rests when the window keeps at least [FREE_SKY_MIN] of sky right of a content column ending at
 * [columnEnd] (the full Pleos window): across the middle of that sky, high enough to clear the towers. Nothing scrolls there,
 * so no card ever passes over it, and the empty side of the window gets its one point of light. Null when there is no such
 * sky (a narrower window, a column without a maximum): there every height inside the column belongs to the header, the hero
 * or the cards (and the header grows with the driver row, the font and the density), so the car leaves the sun and the moon
 * out and the sky tells day from night by its light alone.
 */
internal fun freeSkyOrbAt(windowWidth: Dp, columnEnd: Dp): Offset? {
    if (windowWidth <= 0.dp || columnEnd.value.isInfinite()) return null
    val free = windowWidth - columnEnd
    if (free < FREE_SKY_MIN) return null
    return Offset(((columnEnd + free / 2) / windowWidth).coerceIn(0f, 1f), FREE_SKY_ORB_Y)
}

/** How much of the stars shows at [t] of the crossfade: they fade within its first 40 %, in for an arriving night and out for a leaving one. */
internal fun starFade(entering: Boolean, leaving: Boolean, t: Float): Float {
    val early = (t.coerceIn(0f, 1f) / .4f).coerceAtMost(1f)
    return when { entering -> early; leaving -> 1f - early; else -> 1f }
}

/**
 * The decorative hero background for one [phase]: sky, city across the river, bridge, reflections. No semantics.
 * [entering] marks a skyline arriving through a scene change (its orb rises into place), [leaving] one on its way out.
 * By day it draws only the clouds that lie wholly right of [cloudsClearOf], where the content column ends.
 */
@Composable internal fun Skyline(phase: ScenePhase, modifier: Modifier = Modifier, horizonAt: Float = .62f, heightScale: Float = 1f, orbAt: Offset = Offset(.53f, .40f),
    entering: Boolean = false, leaving: Boolean = false, showOrb: Boolean = true, showClouds: Boolean = true, cloudsClearOf: Dp = 0.dp,
    orbAlpha: () -> Float = { 1f }) {
    val scene = remember { skylineScene() }
    val p = paintFor(phase)
    // One clock per crossfade, read only inside the draw so the cached paths never rebuild; nothing moves under reduced motion.
    val timing = LocalSceneChange.current
    val reduce = LocalReduceMotion.current
    val moving = (entering || leaving) && !reduce
    val progress = remember { Animatable(if (moving) 0f else 1f) }
    LaunchedEffect(entering, leaving) {
        if (!moving) { progress.snapTo(1f); return@LaunchedEffect }
        progress.snapTo(0f)
        progress.animateTo(1f, tween(timing.crossfadeMs, easing = LinearEasing))
    }
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
        // By day the near towers shade from their tops to the river, so they read as buildings in light rather than cut-outs.
        val nearFill: Brush? = p.nearBodyBottom?.let { Brush.verticalGradient(listOf(p.nearBody, it), startY = horizon - ht * scene.near.maxOf { t -> t.height }, endY = horizon) }
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
        val haloRadius = ht * p.orbHaloRadius
        val orbHalo = Brush.radialGradient(listOf(p.orbHalo, p.orbHalo.copy(alpha = 0f)), center = orb, radius = haloRadius)
        // The day sun is a soft disc, pale at the centre and warm at the rim; the moon stays a flat disc with its shade.
        val orbDisc: Brush? = p.orbCore?.let { Brush.radialGradient(listOf(it, p.orb), center = orb, radius = ht * p.orbRadius) }
        // A soft glow around every few warm windows so the towers read as lit from inside rather than dotted.
        val bloom = Path().apply {
            scene.near.filter { !it.cool }.forEach { t ->
                val left = w * t.left; val tw = w * t.width; val th = ht * t.height * .86f; val top = horizon - ht * t.height + ht * t.height * .1f
                t.lit.forEachIndexed { i, pt -> if (i % 4 == 0) addOval(Rect(Offset(left + tw * pt.x, top + th * pt.y), 6f)) }
            }
        }
        // Day clouds: three overlapping ovals per cloud, slightly flattened, drawn before the towers. Only the ones clear of the
        // content column, so none sits behind the header chips or a card (no free sky, no clouds), and clear of the sun's disc.
        val clouds = Path().apply {
            p.clouds ?: return@apply
            visibleDayClouds(w, h, horizon, ht, cloudsClearOf.toPx(), orb.takeIf { showOrb }, ht * p.orbRadius + SUN_CLOUD_MARGIN.toPx()).forEach { c ->
                val cx = w * c.x; val cy = horizon * c.y; val s = ht * CLOUD_SIZE * c.scale
                addOval(Rect(Offset(cx, cy), s * .5f))
                addOval(Rect(Offset(cx - s * .55f, cy + s * .12f), s * .38f))
                addOval(Rect(Offset(cx + s * .5f, cy + s * .1f), s * .42f))
                addRect(Rect(cx - s * .8f, cy + s * .05f, cx + s * .8f, cy + s * .45f))
            }
        }
        val cloudShadeOffset = h * CLOUD_SHADE_DROP
        onDrawBehind {
            // The crossfade clock is the only state read here: the draw invalidates, the paths above do not. The orb and the
            // stars move on the part of the crossfade the driver sees (the fade-through still hides the content at first).
            val t = sceneRevealProgress(progress.value, timing)
            val lift = ht * orbLift(entering, leaving, t)
            val starAlpha = p.stars * starFade(entering, leaving, t)
            drawRect(sky, size = Size(w, horizon))
            drawRect(glow, size = Size(w, horizon))
            if (starAlpha > 0f) scene.stars.forEach { (x, y, a) -> drawCircle(Color.White.copy(alpha = (a * starAlpha).coerceAtMost(1f)), radius = 1.2f + a, center = Offset(w * x, h * y)) }
            // The sun or the moon rests just above the skyline, right of the search bar so it never sits behind text or chips;
            // during a scene change it rises the last 8 % of the sky height into place (or sinks away).
            // [showOrb] false leaves the sun and the moon out where the page above would cover or crowd them; [orbAlpha] fades them
            // with the page instead (read here, in the draw, so a fade never rebuilds the cached paths).
            val orbA = if (showOrb) orbAlpha().coerceIn(0f, 1f) else 0f
            if (orbA > 0f) translate(top = lift) {
                val layer = Paint().apply { alpha = orbA }
                drawContext.canvas.saveLayer(Rect(orb.x - haloRadius, orb.y - haloRadius, orb.x + haloRadius, orb.y + haloRadius), layer)
                drawCircle(orbHalo, radius = haloRadius, center = orb)
                if (orbDisc != null) drawCircle(orbDisc, radius = ht * p.orbRadius, center = orb) else drawCircle(p.orb, radius = ht * p.orbRadius, center = orb)
                p.orbShade?.let { drawCircle(it, radius = ht * .030f, center = orb + Offset(ht * .012f, -ht * .006f)) }
                drawContext.canvas.restore()
            }
            if (showClouds) p.clouds?.let { fill ->
                p.cloudShade?.let { shade -> translate(top = cloudShadeOffset) { drawPath(clouds, shade) } }
                drawPath(clouds, fill)
            }
            drawPath(ridge, p.ridge)
            drawPath(farBodies, p.farBody)
            drawPath(farWarm, p.farWarm); drawPath(farCool, p.farCool)
            if (nearFill != null) drawPath(nearBodies, nearFill) else drawPath(nearBodies, p.nearBody)
            if (p.bloom.alpha > 0f) drawPath(bloom, p.bloom)
            drawPath(nearWarm, p.nearWarm); drawPath(nearCool, p.nearCool)
            antennas.forEach { t ->
                val x = w * (t.left + t.width / 2); val top = horizon - ht * t.height
                drawLine(p.antenna, Offset(x, top), Offset(x, top - ht * .05f), strokeWidth = 2.5f)
                drawCircle(p.beacon, radius = p.beaconRadius, center = Offset(x, top - ht * .05f))
            }
            drawRect(water, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
            scene.near.filter { it.reflect > 0f }.forEach { t ->
                val left = w * (t.left + t.width * .3f); val streak = h * t.reflect
                val tint = if (t.cool) p.reflectCool else p.reflectWarm
                drawRect(Brush.verticalGradient(listOf(tint, tint.copy(alpha = 0f)), startY = horizon, endY = horizon + streak),
                    topLeft = Offset(left, horizon), size = Size(w * t.width * .4f, streak))
            }
            // The bridge: a deck with a string of lights and (by night) their wavering reflection.
            val deck = horizon - h * .012f
            drawLine(p.deck, Offset(w * .22f, deck), Offset(w, deck), strokeWidth = 5f, cap = StrokeCap.Round)
            var x = w * .23f
            while (x < w) {
                drawLine(p.post, Offset(x, deck), Offset(x, horizon + h * .015f), strokeWidth = 3f)
                drawCircle(p.lamp, radius = 2.6f, center = Offset(x + w * .008f, deck - 3f))
                if (p.lampReflect.alpha > 0f) drawLine(p.lampReflect, Offset(x + w * .008f, horizon + h * .03f), Offset(x + w * .008f, horizon + h * .07f), strokeWidth = 2f)
                x += w * .034f
            }
            for (i in 1..5) drawLine(p.lines, Offset(w * .2f, horizon + h * .05f * i), Offset(w, horizon + h * .05f * i), strokeWidth = 1.5f)
        }
    })
}

/** The whole fade-through of an automatic scene change (ms): the content leaves, the sky crossfades while it is hidden, the content returns. See [sceneChangeTiming]. */
internal const val MOTION_SCENE_MS = 800

/** One scrim over the sky: colour stops (fraction → colour) along the vertical or the horizontal axis. */
internal class SkyScrim(val vertical: Boolean, val stops: List<Pair<Float, Color>>)

/**
 * The scrims that keep text legible over the sky, in painting order (pure, unit-tested). With [leftScrim] (the car home) a
 * lead scrim comes in from the left for the greeting: by night the base colour, as in 2.8; by day a white haze, lighter so
 * the sky shows (.90 → .76 at the hero column's right edge [heroEdge] → 0 at [heroFade] past it). Without it (the phone home
 * and the sign-in) there is no lead scrim at all: those screens lay their own haze over the sky by day, and a second white
 * layer here would wash out the sky and the sun. The bottom scrim is the one built in 2.8, lighter by day, and always
 * settles on the base colour. [heroEdge] and [heroFade] are fractions of the width.
 */
internal fun skyScrims(day: Boolean, leftScrim: Boolean, horizonAt: Float, haze: Color, bg: Color,
    heroEdge: Float = .47f, heroFade: Float = .18f): List<SkyScrim> {
    val clear = haze.copy(alpha = 0f)
    val lead = when {
        !leftScrim -> null
        day -> SkyScrim(vertical = false, listOf(0f to haze.copy(alpha = .90f), heroEdge to haze.copy(alpha = .76f), (heroEdge + heroFade).coerceAtMost(1f) to clear))
        else -> SkyScrim(vertical = false, listOf(0f to haze.copy(alpha = .92f), .38f to haze.copy(alpha = .62f), .74f to clear))
    }
    val base = SkyScrim(vertical = true, listOf(0f to haze.copy(alpha = if (day) .25f else .35f), .14f to clear,
        (horizonAt - .04f).coerceAtLeast(.2f) to clear, (horizonAt + .16f).coerceAtMost(.98f) to haze.copy(alpha = if (day) .85f else .90f), 1f to bg))
    return listOfNotNull(lead, base)
}

private class SkyLedger { var changes = 0 }

/**
 * The whole home screen's background: the city edge to edge in the current scene's light, with the scrims of [skyScrims]
 * so the greeting and the widgets stay legible. A scene change cross-fades the two skylines on the clock of
 * [LocalSceneChange] (600 ms inside the 800 ms fade-through, 360 inside 480 on a tap), the arriving sun or moon rising into
 * place while the leaving one sinks; an instant swap under reduced motion. [moonAt] places the sun by day as well: on the
 * car (.62, .21), clear of the hero haze; the phone passes its own. Without [leftScrim] (the phone home, the sign-in) only the
 * bottom scrim is drawn: the caller lays its own day haze. The day clouds keep to the sky right of the car's content column
 * and stay off the phone, whose sky is mostly haze: there they read as smudges beside the header icons.
 *
 * With [freeSkyOrb] (the car) the sun or moon rests only in the free sky right of the column ([freeSkyOrbAt]), where no card
 * or header ever passes over it; a window without free sky leaves it out.
 */
@Composable internal fun HomeBackdrop(modifier: Modifier = Modifier, horizonAt: Float = .52f, heightScale: Float = .72f, moonAt: Offset = Offset(.62f, .21f),
    leftScrim: Boolean = true, showOrb: Boolean = true, showClouds: Boolean = true, orbAlpha: () -> Float = { 1f }, freeSkyOrb: Boolean = false) {
    val colors = Scene.colors
    val day = colors.isDay
    val haze = colors.haze
    val bg = colors.bg
    val compact = LocalCompactApp.current
    val layout = LocalLayout.current
    val gutter = layout.gutter
    // The car's column starts at the gutter and stops at the content maximum; with no maximum it runs to the far gutter, so
    // no cloud fits beside it.
    val columnEnd = gutter + layout.contentMax
    val reduce = LocalReduceMotion.current
    val phase = LocalScenePhase.current
    val crossfadeMs = LocalSceneChange.current.crossfadeMs
    // The first skyline simply appears (never an entrance on launch or on return); one composed later is a scene change.
    val ledger = remember { SkyLedger() }
    val entering = remember(phase) { ledger.changes++ > 0 }
    BoxWithConstraints(modifier) {
        // The car's orb keeps to the free sky, where nothing scrolls over it; without free sky it is left out.
        val freeAt = if (freeSkyOrb) freeSkyOrbAt(maxWidth, columnEnd) else null
        val orbShown = showOrb && (!freeSkyOrb || freeAt != null)
        Crossfade(phase, Modifier.matchParentSize(), animationSpec = if (reduce) snap() else tween(crossfadeMs, easing = LinearEasing), label = "sky-phase") { shown ->
            Skyline(shown, Modifier.fillMaxSize(), horizonAt, heightScale, freeAt ?: moonAt, entering = entering && shown == phase, leaving = shown != phase,
                showOrb = orbShown, showClouds = showClouds && !compact, cloudsClearOf = columnEnd, orbAlpha = orbAlpha)
        }
        Spacer(Modifier.matchParentSize().drawWithCache {
            // The hero column ends 720 dp past the gutter on the car (never past 60 % of the window).
            val heroEdge = min(gutter.toPx() + 720.dp.toPx(), size.width * .6f) / size.width
            val scrims = skyScrims(day, leftScrim, horizonAt, haze, bg, heroEdge = heroEdge, heroFade = 300.dp.toPx() / size.width)
            val brushes = scrims.map { s -> if (s.vertical) Brush.verticalGradient(*s.stops.toTypedArray()) else Brush.horizontalGradient(*s.stops.toTypedArray()) }
            onDrawBehind { brushes.forEach { drawRect(it) } }
        })
    }
}

/** The car's backdrop at [level] (1 on home, dimmed elsewhere, 0 hidden), fading on the page clock inside its own recomposition scope. */
@Composable internal fun SkyLayer(level: Float, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(level, if (LocalReduceMotion.current) snap() else tween(MOTION_PAGE_MS), label = "sky")
    // The sun and the moon belong to the home; on the dimmed panes they fade out with the page (fully gone at the panes' .34)
    // instead of popping on the first frame of the tab change. On the home they rest in the free sky ([freeSkyOrbAt]).
    if (level > 0f || alpha > 0f) HomeBackdrop(modifier.graphicsLayer { this.alpha = alpha }, orbAlpha = { ((alpha - .34f) / .66f).coerceIn(0f, 1f) },
        freeSkyOrb = true)
}
