package ai.sharemate.app

import androidx.core.content.edit
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Fleet care on the car screen: the deadlines a company must not miss (inspection, insurance, lease, service interval)
 * and the maintenance it paid for. Every number here is a date the manager typed or a kilometre the odometer reported;
 * nothing is looked up or estimated. Core rules live in FleetCare.kt, mutations in TripLogRepository.
 */

internal const val CARE_SNOOZE_DAYS = 7L
private const val FLEET_UI_PREFS = "fleet-ui"
private const val CARE_SNOOZE_PREF = "careSnoozes"
private const val DAY_MS = 86_400_000L

// ───────────────────────── pure helpers ─────────────────────────

/**
 * One snooze per threshold crossing: the key names the deadline and the urgency level, so a new date, a new service
 * interval, or the step from "soon" to "overdue" asks again instead of staying silent.
 */
internal fun careSnoozeKey(alert: CareAlert, care: VehicleCare): String = when (alert.kind) {
    CareKind.INSPECTION -> "INSPECTION:${care.inspectionDue}:${alert.level}"
    CareKind.INSURANCE -> "INSURANCE:${care.insuranceDue}:${alert.level}"
    CareKind.LEASE -> "LEASE:${care.leaseEnd}:${alert.level}"
    CareKind.SERVICE -> "SERVICE:${care.lastServiceKm}+${care.serviceIntervalKm}:${alert.level}"
}

/** Stored entries look like `KEY=untilMillis`; only the ones still in the future count. */
internal fun activeCareSnoozes(raw: Set<String>, now: Long): Set<String> = raw.mapNotNull { entry ->
    val at = entry.lastIndexOf('=')
    if (at <= 0) return@mapNotNull null
    val until = entry.substring(at + 1).toLongOrNull() ?: return@mapNotNull null
    entry.substring(0, at).takeIf { until > now }
}.toSet()

internal fun withCareSnooze(raw: Set<String>, key: String, now: Long, days: Long = CARE_SNOOZE_DAYS): Set<String> =
    (raw.filter { activeCareSnoozes(setOf(it), now).isNotEmpty() && !it.startsWith("$key=") } + "$key=${now + days * DAY_MS}").toSet()

/** The snoozed deadline keys, read from the `fleet-ui` preferences; the home task list uses this to stay quiet for seven days. */
internal fun careSnoozes(context: Context, now: Long = System.currentTimeMillis()): Set<String> =
    activeCareSnoozes(context.getSharedPreferences(FLEET_UI_PREFS, Context.MODE_PRIVATE).getStringSet(CARE_SNOOZE_PREF, emptySet()) ?: emptySet(), now)

/** Snoozes [key] for seven days and returns the active set so the screen can update without re-reading preferences. */
internal fun snoozeCare(context: Context, key: String, now: Long = System.currentTimeMillis()): Set<String> {
    val prefs = context.getSharedPreferences(FLEET_UI_PREFS, Context.MODE_PRIVATE)
    val next = withCareSnooze(prefs.getStringSet(CARE_SNOOZE_PREF, emptySet()) ?: emptySet(), key, now)
    prefs.edit { putStringSet(CARE_SNOOZE_PREF, next) }
    return activeCareSnoozes(next, now)
}

/** Deadlines that deserve a card on the home screen: soon or overdue, and not snoozed. Ordered by urgency like [careAlerts]. */
internal fun careDueAlerts(care: VehicleCare, odometerKm: Double?, today: LocalDate, snoozed: Set<String>): List<CareAlert> =
    careAlerts(care, odometerKm, today).filter { it.level != CareLevel.INFO && careSnoozeKey(it, care) !in snoozed }

/** The odometer the car trusts for care: the live sample while fresh (≤ 45 s), else the last value the car reported; the flag says which. */
internal fun careOdometer(state: TripLogState, now: Long): Pair<Double?, Boolean> {
    val live = state.live?.takeIf { vehicleSampleFresh(state, now) && now - it.at <= 45_000 }?.odometerKm
    return if (live != null) live to true else state.data.vehicle.odometerKm?.takeIf { it.isFinite() } to false
}

/** How far through the service interval the car is, 0..1, or null when the interval is not configured or the odometer is unknown. */
internal fun serviceProgress(care: VehicleCare, odometerKm: Double?): Float? {
    val interval = care.serviceIntervalKm?.takeIf { it > 0 } ?: return null
    val last = care.lastServiceKm ?: return null
    odometerKm ?: return null
    return ((odometerKm - last) / interval).toFloat().coerceIn(0f, 1f)
}

/** Dates typed as digits get their dashes as they go ("20261124" → "2026-11-24"); typed dashes, dots or slashes are kept as dashes. */
internal fun typeIsoDate(input: String): String {
    val cleaned = input.replace('.', '-').replace('/', '-').filter { it.isDigit() || it == '-' }.take(10)
    if (cleaned.contains('-')) return cleaned
    return when {
        cleaned.length <= 4 -> cleaned
        cleaned.length <= 6 -> cleaned.substring(0, 4) + "-" + cleaned.substring(4)
        else -> cleaned.substring(0, 4) + "-" + cleaned.substring(4, 6) + "-" + cleaned.substring(6, minOf(8, cleaned.length))
    }
}

/** Inline error for a care date field: nothing for blank or a valid ISO date, otherwise how to write it. */
internal fun careDateError(value: String): String? = if (validIsoDate(value.trim())) null else "2026-11-24처럼 연-월-일로 입력해 주세요"

/** Hint under a valid date: how far away it is today, in the same words the alerts use. */
internal fun careDateHint(value: String, today: LocalDate): String? {
    val due = parseIsoDate(value.trim()) ?: return null
    val days = ChronoUnit.DAYS.between(today, due)
    return when {
        days < 0 -> "${-days}일 지남"
        days == 0L -> "오늘"
        else -> "D-$days · ${due.monthValue}월 ${due.dayOfMonth}일"
    }
}

internal fun careIntervalError(value: String): String? {
    if (value.isBlank()) return null
    val km = value.toIntOrNull() ?: return "숫자만 입력해 주세요"
    return if (km in 500..100_000) null else "500~100,000 km 사이로 입력해 주세요"
}

internal fun careKmError(value: String): String? {
    if (value.isBlank()) return null
    val km = value.toDoubleOrNull() ?: return "숫자만 입력해 주세요"
    return if (km.isFinite() && km in 0.0..10_000_000.0) null else "계기판 값을 확인해 주세요"
}

/** What tapping 완료 on a due card does, said up front so the button is not a mystery. */
internal fun careDoneLabel(kind: CareKind) = when (kind) {
    CareKind.SERVICE -> "정비 완료"; CareKind.INSPECTION -> "검사 완료"; CareKind.INSURANCE -> "갱신 완료"; CareKind.LEASE -> "처리 완료"
}

internal fun careGuidance(kind: CareKind) = when (kind) {
    CareKind.INSPECTION -> "검사소를 예약하세요. 기한을 넘기면 과태료가 붙어요."
    CareKind.INSURANCE -> "보험사와 갱신을 확인하세요. 공백이 생기면 운행할 수 없어요."
    CareKind.LEASE -> "반납·연장을 정하세요. 계약 종료 전 정산 조건을 확인해 두세요."
    CareKind.SERVICE -> "정비소 방문을 잡아 주세요. 계기판 값 기준이에요."
}

internal fun careDoneExplanation(kind: CareKind) = when (kind) {
    CareKind.SERVICE -> "완료를 누르면 지금 계기판 값이 마지막 정비로 기록돼요."
    CareKind.INSPECTION -> "완료를 누르면 검사 기록을 남기고 만료일을 지워요. 다음 검사일은 설정 › 법인·차량에 적어 주세요."
    CareKind.INSURANCE -> "완료를 누르면 등록된 만기일을 지워요. 새 만기일은 설정 › 법인·차량에 적어 주세요."
    CareKind.LEASE -> "완료를 누르면 등록된 만기일을 지워요. 새 계약은 설정 › 법인·차량에 적어 주세요."
}

/** Settings forms put their fields in two columns at EXPANDED and FULL, one below that, and always one at a 1.3× font scale (spec B). */
internal fun careFieldColumns(tokens: LayoutTokens, fontScale: Float): Int = if (tokens.expanded && fontScale <= 1.25f) 2 else 1

private fun kmLabel(value: Double) = String.format(Locale.KOREA, "%,.0f km", value)
private fun shortDate(at: Long) = SimpleDateFormat("M/d", Locale.KOREA).format(Date(at))

/** Overdue is the scene's negative, soon its warn (점검 필요), room to spare its positive: the same three tones on day glass and at night. */
@Composable internal fun careLevelColor(level: CareLevel): Color = when (level) {
    CareLevel.OVERDUE -> Scene.negative
    CareLevel.SOON -> Scene.warn
    CareLevel.INFO -> Scene.positive
}

internal fun careLevelState(level: CareLevel) = when (level) { CareLevel.OVERDUE -> "기한 지남"; CareLevel.SOON -> "곧 만기"; CareLevel.INFO -> "여유 있음" }

// ───────────────────────── 설정 › 법인·차량 ─────────────────────────

/** Fields in [columns] columns of equal width, 16 dp apart; a short last row keeps the column widths. */
@Composable private fun FieldGrid(fields: List<@Composable (Modifier) -> Unit>, columns: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        fields.chunked(columns.coerceAtLeast(1)).forEach { row ->
            if (columns <= 1) row.forEach { it(Modifier) }
            else Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { it(Modifier.weight(1f)) }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * The line under 지금 계기판 값 넣기 (2.12, pure, unit-tested): the odometer the button would put in, live ([fresh]) or the last
 * value the car reported, or why there is none yet. One fact; what an oil-change record does to the last-service value is
 * said to TalkBack ([SettingsCopy.SPOKEN_CARE_SERVICE]).
 */
internal fun careOdometerLine(odometer: Double?, fresh: Boolean): String = when {
    odometer == null -> SettingsCopy.CARE_ODOMETER_WAITING
    fresh -> "지금 계기판 ${kmLabel(odometer)}"
    else -> "마지막 수신 계기판 ${kmLabel(odometer)}"
}

/**
 * Where the manager types the deadlines (2.12: a [CarCard] titled 차량 관리 기한, one line on when the alerts come, the long
 * form for TalkBack). Dates are yyyy-MM-dd with the error under the field while typing; the odometer button fills the
 * last-service field from the live sample so nobody copies a seven-digit number by hand, and the line under it names that
 * value ([careOdometerLine]). The card fills what the settings page gives it ([modifier]: the 720-dp form column, or half the
 * page beside the profile card) and lays its fields in [columns] columns: one beside another card, two in the wide form
 * column ([careFieldColumns]).
 */
@Composable internal fun CareSettingsCard(state: TripLogState, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier,
    columns: Int = careFieldColumns(LocalLayout.current, LocalDensity.current.fontScale), now: Long = System.currentTimeMillis()) {
    val saved = state.data.care
    val followUp = LocalFollowUp.current
    val today = localDate(now)
    var inspection by rememberSaveable(saved.inspectionDue) { mutableStateOf(saved.inspectionDue.orEmpty()) }
    var insurance by rememberSaveable(saved.insuranceDue) { mutableStateOf(saved.insuranceDue.orEmpty()) }
    var lease by rememberSaveable(saved.leaseEnd) { mutableStateOf(saved.leaseEnd.orEmpty()) }
    var interval by rememberSaveable(saved.serviceIntervalKm) { mutableStateOf(saved.serviceIntervalKm?.toString().orEmpty()) }
    var lastKm by rememberSaveable(saved.lastServiceKm) { mutableStateOf(saved.lastServiceKm?.let { String.format(Locale.US, "%.0f", it) }.orEmpty()) }
    val (odometer, fresh) = careOdometer(state, now)
    val errors = listOf(careDateError(inspection), careDateError(insurance), careDateError(lease), careIntervalError(interval), careKmError(lastKm))
    val lastChanged = lastKm.toDoubleOrNull() != saved.lastServiceKm
    val updated = VehicleCare(inspection.trim().ifBlank { null }, insurance.trim().ifBlank { null }, lease.trim().ifBlank { null },
        interval.toIntOrNull(), lastKm.toDoubleOrNull(), lastServiceAt = if (lastChanged) (if (lastKm.isBlank()) null else now) else saved.lastServiceAt)
    val changed = updated.copy(lastServiceAt = saved.lastServiceAt) != saved
    CarCard(modifier, title = { CarSectionTitle(SettingsCopy.CARE_TITLE, icon = painterResource(R.drawable.ic_calendar)) }) {
        CarLine(SettingsCopy.CARE_LINE, spoken = SettingsCopy.SPOKEN_CARE)
        FieldGrid(listOf(
            { m -> Field("정기검사 만료일", inspection, { inspection = typeIsoDate(it) }, m, number = true, hint = careDateHint(inspection, today) ?: "예: 2026-11-24", error = careDateError(inspection)) },
            { m -> Field("보험 만기일", insurance, { insurance = typeIsoDate(it) }, m, number = true, hint = careDateHint(insurance, today) ?: "예: 2027-01-31", error = careDateError(insurance)) },
            { m -> Field("리스·렌트 만기일", lease, { lease = typeIsoDate(it) }, m, number = true, hint = careDateHint(lease, today) ?: "소유 차량이면 비워 두세요", error = careDateError(lease)) },
        ), columns)
        FieldGrid(listOf(
            { m -> Field("정비 주기", interval, { if (it.length <= 6 && it.all(Char::isDigit)) interval = it }, m,
                number = true, suffix = "km", hint = "예: 10000 · 제조사 권장 주기", error = careIntervalError(interval)) },
            { m -> Field("마지막 정비 계기판", lastKm, { if (it.length <= 9 && it.all { c -> c.isDigit() || c == '.' }) lastKm = it }, m,
                number = true, suffix = "km", done = true, hint = saved.lastServiceAt?.let { "마지막 정비 ${shortDate(it)}" } ?: "정비받은 날의 계기판 값", error = careKmError(lastKm)) },
        ), columns)
        QuietAction("지금 계기판 값 넣기", if (columns == 1) Modifier.fillMaxWidth() else Modifier, enabled = odometer != null,
            description = odometer?.let { "지금 계기판 값 ${kmLabel(it)} 넣기" }) { odometer?.let { lastKm = String.format(Locale.US, "%.0f", it) } }
        val reading = careOdometerLine(odometer, fresh)
        CarLine(reading, spoken = "$reading · ${SettingsCopy.SPOKEN_CARE_SERVICE}")
        PrimaryAction(if (changed) "기한 저장" else "저장된 기한", Modifier.fillMaxWidth(), enabled = changed && errors.all { it == null }) {
            act {
                repository.updateCare(updated)
                followUp(FollowUp("차량 관리 기한을 저장했어요", "실행 취소") { repository.updateCare(saved) })
            }
        }
    }
}

// ───────────────────────── 차량 탭 ─────────────────────────

/** The odometer the care card names beside its title (2.12): not yet received, the live value, or the last one received. */
private fun careOdometerTag(odometer: Double?, fresh: Boolean): String = when {
    odometer == null -> "계기판 수신 전"
    fresh -> "계기판 ${kmLabel(odometer)}"
    else -> "계기판 ${kmLabel(odometer)} · 마지막 수신값"
}

/**
 * The car's deadlines (2.12; the maintenance records have their own card, [MaintenanceCard]): the odometer the car trusts as a
 * tag beside the title, then one row per configured deadline, the service interval with a bar from the last service to the
 * next. Without one it says so in one line and offers 기한 입력하기 where [goSettings] is given.
 */
@Composable internal fun VehicleCareCard(state: TripLogState, now: Long, modifier: Modifier = Modifier, goSettings: (() -> Unit)? = null) {
    val care = state.data.care
    val (odometer, fresh) = careOdometer(state, now)
    val today = localDate(now)
    val alerts = remember(care, odometer, today) { careAlerts(care, odometer, today) }
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.CARE_TITLE, icon = painterResource(R.drawable.ic_calendar), trailing = { CarTag(careOdometerTag(odometer, fresh)) })
    }) {
        if (alerts.isEmpty()) {
            Text(if (care.serviceIntervalKm != null && care.lastServiceKm != null && odometer == null) VehicleCopy.CARE_WAITING else VehicleCopy.CARE_EMPTY,
                style = MaterialTheme.typography.titleSmall, color = Scene.text)
            CarLine(VehicleCopy.CARE_LINE)
            goSettings?.let { QuietAction("기한 입력하기", onClick = it) }
        } else Column {
            alerts.forEachIndexed { index, alert ->
                if (index > 0) CarDivider()
                CareAlertRow(alert, care, odometer)
            }
        }
    }
}

/**
 * One deadline as a [CarRow]: its level as a 12-dp dot (overdue in the negative, soon in warn, room to spare in positive) and
 * in words for TalkBack ([careLevelState]); an overdue title takes the negative too. The service interval adds its bar under
 * the row, starting where the text does.
 */
@Composable private fun CareAlertRow(alert: CareAlert, care: VehicleCare, odometer: Double?) {
    val color = careLevelColor(alert.level)
    val progress = if (alert.kind == CareKind.SERVICE) serviceProgress(care, odometer) else null
    Column {
        CarRow(alert.title, line = alert.detail, lead = { Box(Modifier.size(12.dp).clip(CircleShape).background(color)) },
            titleColor = if (alert.level == CareLevel.OVERDUE) color else Scene.text, state = careLevelState(alert.level))
        if (progress != null) {
            val percent = (progress * 100).roundToInt()
            // The bar sweeps from the previous value to the new one (spec C); the figure TalkBack hears is the real one. Keyed, so
            // coming back to the 차량 tab shows it at its value instead of sweeping again from empty.
            val shown = sweepProgress(progress, key = "vehicle-care-service")
            LinearProgressIndicator(progress = { shown.value }, modifier = Modifier.padding(start = 36.dp, bottom = 12.dp).fillMaxWidth().height(8.dp)
                .semantics { contentDescription = "정비 주기 ${percent}퍼센트 사용" },
                color = color, trackColor = Scene.ringTrack, drawStopIndicator = {})
        }
    }
}

/**
 * The maintenance records (2.12, split from 차량 관리): the count as a tag and 추가 / 닫기 beside the title, the form inline while
 * it is open, then the five latest records, each removable with undo in the bar. Card payments at a garage arrive by
 * themselves; the rest is in the Excel 차량관리 sheet.
 */
@Composable internal fun MaintenanceCard(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val data = state.data
    val followUp = LocalFollowUp.current
    val (odometer, _) = careOdometer(state, now)
    val all = data.maintenance.filter { it.demo == data.profile.demoMode }
    val records = all.sortedByDescending { it.at }.take(5)
    var adding by rememberSaveable { mutableStateOf(false) }
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.MAINTENANCE_TITLE, icon = rememberVectorPainter(Icons.Outlined.Build), trailing = {
            CarTag("${all.size}건")
            CarPillButton(if (adding) "닫기" else "추가", { adding = !adding }, description = if (adding) "정비 기록 입력 닫기" else "정비 기록 추가")
        })
    }) {
        if (adding) {
            MaintenanceForm(odometer, act, repository) { adding = false }
            CarDivider()
        }
        if (records.isEmpty()) CarLine(VehicleCopy.MAINTENANCE_EMPTY)
        else Column {
            records.forEachIndexed { index, record ->
                if (index > 0) CarDivider()
                MaintenanceRow(record) {
                    act {
                        repository.removeMaintenance(record.id)
                        followUp(FollowUp("${record.kind.label} 기록을 삭제했어요", "실행 취소") {
                            repository.addMaintenance(record.kind, record.amountWon, record.merchant, record.note, record.odometerKm, record.source)
                        })
                    }
                }
            }
        }
        if (all.size > records.size) CarLine(VehicleCopy.MAINTENANCE_MORE)
    }
}

/** One record as a [CarRow]: date · kind · amount over its details, and 삭제 in the negative, which names the record for TalkBack. */
@Composable private fun MaintenanceRow(record: MaintenanceRecord, onDelete: () -> Unit) {
    val detail = listOfNotNull(record.odometerKm?.let(::kmLabel), record.merchant, record.note.ifBlank { null },
        if (record.source == EditSource.CARD) "카드 자동 매칭" else if (record.source == EditSource.PHONE) "휴대폰" else null).joinToString(" · ")
    CarRow("${shortDate(record.at)} · ${record.kind.label} · ${if (record.amountWon == 0L) "금액 없음" else formatWon(record.amountWon)}",
        line = detail.ifBlank { null }, trailing = {
            CarPillButton("삭제", onDelete, color = Scene.negative, description = "${shortDate(record.at)} ${record.kind.label} 기록 삭제")
        })
}

/**
 * A maintenance kind (2.12): a 46-dp stadium in a 56-dp target, a radio button of the form's group. The chosen one is filled
 * with the accent (the label in onAccent, 4.93; the press darkens the fill under it), the others are outlined with the chip
 * border. The swap is instant; the press settles to .98.
 */
@Composable private fun ChoiceChip(label: String, active: Boolean, onClick: () -> Unit) {
    val skin = Car.skin
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(Modifier.pressable(interaction, .98f)
        .selectable(selected = active, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onClick)
        .padding(vertical = 5.dp).heightIn(min = 46.dp).clip(shape), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().then(if (active) Modifier.background(skin.accent) else Modifier.border(1.dp, skin.chipBorder, shape))
            .pressFill(interaction, if (active) skin.pressOnAccent else Scene.fill))
        Text(label, Modifier.padding(horizontal = 18.dp), style = MaterialTheme.typography.labelLarge, color = if (active) skin.onAccent else Scene.text, maxLines = 1)
    }
}

/**
 * The record form, inline in [MaintenanceCard] (2.12): the kind first (seven radio chips, one row or two), then amount and
 * odometer (prefilled from the car), merchant and note. While 엔진오일·소모품 is chosen one line says that it also moves the
 * last service.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun MaintenanceForm(odometer: Double?, act: Act, repository: TripLogRepository, done: () -> Unit) {
    val followUp = LocalFollowUp.current
    var kind by rememberSaveable { mutableStateOf(MaintenanceKind.OIL) }
    var amount by rememberSaveable { mutableStateOf("") }
    var merchant by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var km by rememberSaveable(odometer) { mutableStateOf(odometer?.let { String.format(Locale.US, "%.0f", it) }.orEmpty()) }
    val amountError = amount.toLongOrNull()?.takeIf { it > 10_000_000 }?.let { "1,000만 원까지 입력할 수 있어요" }
    val kmError = careKmError(km)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(VehicleCopy.MAINTENANCE_KIND, style = MaterialTheme.typography.titleSmall, color = Scene.text)
        FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaintenanceKind.entries.forEach { option -> ChoiceChip(option.label, option == kind) { kind = option } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Field("금액", amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), number = true, suffix = "원",
                hint = amount.toLongOrNull()?.let(::formatWon) ?: "보증·무상이면 비워 두세요", error = amountError)
            Field("계기판", km, { if (it.length <= 9 && it.all { c -> c.isDigit() || c == '.' }) km = it }, Modifier.weight(1f), number = true, suffix = "km",
                hint = if (odometer != null) "차량 수신값" else "선택", error = kmError)
        }
        Field("업체 (선택)", merchant, { if (it.length <= 60) merchant = it }, hint = "예: 블루핸즈 판교점")
        Field("메모 (선택)", note, { if (it.length <= 300) note = it }, done = true, hint = "예: 앞 타이어 2본 교체")
        if (kind == MaintenanceKind.OIL) CarLine(VehicleCopy.MAINTENANCE_OIL)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction("기록 추가", Modifier.weight(1f), enabled = amountError == null && kmError == null) {
                val won = amount.toLongOrNull() ?: 0L
                val chosen = kind; val shop = merchant; val memo = note; val odo = km.toDoubleOrNull()
                act {
                    val previousCare = repository.state.value.data.care
                    val record = repository.addMaintenance(chosen, won, shop.ifBlank { null }, memo, odo)
                    followUp(FollowUp("${chosen.label} 기록을 추가했어요", "실행 취소") {
                        repository.removeMaintenance(record.id)
                        if (chosen == MaintenanceKind.OIL) repository.updateCare(previousCare)
                    })
                }
                amount = ""; merchant = ""; note = ""; done()
            }
            QuietAction("취소", onClick = done)
        }
    }
}

// ───────────────────────── 홈 할 일 카드 ─────────────────────────

/**
 * The next-action card for one deadline: what is due, how far away, what to do about it, and two buttons — snooze a week
 * or mark it done. What 완료 writes is said on the card itself.
 */
@Composable internal fun CareDueCard(alert: CareAlert, onSnooze: () -> Unit, onDone: () -> Unit, modifier: Modifier = Modifier) {
    val color = careLevelColor(alert.level)
    Panel(modifier) {
        // The card's section label takes the brand eyebrow; a deadline already passed says so in the scene's negative.
        val eyebrow = "차량 관리 · ${careLevelState(alert.level)}"
        if (alert.level == CareLevel.OVERDUE) Eyebrow(eyebrow, color) else Eyebrow(eyebrow)
        Text(alert.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() }, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Muted(alert.detail)
        Text(careGuidance(alert.kind), style = MaterialTheme.typography.bodyLarge)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 520.dp) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                QuietAction("7일 뒤 다시", Modifier.weight(1f), onClick = onSnooze)
                PrimaryAction(careDoneLabel(alert.kind), Modifier.weight(1f), onClick = onDone)
            } else Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryAction(careDoneLabel(alert.kind), Modifier.fillMaxWidth(), onClick = onDone)
                QuietAction("7일 뒤 다시", Modifier.fillMaxWidth(), onClick = onSnooze)
            }
        }
        Text(careDoneExplanation(alert.kind), style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary)
    }
}

/** The snoozed set as state, read once from preferences; pass it to [CareDueTask] and to the home task list. */
@Composable internal fun rememberCareSnoozes(): MutableState<Set<String>> {
    val context = LocalContext.current
    return remember { mutableStateOf(careSnoozes(context)) }
}

/**
 * [CareDueCard] wired to the repository for the most urgent unsnoozed deadline. Renders nothing when there is none.
 * Done: SERVICE adds an oil/consumables record (which moves the service clock); INSPECTION adds an inspection record and
 * clears the due date; INSURANCE/LEASE clear the date. Every path has undo that restores the previous [VehicleCare].
 */
@Composable internal fun CareDueTask(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, snoozed: Set<String>, onSnoozed: (Set<String>) -> Unit,
    modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val followUp = LocalFollowUp.current
    val care = state.data.care
    val (odometer, _) = careOdometer(state, now)
    val alert = careDueAlerts(care, odometer, localDate(now), snoozed).firstOrNull() ?: return
    CareDueCard(alert, onSnooze = { onSnoozed(snoozeCare(context, careSnoozeKey(alert, care), now)) }, onDone = {
        act {
            when (alert.kind) {
                CareKind.SERVICE -> {
                    val record = repository.addMaintenance(MaintenanceKind.OIL, 0, null, "정비 완료", odometer)
                    val interval = care.serviceIntervalKm?.let { " · 다음 정비까지 ${"%,d".format(it)} km" }.orEmpty()
                    followUp(FollowUp("정비 완료로 기록했어요$interval", "실행 취소") { repository.removeMaintenance(record.id); repository.updateCare(care) })
                }
                CareKind.INSPECTION -> {
                    val record = repository.addMaintenance(MaintenanceKind.INSPECTION, 0, null, "정기검사 완료", odometer)
                    repository.updateCare(care.copy(inspectionDue = null))
                    followUp(FollowUp("검사 완료 · 다음 검사일은 설정 › 법인·차량에 적어 주세요", "실행 취소") { repository.removeMaintenance(record.id); repository.updateCare(care) })
                }
                CareKind.INSURANCE -> {
                    repository.updateCare(care.copy(insuranceDue = null))
                    followUp(FollowUp("보험 만기일을 지웠어요 · 새 만기일은 설정 › 법인·차량에", "실행 취소") { repository.updateCare(care) })
                }
                CareKind.LEASE -> {
                    repository.updateCare(care.copy(leaseEnd = null))
                    followUp(FollowUp("리스·렌트 만기일을 지웠어요 · 새 계약은 설정 › 법인·차량에", "실행 취소") { repository.updateCare(care) })
                }
            }
        }
    }, modifier)
}
