package ai.sharemate.app

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The car's 2.12 components (docs/design/DESIGN_2_12_SPEC.md §2): cards, one-line copy, ⓘ rows, tags, pill buttons, chips,
 * mini stats and rows in the [CarSkin] tokens. They exist for the car only; the phone keeps [Panel] and its own pieces.
 * Every target is 56 dp or more (72 for a row with a line); a press is the .98 settle plus the press fill, never a ripple;
 * every motion snaps under reduced motion. Supporting copy is one line ([CarLine]): anything longer goes into an ⓘ row's
 * `more`, or into what TalkBack says.
 */

// ───────────────────────── press ─────────────────────────

/**
 * How far a press has come, 0 to 1, on the press clock of the spec: 90 ms in, 160 ms out, at once under reduced motion. For
 * controls whose container changes colour while held rather than taking [pressFill] over their content.
 */
@Composable internal fun pressStrength(interaction: InteractionSource): State<Float> {
    val pressed by interaction.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    return animateFloatAsState(if (pressed) 1f else 0f,
        if (reduce) snap() else if (pressed) tween(90, easing = LinearOutSlowInEasing) else tween(160, easing = FastOutSlowInEasing), label = "press-strength")
}

/**
 * [PrimaryAction]'s colours: the accent, darkened by [CarSkin.pressOnAccent] while held (the label keeps 5.70 on it); disabled,
 * the chip fill with a textMuted label (5.19 / 5.88), never a slab that reads stronger than an enabled button.
 */
@Composable internal fun primaryActionColors(interaction: InteractionSource): ButtonColors {
    val skin = Car.skin
    val strength by pressStrength(interaction)
    return ButtonDefaults.buttonColors(containerColor = lerp(skin.accent, skin.pressOnAccent.compositeOver(skin.accent), strength), contentColor = skin.onAccent,
        disabledContainerColor = skin.chipFill, disabledContentColor = Scene.textMuted)
}

/** [QuietAction]'s colours: no container but the scene's press fill while held; disabled, [color] at 38 %. */
@Composable internal fun quietActionColors(interaction: InteractionSource, color: Color): ButtonColors {
    val fill = Scene.fill
    val strength by pressStrength(interaction)
    return ButtonDefaults.buttonColors(containerColor = fill.copy(alpha = fill.alpha * strength), contentColor = color,
        disabledContainerColor = Color.Transparent, disabledContentColor = color.copy(alpha = .38f))
}

/** [QuietAction]'s outline: the chip border (3.47 / 3.69 on the card), at 38 % with its label when disabled. */
@Composable internal fun quietActionBorder(enabled: Boolean): BorderStroke {
    val border = Car.skin.chipBorder
    return BorderStroke(1.dp, if (enabled) border else border.copy(alpha = border.alpha * .38f))
}

// ───────────────────────── cards ─────────────────────────

/** A card's padding: 24 dp from EXPANDED up, 20 below (the 지금 column's tiles pass 20 everywhere). */
@Composable @ReadOnlyComposable internal fun carCardPadding(): Dp = if (LocalLayout.current.expanded) 24.dp else 20.dp

/**
 * The car's card, a sibling of [Panel] for the restyled pages: corner 20, the opaque [CarSkin.card] with a 1-dp hairline,
 * so a card reads the same over the sky, the backdrop and by night. [title] sits 12 dp above the [content], whose blocks
 * stand [gap] apart. A tappable card ([onClick]) adds the day drop shadow, the .98 settle and the press fill and names its
 * action for TalkBack ([onClickLabel]); [description] replaces the merged words where the card's own read badly aloud, and
 * [live] makes the card a polite live region, so a change TalkBack should hear is announced without a touch.
 */
@Composable internal fun CarCard(modifier: Modifier = Modifier, title: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
    onClickLabel: String? = null, description: String? = null, live: Boolean = false, padding: Dp = carCardPadding(), gap: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val skin = Car.skin
    val interaction = remember { MutableInteractionSource() }
    val press = if (onClick != null) Modifier.dayCardShadow(shape).pressable(interaction, .98f) else Modifier
    Surface(modifier.fillMaxWidth().then(press), shape = shape, color = skin.card, contentColor = Scene.text, border = BorderStroke(1.dp, skin.cardBorder)) {
        val tap = if (onClick == null) Modifier else Modifier
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = onClickLabel, onClick = onClick)
            .pressFill(interaction)
        val merged = if (description == null && !live) Modifier else Modifier.semantics(mergeDescendants = true) {
            description?.let { contentDescription = it }
            if (live) liveRegion = LiveRegionMode.Polite
        }
        Column(tap.then(merged).fillMaxWidth().padding(padding)) {
            if (title != null) {
                title()
                Spacer(Modifier.height(12.dp))
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap), content = content)
        }
    }
}

/**
 * A card's title row: [icon] (decorative) and the [title] in titleMedium, a heading for TalkBack ([heading]) on the text
 * alone; at the far end [trailing] (a tag, a link, a pill button), then a chevron on a card whose whole surface opens a
 * page. Where the end part does not fit beside the title (a 1.3× font, a narrow card) it wraps under it, start-aligned,
 * instead of squeezing the title. 32 dp tall, 56 when [trailing] holds a control. [trailing] keeps its own semantics, so a
 * status tag there stays its own live region.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CarSectionTitle(title: String, modifier: Modifier = Modifier, icon: Painter? = null, chevron: Boolean = false,
    heading: Boolean = true, trailing: (@Composable RowScope.() -> Unit)? = null) {
    val end = trailing != null || chevron
    FlowRow(modifier.fillMaxWidth().heightIn(min = 32.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), itemVerticalAlignment = Alignment.CenterVertically) {
        // 12 dp kept clear at the title's end: the end part only joins the line with at least that gap, or it wraps.
        Row(if (end) Modifier.padding(end = 12.dp) else Modifier, verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = Car.skin.icon)
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = if (heading) Modifier.semantics { heading() } else Modifier)
        }
        if (end) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trailing?.invoke(this)
            if (chevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(24.dp), tint = Scene.textSecondary)
        }
    }
}

/** The card hairline as a divider (decorative): 1 dp of [CarSkin.cardBorder]. */
@Composable internal fun CarDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Car.skin.cardBorder)

// ───────────────────────── one line ─────────────────────────

/**
 * How many lines a [CarLine] may take at [fontScale] (pure, unit-tested): one at the normal font, so copy is written to fit;
 * two above 1.05×, so a 1.3× font loses no words (WCAG 1.4.4) where an ellipsis would cut them.
 */
internal fun carLineMaxLines(fontScale: Float): Int = if (fontScale > 1.05f) 2 else 1

/**
 * One supporting line under a title, in bodySmall [color] (textMuted, 5.82 / 6.27 on the card): one line at the normal font
 * ([carLineMaxLines]), ellipsised past it. [spoken] replaces it for TalkBack where the long form says more than fits. A debug
 * build logs a line that overflows at the normal font (tag CarLine), which is how the screenshot pass finds copy to shorten.
 */
@Composable internal fun CarLine(text: String, modifier: Modifier = Modifier, color: Color = Scene.textMuted, spoken: String? = null) {
    val fontScale = LocalDensity.current.fontScale
    Text(text, modifier.then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier), color = color,
        style = MaterialTheme.typography.bodySmall, maxLines = carLineMaxLines(fontScale), overflow = TextOverflow.Ellipsis,
        onTextLayout = { result -> if (BuildConfig.DEBUG && result.hasVisualOverflow && fontScale <= 1.05f) Log.w("CarLine", text) })
}

/** The ⓘ row's fold and its arrow: 200 ms (spec C), a snap under reduced motion. */
private const val INFO_FOLD_MS = 200

/**
 * An ⓘ row: the info mark and one [line] ([CarLine], [spoken] for TalkBack). With [more] it is a 56-dp toggle (자세히 보기 /
 * 접기, state 접힘 / 펼침) whose arrow turns as the lines unfold under the line, aligned with it; there they may wrap, as they
 * are the long form. The fold is expandVertically and a fade in 200 ms with the arrow on the same clock, and it keeps its
 * state across a page change or a rotation.
 */
@Composable internal fun CarInfoRow(line: String, modifier: Modifier = Modifier, more: List<String> = emptyList(), spoken: String? = null) {
    val mark: @Composable () -> Unit = { Icon(Icons.Outlined.Info, contentDescription = null, Modifier.size(20.dp), tint = Scene.textMuted) }
    if (more.isEmpty()) {
        Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            mark()
            Spacer(Modifier.width(10.dp))
            CarLine(line, Modifier.weight(1f), spoken = spoken)
        }
        return
    }
    var open by rememberSaveable { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val ease = FastOutSlowInEasing
    val interaction = remember { MutableInteractionSource() }
    val turn by animateFloatAsState(if (open) 180f else 0f, if (reduce) snap() else tween(INFO_FOLD_MS, easing = ease), label = "info-arrow")
    Column(modifier.fillMaxWidth()) {
        // The outer semantics names the click (the toggle's own action carries no label) and says the state in words.
        Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(RoundedCornerShape(12.dp))
            .semantics {
                onClick(label = if (open) "접기" else "자세히 보기") { open = !open; true }
                stateDescription = if (open) "펼침" else "접힘"
            }
            .toggleable(value = open, interactionSource = interaction, indication = null, role = Role.Button) { open = it }
            .pressFill(interaction).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            mark()
            Spacer(Modifier.width(10.dp))
            CarLine(line, Modifier.weight(1f), spoken = spoken)
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, Modifier.size(24.dp).graphicsLayer { rotationZ = turn }, tint = Scene.textSecondary)
        }
        AnimatedVisibility(open, label = "info-more",
            enter = if (reduce) fadeIn(snap()) else fadeIn(tween(INFO_FOLD_MS, easing = ease)) + expandVertically(tween(INFO_FOLD_MS, easing = ease)),
            exit = if (reduce) fadeOut(snap()) else fadeOut(tween(INFO_FOLD_MS, easing = ease)) + shrinkVertically(tween(INFO_FOLD_MS, easing = ease))) {
            Column(Modifier.fillMaxWidth().padding(start = 30.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                more.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Scene.textMuted) }
            }
        }
    }
}

// ───────────────────────── tags ─────────────────────────

/**
 * The dot of a status tone: ready in glow (자동 기록 대기, the mock's teal dot), waiting in textMuted, recording and
 * connected in positive, a failure or a stale signal in negative, demo data in the assistant's ink. Every one clears 4.5 on
 * the tag fill and on the card, so the dot never carries less than its label.
 */
@Composable @ReadOnlyComposable internal fun pillDotColor(tone: PillTone): Color = when (tone) {
    PillTone.READY -> Scene.glow
    PillTone.QUIET -> Scene.textMuted
    PillTone.POSITIVE -> Scene.positive
    PillTone.NEGATIVE -> Scene.negative
    PillTone.DEMO -> LocalPleosColors.current.gleoSpeaker
}

/**
 * A tag: a short fact on the tag fill (a count, a basis, a state), never a control. With a [tone] a 10-dp dot leads the label,
 * which then takes the text colour; without one the label is textSecondary. One node for TalkBack ([description] replaces
 * the words); [live] announces a change politely. A changed tone swaps the dot at once: status colours never animate.
 */
@Composable internal fun CarTag(text: String, modifier: Modifier = Modifier, tone: PillTone? = null, live: Boolean = false, description: String? = null) {
    val skin = Car.skin
    val shape = RoundedCornerShape(50)
    Row(modifier.heightIn(min = 32.dp).clip(shape).background(skin.tagFill).border(1.dp, skin.tagBorder, shape)
        .semantics(mergeDescendants = true) {
            if (live) liveRegion = LiveRegionMode.Polite
            description?.let { contentDescription = it }
        }
        .padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (tone != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(pillDotColor(tone)))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (tone != null) Scene.text else Scene.textSecondary, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

// ───────────────────────── buttons and chips ─────────────────────────

/**
 * A secondary action in a card (바꾸기, 추가 / 닫기, 삭제 / 취소, 완료 / 다시 열기): a 46-dp outlined stadium inside a 56-dp
 * target, [width] wide or at least 112 dp, the label in [color] (Scene.negative for a destructive one, 5.60 / 4.81). It
 * settles to .98 with the press fill inside the pill; disabled, label and outline drop to 38 %. [description] replaces the
 * label for TalkBack and always contains it.
 */
@Composable internal fun CarPillButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    color: Color = Scene.text, description: String? = null, width: Dp? = null) {
    val interaction = remember { MutableInteractionSource() }
    val border = Car.skin.chipBorder
    val alpha = if (enabled) 1f else .38f
    val shape = RoundedCornerShape(50)
    // The 5-dp band above and below the pill belongs to the target, so a 46-dp pill keeps a 56-dp touch target.
    Box(modifier.pressable(interaction, .98f)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .padding(vertical = 5.dp)
        .then(if (width != null) Modifier.width(width) else Modifier.widthIn(min = 112.dp))
        .heightIn(min = 46.dp).clip(shape).border(1.dp, border.copy(alpha = border.alpha * alpha), shape).pressFill(interaction)
        .padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = color.copy(alpha = color.alpha * alpha), maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier)
    }
}

/**
 * An example the driver can send as is (the assistant's “…” phrases): a 46-dp stadium on the chip fill inside a 56-dp target,
 * the phrase in labelLarge on one line. [actionLabel] names what the tap does (운행 비서에서 열기). Lay them out in a FlowRow
 * 8 dp apart.
 */
@Composable internal fun CarExampleChip(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, actionLabel: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val skin = Car.skin
    val alpha = if (enabled) 1f else .38f
    val shape = RoundedCornerShape(50)
    val ink = Scene.text
    Box(modifier.pressable(interaction, .98f)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClickLabel = actionLabel, role = Role.Button, onClick = onClick)
        .padding(vertical = 5.dp).heightIn(min = 46.dp).clip(shape).background(skin.chipFill)
        .border(1.dp, skin.chipBorder.copy(alpha = skin.chipBorder.alpha * alpha), shape).pressFill(interaction)
        .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = ink.copy(alpha = ink.alpha * alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A link inside a card (시연 체험 다시보기): the label and a chevron in the accent ink, which is AA on the card only (4.93 /
 * 7.66), so never on a chip or the page. A 56-dp target that settles to .98 with the press fill. [description] replaces the
 * label for TalkBack.
 */
@Composable internal fun CarLink(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, description: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val ink = Car.skin.accentInk
    Row(modifier.pressable(interaction, .98f).clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .pressFill(interaction).heightIn(min = 56.dp).widthIn(min = 56.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp), tint = ink)
    }
}

/** A line icon on the disc (the switch rows' icons, as in the mock), decorative: the row says what it is. */
@Composable internal fun CarIconDisc(icon: Painter, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 22.dp) {
    val skin = Car.skin
    Box(modifier.size(size).clip(CircleShape).background(skin.disc), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, Modifier.size(iconSize), tint = skin.icon)
    }
}

/**
 * The switch in the car's tokens: on, the accent track with a white thumb; off, the dark thumb on the light track (3.71 / 7.09)
 * inside the chip border, so the off state never rests on colour alone. Disabled, both at 38 % over the card.
 */
@Composable internal fun carSwitchColors(): SwitchColors {
    val skin = Car.skin
    fun dim(color: Color) = color.copy(alpha = color.alpha * .38f).compositeOver(skin.card)
    return SwitchDefaults.colors(
        checkedThumbColor = skin.onAccent, checkedTrackColor = skin.accent, checkedBorderColor = skin.accent,
        uncheckedThumbColor = skin.thumbOff, uncheckedTrackColor = skin.trackOff, uncheckedBorderColor = skin.chipBorder,
        disabledCheckedThumbColor = dim(skin.onAccent), disabledCheckedTrackColor = dim(skin.accent), disabledCheckedBorderColor = dim(skin.accent),
        disabledUncheckedThumbColor = dim(skin.thumbOff), disabledUncheckedTrackColor = dim(skin.trackOff), disabledUncheckedBorderColor = dim(skin.chipBorder),
    )
}

// ───────────────────────── stats and rows ─────────────────────────

/**
 * One of three small figures across a card (완료 운행 · 등록 경비 · 계기판): an 18-dp icon and the label, the value 4 dp
 * below in titleSmall with tabular digits. A value never ellipsises: it steps down to 14 sp where it would not fit its third
 * of the card ("12,345.6 km" takes 107 of 109 dp at 1×). A [live] value (telemetry) updates in place; any other value
 * cross-fades as [DigitChange] does. One node for TalkBack.
 */
@Composable internal fun CarMiniStat(icon: Painter, label: String, value: String, modifier: Modifier = Modifier, live: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = Car.skin.icon)
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(4.dp))
        if (live) MiniStatValue(value)
        else {
            val reduce = LocalReduceMotion.current
            val lift = with(LocalDensity.current) { 6.dp.roundToPx() }
            AnimatedContent(targetState = value, label = "mini-stat", transitionSpec = {
                val ease = FastOutSlowInEasing
                val swap = if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(tween(160, easing = ease)) + slideInVertically(tween(160, easing = ease)) { lift }) togetherWith
                    (fadeOut(tween(120, easing = ease)) + slideOutVertically(tween(160, easing = ease)) { -lift })
                swap using SizeTransform(clip = false) { _, _ -> digitSizeSpec(reduce) }
            }) { shown -> MiniStatValue(shown) }
        }
    }
}

/** A mini stat's figure: titleSmall, tabular, one line, stepping down to 14 sp instead of ellipsising. */
@Composable private fun MiniStatValue(value: String) {
    val style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum")
    val ink = Scene.text
    BasicText(value, style = style, color = { ink }, maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 19.sp, stepSize = .5.sp))
}

/**
 * A list row in a card (a booking, a maintenance record, an employee's usage): [lead] in a 24-dp slot (an icon, a 12-dp dot or
 * a 32-dp disc), the [title] in titleSmall over one [line], and [trailing] (an amount, a pill button). At least 72 dp with a
 * line, 56 without. The text merges into one node that carries [state] and, where the words read badly aloud, [spoken];
 * the trailing control keeps its own. [titleMaxLines] lets data (a booking window) take two lines.
 */
@Composable internal fun CarRow(title: String, modifier: Modifier = Modifier, line: String? = null, lead: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null, titleColor: Color = Scene.text, lineColor: Color = Scene.textMuted,
    titleMaxLines: Int = 1, state: String? = null, spoken: String? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = if (line != null) 72.dp else 56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) {
            Box(Modifier.widthIn(min = 24.dp), contentAlignment = Alignment.Center) { lead() }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {
            state?.let { stateDescription = it }
            spoken?.let { contentDescription = it }
        }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (line != null) CarLine(line, color = lineColor)
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}
