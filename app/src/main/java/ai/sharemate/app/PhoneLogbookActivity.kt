package ai.sharemate.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class PhoneLogbookActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)) { finish(); return }
        setContent { SharemateTheme(compact = true) { PhoneLogbookApp(intent?.data) } }
    }
}

@Composable private fun PhoneLogbookApp(initialUri: Uri?) {
    val context = LocalContext.current
    val repository = remember { TripLogRepository.get(context) }
    val state by repository.state.collectAsState()
    var pending by remember { mutableStateOf<TripLogData?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var exported by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var employeeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var employeeMenu by remember { mutableStateOf(false) }
    var month by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    var selected by remember { mutableStateOf<Trip?>(null) }
    val zone = ZoneId.systemDefault()
    val scope = rememberCoroutineScope()
    val data = state.data
    val period = YearMonth.parse(month)
    val monthTrips = tripsIn(data.trips.filter { it.demo == data.profile.demoMode }, period.atDay(1), period.atEndOfMonth(), zone)
    val trips = monthTrips.filter { employeeFilter == null || it.driverId.orEmpty() == employeeFilter }
    val totals = logbookTotals(trips)
    val filterLabel = if (employeeFilter == null) "전체 직원" else data.drivers.firstOrNull { it.id == employeeFilter }?.label ?: "직원 미지정"
    val employees = employeeUsage(monthTrips, data.drivers, data.profile.reimbursePerKmWon)
    val exportFile = remember { File(context.cacheDir, "phone-logbook.xlsx") }
    fun act(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { error = "파일을 처리하지 못했어요. SHAREMATE 운행파일인지, 저장 공간이 있는지 확인해 주세요. 기존 기록은 보존했습니다." }
            finally { busy = false }
        }
    }
    val import: (Uri) -> Unit = { uri -> act {
        val copy = withContext(Dispatchers.IO) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    require(output.size() + read <= LOGBOOK_MAX_BYTES)
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            } ?: throw IOException()
            TripLogCodec.decode(bytes).also { require(it.open == null) }
        }
        pending = copy
    } }
    val importer = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) import(uri) }
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(XLSX_MIME)) { uri ->
        if (uri != null && exported) act {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { out -> exportFile.inputStream().use { it.copyTo(out) } } ?: throw IOException()
                }
                message = "Excel 파일을 저장했어요. 파일 앱에서 열거나 공유할 수 있어요."
            } finally { exported = false }
        } else exported = false
    }
    LaunchedEffect(Unit) { repository.ensureLoaded() }
    LaunchedEffect(initialUri) { if (initialUri != null) import(initialUri) }
    val pick: () -> Unit = { importer.launch(arrayOf(LOGBOOK_MIME, "application/json", "application/octet-stream")) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SHAREMATE", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (data.trips.isEmpty()) "법인차 운행 · 경비" else listOf(data.profile.plateNumber.ifBlank { "차량번호 미입력" }, data.profile.companyName).filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = pick, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape = RoundedCornerShape(16.dp)) { Text("파일 갱신") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (!state.loaded) SharemateSplash(Modifier.weight(1f))
            else if (data.trips.isEmpty()) Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SharemateTheme(compact = true, dark = true) { Panel {
                    Eyebrow("운행은 차량에서, 확인은 어디서든")
                    Text("운행일지를\n휴대폰으로 가져오세요", style = MaterialTheme.typography.headlineLarge)
                    VehicleDrawing(Modifier.fillMaxWidth().height(150.dp))
                } }
                Muted("차량 앱의 월간현황에서 ‘휴대폰에서 이어 보기 · 운행파일 저장’을 선택하세요. 이 휴대폰으로 파일을 옮겨 가져오면 조회와 Excel 저장이 가능합니다.")
                Button(onClick = pick, enabled = !busy && !state.loadFailed, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text("운행파일 가져오기") }
                if (state.loadFailed) Text("저장된 사본을 읽지 못했어요. 기존 파일을 보존하고 있습니다.", color = MaterialTheme.colorScheme.error)
            } else {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { month = period.minusMonths(1).toString() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ 이전") }
                    Text("${period.year}년 ${period.monthValue}월", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    TextButton(onClick = { month = period.plusMonths(1).toString() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("다음 ›") }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 2.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(5.dp).background(MaterialTheme.colorScheme.onSurfaceVariant, RoundedCornerShape(50)))
                    Text(if (data.profile.demoMode) "시연 기록 사본 · 파일로 갱신" else "가져온 기록 사본 · 파일로 갱신", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Segmented(listOf("요약", "운행 ${trips.size}건", "Excel"), tab, Modifier.weight(1f)) { tab = it }
                }
                Box(Modifier.padding(horizontal = 16.dp)) {
                    TextButton(onClick = { employeeMenu = true }, modifier = Modifier.heightIn(min = 48.dp)) { Text("직원 · $filterLabel ▾", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    DropdownMenu(expanded = employeeMenu, onDismissRequest = { employeeMenu = false }) {
                        DropdownMenuItem(text = { Text("전체 직원") }, onClick = { employeeFilter = null; employeeMenu = false })
                        employees.forEach { employee ->
                            DropdownMenuItem(text = { Text(employee.driver?.label ?: "직원 미지정") }, onClick = {
                                employeeFilter = employee.driver?.id.orEmpty(); employeeMenu = false
                            })
                        }
                    }
                }
                Crossfade(targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth(), animationSpec = tween(160), label = "phoneSection") { section ->
                if (section == 0) Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SharemateTheme(compact = true, dark = true) {
                        Panel(Modifier.clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(Color(0xFF302B29), Color(0xFF17191D)))), color = Color.Transparent, padding = 22.dp) {
                            Eyebrow(if (data.profile.demoMode) "DEMO / 시연 월간 현황" else "MONTHLY / 이달의 이동", Color(0xFFE1B69C))
                            BoxWithConstraints {
                                val metric: @Composable (Modifier) -> Unit = { modifier ->
                                    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text("%.1f".format(Locale.KOREA, totals.totalKm), style = MaterialTheme.typography.displayMedium)
                                        Text("km · 전체 운행", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text("업무 %.1f km".format(Locale.KOREA, totals.businessKm), style = MaterialTheme.typography.labelLarge, color = Color(0xFFE1B69C))
                                    }
                                }
                                if (maxWidth >= 300.dp && totals.totalKm < 10_000) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                    metric(Modifier.weight(1f))
                                    RatioRing(totals.ratio, 100.dp, "업무 비율")
                                } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    metric(Modifier.fillMaxWidth())
                                    RatioRing(totals.ratio, 100.dp, "업무 비율")
                                }
                            }
                            HorizontalDivider(color = Color.White.copy(alpha = .12f))
                            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                Stat("완료 운행", "${totals.trips}건", Modifier.weight(1f))
                                Stat("등록 경비", formatWon(totals.expenseWon), Modifier.weight(1f))
                            }
                        }
                    }
                    Panel(padding = 22.dp) {
                        Eyebrow("등록 경비 · 종류별 내역")
                        ExpenseType.entries.forEach { type ->
                            val amount = trips.sumOf { trip -> trip.expenses.filter { it.type == type }.sumOf { it.amountWon } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(type.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatWon(amount), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        if (data.profile.reimbursePerKmWon > 0) {
                            Stat("업무 거리 정산 참고", formatWon(employeeUsage(trips, data.drivers, data.profile.reimbursePerKmWon).sumOf { it.reimbursementWon }))
                            Muted("업무 거리 × ${formatWon(data.profile.reimbursePerKmWon)}/km · 직원 미지정 제외 · 경비와 별도예요.")
                        } else Muted("차량에서 km당 지급 단가를 설정하면 업무 거리 정산액을 함께 볼 수 있어요.")
                        Muted("입력한 경비 내역입니다. 영수증·회사 지급 기준은 별도로 확인하세요.")
                    }
                    Panel(padding = 22.dp) {
                        Eyebrow("운행 목적별 거리")
                        listOf("출·퇴근" to totals.commuteKm, "일반 업무" to totals.generalKm, "개인" to totals.personalKm, "미분류" to totals.unclassifiedKm).forEach { (label, km) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("%.1f km".format(Locale.KOREA, km), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    if (employees.isNotEmpty()) Panel(padding = 22.dp) {
                        Eyebrow("이번 달 전체 직원 · 선택하면 운행 보기")
                        employees.forEach { employee ->
                            Surface(onClick = { employeeFilter = employee.driver?.id.orEmpty(); tab = 1 }, shape = RoundedCornerShape(14.dp), color = Color.Transparent) {
                                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(employee.driver?.label ?: "직원 미지정", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        Text("%.1f km  ›".format(Locale.KOREA, employee.totals.totalKm))
                                    }
                                    Muted("${employee.totals.trips}건 · 경비 ${formatWon(employee.totals.expenseWon)}")
                                }
                            }
                        }
                    }
                    Muted("Excel에는 선택한 월 · $filterLabel 기록과 직원별 요약, 경비·검토 내역을 담아요. 신고·지급 확정 자료가 아니에요.")
                    Muted("최신 기록은 차량에서 새 파일을 가져와 갱신하세요. 자동 동기화 기능은 아직 제공하지 않아요.")
                } else if (section == 1) LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (trips.isEmpty()) item { Muted("이 달의 운행이 없어요. 다른 달을 선택해 주세요.") }
                    items(trips.reversed(), key = { it.id }) { trip ->
                        Surface(onClick = { selected = trip }, shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
                            Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(Instant.ofEpochMilli(trip.startAt).atZone(zone).format(DateTimeFormatter.ofPattern("M월 d일 · HH:mm")),
                                        Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PurposeChip(trip.purpose)
                                }
                                Text("%.1f km".format(Locale.KOREA, trip.distanceKm), style = MaterialTheme.typography.headlineLarge)
                                Text(trip.destination ?: trip.endAddress ?: "주소 미기록", maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text(data.drivers.firstOrNull { it.id == trip.driverId }?.label ?: "직원 미지정", Modifier.weight(1f),
                                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(if (trip.expenses.isNotEmpty()) formatWon(trip.expenseWon) else "상세 보기  ›", style = MaterialTheme.typography.labelMedium)
                                }
                            }
                        }
                    }
                } else ExcelPreview(data, trips, period.atDay(1), period.atEndOfMonth(), Modifier.fillMaxSize().padding(horizontal = 20.dp), compact = true)
                }
                Button(onClick = {
                    act {
                        val bytes = withContext(Dispatchers.Default) { logbookXlsx(data.profile, period.atDay(1), period.atEndOfMonth(), trips, data.drivers, zone) }
                        withContext(Dispatchers.IO) { exportFile.writeBytes(bytes) }
                        exported = true
                        saver.launch("${if (data.profile.demoMode) "시연_" else ""}운행기록부_${period}${if (employeeFilter != null) "_직원선택" else ""}.xlsx")
                    }
                }, enabled = trips.isNotEmpty() && !busy && !exported, modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp).fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) {
                    Text(if (employeeFilter == null) "Excel로 내보내기" else "선택 직원 Excel 내보내기")
                }
            }
        }
    }
    pending?.let { copy -> AlertDialog(onDismissRequest = { pending = null }, title = { Text("운행기록을 가져올까요?") },
        text = { Text("${copy.profile.companyName}\n${copy.profile.plateNumber} · ${copy.trips.size}건\n\n휴대폰의 기존 사본을 이 파일로 교체합니다. 차량 원본에는 영향을 주지 않습니다.") },
        confirmButton = { TextButton(onClick = { act {
            repository.importPhoneCopy(copy)
            employeeFilter = null
            copy.trips.maxOfOrNull { it.startAt }?.let { month = YearMonth.from(Instant.ofEpochMilli(it).atZone(zone)).toString() }
            pending = null
        } }, enabled = !busy) { Text("가져오기") } }, dismissButton = { TextButton(onClick = { pending = null }) { Text("취소") } }) }
    selected?.let { trip -> AlertDialog(onDismissRequest = { selected = null }, title = { Text("운행 상세") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PurposeChip(trip.purpose)
            Text("%.1f km".format(Locale.KOREA, trip.distanceKm), style = MaterialTheme.typography.headlineLarge)
            Text("계기판 %.1f → %.1f km".format(Locale.KOREA, trip.startOdometerKm, trip.endOdometerKm))
            Text("출발: ${trip.startAddress ?: "미기록"}\n도착: ${trip.endAddress ?: "미기록"}")
            Text("사용자: ${data.drivers.firstOrNull { it.id == trip.driverId }?.label ?: "미지정"}")
            trip.expenses.forEach { Text("${it.type.label} · ${formatWon(it.amountWon)}") }
            if (trip.note.isNotBlank()) Text(trip.note)
            if (trip.recovered) Text("종료 시각 확인 필요 · 마지막 수신 시각으로 종료", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { selected = null }) { Text("닫기") } }) }
    (error ?: state.error)?.let { text -> AlertDialog(onDismissRequest = { error = null; repository.clearError() }, title = { Text("확인이 필요해요") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null; repository.clearError() }) { Text("확인") } }) }
    message?.let { text -> AlertDialog(onDismissRequest = { message = null }, title = { Text("저장 완료") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { message = null }) { Text("확인") } }) }
}
