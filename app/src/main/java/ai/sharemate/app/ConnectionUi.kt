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

private fun clock(at: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
private fun distanceText(session: RentalSession, value: Double?) = value?.let(::km) ?: if (session.startingOdometerKm == null) "거리 기준 필요" else "확인 불가"
private fun costText(session: RentalSession, total: Long?) = total?.let(::won) ?: if (session.rates == null) "요금 입력 전" else "거리 확인 필요"
private fun forecastNote(forecast: CostForecast) =
    if (forecast.routeIncluded) "반납지까지 ${km(forecast.remainingKm!!)} · ${clock(forecast.arrivalAt!!)} 도착 예상 기준"
    else "반납 경로를 연결하면 남은 거리와 도착 시각까지 예상해요"

@Composable private fun FareFigure(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 1,
            color = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

// Distance, cost so far, and the expected total on return: the three numbers a car-share user checks while driving.
@Composable
fun FareSummary(state: AppState, editRates: () -> Unit) {
    val session = state.session ?: return
    val cost = rentalCost(session, state.snapshot, state.now)
    val forecast = costForecast(session, state.snapshot, state.route, state.now)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FareFigure("이번 주행", distanceText(session, rentalDistance(session, state.snapshot, state.now)), Modifier.weight(1f))
            FareFigure("지금까지 비용", costText(session, cost?.totalWon), Modifier.weight(1f))
            FareFigure("반납 시 예상", costText(session, forecast?.totalWon), Modifier.weight(1f), highlight = true)
        }
        if (cost != null) Text("대여·보험 ${won(cost.fixedWon)} · 주행 ${cost.distanceWon?.let(::won) ?: "확인 불가"} · 지연 ${won(cost.lateWon)}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (forecast != null) Text(forecastNote(forecast), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (session.rates == null) Button(onClick = editRates, modifier = Modifier.heightIn(min = 56.dp)) { Text("카셰어 요금 입력") }
        else TextButton(onClick = editRates, modifier = Modifier.heightIn(min = 48.dp)) { Text("요금 수정") }
    }
}

@Composable private fun FareRow(label: String, now: String, expected: String, emphasis: Boolean = false) {
    val weight = if (emphasis) FontWeight.SemiBold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1.2f), color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = weight)
        Text(now, Modifier.weight(1f), fontWeight = weight)
        Text(expected, Modifier.weight(1f), fontWeight = weight, color = if (emphasis) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
fun FareDetails(state: AppState, model: MainViewModel, editRates: () -> Unit) {
    val session = state.session ?: return
    val cost = rentalCost(session, state.snapshot, state.now)
    val forecast = costForecast(session, state.snapshot, state.route, state.now)
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("이번 이용 요금", style = MaterialTheme.typography.titleLarge)
            if (cost == null || forecast == null) {
                Text("카셰어 앱의 예약 화면에 나온 대여요금·보험료·km당 주행요금을 입력하면 지금까지 비용과 반납 시 예상 비용을 계산해요.")
                Button(onClick = editRates, modifier = Modifier.heightIn(min = 56.dp)) { Text("카셰어 요금 입력") }
            } else {
                FareRow("", "지금까지", "반납 시 예상")
                FareRow("대여·보험", won(cost.fixedWon), won(cost.fixedWon))
                FareRow("주행", "${cost.distanceWon?.let(::won) ?: "확인 불가"}\n${distanceText(session, cost.distanceKm)}",
                    "${forecast.distanceWon?.let(::won) ?: "확인 불가"}\n${distanceText(session, forecast.distanceKm)}")
                FareRow("반납 지연", won(cost.lateWon), won(forecast.lateWon))
                HorizontalDivider()
                FareRow("합계", costText(session, cost.totalWon), costText(session, forecast.totalWon), emphasis = true)
                Text(forecastNote(forecast), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = editRates, modifier = Modifier.heightIn(min = 48.dp)) { Text("요금 수정") }
            }
            if (state.snapshot.odometerKm.isFresh(state.now)) Text("차량 누적 주행거리 ${km(state.snapshot.odometerKm.value!!.toDouble())}", style = MaterialTheme.typography.bodyMedium)
            if (session.startingOdometerKm == null && session.phase == Phase.CHECK_IN) OutlinedButton(onClick = model::captureStartingDistance, modifier = Modifier.heightIn(min = 56.dp)) { Text("출발 전 거리 기준 설정") }
            Text("주행요금은 원 단위 올림, 지연 요금은 시작된 10분마다 더합니다. 할인·쿠폰·추가 과금은 포함하지 않으며 운영사 확정 청구액이 아닙니다.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun RateField(label: String, value: String, digits: Int, change: (String) -> Unit, modifier: Modifier) =
    OutlinedTextField(value, { if (it.length <= digits && it.all(Char::isDigit)) change(it) }, label = { Text(label) },
        suffix = { Text("원") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = modifier)

@Composable
fun RateEditor(session: RentalSession, save: (RentalRates) -> Unit) {
    var rental by rememberSaveable(session.id) { mutableStateOf(session.rates?.rentalWon?.toString() ?: "") }
    var insurance by rememberSaveable(session.id) { mutableStateOf(session.rates?.insuranceWon?.toString() ?: "") }
    var perKm by rememberSaveable(session.id) { mutableStateOf(session.rates?.perKmWon?.toString() ?: "") }
    var late by rememberSaveable(session.id) { mutableStateOf(session.rates?.latePer10MinWon?.toString() ?: "") }
    Text("카셰어 앱의 예약·요금 화면에 나온 금액을 입력하세요. 주행요금이 없는 차량이나 지연 요금을 모르면 0을 입력합니다.", style = MaterialTheme.typography.bodyMedium)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RateField("대여요금 · 예약 금액", rental, 8, { rental = it }, Modifier.weight(1f))
        RateField("보험 · 보장상품", insurance, 7, { insurance = it }, Modifier.weight(1f))
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        RateField("주행요금 · km당", perKm, 7, { perKm = it }, Modifier.weight(1f))
        RateField("반납 지연 · 10분당", late, 7, { late = it }, Modifier.weight(1f))
    }
    val numbers = listOf(rental, insurance, perKm, late).map { it.toLongOrNull() }
    val rates = if (numbers.any { it == null }) null
        else RentalRates(numbers[0]!!, numbers[1]!!, numbers[2]!!, numbers[3]!!).takeIf { runCatching { validateRates(it) }.isSuccess }
    Button(onClick = { save(rates!!) }, enabled = rates != null, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("요금 적용") }
}

@Composable fun CarConnectionPanel(model: MainViewModel, showRecords: () -> Unit) {
    val status by model.link.collectAsState()
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("휴대폰과 이어지는 이용", style = MaterialTheme.typography.titleLarge)
            Text(status.message)
            Text("휴대폰의 탑승 전·후 촬영 결과를 여기서 확인하고, 차량의 주행거리·비용을 휴대폰으로 전달합니다.", style = MaterialTheme.typography.bodyMedium)
            if (status.configured) {
                InspectionSummary(status.remote?.optJSONObject("inspection"), compact = true)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(onClick = showRecords, modifier = Modifier.heightIn(min = 56.dp)) { Text("촬영 기록 자세히") }
                    OutlinedButton(onClick = model::disconnectPhone, modifier = Modifier.heightIn(min = 56.dp)) { Text("공유 정보 삭제 후 연결 해제") }
                }
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

@Composable fun PairedInspectionStatus(model: MainViewModel, showRecords: () -> Unit) {
    val link by model.link.collectAsState()
    if (link.configured) OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("휴대폰 촬영으로 마무리", style = MaterialTheme.typography.titleLarge)
            Text(link.message)
            InspectionSummary(link.remote?.optJSONObject("inspection"), compact = true)
            Text("연결된 휴대폰에서 이용 후 네 방향 사진과 둘레 영상을 남긴 뒤 기록을 마무리해 주세요.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = showRecords, modifier = Modifier.heightIn(min = 56.dp)) { Text("촬영 기록 자세히") }
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
                    Text("${if (status.current) "지금까지 비용" else "마지막 수신 비용"} ${if (vehicle.isNull("costWon")) "요금·거리 설정 확인" else won(vehicle.optLong("costWon"))}", style = MaterialTheme.typography.headlineSmall)
                    Text("배터리 ${if (vehicle.isNull("soc")) "확인 불가" else "${vehicle.optInt("soc")}%"} · 누적 ${if (vehicle.isNull("odometerKm")) "확인 불가" else km(vehicle.optDouble("odometerKm"))}")
                    Text("반납 장소 ${vehicle.optString("returnPlace").ifBlank { "이용 시작 전" }}")
                    if (!vehicle.isNull("returnAt")) Text("반납 ${SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(vehicle.optLong("returnAt")))}")
                    Text("차량 전송 ${time(vehicle.optLong("sentAt"))} · 입력한 카셰어 요금 기준이며 확정 청구액이 아닙니다", style = MaterialTheme.typography.bodySmall)
                }
                Text("촬영 요약·사고 메모만 연결된 차량에 공유합니다. 원본은 이 휴대폰에 보관합니다.", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { withContext(Dispatchers.IO) { link.clear() }; config = null; status = LinkStatus(message = "차량 연결 해제됨"); callback(null) } }) { Text("휴대폰 연결 해제") }
            }
        }
    }
}
