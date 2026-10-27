package ai.sharemate.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun won(amount: Long) = "${NumberFormat.getIntegerInstance(Locale.KOREA).format(amount)}원"
private fun km(value: Double) = "${String.format(Locale.KOREA, "%.1f", value)} km"
private fun time(at: Long) = SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(at))

@Composable
fun RentalMetricsPanel(state: AppState, model: MainViewModel) {
    val session = state.session ?: return
    var editing by rememberSaveable(session.id) { mutableStateOf(false) }
    val distance = rentalDistance(session, state.snapshot, state.now)
    val cost = rentalCost(session, state.snapshot, state.now)
    val link by model.link.collectAsState()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("이동과 이용 요금", style = MaterialTheme.typography.titleLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { Text("이번 주행거리"); Text(distance?.let(::km) ?: "확인 불가", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f)) { Text("현재 예상 금액"); Text(cost?.totalWon?.let(::won) ?: if (session.rates == null) "요금 입력 전" else "정보 확인 필요", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary) }
            }
            if (state.snapshot.odometerKm.isFresh(state.now)) Text("차량 누적 주행거리 ${km(state.snapshot.odometerKm.value!!.toDouble())}", style = MaterialTheme.typography.bodyMedium)
            if (cost != null) Text("${cost.elapsedMinutes}분 · 기본·시간 요금 ${won(cost.timeWon)} · 거리 요금 ${cost.distanceWon?.let(::won) ?: "확인 불가"}")
            Text("입력한 요금으로 계산한 예상액입니다. 분 단위 올림, 거리 요금은 원 단위 올림. 보험·할인·연체료는 포함하지 않습니다.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (session.startingOdometerKm == null && session.phase == Phase.CHECK_IN) OutlinedButton(onClick = model::captureStartingDistance, modifier = Modifier.heightIn(min = 56.dp)) { Text("출발 전 거리 기준 설정") }
            TextButton(onClick = { editing = !editing }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (editing) "요금 설정 닫기" else "예상 요금표 입력") }
            if (editing) RateEditor(session) { rates -> model.updateSession { it.copy(rates = rates) }; editing = false }
            if (link.configured) {
                HorizontalDivider()
                Text(link.message, style = MaterialTheme.typography.bodyMedium)
                InspectionSummary(link.remote?.optJSONObject("inspection"))
            }
        }
    }
}

@Composable private fun RateEditor(session: RentalSession, save: (RentalRates) -> Unit) {
    var base by rememberSaveable(session.id) { mutableStateOf(session.rates?.baseWon?.toString() ?: "") }
    var minute by rememberSaveable(session.id) { mutableStateOf(session.rates?.perMinuteWon?.toString() ?: "") }
    var distance by rememberSaveable(session.id) { mutableStateOf(session.rates?.perKmWon?.toString() ?: "") }
    val fields = listOf(Triple("기본료 · 원", base, { text: String -> base = text }), Triple("분당 · 원", minute, { text: String -> minute = text }), Triple("km당 · 원", distance, { text: String -> distance = text }))
    fields.forEach { (label, value, change) -> OutlinedTextField(value, { if (it.length <= 7 && it.all(Char::isDigit)) change(it) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth()) }
    val numbers = listOf(base, minute, distance).map { it.toLongOrNull()?.takeIf { value -> value in 0..1_000_000 } }
    Button(onClick = { save(RentalRates(numbers[0]!!, numbers[1]!!, numbers[2]!!)) }, enabled = numbers.all { it != null }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("예상 요금표 적용") }
}

@Composable fun CarConnectionPanel(model: MainViewModel) {
    val status by model.link.collectAsState()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("휴대폰과 이어지는 이용", style = MaterialTheme.typography.titleLarge)
            Text(status.message)
            Text("휴대폰의 탑승 전·후 촬영 결과를 여기서 확인하고, 차량의 주행거리·예상 요금을 휴대폰으로 전달합니다.", style = MaterialTheme.typography.bodyMedium)
            if (status.configured) {
                InspectionSummary(status.remote?.optJSONObject("inspection"))
                OutlinedButton(onClick = model::disconnectPhone, modifier = Modifier.heightIn(min = 56.dp)) { Text("공유 정보 삭제 후 연결 해제") }
            } else LinkEntry("차량용 연결 정보", model::configureLink)
            Text("연결 서버가 필요합니다. 앱 실행 중 약 3초마다 공유하며, 차량 값은 SDK 수신 주기에 따라 갱신됩니다.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun LinkEntry(label: String, save: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(text, { if (it.length <= 2_048) text = it }, label = { Text(label) }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), supportingText = { Text(error ?: "서버에서 발급한 주소·토큰·인증서 지문을 붙여넣으세요.") }, isError = error != null)
    Button(onClick = {
        try { parseLink(text); save(text); text = ""; error = null }
        catch (e: IllegalArgumentException) { error = e.message ?: "연결 정보를 확인해 주세요." }
    }, enabled = text.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) { Text("안전하게 연결") }
}

@Composable internal fun InspectionSummary(summary: JSONObject?, compact: Boolean = false) {
    if (summary == null) { Text("연결된 촬영 기록이 아직 없습니다.", style = MaterialTheme.typography.bodyMedium); return }
    val candidates = summary.optInt("candidateCount")
    val retakes = summary.optInt("retakeCount")
    val incident = summary.optString("incidentType", "NONE")
    val reports = summary.optJSONArray("aiReports")
    val aiReports = (0 until minOf(reports?.length() ?: 0, 8)).mapNotNull { index ->
        runCatching { parseAiReport(reports!!.getJSONObject(index)) }.getOrNull()
    }.sortedBy { when (it.status) { ComparisonStatus.REVIEW -> 0; ComparisonStatus.RETAKE -> 1; ComparisonStatus.NO_CANDIDATE -> 2 } }
    val invalidAi = (reports?.length() ?: 0) != aiReports.size
    val aiCandidates = aiReports.sumOf { it.findings.size }
    val aiRetakes = aiReports.count { it.status == ComparisonStatus.RETAKE }
    val alert = candidates > 0 || retakes > 0 || incident != "NONE" || aiCandidates > 0 || aiRetakes > 0 || invalidAi
    Text(if (alert) "촬영 기록 확인이 필요해요" else "휴대폰 촬영 기록", color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
    Text("탑승 전 ${if (summary.optBoolean("beforeComplete")) "기록 완료" else "촬영 중"} · 탑승 후 ${if (summary.optBoolean("afterComplete")) "기록 완료" else "촬영 전·진행 중"}")
    if (compact) return
    HorizontalDivider()
    Text("AI 사진 판독", style = MaterialTheme.typography.titleMedium)
    if (aiReports.isEmpty()) Text(if (invalidAi) "판독 기록을 확인하지 못했어요." else "공유된 AI 판독 기록이 아직 없어요.")
    else {
        Text("확인 후보 ${aiCandidates}곳 · 재촬영 ${aiRetakes}면 · 판독 사진 ${aiReports.size}장", fontWeight = FontWeight.Medium)
        aiReports.forEach { report ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val needsReview = report.status != ComparisonStatus.NO_CANDIDATE
                val label = when (report.status) {
                    ComparisonStatus.REVIEW -> "손상 의심 · 직접 확인"
                    ComparisonStatus.RETAKE -> "재촬영 필요"
                    ComparisonStatus.NO_CANDIDATE -> "판독 후보 없음 · 직접 점검 필요"
                }
                Text("${report.stage.label} · ${report.view.label} · $label", style = MaterialTheme.typography.labelLarge,
                    color = if (needsReview) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(report.summary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    if (invalidAi) Text("일부 AI 기록을 읽지 못했습니다. 휴대폰에서 원본과 판독 상태를 확인해 주세요.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    Text("AI 후보가 없어도 손상이 없다는 뜻은 아닙니다. 사진의 흔적은 직접 확인해 주세요.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Text("기기 내 사진 비교 · 변화 의심 ${candidates}개 · 재촬영 ${retakes}면", style = MaterialTheme.typography.bodyMedium)
    Text(when (incident) { "ACCIDENT" -> "직접 남긴 사고 기록 있음"; "SCRATCH" -> "직접 남긴 긁힘 기록 있음"; "OTHER" -> "직접 남긴 기타 기록 있음"; else -> "직접 남긴 사고 기록 없음" })
    if (summary.optString("note").isNotBlank()) Text(summary.optString("note"), style = MaterialTheme.typography.bodyMedium)
    Text("원본 사진·영상은 촬영한 휴대폰에서 확인하세요. AI 판독과 사진 비교는 손상 여부·발생 시점·책임을 확정하지 않습니다.", style = MaterialTheme.typography.bodySmall)
}

@Composable fun PairedInspectionStatus(model: MainViewModel) {
    val link by model.link.collectAsState()
    if (link.configured) OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("휴대폰 촬영으로 마무리", style = MaterialTheme.typography.titleLarge)
            Text(link.message)
            InspectionSummary(link.remote?.optJSONObject("inspection"))
            Text("연결된 휴대폰에서 이용 후 네 방향 사진과 둘레 영상을 남긴 뒤 기록을 마무리해 주세요.", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable fun MobileConnectionPanel(onSessionChanged: (String?) -> Unit = {}, inspectionSummary: JSONObject? = null, visible: Boolean = true) {
    val context = LocalContext.current
    val link = remember { DeviceLink(context, "phone") }
    var config by remember { mutableStateOf(runCatching { link.load() }.getOrNull()) }
    var enteringLink by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(LinkStatus(configured = config != null, message = if (config == null) "차량 연결 전" else "차량 확인 중")) }
    val latestSummary by rememberUpdatedState(inspectionSummary)
    val callback by rememberUpdatedState(onSessionChanged)
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(config, lifecycle) {
        val selected = config ?: return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            status = LinkStatus(true, "차량 정보 다시 확인 중")
            callback(null)
            var uploaded: String? = null
            while (true) {
                try {
                    val remote = link.request(selected, "/v1/state")
                    val vehicle = remote.optJSONObject("telemetry")
                    val now = System.currentTimeMillis()
                    val fresh = currentTelemetry(vehicle?.optLong("sentAt") ?: 0L, remote.optLong("telemetryReceivedAt"), remote.optLong("receivedAt"), now, remote.optBoolean("closed"))
                    val id = vehicle?.optString("sessionId")?.takeIf(::validUuid)
                    callback(if (fresh) id else null)
                    val summary = latestSummary
                    if (fresh && id != null && summary?.optString("sessionId") == id && summary.toString() != uploaded) {
                        link.request(selected, "/v1/inspection", summary)
                        uploaded = summary.toString()
                    }
                    status = LinkStatus(true, when {
                        remote.optBoolean("closed") -> "이용이 종료됐어요 · 다음 이용은 새로 연결"
                        vehicle == null || id == null -> "차량 앱에서 이용 시작 대기"
                        fresh -> "차량과 연결됨"
                        else -> "차량 정보가 오래됐어요 · 차량 앱 확인"
                    }, remote, now, current = fresh && id != null)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { status = LinkStatus(true, "연결 확인 필요 · 최신 차량 정보 없음"); callback(null) }
                delay(3_000)
            }
        }
    }
    if (visible) OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("내 차량 · 실시간 이용", style = MaterialTheme.typography.titleLarge)
            Text(status.message, color = MaterialTheme.colorScheme.primary)
            if (config == null) {
                if (!enteringLink) OutlinedButton(onClick = { enteringLink = true }) { Text("차량과 연결하기") }
                else LinkEntry("휴대폰용 연결 정보") { value -> scope.launch { try { withContext(Dispatchers.IO) { link.save(value) }; config = parseLink(value); enteringLink = false } catch (_: Exception) { status = LinkStatus(message = "연결 정보를 저장하지 못했습니다.") } } }
            }
            else {
                val vehicle = status.remote?.optJSONObject("telemetry")
                if (vehicle != null) {
                    Text(if (vehicle.optString("source") == "DEMO") "시연 데이터 · 실제 차량 정보 아님" else "Pleos 차량 수신 정보", style = MaterialTheme.typography.labelLarge)
                    if (!status.current) Text("마지막 수신 기록 · 현재 거리·요금·배터리가 아닙니다", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
                    Text("${if (status.current) "이번 주행" else "마지막 주행거리"} ${if (vehicle.isNull("distanceKm")) "확인 불가" else km(vehicle.optDouble("distanceKm"))}", style = MaterialTheme.typography.headlineSmall)
                    Text("${if (status.current) "예상 금액" else "마지막 예상 금액"} ${if (vehicle.isNull("costWon")) "요금·거리 설정 확인" else won(vehicle.optLong("costWon"))}", style = MaterialTheme.typography.headlineSmall)
                    Text("배터리 ${if (vehicle.isNull("soc")) "확인 불가" else "${vehicle.optInt("soc")}%"} · 누적 ${if (vehicle.isNull("odometerKm")) "확인 불가" else km(vehicle.optDouble("odometerKm"))}")
                    Text("반납 장소 ${vehicle.optString("returnPlace").ifBlank { "이용 시작 전" }}")
                    if (!vehicle.isNull("returnAt")) Text("반납 ${SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(vehicle.optLong("returnAt")))}")
                    Text("차량 전송 ${time(vehicle.optLong("sentAt"))} · 요금은 입력한 기준의 예상액", style = MaterialTheme.typography.bodySmall)
                }
                Text("촬영 요약·사고 메모만 연결된 차량에 공유합니다. 원본은 이 휴대폰에 보관합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { link.clear() }; config = null; status = LinkStatus(message = "차량 연결 해제됨"); callback(null) } }) { Text("휴대폰 연결 해제") }
            }
        }
    }
}
