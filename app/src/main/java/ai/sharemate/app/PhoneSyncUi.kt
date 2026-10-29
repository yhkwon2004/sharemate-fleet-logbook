package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Phone-side cards for the live link. The phone adds what the car cannot: typed words, photos, a passenger's hands.
 * Every edit is one SyncCommand; the result arrives through the mirrored copy, so nothing here writes the log directly.
 */

private val connectedGreen = Color(0xFF2E9E6B)
private fun phoneTime(at: Long, pattern: String = "HH:mm") = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))

@Composable internal fun PhoneLinkBadge(link: PhoneLink) {
    Surface(shape = RoundedCornerShape(50), color = if (link.connected) connectedGreen.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (link.connected) connectedGreen else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
            Text(if (link.connected) "차량 연결" else "연결 대기", style = MaterialTheme.typography.labelMedium)
        }
    }
}

/** The one-time handshake: the car shows a six-digit code and its address, the phone types both. */
@Composable internal fun PhoneConnectCard(busy: Boolean, pair: (String, String) -> Unit) {
    var endpoint by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    Panel(padding = 22.dp) {
        Eyebrow("차량과 실시간 연결")
        Text("차량 화면의 주소와 6자리 번호를 입력하세요", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(endpoint, { if (it.length <= 80) endpoint = it.trim() }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("차량 주소 (예: 192.168.0.12:9247)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(16.dp))
        OutlinedTextField(code, { if (it.length <= 6 && it.all(Char::isDigit)) code = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text("연결 번호 6자리") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), shape = RoundedCornerShape(16.dp))
        Button(onClick = { pair(endpoint, code) }, enabled = !busy && endpoint.isNotBlank() && code.length == 6,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { Text("차량과 연결") }
        Muted("차량 앱 › 차량 탭 › 휴대폰 연결에서 번호를 볼 수 있어요. 번호는 5분 뒤 만료돼요. 차량과 같은 Wi-Fi·핫스팟이어야 해요.")
    }
}

@Composable internal fun PhoneOpenTripCard(open: Trip, link: PhoneLink) {
    SharemateTheme(compact = true, dark = true) {
        Panel(color = Color(0xFF1D1E21), padding = 20.dp) {
            Eyebrow("지금 운행 중 · ${phoneTime(open.startAt)} 출발", Color(0xFFE4BFA6))
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.1f".format(Locale.KOREA, open.distanceKm), style = MaterialTheme.typography.displaySmall)
                Text("km", style = MaterialTheme.typography.titleMedium, color = Color(0xFFC7C9CE), modifier = Modifier.padding(bottom = 6.dp))
            }
            Muted(listOfNotNull(open.startAddress, open.destination?.let { "→ $it" }, link.live?.socPercent?.let { "배터리 $it%" }).joinToString(" · ").ifBlank { "차량 신호 수신 중" })
        }
    }
}

@Composable internal fun PhoneIncidentButtons(busy: Boolean, send: (SyncCommand) -> Unit) {
    Panel(padding = 20.dp) {
        Eyebrow("사고·특이사항 · 차량 시각·계기판·위치로 기록")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IncidentKind.entries.forEach { kind ->
                val urgent = kind == IncidentKind.ACCIDENT
                OutlinedButton(onClick = { send(SyncCommand.ReportIncident(kind, "")) }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant)) {
                    Text(kind.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (urgent) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** An open report mirrored from the car: the phone adds the words and the photos the car cannot. */
@Composable internal fun PhoneIncidentCard(incident: Incident, data: TripLogData, busy: Boolean, send: (SyncCommand) -> Unit,
    photos: PhotoStore, photoVersion: Int, capture: (String) -> Unit, fetch: (String) -> Unit) {
    var note by rememberSaveable(incident.id, incident.note) { mutableStateOf(incident.note) }
    Panel(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f), padding = 20.dp) {
        Eyebrow("${incident.kind.label} · 대응 중 · ${phoneTime(incident.at, "M/d HH:mm")}", MaterialTheme.colorScheme.error)
        incidentSteps(incident.kind).forEachIndexed { index, step -> Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium) }
        Muted(listOfNotNull(incident.odometerKm?.let { "계기판 %,.1f km".format(Locale.KOREA, it) }, incident.address,
            data.drivers.firstOrNull { it.id == incident.driverId }?.label).joinToString(" · ").ifBlank { "차량 정보 수신 대기" })
        PhotoStrip(incident.photos, photos, photoVersion, size = 88.dp, missing = fetch, onRemove = { photo -> send(SyncCommand.RemovePhoto(incident.id, photo.id)) })
        OutlinedButton(onClick = { capture(incident.id) }, enabled = !busy && incident.photos.size < MAX_INCIDENT_PHOTOS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (incident.photos.isEmpty()) "현장 사진 촬영 · 차량에 저장" else "사진 추가 촬영 (${incident.photos.size}/$MAX_INCIDENT_PHOTOS)")
        }
        OutlinedTextField(note, { if (it.length <= 500) note = it }, Modifier.fillMaxWidth(), label = { Text("메모 · 상대 차량번호, 상황") }, minLines = 2, shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { send(SyncCommand.UpdateIncident(incident.id, note, null, null)) }, enabled = !busy && note != incident.note,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("메모 저장") }
            OutlinedButton(onClick = { send(SyncCommand.UpdateIncident(incident.id, note.takeIf { it != incident.note }, IncidentStatus.CLOSED, null)) }, enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("처리 완료") }
        }
    }
}

@Composable internal fun PhoneChargeCard(session: ChargeSession, busy: Boolean, send: (SyncCommand) -> Unit) {
    var amount by rememberSaveable(session.endAt) { mutableStateOf("") }
    Panel(padding = 20.dp) {
        Eyebrow("차량이 충전 종료를 감지했어요 · ${phoneTime(session.startAt)}–${phoneTime(session.endAt)}")
        Text(if (session.socFrom != null && session.socTo != null) "배터리 ${session.socFrom}% → ${session.socTo}%" else "충전 비용을 입력해 주세요", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("충전 비용 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            Button(onClick = { send(SyncCommand.ResolveCharge(amount.toLong())) }, enabled = !busy && (amount.toLongOrNull() ?: 0) > 0,
                modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) { Text("등록") }
        }
        TextButton(onClick = { send(SyncCommand.ResolveCharge(null)) }, enabled = !busy) { Text("비용 없음 · 건너뛰기") }
    }
}

/** Purpose, expense and note edits for one trip, each sent as a single command. */
@Composable internal fun PhoneTripEditor(trip: Trip, busy: Boolean, send: (SyncCommand) -> Unit) {
    var type by rememberSaveable(trip.id) { mutableStateOf(ExpenseType.TOLL.name) }
    var amount by rememberSaveable(trip.id) { mutableStateOf("") }
    var note by rememberSaveable(trip.id, trip.note) { mutableStateOf(trip.note) }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Eyebrow("운행 목적")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL).forEach { purpose ->
            val selected = purpose == trip.purpose
            OutlinedButton(onClick = { send(SyncCommand.Classify(trip.id, purpose)) }, enabled = !busy && !selected, modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .15f) else Color.Transparent)) {
                Text(purpose.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
    Eyebrow("경비 추가")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExpenseType.entries.forEach { candidate ->
            FilterChip(selected = type == candidate.name, onClick = { type = candidate.name }, label = { Text(candidate.label, style = MaterialTheme.typography.labelSmall) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), singleLine = true,
            label = { Text("금액 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(14.dp))
        Button(onClick = { send(SyncCommand.AddExpense(trip.id, ExpenseType.valueOf(type), amount.toLong())); amount = "" },
            enabled = !busy && (amount.toLongOrNull() ?: 0) > 0, shape = RoundedCornerShape(12.dp)) { Text("추가") }
    }
    OutlinedTextField(note, { if (it.length <= 300) note = it }, Modifier.fillMaxWidth(), label = { Text("메모") }, shape = RoundedCornerShape(14.dp))
    if (note != trip.note) TextButton(onClick = { send(SyncCommand.Note(trip.id, note)) }, enabled = !busy) { Text("메모 저장") }
}
