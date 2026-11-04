@file:OptIn(ExperimentalMaterial3Api::class)

package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/*
 * The phone's company vehicles (2.9): a header chip that names the selected car and how it is linked, a sheet listing
 * every registered vehicle, and the two ways to add one (pair with the car, or type its plate). Selecting, adding and
 * removing are the caller's calls into PhoneSyncClient / FleetStore; this file only shows the list and asks before a
 * removal, the one step here that cannot be undone from the phone.
 */

// ───────────────────────── pure copy (unit-tested) ─────────────────────────

/** How the selected vehicle is linked, for the dot on the header chip. */
internal enum class FleetLinkTone(val label: String) {
    LIVE("실시간 연결"), WAITING("연결 대기"), MANUAL("직접 등록 · 연결 전"), NONE("등록된 차량 없음")
}

internal fun fleetLinkTone(vehicle: FleetVehicle?, connected: Boolean): FleetLinkTone = when {
    vehicle == null -> FleetLinkTone.NONE
    !vehicle.paired -> FleetLinkTone.MANUAL
    connected -> FleetLinkTone.LIVE
    else -> FleetLinkTone.WAITING
}

/** "방금" / "N분 전" / "N시간 전" / "M월 d일"; null when the car never answered. A time ahead of [now] reads as "방금". */
internal fun fleetLastSeenText(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (at <= 0) return null
    val minutes = ((now - at) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        minutes < 24 * 60 -> "${minutes / 60}시간 전"
        else -> Instant.ofEpochMilli(at).atZone(zone).let { "${it.monthValue}월 ${it.dayOfMonth}일" }
    }
}

/**
 * The status line under a vehicle in the sheet. The selected paired car speaks from its copy (in use, reserved, free);
 * when the link is down that copy is the last one received, which the line says. Other paired cars show when they last
 * answered, and a typed vehicle says it is not connected yet.
 */
internal fun fleetStatusLine(vehicle: FleetVehicle, selected: Boolean, connected: Boolean, selectedData: TripLogData?, now: Long,
    zone: ZoneId = ZoneId.systemDefault()): String {
    val seen = fleetLastSeenText(vehicle.lastSeenAt, now, zone)
    return when {
        !vehicle.paired -> "아직 연결하지 않은 차량"
        selected && selectedData != null && connected -> availabilityText(vehicleAvailability(selectedData, now), now, zone)
        selected && selectedData != null && seen != null -> "연결 대기 · ${availabilityText(vehicleAvailability(selectedData, now), now, zone)}"
        selected -> "연결 대기 · 아직 받은 기록 없음"
        seen != null -> "연결됨 · 마지막 확인 $seen"
        else -> "연결됨 · 아직 받은 기록 없음"
    }
}

/** "아이오닉 5 · 쉐어메이트", or a plain note when neither was entered. */
internal fun fleetVehicleSubtitle(vehicle: FleetVehicle): String =
    listOf(vehicle.model, vehicle.company).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { "차종·회사 미입력" }

/** What a screen reader hears for the header chip: "차량 선택, 현재 12가 3456, 상태 실시간 연결 · 사용 가능 · …". */
internal fun fleetSwitcherDescription(fleet: FleetState, data: TripLogData?, connected: Boolean, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val vehicle = fleet.selected
    val tone = fleetLinkTone(vehicle, connected)
    val availability = if (vehicle?.paired == true && data != null && connected) " · ${availabilityText(vehicleAvailability(data, now), now, zone)}" else ""
    return "차량 선택, 현재 ${vehicle?.title ?: "선택한 차량 없음"}, 상태 ${tone.label}$availability"
}

/** What the removal dialog explains, by how the vehicle is registered. */
internal fun fleetRemoveText(vehicle: FleetVehicle): String = if (vehicle.paired)
    "이 차량과의 연결도 끊어요. 휴대폰에 받아 둔 이 차량의 사본도 지워요. 차량에 저장된 운행 기록은 그대로예요. 다시 쓰려면 차량 화면의 새 6자리 번호로 연결해야 해요."
else "휴대폰에 등록한 차량 정보만 지워요."

/** The form's own check before anything is sent: the first problem as one sentence, or null when it can be submitted. */
internal fun vehicleFormError(plate: String, model: String, company: String): String? =
    runCatching { validateFleetVehicle(plate, model, company) }.exceptionOrNull()?.let { it.message ?: "입력 내용을 확인해 주세요." }

/** Messages about the plate (format, already registered) go under the plate field; the rest under the button. */
internal fun fleetErrorIsPlate(message: String): Boolean = message.contains("차량번호")

internal const val FLEET_PLATE_MAX = 12
internal const val FLEET_MODEL_MAX = 40
internal const val FLEET_COMPANY_MAX = 60

private val fleetLive = Color(0xFF45E0A8)

private fun FleetLinkTone.dot(): Color = when (this) {
    FleetLinkTone.LIVE -> fleetLive
    FleetLinkTone.WAITING -> Midnight.warn
    FleetLinkTone.MANUAL, FleetLinkTone.NONE -> Midnight.textMuted
}

// ───────────────────────── header chip ─────────────────────────

/**
 * A compact glass chip for the phone header (about 150 dp): vehicle icon, the selected plate, a status dot (green when
 * the paired car answers, amber while it does not, grey for a typed vehicle) and ▾. Tapping opens [PhoneFleetSheet].
 */
@Composable internal fun PhoneVehicleSwitcher(modifier: Modifier = Modifier, fleet: FleetState, data: TripLogData, link: PhoneLink, now: Long, onClick: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val vehicle = fleet.selected
    val tone = fleetLinkTone(vehicle, link.connected)
    val description = fleetSwitcherDescription(fleet, data, link.connected, now, zone)
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Midnight.glass, contentColor = Midnight.text, border = Midnight.edge,
        modifier = modifier.heightIn(min = 48.dp).widthIn(max = 168.dp).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }) {
        Row(Modifier.heightIn(min = 48.dp).padding(start = 12.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(painterResource(R.drawable.ic_vehicle), null, Modifier.size(18.dp), tint = Midnight.textSecondary)
            Text(vehicle?.title ?: "차량 선택", style = MaterialTheme.typography.labelLarge, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {})
            Box(Modifier.size(8.dp).background(tone.dot(), CircleShape))
            Text("▾", style = MaterialTheme.typography.labelMedium, color = Midnight.textMuted, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

// ───────────────────────── vehicle list sheet ─────────────────────────

/**
 * Every company vehicle on this phone. A row tap selects it (and closes the sheet); 삭제 asks first, since it unpairs a
 * paired car and drops its copy. [selectedData] is the selected vehicle's copy and [link] its live link. The caller
 * runs [onSelect] / [onRemove] (PhoneSyncClient.switchTo / removeVehicle) and reports the outcome through LocalFollowUp.
 */
@Composable internal fun PhoneFleetSheet(modifier: Modifier = Modifier, fleet: FleetState, selectedData: TripLogData, link: PhoneLink, now: Long, busy: Boolean,
    onSelect: (String) -> Unit, onAdd: () -> Unit, onRemove: (String) -> Unit, onDismiss: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var removing by remember { mutableStateOf<FleetVehicle?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = modifier, sheetState = sheet, containerColor = Midnight.rail, contentColor = Midnight.text) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { heading() }, verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("회사 차량", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Midnight.text)
                Text("${fleet.vehicles.size}대", style = MaterialTheme.typography.labelLarge, color = Midnight.brand, modifier = Modifier.padding(bottom = 3.dp))
            }
            if (fleet.vehicles.isEmpty()) {
                PhoneEmpty("등록된 회사 차량이 없어요", detail = "차량과 연결하거나 차량번호를 직접 등록해 주세요.")
            } else {
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(fleet.vehicles, key = { it.id }) { vehicle ->
                        val selected = vehicle.id == fleet.selectedId
                        FleetVehicleRow(vehicle, selected, fleetStatusLine(vehicle, selected, selected && link.connected, if (selected) selectedData else null, now, zone), busy,
                            onSelect = { if (!selected) onSelect(vehicle.id); onDismiss() }, onRemove = { removing = vehicle })
                    }
                }
            }
            val full = fleet.vehicles.size >= MAX_FLEET_VEHICLES
            PhoneLoadingButton("차량 추가", loading = false, enabled = !busy && !full, modifier = Modifier.fillMaxWidth(), onClick = onAdd)
            if (full) Muted("차량은 ${MAX_FLEET_VEHICLES}대까지 등록할 수 있어요. 쓰지 않는 차량을 삭제한 뒤 추가해 주세요.")
        }
    }
    removing?.let { vehicle ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("${vehicle.title} 차량을 삭제할까요?") },
            text = { Text(fleetRemoveText(vehicle)) },
            confirmButton = { TextButton(onClick = { removing = null; onRemove(vehicle.id) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("삭제", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { removing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
    }
}

@Composable private fun FleetVehicleRow(vehicle: FleetVehicle, selected: Boolean, status: String, busy: Boolean, onSelect: () -> Unit, onRemove: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(shape = shape, color = if (selected) Midnight.glow.copy(alpha = .08f) else Midnight.glass, contentColor = Midnight.text,
        border = if (selected) BorderStroke(1.dp, Midnight.glow.copy(alpha = .7f)) else Midnight.edge, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).heightIn(min = 72.dp)
                .clickable(enabled = !busy, role = Role.Button, onClickLabel = if (selected) "닫기" else "이 차량 선택", onClick = onSelect)
                .semantics(mergeDescendants = true) {
                    this.selected = selected
                    contentDescription = "${vehicle.title}, ${fleetVehicleSubtitle(vehicle)}, $status" + if (selected) ", 선택됨" else ""
                }
                .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(40.dp).background(if (selected) Midnight.glow.copy(alpha = .16f) else Color.White.copy(alpha = .06f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_vehicle), null, Modifier.size(22.dp), tint = if (selected) Midnight.glow else Midnight.textSecondary)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(vehicle.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(fleetVehicleSubtitle(vehicle), style = MaterialTheme.typography.labelMedium, color = Midnight.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(status, style = MaterialTheme.typography.labelMedium, color = if (selected) Midnight.glow else Midnight.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (selected) Icon(Icons.Default.Check, null, Modifier.size(22.dp), tint = Midnight.glow)
            }
            TextButton(onClick = onRemove, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).padding(end = 4.dp)
                .semantics { contentDescription = "${vehicle.title} 삭제" }) {
                Text("삭제", color = Midnight.textSecondary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

// ───────────────────────── add a vehicle ─────────────────────────

private enum class AddMode(val label: String) { PAIR("차량과 연결"), MANUAL("직접 등록") }

/**
 * Two ways to add a company vehicle: pair with the car (six-digit code on the car screen, same Wi-Fi or hotspot) or type
 * its plate. Success closes the sheet with a bar; a failure stays inline. While a request is in flight the sheet cannot
 * be swiped away, so a half-finished pairing is not cancelled by a stray gesture. [pairError] is an earlier pairing
 * failure the caller wants shown; the sheet's own failures replace it.
 */
@Composable internal fun PhoneAddVehicleSheet(modifier: Modifier = Modifier, busy: Boolean, pairError: String?, onPair: suspend (endpoint: String, code: String) -> Unit,
    onAddManual: suspend (plate: String, model: String, company: String) -> Unit, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val followUp = LocalFollowUp.current
    var mode by rememberSaveable { mutableStateOf(AddMode.PAIR) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val latestWorking by rememberUpdatedState(working)
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !latestWorking })
    ModalBottomSheet(onDismissRequest = { if (!working) onDismiss() }, modifier = modifier, sheetState = sheet, containerColor = Midnight.rail, contentColor = Midnight.text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("차량 추가", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Midnight.text, modifier = Modifier.semantics { heading() })
            FleetAddModeSwitch(mode, enabled = !working) { mode = it }
            when (mode) {
                AddMode.PAIR -> {
                    Muted("차량 화면에 뜨는 6자리 연결 번호가 필요해요. 휴대폰과 차량이 같은 Wi-Fi나 핫스팟에 있어야 해요.")
                    PhoneConnectCard(busy || working, error ?: pairError) { endpoint, code ->
                        error = null
                        scope.launch {
                            working = true
                            try {
                                onPair(endpoint, code)
                                followUp(FollowUp("차량과 연결됐어요 · 기록을 받고 있어요"))
                                onDismiss()
                            } catch (e: CancellationException) { throw e }
                            catch (e: IllegalArgumentException) { error = e.message ?: PAIR_UNREACHABLE }
                            catch (_: Exception) { error = PAIR_UNREACHABLE }
                            finally { working = false }
                        }
                    }
                }
                AddMode.MANUAL -> {
                    Muted("차량번호로 먼저 목록에 올려요. 운행 기록은 나중에 이 차량과 연결하면 받아요.")
                    PhoneManualVehicleForm(busy = busy || working) { plate, model, company ->
                        working = true
                        try { onAddManual(plate, model, company) } finally { working = false }
                        followUp(FollowUp("${plate.trim()} 차량을 등록했어요"))
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable private fun FleetAddModeSwitch(mode: AddMode, enabled: Boolean, onChange: (AddMode) -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = Midnight.glass, border = Midnight.edge, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            AddMode.entries.forEach { option ->
                val on = option == mode
                Box(Modifier.weight(1f).heightIn(min = 48.dp).background(if (on) Midnight.glow.copy(alpha = .18f) else Color.Transparent, RoundedCornerShape(50))
                    .selectable(selected = on, enabled = enabled, role = Role.Tab) { onChange(option) }, contentAlignment = Alignment.Center) {
                    Text(option.label, style = MaterialTheme.typography.labelLarge, color = if (on) Midnight.glow else Midnight.textSecondary,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Registers a vehicle by its details: plate (required, e.g. 12가 3456), model and company. The plate is checked with
 * validateFleetVehicle once the first submit was tried; [onSubmit] throwing IllegalArgumentException ("이미 등록된
 * 차량번호예요.") lands inline. The fields clear after a successful submit. Usable on its own (e.g. in onboarding).
 */
@Composable internal fun PhoneManualVehicleForm(modifier: Modifier = Modifier, busy: Boolean, onSubmit: suspend (plate: String, model: String, company: String) -> Unit) {
    val scope = rememberCoroutineScope()
    var plate by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var company by rememberSaveable { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var submitError by remember { mutableStateOf<String?>(null) }
    val checkError = if (attempted) vehicleFormError(plate, model, company) else null
    val shown = submitError ?: checkError
    val plateError = shown?.takeIf { fleetErrorIsPlate(it) }
    val otherError = shown?.takeIf { !fleetErrorIsPlate(it) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Field("차량번호", plate, { if (it.length <= FLEET_PLATE_MAX) { plate = it; submitError = null } }, hint = "예: 12가 3456", error = plateError)
        Field("차종 (선택)", model, { if (it.length <= FLEET_MODEL_MAX) { model = it; submitError = null } }, hint = "예: 아이오닉 5")
        Field("회사명 (선택)", company, { if (it.length <= FLEET_COMPANY_MAX) { company = it; submitError = null } }, done = true)
        InlineError(otherError, Modifier.padding(horizontal = 4.dp))
        PhoneLoadingButton("차량 등록", loading = working, loadingLabel = "등록하는 중…", enabled = !busy && plate.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            attempted = true
            submitError = null
            if (vehicleFormError(plate, model, company) != null) return@PhoneLoadingButton
            scope.launch {
                working = true
                try {
                    onSubmit(plate.trim(), model.trim(), company.trim())
                    plate = ""; model = ""; company = ""; attempted = false
                } catch (e: CancellationException) { throw e }
                catch (e: IllegalArgumentException) { submitError = e.message ?: "입력 내용을 확인해 주세요." }
                catch (_: Exception) { submitError = "차량을 등록하지 못했어요. 다시 시도해 주세요." }
                finally { working = false }
            }
        }
    }
}
