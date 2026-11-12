package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/*
 * What the parked home remembers of the driver's answers, and the small surfaces around its cards: the charge question's
 * wait for the card feed, the 방금 운행 card acknowledged or reopened as a purpose question ([ParkedStackAnswers]), the
 * 자동 반영 list where each card payment can be taken back, and the driver check once a day. Since 2.13 the cards themselves
 * are 홈's attention cards (HomeAttention.kt), in the order of [attentionQueue]. Nothing here is shown while the car is moving.
 */

// ───────────────────────── 한 장씩 보여 주는 할 일 ─────────────────────────

/** The one remembered answer to "do you want to type the amount now or wait for the card?", reset for each new stop. */
@Composable internal fun rememberChargePrompt(data: TripLogData): MutableState<ChargePrompt> =
    rememberSaveable(pendingChargeSession(data)?.endAt ?: 0L) { mutableStateOf(ChargePrompt.AUTO) }

/** Ids or day keys kept as one saveable string (the stack's answers, the shell's answered days): joined by commas, and split back. */
internal fun Set<String>.joined() = joinToString(",")
internal fun String.toIdSet() = split(',').filter { it.isNotBlank() }.toSet()

/**
 * 홈's own answers: the 방금 운행 cards already acknowledged ([dismissed], ids joined by commas) and the trip whose purpose is
 * being changed from its card ([reclassifyId]). The home holds them ([rememberParkedStackAnswers]), so they outlive a card
 * leaving the composition, and the queue ([attentionQueue]) reads them as [finishedId] and [reopenedId].
 */
@Stable
internal class ParkedStackAnswers(dismissed: String = "", reclassifyId: String? = null) {
    var dismissed by mutableStateOf(dismissed)
    var reclassifyId by mutableStateOf(reclassifyId)

    /** The trip whose 방금 운행 card is due ([finishedTripToShow]), unless the driver already acknowledged it. */
    fun finishedId(data: TripLogData, lastFinishedId: String?, now: Long): String? =
        finishedTripToShow(data, lastFinishedId, now, dismissed.toIdSet())?.id

    /**
     * The trip whose purpose question the 방금 운행 card reopened, while it is still the last one finished and not recording:
     * a newer trip retires it, and it stays past the card's two minutes until answered.
     */
    fun reopenedId(data: TripLogData, lastFinishedId: String?): String? =
        reclassifyId?.takeIf { it == lastFinishedId }?.takeIf { id -> data.trips.any { it.id == id && !it.open } }

    /** Acknowledges the finished-trip card of [tripId]; with [reclassify] the purpose question for that trip opens in its place. */
    fun answer(tripId: String, reclassify: Boolean = false) {
        dismissed = (dismissed.toIdSet() + tripId).joined()
        if (reclassify) reclassifyId = tripId
    }

    companion object {
        val Saver: Saver<ParkedStackAnswers, Any> = listSaver<ParkedStackAnswers, String>(save = { listOf(it.dismissed, it.reclassifyId.orEmpty()) },
            restore = { ParkedStackAnswers(it[0], it[1].ifEmpty { null }) })
    }
}

/** 홈's answers, kept across rotation and a return to the tab; hold them where the queue is built. */
@Composable internal fun rememberParkedStackAnswers(): ParkedStackAnswers = rememberSaveable(saver = ParkedStackAnswers.Saver) { ParkedStackAnswers() }

// ───────────────────────── 카드 자동 반영 묶음 ─────────────────────────

/**
 * The "자동 반영" filter of the log: this month's trips that carry a card-matched expense, each line with its own
 * "회사 비용 아님" and an undo that attaches the payment again. Nothing was hidden in a basket, so nothing sneaks in.
 */
@Composable internal fun MatchedTripList(data: TripLogData, now: Long, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val rows = matchedTripsThisMonth(data, zone, localDate(now))
    if (rows.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Muted("이달에 카드 결제가 자동으로 들어간 운행이 없어요.") }
        return
    }
    val followUp = LocalFollowUp.current
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(rows, key = { it.first.id }) { (trip, expenses) ->
            Column(Modifier.clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.surface)) {
                TripRow(trip, data, flagged = false, selected = false) { onOpen(trip.id) }
                expenses.forEach { expense ->
                    val chargeId = expense.cardChargeId ?: return@forEach
                    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
                            Text("${expense.type.label} ${formatWon(expense.amountWon)}", style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
                        }
                        Muted(expense.merchant ?: EditSource.CARD.label, Modifier.weight(1f))
                        QuietAction("회사 비용 아님", description = "${expense.merchant ?: expense.type.label} ${formatWon(expense.amountWon)} 회사 비용 아님") {
                            act {
                                repository.excludeCardCharge(chargeId)
                                followUp(FollowUp("${expense.merchant ?: expense.type.label} ${formatWon(expense.amountWon)}을 회사 비용에서 뺐어요", "실행 취소") {
                                    repository.confirmCardCharge(chargeId, trip.id)
                                })
                            }
                        }
                    }
                }
            }
        }
    }
}

// ───────────────────────── 오늘의 운전자 ─────────────────────────

/** The driver row folds away in 200 ms once it is answered (spec C); nothing is composed once it has gone. */
private const val MOTION_FOLD_MS = 200

/**
 * One question a day under the header, in a window without the 지금 column (where [DriverNowCard] asks it): the avatar and
 * "오늘도 김서연 님이 운전하나요?" ([driverQuestion]: the person, not the department) with the current driver as the default.
 * "네" writes nothing and remembers today; "다른 직원" opens the picker. Both count as an answer, so the row does not
 * come back for the newly chosen driver. See [driverConfirmDue] for when the question is worth asking at all.
 * [confirmed] holds the day keys already answered; the shell owns it so the home hero and the other tabs ask once a day between them.
 * The row folds itself in and out; [modifier] applies inside the fold, so a gap the caller pads under the row folds with it.
 * 2.12: it wears the car's card (opaque, the hairline, corner 20), the avatar sits on the disc, and the answers are the
 * accent 네 and the outlined 다른 직원; under 560 dp the buttons still drop below the question.
 */
@Composable internal fun DriverConfirmRow(state: TripLogState, now: Long, openDriver: (String?) -> Unit, confirmed: String, onConfirmed: (String) -> Unit,
    modifier: Modifier = Modifier) {
    val dates = confirmed.toIdSet()
    val due = driverConfirmDue(state.data, now, zone, dates)
    // The answered row shrinks and fades showing the question it just asked, then leaves the composition altogether.
    var shown by remember { mutableStateOf(due) }
    if (due != null) shown = due
    val visible = remember { MutableTransitionState(due != null) }
    visible.targetState = due != null
    val driver = shown
    if (driver == null || (!visible.currentState && !visible.targetState)) return
    val reduce = LocalReduceMotion.current
    val ease = FastOutSlowInEasing
    AnimatedVisibility(visibleState = visible,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(MOTION_FOLD_MS, easing = ease)) + expandVertically(tween(MOTION_FOLD_MS, easing = ease)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(MOTION_FOLD_MS, easing = ease)) + shrinkVertically(tween(MOTION_FOLD_MS, easing = ease)),
        label = "driver-confirm") {
        val answer = { onConfirmed((dates + dateKey(now, zone)).joined()) }
        val skin = Car.skin
        Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = skin.card, contentColor = Scene.text, border = BorderStroke(1.dp, skin.cardBorder)) {
            BoxWithConstraints(Modifier.padding(12.dp)) {
                val question: @Composable (Modifier) -> Unit = { place ->
                    Row(place, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        DriverAvatar(driver, 40.dp)
                        Text(driverQuestion(driver), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).semantics { heading() })
                    }
                }
                val buttons: @Composable () -> Unit = {
                    PrimaryAction("네", Modifier.heightIn(min = 56.dp), onClick = answer)
                    QuietAction("다른 직원", Modifier.heightIn(min = 56.dp)) { answer(); openDriver(null) }
                }
                if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    question(Modifier.padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { buttons() }
                } else Row(Modifier.heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    question(Modifier.weight(1f))
                    buttons()
                }
            }
        }
    }
}
