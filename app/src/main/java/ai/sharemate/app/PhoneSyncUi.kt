package ai.sharemate.app

import androidx.core.content.edit
import android.content.Context
import android.content.SharedPreferences
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/*
 * Phone-side cards for the live link. The phone adds what the car cannot: typed words, photos, a passenger's hands.
 * Every edit is one SyncCommand; the result arrives through the mirrored copy, so nothing here writes the log directly.
 * Confirmations go through LocalFollowUp (the shell shows them as a snackbar); errors stay inline next to the field.
 */

internal fun phoneTime(at: Long, pattern: String = "HH:mm") = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))

/** Small per-phone memories (a deferred prompt, checked steps). Nothing here is part of the log. */
internal const val PHONE_UI_PREFS = "phone-ui"
internal fun phoneUiPreferences(context: Context): SharedPreferences = context.applicationContext.getSharedPreferences(PHONE_UI_PREFS, Context.MODE_PRIVATE)

// 30 days: long enough that a refusal is respected, short enough that a new phone or a new month gets one more chance.
internal const val ALERT_PROMPT_LATER_MILLIS = 30L * 24 * 60 * 60 * 1000
internal const val ALERT_PROMPT_LATER_KEY = "alert-prompt-later-until"
internal fun alertPromptDeferred(laterUntil: Long, now: Long) = laterUntil > now

/** The inline "실수예요" stays available this long after a one-tap report; after that the record is kept as a real one. */
internal const val INCIDENT_UNDO_WINDOW_MILLIS = 60_000L

/** The report the phone just sent, once the mirror shows it: the newest open record of that kind that was not there before. */
internal fun newIncidentFor(kind: IncidentKind, knownIds: Set<String>, incidents: List<Incident>, demo: Boolean): Incident? =
    incidents.filter { it.kind == kind && it.status == IncidentStatus.OPEN && it.demo == demo && it.id !in knownIds }.maxByOrNull { it.at }

/** Checked response steps are a local memo: "0,2" means the first and third step are done. */
internal fun parseStepMarks(raw: String?): Set<Int> = raw.orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it >= 0 }.toSet()
internal fun encodeStepMarks(marks: Set<Int>): String = marks.sorted().joinToString(",")
internal fun incidentStepsKey(incidentId: String) = "incident-steps-$incidentId"

/**
 * What the connect card shows under a field when [PhoneSyncClient.pair] fails: the client already speaks in one short
 * sentence for every expected failure; anything else gets one generic line. Messages that mention the address go under
 * the address field, every other one under the code field.
 */
internal fun pairingErrorMessage(e: Throwable): String = (e as? IllegalArgumentException)?.message?.takeIf { it.isNotBlank() }
    ?: "차량과 연결하지 못했어요. 주소와 번호를 확인하고 다시 시도해 주세요."
internal fun pairingErrorIsAddress(message: String) = message.contains("주소") || message.contains("닿지")

/** The matcher writes its reasons as one line joined by " · "; the phone shows them as a numbered list. */
internal fun reasonSteps(reason: String): List<String> = reason.split(" · ").map { it.trim() }.filter { it.isNotEmpty() }

/** 차량 연결 · 연결 대기: the connected state in the scene's positive green (AA on day glass), the dot never pulses. */
@Composable internal fun PhoneLinkBadge(link: PhoneLink) {
    val label = if (link.connected) "차량 연결" else "연결 대기"
    val positive = Scene.positive
    Surface(shape = RoundedCornerShape(50), color = if (link.connected) positive.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite; stateDescription = label }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(if (link.connected) positive else MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (link.connected) positive else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The one-time handshake. 2.15 (docs/design/DESIGN_2_15.md §6.3): the car shows its addresses, a six-digit code and its key
 * as one QR, so the card leads with QR 찍어서 연결: the system camera takes a picture into the app's cache through the
 * evidence FileProvider ([qrScanFile]; no camera permission, the camera app has its own), then 사진에서 QR 읽기 (the system
 * photo picker, images only, for a screenshot or a picture sent from another phone). The picture is decoded off the main
 * thread ([readQrPicture]) and the camera's file deleted. [PhoneSyncClient.pair] tries a link's addresses in order. What went
 * wrong with a scan, or with the pairing a scan started, is one message under the buttons, a polite live region. Then 직접
 * 입력: the three fields and the button as before, [error] under the field it concerns after a typed try.
 *
 * 2.15 fix (docs/design/DESIGN_2_15.md §9.6): a link is trusted only as far as typing was. A link that parses
 * ([parsePairingLink]) always fills the three fields, but it connects at once only for a picture the camera just took of a
 * link whose addresses are all the car's local ones ([scannedLinkPairsAtOnce], [pairingLinkIsLocal]): the person stands at
 * the car that shows it. A picture from the picker (one sent from elsewhere), or a link that names a public address or a
 * host name, waits: one line under the buttons, not an error, names the first address ([PhoneQrCopy.confirm]) and the person
 * presses 차량과 연결, as when typing. The QR's key pins whichever server the link names, so without this a hostile picture
 * would move the phone's car, its card payments and photos to that server with no look at where they go.
 */
@Composable internal fun PhoneConnectCard(busy: Boolean, error: String? = null, pair: (String, String, String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var endpoint by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var fingerprint by rememberSaveable { mutableStateOf("") }
    // Whether the last try came from a scan: its failure is said under the scan buttons, not under a field further down.
    var scanned by rememberSaveable { mutableStateOf(false) }
    var scanError by rememberSaveable { mutableStateOf<String?>(null) }
    // A scanned link that waits for 차량과 연결 (§9.6): the line under the buttons that names its first address.
    var scanNotice by rememberSaveable { mutableStateOf<String?>(null) }
    // The host keeps its last pairing error until the next try; a new scan sets that stale one aside until then.
    var setAside by rememberSaveable { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    val endpointFocus = remember { FocusRequester() }
    val codeFocus = remember { FocusRequester() }
    val current = error?.takeUnless { it == setAside }
    val fieldError = current?.takeUnless { scanned }
    val addressError = fieldError != null && pairingErrorIsAddress(fieldError)
    val codeError = fieldError != null && !addressError
    val scanMessage = scanError ?: current?.takeIf { scanned }
    LaunchedEffect(error) {
        if (error == null || scanned || error == setAside) return@LaunchedEffect
        runCatching { if (addressError) endpointFocus.requestFocus() else codeFocus.requestFocus() }
    }
    val connect: (String, String, String) -> Unit = { e, c, k -> setAside = null; pair(e, c, k) }
    val startScan: () -> Unit = { scanError = null; scanNotice = null; setAside = error }
    val use: (String?, Boolean) -> Unit = { text, fromCamera ->
        val link = text?.let(::parsePairingLink)
        if (link == null) scanError = if (text == null) PhoneQrCopy.UNREADABLE else PhoneQrCopy.NOT_OURS
        else {
            endpoint = link.endpointText; code = link.code; fingerprint = link.fingerprint
            if (scannedLinkPairsAtOnce(fromCamera, pairingLinkIsLocal(link))) {
                scanned = true
                connect(link.endpointText, link.code, link.fingerprint)
            } else {
                scanned = false
                scanNotice = PhoneQrCopy.confirm(link.endpoints.first())
            }
        }
    }
    val read: (Boolean, suspend () -> String?) -> Unit = { fromCamera, load ->
        reading = true
        scope.launch { try { use(load(), fromCamera) } finally { reading = false } }
    }
    val scanFile = remember(context) { qrScanFile(context) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        if (taken) read(true) { try { readQrPicture(scanFile) } finally { scanFile.delete() } } else scanFile.delete()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) read(false) { readQrPicture(context, uri) } }
    val idle = !busy && !reading
    Panel(padding = 22.dp) {
        Box(Modifier.semantics(mergeDescendants = true) { heading() }) { Eyebrow("차량과 실시간 연결") }
        Button(onClick = {
            startScan()
            scanFile.delete()
            runCatching { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.evidence", scanFile)) }
                .onFailure { scanError = PhoneQrCopy.NO_CAMERA }
        }, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
            // The scan's own progress stays on its button: reading the picture, then the pairing it started.
            val working = reading || (busy && scanned)
            if (!working) {
                Icon(painterResource(R.drawable.ic_camera), contentDescription = null, Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            LoadingLabel(PhoneQrCopy.SCAN, working, if (reading) PhoneQrCopy.READING else "연결하는 중…")
        }
        TextButton(onClick = {
            startScan()
            runCatching { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .onFailure { scanError = PhoneQrCopy.NO_PICKER }
        }, enabled = idle, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(PhoneQrCopy.PICK) }
        val notice = scanNotice
        if (scanMessage != null) Text(scanMessage, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        else if (notice != null) Muted(notice, Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))
        Text(PhoneQrCopy.MANUAL, style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        OutlinedTextField(endpoint, { value ->
            if (value.length <= 80 || value.length < endpoint.length) {
                // The waiting line names the scanned address; once the person changes the address it no longer applies.
                if (value.trim() != endpoint) scanNotice = null
                endpoint = value.trim()
            }
        }, Modifier.fillMaxWidth().focusRequester(endpointFocus), singleLine = true,
            label = { Text("차량 주소 (예: 192.168.0.12:9247)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), shape = RoundedCornerShape(16.dp),
            isError = addressError, supportingText = if (addressError) { { Text(fieldError!!, style = MaterialTheme.typography.bodySmall) } } else null)
        OutlinedTextField(code, { if (it.length <= 6 && it.all(Char::isDigit)) code = it }, Modifier.fillMaxWidth().focusRequester(codeFocus), singleLine = true,
            label = { Text("연결 번호 6자리") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), shape = RoundedCornerShape(16.dp),
            isError = codeError, supportingText = if (codeError) { { Text(fieldError!!, style = MaterialTheme.typography.bodySmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) } } else null)
        OutlinedTextField(fingerprint, { value ->
            val compact = value.filterNot(Char::isWhitespace).lowercase()
            if (compact.length <= 64 && compact.all { it in '0'..'9' || it in 'a'..'f' }) fingerprint = compact
        }, Modifier.fillMaxWidth(), label = { Text("차량 인증키 64자리") }, minLines = 2,
            supportingText = { Text("차량 화면의 인증키를 입력하세요. 첫 연결 전에 차량을 확인합니다.") })
        Button(onClick = { scanned = false; scanError = null; scanNotice = null; connect(endpoint, code, fingerprint) },
            enabled = idle && endpoint.isNotBlank() && code.length == 6 && validToken(fingerprint),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { LoadingLabel(PhoneQrCopy.CONNECT, busy && !scanned, "연결하는 중…") }
        Muted("차량 앱 › 차량 탭 › 휴대폰 연결에서 번호를 볼 수 있어요. 번호는 5분 뒤 만료돼요. 차량과 같은 Wi-Fi·핫스팟이어야 해요.")
    }
}

@Composable internal fun PhoneOpenTripCard(open: Trip, link: PhoneLink) {
    Panel(color = Scene.glass, padding = 20.dp) {
            Eyebrow("지금 운행 중 · ${phoneTime(open.startAt)} 출발")
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("%.1f".format(Locale.KOREA, open.distanceKm), style = MaterialTheme.typography.displaySmall)
                Text("km", style = MaterialTheme.typography.titleMedium, color = Scene.textSecondary, modifier = Modifier.padding(bottom = 6.dp))
            }
            Muted(listOfNotNull(open.startAddress, open.destination?.let { "→ $it" }, link.live?.socPercent?.let { "배터리 $it%" }).joinToString(" · ").ifBlank { "차량 신호 수신 중" })
    }
}

private class PendingIncidentReport(val kind: IncidentKind, val tappedAt: Long, val knownIds: Set<String>, val resolvedId: String? = null)

/**
 * One tap files a report with the car's time, odometer and place. The mirror brings the record back; until then the
 * undo waits, and for a minute after that "실수예요" deletes it again. Only an accident asks first, because it opens the
 * emergency guidance.
 */
@Composable internal fun PhoneIncidentButtons(busy: Boolean, send: (SyncCommand) -> Unit, data: TripLogData? = null, paired: Boolean = true) {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    var pending by remember { mutableStateOf<PendingIncidentReport?>(null) }
    var confirmAccident by rememberSaveable { mutableStateOf(false) }
    val report: (IncidentKind) -> Unit = { kind ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pending = if (data != null) PendingIncidentReport(kind, System.currentTimeMillis(), data.incidents.map { it.id }.toSet()) else null
        send(SyncCommand.ReportIncident(kind, ""))
        if (data == null) followUp(FollowUp("${kind.label} 기록을 차량에 보냈어요"))
    }
    val undo: (String) -> Unit = { id -> send(SyncCommand.DeleteIncident(id)); pending = null; followUp(FollowUp("기록을 삭제했어요")) }
    // The car assigns the id; the first mirror after the tap that lists a new open record of that kind is the one we sent.
    LaunchedEffect(data?.incidents, pending?.tappedAt) {
        val current = pending ?: return@LaunchedEffect
        if (data == null) return@LaunchedEffect
        if (current.resolvedId == null) {
            val created = newIncidentFor(current.kind, current.knownIds, data.incidents, data.profile.demoMode) ?: return@LaunchedEffect
            pending = PendingIncidentReport(current.kind, current.tappedAt, current.knownIds, created.id)
            followUp(FollowUp("${current.kind.label} 기록됨 · 차량 시각·계기판·위치 저장", "실수예요") { undo(created.id) })
        } else if (data.incidents.none { it.id == current.resolvedId }) pending = null
    }
    LaunchedEffect(pending?.tappedAt) {
        val tappedAt = pending?.tappedAt ?: return@LaunchedEffect
        delay((tappedAt + INCIDENT_UNDO_WINDOW_MILLIS - System.currentTimeMillis()).coerceAtLeast(0))
        if (pending?.tappedAt == tappedAt) pending = null
    }
    // One panel, four 48-dp chips; 사고 reads in the scene's negative, the others carry the outline token (AA on glass by day).
    val negative = Scene.negative
    val outline = Scene.outline
    Panel(padding = 20.dp) {
        Eyebrow("사고·특이사항 · 차량 시각·계기판·위치로 기록")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IncidentKind.entries.forEach { kind ->
                val urgent = kind == IncidentKind.ACCIDENT
                OutlinedButton(onClick = { if (urgent) confirmAccident = true else report(kind) }, enabled = !busy && paired,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp).semantics { contentDescription = "${kind.label} 기록" },
                    shape = RoundedCornerShape(14.dp), contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
                    border = BorderStroke(1.dp, if (urgent) negative else outline)) {
                    Text(kind.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (urgent) negative else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        if (!paired) Muted("차량과 연결되면 기록할 수 있어요.")
        pending?.let { current ->
            Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (current.resolvedId == null) "${current.kind.label} 기록 중…" else "${current.kind.label} 기록됨 · 시각·계기판·위치 저장",
                    Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { current.resolvedId?.let(undo) }, enabled = current.resolvedId != null && !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("실수예요") }
            }
        }
    }
    if (confirmAccident) AlertDialog(onDismissRequest = { confirmAccident = false }, title = { Text("사고로 기록할까요?") },
        text = { Text("시각·계기판·위치가 저장되고 119·112 안내가 떠요.") },
        confirmButton = { TextButton(onClick = { confirmAccident = false; report(IncidentKind.ACCIDENT) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("기록") } },
        dismissButton = { TextButton(onClick = { confirmAccident = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("취소") } })
}

/** An open report mirrored from the car: the phone adds the words and the photos the car cannot. Steps can be ticked off as a local memo. */
@Composable internal fun PhoneIncidentCard(incident: Incident, data: TripLogData, busy: Boolean, send: (SyncCommand) -> Unit,
    photos: PhotoStore, photoVersion: Int, capture: (String) -> Unit, fetch: (String) -> Unit) {
    val followUp = LocalFollowUp.current
    val context = LocalContext.current
    val prefs = remember(context) { runCatching { phoneUiPreferences(context) }.getOrNull() }
    var note by rememberSaveable(incident.id, incident.note) { mutableStateOf(incident.note) }
    var marks by remember(incident.id) { mutableStateOf(parseStepMarks(prefs?.getString(incidentStepsKey(incident.id), null))) }
    val steps = incidentSteps(incident.kind)
    Panel(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = .35f), padding = 20.dp) {
        Eyebrow("${incident.kind.label} · 대응 중 · ${phoneTime(incident.at, "M/d HH:mm")}", MaterialTheme.colorScheme.error)
        Text("지금 할 일", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        steps.forEachIndexed { index, step ->
            val done = index in marks
            Row(Modifier.fillMaxWidth().toggleable(value = done, role = Role.Checkbox) { checked ->
                marks = if (checked) marks + index else marks - index
                prefs?.edit { putString(incidentStepsKey(incident.id), encodeStepMarks(marks)) }
            }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Checkbox(checked = done, onCheckedChange = null)
                Text("${index + 1}. $step", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).alpha(if (done) .6f else 1f))
            }
        }
        Muted(listOfNotNull(incident.odometerKm?.let { "계기판 %,.1f km".format(Locale.KOREA, it) }, incident.address,
            data.drivers.firstOrNull { it.id == incident.driverId }?.label).joinToString(" · ").ifBlank { "차량 정보 수신 대기" })
        PhotoStrip(incident.photos, photos, photoVersion, size = 88.dp, missing = fetch, onRemove = { photo -> send(SyncCommand.RemovePhoto(incident.id, photo.id)) })
        OutlinedButton(onClick = { capture(incident.id) }, enabled = !busy && incident.photos.size < MAX_INCIDENT_PHOTOS,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
            Text(if (incident.photos.isEmpty()) "현장 사진 촬영 · 차량에 저장" else "사진 추가 촬영 (${incident.photos.size}/$MAX_INCIDENT_PHOTOS)")
        }
        OutlinedTextField(note, { if (it.length <= 500) note = it }, Modifier.fillMaxWidth(), label = { Text("메모 · 상대 차량번호, 상황") }, minLines = 2, shape = RoundedCornerShape(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { send(SyncCommand.UpdateIncident(incident.id, note, null, null)); followUp(FollowUp("메모를 저장했어요")) }, enabled = !busy && note != incident.note,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("메모 저장") }
            OutlinedButton(onClick = {
                send(SyncCommand.UpdateIncident(incident.id, note.takeIf { it != incident.note }, IncidentStatus.CLOSED, null))
                followUp(FollowUp("처리 완료로 바꿨어요", "되돌리기") { send(SyncCommand.UpdateIncident(incident.id, null, IncidentStatus.OPEN, null)) })
            }, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("처리 완료") }
        }
    }
}

@Composable internal fun PhoneChargeCard(session: ChargeSession, busy: Boolean, send: (SyncCommand) -> Unit) {
    val followUp = LocalFollowUp.current
    var amount by rememberSaveable(session.endAt) { mutableStateOf("") }
    val word = if (session.fuel) "주유" else "충전"
    Panel(padding = 20.dp) {
        Eyebrow("차량이 $word 종료를 감지했어요 · ${phoneTime(session.startAt)}–${phoneTime(session.endAt)}")
        Text(if (session.socFrom != null && session.socTo != null) "${if (session.fuel) "연료" else "배터리"} ${session.socFrom}% → ${session.socTo}%" else "$word 비용을 입력해 주세요",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), singleLine = true,
                label = { Text("$word 비용 (원)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp))
            Button(onClick = { send(SyncCommand.ResolveCharge(amount.toLong())); followUp(FollowUp("$word 비용 ${formatWon(amount.toLong())}을 등록했어요")) },
                enabled = !busy && (amount.toLongOrNull() ?: 0) > 0, modifier = Modifier.heightIn(min = 56.dp), shape = RoundedCornerShape(14.dp)) { Text("등록") }
        }
        TextButton(onClick = { send(SyncCommand.ResolveCharge(null)); followUp(FollowUp("비용 없음으로 넘겼어요")) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("비용 없음") }
        Muted("등록한 금액은 가장 최근 운행의 ‘주유·충전’ 경비로 들어가요.")
    }
}


/**
 * Status and setup for the card feed. The permission is asked once; "나중에" folds the card to one line for 30 days
 * (SharedPreferences phone-ui) so the same question does not greet every visit. [showImport] is off on the 경비 tab,
 * which has its own statement card.
 */
@Composable internal fun PhoneCardLinkCard(granted: Boolean, queued: Int, busy: Boolean, openSettings: () -> Unit, importStatement: () -> Unit, showImport: Boolean = true) {
    val context = LocalContext.current
    val prefs = remember(context) { runCatching { phoneUiPreferences(context) }.getOrNull() }
    var laterUntil by remember { mutableLongStateOf(prefs?.getLong(ALERT_PROMPT_LATER_KEY, 0L) ?: 0L) }
    val deferred = !granted && alertPromptDeferred(laterUntil, System.currentTimeMillis())
    if (deferred) {
        Surface(shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (queued > 0) "카드 알림 연결 안 됨 · 대기 ${queued}건" else "카드 알림 연결 안 됨", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = openSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("설정") }
            }
        }
        return
    }
    Panel(padding = 20.dp) {
        Eyebrow("카드 자동 연동")
        Text(if (granted) "카드 승인 알림을 차량 기록과 맞추고 있어요" else "카드 승인 알림을 연결하면 비용이 저절로 채워져요",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Muted("알림에서 금액·가맹점·시각·카드 끝 4자리만 읽어 차량에 보내요. 원문과 다른 앱의 알림은 저장하지 않아요.")
        if (!granted) Muted("Android 설정에서 SHAREMATE의 알림 접근을 허용해 주세요. 카드사 앱이나 문자 승인 알림만 골라 읽어요.")
        if (queued > 0) Muted("차량에 보낼 결제 ${queued}건 대기 중 · 연결되면 자동 전송")
        if (!granted) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = openSettings, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("알림 접근 허용") }
            OutlinedButton(onClick = {
                val until = System.currentTimeMillis() + ALERT_PROMPT_LATER_MILLIS
                prefs?.edit { putLong(ALERT_PROMPT_LATER_KEY, until) }
                laterUntil = until
            }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("나중에") }
        }
        if (showImport) {
            OutlinedButton(onClick = importStatement, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) {
                Text("카드 명세서 가져오기 · CSV·XLSX")
            }
            Muted("하이패스처럼 승인 알림이 없는 결제는 월말 명세서로 맞춰요.")
        }
    }
}

/**
 * The review question from the car, answerable from the passenger seat or the office. The matcher's reasons unfold as
 * a numbered list; both answers can be taken back from the confirmation bar. [onDecided] lets a host row dim itself
 * until the mirror moves the charge out of the review list.
 */
@Composable internal fun PhoneChargeReviewCard(charge: CardCharge, data: TripLogData, busy: Boolean, send: (SyncCommand) -> Unit, onDecided: (() -> Unit)? = null) {
    val followUp = LocalFollowUp.current
    val trip = suggestedTrip(charge, data)
    val reasons = reasonSteps(charge.reason)
    var expanded by rememberSaveable(charge.id) { mutableStateOf(false) }
    val label = "${charge.merchant} ${formatWon(charge.amountWon)}"
    val confirm: () -> Unit = {
        if (trip != null) {
            send(SyncCommand.ConfirmCharge(charge.id, trip.id)); onDecided?.invoke()
            followUp(FollowUp("${label}을 운행에 반영했어요", "회사 비용 아님") { send(SyncCommand.ExcludeCharge(charge.id)) })
        }
    }
    val exclude: () -> Unit = {
        send(SyncCommand.ExcludeCharge(charge.id)); onDecided?.invoke()
        followUp(if (trip != null) FollowUp("${label}을 회사 비용 아님으로 제외했어요", "되돌리기") { send(SyncCommand.ConfirmCharge(charge.id, trip.id)) }
            else FollowUp("${label}을 회사 비용 아님으로 제외했어요"))
    }
    Panel(padding = 20.dp) {
        Eyebrow("카드 결제 확인 · ${phoneTime(charge.at, if (charge.timeKnown) "M/d HH:mm" else "M/d")}")
        Text("${formatWon(charge.amountWon)} · ${charge.merchant}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        Muted(trip?.let { "${phoneTime(it.startAt)} 출발 ${"%.1f".format(Locale.KOREA, it.distanceKm)} km · ${it.destination ?: it.endAddress ?: "주소 미기록"}" } ?: "맞는 운행을 찾지 못했어요")
        when {
            reasons.size > 1 -> {
                TextButton(onClick = { expanded = !expanded }, modifier = Modifier.heightIn(min = 48.dp).semantics { stateDescription = if (expanded) "펼침" else "접힘" },
                    contentPadding = PaddingValues(horizontal = 4.dp)) { Text(if (expanded) "판단 근거 ${reasons.size}가지 · 접기" else "판단 근거 ${reasons.size}가지 · 펼치기") }
                if (expanded) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    reasons.forEachIndexed { index, reason -> Muted("${index + 1}. $reason") }
                }
            }
            reasons.size == 1 -> Muted("판단 근거 · ${reasons[0]}")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (trip != null) Button(onClick = confirm, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("이 운행 비용") }
            OutlinedButton(onClick = exclude, enabled = !busy, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = RoundedCornerShape(14.dp)) { Text("회사 비용 아님") }
        }
    }
}
