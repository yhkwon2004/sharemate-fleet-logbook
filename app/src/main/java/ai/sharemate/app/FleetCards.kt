package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
 * Each card captures vehicle data by itself and asks the driver for one decision at most.
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

// ───────────────────────── 사고·특이사항 ─────────────────────────

private fun incidentCaption(kind: IncidentKind) = when (kind) {
    IncidentKind.ACCIDENT -> "119 · 112 안내"
    IncidentKind.DAMAGE -> "촬영 안내"
    IncidentKind.BREAKDOWN -> "긴급출동 안내"
    IncidentKind.OTHER -> "메모로 남기기"
}

/** One tap at the scene. The car fills time, odometer, place, trip and driver; the driver only chooses what happened. */
@Composable internal fun IncidentQuickRow(data: TripLogData, act: Act, repository: TripLogRepository) {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    val report: (IncidentKind) -> Unit = { kind ->
        act {
            val incident = repository.reportIncident(kind)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            followUp(FollowUp("${kind.label} 기록됨 · 시각·계기판·위치 저장, 대처 안내를 홈에 띄웠어요", "실수예요") { repository.deleteIncident(incident.id) })
        }
    }
    Panel(padding = 20.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("사고·특이사항 · 한 번 누르면 차량 정보가 바로 기록돼요")
            Spacer(Modifier.weight(1f))
            if (data.profile.phoneSync) Text("휴대폰에도 공유", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val kinds = IncidentKind.entries
            if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                kinds.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { pair.forEach { IncidentTile(it, Modifier.weight(1f)) { report(it) } } }
                }
            } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                kinds.forEach { IncidentTile(it, Modifier.weight(1f)) { report(it) } }
            }
        }
    }
}

@Composable private fun IncidentTile(kind: IncidentKind, modifier: Modifier, onClick: () -> Unit) {
    val pleos = LocalPleosColors.current
    val urgent = kind == IncidentKind.ACCIDENT
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 88.dp).semantics { role = Role.Button; contentDescription = "${kind.label} 기록, ${incidentCaption(kind)}" },
        shape = RoundedCornerShape(22.dp), color = if (urgent) pleos.negative.copy(alpha = .16f) else pleos.surfaceHigh,
        border = if (urgent) BorderStroke(1.dp, pleos.negative.copy(alpha = .5f)) else null) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(kind.label, style = MaterialTheme.typography.titleMedium, color = if (urgent) pleos.negative else MaterialTheme.colorScheme.onSurface, maxLines = 1)
            Text(incidentCaption(kind), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/** Guidance for an open report: numbered steps first, captured facts second, one closing action. */
@Composable internal fun IncidentCard(incident: Incident, data: TripLogData, act: Act, repository: TripLogRepository) {
    val pleos = LocalPleosColors.current
    val driver = data.drivers.firstOrNull { it.id == incident.driverId }
    val steps = incidentSteps(incident.kind)
    Panel(color = if (incident.kind == IncidentKind.ACCIDENT) pleos.negative.copy(alpha = .08f) else MaterialTheme.colorScheme.surface) {
        Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Eyebrow("${incident.kind.label} · ${incident.status.label} · ${clock(incident.at)}", pleos.negative)
            Text("지금 할 일", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        }
        steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(32.dp).clip(CircleShape).background(pleos.surfaceHigh), contentAlignment = Alignment.Center) {
                    Text("${index + 1}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
                }
                Text(step, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(top = 4.dp))
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("계기판", km(incident.odometerKm), Modifier.weight(1f))
            Stat("사용자", driver?.name ?: "미지정", Modifier.weight(1f))
        }
        Muted(incident.address?.let { "위치 · $it" } ?: "위치 · 내비 주소 미수신")
        if (incident.note.isNotBlank()) Text(incident.note, style = MaterialTheme.typography.bodyMedium)
        PhotoStrip(incident.photos, repository.photos, size = 112.dp, onRemove = { photo -> act { repository.removePhoto(incident.id, photo.id) } })
        Muted(when {
            incident.photos.isNotEmpty() -> "사진 ${incident.photos.size}장 · 휴대폰에서 촬영해 차량에 저장됐어요."
            data.profile.phoneSync -> "휴대폰 SHAREMATE에 같은 기록이 떠 있어요. 현장 사진과 메모는 휴대폰에서 덧붙이세요."
            else -> "사진·메모는 휴대폰에서 덧붙일 수 있어요. 설정에서 휴대폰 실시간 연동을 켜 주세요."
        })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction("처리 완료", Modifier.weight(1f)) { act { repository.updateIncident(incident.id, status = IncidentStatus.CLOSED) } }
            QuietAction("기록 삭제", Modifier.weight(1f), color = pleos.negative) { act { repository.deleteIncident(incident.id) } }
        }
    }
}

/**
 * Thumbnails for a report's photos. A photo whose file is not on this device yet shows a placeholder and asks [missing]
 * to fetch it; [version] bumps when a fetch lands so the strip re-reads the files.
 */
@Composable internal fun PhotoStrip(photos: List<IncidentPhoto>, store: PhotoStore, version: Int = 0, size: Dp = 96.dp,
    missing: ((String) -> Unit)? = null, onRemove: ((IncidentPhoto) -> Unit)? = null) {
    if (photos.isEmpty()) return
    val pleos = LocalPleosColors.current
    Row(Modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "첨부 사진 ${photos.size}장" }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        photos.forEach { photo ->
            val file = store.file(photo.id)
            val bitmap by produceState<android.graphics.Bitmap?>(null, photo.id, version) { value = withContext(Dispatchers.IO) { thumbnail(file) } }
            LaunchedEffect(photo.id, version) { if (!file.exists()) missing?.invoke(photo.id) }
            Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(pleos.surfaceHigh)) {
                bitmap?.let { Image(it.asImageBitmap(), "사진 ${clock(photo.at)} · ${photo.source.label}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Text(if (missing != null) "받는 중" else "사진 없음", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center))
                if (onRemove != null) Surface(onClick = { onRemove(photo) }, shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).semantics { role = Role.Button; contentDescription = "사진 삭제" }) {
                    Text("×", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}

/** History for the vehicle page; status can be flipped back if a report was closed too early. */
@Composable internal fun IncidentHistory(data: TripLogData, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val items = recentIncidents(data)
    val pleos = LocalPleosColors.current
    Panel(modifier, padding = 22.dp) {
        Eyebrow("사고·특이사항 기록 · ${data.incidents.count { it.demo == data.profile.demoMode }}건")
        if (items.isEmpty()) Muted("기록이 없어요. 홈의 사고·특이사항 버튼을 누르면 그 순간의 차량 정보가 저장돼요.")
        items.forEach { incident ->
            val open = incident.status == IncidentStatus.OPEN
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).semantics(mergeDescendants = true) { stateDescription = incident.status.label },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(if (open) pleos.negative else pleos.chartActive))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${incident.kind.label} · ${SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(incident.at))}", style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(incident.odometerKm?.let(::km), incident.address, incident.photos.size.takeIf { it > 0 }?.let { "사진 ${it}장" }, incident.note.ifBlank { null }).joinToString(" · ").ifBlank { "상세 없음" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { act { repository.updateIncident(incident.id, status = if (open) IncidentStatus.CLOSED else IncidentStatus.OPEN) } },
                    modifier = Modifier.heightIn(min = 48.dp)) { Text(if (open) "완료" else "다시 열기") }
            }
        }
    }
}

// ───────────────────────── 충전 ─────────────────────────

private val chargeQuickAmounts = listOf(10_000L, 20_000L, 30_000L, 50_000L)

/** The car saw the charge end; the amount is the only thing left, and even that can be skipped. */
@Composable internal fun ChargeCard(session: ChargeSession, act: Act, repository: TripLogRepository) {
    val pleos = LocalPleosColors.current
    var custom by rememberSaveable(session.endAt) { mutableStateOf("") }
    val minutes = ((session.endAt - session.startAt) / 60_000).coerceAtLeast(1)
    val soc = if (session.socFrom != null && session.socTo != null) "${session.socFrom}% → ${session.socTo}%" else "배터리 변화 미수신"
    Panel {
        val word = if (session.fuel) "주유" else "충전"
        Eyebrow("$word 감지 · ${clock(session.startAt)}–${clock(session.endAt)}", pleos.active)
        Text("$word 비용만 알려 주세요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat(if (session.fuel) "연료" else "배터리", soc, Modifier.weight(1.2f))
            Stat("$word 시간", "${minutes}분", Modifier.weight(1f))
            Stat("계기판", km(session.odometerKm), Modifier.weight(1f))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = if (maxWidth < 520.dp) 2 else 4
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                chargeQuickAmounts.chunked(columns).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { amount ->
                            Surface(onClick = { act { repository.resolveCharge(amount, EditSource.DRIVER) } }, modifier = Modifier.weight(1f).heightIn(min = 64.dp),
                                shape = RoundedCornerShape(18.dp), color = pleos.surfaceHigh) {
                                Text(formatWon(amount), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
                            }
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(custom, { if (it.length <= 8 && it.all(Char::isDigit)) custom = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("직접 입력 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            PrimaryAction("등록", enabled = (custom.toLongOrNull() ?: 0) > 0) { act { repository.resolveCharge(custom.toLong(), EditSource.DRIVER); custom = "" } }
        }
        QuietAction("비용 없음 · 법인카드 자동정산 (건너뛰기)", Modifier.fillMaxWidth()) { act { repository.resolveCharge(null, EditSource.DRIVER) } }
        Muted("등록한 금액은 가장 최근 운행의 ‘주유·충전’ 경비로 들어가고 Excel ⑫ 비용에 합산돼요.")
    }
}

// ───────────────────────── 차량 카드 · 휴대폰 연결 ─────────────────────────

/** Identity chips read from the car itself; shows nothing until the vehicle has reported something. */
@Composable internal fun VehicleChips(state: TripLogState, modifier: Modifier = Modifier) {
    val card = state.data.vehicle
    val live = state.live?.takeIf { it.demo == state.data.profile.demoMode }
    val charging = live?.charging == true
    val chips = listOfNotNull(
        (state.vehicleModel ?: card.model)?.takeIf { it.isNotBlank() },
        (live?.socPercent ?: card.socPercent)?.let { "배터리 $it%" },
        if (charging) "충전 중" else null,
        validVin(live?.vin ?: card.vin)?.takeLast(6)?.let { "VIN …$it" },
    )
    if (chips.isEmpty()) return
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "차량 정보 " + chips.joinToString(", ") }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            Surface(shape = RoundedCornerShape(50), color = if (chip == "충전 중") LocalPleosColors.current.active.copy(alpha = .22f) else Color.White.copy(alpha = .08f)) {
                Text(chip, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
            }
        }
    }
}

/** Phone pairing from the driver's seat: a six-digit code, the car's address, and nothing to type on the car. */
@Composable internal fun PairingCard(info: SyncInfo, now: Long, compact: Boolean = false) {
    val pleos = LocalPleosColors.current
    val code = info.pairingCode
    val remaining = pairingRemaining(info, now)
    Panel(padding = if (compact) 22.dp else 28.dp) {
        Eyebrow(if (info.pairedPhones == 0) "휴대폰 연결 · 처음 한 번만" else "휴대폰 추가 연결")
        if (!info.running) {
            Text(info.error ?: "휴대폰 연결 서버를 준비하고 있어요", style = MaterialTheme.typography.titleMedium)
        } else if (code != null) {
            Text(pairingDisplay(code), style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Medium,
                modifier = Modifier.semantics { contentDescription = "연결 번호 " + code.toCharArray().joinToString(" ") })
            Muted("휴대폰 SHAREMATE에서 차량 주소와 이 번호를 입력하세요 · ${remaining ?: "—"} 남음")
        } else {
            Text(if (info.pairedPhones > 0) "다른 휴대폰을 더 연결하려면 새 번호를 만드세요" else "연결 번호가 만료됐어요", style = MaterialTheme.typography.titleMedium)
        }
        if (info.running) {
            Text((info.addresses.ifEmpty { listOf("네트워크 주소 확인 중") }).joinToString("  ·  ") { "$it:${info.port}" },
                style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                QuietAction(if (code == null) "새 연결 번호" else "번호 다시 만들기", Modifier.weight(1f)) { SyncServer.active?.refreshPairingCode() }
                Text("연결된 휴대폰 ${info.pairedPhones}대", style = MaterialTheme.typography.labelLarge, color = pleos.textTertiary)
            }
        }
        Muted("같은 Wi-Fi·핫스팟 안에서만 연결되고, 차량 인증서를 휴대폰이 기억해 다른 기기는 끼어들 수 없어요.")
    }
}

/** Live-sync status for the vehicle page. */
@Composable internal fun PhoneSyncCard(state: TripLogState, now: Long, modifier: Modifier = Modifier) {
    val info = state.sync
    val pleos = LocalPleosColors.current
    val enabled = state.data.profile.phoneSync
    val seen = info.lastPhoneSeenAt.takeIf { it > 0 }?.let { (now - it) / 1000 }
    // A long-poll is held for up to 20 s, so anything under 45 s means the phone is sitting on the line right now.
    val online = seen != null && seen < 45
    Panel(modifier, padding = 22.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(when { !enabled -> pleos.textTertiary; online -> pleos.chartActive; info.running -> pleos.active; else -> pleos.negative }))
            Text(when {
                !enabled -> "휴대폰 실시간 연동 꺼짐"
                !info.running -> info.error ?: "연결 서버 준비 중"
                online -> "휴대폰과 실시간 동기화 중"
                info.pairedPhones > 0 -> "연결된 휴대폰 ${info.pairedPhones}대 · 대기"
                else -> "휴대폰을 연결해 주세요"
            }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Stat("기록 버전", "#${state.dataRevision}", Modifier.weight(1f))
            Stat("휴대폰 최근 수신", seen?.let { if (it < 60) "${it}초 전" else "${it / 60}분 전" } ?: "없음", Modifier.weight(1f))
        }
        Muted(if (enabled) "차량이 원본이고 휴대폰은 거울이에요. 휴대폰에서 바꾼 목적·경비·특이사항은 차량 기록에 ‘휴대폰’ 출처로 남아요."
            else "설정 › 기록에서 켜면 휴대폰 SHAREMATE가 운행·경비·특이사항을 실시간으로 받고, 정차 중 입력도 휴대폰에서 할 수 있어요.")
    }
}

// ───────────────────────── 자동 완성 ─────────────────────────

/** Month progress in one line of numbers: how much of the logbook finished itself and what is left for a person. */
@Composable internal fun AutopilotStrip(data: TripLogData, now: Long, modifier: Modifier = Modifier) {
    if (!data.profile.autopilot) return
    val today = java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
    val summary = autopilotSummary(data, today.withDayOfMonth(1), today, java.time.ZoneId.systemDefault())
    val pleos = LocalPleosColors.current
    val headline: @Composable (Modifier) -> Unit = { place ->
        Column(place, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("이달 운행일지 ${summary.percent?.let { "$it%" } ?: "—"} 완성", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(if (summary.left > 0) "남은 확인 ${summary.left}건" else "사람이 확인할 일 없음", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    val numbers: @Composable RowScope.() -> Unit = {
        Stat("목적 자동 분류", "${summary.autoPurpose}건", Modifier.weight(1f))
        Stat("카드 자동 반영", "${summary.cardMatched}건 · ${formatWon(summary.cardWon)}", Modifier.weight(1.5f))
    }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = pleos.surfaceHigh) {
        BoxWithConstraints(Modifier.padding(horizontal = 18.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}) {
            if (maxWidth < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                headline(Modifier)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), content = numbers)
            } else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                headline(Modifier.weight(1.2f))
                numbers()
            }
        }
    }
}

/** The only card question the car ever asks: one payment, the trip it probably belongs to, yes or no. */
@Composable internal fun ChargeReviewCard(charge: CardCharge, remaining: Int, data: TripLogData, act: Act, repository: TripLogRepository) {
    val pleos = LocalPleosColors.current
    val trip = suggestedTrip(charge, data)
    val moment = SimpleDateFormat(if (charge.timeKnown) "M/d HH:mm" else "M/d", Locale.KOREA).format(Date(charge.at))
    Panel {
        Eyebrow(if (remaining > 1) "카드 결제 확인 ${remaining}건 · 한 건씩" else "카드 결제 확인", pleos.active)
        Text(trip?.let { "이 운행의 비용이 맞나요?" } ?: "어느 운행의 비용인지 찾지 못했어요", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(formatWon(charge.amountWon), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
            Muted("$moment · ${charge.category?.label ?: "차량 비용 추정"}", Modifier.padding(bottom = 6.dp))
        }
        Text(charge.merchant, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        trip?.let { Muted("${clock(it.startAt)} 출발 · ${km(it.distanceKm)} · ${it.destination ?: it.endAddress ?: "주소 미기록"}") }
        if (charge.reason.isNotBlank()) Muted("판단 근거 · ${charge.reason}")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (trip != null) PrimaryAction("맞아요", Modifier.weight(1f)) { act { repository.confirmCardCharge(charge.id, trip.id) } }
            QuietAction("회사 비용 아님", Modifier.weight(1f)) { act { repository.excludeCardCharge(charge.id) } }
        }
    }
}

/** Settings for the hands-off behaviour: the switch and which cards count as the company's. */
@Composable internal fun AutopilotSettings(saved: FleetProfile, act: Act, repository: TripLogRepository, field: @Composable (String, (String) -> Unit) -> Unit) {
    var cards by rememberSaveable(saved.cards) { mutableStateOf(saved.cards.joinToString(", ")) }
    val parsed = cards.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val valid = parsed.size <= 10 && parsed.all { it.matches(Regex("[0-9]{4}")) }
    Panel {
        Eyebrow("자동 완성")
        Muted("반복 도착지는 목적을 스스로 채우고, 휴대폰이 받은 카드 승인을 차량 기록과 맞춰 비용에 넣습니다. 자동으로 들어간 항목은 ‘자동 분류’·‘카드 자동 매칭’으로 표시되고 언제든 고칠 수 있어요.")
        field(cards) { value -> if (value.length <= 60 && value.all { it.isDigit() || it == ',' || it == ' ' }) cards = value }
        if (!valid) Text("끝 4자리 숫자를 쉼표로 구분해 입력해 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        Muted("등록하면 다른 카드의 알림은 무시하고, 가맹점 이름만으로 알 수 없는 결제도 차량 기록과 맞춰 봅니다. 등록하지 않으면 주유·충전·통행료·주차로 확인되는 결제만 받습니다.")
        PrimaryAction("카드 저장", Modifier.fillMaxWidth(), enabled = valid && parsed != saved.cards) { act { repository.updateProfile(saved.copy(cards = parsed)) } }
    }
}
