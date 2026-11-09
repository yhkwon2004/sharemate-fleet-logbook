package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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

/** The month-close card keeps to this width in the 월간·Excel column (spec B). */
internal val MONTH_CLOSE_MAX_WIDTH = 760.dp

// ───────────────────────── pure helpers ─────────────────────────

/** The month the manager should be closing: last month while it still has open work, else this month from the 25th on. */
internal fun monthCloseTarget(data: TripLogData, today: LocalDate, zone: ZoneId): MonthStatus? {
    val previous = monthStatus(data, YearMonth.from(today).minusMonths(1), zone)
    if (previous.trips > 0 && previous.closed == null) return previous
    if (today.dayOfMonth < MONTH_CLOSE_BANNER_DAY) return null
    return monthStatus(data, YearMonth.from(today), zone).takeIf { it.trips > 0 && it.closed == null }
}

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

// ───────────────────────── 홈 배너 ─────────────────────────

/** One glass line under the status hero with the real deadline; × hides it for this month only. */
@Composable internal fun MonthCloseBanner(status: MonthStatus, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier, today: LocalDate = LocalDate.now(zone)) {
    val text = monthCloseBannerText(status, today)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Scene.glass, contentColor = Scene.text, border = Scene.edge) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 20.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text, Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.bodyLarge, color = Scene.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            TextButton(onClick = onOpen, modifier = Modifier.heightIn(min = 56.dp)) { Text(if (status.issues == 0) "마감하기" else "정리하기") }
            IconButton(onClick = onDismiss, modifier = Modifier.size(56.dp)) {
                Text("×", style = MaterialTheme.typography.headlineSmall, color = Scene.textSecondary, modifier = Modifier.clearAndSetSemantics { contentDescription = "이달 마감 안내 닫기" })
            }
        }
    }
}

/** [MonthCloseBanner] with its own dismissed month (survives rotation, returns next month). Renders nothing when there is no target. */
@Composable internal fun MonthCloseBannerHost(data: TripLogData, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val today = localDate(now)
    val status = monthCloseTarget(data, today, zone) ?: return
    var dismissed by rememberSaveable { mutableStateOf("") }
    if (dismissed == monthKey(status.month)) return
    MonthCloseBanner(status, onOpen, { dismissed = monthKey(status.month) }, modifier, today)
}

// ───────────────────────── 월간 패널 ─────────────────────────

/**
 * The stepper of what is left: ① purposes and drivers, ② card questions, ③ Excel, ④ close. Each open step is a jump to
 * the place that finishes it; ④ opens only when the month is really ready. Closed shows the sign-off and 마감 해제.
 * In the 월간·Excel column the panel keeps to 760 dp at the start; the home card ([compact]) fills its column.
 */
@Composable internal fun MonthClosePanel(status: MonthStatus, onIssues: () -> Unit, onReviews: () -> Unit, onExport: () -> Unit, exporting: Boolean,
    act: Act, repository: TripLogRepository, modifier: Modifier = Modifier, compact: Boolean = false, editedAfterClose: Int = 0, today: LocalDate = LocalDate.now(zone)) {
    val followUp = LocalFollowUp.current
    val name = monthLabel(status.month, today)
    val key = monthKey(status.month)
    val closed = status.closed
    val percent = status.percent
    // The completion bar sweeps from the previous value to the new one (spec C); TalkBack hears the real figure.
    val progress = sweepProgress((percent ?: 0) / 100f)
    Panel(if (compact) modifier else modifier.widthIn(max = MONTH_CLOSE_MAX_WIDTH), padding = if (compact) 22.dp else LocalLayout.current.panelPadding) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow(if (compact) "월 마감 · ${status.trips}건" else "MONTH CLOSE · ${status.trips}건", Scene.brand)
            Spacer(Modifier.weight(1f))
            closed?.let { ClosedChip(it) }
        }
        Text(when { closed != null -> "$name 운행일지 마감됨"; percent == null -> "$name 운행이 아직 없어요"; else -> "$name 운행일지 ${percent}% 완성" },
            style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        if (percent != null) LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(10.dp)
            .semantics { contentDescription = "완성률 ${percent}퍼센트" }, color = Scene.positive, trackColor = Scene.ringTrack, drawStopIndicator = {})
        if (closed != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Stat("운행", "${closed.trips}건", Modifier.weight(1f))
                Stat("총주행", kmText(closed.totalKm), Modifier.weight(1f))
                Stat("경비", formatWon(closed.expenseWon), Modifier.weight(1f))
            }
            Muted(if (editedAfterClose > 0) "마감 후 수정된 운행 ${editedAfterClose}건 · 운행기록에 ‘마감 후 수정’으로 표시돼요" else "마감 후 고친 운행은 ‘마감 후 수정’으로 표시돼요. 기록을 잠그지는 않아요.")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietAction("마감 해제", Modifier.weight(1f)) {
                    act {
                        repository.reopenMonth(key)
                        followUp(FollowUp("$name 마감을 해제했어요", "다시 마감") { repository.closeMonth(key) })
                    }
                }
                QuietAction(if (exporting) "저장 중…" else "Excel 저장", Modifier.weight(1f), enabled = !exporting && excelSaveAvailable(status.trips), onClick = onExport)
            }
        } else {
            val steps = monthCloseSteps(status)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                steps.forEachIndexed { index, step ->
                    StepRow(index + 1, step, exporting && index == 2, when (index) { 0 -> onIssues; 1 -> onReviews; 2 -> onExport; else -> null })
                    if (index < steps.lastIndex) HorizontalDivider(color = Scene.divider, modifier = Modifier.padding(start = 46.dp))
                }
            }
            LoadingAction("$name 마감", "마감 중…", act, Modifier.fillMaxWidth(), enabled = status.ready) {
                repository.closeMonth(key)
                followUp(FollowUp("${name}을 마감했어요", "마감 해제") { repository.reopenMonth(key) })
            }
            Muted(if (status.ready) "마감은 이 달이 완성됐다는 표시예요. 마감 후에도 운행은 고칠 수 있고, 고친 기록엔 ‘마감 후 수정’이 붙어요."
                else if (status.trips == 0) "운행이 기록되면 여기서 마감할 수 있어요." else "남은 확인 ${status.issues}건을 끝내면 마감 버튼이 열려요.")
        }
    }
}

/** The home task-stack version: the same stepper in a card, used when a month passed without being closed. */
@Composable internal fun MonthCloseCard(status: MonthStatus, onIssues: () -> Unit, onReviews: () -> Unit, onExport: () -> Unit, exporting: Boolean,
    act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) =
    MonthClosePanel(status, onIssues, onReviews, onExport, exporting, act, repository, modifier, compact = true)

@Composable private fun StepRow(number: Int, step: CloseStep, busy: Boolean, onAction: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val state = when { step.done -> "완료"; step.idle -> "대기"; step.optional -> "선택"; else -> "남음" }
    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).semantics(mergeDescendants = true) { stateDescription = state },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Done is the scene's positive with the scheme's text for it; a step still to do is the primary disc; an optional one the
        // row fill; an idle one (a month with no trips) the row fill with a quieter number.
        Box(Modifier.size(32.dp).clip(CircleShape).background(when { step.done -> Scene.positive; step.optional || step.idle -> Scene.fill; else -> colors.primary }),
            contentAlignment = Alignment.Center) {
            Text(if (step.done) "✓" else number.toString(), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
                color = when { step.done -> colors.onTertiary; step.idle -> Scene.textSecondary; step.optional -> Scene.text; else -> colors.onPrimary },
                modifier = Modifier.clearAndSetSemantics { contentDescription = "${number}단계" })
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(step.detail, style = MaterialTheme.typography.bodySmall, color = if (!step.done && !step.optional && step.action != null) Scene.negative else Scene.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (step.action != null && onAction != null) TextButton(onClick = onAction, enabled = !busy, modifier = Modifier.heightIn(min = 56.dp)) {
            Text(if (busy) "저장 중…" else step.action)
        }
    }
}
