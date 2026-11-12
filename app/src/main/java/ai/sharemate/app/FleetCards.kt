package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Cards that give the car app its reason to exist: things only the vehicle can do at the moment they happen.
 * Each card captures vehicle data by itself and asks the driver for one decision at most. 2.10: every colour here is a
 * scene token, so the same cards read on the day glass and in the night palette.
 */

private fun clock(at: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
private fun km(value: Double?) = value?.let { String.format(Locale.KOREA, "%,.1f km", it) } ?: "—"

internal fun openIncidents(data: TripLogData): List<Incident> =
    data.incidents.filter { it.demo == data.profile.demoMode && it.status == IncidentStatus.OPEN }.sortedByDescending { it.at }

internal fun recentIncidents(data: TripLogData, limit: Int = 12): List<Incident> =
    data.incidents.filter { it.demo == data.profile.demoMode }.sortedByDescending { it.at }.take(limit)

/** Pairing code shown as "123 456" so it reads at arm's length and over a phone call. */
internal fun pairingDisplay(code: String) = code.chunked(3).joinToString(" ")

internal fun pairingRemaining(info: SyncInfo, now: Long): String? =
    info.pairingCode?.let { ((info.pairingExpiresAt - now) / 1000).coerceAtLeast(0) }?.let { "%d:%02d".format(it / 60, it % 60) }

/** The car caps a staggered entrance at five rows (spec C); later rows are simply there. */
internal const val CAR_APPEAR_CAP = 5

/**
 * Press feedback for a surface the driver taps on the car (spec C): the surface settles to .98 while held ([pressable]) and
 * no ripple draws (indication = null), as on the car's segmented controls and stepper arrows. A car card is built as
 * `Surface(modifier.carTap(source) { … })`, never `Surface(onClick = …)`, which brings Material's ripple back. The same
 * [source] goes nowhere else. [label] is the click label TalkBack reads.
 */
@Composable internal fun Modifier.carTap(source: MutableInteractionSource, enabled: Boolean = true, role: Role = Role.Button, label: String? = null,
    onClick: () -> Unit): Modifier =
    pressable(source, .98f).clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = label, role = role, onClick = onClick)

// ───────────────────────── 사고·특이사항 ─────────────────────────

private fun incidentCaption(kind: IncidentKind) = when (kind) {
    IncidentKind.ACCIDENT -> "119 · 112 안내"
    IncidentKind.DAMAGE -> "촬영 안내"
    IncidentKind.BREAKDOWN -> "긴급출동 안내"
    IncidentKind.OTHER -> "메모로 남기기"
}

/** The dock's lead: the warning mark, 사고·특이사항 and its one line, 224 dp wide beside the buttons. */
private val DOCK_LEAD = 224.dp

/**
 * One tap at the scene (2.13, 홈's incident dock at the foot of the page, which never moves): the car fills time, odometer,
 * place, trip and driver, the driver only chooses what happened. An 80-dp card: the lead (사고·특이사항, and whether the record
 * reaches the phone too), then four 56-dp outlined stadiums, 사고 in the negative. A caption sits beside its label from 196 dp
 * of button ([incidentCaptionBeside]) and under it, in a 72-dp button, below that: it is the safety hint, so it stays on
 * screen. Under 560 dp the lead takes its own row over two rows of two. A report answers "{종류} 기록됨 · 실행 취소": the
 * card that appears under the greeting already says what was saved.
 */
@Composable internal fun IncidentDock(data: TripLogData, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    val fontScale = LocalDensity.current.fontScale
    val report: (IncidentKind) -> Unit = { kind ->
        act {
            val incident = repository.reportIncident(kind)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            followUp(FollowUp("${kind.label} 기록됨", HomeCopy.UNDO) { repository.deleteIncident(incident.id) })
        }
    }
    val line = if (data.profile.phoneSync) HomeCopy.DOCK_LINE else HomeCopy.DOCK_LINE_OFF
    val lead: @Composable (Modifier) -> Unit = { place ->
        Row(place.clearAndSetSemantics { contentDescription = HomeCopy.SPOKEN_DOCK; heading() }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(24.dp), tint = Car.skin.icon)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(HomeCopy.DOCK_TITLE, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CarLine(line)
            }
        }
    }
    CarCard(modifier, padding = 12.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val kinds = IncidentKind.entries
            if (maxWidth < 560.dp) {
                val beside = incidentCaptionBeside((maxWidth - gap) / 2, fontScale)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    lead(Modifier.fillMaxWidth())
                    kinds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) { pair.forEach { IncidentButton(it, beside, Modifier.weight(1f)) { report(it) } } }
                    }
                }
            } else {
                val beside = incidentCaptionBeside((maxWidth - DOCK_LEAD - gap * kinds.size) / kinds.size, fontScale)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    lead(Modifier.width(DOCK_LEAD))
                    kinds.forEach { IncidentButton(it, beside, Modifier.weight(1f)) { report(it) } }
                }
            }
        }
    }
}

/**
 * One kind on the dock: a 56-dp outlined stadium (72 with the caption under the label), the label in labelLarge and its
 * caption in textMuted; 사고 takes the negative for both label and outline (5.60 / 4.81). It settles to .98 with the press fill.
 * TalkBack hears "사고 기록, 119 · 112 안내".
 */
@Composable private fun IncidentButton(kind: IncidentKind, captionBeside: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val urgent = kind == IncidentKind.ACCIDENT
    val ink = if (urgent) Scene.negative else Scene.text
    val outline = if (urgent) Scene.negative else Car.skin.chipBorder
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(modifier.heightIn(min = if (captionBeside) 56.dp else 72.dp).carTap(source, label = "${kind.label} 기록", onClick = onClick).clip(shape).border(1.dp, outline, shape)
        .pressFill(source).semantics { contentDescription = "${kind.label} 기록, ${incidentCaption(kind)}" }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        val label: @Composable () -> Unit = { Text(kind.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1) }
        val caption: @Composable () -> Unit = {
            Text(incidentCaption(kind), style = MaterialTheme.typography.labelMedium, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (captionBeside) Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            label()
            Spacer(Modifier.width(8.dp))
            caption()
        } else Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            label()
            caption()
        }
    }
}

/**
 * Closes a report with a way back (2.13: 실행 취소 everywhere). A breakdown is offered a second life as a maintenance record
 * (남기기 beside the undo), because this is the only moment that connection is obvious; the reopen also stays one tap away in
 * the vehicle tab.
 */
internal fun closeIncident(incident: Incident, act: Act, repository: TripLogRepository, followUp: (FollowUp) -> Unit) = act {
    repository.updateIncident(incident.id, status = IncidentStatus.CLOSED)
    val reopen: suspend () -> Unit = { repository.updateIncident(incident.id, status = IncidentStatus.OPEN) }
    followUp(if (incident.kind == IncidentKind.BREAKDOWN) FollowUp("처리 완료 · 고장·경고를 정비 항목으로 남길까요?", HomeCopy.UNDO, second = FollowUpAction("남기기") {
        val record = repository.addMaintenance(MaintenanceKind.REPAIR, 0, null, incident.note.ifBlank { "고장·경고 특이사항에서 남김" }.take(300), incident.odometerKm)
        followUp(FollowUp("정비 항목으로 남겼어요 · 차량 탭에서 볼 수 있어요", HomeCopy.UNDO) { repository.removeMaintenance(record.id) })
    }, perform = reopen) else FollowUp("처리 완료로 바꿨어요", HomeCopy.UNDO, perform = reopen))
}

/** Removes a report and offers it back; photo files are gone for good, and the bar says so when there were any. */
internal fun deleteIncident(incident: Incident, act: Act, repository: TripLogRepository, followUp: (FollowUp) -> Unit) = act {
    repository.deleteIncident(incident.id)
    val photos = incident.photos.size
    followUp(FollowUp(if (photos > 0) "특이사항 기록과 사진 ${photos}장을 삭제했어요 · 사진은 복구되지 않아요" else "특이사항 기록을 삭제했어요", "실행 취소") {
        repository.restoreIncident(incident)
    })
}

/**
 * Thumbnails for a report's photos. A photo whose file is not on this device yet shows a placeholder and asks [missing]
 * to fetch it; [version] bumps when a fetch lands so the strip re-reads the files. The remove mark is a 28-dp disc whose
 * touch area is [tapTarget] square (48 on the phone, 56 on the car) and presses like the rest of its device: .98 with no
 * ripple on the car ([carTap]), .97 with Material's bounded ripple on the phone. The scrim under it stays black on purpose:
 * it sits on a photograph, not on the scene.
 */
@Composable internal fun PhotoStrip(photos: List<IncidentPhoto>, store: PhotoStore, version: Int = 0, size: Dp = 96.dp,
    missing: ((String) -> Unit)? = null, onRemove: ((IncidentPhoto) -> Unit)? = null, tapTarget: Dp = 48.dp) {
    if (photos.isEmpty()) return
    val placeholder = Scene.rail
    val car = LocalLayout.current.startAnchored
    Row(Modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "첨부 사진 ${photos.size}장" }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        photos.forEach { photo ->
            val file = store.file(photo.id)
            val bitmap by produceState<android.graphics.Bitmap?>(null, photo.id, version) { value = withContext(Dispatchers.IO) { thumbnail(file) } }
            LaunchedEffect(photo.id, version) { if (!file.exists()) missing?.invoke(photo.id) }
            Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(placeholder)) {
                bitmap?.let { Image(it.asImageBitmap(), "사진 ${clock(photo.at)} · ${photo.source.label}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Text(if (missing != null) "받는 중" else "사진 없음", style = MaterialTheme.typography.labelSmall, color = Scene.textSecondary,
                        modifier = Modifier.align(Alignment.Center))
                if (onRemove != null) {
                    val source = remember(photo.id) { MutableInteractionSource() }
                    val area = Modifier.align(Alignment.TopEnd).size(tapTarget.coerceAtMost(size))
                    val press = if (car) area.carTap(source, label = "사진 삭제") { onRemove(photo) }
                        else area.pressable(source, .97f).clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button,
                            onClickLabel = "사진 삭제") { onRemove(photo) }
                    Box(press.semantics { contentDescription = "사진 삭제" }, contentAlignment = Alignment.TopEnd) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White, modifier = Modifier.padding(4.dp).size(28.dp)) {
                            Text("×", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {})
                        }
                    }
                }
            }
        }
    }
}

/**
 * History for the vehicle page (2.12): the count as a tag beside the title, then one row per report with its state as a dot
 * (open in the negative, done in positive) and in words for TalkBack; a report closed too early can be reopened, and the
 * reopen's way back is 실행 취소, the one word for undo (2.13). Rows arrive staggered the first time only. The title's mark is
 * the line warning triangle of the car's icon family (2.13, ic_warning), where 2.12 drew Material's solid one.
 */
@Composable internal fun IncidentHistory(data: TripLogData, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val items = recentIncidents(data)
    val followUp = LocalFollowUp.current
    // First composition only: rows a sync adds later, and the list on a return to this tab, are simply there.
    var introduced by rememberSaveable { mutableStateOf(false) }
    val stagger = remember { !introduced }
    val initial = remember { items.map { it.id }.toSet() }
    LaunchedEffect(Unit) { introduced = true }
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.INCIDENTS_TITLE, icon = painterResource(R.drawable.ic_warning), trailing = {
            CarTag("${data.incidents.count { it.demo == data.profile.demoMode }}건")
        })
    }) {
        if (items.isEmpty()) CarLine(VehicleCopy.INCIDENTS_EMPTY)
        else Column {
            items.forEachIndexed { index, incident ->
                val open = incident.status == IncidentStatus.OPEN
                val stamp = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(incident.at))
                val detail = listOfNotNull(incident.odometerKm?.let(::km), incident.address, incident.photos.size.takeIf { it > 0 }?.let { "사진 ${it}장" },
                    incident.note.ifBlank { null }).joinToString(" · ").ifBlank { "상세 없음" }
                // The rule above a row arrives with it.
                Appear(index = index.coerceAtMost(CAR_APPEAR_CAP - 1), animate = stagger && incident.id in initial) {
                    Column {
                        if (index > 0) CarDivider()
                        CarRow("${incident.kind.label} · $stamp", line = detail, state = incident.status.label,
                            lead = { Box(Modifier.size(12.dp).clip(CircleShape).background(if (open) Scene.negative else Scene.positive)) },
                            trailing = {
                                CarPillButton(if (open) "완료" else "다시 열기", {
                                    if (open) closeIncident(incident, act, repository, followUp)
                                    else act {
                                        repository.updateIncident(incident.id, status = IncidentStatus.OPEN)
                                        followUp(FollowUp("${incident.kind.label} 기록을 다시 열었어요 · 홈에 대처 안내가 떠요", "실행 취소") { repository.updateIncident(incident.id, status = IncidentStatus.CLOSED) })
                                    }
                                }, description = "${incident.kind.label} $stamp ${if (open) "처리 완료" else "다시 열기"}")
                            })
                    }
                }
            }
        }
    }
}

// ───────────────────────── 차량 카드 · 휴대폰 연결 ─────────────────────────

/** Identity chips read from the car itself; shows nothing until the vehicle has reported something. */
@Composable internal fun VehicleChips(state: TripLogState, modifier: Modifier = Modifier) {
    val card = state.data.vehicle
    val live = state.live?.takeIf { it.demo == state.data.profile.demoMode && System.currentTimeMillis() - it.at in 0..45_000 }
    val charging = live?.charging == true
    val chips = listOfNotNull(
        (state.vehicleModel ?: card.model)?.takeIf { it.isNotBlank() },
        live?.socPercent?.let { "배터리 $it%" } ?: card.socPercent?.takeUnless { state.data.profile.demoMode }?.let { "마지막 저장 잔량 $it%" },
        if (charging) "충전 중" else null,
        validVin(live?.vin ?: card.vin)?.takeLast(6)?.let { "VIN …$it" },
    )
    if (chips.isEmpty()) return
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "차량 정보 " + chips.joinToString(", ") }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            val charge = chip == "충전 중"
            Surface(shape = RoundedCornerShape(50), color = if (charge) Scene.positive.copy(alpha = .18f) else Scene.fill, contentColor = if (charge) Scene.positive else Scene.text, border = Scene.edge) {
                Text(chip, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
            }
        }
    }
}

/**
 * The phone link's state in a few words, with its tone (2.12, pure, unit-tested): off; the sync server down with an error, or
 * still starting; a phone on the line right now ([online]); phones paired but none on the line; or none paired yet. Waiting
 * and not-yet-paired take the ready tone: the server is up and listening.
 */
internal fun phoneLinkTag(enabled: Boolean, running: Boolean, error: String?, online: Boolean, paired: Int): Pair<String, PillTone> = when {
    !enabled -> "꺼짐" to PillTone.QUIET
    !running -> if (error != null) "서버 오류" to PillTone.NEGATIVE else "준비 중" to PillTone.QUIET
    online -> "실시간 동기화 중" to PillTone.POSITIVE
    paired > 0 -> "대기" to PillTone.READY
    else -> "연결 전" to PillTone.READY
}

/**
 * 휴대폰 연결 on the vehicle page (2.12): the link's state as a live tag beside the title ([phoneLinkTag]); while the link is on,
 * three small figures (the record version, the phone's last call, the phones paired) and, while the server runs, the pairing
 * ([PhonePairing]): the three things a phone types, in the phone's order. The home keeps its own pairing card ([PairingCard]).
 * What the link does sits behind ⓘ. Switched off (2.13), the card holds the switch itself, the same row as 설정 › 기록's
 * ([SettingsCopy.PHONE_SYNC_TITLE]), where 2.12 only said where to find it.
 */
@Composable internal fun PhoneSyncCard(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val info = state.sync
    val profile = state.data.profile
    val enabled = profile.phoneSync
    val seen = info.lastPhoneSeenAt.takeIf { it > 0 }?.let { (now - it) / 1000 }
    // A long-poll is held for up to 20 s, so anything under 45 s means the phone is sitting on the line right now.
    val online = seen != null && seen < 45
    val (tag, tone) = phoneLinkTag(enabled, info.running, info.error, online, info.pairedPhones)
    val phone = painterResource(R.drawable.ic_phone)
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.PHONE_TITLE, icon = phone, trailing = { CarTag(tag, tone = tone, live = true) })
    }) {
        // The title already carries the phone mark, so the switch row goes without its disc.
        if (!enabled) SwitchRow(SettingsCopy.PHONE_SYNC_TITLE, SettingsCopy.PHONE_SYNC_LINE, checked = false, spoken = SettingsCopy.SPOKEN_PHONE_SYNC) { value ->
            act { repository.updateProfile(profile.copy(phoneSync = value)) }
        }
        else {
            // The server's own words for what went wrong; the tag already says that it did.
            if (!info.running) info.error?.let { CarLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The revision steps on every sync (it cross-fades); the seconds-ago figure is live and simply updates.
                CarMiniStat(painterResource(R.drawable.ic_journal), "기록 버전", "#${state.dataRevision}", Modifier.weight(1f))
                CarMiniStat(rememberVectorPainter(Icons.Outlined.Refresh), "최근 수신", seen?.let { if (it < 60) "${it}초 전" else "${it / 60}분 전" } ?: "없음",
                    Modifier.weight(1f), live = true)
                CarMiniStat(phone, "연결", "${info.pairedPhones}대", Modifier.weight(1f))
            }
            if (info.running) {
                CarDivider()
                PhonePairing(info, now)
            }
            CarInfoRow(VehicleCopy.PHONE_NOTE, more = VehicleCopy.PHONE_NOTE_MORE)
        }
    }
}

/**
 * The pairing inside [PhoneSyncCard] (2.13): the three things the phone asks for, in the phone's order (address, code, key),
 * each led by its step disc ([CarStepDisc]) so the driver reads them off top to bottom as they are typed. ① The addresses, two
 * lines at most. ② 연결 번호 with its countdown as a tag (it ticks in place, never announced) and, at the end of that row, the
 * pill for a new code (2.12 left it alone under the key); under them the six digits as the phone shows them, or why there is
 * no code. ③ The vehicle key, wrapping onto as many lines as it needs and never cut (a phone types all 64 characters, and a
 * 1.3× font in a narrow window takes three lines). TalkBack hears each step's number with what it holds.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PhonePairing(info: SyncInfo, now: Long) {
    val code = info.pairingCode
    val remaining = pairingRemaining(info, now)
    val key = info.fingerprint.chunked(8).joinToString(" ")
    val addresses = if (info.addresses.isEmpty()) VehicleCopy.PHONE_ADDRESS_PENDING else info.addresses.joinToString("  ·  ") { "$it:${info.port}" }
    // Under a step's own row, the content starts where the step's words do: the 32-dp disc and the 12-dp gap after it.
    val under = 44.dp
    Column(Modifier.fillMaxWidth()) {
        CarRow(addresses, line = VehicleCopy.PHONE_ADDRESS, lead = { CarStepDisc(1, done = false) }, titleMaxLines = 2,
            spoken = "1단계 ${VehicleCopy.SPOKEN_PHONE_ADDRESS}, $addresses")
        CarDivider(Modifier.padding(start = under))
        // ② as a title row whose end (the countdown and the pill) wraps under the title where it does not fit beside it.
        FlowRow(Modifier.fillMaxWidth().heightIn(min = 72.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(end = 12.dp).clearAndSetSemantics { contentDescription = "2단계 ${VehicleCopy.PHONE_CODE}" },
                verticalAlignment = Alignment.CenterVertically) {
                CarStepDisc(2, done = false)
                Spacer(Modifier.width(12.dp))
                Text(VehicleCopy.PHONE_CODE, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (code != null && remaining != null) CarTag("$remaining 남음")
                CarPillButton(if (code == null) "새 연결 번호" else "번호 다시 만들기", { SyncServer.active?.refreshPairingCode() })
            }
        }
        Column(Modifier.fillMaxWidth().padding(start = under, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (code != null) {
                Text(pairingDisplay(code), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium, color = Scene.text,
                    modifier = Modifier.semantics { contentDescription = "연결 번호 " + code.toCharArray().joinToString(" ") })
                CarLine(VehicleCopy.PHONE_HOW)
            } else Text(if (info.pairedPhones > 0) VehicleCopy.PHONE_ANOTHER else VehicleCopy.PHONE_EXPIRED, style = MaterialTheme.typography.titleSmall, color = Scene.text)
        }
        CarDivider(Modifier.padding(start = under))
        CarRow(key, line = VehicleCopy.PHONE_KEY, lead = { CarStepDisc(3, done = false) }, titleMaxLines = Int.MAX_VALUE,
            spoken = "3단계 ${VehicleCopy.SPOKEN_PHONE_KEY}, $key")
    }
}

/**
 * Which card payments the car takes, as the saved [cards] stand (2.12, pure, unit-tested): with none registered only those it
 * recognises as fuel, charging, tolls or parking; with some, only theirs ("등록한 카드 2장의 결제만 받아요").
 */
internal fun cardRegistryLine(cards: List<String>): String =
    if (cards.isEmpty()) "카드 미등록 · 주유·충전·통행료·주차 결제만 받아요" else "등록한 카드 ${cards.size}장의 결제만 받아요"

/**
 * 설정 › 기록's 자동 완성 card (2.12): which cards count as the company's. One line says what the saved cards mean
 * ([cardRegistryLine]); TalkBack hears the whole of it instead ([SettingsCopy.SPOKEN_CARDS]). Then the field, its error and
 * 카드 저장, the card's one solid action. The switch itself is 기록 및 개인정보's.
 */
@Composable internal fun AutopilotSettings(saved: FleetProfile, act: Act, repository: TripLogRepository, field: @Composable (String, (String) -> Unit) -> Unit) {
    var cards by rememberSaveable(saved.cards) { mutableStateOf(saved.cards.joinToString(", ")) }
    val parsed = cards.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val valid = parsed.size <= 10 && parsed.all { it.matches(Regex("[0-9]{4}")) }
    CarCard(title = { CarSectionTitle(SettingsCopy.AUTOFILL_TITLE, icon = painterResource(R.drawable.ic_autofill)) }) {
        CarLine(cardRegistryLine(saved.cards), spoken = SettingsCopy.SPOKEN_CARDS)
        field(cards) { value -> if (value.length <= 60 && value.all { it.isDigit() || it == ',' || it == ' ' }) cards = value }
        if (!valid) Text(SettingsCopy.CARDS_ERROR, color = Scene.negative, style = MaterialTheme.typography.bodyMedium)
        PrimaryAction("카드 저장", Modifier.fillMaxWidth(), enabled = valid && parsed != saved.cards) { act { repository.updateProfile(saved.copy(cards = parsed)) } }
    }
}
