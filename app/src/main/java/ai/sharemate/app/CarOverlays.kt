package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/*
 * What the car lays over its pages besides its dialogs (2.13, docs/design/DESIGN_2_13_SPEC.md §8.1): the follow-up bar in the
 * car's skin and the answer a classification gives on it. The phone keeps [FollowUpBar] as it is.
 */

/**
 * The scene the follow-up bar wears (pure, unit-tested): the other one, so the bar stands out from the page under it, a
 * navy card by day and a white one by night. Every pair inside it is one CarSkinTest already holds for that scene (the
 * message 16.00 / 17.38 on its card, the pill outline 3.69 / 3.47), and it reads no Material colour: the 2.12 bar took its
 * action from inversePrimary, which the night scheme never sets, so the undo was Material's default purple by night.
 */
internal fun followUpScene(isDay: Boolean): SceneColors = if (isDay) NightSceneColors else DaySceneColors

/**
 * The car bar's second action ([FollowUp.second]), already bound to the shell's act: the bar dismisses itself, then runs it.
 */
internal class FollowUpSecond(val label: String, val run: () -> Unit)

/**
 * What [showFollowUp] hands Material's snackbar host on the car: the message and the undo as a snackbar carries them, and the
 * [second] action beside it. It stays long while it offers either, as the 2.12 bar did with its one action.
 */
internal class FollowUpVisuals(override val message: String, override val actionLabel: String?, val second: FollowUpSecond?) : SnackbarVisuals {
    override val withDismissAction: Boolean get() = false
    override val duration: SnackbarDuration get() = if (actionLabel != null || second != null) SnackbarDuration.Long else SnackbarDuration.Short
}

/**
 * The width of [text] in the copy rules' units (pure): a Hangul syllable 1, whitespace .25, anything else .55, the estimate
 * the copy tests hold every car line to (a syllable at bodyMedium is about 14–15 dp).
 */
internal fun copyUnits(text: String): Double = text.sumOf { ch ->
    when {
        ch in '가'..'힣' -> 1.0
        ch.isWhitespace() -> .25
        else -> .55
    }
}

/**
 * Whether [message] fits the follow-up bar's one line at FULL and the normal font beside [actions] pills (pure): 32 units
 * beside one (the widest pill, 회사 비용 아님, leaves about 555 dp of the 760-dp bar), 24 beside two.
 */
internal fun followUpFits(message: String, actions: Int): Boolean = copyUnits(message) <= if (actions >= 2) 24.0 else 32.0

/**
 * The bar's answer to a purpose the driver set (pure, unit-tested): always 실행 취소 ([undo]), so a wrong tap is one tap from
 * being taken back; with [others] unclassified trips left that day, also "3건도 같게" ([sameForOthers]) beside it, drawn
 * first, the undo last. In 2.12 the bulk offer took the undo's place.
 */
internal fun classifyFollowUp(purpose: TripPurpose, others: Int, undo: suspend () -> Unit, sameForOthers: suspend () -> Unit): FollowUp =
    if (others <= 0) FollowUp(OverlayCopy.classified(purpose), OverlayCopy.UNDO, perform = undo)
    else FollowUp(OverlayCopy.classifiedWithOthers(purpose, others), OverlayCopy.UNDO,
        second = FollowUpAction(OverlayCopy.sameForOthers(others), sameForOthers), perform = undo)

/** The bar's answer to "3건도 같게" (pure): "3건을 일반 업무로 기록했어요", with 실행 취소 ([undo]) for the [count] trips it set. */
internal fun classifyManyFollowUp(purpose: TripPurpose, count: Int, undo: suspend () -> Unit): FollowUp =
    FollowUp(OverlayCopy.classifiedMany(purpose, count), OverlayCopy.UNDO, perform = undo)

/**
 * The car's follow-up bar (2.13, in place of [FollowUpBar], which the phone keeps): the other scene's card
 * ([followUpScene]) with its hairline, corner 32, at least 64 dp tall; the message in bodyMedium, one line in the FULL window
 * at the normal font and two elsewhere; then its actions as [CarPillButton]s, the second ([FollowUpVisuals.second]) first and
 * the undo last, at the bar's end. A polite live region, so TalkBack reads it without a touch; it slides in and out on the
 * bar's 120-ms clock ([ShellFollowUpBar]).
 */
@Composable internal fun CarFollowUpBar(data: SnackbarData) {
    val second = (data.visuals as? FollowUpVisuals)?.second
    val oneLine = LocalLayout.current.full && LocalDensity.current.fontScale <= 1.05f
    CompositionLocalProvider(LocalScene provides followUpScene(Scene.isDay)) {
        val skin = Car.skin
        Surface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, shape = RoundedCornerShape(32.dp), color = skin.card, contentColor = Scene.text,
            border = BorderStroke(1.dp, skin.cardBorder)) {
            Row(Modifier.heightIn(min = 64.dp).padding(start = 24.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(data.visuals.message, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = Scene.text,
                    maxLines = if (oneLine) 1 else 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    second?.let { action -> CarPillButton(action.label, onClick = { data.dismiss(); action.run() }) }
                    data.visuals.actionLabel?.let { label -> CarPillButton(label, onClick = data::performAction) }
                }
            }
        }
    }
}
