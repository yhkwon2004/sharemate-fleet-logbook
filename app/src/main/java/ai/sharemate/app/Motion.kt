package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/*
 * Motion tokens for the parked screens and the phone. Entering content eases out (fast start, soft landing), state
 * changes use a light spring, lists stagger, loading never pretends. Everything collapses to an instant change when
 * the system animation scale is off (LocalReduceMotion); the driving screen uses none of this.
 */

internal const val MOTION_ENTER_MS = 220
internal const val MOTION_STAGGER_MS = 40

/** Ease-out for things arriving on screen. */
@Composable fun <T> enterSpec(duration: Int = MOTION_ENTER_MS, delay: Int = 0): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else tween(duration, delayMillis = delay, easing = FastOutSlowInEasing)

/** A light spring for values that change in place (progress, selection); it settles without visible overshoot on a car screen. */
@Composable fun <T> springSpec(): FiniteAnimationSpec<T> =
    if (LocalReduceMotion.current) snap() else spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow)

/**
 * Fades and lifts [content] in once; [index] staggers siblings so a list arrives top to bottom. [animate] false renders
 * the content in place, for rows that already had their entrance (a card the driver is returning to).
 */
@Composable fun Appear(modifier: Modifier = Modifier, index: Int = 0, animate: Boolean = true, content: @Composable () -> Unit) {
    if (LocalReduceMotion.current || !animate) { Box(modifier) { content() }; return }
    val state = remember { MutableTransitionState(false).apply { targetState = true } }
    val delay = (index * MOTION_STAGGER_MS).coerceAtMost(400)
    AnimatedVisibility(visibleState = state, modifier = modifier,
        enter = fadeIn(tween(MOTION_ENTER_MS, delayMillis = delay, easing = FastOutSlowInEasing)) +
            slideInVertically(tween(MOTION_ENTER_MS, delayMillis = delay, easing = FastOutSlowInEasing)) { it / 10 },
        exit = fadeOut(tween(120))) { content() }
}

/** Skeleton placeholder: a slow pulse, no shimmer, so it reads as "loading" without drawing the eye. */
@Composable fun SkeletonBlock(modifier: Modifier = Modifier, height: Dp = 16.dp, width: Dp? = null, corner: Dp = 8.dp) {
    val base = LocalPleosColors.current.surfaceHigh
    val alpha = if (LocalReduceMotion.current) 1f else {
        val pulse = rememberInfiniteTransition(label = "skeleton")
        val value by pulse.animateFloat(0.55f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "skeleton-alpha")
        value
    }
    Box(modifier.then(if (width != null) Modifier.width(width) else Modifier).height(height).alpha(alpha).background(base, RoundedCornerShape(corner))
        .semantics { contentDescription = "불러오는 중" })
}

/** Button content that shows real progress: the label stays, a small ring joins it while [loading]. */
@Composable fun LoadingLabel(label: String, loading: Boolean, loadingLabel: String = label) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(10.dp))
        }
        Text(if (loading) loadingLabel else label)
    }
}

/**
 * Reveals [text] a few characters at a time, the way a streamed answer arrives. The whole text is in the semantics
 * from the first frame, so a screen reader is never fed fragments; with reduced motion it simply appears.
 */
@Composable fun StreamingText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified,
    charsPerTick: Int = 2, tickMillis: Long = 18, onDone: (() -> Unit)? = null) {
    val reduce = LocalReduceMotion.current
    var shown by remember(text) { mutableIntStateOf(if (reduce) text.length else 0) }
    LaunchedEffect(text, reduce) {
        if (reduce) { onDone?.invoke(); return@LaunchedEffect }
        while (shown < text.length) { delay(tickMillis); shown = (shown + charsPerTick).coerceAtMost(text.length) }
        onDone?.invoke()
    }
    Text(text.take(shown), modifier.semantics { contentDescription = text }, style = style, color = color)
}

/**
 * Press feedback for cards, tiles and chips: the surface settles to [pressedScale] while held and eases back on release,
 * so a tap reads as a touch on something solid. Nothing under reduced motion. Pass the same [interactionSource] to the clickable.
 */
@Composable fun Modifier.pressable(interactionSource: InteractionSource, pressedScale: Float = .97f): Modifier {
    if (LocalReduceMotion.current) return this
    val pressed by interactionSource.collectIsPressedAsState()
    // 90 ms in, 160 ms out: quick to acknowledge the touch, soft to let go (no bounce on a car screen).
    val scale by animateFloatAsState(if (pressed) pressedScale else 1f,
        if (pressed) tween(90, easing = LinearOutSlowInEasing) else tween(160, easing = FastOutSlowInEasing), label = "press")
    return graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * A number that rolls to its new value (600 ms ease-out, like an odometer settling) and counts up from zero the first time
 * it appears when [countUp]; the exact value, at once, under reduced motion.
 */
@Composable fun animatedValue(target: Float, countUp: Boolean = true, durationMs: Int = 600): Float {
    val reduce = LocalReduceMotion.current
    var started by remember { mutableStateOf(!countUp || reduce) }
    LaunchedEffect(Unit) { started = true }
    val value by animateFloatAsState(if (started) target else 0f, if (reduce) snap() else tween(durationMs, easing = FastOutSlowInEasing), label = "value")
    return if (reduce) target else value
}

/**
 * A value that changes in place (a widget number, a greeting, a count): the old text fades out as the new one fades in
 * with a 6 dp lift, 160 ms; an instant swap under reduced motion. Never used for the clock or live telemetry, which simply update.
 */
@Composable fun DigitChange(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyLarge, color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null, maxLines: Int = 1, lift: Dp = 6.dp) {
    val reduce = LocalReduceMotion.current
    val liftPx = with(LocalDensity.current) { lift.roundToPx() }
    AnimatedContent(targetState = text, modifier = modifier, label = "digit", transitionSpec = {
        if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
        else (fadeIn(tween(160, easing = FastOutSlowInEasing)) + slideInVertically(tween(160, easing = FastOutSlowInEasing)) { liftPx }) togetherWith
            (fadeOut(tween(120, easing = FastOutSlowInEasing)) + slideOutVertically(tween(160, easing = FastOutSlowInEasing)) { -liftPx })
    }) { value -> Text(value, style = style, color = color, fontWeight = fontWeight, maxLines = maxLines, overflow = TextOverflow.Ellipsis) }
}

/** Three dots breathing in turn: the thinking indicator, used only while real work is pending. */
@Composable fun ThinkingDots(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val reduce = LocalReduceMotion.current
    Row(modifier.semantics { contentDescription = "답변을 준비하고 있어요" }, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha = if (reduce) 1f else {
                val pulse = rememberInfiniteTransition(label = "dots")
                val value by pulse.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = index * 150, easing = LinearEasing), RepeatMode.Reverse), label = "dot$index")
                value
            }
            Box(Modifier.size(10.dp).alpha(alpha).background(color, RoundedCornerShape(50)))
            if (index < 2) Spacer(Modifier.width(6.dp))
        }
    }
}
