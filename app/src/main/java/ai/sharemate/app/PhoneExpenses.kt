package ai.sharemate.app

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.time.YearMonth
import java.time.ZoneId

/*
 * 경비 tab of the phone app: money questions in one place. Totals for the month, the card payments the car could not
 * place by itself (swipe or tap to answer, every answer undoable), what was matched automatically, the month-end
 * statement import as three visible steps, and a sheet for a cost the card never saw. Everything is sent to the car as
 * one SyncCommand and comes back through the mirror; the phone never writes the log.
 */

// ───────────────────────── pure helpers ─────────────────────────

internal enum class StatementStep(val label: String) { PICK("파일 선택"), SEND("차량 전송"), RESULT("자동 반영 결과") }

/** What became of the statement lines after the car looked at them, read off the mirror before and after. */
internal data class StatementOutcome(
    val total: Int,
    val relevant: Int,
    // Lines the car took in for the first time, by category and what it did with them: ("주차" to "새로 반영") -> 1.
    val arrived: Map<Pair<String, String>, Int>,
    val duplicates: Int,
    val waiting: Int,
    val paired: Boolean,
)

private fun chargeOutcomeLabel(charge: CardCharge): Pair<String, String> = (charge.category?.label ?: "기타") to when (charge.state) {
    ChargeState.MATCHED -> "새로 반영"
    ChargeState.REVIEW -> "확인 필요"
    ChargeState.WAITING -> "차량 기록 대기"
    ChargeState.EXCLUDED -> "제외"
    ChargeState.CANCELED -> "승인 취소"
}

internal fun statementOutcome(all: List<CardApproval>, relevant: List<CardApproval>, before: List<CardCharge>, after: List<CardCharge>, paired: Boolean): StatementOutcome {
    val known = before.flatMap { listOfNotNull(it.id, it.statementId) }.toSet()
    val arrived = linkedMapOf<Pair<String, String>, Int>()
    var duplicates = 0
    var waiting = 0
    relevant.forEach { line ->
        val fresh = after.firstOrNull { it.id == line.id }
        when {
            line.id in known -> duplicates++
            fresh != null -> arrived.merge(chargeOutcomeLabel(fresh), 1, Int::plus)
            after.any { it.statementId == line.id } -> duplicates++
            else -> waiting++
        }
    }
    return StatementOutcome(all.size, relevant.size, arrived, duplicates, waiting, paired)
}

internal fun statementOutcomeText(outcome: StatementOutcome): String {
    if (outcome.relevant == 0) return "명세서 ${outcome.total}건 중 차량 관련 결제가 없어요 · 보낸 것 없음"
    val head = "명세서 ${outcome.total}건 중 차량 관련 ${outcome.relevant}건을 보냈어요"
    if (!outcome.paired) return "$head · 차량과 연결되면 자동 전송돼요"
    val parts = outcome.arrived.map { (key, count) -> "${key.first} ${count}건 ${key.second}" } +
        listOfNotNull(outcome.duplicates.takeIf { it > 0 }?.let { "${it}건은 이미 있는 건" }, outcome.waiting.takeIf { it > 0 }?.let { "${it}건은 차량 반영 대기" })
    return if (parts.isEmpty()) head else "$head · ${parts.joinToString(", ")}"
}

/** Charges of [month] in the demo/real set that is showing. */
internal fun chargesIn(data: TripLogData, month: YearMonth, zone: ZoneId): List<CardCharge> =
    data.charges.filter { it.demo == data.profile.demoMode && YearMonth.from(java.time.Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate()) == month }

/** Default trip for a typed cost: the most recent finished trip that is showing; the sheet says it is a default. */
internal fun defaultExpenseTrip(data: TripLogData): Trip? = data.trips.filter { it.demo == data.profile.demoMode && !it.open }.maxByOrNull { it.startAt }

internal fun expenseTripLabel(trip: Trip): String = "${phoneTime(trip.startAt)} 출발 · ${trip.destination ?: trip.endAddress ?: "주소 미기록"}"

/** The month's registered costs by kind, every kind present (0 when nothing was spent), in [ExpenseType] order. */
internal fun expenseTypeTotals(trips: List<Trip>): Map<ExpenseType, Long> =
    ExpenseType.entries.associateWith { type -> trips.sumOf { trip -> trip.expenses.filter { it.type == type }.sumOf { it.amountWon } } }

/** The extra room under the 경비 list while the add button floats over it: the 56-dp button, its gutter margin and 12 dp of air. */
internal fun expenseFabClearance(gutter: Dp): Dp = 56.dp + gutter + 12.dp

// ───────────────────────── statement import ─────────────────────────

/** Owns the file picker and the three steps of a statement import; the result is read off the mirror, not guessed. */
@Stable internal class StatementImporter internal constructor(private val open: () -> Unit) {
    var step by mutableStateOf(StatementStep.PICK)
        internal set
    var fileName by mutableStateOf<String?>(null)
        internal set
    var error by mutableStateOf<String?>(null)
        internal set
    var outcome by mutableStateOf<StatementOutcome?>(null)
        internal set
    var running by mutableStateOf(false)
        internal set

    fun launch() { error = null; outcome = null; open() }
    fun reset() { step = StatementStep.PICK; fileName = null; error = null; outcome = null; running = false }
}

private val STATEMENT_MIME_TYPES = arrayOf("text/csv", "text/comma-separated-values", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-excel", "text/html", "*/*")
private const val STATEMENT_MIRROR_WAIT_MILLIS = 10_000L

/**
 * One importer per screen. Picks a CSV/XLSX, keeps only vehicle-related lines on the phone, queues them for the car and
 * waits for the mirror to come back so the result line says what really happened.
 */
@Composable internal fun rememberStatementImporter(sync: PhoneSyncClient, data: TripLogData, zone: ZoneId = ZoneId.systemDefault(), paired: Boolean = sync.paired): StatementImporter {
    val context = LocalContext.current
    val latest = rememberUpdatedState(data)
    val isPaired = rememberUpdatedState(paired)
    val scope = rememberCoroutineScope()
    // The launcher is created after the importer, so the importer reaches it through this holder.
    val open = remember { LauncherHolder() }
    val target = remember { StatementImporter { open.launch?.invoke() } }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            target.running = true
            target.step = StatementStep.SEND
            try {
                target.fileName = withContext(Dispatchers.IO) {
                    runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
                }
                val all = withContext(Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException()
                    parseStatementFile(bytes, zone)
                }
                val snapshot = latest.value
                val relevant = vehicleRelevant(all, snapshot, zone)
                val before = snapshot.charges
                sync.enqueue(relevant)
                if (relevant.isNotEmpty() && isPaired.value) {
                    // The car applies the batch in one revision; the next mirror after the send carries the answer.
                    withTimeoutOrNull(STATEMENT_MIRROR_WAIT_MILLIS) { snapshotFlow { latest.value.charges }.drop(1).first() }
                }
                target.outcome = statementOutcome(all, relevant, before, latest.value.charges, isPaired.value)
                target.step = StatementStep.RESULT
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) {
                target.error = e.message ?: "명세서를 읽지 못했어요. CSV·XLSX 파일인지 확인해 주세요."
                target.step = StatementStep.PICK
            } catch (_: Exception) {
                target.error = "명세서를 읽지 못했어요. CSV·XLSX 파일인지 확인해 주세요. 기록은 바뀌지 않았어요."
                target.step = StatementStep.PICK
            } finally { target.running = false }
        }
    }
    open.launch = { launcher.launch(STATEMENT_MIME_TYPES) }
    return target
}

private class LauncherHolder { var launch: (() -> Unit)? = null }

/** Three dots and labels: where the import stands. Hidden from TalkBack as decoration; the row below says the same in words. */
@Composable private fun StepDots(steps: List<String>, current: Int, done: Boolean) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().semantics { contentDescription = "${steps.size}단계 중 ${current + 1}단계 · ${steps[current]}${if (done) " 완료" else ""}" },
        verticalAlignment = Alignment.CenterVertically) {
        steps.forEachIndexed { index, label ->
            val reached = index < current || (index == current && done)
            val active = index == current
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(when { reached -> Scene.positive; active -> colors.primary; else -> Scene.fill }),
                    contentAlignment = Alignment.Center) {
                    Text(if (reached) "✓" else "${index + 1}", style = MaterialTheme.typography.labelSmall, color = when { reached -> colors.onPrimary; active -> colors.onPrimary; else -> colors.onSurfaceVariant })
                }
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (active || reached) colors.onSurface else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (index < steps.lastIndex) Box(Modifier.weight(1f).padding(horizontal = 6.dp).height(1.dp).background(if (index < current) Scene.positive else Scene.divider))
        }
    }
}

@Composable internal fun StatementImportCard(importer: StatementImporter, busy: Boolean) {
    val steps = StatementStep.entries.map { it.label }
    Panel(padding = phoneTokens().panelPadding) {
        Eyebrow("카드 명세서 가져오기 · CSV·XLSX")
        StepDots(steps, importer.step.ordinal, done = importer.step == StatementStep.RESULT)
        when (importer.step) {
            StatementStep.PICK -> {
                Text("하이패스처럼 승인 알림이 없는 결제를 월말 명세서로 맞춰요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                Muted("차량 관련 줄만 차량으로 보내고 나머지는 휴대폰을 떠나지 않아요. 같은 파일을 다시 넣어도 중복되지 않아요.")
                importer.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                OutlinedButton(onClick = importer::launch, enabled = !busy && !importer.running, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                    Text(if (importer.error != null) "다른 파일 선택" else "명세서 파일 선택")
                }
            }
            StatementStep.SEND -> {
                Text(importer.fileName ?: "명세서 파일", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) { LoadingLabel("차량에 보내는 중…", true, "차량에 보내는 중…") }
                Muted("차량이 운행 기록과 맞춰 보는 동안 잠시 기다려 주세요.")
            }
            StatementStep.RESULT -> {
                importer.fileName?.let { Text(it, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(importer.outcome?.let(::statementOutcomeText) ?: "결과를 받지 못했어요", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Muted("확인이 필요한 건은 위 ‘확인 필요’ 목록에 올라와요.")
                TextButton(onClick = importer::reset, modifier = Modifier.heightIn(min = 48.dp)) { Text("다른 명세서 가져오기") }
            }
        }
    }
}

// ───────────────────────── the tab ─────────────────────────

/**
 * 경비 tab body. [month] and [employeeFilter] default to this month and everyone, so the shell can pass only what it
 * holds. [importer] may be shared with the settings page; when null the tab owns one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhoneExpensesTab(
    data: TripLogData, state: TripLogState, link: PhoneLink, busy: Boolean, send: (SyncCommand) -> Unit, importStatement: () -> Unit,
    alertsGranted: Boolean, openAlertSettings: () -> Unit, queued: Int, modifier: Modifier = Modifier,
    month: YearMonth = YearMonth.now(), employeeFilter: String? = null, paired: Boolean = true, zone: ZoneId = ZoneId.systemDefault(),
    importer: StatementImporter? = null,
) {
    val followUp = LocalFollowUp.current
    val context = LocalContext.current
    val tokens = phoneTokens()
    val statement = importer ?: rememberStatementImporter(remember(context) { PhoneSyncClient.get(context) }, data, zone, paired)
    val demo = data.profile.demoMode
    val monthTrips = remember(data.trips, month, demo) { tripsIn(data.trips.filter { it.demo == demo }, month.atDay(1), month.atEndOfMonth(), zone) }
    val trips = remember(monthTrips, employeeFilter) { monthTrips.filter { employeeFilter == null || it.driverId.orEmpty() == employeeFilter } }
    val charges = remember(data.charges, month, demo) { chargesIn(data, month, zone) }
    val reviews = remember(data.charges, demo) { data.charges.filter { it.demo == demo && it.state == ChargeState.REVIEW }.sortedBy { it.at } }
    val matched = remember(charges) { charges.filter { it.state == ChargeState.MATCHED }.sortedByDescending { it.at } }
    val excluded = remember(charges) { charges.filter { it.state == ChargeState.EXCLUDED }.sortedByDescending { it.at } }
    val filterLabel = when (employeeFilter) { null -> ""; "" -> " · 직원 미지정"; else -> data.drivers.firstOrNull { it.id == employeeFilter }?.let { " · ${it.name}" } ?: "" }
    var showAdd by rememberSaveable { mutableStateOf(false) }
    var showMatched by rememberSaveable { mutableStateOf(false) }
    var showExcluded by rememberSaveable { mutableStateOf(false) }
    var explainReimbursement by rememberSaveable { mutableStateOf(false) }
    // Answers already sent for a review card; the row dims until the mirror moves it out of the list.
    var decided by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(reviews) { decided = decided.filter { id -> reviews.any { it.id == id } }.toSet() }
    LaunchedEffect(decided) { if (decided.isNotEmpty()) { delay(8_000); decided = emptySet() } }

    // The floating tab bar covers the bottom of the tab: the list scrolls on under it, and the add button stands above it.
    val navInset = LocalNavInset.current
    Box(modifier.fillMaxSize()) {
        // The list keeps to the content width and centres on a wide window (spec B); the add button stays at the window's corner.
        LazyColumn(Modifier.contentWidth().fillMaxHeight().align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = tokens.gutter, end = tokens.gutter, bottom = tabScrollBottom(navInset, extra = if (paired) expenseFabClearance(tokens.gutter) else 0.dp)),
            verticalArrangement = Arrangement.spacedBy(tokens.cardGap + 4.dp)) {
            item(key = "totals") {
                Panel(padding = tokens.panelPadding + 2.dp) {
                    Eyebrow("${month.monthValue}월 경비$filterLabel")
                    // The figures cross-fade to their new values when the mirror brings a change; they never count up (a sum is data).
                    DigitChange(formatWon(trips.sumOf { it.expenseWon }), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
                        color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.semantics { heading() })
                    val byType = remember(trips) { expenseTypeTotals(trips) }
                    ExpenseType.entries.forEach { type ->
                        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            Text(type.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            DigitChange(formatWon(byType[type] ?: 0L), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                    HorizontalDivider(color = Scene.divider)
                    if (data.profile.reimbursePerKmWon > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Stat("업무 거리 정산 참고", formatWon(employeeUsage(trips, data.drivers, data.profile.reimbursePerKmWon).sumOf { it.reimbursementWon }), Modifier.weight(1f))
                            TextButton(onClick = { explainReimbursement = !explainReimbursement }, modifier = Modifier.heightIn(min = 48.dp).semantics {
                                contentDescription = "정산 참고 설명"; stateDescription = if (explainReimbursement) "펼침" else "접힘" }) { Text("ⓘ", style = MaterialTheme.typography.titleMedium) }
                        }
                        if (explainReimbursement) Muted("업무 거리 × ${formatWon(data.profile.reimbursePerKmWon)}/km · 직원 미지정 제외 · 지급 확정 아님")
                    } else Muted("차량에서 km당 지급 단가를 설정하면 업무 거리 정산액을 함께 볼 수 있어요.")
                    Muted("입력했거나 카드에서 자동 반영된 경비예요. 영수증·지급 기준은 별도로 확인하세요.")
                }
            }
            if (!paired) item(key = "unpaired") { Muted("차량과 연결되면 카드 결제를 확인하고 경비를 추가할 수 있어요. 지금은 사본을 보고 있어요.") }
            if (reviews.isNotEmpty()) {
                item(key = "reviews-title") {
                    Text("확인 필요 ${reviews.size}건 · 옆으로 밀어 답할 수 있어요", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
                }
                reviews.forEach { charge ->
                    item(key = "review-${charge.id}") {
                        ReviewSwipeRow(charge, data, busy || charge.id in decided, paired, send, onDecided = { decided = decided + charge.id })
                    }
                }
            }
            if (matched.isNotEmpty()) item(key = "matched") {
                Panel(padding = tokens.panelPadding) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = showMatched, role = Role.Button) { showMatched = !showMatched }
                        .semantics { stateDescription = if (showMatched) "펼침" else "접힘" }, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Eyebrow("자동 반영 ${matched.size}건 · ${formatWon(matched.sumOf { it.amountWon })}")
                            Muted("차량 기록과 맞아 운행 비용에 들어간 카드 결제예요. 틀렸으면 ‘회사 비용 아님’으로 빼요.")
                        }
                        Text(if (showMatched) "접기" else "펼치기", style = MaterialTheme.typography.labelLarge)
                    }
                    if (showMatched) matched.forEach { charge ->
                        val trip = data.trips.firstOrNull { it.id == charge.tripId } ?: data.open?.takeIf { it.id == charge.tripId }
                        HorizontalDivider(color = Scene.divider)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${formatWon(charge.amountWon)} · ${charge.merchant}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Muted(listOfNotNull(phoneTime(charge.at, if (charge.timeKnown) "M/d HH:mm" else "M/d"), trip?.let { "${phoneTime(it.startAt)} 출발 운행" }, charge.reason.ifBlank { null }).joinToString(" · "))
                            }
                            TextButton(onClick = {
                                send(SyncCommand.ExcludeCharge(charge.id))
                                followUp(if (trip != null) FollowUp("${charge.merchant} ${formatWon(charge.amountWon)}을 운행에서 뺐어요", "실행 취소") { send(SyncCommand.ConfirmCharge(charge.id, trip.id)) }
                                    else FollowUp("${charge.merchant} ${formatWon(charge.amountWon)}을 운행에서 뺐어요"))
                            }, enabled = !busy && paired, modifier = Modifier.heightIn(min = 48.dp)) { Text("회사 비용 아님") }
                        }
                    }
                }
            }
            if (excluded.isNotEmpty()) item(key = "excluded") {
                Panel(padding = tokens.panelPadding) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected = showExcluded, role = Role.Button) { showExcluded = !showExcluded }
                        .semantics { stateDescription = if (showExcluded) "펼침" else "접힘" }, verticalAlignment = Alignment.CenterVertically) {
                        Eyebrow("제외 ${excluded.size}건 · 회사 비용 아님")
                        Spacer(Modifier.weight(1f))
                        Text(if (showExcluded) "접기" else "펼치기", style = MaterialTheme.typography.labelLarge)
                    }
                    if (showExcluded) excluded.forEach { charge ->
                        val trip = suggestedTrip(charge, data)
                        HorizontalDivider(color = Scene.divider)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text("${formatWon(charge.amountWon)} · ${charge.merchant}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Muted(phoneTime(charge.at, if (charge.timeKnown) "M/d HH:mm" else "M/d"))
                            }
                            if (trip != null) TextButton(onClick = {
                                send(SyncCommand.ConfirmCharge(charge.id, trip.id))
                                followUp(FollowUp("${charge.merchant} ${formatWon(charge.amountWon)}을 ${phoneTime(trip.startAt)} 운행에 반영했어요", "실행 취소") { send(SyncCommand.ExcludeCharge(charge.id)) })
                            }, enabled = !busy && paired, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 반영") }
                        }
                    }
                }
            }
            item(key = "statement") { StatementImportCard(statement, busy) }
            item(key = "card-link") { PhoneCardLinkCard(alertsGranted, queued, busy, openAlertSettings, importStatement, showImport = false) }
        }
        if (paired) ExtendedFloatingActionButton(onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(start = tokens.gutter, top = tokens.gutter, end = tokens.gutter, bottom = tokens.gutter + navInset).heightIn(min = 56.dp)
            .semantics { contentDescription = "경비 추가" }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
            Text("+ 경비", style = MaterialTheme.typography.labelLarge)
        }
    }
    if (showAdd) AddExpenseSheet(data, busy, send, onDismiss = { showAdd = false })
}

/** A review card with the two answers as swipes: right to accept the suggested trip, left to say it was not a company cost. */
@Composable private fun ReviewSwipeRow(charge: CardCharge, data: TripLogData, busy: Boolean, paired: Boolean, send: (SyncCommand) -> Unit, onDecided: () -> Unit) {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    val trip = suggestedTrip(charge, data)
    val label = "${charge.merchant} ${formatWon(charge.amountWon)}"
    val confirm: () -> Unit = {
        if (trip != null) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            send(SyncCommand.ConfirmCharge(charge.id, trip.id)); onDecided()
            followUp(FollowUp("${label}을 운행에 반영했어요", "회사 비용 아님") { send(SyncCommand.ExcludeCharge(charge.id)) })
        }
    }
    val exclude: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        send(SyncCommand.ExcludeCharge(charge.id)); onDecided()
        followUp(if (trip != null) FollowUp("${label}을 회사 비용 아님으로 제외했어요", "되돌리기") { send(SyncCommand.ConfirmCharge(charge.id, trip.id)) }
            else FollowUp("${label}을 회사 비용 아님으로 제외했어요"))
    }
    // The row never leaves by itself: the command goes out and the mirror removes the card when the car has applied it.
    val swipe = rememberSwipeToDismissBoxState(confirmValueChange = { value ->
        when (value) {
            SwipeToDismissBoxValue.StartToEnd -> if (!busy && paired && trip != null) confirm()
            SwipeToDismissBoxValue.EndToStart -> if (!busy && paired) exclude()
            SwipeToDismissBoxValue.Settled -> {}
        }
        false
    }, positionalThreshold = { distance -> distance * .4f })
    val colors = MaterialTheme.colorScheme
    SwipeToDismissBox(state = swipe, backgroundContent = {
        val toConfirm = swipe.dismissDirection == SwipeToDismissBoxValue.StartToEnd
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(if (toConfirm) colors.tertiaryContainer else colors.surfaceVariant).padding(horizontal = 24.dp),
            contentAlignment = if (toConfirm) Alignment.CenterStart else Alignment.CenterEnd) {
            Text(if (toConfirm) "이 운행 비용" else "회사 비용 아님", style = MaterialTheme.typography.labelLarge, color = if (toConfirm) colors.onTertiaryContainer else colors.onSurfaceVariant)
        }
    }, modifier = Modifier.alpha(if (busy) .6f else 1f).semantics {
        customActions = listOfNotNull(if (trip != null) CustomAccessibilityAction("이 운행 비용") { confirm(); true } else null, CustomAccessibilityAction("회사 비용 아님") { exclude(); true })
        if (busy) stateDescription = "차량에 보내는 중"
    }, enableDismissFromStartToEnd = paired && trip != null && !busy, enableDismissFromEndToStart = paired && !busy) {
        PhoneChargeReviewCard(charge, data, busy || !paired, send, onDecided = onDecided)
    }
}

/** A cost the card never saw: pick the trip (the latest is pre-selected and says so), the kind and the amount. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun AddExpenseSheet(data: TripLogData, busy: Boolean, send: (SyncCommand) -> Unit, onDismiss: () -> Unit) {
    val followUp = LocalFollowUp.current
    val candidates = remember(data.trips, data.profile.demoMode) { data.trips.filter { it.demo == data.profile.demoMode && !it.open }.sortedByDescending { it.startAt }.take(12) }
    val fallback = defaultExpenseTrip(data)
    var tripId by rememberSaveable { mutableStateOf(fallback?.id) }
    var choosing by rememberSaveable { mutableStateOf(false) }
    var type by rememberSaveable { mutableStateOf(ExpenseType.PARKING.name) }
    var amount by rememberSaveable { mutableStateOf("") }
    var amountError by rememberSaveable { mutableStateOf<String?>(null) }
    val trip = candidates.firstOrNull { it.id == tripId } ?: fallback
    val tokens = phoneTokens()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetMaxWidth = phoneSheetMaxWidth(), containerColor = Scene.rail, contentColor = Scene.text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = tokens.gutter).padding(bottom = 32.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("경비 추가", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Eyebrow("어느 운행이에요?")
            if (trip == null) Muted("끝난 운행이 없어요. 운행이 끝나면 경비를 붙일 수 있어요.")
            else {
                Surface(shape = RoundedCornerShape(16.dp), color = Scene.glass, contentColor = Scene.text, border = Scene.edge) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("${dayLabel(localDate(trip.startAt))} ${expenseTripLabel(trip)}", style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Muted(if (trip.id == fallback?.id) "가장 최근 운행 · 기본 선택" else "선택한 운행")
                        }
                        if (candidates.size > 1) TextButton(onClick = { choosing = !choosing }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (choosing) "접기" else "다른 운행 고르기") }
                    }
                }
                if (choosing) Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    candidates.forEach { candidate ->
                        val press = remember(candidate.id) { MutableInteractionSource() }
                        Row(Modifier.fillMaxWidth().pressable(press).clip(RoundedCornerShape(12.dp)).background(if (candidate.id == trip.id) Scene.selectionFill else Color.Transparent)
                            .selectable(selected = candidate.id == trip.id, interactionSource = press, indication = LocalIndication.current, role = Role.RadioButton) { tripId = candidate.id; choosing = false }
                            .heightIn(min = 52.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            RadioButton(selected = candidate.id == trip.id, onClick = null)
                            Text("${dayLabel(localDate(candidate.startAt))} ${expenseTripLabel(candidate)}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Eyebrow("종류")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ExpenseType.entries.forEach { candidate ->
                    val chosen = type == candidate.name
                    FilterChip(selected = chosen, onClick = { type = candidate.name }, label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall, maxLines = 1) },
                        colors = sceneChipColors(), border = sceneChipBorder(chosen), modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedTextField(amount, { if (it.length <= 8 && it.all(Char::isDigit)) { amount = it; amountError = null } }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("금액 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp),
                isError = amountError != null, supportingText = amountError?.let { { Text(it) } }, colors = sceneTextFieldColors())
            Button(onClick = {
                val won = amount.toLongOrNull() ?: 0
                val target = trip
                when {
                    target == null -> {}
                    won <= 0 -> amountError = "금액을 입력해 주세요."
                    won > 10_000_000 -> amountError = "1,000만 원 이하로 입력해 주세요."
                    else -> {
                        val kind = ExpenseType.valueOf(type)
                        val index = target.expenses.size
                        send(SyncCommand.AddExpense(target.id, kind, won))
                        followUp(FollowUp("${kind.label} ${formatWon(won)}을 ${phoneTime(target.startAt)} 운행에 추가했어요", "실행 취소") { send(SyncCommand.RemoveExpense(target.id, index)) })
                        onDismiss()
                    }
                }
            }, enabled = !busy && trip != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { Text("추가") }
            Muted("차량 기록에 ‘휴대폰’ 출처로 남고, 잘못 넣었으면 바로 실행 취소할 수 있어요.")
        }
    }
}
