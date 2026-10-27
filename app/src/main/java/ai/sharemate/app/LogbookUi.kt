package ai.sharemate.app

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt

private val zone: ZoneId get() = ZoneId.systemDefault()
private fun hm(at: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
private fun localDate(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
private fun dayLabel(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일 (${weekday(date)})"
private fun kmText(value: Double) = String.format(Locale.KOREA, "%,.1f km", value)
private fun ratioText(ratio: Double?) = ratio?.let { "${(it * 100).roundToInt()}%" } ?: "–"
private fun durationText(ms: Long): String {
    val minutes = ceil(ms.coerceAtLeast(0) / 60_000.0).toLong()
    return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
}
private fun routeText(trip: Trip) = listOfNotNull(trip.startAddress, trip.destination ?: trip.endAddress)
    .filter { it.isNotBlank() }.joinToString("  →  ").ifBlank { "주소 미기록 · 계기판 거리로 기록" }

private typealias Act = (suspend () -> Unit) -> Unit
private val purposeChoices = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)

@Composable private fun purposeColor(purpose: TripPurpose): Color {
    val pleos = LocalPleosColors.current
    return when (purpose) {
        TripPurpose.COMMUTE -> pleos.positive
        TripPurpose.BUSINESS -> pleos.active
        TripPurpose.PERSONAL -> pleos.textTertiary
        TripPurpose.UNCLASSIFIED -> pleos.negative
    }
}

/** Car screen of the automatic corporate trip log. Recording itself runs in [TripLogService]. */
@Composable
fun LogbookApp(driving: DrivingUiState, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { TripLogRepository.get(context) }
    val stored by repository.state.collectAsState()
    val state = stored.copy(data = stored.data.copy(trips = stored.data.trips.filter { it.demo == stored.data.profile.demoMode }))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var driverFor by remember { mutableStateOf<String?>(null) }
    var pickingDriver by remember { mutableStateOf(false) }
    var tutorial by rememberSaveable { mutableStateOf(false) }
    var expenseTripId by rememberSaveable { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val act: Act = { block ->
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message ?: "입력 내용을 확인해 주세요." }
            catch (_: Exception) { error = "저장하지 못했어요. 다시 시도해 주세요." }
        }
    }
    val now by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(15_000) } }
    LaunchedEffect(Unit) { repository.ensureLoaded() }
    val blocked = driving.automotive && (!driving.available || driving.restricted)
    LaunchedEffect(blocked) { if (blocked) pickingDriver = false }
    val openDriver: (String?) -> Unit = { tripId -> if (!blocked) { driverFor = tripId; pickingDriver = true } }
    var pendingExportName by rememberSaveable { mutableStateOf<String?>(null) }
    val exportFile = remember { File(context.cacheDir, "pending-logbook-export") }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data?.takeIf { result.resultCode == android.app.Activity.RESULT_OK }
        if (uri == null || pendingExportName == null) pendingExportName = null
        else act {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "wt")?.use { output -> exportFile.inputStream().use { it.copyTo(output) } }
                        ?: throw IOException()
                }
                info = "운행기록부를 저장했어요. 엑셀에서 열어 확인해 주세요."
            } finally { pendingExportName = null }
        }
    }
    val export: (String, String, ByteArray) -> Unit = { name, mime, bytes ->
        if (pendingExportName == null) {
            pendingExportName = name
            act {
                try {
                    withContext(Dispatchers.IO) { exportFile.writeBytes(bytes) }
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(android.content.Intent.CATEGORY_OPENABLE); type = mime; putExtra(android.content.Intent.EXTRA_TITLE, name)
                        }
                        val target = intent.resolveActivity(context.packageManager)
                        // AAOS exposes a DocumentsUI stub that immediately cancels instead of showing a picker.
                        if (target == null || target.packageName == "com.android.car.frameworkpackagestubs") throw ActivityNotFoundException()
                        saver.launch(intent)
                    } catch (_: ActivityNotFoundException) {
                        val file = withContext(Dispatchers.IO) {
                            File(context.getExternalFilesDir("exports") ?: context.filesDir, name).also { exportFile.copyTo(it, overwrite = true) }
                        }
                        pendingExportName = null
                        info = "파일 선택기를 사용할 수 없어 앱 보관 폴더에 저장했어요.\n${file.absolutePath}"
                    }
                } catch (e: Exception) { pendingExportName = null; throw e }
            }
        }
    }
    SharemateTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 1100.dp
                val gutter = if (wide) 32.dp else 20.dp
                Column(Modifier.fillMaxSize()) {
                    TopBar(state, now, wide, gutter, { openDriver(null) }, { if (!blocked) tutorial = true })
                    when {
                        blocked -> ParkOnly(Modifier.weight(1f))
                        !state.loaded -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                        else -> {
                            val body: @Composable (Modifier) -> Unit = { modifier ->
                                Box(modifier.padding(start = gutter, end = gutter, top = 8.dp, bottom = 20.dp)) {
                                    when (tab) {
                                        0 -> DrivePane(state, now, wide, act, repository, openDriver, requestPermissions, { tab = 3 }, { tab = it }, {
                                            expenseTripId = state.data.trips.maxByOrNull { it.startAt }?.id
                                            tab = 1
                                        })
                                        1 -> LogPane(state, now, wide, act, repository, openDriver, expenseTripId)
                                        2 -> ReportPane(state, now, wide, pendingExportName != null, export) { tab = 3 }
                                        else -> SettingsPane(state, wide, act, repository) { info = it }
                                    }
                                }
                            }
                            val labels = listOf("홈", "운행기록", "월간현황", "설정")
                            val badges = listOf(0, state.data.trips.count { it.purpose == TripPurpose.UNCLASSIFIED }, 0, 0)
                            if (wide) Row(Modifier.weight(1f)) { NavRail(labels, badges, tab) { tab = it }; body(Modifier.weight(1f).fillMaxHeight()) }
                            else Column(Modifier.weight(1f)) {
                                Segmented(labels.mapIndexed { i, l -> if (badges[i] > 0) "$l ${badges[i]}" else l }, tab, Modifier.padding(horizontal = gutter).padding(bottom = 8.dp)) { tab = it }
                                body(Modifier.weight(1f).fillMaxWidth())
                            }
                        }
                    }
                }
            }
        }
        if (pickingDriver && !blocked) DriverPicker(state.data, driverFor, act, repository, { pickingDriver = false }) { pickingDriver = false; tab = 3 }
        if (tutorial && !blocked) DemoTutorial(pendingExportName != null, export) { tutorial = false }
        val message = error ?: state.error
        if (message != null && !blocked) AlertDialog(onDismissRequest = { error = null; repository.clearError() }, title = { Text("확인이 필요해요") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null; repository.clearError() }) { Text("확인") } })
        info?.takeUnless { blocked }?.let { text -> AlertDialog(onDismissRequest = { info = null }, title = { Text("완료") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { info = null }) { Text("확인") } }) }
    }
}

@Composable private fun TopBar(state: TripLogState, now: Long, wide: Boolean, gutter: Dp, onDriver: () -> Unit, onTutorial: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val driver = state.data.drivers.firstOrNull { it.id == state.data.currentDriverId }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 12.dp)) {
    val narrow = maxWidth < 720.dp
    val brand: @Composable () -> Unit = {
        Column {
            Text("SHAREMATE", style = MaterialTheme.typography.titleLarge, letterSpacing = 4.sp, fontWeight = FontWeight.Bold)
            Text("FLEET JOURNAL  /  법인차 운행일지", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        }
    }
    val employee: @Composable () -> Unit = {
        Surface(onClick = onDriver, shape = RoundedCornerShape(16.dp), color = colors.surface, modifier = Modifier.heightIn(min = 56.dp)) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
                Text("이용 직원", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                Text(driver?.label ?: "직원 선택", style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (wide) 230.dp else 140.dp))
            }
        }
    }
    if (narrow) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.weight(1f)) { brand() }; TextButton(onClick = onTutorial, modifier = Modifier.heightIn(min = 56.dp)) { Text("시연 체험") } }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)) { StatusPill(state) }; employee() }
    } else Row(Modifier.fillMaxWidth().heightIn(min = 80.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(1f)) { brand() }
        StatusPill(state)
        employee()
        OutlinedButton(onClick = onTutorial, modifier = Modifier.heightIn(min = 56.dp), border = BorderStroke(1.dp, colors.outlineVariant)) { Text("시연 체험") }
    }
    }
}

@Composable private fun StatusPill(state: TripLogState) {
    val pleos = LocalPleosColors.current
    val recording = state.data.open != null
    val (label, color) = when {
        state.loadFailed || state.error != null -> "기록 저장 확인 필요" to pleos.negative
        state.signal !in setOf(SignalStatus.READY, SignalStatus.DEMO) -> state.signal.label to pleos.negative
        recording -> "기록 중 · ${kmText(state.data.open!!.distanceKm)}" to pleos.active
        state.signal == SignalStatus.READY -> "자동 기록 대기" to pleos.active.copy(alpha = .55f)
        state.signal == SignalStatus.DEMO -> "시연 데이터" to pleos.gleoSpeaker
        state.signal == SignalStatus.STARTING -> state.signal.label to pleos.textTertiary
        else -> state.signal.label to pleos.negative
    }
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable private fun NavRail(labels: List<String>, badges: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pleos = LocalPleosColors.current
    Column(Modifier.width(128.dp).fillMaxHeight().padding(start = 20.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(Modifier.fillMaxWidth().heightIn(min = 76.dp).clip(RoundedCornerShape(22.dp))
                .background(if (active) colors.surface else Color.Transparent)
                .semantics { this.selected = active; role = Role.Tab }.clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = if (active) colors.onSurface else colors.onSurfaceVariant)
                if (badges[index] > 0) Box(Modifier.align(Alignment.TopEnd).padding(10.dp).size(24.dp).clip(CircleShape).background(pleos.negative), contentAlignment = Alignment.Center) {
                    Text("${badges[index].coerceAtMost(99)}", style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

@Composable internal fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pleos = LocalPleosColors.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(colors.surface).padding(5.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            Box(Modifier.weight(1f).heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp)).background(if (active) pleos.surfaceAccent else Color.Transparent)
                .semantics { this.selected = active; role = Role.Tab }.clickable { onSelect(index) }, contentAlignment = Alignment.Center) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (active) colors.onSurface else colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

@Composable internal fun Panel(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.surface, padding: Dp = 28.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = color,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f))) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable internal fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, letterSpacing = 0.6.sp)

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)

@Composable private fun PrimaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

@Composable private fun QuietAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 60.dp), shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = LocalPleosColors.current.surfaceHigh, contentColor = color),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) { Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center) }
}

@Composable internal fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge, color = valueColor, maxLines = 1)
    }
}

@Composable internal fun PurposeChip(purpose: TripPurpose) {
    val color = purposeColor(purpose)
    Surface(shape = RoundedCornerShape(14.dp), color = color.copy(alpha = .16f)) {
        Text(purpose.label, style = MaterialTheme.typography.labelMedium, color = when (purpose) {
            TripPurpose.PERSONAL -> MaterialTheme.colorScheme.onSurface
            TripPurpose.BUSINESS -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> color
        },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
    }
}

@Composable internal fun RatioRing(ratio: Double?, diameter: Dp, caption: String) {
    val pleos = LocalPleosColors.current
    Box(Modifier.size(diameter).semantics { contentDescription = "$caption ${ratioText(ratio)}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = size.minDimension * .075f
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(pleos.surfaceHigh, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (ratio != null && ratio > 0) drawArc(pleos.active, -90f, (360 * ratio).toFloat(), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(ratioText(ratio), style = if (diameter >= 200.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge)
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun ParkOnly(modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(40.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("P", style = MaterialTheme.typography.displayLarge, color = LocalPleosColors.current.active)
        Spacer(Modifier.height(20.dp))
        Text("운행은 자동으로 기록되고 있어요", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Muted("운전 중에는 화면 조작이 제한돼요. 정차 후 운행 목적과 기록을 확인해 주세요.", Modifier.widthIn(max = 620.dp))
    }
}

// ───────────────────────── 운행 ─────────────────────────

@Composable private fun DrivePane(state: TripLogState, now: Long, wide: Boolean, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, requestPermissions: () -> Unit, goSettings: () -> Unit, navigate: (Int) -> Unit, expense: () -> Unit) {
    val data = state.data
    val pending = data.trips.filter { it.purpose == TripPurpose.UNCLASSIFIED }.sortedByDescending { it.startAt }
    val setupNeeded = data.drivers.isEmpty() || data.profile.plateNumber.isBlank() || data.profile.companyName.isBlank()
    val task: @Composable ColumnScope.() -> Unit = {
        when {
            pending.isNotEmpty() -> PendingCard(pending.first(), pending.size, data, act, repository, openDriver)
            setupNeeded -> SetupCard(data, goSettings) { openDriver(null) }
            else -> Panel {
                val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
                Eyebrow("CURRENT DRIVER / 이용 직원")
                Text(driver?.label ?: "직원을 선택해 주세요", style = MaterialTheme.typography.headlineMedium)
                val mine = data.trips.filter { it.driverId == data.currentDriverId }.filter { localDate(it.startAt) == localDate(now) }
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("오늘 직원 운행", "${mine.size}건", Modifier.weight(1f))
                    Stat("오늘 등록 경비", formatWon(mine.sumOf { it.expenseWon }), Modifier.weight(1f))
                }
                Muted("목적 확인 완료 · 다음 운행도 자동으로 기록합니다.")
            }
        }
    }
    if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1.08f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            StatusHero(state, now, false, requestPermissions)
            MonthCard(data, now)
        }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            task()
            HomeActions(data, navigate, expense)
        }
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        StatusHero(state, now, true, requestPermissions)
        task()
        MonthCard(data, now)
        HomeActions(data, navigate, expense)
    }
}

@Composable private fun HomeActions(data: TripLogData, navigate: (Int) -> Unit, expense: () -> Unit) {
    Panel {
        Eyebrow("운행 관리, 한곳에서")
        QuietAction("운행기록 · 직원과 이동 내역", Modifier.fillMaxWidth()) { navigate(1) }
        QuietAction("경비 등록 · 최근 운행에 연결", Modifier.fillMaxWidth(), enabled = data.trips.isNotEmpty(), onClick = expense)
        PrimaryAction("이번 달 현황 · Excel", Modifier.fillMaxWidth()) { navigate(2) }
    }
}

@Composable private fun StatusHero(state: TripLogState, now: Long, compact: Boolean, requestPermissions: () -> Unit) {
    val open = state.data.open
    val demo = state.data.profile.demoMode
    val today = localDate(now)
    val todayKm = tripsIn(state.data.trips, today, today, zone).sumOf { it.distanceKm } + (open?.distanceKm ?: 0.0)
    val moving by DemoDrive.moving.collectAsState()
    SharemateTheme(dark = true) {
    val pleos = LocalPleosColors.current
    Panel(padding = if (compact) 24.dp else 32.dp) {
        Eyebrow("YOUR BUSINESS, IN MOTION", Color(0xFFE4BFA6))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(14.dp).clip(CircleShape).background(if (open != null) pleos.active else if (state.signal == SignalStatus.READY || demo) pleos.active.copy(alpha = .5f) else pleos.negative))
            Text(if (state.error != null || state.loadFailed) "기록 저장 확인 필요" else if (state.signal !in setOf(SignalStatus.READY, SignalStatus.DEMO)) state.signal.label else if (open != null) "운행 기록 중" else if (demo) "시연 모드 · 자동 기록 대기" else state.signal.label, style = MaterialTheme.typography.titleLarge)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1.1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(if (open != null) "이번 운행" else "오늘 주행거리")
                Text(kmText(open?.distanceKm ?: todayKm), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                Muted(if (open != null) "${hm(open.startAt)} 출발 · ${durationText(now - open.startAt)}" else dayLabel(today))
            }
            if (!compact) VehicleDrawing(Modifier.weight(1f).height(184.dp))
        }
        HorizontalDivider(color = pleos.surfaceHigh)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stat("계기판 누적", state.live?.odometerKm?.let(::kmText) ?: "확인 중", Modifier.weight(1.4f))
            Stat("오늘 완료 운행", "${tripsIn(state.data.trips, today, today, zone).size}건", Modifier.weight(1f))
        }
        if (open == null) {
            Muted(when {
                demo -> "시연 주행은 15배속으로 기록되고 ‘시연 데이터’로 구분돼요."
                state.signal == SignalStatus.READY -> "D 출발 → 자동 기록 · P 주차 → 저장. 운행 후 목적만 확인하세요."
                state.signal == SignalStatus.DENIED -> "계기판 누적거리를 읽으려면 차량 정보 권한이 필요해요."
                state.signal == SignalStatus.STARTING -> "차량과 연결하고 있어요."
                else -> "차량 데이터를 받을 수 없어요. 연결 상태를 확인해 주세요."
            })
        }
        if (state.signal == SignalStatus.DENIED) PrimaryAction("차량 정보 권한 허용", onClick = requestPermissions)
        if (demo) PrimaryAction(if (moving) "시연 주차 (P)" else "시연 출발 (D)", Modifier.fillMaxWidth()) { DemoDrive.setMoving(!moving) }
    }
    }
}

@Composable private fun PendingCard(trip: Trip, count: Int, data: TripLogData, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit) {
    val suggestion = remember(trip.id, data.trips.size) { suggestPurpose(trip, data.trips) }
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    Panel {
        Eyebrow(if (count > 1) "분류가 필요한 운행 ${count}건 · 가장 최근" else "방금 끝난 운행", LocalPleosColors.current.negative)
        Text("운행 목적만 확인해 주세요", style = MaterialTheme.typography.headlineMedium)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(kmText(trip.distanceKm), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"))
            Muted("${hm(trip.startAt)} – ${hm(trip.endAt ?: trip.updatedAt)}", Modifier.padding(bottom = 6.dp))
        }
        Muted(routeText(trip))
        suggestion?.let { previous ->
            Surface(shape = RoundedCornerShape(20.dp), color = LocalPleosColors.current.surfaceHigh) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("‘${previous.destination ?: previous.endAddress}’ 운행은 ${previous.purpose.label}였어요", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { act { repository.classify(trip.id, previous.purpose, EditSource.SUGGESTED) } }, modifier = Modifier.heightIn(min = 56.dp)) { Text("같게 기록") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            purposeChoices.forEach { purpose ->
                PurposeTile(purpose, Modifier.weight(1f)) { act { repository.classify(trip.id, purpose, EditSource.DRIVER) } }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Muted("사용자  ${driver?.label ?: "미지정"}", Modifier.weight(1f))
            TextButton(onClick = { openDriver(trip.id) }, modifier = Modifier.heightIn(min = 56.dp)) { Text("변경") }
        }
    }
}

@Composable private fun PurposeTile(purpose: TripPurpose, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val color = purposeColor(purpose)
    val caption = if (purpose.business) "업무 거리 포함" else "업무 합계 제외"
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 96.dp).semantics { this.selected = selected }, shape = RoundedCornerShape(24.dp),
        color = if (selected) color.copy(alpha = .22f) else LocalPleosColors.current.surfaceHigh) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(purpose.label, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun SetupCard(data: TripLogData, goSettings: () -> Unit, pickDriver: () -> Unit) {
    val steps = listOf(
        "법인·차량 정보" to (data.profile.companyName.isNotBlank() && data.profile.plateNumber.isNotBlank()),
        "운전자(사용자) 등록" to data.drivers.isNotEmpty(),
        "현재 사용자 선택" to (data.currentDriverId != null),
    )
    Panel {
        Eyebrow("시작 설정 · ${steps.count { it.second }}/${steps.size}")
        Text("운행기록부 양식에 들어갈 정보를 한 번만 등록해 주세요", style = MaterialTheme.typography.headlineSmall)
        steps.forEachIndexed { index, (label, done) ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (done) LocalPleosColors.current.active else LocalPleosColors.current.surfaceHigh), contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else "${index + 1}", style = MaterialTheme.typography.labelLarge, color = if (done) Color.Black else MaterialTheme.colorScheme.onSurface)
                }
                Text(label, style = MaterialTheme.typography.bodyLarge, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction("설정 열기", Modifier.weight(1f), onClick = goSettings)
            if (data.drivers.isNotEmpty() && data.currentDriverId == null) QuietAction("사용자 선택", Modifier.weight(1f), onClick = pickDriver)
        }
    }
}

@Composable private fun MonthCard(data: TripLogData, now: Long) {
    val today = localDate(now)
    val trips = tripsIn(data.trips, today.withDayOfMonth(1), today, zone)
    val totals = logbookTotals(trips)
    Panel {
        Eyebrow("${today.monthValue}월 운행기록부")
        BoxWithConstraints {
        val diameter = if (maxWidth < 450.dp) 112.dp else 156.dp
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            RatioRing(totals.ratio, diameter, "업무사용비율")
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Stat("총주행 거리 ⑪", kmText(totals.totalKm))
                Stat("업무용 사용거리 ⑫", kmText(totals.businessKm), valueColor = MaterialTheme.colorScheme.onTertiaryContainer)
                Stat("운행", "${totals.trips}건")
            }
        }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("등록 경비", formatWon(totals.expenseWon), Modifier.weight(1f))
            Stat("이용 직원", "${trips.mapNotNull { it.driverId }.distinct().size}명", Modifier.weight(1f))
        }
        Muted(when { trips.isEmpty() -> "이번 달 운행이 아직 없어요."; trips.any { it.purpose == TripPurpose.UNCLASSIFIED } -> "미분류 운행은 업무 합계에서 제외돼요. 목적을 확인해 주세요."; else -> "모든 운행이 분류됐어요. 리포트에서 Excel로 저장할 수 있어요." })
    }
}

@Composable private fun GleoCard(compact: Boolean) {
    val pleos = LocalPleosColors.current
    val phrases = listOf("“업무 운행으로 기록해줘”", "“통행료 3,600원 추가해줘”", "“이번 달 운행 요약 알려줘”")
    Panel(padding = if (compact) 24.dp else 28.dp) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(12.dp), color = pleos.gleoBubble) {
                Text("Gleo", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            }
            Text("음성 기록 연동 준비 중", style = MaterialTheme.typography.titleMedium)
        }
        (if (compact) phrases.take(2) else phrases).forEach { phrase ->
            Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp), color = pleos.gleoBubble.copy(alpha = .55f)) {
                Text(phrase, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
        }
        Muted("Gleo 심사와 호출자 검증을 마친 후 제공할 예정이에요. 현재는 정차 후 화면에서 분류해 주세요.")
    }
}

// ───────────────────────── 일지 ─────────────────────────

@Composable private fun LogPane(state: TripLogState, now: Long, wide: Boolean, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit, initialTripId: String?) {
    var month by rememberSaveable { mutableStateOf(YearMonth.from(localDate(now)).toString()) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var selectedId by rememberSaveable(initialTripId) { mutableStateOf(initialTripId) }
    var employeeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var employeeMenu by remember { mutableStateOf(false) }
    val period = YearMonth.parse(month)
    val all = state.data.trips
    val trips = tripsIn(all, period.atDay(1), period.atEndOfMonth(), zone).filter { employeeFilter == null || it.driverId == employeeFilter }
    val totals = logbookTotals(trips)
    val ids = trips.map { it.id }.toSet()
    val issues = auditTrips(all).filter { it.tripId in ids }
    val selected = all.firstOrNull { it.id == selectedId }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietAction("‹", Modifier.width(64.dp)) { month = period.minusMonths(1).toString() }
            Text("${period.year}년 ${period.monthValue}월", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 180.dp))
            QuietAction("›", Modifier.width(64.dp), enabled = period < YearMonth.from(localDate(now))) { month = period.plusMonths(1).toString() }
            Spacer(Modifier.weight(1f))
            if (wide) Muted("총 ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)} · ${totals.trips}건")
        }
        Segmented(listOf("전체 ${trips.size}", "점검 필요 ${issues.size}"), filter) { filter = it }
        Box {
            TextButton(onClick = { employeeMenu = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("직원: ${state.data.drivers.firstOrNull { it.id == employeeFilter }?.label ?: "전체 직원"}  ▾") }
            DropdownMenu(expanded = employeeMenu, onDismissRequest = { employeeMenu = false }) {
                DropdownMenuItem(text = { Text("전체 직원") }, onClick = { employeeFilter = null; employeeMenu = false; selectedId = null })
                state.data.drivers.forEach { driver -> DropdownMenuItem(text = { Text(driver.label) }, onClick = { employeeFilter = driver.id; employeeMenu = false; selectedId = null }) }
            }
        }
        if (!wide) Muted("총 ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)} · ${totals.trips}건")
        val list: @Composable (Modifier) -> Unit = { modifier ->
            if (filter == 0) TripList(trips, state.data, issues, selectedId, modifier) { selectedId = it }
            else IssueList(issues, state.data, modifier, act, repository, openDriver) { selectedId = it }
        }
        if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            list(Modifier.weight(1.1f).fillMaxHeight())
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface) {
                if (selected == null) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Muted("운행을 선택하면 계기판 기록과 목적·비용·수정 이력을 볼 수 있어요.", Modifier.widthIn(max = 420.dp))
                } else TripDetail(selected, state.data, act, repository, openDriver) { selectedId = null }
            }
        } else list(Modifier.weight(1f).fillMaxWidth())
    }
    if (!wide && selected != null) Dialog(onDismissRequest = { selectedId = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(.94f).fillMaxHeight(.9f), shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surface) {
            TripDetail(selected, state.data, act, repository, openDriver) { selectedId = null }
        }
    }
}

@Composable private fun TripList(trips: List<Trip>, data: TripLogData, issues: List<TripIssue>, selectedId: String?, modifier: Modifier, onSelect: (String) -> Unit) {
    if (trips.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Muted("이 달의 운행 기록이 없어요.") }
        return
    }
    val flagged = issues.map { it.tripId }.toSet()
    val days = trips.reversed().groupBy { localDate(it.startAt) }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { (date, list) ->
            item(key = "d$date") {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)) {
                    Text(dayLabel(date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Muted("${list.size}건 · ${kmText(list.sumOf { it.distanceKm })}")
                }
            }
            items(list, key = { it.id }) { trip -> TripRow(trip, data, trip.id in flagged, trip.id == selectedId) { onSelect(trip.id) } }
        }
    }
}

@Composable private fun TripRow(trip: Trip, data: TripLogData, flagged: Boolean, selected: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(if (selected) LocalPleosColors.current.surfaceHigh else colors.surface)
        .clickable(onClick = onClick).heightIn(min = 88.dp).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(68.dp)) {
            Text(hm(trip.startAt), style = MaterialTheme.typography.titleMedium)
            Text(hm(trip.endAt ?: trip.updatedAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(routeText(trip), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(kmText(trip.distanceKm), durationText(trip.durationMillis), driver?.label, if (trip.demo) "시연" else null,
                if (trip.expenses.isNotEmpty()) formatWon(trip.expenseWon) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (flagged) Box(Modifier.size(10.dp).clip(CircleShape).background(LocalPleosColors.current.negative).semantics { contentDescription = "점검 필요" })
        PurposeChip(trip.purpose)
    }
}

@Composable private fun IssueList(issues: List<TripIssue>, data: TripLogData, modifier: Modifier, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, onOpen: (String) -> Unit) {
    if (issues.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Muted("점검할 항목이 없어요. 계기판 누적거리가 끊김 없이 이어집니다.") }
        return
    }
    val trips = data.trips.associateBy { it.id }
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(issues, key = { "${it.type}-${it.tripId}" }) { issue ->
            val trip = trips[issue.tripId] ?: return@items
            Panel(padding = 22.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(LocalPleosColors.current.negative))
                    Text(issue.type.label + (issue.gapKm?.let { " · ${kmText(it)}" } ?: ""), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Muted("${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)}")
                }
                Muted(issue.type.detail)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (issue.type) {
                        IssueType.UNCLASSIFIED -> purposeChoices.forEach { purpose ->
                            QuietAction(purpose.label, Modifier.weight(1f)) { act { repository.classify(trip.id, purpose, EditSource.DRIVER) } }
                        }
                        IssueType.NO_DRIVER -> QuietAction("사용자 지정", Modifier.weight(1f)) { openDriver(trip.id) }
                        IssueType.ZERO_DISTANCE -> QuietAction("기록 삭제", Modifier.weight(1f), color = LocalPleosColors.current.negative) { act { repository.deleteTrip(trip.id) } }
                        IssueType.ODOMETER_GAP -> QuietAction("누락 구간을 운행으로 추가", Modifier.weight(1f)) {
                            act { repository.fillGap(issue.previousTripId!!, trip.id) }
                        }
                        else -> {}
                    }
                    QuietAction("자세히") { onOpen(trip.id) }
                }
            }
        }
    }
}

@Composable private fun TripDetail(trip: Trip, data: TripLogData, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit, close: () -> Unit) {
    val pleos = LocalPleosColors.current
    var note by remember(trip.id, trip.note) { mutableStateOf(trip.note) }
    var expenseType by remember(trip.id) { mutableIntStateOf(1) }
    var amount by remember(trip.id) { mutableStateOf("") }
    var deleting by remember(trip.id) { mutableStateOf(false) }
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Eyebrow(dayLabel(localDate(trip.startAt)) + if (trip.demo) " · 시연 데이터" else "")
                Text("${hm(trip.startAt)} – ${if (trip.open) "기록 중" else hm(trip.endAt!!)}", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 56.dp)) { Text("닫기") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = pleos.surfaceHigh) {
            Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(kmText(trip.distanceKm), style = MaterialTheme.typography.displaySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Stat("⑤ 주행 전 계기판", kmText(trip.startOdometerKm), Modifier.weight(1f))
                    Stat("⑥ 주행 후 계기판", kmText(trip.endOdometerKm), Modifier.weight(1f))
                }
                Text(if (trip.origin == TripOrigin.GAP_FILL) "앞뒤 운행의 계기판 값으로 보완한 구간이에요." else "차량 계기판에서 읽은 값이라 수정할 수 없어요.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Stat("출발", trip.startAddress ?: "주소 미기록")
            Stat("도착", listOfNotNull(trip.destination, trip.endAddress).distinct().joinToString(" · ").ifBlank { if (trip.open) "기록 중" else "주소 미기록" })
        }
        Eyebrow("운행 목적")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            purposeChoices.forEach { purpose ->
                PurposeTile(purpose, Modifier.weight(1f), selected = trip.purpose == purpose) { act { repository.classify(trip.id, purpose, EditSource.DRIVER) } }
            }
        }
        trip.purposeSource?.let { Muted("목적: ${trip.purpose.label} · ${it.label}") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat("④ 사용자", driver?.label ?: "미지정", Modifier.weight(1f))
            QuietAction("변경") { openDriver(trip.id) }
        }
        Eyebrow("운행 비용")
        trip.expenses.forEachIndexed { index, expense ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${expense.type.label}  ${formatWon(expense.amountWon)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Muted(expense.source.label)
                TextButton(onClick = { act { repository.removeExpense(trip.id, index) } }, modifier = Modifier.heightIn(min = 56.dp)) { Text("삭제") }
            }
        }
        Segmented(ExpenseType.entries.map { it.label }, expenseType) { expenseType = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("금액", amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), number = true, suffix = "원")
            PrimaryAction("추가", enabled = amount.toLongOrNull()?.let { it in 1..10_000_000 } == true) {
                val value = amount.toLong()
                act { repository.addExpense(trip.id, ExpenseType.entries[expenseType], value, EditSource.DRIVER); amount = "" }
            }
        }
        Eyebrow("⑩ 비고")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("방문처·업무 내용", note, { if (it.length <= 300) note = it }, Modifier.weight(1f))
            PrimaryAction("저장", enabled = note != trip.note) { act { repository.setNote(trip.id, note.trim()) } }
        }
        if (trip.history.isNotEmpty()) {
            Eyebrow("수정 이력")
            trip.history.takeLast(8).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!trip.open) TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("이 기록 삭제", color = pleos.negative) }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("운행 기록을 삭제할까요?") },
        text = { Text("삭제하면 계기판 거리가 끊겨 ‘기록되지 않은 주행’으로 표시될 수 있어요. 시동만 켠 기록이나 시연 기록만 삭제하는 것을 권장해요.") },
        confirmButton = { TextButton(onClick = { deleting = false; close(); act { repository.deleteTrip(trip.id) } }) { Text("삭제", color = pleos.negative) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("취소") } })
}

@Composable private fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, suffix: String? = null) {
    OutlinedTextField(value, onChange, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
        suffix = suffix?.let { { Text(it) } }, shape = RoundedCornerShape(18.dp),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text))
}

@Composable private fun DriverPicker(data: TripLogData, tripId: String?, act: Act, repository: TripLogRepository, dismiss: () -> Unit, goSettings: () -> Unit) {
    val current = if (tripId == null) data.currentDriverId else (data.trips.firstOrNull { it.id == tripId } ?: data.open)?.driverId
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (tripId == null) "지금 운전하는 사용자" else "이 운행의 사용자") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (data.drivers.isEmpty()) Text("등록된 사용자가 없어요. 설정에서 부서·성명을 추가해 주세요.")
            data.drivers.forEach { driver ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable {
                    act { if (tripId == null) repository.selectDriver(driver.id) else repository.setTripDriver(tripId, driver.id) }; dismiss()
                }.heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = driver.id == current, onClick = null)
                    Text(driver.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (tripId == null) Text("새 운행부터 적용되고, 기록 중인 운행에도 반영돼요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("닫기") } }, dismissButton = { TextButton(onClick = goSettings) { Text("사용자 관리") } })
}

// ───────────────────────── 리포트 ─────────────────────────

@Composable private fun ReportPane(state: TripLogState, now: Long, wide: Boolean, exporting: Boolean, saveFile: (String, String, ByteArray) -> Unit, goSettings: () -> Unit) {
    val data = state.data
    var period by rememberSaveable { mutableIntStateOf(0) }
    var confirmExport by remember { mutableStateOf(false) }
    val today = localDate(now)
    val from = if (period == 0) today.withDayOfMonth(1) else today.withDayOfYear(1)
    val trips = tripsIn(data.trips, from, today, zone)
    val totals = logbookTotals(trips)
    val yearTotals = logbookTotals(tripsIn(data.trips, today.withDayOfYear(1), today, zone))
    val export: () -> Unit = {
        val plate = data.profile.plateNumber.ifBlank { "차량" }.replace(Regex("[^\\p{L}\\p{N}-]"), "_")
        val name = "${if (data.profile.demoMode) "시연_" else ""}운행기록부_${plate}_${from}_${today}.xlsx"
        val bytes = logbookXlsx(data.profile.copy(carModel = data.profile.carModel.ifBlank { state.vehicleModel.orEmpty() }), from, today, trips, data.drivers, zone)
        saveFile(name, XLSX_MIME, bytes)
    }
    val ratio: @Composable (Modifier) -> Unit = { modifier -> RatioCard(totals, modifier) }
    val tax: @Composable (Modifier) -> Unit = { modifier -> TaxCard(data.profile, yearTotals, modifier, goSettings) }
    val settle: @Composable (Modifier) -> Unit = { modifier -> SettlementCard(trips, data, modifier, goSettings) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(listOf("이번 달", "올해"), period, Modifier.weight(1f)) { period = it }
            PrimaryAction("Excel 저장", enabled = trips.isNotEmpty() && !exporting) {
                if (totals.unclassifiedKm > 0 || trips.any { it.driverId == null }) confirmExport = true else export()
            }
        }
        QuietAction("휴대폰에서 이어 보기 · 운행파일 저장", enabled = data.trips.isNotEmpty() && !exporting) {
            saveFile("SHAREMATE_운행기록.smlog", LOGBOOK_MIME, TripLogCodec.encode(data.copy(open = null)))
        }
        Muted("${if (data.profile.demoMode) "시연 데이터 · " else ""}${from} ~ ${today} · ${totals.trips}건 · Excel에 운행·직원·경비·확인사항 포함")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) { ratio(Modifier); tax(Modifier) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        EmployeeUsageCard(trips, data, Modifier); ExpenseOverview(trips, Modifier); settle(Modifier)
                    }
                }
            } else { EmployeeUsageCard(trips, data, Modifier); ratio(Modifier); ExpenseOverview(trips, Modifier); settle(Modifier); tax(Modifier) }
            Panel {
                Eyebrow("증빙 준비 상태")
                val issues = auditTrips(trips)
                Text(if (issues.isEmpty() && trips.isNotEmpty()) "기록 기본 점검 완료" else "확인이 필요한 항목 ${issues.size}개", style = MaterialTheme.typography.titleLarge)
                Muted("직원·운행 목적·누적거리 연속성을 점검합니다. Excel의 확인사항 시트에서 세부 항목을 확인하세요.")
                Muted("국세청 운행기록부 항목 참고 · 제출 전 검토용. 영수증과 세무 요건은 별도로 확인해야 합니다.")
            }
        }
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("정리되지 않은 운행이 있어요") },
        text = { Text("미분류 ${kmText(totals.unclassifiedKm)}는 업무용 사용거리에서 제외되고, 사용자 미지정 운행은 ④란이 비어 있게 저장돼요. 그래도 저장할까요?") },
        confirmButton = { TextButton(onClick = { confirmExport = false; export() }) { Text("저장") } },
        dismissButton = { TextButton(onClick = { confirmExport = false }) { Text("정리하러 가기") } })
}

@Composable private fun EmployeeUsageCard(trips: List<Trip>, data: TripLogData, modifier: Modifier) {
    Panel(modifier) {
        Eyebrow("EMPLOYEES / 직원별 이번 기간 이용")
        val rows = employeeUsage(trips, data.drivers, data.profile.reimbursePerKmWon)
        if (rows.isEmpty()) Muted("완료된 운행이 없어요. 출발하면 자동으로 기록해요.")
        rows.forEach { row ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(row.driver?.label ?: "직원 미지정", style = MaterialTheme.typography.titleMedium)
                    Muted("${row.totals.trips}건 · 업무 ${kmText(row.totals.businessKm)}")
                }
                Text(kmText(row.totals.totalKm), style = MaterialTheme.typography.titleMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Muted("이용 직원은 상단에서 선택하고, 이전 기록은 운행 상세에서 수정할 수 있어요.")
    }
}

@Composable private fun ExpenseOverview(trips: List<Trip>, modifier: Modifier) {
    Panel(modifier) {
        Eyebrow("EXPENSES / 등록 경비")
        Stat("경비 합계", formatWon(trips.sumOf { it.expenseWon }), large = true)
        ExpenseType.entries.forEach { type ->
            Row {
                Text(type.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text(formatWon(trips.flatMap { it.expenses }.filter { it.type == type }.sumOf { it.amountWon }), style = MaterialTheme.typography.titleSmall)
            }
        }
        Muted("직접 등록한 금액입니다. 영수증 인증이나 지급 완료를 의미하지 않습니다.")
    }
}

@Composable private fun RatioCard(totals: LogbookTotals, modifier: Modifier) {
    val pleos = LocalPleosColors.current
    Panel(modifier.fillMaxHeight()) {
        Eyebrow("업무사용비율 ⑬ = ⑫ ÷ ⑪")
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RatioRing(totals.ratio, 220.dp, "업무용 ${kmText(totals.businessKm)}") }
        val rows = listOf(
            Triple("출·퇴근 ⑧", totals.commuteKm, pleos.positive), Triple("일반 업무 ⑨", totals.generalKm, pleos.active),
            Triple("개인", totals.personalKm, pleos.textTertiary), Triple("미분류", totals.unclassifiedKm, pleos.negative))
        rows.forEach { (label, km, color) ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(kmText(km), style = MaterialTheme.typography.labelLarge) }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(pleos.surfaceHigh)) {
                    if (totals.totalKm > 0 && km > 0) Box(Modifier.fillMaxWidth((km / totals.totalKm).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(color))
                }
            }
        }
        Stat("총주행 거리 ⑪", kmText(totals.totalKm))
    }
}

@Composable private fun TaxCard(profile: FleetProfile, year: LogbookTotals, modifier: Modifier, goSettings: () -> Unit) {
    val pleos = LocalPleosColors.current
    Panel(modifier.fillMaxHeight()) {
        Eyebrow("비용 참고 · 올해 업무사용비율 기준")
        val cost = profile.annualCostWon
        val ratio = year.ratio
        if (cost == null || ratio == null) {
            Text("연간 차량 비용을 입력하면 운행기록부의 효과를 계산해요", style = MaterialTheme.typography.titleMedium)
            Muted("감가상각비·유류비·보험료·자동차세 등 해당 차량의 연간 비용입니다. 운행기록부가 없으면 차량 1대당 연 1,500만 원까지만 업무용으로 인정돼요.")
            QuietAction("설정에서 입력", onClick = goSettings)
        } else {
            val effect = taxEffect(cost, ratio)
            Stat("연간 차량 관련 비용", formatWon(cost))
            Stat("업무 비율 적용액 · 단순 비교", formatWon(effect.withLogWon), valueColor = MaterialTheme.colorScheme.onTertiaryContainer, large = true)
            Stat("미작성 방식 비교액", formatWon(effect.withoutLogWon))
            HorizontalDivider(color = pleos.surfaceHigh)
            Text(when {
                effect.differenceWon > 0 -> "두 계산의 차이는 ${formatWon(effect.differenceWon)}입니다."
                effect.differenceWon < 0 -> "업무 비율 ${ratioText(ratio)}에 따른 계산액이 비교액보다 작아요."
                else -> "현재 입력 기준으로 두 계산액이 같아요."
            }, style = MaterialTheme.typography.titleSmall)
        }
        Text("보험 가입·보유 기간·비용별 한도 등을 반영하지 않은 참고 계산입니다. 실제 인정액과 신고 요건은 담당 세무사에게 확인해 주세요.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable private fun SettlementCard(trips: List<Trip>, data: TripLogData, modifier: Modifier, goSettings: () -> Unit) {
    val rate = data.profile.reimbursePerKmWon
    Panel(modifier.fillMaxHeight()) {
        Eyebrow("유류비 정산 · 업무용 거리 × km당 단가")
        if (rate <= 0) {
            Text("km당 정산 단가를 입력하면 사용자별 정산액을 계산해요", style = MaterialTheme.typography.titleMedium)
            Muted("회사에서 정한 단가를 업무용 계기판 거리에 적용합니다. 실제 주유 경비와 별개로 표시해요.")
            QuietAction("설정에서 입력", onClick = goSettings)
        } else {
            val list = employeeUsage(trips, data.drivers, rate).filter { it.driver != null && it.totals.businessKm > 0 }
            if (list.isEmpty()) Muted("이 기간의 업무용 운행이 없어요.")
            list.take(6).forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.driver!!.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Muted(kmText(item.totals.businessKm))
                    }
                    Text(formatWon(item.reimbursementWon), style = MaterialTheme.typography.titleMedium)
                }
            }
            HorizontalDivider(color = LocalPleosColors.current.surfaceHigh)
            Stat("정산 참고 합계 · km당 ${formatWon(rate)}", formatWon(list.sumOf { it.reimbursementWon }), large = true)
            Muted("직원 미지정 운행은 정산 보류입니다. 등록 경비를 이 금액에 자동으로 더하지 않아요.")
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

@Composable private fun SettingsPane(state: TripLogState, wide: Boolean, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit) {
    var section by rememberSaveable { mutableIntStateOf(0) }
    val profile: @Composable () -> Unit = { ProfileCard(state, act, repository, showInfo) }
    val drivers: @Composable () -> Unit = { DriversCard(state.data, act, repository) }
    val costs: @Composable () -> Unit = { CostCard(state.data.profile, act, repository, showInfo) }
    val privacy: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PrivacyCard(state.data.profile, act, repository)
            GleoCard(compact = !wide)
            Panel {
                Eyebrow("PLEOS SDK / 필요한 신호만 사용")
                Text("${state.signal.label} · 기어와 계기판 누적거리", style = MaterialTheme.typography.titleMedium)
                Muted("Vehicle SDK로 거리를, AAOS로 기어를 읽습니다. NaviHelper 주소는 수신한 경우에만 기록합니다.")
                Muted("권한·미지원·수신 지연은 확인 필요로 표시합니다. 차량 제어와 내비 목적지 변경은 하지 않습니다.")
            }
        }
    }
    if (wide) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) { profile(); costs() }
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) { drivers(); privacy() }
    } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Segmented(listOf("법인·차량", "사용자", "정산·세무", "기록"), section) { section = it }
        Box(Modifier.weight(1f).verticalScroll(rememberScrollState())) { when (section) { 0 -> profile(); 1 -> drivers(); 2 -> costs(); else -> privacy() } }
    }
}

@Composable private fun ProfileCard(state: TripLogState, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit) {
    val saved = state.data.profile
    var company by remember(saved) { mutableStateOf(saved.companyName) }
    var number by remember(saved) { mutableStateOf(saved.businessNumber) }
    var model by remember(saved) { mutableStateOf(saved.carModel) }
    var plate by remember(saved) { mutableStateOf(saved.plateNumber) }
    val vehicleModel = state.vehicleModel?.takeIf { it.isNotBlank() && it != "시연 차량" }
    Panel {
        Eyebrow("법인·차량 · 운행기록부 상단 정보")
        Field("법인명", company, { if (it.length <= 60) company = it })
        Field("사업자등록번호 (000-00-00000)", number, { value -> if (value.length <= 12 && value.all { it.isDigit() || it == '-' }) number = value })
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("① 차종", model, { if (it.length <= 60) model = it }, Modifier.weight(1f))
            if (vehicleModel != null && vehicleModel != model) QuietAction("차량에서 가져오기") { model = vehicleModel }
        }
        Field("② 자동차등록번호", plate, { if (it.length <= 20) plate = it })
        val changed = company != saved.companyName || number != saved.businessNumber || model != saved.carModel || plate != saved.plateNumber
        PrimaryAction("저장", Modifier.fillMaxWidth(), enabled = changed) {
            act {
                repository.updateProfile(saved.copy(companyName = company.trim(), businessNumber = number.trim(), carModel = model.trim(), plateNumber = plate.trim()))
                showInfo("법인·차량 정보를 저장했어요.")
            }
        }
    }
}

@Composable private fun DriversCard(data: TripLogData, act: Act, repository: TripLogRepository) {
    var department by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Driver?>(null) }
    Panel {
        Eyebrow("사용자 · ④ 부서와 성명")
        if (data.drivers.isEmpty()) Muted("차량을 이용하는 직원을 등록하세요. 운행마다 사용자가 기록됩니다.")
        data.drivers.forEach { driver ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { act { repository.selectDriver(driver.id) } }.heightIn(min = 64.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = driver.id == data.currentDriverId, onClick = null, modifier = Modifier.padding(start = 8.dp))
                Text(driver.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                TextButton(onClick = { editing = driver; department = driver.department; name = driver.name }, modifier = Modifier.heightIn(min = 56.dp)) { Text("수정") }
                TextButton(onClick = { act { repository.removeDriver(driver.id) } }, modifier = Modifier.heightIn(min = 56.dp)) { Text("삭제", color = LocalPleosColors.current.negative) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("부서", department, { if (it.length <= 30) department = it }, Modifier.weight(1f))
            Field("성명", name, { if (it.length <= 30) name = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction(if (editing == null) "사용자 추가" else "수정 저장", Modifier.weight(1f), enabled = name.isNotBlank()) {
                val driver = Driver(editing?.id ?: UUID.randomUUID().toString(), department.trim(), name.trim())
                act { repository.saveDriver(driver); department = ""; name = ""; editing = null }
            }
            if (editing != null) QuietAction("취소") { editing = null; department = ""; name = "" }
        }
    }
}

@Composable private fun CostCard(saved: FleetProfile, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit) {
    var rate by remember(saved) { mutableStateOf(saved.reimbursePerKmWon.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by remember(saved) { mutableStateOf(saved.annualCostWon?.toString().orEmpty()) }
    Panel {
        Eyebrow("정산·세무")
        Field("유류비 정산 단가 (km당)", rate, { if (it.length <= 6 && it.all(Char::isDigit)) rate = it }, number = true, suffix = "원")
        Field("연간 차량 관련 비용", cost, { if (it.length <= 11 && it.all(Char::isDigit)) cost = it }, number = true, suffix = "원")
        Muted("연간 비용은 감가상각비(또는 리스·렌트료), 유류비, 보험료, 자동차세, 수선비, 통행료 등의 합계입니다.")
        val changed = (rate.toLongOrNull() ?: 0) != saved.reimbursePerKmWon || cost.toLongOrNull() != saved.annualCostWon
        PrimaryAction("저장", Modifier.fillMaxWidth(), enabled = changed) {
            act {
                repository.updateProfile(saved.copy(reimbursePerKmWon = rate.toLongOrNull() ?: 0, annualCostWon = cost.toLongOrNull()))
                showInfo("정산·세무 기준을 저장했어요.")
            }
        }
    }
}

@Composable private fun PrivacyCard(saved: FleetProfile, act: Act, repository: TripLogRepository) {
    Panel {
        Eyebrow("기록 방식·개인정보")
        SwitchRow("출발·도착 주소 저장", "끄면 계기판 거리와 시각만 남기고 내비 주소는 저장하지 않아요.", saved.storeAddresses) { value ->
            act { repository.updateProfile(saved.copy(storeAddresses = value)) }
        }
        SwitchRow("시연 모드", "실제 차량 대신 15배속 시연 주행으로 기록해요. 시연 기록은 따로 표시되고 실제 기록과 섞이지 않아요.", saved.demoMode) { value ->
            if (!value) DemoDrive.setMoving(false)
            act { repository.updateProfile(saved.copy(demoMode = value)) }
        }
        HorizontalDivider(color = LocalPleosColors.current.surfaceHigh)
        Muted("기록은 이 차량의 앱 저장소에 보관되고 백업·외부 서버로 전송하지 않아요. 내비 주소는 초기 연결, 출발·주차 전환과 운행 중에 조회하며 저장 여부는 위 설정을 따릅니다.")
        Muted("Gleo 음성 연동은 심사·호출자 검증 후 제공 예정입니다. 운행 목적과 비용은 정차 후 화면에서 기록해 주세요.")
    }
}

@Composable private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable { onChange(!checked) }.heightIn(min = 72.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Muted(description)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
