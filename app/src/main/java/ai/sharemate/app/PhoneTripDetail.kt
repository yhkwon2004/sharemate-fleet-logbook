package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/*
 * Full-screen trip detail on the phone, in the car's order: distance, the odometer the car read, places, purpose with
 * the source of that purpose spelled out (an automatic value is never dressed up as a human one), the driver, costs
 * that swipe away with an undo, the note, the history, and the incidents tied to this trip. Everything is read live
 * from the mirrored copy by id; if the car deletes the trip the screen says so and the shell pops it.
 */

/** The mandatory line under the purpose: what the value is and where it came from, with the evidence for automatic ones. */
internal fun purposeSourceLine(trip: Trip, history: List<Trip>): String {
    val head = "목적: ${trip.purpose.label}"
    if (trip.purpose == TripPurpose.UNCLASSIFIED) return "$head · 아직 아무도 정하지 않았어요"
    return when (trip.purposeSource) {
        EditSource.AUTO -> "$head · 자동 분류 — 같은 도착지 ${phoneSameDestinationCount(trip, history, trip.purpose)}회 기록을 근거로 차량이 채웠어요"
        EditSource.CARD -> "$head · 카드 자동 매칭 — 결제 기록을 근거로 채웠어요"
        EditSource.SUGGESTED -> "$head · 이전 기록 제안 — 같은 도착지의 지난 기록과 같게 골랐어요"
        EditSource.PHONE -> "$head · 휴대폰에서 직접 선택"
        EditSource.DRIVER -> "$head · 차량에서 직접 선택"
        EditSource.VOICE -> "$head · Gleo 음성으로 기록"
        EditSource.ASSISTANT -> "$head · 운행 비서(화면)로 기록"
        null -> "$head · 출처 기록 없음"
    }
}

/** True when the expense at [index] in the live copy is still the one the user swiped; the list may have moved under the finger. */
internal fun expenseStillAt(trip: Trip?, index: Int, snapshot: TripExpense): Boolean =
    trip?.expenses?.getOrNull(index)?.let { it.at == snapshot.at && it.amountWon == snapshot.amountWon && it.type == snapshot.type } == true

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhoneTripDetailScreen(tripId: String, data: TripLogData, paired: Boolean, busy: Boolean, send: (SyncCommand) -> Unit, onBack: () -> Unit,
    onPickDriver: () -> Unit, pendingEdit: PendingEdit? = null, classify: (Trip, TripPurpose) -> Unit = { trip, purpose -> send(SyncCommand.Classify(trip.id, purpose)) }) {
    val zone = remember { ZoneId.systemDefault() }
    val followUp = LocalFollowUp.current
    val tokens = phoneTokens()
    val trip = data.trips.firstOrNull { it.id == tripId } ?: data.open?.takeIf { it.id == tripId }
    val latestTrip by rememberUpdatedState(trip)
    Column(Modifier.fillMaxSize()) {
        if (trip == null) {
            PhoneRouteHeader("운행 상세", onBack = onBack)
            PhoneBanner(true, "이 운행은 차량에서 삭제됐어요 · 목록으로 돌아가요", Modifier.padding(horizontal = tokens.gutter, vertical = 8.dp), tone = MaterialTheme.colorScheme.errorContainer)
            return
        }
        val history = remember(data.trips) { phoneTrips(data) }
        val driver = data.drivers.firstOrNull { it.id == trip.driverId }
        val shownPurpose = pendingEdit?.purpose ?: trip.purpose
        val canEdit = paired && !trip.open
        var note by rememberSaveable(trip.id, trip.note) { mutableStateOf(trip.note) }
        var savingNote by remember(trip.id) { mutableStateOf(false) }
        LaunchedEffect(busy, trip.note) { if (!busy || trip.note == note) savingNote = false }
        var staleExpense by remember(trip.id) { mutableStateOf(false) }
        LaunchedEffect(trip.expenses) { staleExpense = false }

        PhoneRouteHeader("${dayLabel(localDate(trip.startAt, zone))} · ${hm(trip.startAt)}–${if (trip.open) "기록 중" else hm(trip.endAt ?: trip.updatedAt)}",
            subtitle = listOfNotNull(if (trip.demo) "시연 데이터" else null, if (trip.origin == TripOrigin.GAP_FILL) "누락 구간 보완" else null).joinToString(" · ").ifBlank { null },
            onBack = onBack, trailing = { PendingPurposeChip(trip.purpose, pendingEdit?.purpose) })
        // The detail keeps to the content width and centres on a wide window (spec B); the header above spans the window.
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.contentWidth().fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = tokens.gutter).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(tokens.cardGap + 4.dp)) {
            if (phoneEditedAfterClose(trip)) Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                Text("마감 후 수정 · 마감된 달의 기록을 고쳤어요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            } else closedMonthOf(data, trip, zone)?.let { Muted("마감된 달의 기록이에요 · 고치면 ‘마감 후 수정’으로 표시돼요") }

            Surface(shape = RoundedCornerShape(24.dp), color = Scene.glass, contentColor = Scene.text, border = Scene.edge, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(tokens.panelPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val distanceStyle = scaledDisplay(MaterialTheme.typography.displaySmall, MaterialTheme.typography.headlineMedium).copy(fontFeatureSettings = "tnum")
                    // A finished trip's distance is data and may change on sync, so it cross-fades; a recording trip is live telemetry and simply updates.
                    if (trip.open) Text(kmText(trip.distanceKm), style = distanceStyle)
                    else DigitChange(kmText(trip.distanceKm), style = distanceStyle, color = Scene.text)
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("⑤ 주행 전 계기판", kmText(trip.startOdometerKm), Modifier.weight(1f))
                        Stat("⑥ 주행 후 계기판", kmText(trip.endOdometerKm), Modifier.weight(1f))
                    }
                    Text(if (trip.origin == TripOrigin.GAP_FILL) "앞뒤 운행의 계기판 값으로 보완한 구간이에요." else "차량 계기판에서 읽은 값이라 수정할 수 없어요.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Muted(durationText(trip.durationMillis) + (trip.recovered.takeIf { it }?.let { " · 종료 시각 추정" } ?: ""))
                }
            }
            if (trip.recovered) InlineError("운행 중 앱이 중단되어 마지막 수신 시각으로 종료했어요. 종료 시각을 확인해 주세요.")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("출발", trip.startAddress ?: "주소 미기록")
                Stat("도착", listOfNotNull(trip.destination, trip.endAddress).distinct().joinToString(" · ").ifBlank { if (trip.open) "기록 중" else "주소 미기록" })
            }

            // Field labels ("운행 목적", "⑩ 비고") keep the grey of the Stat labels around them; the section labels below take the brand default.
            Eyebrow("운행 목적", Scene.textSecondary)
            PhonePurposeButtons(current = shownPurpose, enabled = canEdit && !busy) { purpose -> if (purpose != shownPurpose) classify(trip, purpose) }
            Text(if (pendingEdit?.purpose != null) "차량에 보내는 중 · ${pendingEdit.purpose.label}" else purposeSourceLine(trip, history),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (!paired) Muted("차량과 연결되면 목적을 바꿀 수 있어요") else if (trip.open) Muted("운행이 끝나면 목적을 정할 수 있어요")

            Row(verticalAlignment = Alignment.CenterVertically) {
                Stat("④ 사용자", driver?.label ?: "미지정", Modifier.weight(1f))
                TextButton(onClick = onPickDriver, enabled = paired && !busy && data.drivers.isNotEmpty(), modifier = Modifier.heightIn(min = 48.dp)) { Text("변경") }
            }
            if (paired && data.drivers.isEmpty()) Muted("차량 설정 › 사용자에서 직원을 등록하면 여기서 지정할 수 있어요")

            PhoneSectionTitle("운행 비용", trailing = if (trip.expenses.isNotEmpty()) formatWon(trip.expenseWon) else null)
            if (trip.expenses.isEmpty()) Muted("등록된 비용이 없어요")
            trip.expenses.forEachIndexed { index, expense ->
                key(index, expense.at, expense.amountWon) {
                    ExpenseSwipeRow(expense, enabled = canEdit && !busy,
                        onDelete = {
                            if (expenseStillAt(latestTrip, index, expense)) {
                                staleExpense = false
                                send(SyncCommand.RemoveExpense(trip.id, index))
                                followUp(FollowUp("${expense.type.label} ${formatWon(expense.amountWon)}을 삭제했어요", "실행 취소") {
                                    val charge = expense.cardChargeId
                                    send(if (charge != null) SyncCommand.ConfirmCharge(charge, trip.id) else SyncCommand.AddExpense(trip.id, expense.type, expense.amountWon.coerceIn(1, 10_000_000)))
                                })
                            } else staleExpense = true
                        })
                }
            }
            if (staleExpense) InlineError("목록이 바뀌었어요. 다시 확인해 주세요")
            if (canEdit && trip.expenses.isNotEmpty()) Muted("왼쪽으로 밀면 삭제돼요 · 바로 실행 취소할 수 있어요", Modifier.padding(horizontal = 4.dp))
            if (canEdit) ExpenseAddRow(enabled = !busy) { type, amount ->
                send(SyncCommand.AddExpense(trip.id, type, amount))
                followUp(FollowUp("${type.label} ${formatWon(amount)}을 추가했어요", "실행 취소") {
                    val index = latestTrip?.expenses?.indexOfLast { it.type == type && it.amountWon == amount && it.source == EditSource.PHONE } ?: -1
                    if (index >= 0) send(SyncCommand.RemoveExpense(trip.id, index))
                    else followUp(FollowUp("아직 차량에서 돌아오지 않았어요 · 운행 비용 목록에서 밀어 삭제해 주세요"))
                })
            }

            Eyebrow("⑩ 비고", Scene.textSecondary)
            OutlinedTextField(note, { if (it.length <= 300) note = it }, Modifier.fillMaxWidth(), label = { Text("방문처·업무 내용") }, enabled = paired,
                supportingText = { Text("${note.length} / 300") }, shape = RoundedCornerShape(14.dp), minLines = 2, colors = sceneTextFieldColors())
            if (paired) PhoneLoadingButton("메모 저장", loading = savingNote, loadingLabel = "보내는 중…", enabled = note.trim() != trip.note, outlined = true, modifier = Modifier.fillMaxWidth()) {
                savingNote = true
                send(SyncCommand.Note(trip.id, note.trim()))
            }

            if (trip.history.isNotEmpty()) {
                Eyebrow("수정 이력")
                trip.history.takeLast(8).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            val linked = remember(data.incidents, trip.id) { data.incidents.filter { it.tripId == trip.id && it.demo == trip.demo }.sortedByDescending { it.at } }
            if (linked.isNotEmpty()) {
                Eyebrow("사고·특이사항 기록")
                linked.forEach { incident ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${incident.kind.label} · ${hm(incident.at)} · ${incident.status.label}", style = MaterialTheme.typography.bodyMedium)
                            if (incident.note.isNotBlank()) Muted(incident.note)
                            if (incident.photos.isNotEmpty()) Muted("사진 ${incident.photos.size}장")
                        }
                        if (incident.status == IncidentStatus.CLOSED && paired) TextButton(onClick = {
                            send(SyncCommand.UpdateIncident(incident.id, null, IncidentStatus.OPEN, null))
                            followUp(FollowUp("다시 열었어요 · 홈의 할 일에 나타나요", "되돌리기") { send(SyncCommand.UpdateIncident(incident.id, null, IncidentStatus.CLOSED, null)) })
                        }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("다시 열기") }
                    }
                }
            }
        }
        }
    }
}

/** One cost line that swipes left to delete; the background says "삭제", the row itself never dismisses (the mirror removes it). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ExpenseSwipeRow(expense: TripExpense, enabled: Boolean, onDelete: () -> Unit) {
    val latestDelete by rememberUpdatedState(onDelete)
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { value -> if (value == SwipeToDismissBoxValue.EndToStart) latestDelete(); false }, positionalThreshold = { it * 0.4f })
    val colors = MaterialTheme.colorScheme
    val label = "${expense.type.label} ${formatWon(expense.amountWon)}"
    SwipeToDismissBox(state, modifier = Modifier.clip(RoundedCornerShape(16.dp)), enableDismissFromStartToEnd = false, enableDismissFromEndToStart = enabled, gesturesEnabled = enabled,
        backgroundContent = {
            val armed = state.targetValue == SwipeToDismissBoxValue.EndToStart
            Box(Modifier.fillMaxSize().background(colors.errorContainer).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Text("삭제", style = MaterialTheme.typography.labelLarge, fontWeight = if (armed) FontWeight.Bold else FontWeight.Medium, color = colors.onErrorContainer)
            }
        }) {
        Row(Modifier.fillMaxWidth().background(Scene.glass).heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) { if (enabled) customActions = listOf(CustomAccessibilityAction("$label 삭제") { latestDelete(); true }) },
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = MaterialTheme.typography.bodyLarge)
                Muted(listOfNotNull(expense.merchant, expense.source.label, hm(expense.at)).joinToString(" · "))
            }
        }
    }
}

/** Type chips, an amount, and 추가; the range error sits under the field. */
@Composable private fun ExpenseAddRow(enabled: Boolean, onAdd: (ExpenseType, Long) -> Unit) {
    var type by rememberSaveable { mutableStateOf(ExpenseType.TOLL.name) }
    var amount by rememberSaveable { mutableStateOf("") }
    val value = amount.toLongOrNull()
    val error = if (amount.isNotEmpty() && (value == null || value !in 1..10_000_000)) "1원부터 1,000만 원까지 입력할 수 있어요" else null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ExpenseType.entries.forEach { candidate ->
                val chosen = type == candidate.name
                FilterChip(selected = chosen, onClick = { type = candidate.name }, label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall) },
                    colors = sceneChipColors(), border = sceneChipBorder(chosen), modifier = Modifier.heightIn(min = 48.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), singleLine = true, isError = error != null,
                label = { Text("금액 (원)") }, supportingText = { Text(error ?: value?.let(::formatWon) ?: "1원~1,000만 원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(14.dp), colors = sceneTextFieldColors())
            PhoneLoadingButton("추가", loading = false, enabled = enabled && error == null && value != null, modifier = Modifier.semantics { contentDescription = "경비 추가" }) {
                onAdd(ExpenseType.valueOf(type), value!!); amount = ""
            }
        }
    }
}
