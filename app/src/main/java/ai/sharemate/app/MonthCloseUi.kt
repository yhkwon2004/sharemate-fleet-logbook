package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/*
 * Month close on the car screen. The deadline is the real calendar month end, the counts are the audit numbers
 * (monthStatus in FleetCare.kt), and the close itself is a statement that the month is complete: trips in a closed month
 * can still be corrected, but the correction is marked. Nothing here invents urgency; when there is nothing left it says so.
 */

internal const val MONTH_CLOSE_BANNER_DAY = 25
internal const val SEALED_EDIT_MARK = "마감된 달의 기록 수정"

// ───────────────────────── pure helpers ─────────────────────────

/** The month the manager should be closing: last month while it still has open work, else this month from the 25th on. */
internal fun monthCloseTarget(data: TripLogData, today: LocalDate, zone: ZoneId): MonthStatus? {
    val previous = monthStatus(data, YearMonth.from(today).minusMonths(1), zone)
    if (previous.trips > 0 && previous.closed == null) return previous
    if (today.dayOfMonth < MONTH_CLOSE_BANNER_DAY) return null
    return monthStatus(data, YearMonth.from(today), zone).takeIf { it.trips > 0 && it.closed == null }
}

/**
 * The month 월간·Excel opens on when no button hands it one: the month the home close card, the banner and the tab badge point
 * at ([monthCloseTarget]), else this month. The home's and the phone's 정보 내보내기 name and open the same month.
 */
internal fun reportLandingMonth(data: TripLogData, today: LocalDate, zone: ZoneId): YearMonth =
    monthCloseTarget(data, today, zone)?.month ?: YearMonth.from(today)

internal fun monthLabel(month: YearMonth, today: LocalDate): String =
    if (month.year == today.year) "${month.monthValue}월" else "${month.year}년 ${month.monthValue}월"

/** Banner line: the real days to month end and the real count, or the fact that only saving and closing are left. */
internal fun monthCloseBannerText(status: MonthStatus, today: LocalDate): String {
    val name = monthLabel(status.month, today)
    val daysLeft = ChronoUnit.DAYS.between(today, status.month.atEndOfMonth())
    return when {
        status.issues == 0 -> "$name 정리 끝 · Excel 저장과 마감만 남았어요"
        daysLeft < 0 -> "$name 운행일지 마감 전 · 남은 확인 ${status.issues}건"
        daysLeft == 0L -> "$name 마감일 오늘 · 남은 확인 ${status.issues}건"
        else -> "$name 마감까지 ${daysLeft}일 · 남은 확인 ${status.issues}건"
    }
}

/**
 * One rule for every Excel 저장 on the month screens, the 월간·Excel column's button and step ③ of the close card alike: a range
 * without trips has nothing to save, so neither offers the action.
 */
internal fun excelSaveAvailable(trips: Int): Boolean = trips > 0

/**
 * One row of the close stepper. [optional] steps (Excel) never claim completion the app cannot see; [idle] steps have nothing
 * to act on yet because the month has no trips: no check, no action, a neutral disc.
 */
internal data class CloseStep(val label: String, val detail: String, val done: Boolean, val action: String? = null, val optional: Boolean = false,
    val idle: Boolean = false)

internal fun monthCloseSteps(status: MonthStatus): List<CloseStep> {
    val closed = status.closed
    // A month without trips has nothing confirmed yet: ① and ② wait instead of showing done, ③ offers no save (the column's
    // Excel 저장 follows the same rule) and ④ says why it stays shut. A card question that is really pending still shows in ②.
    val noTrips = status.trips == 0
    return listOf(
        if (noTrips) CloseStep("목적·사용자 확인", "운행이 없어요", false, idle = true)
        else CloseStep("목적·사용자 확인", if (status.unfinished == 0) "모두 확인됨" else "${status.unfinished}건 남음", status.unfinished == 0, "정리하기".takeIf { status.unfinished > 0 }),
        if (noTrips && status.reviews == 0) CloseStep("카드 확인", "확인할 결제 없음", false, idle = true)
        else CloseStep("카드 확인", if (status.reviews == 0) "확인할 결제 없음" else "${status.reviews}건 남음", status.reviews == 0, "확인하기".takeIf { status.reviews > 0 }),
        if (!excelSaveAvailable(status.trips)) CloseStep("Excel 저장", "저장할 운행이 없어요", false, optional = true, idle = true)
        else CloseStep("Excel 저장", if (closed != null) "마감 후에도 다시 저장할 수 있어요" else "마감 전 저장을 권해요", false, "Excel 저장", optional = true),
        CloseStep("마감", when { closed != null -> "마감됨 · ${closedStamp(closed.closedAt)}"; status.ready -> "지금 마감할 수 있어요"; noTrips -> "운행이 없어요"; else -> "①·②를 끝내면 열려요" },
            closed != null, idle = noTrips && closed == null),
    )
}

/**
 * The close card's headline. A month without trips is still waiting for them only while it runs; a past month says it has no
 * record, so the card never promises trips to a month that is over (월간·Excel can show any month).
 */
internal fun monthCloseHeadline(status: MonthStatus, today: LocalDate): String {
    val name = monthLabel(status.month, today)
    val percent = status.percent
    return when {
        status.closed != null -> "$name 운행일지 마감됨"
        percent == null && status.month < YearMonth.from(today) -> "$name 운행 기록이 없어요"
        percent == null -> "$name 운행이 아직 없어요"
        else -> "$name 운행일지 ${percent}% 완성"
    }
}

/** The line under the close button: what a close means once it is open, else what it waits for; a past month without trips waits for nothing. */
internal fun monthCloseFootnote(status: MonthStatus, today: LocalDate): String = when {
    status.ready -> "마감은 이 달이 완성됐다는 표시예요. 마감 후에도 운행은 고칠 수 있고, 고친 기록엔 ‘마감 후 수정’이 붙어요."
    status.trips == 0 && status.month < YearMonth.from(today) -> "운행이 없는 달은 마감하지 않아도 돼요."
    status.trips == 0 -> "운행이 기록되면 여기서 마감할 수 있어요."
    else -> "남은 확인 ${status.issues}건을 끝내면 마감 버튼이 열려요."
}

/**
 * The one note of 월간·Excel's close card (2.12, pure, unit-tested), as a line and what an ⓘ row folds under it: with
 * nothing to fold it is a plain line. A closed month counts its edits after the close; a closed month without edits, and a
 * month ready to close, say once that a close locks nothing. A month without trips has no steps and no close button since
 * 2.13 ([ReportCloseCard]), so its note says it all: a past one needs no close, this one waits for its first trip
 * ([ReportCopy.CLOSE_WAITING]). Otherwise null: step ④ already says what the close waits for, and TalkBack hears it on the
 * button ([monthCloseFootnote]).
 */
internal fun monthCloseNote(status: MonthStatus, today: LocalDate, edited: Int): Pair<String, List<String>>? = when {
    status.closed != null && edited > 0 -> "마감 후 수정 ${edited}건 · 운행기록에 표시돼요" to emptyList()
    status.closed != null || status.ready -> ReportCopy.CLOSE_NOTE to listOf(ReportCopy.CLOSE_NOTE_MORE)
    status.trips == 0 && status.month < YearMonth.from(today) -> ReportCopy.CLOSE_NOTHING to emptyList()
    status.trips == 0 -> ReportCopy.CLOSE_WAITING to emptyList()
    else -> null
}

/** The least effective width (dp ÷ font scale) at which the close card sets its four steps side by side. */
private val CLOSE_STEPS_ACROSS_MIN = 980.dp

/**
 * Whether the close card sets its four steps across (pure, unit-tested): where its [content] width keeps 980 dp at the font
 * scale (a page-wide card at FULL, 1064 dp, or beside no column, 1056), each step a column of about 250 dp. Narrower, or at a
 * large font, they stack as rows.
 */
internal fun closeStepsAcross(content: Dp, fontScale: Float): Boolean = content / fontScale.coerceAtLeast(1f) >= CLOSE_STEPS_ACROSS_MIN

internal fun closedStamp(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(at).atZone(zone).let { "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

internal fun closedDay(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(at).atZone(zone).let { "${it.monthValue}/${it.dayOfMonth}" }

/** True when the trip was changed after its month was closed; the repository writes this history line itself. */
internal fun editedAfterClose(trip: Trip): Boolean = trip.history.any { it.contains(SEALED_EDIT_MARK) }

internal fun editedAfterCloseCount(data: TripLogData, month: YearMonth, zone: ZoneId): Int =
    tripsIn(data.trips.filter { it.demo == data.profile.demoMode }, month.atDay(1), month.atEndOfMonth(), zone).count(::editedAfterClose)

/** The close record covering [from]..[to] when both fall in one closed month, for preview headers. */
internal fun closedMonthFor(data: TripLogData, from: LocalDate, to: LocalDate): MonthClose? {
    val month = YearMonth.from(from)
    if (YearMonth.from(to) != month) return null
    return data.closedMonths.firstOrNull { it.month == monthKey(month) && it.demo == data.profile.demoMode }
}

// ───────────────────────── chips ─────────────────────────

/** Small neutral chip for a trip row or a header: "마감 후 수정", "마감됨 · 11/1". Never a lock; the record stays editable. */
@Composable internal fun MonthCloseChip(text: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = Scene.fill, contentColor = Scene.textSecondary, border = BorderStroke(1.dp, Scene.divider)) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1)
    }
}

@Composable internal fun ClosedChip(close: MonthClose, modifier: Modifier = Modifier) = MonthCloseChip("마감됨 · ${closedDay(close.closedAt)}", modifier)

// ───────────────────────── 월간·Excel 마감 카드 (2.12) ─────────────────────────

/**
 * 월간·Excel's month card (2.12) in the car's one-line look; the home keeps [MonthClosePanel]. The title is 월 마감 with the
 * month's trips as a tag (2.13: a count is a tag, never part of a title) and, once it is closed, a 마감됨 tag; then the
 * headline and the completion bar (keyed for this card alone, so the home card's sweep never carries over). A closed month
 * shows its three figures (경비 in the one money style) and one row: the note, 마감 해제 as a pill and Excel 저장 (2.12 had two
 * 520-dp outlined buttons there, v6). A month without trips (2.13, v5) shows no steps and no close button that could never
 * enable: the headline, then the note of what the close waits for ([monthCloseNote]) beside 이전 달 보기 where [onPrevious] is
 * given. Any other open month shows the four steps ([monthCloseSteps]): across where the card has the room
 * ([closeStepsAcross]), as rows otherwise; under them the note and the close button, which is solid only when the month is
 * ready and tells TalkBack what it waits for while it is not ([monthCloseFootnote]). One note at most: what the old
 * paragraphs added sits behind its ⓘ. A row of note and buttons stacks under 560 dp of card ([CloseNoteRow]).
 */
@Composable internal fun ReportCloseCard(status: MonthStatus, onIssues: () -> Unit, onReviews: () -> Unit, onExport: () -> Unit, exporting: Boolean,
    act: Act, repository: TripLogRepository, modifier: Modifier = Modifier, editedAfterClose: Int = 0, today: LocalDate = LocalDate.now(zone),
    onPrevious: (() -> Unit)? = null) {
    val followUp = LocalFollowUp.current
    val name = monthLabel(status.month, today)
    val key = monthKey(status.month)
    val closed = status.closed
    val percent = status.percent
    // The bar sweeps from the previous value to the new one (spec C); TalkBack hears the real figure.
    val progress = sweepProgress((percent ?: 0) / 100f, key = "report-close-bar")
    val note = monthCloseNote(status, today, editedAfterClose)
    // Nothing to close yet: no trips and no card question waiting in the month (one still waiting keeps its step and its way).
    val empty = closed == null && status.trips == 0 && status.reviews == 0
    CarCard(modifier, title = {
        CarSectionTitle("월 마감", icon = rememberVectorPainter(Icons.Outlined.CheckCircle), trailing = if (status.trips > 0 || closed != null) { {
            if (status.trips > 0) CarTag("${status.trips}건", description = "운행 ${status.trips}건")
            if (closed != null) CarTag("마감됨 · ${closedDay(closed.closedAt)}", tone = PillTone.POSITIVE)
        } } else null)
    }) {
        Text(monthCloseHeadline(status, today), style = MaterialTheme.typography.headlineMedium, color = Scene.text, modifier = Modifier.semantics { heading() })
        if (percent != null) LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(10.dp)
            .semantics { contentDescription = "완성률 ${percent}퍼센트" }, color = Scene.positive, trackColor = Scene.ringTrack, drawStopIndicator = {})
        if (closed != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarMiniStat(painterResource(R.drawable.ic_vehicle), "운행", "${closed.trips}건", Modifier.weight(1f))
                CarMiniStat(painterResource(R.drawable.ic_gauge), "총주행", kmText(closed.totalKm), Modifier.weight(1f))
                CarMiniStat(painterResource(R.drawable.ic_won), "경비", won = closed.expenseWon, modifier = Modifier.weight(1f))
            }
            CloseNoteRow(note) {
                CarPillButton("마감 해제", {
                    act {
                        repository.reopenMonth(key)
                        followUp(FollowUp("$name 마감을 해제했어요", "다시 마감") { repository.closeMonth(key) })
                    }
                }, description = "$name 마감 해제")
                QuietAction(if (exporting) "저장 중…" else "Excel 저장", enabled = !exporting && excelSaveAvailable(status.trips), onClick = onExport)
            }
        } else if (empty) {
            CloseNoteRow(note) { onPrevious?.let { CarPillButton("이전 달 보기", it) } }
        } else {
            val steps = monthCloseSteps(status)
            val actions = listOf(onIssues, onReviews, onExport, null)
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (closeStepsAcross(maxWidth, fontScale)) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    steps.forEachIndexed { index, step -> StepCell(index + 1, step, exporting && index == 2, actions[index], Modifier.weight(1f)) }
                } else Column(Modifier.fillMaxWidth()) {
                    steps.forEachIndexed { index, step ->
                        // The rule starts where the text does: the 32-dp disc and its 12-dp gap.
                        if (index > 0) CarDivider(Modifier.padding(start = 44.dp))
                        StepLine(index + 1, step, exporting && index == 2, actions[index])
                    }
                }
            }
            val close: @Composable (Modifier) -> Unit = { place ->
                LoadingAction("$name 마감", "마감 중…", act, place.widthIn(min = 240.dp).semantics {
                    if (!status.ready) stateDescription = monthCloseFootnote(status, today)
                }, enabled = status.ready) {
                    repository.closeMonth(key)
                    followUp(FollowUp("${name}을 마감했어요", "마감 해제") { repository.reopenMonth(key) })
                }
            }
            // The note and the button share a row; a narrow card (under 560 dp, as the home's daily question) stacks them.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 560.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f).heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) { note?.let { CloseNote(it) } }
                    close(Modifier)
                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    note?.let { CloseNote(it) }
                    close(Modifier.fillMaxWidth())
                }
            }
            // A month without trips but with a card question still has nothing to close; the way on is the month before it.
            if (status.trips == 0) onPrevious?.let { CarPillButton("이전 달 보기", it) }
        }
    }
}

/** The close card's note ([monthCloseNote]): a plain line, or an ⓘ row when it folds a longer form. */
@Composable private fun CloseNote(note: Pair<String, List<String>>) {
    val (line, more) = note
    if (more.isEmpty()) CarLine(line) else CarInfoRow(line, more = more)
}

/**
 * The close card's last row for a closed or an empty month (2.13): the [note] takes the room and the [actions] sit at the
 * end, 12 dp apart, each at its own width; under 560 dp of card (the home's daily question) the note sits over them.
 */
@Composable private fun CloseNoteRow(note: Pair<String, List<String>>?, actions: @Composable RowScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 560.dp) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.weight(1f).heightIn(min = 56.dp), contentAlignment = Alignment.CenterStart) { note?.let { CloseNote(it) } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
        } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            note?.let { CloseNote(it) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = actions)
        }
    }
}

/** What TalkBack says of a step's state, as on the home card: 완료, 대기 (nothing to act on yet), 선택 (Excel) or 남음. */
private fun closeStepState(step: CloseStep): String = when { step.done -> "완료"; step.idle -> "대기"; step.optional -> "선택"; else -> "남음" }

/** What TalkBack reads for a step: its number, its label and its detail, in one node. */
private fun closeStepSpoken(number: Int, step: CloseStep): String = "${number}단계, ${step.label}, ${step.detail}"

/** A step's detail in the scene's negative while it still waits on the driver (an action that is not optional), else muted. */
@Composable private fun closeStepDetailColor(step: CloseStep): Color =
    if (!step.done && !step.optional && step.action != null) Scene.negative else Scene.textMuted

/**
 * One step of four across ([closeStepsAcross]): the disc beside the label, the detail under the label ([CarLine]) and the
 * step's action as a pill under that. The words are one node with the step's state; the pill keeps its own. The label starts
 * 12 dp after the disc (2.13, the 8-12-16-24 scale), where a [StepLine]'s does.
 */
@Composable private fun StepCell(number: Int, step: CloseStep, busy: Boolean, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    val action = step.action
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = closeStepSpoken(number, step); stateDescription = closeStepState(step) },
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CarStepDisc(number, step.done, step.idle)
                Spacer(Modifier.width(12.dp))
                Text(step.label, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Under the label, not the disc: 32 dp of disc and 12 of gap.
            CarLine(step.detail, Modifier.padding(start = 44.dp), color = closeStepDetailColor(step))
        }
        if (action != null && onAction != null) CarPillButton(if (busy) "저장 중…" else action, onAction, Modifier.padding(start = 44.dp), enabled = !busy)
    }
}

/** One step as a row ([CarRow]) where the four do not fit across: the disc as its lead, the action as a pill at its end. */
@Composable private fun StepLine(number: Int, step: CloseStep, busy: Boolean, onAction: (() -> Unit)?) {
    val action = step.action
    CarRow(step.label, line = step.detail, lead = { CarStepDisc(number, step.done, step.idle) }, lineColor = closeStepDetailColor(step),
        state = closeStepState(step), spoken = closeStepSpoken(number, step),
        trailing = if (action != null && onAction != null) { { CarPillButton(if (busy) "저장 중…" else action, onAction, enabled = !busy) } } else null)
}
