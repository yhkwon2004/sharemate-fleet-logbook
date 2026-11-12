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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measured
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

/*
 * The car's 2.12 components (docs/design/DESIGN_2_12_SPEC.md §2): cards, one-line copy, ⓘ rows, tags, pill buttons, chips,
 * mini stats and rows in the [CarSkin] tokens. They exist for the car only; the phone keeps [Panel] and its own pieces.
 * Every target is 56 dp or more (72 for a row with a line); a press is the .98 settle plus the press fill, never a ripple;
 * every motion snaps under reduced motion. Supporting copy is one line ([CarLine]): anything longer goes into an ⓘ row's
 * `more`, or into what TalkBack says. 2.13 (docs/design/DESIGN_2_13_SPEC.md §1) adds the one paint for a current row
 * ([carSelection]), choice tiles and chips, the step disc, the car's own dialog ([CarDialog]) and the money mini stat; amounts
 * themselves live in CarMoney.kt.
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
 * [PrimaryAction]'s colours: the accent, darkened by [CarSkin.pressOnAccent] while held (the label keeps 5.70 on it). Disabled
 * (2.13) it has no fill and a textMuted label (5.82 / 6.27 on the card) inside the 38 % outline [PrimaryAction] draws: the
 * 2.12 chip-fill slab read as a field (설정's 카드 저장, 저장된 기준), so a button that cannot act now looks like
 * [QuietAction]'s disabled one instead.
 */
@Composable internal fun primaryActionColors(interaction: InteractionSource): ButtonColors {
    val skin = Car.skin
    val strength by pressStrength(interaction)
    return ButtonDefaults.buttonColors(containerColor = lerp(skin.accent, skin.pressOnAccent.compositeOver(skin.accent), strength), contentColor = skin.onAccent,
        disabledContainerColor = Color.Transparent, disabledContentColor = Scene.textMuted)
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
 * [live] makes the card a polite live region, so a change TalkBack should hear is announced without a touch. [selected]
 * (2.13: the 점검 필요 card whose trip the detail shows) is set on the tappable, merged node TalkBack focuses, not on the
 * outer surface, which TalkBack never lands on; a caller's own `semantics { selected }` on [modifier] would go unheard.
 */
@Composable internal fun CarCard(modifier: Modifier = Modifier, title: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null,
    onClickLabel: String? = null, description: String? = null, live: Boolean = false, padding: Dp = carCardPadding(), gap: Dp = 16.dp,
    selected: Boolean? = null, content: @Composable ColumnScope.() -> Unit) {
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
        val state = if (selected == null) Modifier else Modifier.semantics { this.selected = selected }
        Column(tap.then(merged).then(state).fillMaxWidth().padding(padding)) {
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
 * status tag there stays its own live region. A [live] title (2.13: 운행 상세's date and time in the pane, which changes with
 * the row the driver opens) is a polite live region, so TalkBack hears the new title without a touch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun CarSectionTitle(title: String, modifier: Modifier = Modifier, icon: Painter? = null, chevron: Boolean = false,
    heading: Boolean = true, live: Boolean = false, trailing: (@Composable RowScope.() -> Unit)? = null) {
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
                modifier = if (heading || live) Modifier.semantics {
                    if (heading) heading()
                    if (live) liveRegion = LiveRegionMode.Polite
                } else Modifier)
        }
        if (end) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            trailing?.invoke(this)
            if (chevron) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(24.dp), tint = Scene.textSecondary)
        }
    }
}

/** The card hairline as a divider (decorative): 1 dp of [CarSkin.cardBorder]. */
@Composable internal fun CarDivider(modifier: Modifier = Modifier) = HorizontalDivider(modifier, thickness = 1.dp, color = Car.skin.cardBorder)

// ───────────────────────── selection ─────────────────────────

/**
 * The current row of a list (2.13, docs/design/DESIGN_2_13_SPEC.md §1.1): the open trip, the chosen person, a filter that
 * narrows the list. Selected, the row sits on the chip fill under a 2-dp accentInk ring (4.39 / 7.18 on the fill, 4.93 /
 * 7.66 against the card beside it); its name stays [Scene.text] and a check, where the row has one, is `Icons.Filled.Check`
 * in accentInk. Not selected, nothing, or a 1-dp chip border when [outlined] (a grid of cards). The car had two selection
 * colours, the teal selection fill and ring beside the blue accent; this is the one paint for a current row, while a control
 * that is chosen (a segment, a chip, a purpose tile) fills with the accent. Everything that sits on a selected row keeps AA on
 * the chip fill (CarSkinTest). A card that holds buttons (an issue card) takes the 2-dp accentInk ring alone, without this
 * fill, so its outlined buttons keep the card under them. Apply it inside the press scale and before the press fill, and clip
 * to [shape] there if the row draws a press fill.
 */
@Composable internal fun Modifier.carSelection(selected: Boolean, shape: Shape, outlined: Boolean = false): Modifier {
    val skin = Car.skin
    return when {
        selected -> background(skin.chipFill, shape).border(2.dp, skin.accentInk, shape)
        outlined -> border(1.dp, skin.chipBorder, shape)
        else -> this
    }
}

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
 * A tag: a short fact on the tag fill (a count, a basis, a state), never a control, and never a sum (an amount is a
 * [CarAmount]). With a [tone] a 10-dp dot leads the label, which then takes the text colour; without one the label is
 * textSecondary. [dot] (2.13) leads it with a dot in a colour of its own instead, a trip's purpose ([PurposeChip]), whose
 * four colours clear 4.0 on the tag fill in both scenes; it wins over [tone]. One node for TalkBack ([description] replaces
 * the words); [live] announces a change politely. A changed tone swaps the dot at once: status colours never animate.
 */
@Composable internal fun CarTag(text: String, modifier: Modifier = Modifier, tone: PillTone? = null, live: Boolean = false, description: String? = null,
    dot: Color? = null) {
    val skin = Car.skin
    val shape = RoundedCornerShape(50)
    val mark = dot ?: tone?.let { pillDotColor(it) }
    Row(modifier.heightIn(min = 32.dp).clip(shape).background(skin.tagFill).border(1.dp, skin.tagBorder, shape)
        .semantics(mergeDescendants = true) {
            if (live) liveRegion = LiveRegionMode.Polite
            description?.let { contentDescription = it }
        }
        .padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        if (mark != null) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(mark))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelMedium, color = if (mark != null) Scene.text else Scene.textSecondary, maxLines = 1,
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
 * A choice among a few options that each need a word of explanation (2.13: the three purposes, on 홈 and in 운행 상세): a
 * 72-dp tile, corner 16, that settles to .98 with the press fill. At rest it is outlined with the chip border (3.47 / 3.69):
 * an optional 10-dp [dot] (a purpose's colour), 8 dp, the [title] in labelLarge text, over one [line] in bodySmall textMuted,
 * centred. Chosen ([selected] true) it fills with the accent, both lines turn onAccent (4.93) and a 16-dp check takes the
 * dot's place, as a chosen [CarChoiceChip] does; the press darkens it with pressOnAccent. With [selected] set the tile is a
 * radio button of its group (put the row in `selectableGroup()`); left null it is a button that acts at once. TalkBack
 * hears one stop, "[title], [line]", or [description].
 */
@Composable internal fun CarChoiceTile(title: String, line: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    selected: Boolean? = null, dot: Color? = null, description: String? = null) {
    val skin = Car.skin
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val chosen = selected == true
    val ink = if (chosen) skin.onAccent else Scene.text
    val tap = if (selected != null) Modifier.selectable(selected = chosen, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
        else Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    val spoken = description ?: "$title, $line"
    Box(modifier.pressable(interaction, .98f).then(tap).semantics { contentDescription = spoken }.heightIn(min = 72.dp).clip(shape),
        contentAlignment = Alignment.Center) {
        // The fill and the press layer sit under the words (as on a chosen chip), so the press never dims the label.
        Box(Modifier.matchParentSize().then(if (chosen) Modifier.background(skin.accent) else Modifier.border(1.dp, skin.chipBorder, shape))
            .pressFill(interaction, if (chosen) skin.pressOnAccent else Scene.fill))
        Column(Modifier.clearAndSetSemantics {}.padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (chosen) Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(16.dp), tint = skin.onAccent)
                else if (dot != null) Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
                if (chosen || dot != null) Spacer(Modifier.width(8.dp))
                Text(title, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = if (chosen) skin.onAccent else Scene.textMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}

/**
 * One option of a short group (2.12's maintenance kinds; 2.13 the expense types too): a 46-dp stadium in a 56-dp target, a
 * radio button of the group (put the row in `selectableGroup()`). The chosen one is filled with the accent (the label in
 * onAccent, 4.93; the press darkens the fill under it), the others are outlined with the chip border. The swap is instant;
 * the press settles to .98.
 */
@Composable internal fun CarChoiceChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val skin = Car.skin
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(Modifier.pressable(interaction, .98f)
        .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 5.dp).heightIn(min = 46.dp).clip(shape), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().then(if (selected) Modifier.background(skin.accent) else Modifier.border(1.dp, skin.chipBorder, shape))
            .pressFill(interaction, if (selected) skin.pressOnAccent else Scene.fill))
        Text(label, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.labelLarge, color = if (selected) skin.onAccent else Scene.text, maxLines = 1)
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
 * cross-fades as [DigitChange] does. A [muted] value (2.13: 없음, a count of zero) is textMuted in Medium, the way a zero
 * amount is ([moneyText]), so an empty figure never reads as loud as a real one. One node for TalkBack.
 */
@Composable internal fun CarMiniStat(icon: Painter, label: String, value: String, modifier: Modifier = Modifier, live: Boolean = false, muted: Boolean = false) {
    Column(modifier.semantics(mergeDescendants = true) {}) {
        MiniStatLabel(icon, label)
        if (live) MiniStatValue(value, muted)
        else {
            val reduce = LocalReduceMotion.current
            val lift = with(LocalDensity.current) { 6.dp.roundToPx() }
            AnimatedContent(targetState = value, label = "mini-stat", transitionSpec = {
                val ease = FastOutSlowInEasing
                val swap = if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                else (fadeIn(tween(160, easing = ease)) + slideInVertically(tween(160, easing = ease)) { lift }) togetherWith
                    (fadeOut(tween(120, easing = ease)) + slideOutVertically(tween(160, easing = ease)) { -lift })
                swap using SizeTransform(clip = false) { _, _ -> digitSizeSpec(reduce) }
            }) { shown -> MiniStatValue(shown, muted) }
        }
    }
}

/**
 * A mini stat of money (2.13, the spec's §5: 등록 경비, 월 마감's 경비): the label row as above, then the amount in the one
 * money style at the mini stat's size, the digits titleSmall with tabular figures and 원 the same size in textMuted, stepping
 * down to 14 sp with the digits. Zero is "0원" muted and Medium, no amount ([won] null) is "—", heard as 금액 없음. Money
 * never animates, so it lands in place. The card's ₩ mark belongs in [icon], once per card. One node for TalkBack: the
 * label and the amount ("등록 경비, 4,500원").
 */
@Composable internal fun CarMiniStat(icon: Painter, label: String, won: Long?, modifier: Modifier = Modifier) {
    val money = moneyText(won, "—")
    Column(modifier.clearAndSetSemantics { contentDescription = "$label, ${money.spoken}" }) {
        MiniStatLabel(icon, label)
        AmountText(money, MaterialTheme.typography.titleSmall, unitSize = TextUnit.Unspecified, tone = MoneyTone.NEUTRAL,
            autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 19.sp, stepSize = .5.sp), alignEnd = false)
    }
}

/** A mini stat's label row: the 18-dp icon (decorative) and the label in labelMedium textSecondary, then 4 dp to the value. */
@Composable private fun MiniStatLabel(icon: Painter, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = Car.skin.icon)
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    Spacer(Modifier.height(4.dp))
}

/** A mini stat's figure: titleSmall, tabular, one line, stepping down to 14 sp instead of ellipsising; [muted] in textMuted Medium. */
@Composable private fun MiniStatValue(value: String, muted: Boolean = false) {
    val base = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum")
    val style = if (muted) base.copy(fontWeight = FontWeight.Medium) else base
    val ink = if (muted) Scene.textMuted else Scene.text
    BasicText(value, style = style, color = { ink }, maxLines = 1, softWrap = false,
        autoSize = TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = 19.sp, stepSize = .5.sp))
}

/**
 * A step's disc (2.12, shared since 2.13: 월 마감's steps, 홈's setup and incident steps, 휴대폰 연결's rows): done, the
 * scene's positive with a check in the scheme's text for it (6.21 / 7.44); otherwise the icon disc with the [number] in text,
 * a quieter number while the step is [idle]. [size] is 32 dp, 28 where the discs stack in 32-dp rows and would touch.
 * Decorative: the step's own node says the number.
 */
@Composable internal fun CarStepDisc(number: Int, done: Boolean, idle: Boolean = false, size: Dp = 32.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(if (done) Scene.positive else Car.skin.disc), contentAlignment = Alignment.Center) {
        Text(if (done) "✓" else number.toString(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            color = when { done -> MaterialTheme.colorScheme.onTertiary; idle -> Scene.textSecondary; else -> Scene.text },
            modifier = Modifier.clearAndSetSemantics {})
    }
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

// ───────────────────────── dialogs ─────────────────────────

/**
 * Makes the dialog window it is composed in fade in and out (spec C: a car dialog fades only) instead of the platform's
 * fade-and-slide: [R.style.CarDialogFade], 160 / 120 ms, and no window animation at all under reduced motion. Call it first
 * in a `Dialog`'s content: that content first composes before the window's first layout, so the enter fade applies.
 */
@Composable internal fun CarDialogFade() {
    val reduce = LocalReduceMotion.current
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    DisposableEffect(window, reduce) {
        window?.setWindowAnimations(if (reduce) 0 else R.style.CarDialogFade)
        onDispose {}
    }
}

/**
 * The car's dialog window and card (2.13, the spec's §1.1), for a dialog that lays out its own content (the driver picker):
 * a platform-width-free `Dialog` that fades ([CarDialogFade]), and in it the opaque [CarSkin.card] with a 1-dp card hairline,
 * corner 28, padding 28, at most [maxWidth] wide. Material's AlertDialog sat on surfaceContainerHigh, where the accent ink
 * falls to 4.26 by day and an accent button's edge to 3.05 by night, and its window slid. The pane is named [title] for
 * TalkBack; the [content] draws the title itself (a [CarSectionTitle]). The content wraps the card, so Back and a tap beside
 * the card dismiss ([onDismiss]).
 */
@Composable internal fun CarDialogFrame(title: String, onDismiss: () -> Unit, maxWidth: Dp = 600.dp, content: @Composable ColumnScope.() -> Unit) {
    val skin = Car.skin
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CarDialogFade()
        Box(Modifier.padding(24.dp)) {
            Surface(Modifier.widthIn(max = maxWidth).fillMaxWidth().semantics { paneTitle = title }, shape = RoundedCornerShape(28.dp),
                color = skin.card, contentColor = Scene.text, border = BorderStroke(1.dp, skin.cardBorder)) {
                Column(Modifier.padding(28.dp), content = content)
            }
        }
    }
}

/**
 * A car dialog (2.13): in a [CarDialogFrame], the title row ([CarSectionTitle] with an optional [icon], the dialog's heading),
 * 12 dp, the [lines] in bodySmall textSecondary (8.07 / 9.88), one sentence each ([dialogLines]) and one node for TalkBack
 * ([spoken] replaces their words where the long form says more), then the optional [body], 24 dp, and the [buttons]. A line is
 * written to fit one line at 600 dp (DIALOG_LINE_UNITS, 30) but may take two, so a narrow window or a large font never cuts a
 * warning. The buttons are equal parts of the row, 56 dp tall, in the order given, so the way on, given last, is at the right;
 * under 400 dp of content or above a 1.05× font they stack full width in the same order ([DialogButtons]). Acknowledge:
 * `PrimaryAction("확인")` alone. Decide: [QuietAction] then [PrimaryAction]. Destroy: `QuietAction("취소")` then
 * `QuietAction("삭제", color = Scene.negative)`, never the accent. Read only: `QuietAction("닫기")`.
 */
@Composable internal fun CarDialog(title: String, onDismiss: () -> Unit, icon: Painter? = null, lines: List<String> = emptyList(),
    spoken: String? = null, body: (@Composable ColumnScope.() -> Unit)? = null, buttons: @Composable RowScope.() -> Unit) {
    CarDialogFrame(title, onDismiss) {
        CarSectionTitle(title, icon = icon)
        if (lines.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { spoken?.let { contentDescription = it } },
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary) }
            }
        }
        if (body != null) {
            Spacer(Modifier.height(12.dp))
            body()
        }
        Spacer(Modifier.height(24.dp))
        DialogButtons(buttons)
    }
}

/**
 * A message as a dialog's lines (pure, unit-tested): one sentence per line, without the final period, the car's one-line
 * rule. It splits after a period, ? or ! that is followed by a space or a line break, and at a line break, then drops one
 * trailing period of each line; a period inside a number ("1.5MB") stays. Blank → no lines. The repository's messages stay
 * as they are written and are drawn through this.
 */
internal fun dialogLines(text: String): List<String> =
    text.split('\n').flatMap { it.trim().split(Regex("(?<=[.?!])\\s+")) }
        .map { it.trim().removeSuffix(".").trim() }.filter { it.isNotEmpty() }

/**
 * The scope [CarDialog]'s buttons are written in. [DialogButtons] sizes every button itself (equal parts, or full width when
 * stacked), so what a caller asks of a Row here, a weight or an alignment, changes nothing and the buttons stay equal.
 */
private object DialogButtonScope : RowScope {
    override fun Modifier.weight(weight: Float, fill: Boolean): Modifier = this
    override fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
    override fun Modifier.alignBy(alignmentLine: HorizontalAlignmentLine): Modifier = this
    override fun Modifier.alignByBaseline(): Modifier = this
    override fun Modifier.alignBy(alignmentLineBlock: (Measured) -> Int): Modifier = this
}

/** Under this much width of a dialog's content (or above a 1.05× font) its buttons stack. */
private val DIALOG_STACK_UNDER = 400.dp

/**
 * A dialog's buttons: equal parts of the width 12 dp apart, each at least 56 dp tall, in the order given (the last at the
 * right, mirrored in RTL); stacked full width, 12 dp apart and in the same order, under [DIALOG_STACK_UNDER] or above a 1.05×
 * font, so a label never wraps or ellipsises in a narrow window. TalkBack reads them in that order.
 */
@Composable private fun DialogButtons(buttons: @Composable RowScope.() -> Unit) {
    val largeFont = LocalDensity.current.fontScale > 1.05f
    Layout(content = { DialogButtonScope.buttons() }, modifier = Modifier.fillMaxWidth()) { measurables, constraints ->
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else 600.dp.roundToPx()
        if (measurables.isEmpty()) return@Layout layout(width, 0) {}
        val gap = 12.dp.roundToPx()
        val minHeight = 56.dp.roundToPx()
        if (largeFont || width < DIALOG_STACK_UNDER.roundToPx()) {
            val placeables = measurables.map { it.measure(Constraints(minWidth = width, maxWidth = width, minHeight = minHeight)) }
            layout(width, placeables.sumOf { it.height } + gap * (placeables.size - 1)) {
                var y = 0
                placeables.forEach { it.placeRelative(0, y); y += it.height + gap }
            }
        } else {
            val each = ((width - gap * (measurables.size - 1)) / measurables.size).coerceAtLeast(0)
            val placeables = measurables.map { it.measure(Constraints(minWidth = each, maxWidth = each, minHeight = minHeight)) }
            val height = placeables.maxOf { it.height }
            layout(width, height) {
                placeables.forEachIndexed { index, placeable -> placeable.placeRelative(index * (each + gap), (height - placeable.height) / 2) }
            }
        }
    }
}
