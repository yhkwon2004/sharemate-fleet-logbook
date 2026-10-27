package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.time.Instant
import java.time.ZoneId

/** An isolated walkthrough: no repository, service, or vehicle writes. */
internal fun tutorialData(step: Int, employee: Int, purpose: TripPurpose, at: Long): TripLogData {
    require(step in 0..5 && employee in 0..1)
    val drivers = listOf(Driver("10000000-0000-0000-0000-000000000001", "영업팀", "김서연"),
        Driver("10000000-0000-0000-0000-000000000002", "운영팀", "박지훈"))
    val selected = drivers[employee].id
    val start = DriveSample(at - 1_800_000, GearState.MOVING, 12_400.0, "본사", "거래처", demo = true)
    val open = (advanceTrip(null, start, selected) { "20000000-0000-0000-0000-000000000001" }.single() as TripChange.Started).trip
    val checkpoint = (advanceTrip(open, start.copy(at = at - 1_200_000, odometerKm = 12_406.0), selected) { error("No new trip") }.single() as TripChange.Progress).trip
    val finished = (advanceTrip(checkpoint, start.copy(at = at - 600_000, gear = GearState.PARK, odometerKm = 12_412.4, address = "거래처"), selected) { error("No new trip") }
        .single() as TripChange.Finished).trip
    val trip = finished.copy(purpose = if (step >= 3) purpose else TripPurpose.UNCLASSIFIED,
        purposeSource = if (step >= 3) EditSource.DRIVER else null,
        expenses = if (step >= 4) listOf(TripExpense(ExpenseType.FUEL, 55_000, at, EditSource.DRIVER)) else emptyList(), demo = true)
    return TripLogData(FleetProfile(companyName = "SHAREMATE 시연 법인", carModel = "시연 차량", plateNumber = "시연 1234",
        reimbursePerKmWon = 200, demoMode = true), drivers, selected,
        trips = if (step >= 2) listOf(trip) else emptyList(), open = if (step == 1) open else null)
}

@Composable
internal fun DemoTutorial(exporting: Boolean, saveFile: (String, String, ByteArray) -> Unit, close: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var employee by rememberSaveable { mutableIntStateOf(0) }
    var purpose by rememberSaveable { mutableStateOf(TripPurpose.BUSINESS) }
    var voiceSummary by remember { mutableStateOf<String?>(null) }
    val at = rememberSaveable { System.currentTimeMillis() }
    val data = remember(step, employee, purpose, at) { tutorialData(step, employee, purpose, at) }
    val titles = listOf("오늘의 운전자를 선택해요", "출발하면 자동으로 기록해요", "주차 후, 목적만 확인해요", "경비도 운행과 함께 기록해요", "직원·거리·비용을 한눈에", "Excel로 정리까지 끝내요")
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        SharemateTheme {
            Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.94f), shape = RoundedCornerShape(28.dp)) {
                BoxWithConstraints(Modifier.padding(24.dp)) {
                    val wide = maxWidth >= 900.dp
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Eyebrow("SHAREMATE EXPERIENCE · ${step + 1} / 6")
                                Text("직접 체험하는 자동 운행일지", style = MaterialTheme.typography.headlineSmall)
                            }
                            TextButton(onClick = close, modifier = Modifier.heightIn(min = 56.dp)) { Text("시연 닫기") }
                        }
                        LinearProgressIndicator(progress = { (step + 1) / 6f }, modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.onSurface)
                        Muted("시연 데이터 · 실제 직원·운행기록과 차량 설정은 변경하지 않아요.")
                        val story: @Composable (Modifier) -> Unit = { modifier ->
                            SharemateTheme(dark = true) {
                                Panel(modifier, padding = 28.dp) {
                                    Eyebrow(listOf("01  DRIVER", "02  AUTO RECORD", "03  PURPOSE", "04  EXPENSE", "05  OVERVIEW", "06  EXPORT")[step], Color(0xFFE4BFA6))
                                    Text(titles[step], style = MaterialTheme.typography.headlineMedium)
                                    VehicleDrawing(Modifier.fillMaxWidth().height(if (wide) 180.dp else 105.dp))
                                    Text(when (step) { 0 -> data.drivers[employee].label; 1 -> "D → 운행 기록 시작"; 2 -> "P → 12.4 km 저장"; 3 -> "주유·충전 55,000원"; 4 -> "운행·직원·경비 통합"; else -> "운행기록부 + 직원별 현황 + 경비원장" }, style = MaterialTheme.typography.titleLarge)
                                    Muted(when (step) {
                                        0 -> "선택한 직원이 이번 운행에 기록됩니다."
                                        1 -> "시연 신호로 동일한 기록 로직을 실행합니다. 실제 SDK 연결 검증은 Virtual Console에서 진행합니다."
                                        2 -> "운행 목적은 운전자가 확인합니다. 센서가 업무 여부를 확정하지 않습니다."
                                        3 -> "영수증 인증·자동 결제 없이 입력한 비용을 보관합니다."
                                        4 -> "업무 거리 × 회사 단가의 참고액과 실제 등록 경비를 구분합니다."
                                        else -> "국세청 운행기록부 항목을 참고한 검토용 파일입니다. 세무 신고·제출은 자동으로 하지 않습니다."
                                    })
                                }
                            }
                        }
                        val detail: @Composable (Modifier) -> Unit = { modifier ->
                            Panel(modifier) {
                                when (step) {
                                    0 -> { Eyebrow("차량을 사용하는 직원"); Segmented(data.drivers.map { it.name }, employee) { employee = it }; Muted("시연용으로 등록된 직원입니다. 실제 직원은 설정에서 관리해요.") }
                                    1 -> { Eyebrow("기록 중"); Stat("출발 계기판", "12,400.0 km", large = true); Muted("출발·주차 신호와 누적거리로 운행을 기록합니다.") }
                                    2 -> { Stat("완료 운행", "12.4 km", large = true); Muted("다음 버튼으로 일반 업무 분류를 확인해요."); Segmented(listOf("출·퇴근", "일반 업무", "개인"), listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL).indexOf(purpose)) { purpose = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)[it] } }
                                    3 -> { Eyebrow("이번 운행에 경비 연결"); Stat("주유·충전", "55,000원", large = true); Muted("시연은 금액을 미리 채웁니다. 실제 경비는 운행 상세에서 종류와 금액만 입력해요.") }
                                    else -> {
                                        val totals = logbookTotals(data.trips)
                                        Stat("${data.drivers[employee].label} · 이번 운행", "12.4 km", large = true)
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Stat("업무 거리", "${totals.businessKm} km", Modifier.weight(1f)); Stat("등록 경비", formatWon(totals.expenseWon), Modifier.weight(1f)) }
                                        Muted("회사 단가 200원/km · 정산 참고 ${formatWon(reimbursements(data.trips, 200).sumOf { it.amountWon })}")
                                        if (step == 5) {
                                            Eyebrow("GLEO / 음성 액션 로직 체험")
                                            OutlinedButton(onClick = {
                                                val z = ZoneId.systemDefault()
                                                val date = Instant.ofEpochMilli(data.trips.single().startAt).atZone(z).toLocalDate()
                                                val reply = gleoDataSummaryReply(data, "today", date, z)
                                                val result = org.json.JSONArray(reply.data).getJSONObject(0)
                                                voiceSummary = "${result.getInt("trips")}건 · ${result.getDouble("totalKm")} km · 경비 ${formatWon(result.getLong("expenseWon"))}"
                                            }, modifier = Modifier.heightIn(min = 56.dp)) { Text("“오늘 운행 요약해 줘” 로직 실행") }
                                            voiceSummary?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
                                            Muted("내부 요약 처리 체험입니다. 실제 마이크·Gleo 연결은 스키마 등록과 승인 후 사용할 수 있어요.")
                                        }
                                    }
                                }
                            }
                        }
                        if (wide) BoxWithConstraints(Modifier.weight(1f)) {
                            val height = maxHeight
                            Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = height), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(20.dp)) { story(Modifier.weight(1f)); detail(Modifier.weight(1f)) }
                        }
                        else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { story(Modifier); detail(Modifier) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (step > 0) OutlinedButton(onClick = { step-- }, modifier = Modifier.heightIn(min = 60.dp)) { Text("이전") }
                            Button(onClick = {
                                if (step < 5) step++ else {
                                    val z = ZoneId.systemDefault()
                                    val from = Instant.ofEpochMilli(data.trips.single().startAt).atZone(z).toLocalDate()
                                    val to = Instant.ofEpochMilli(at).atZone(z).toLocalDate()
                                    saveFile("SHAREMATE_시연_운행기록부.xlsx", XLSX_MIME, logbookXlsx(data.profile, from, to, data.trips, data.drivers, z))
                                }
                            }, enabled = !exporting, modifier = Modifier.weight(1f).heightIn(min = 60.dp)) {
                                Text(listOf("이 직원으로 출발 체험", "P 주차로 운행 마무리", "목적 확인", "시연 경비 등록", "Excel 내보내기 안내", "시연 Excel 저장")[step])
                            }
                        }
                    }
                }
            }
        }
    }
}
