package ai.sharemate.app

import android.app.DatePickerDialog
import android.app.Dialog
import android.app.TimePickerDialog
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun clockTime(at: Long) = SimpleDateFormat("HH:mm", Locale.KOREAN).format(Date(at))
private fun dateTime(at: Long) = SimpleDateFormat("M월 d일 HH:mm", Locale.KOREAN).format(Date(at))
private fun duration(ms: Long): String {
    val minutes = (ms.toDouble().let { kotlin.math.abs(it) } / 60_000).let { kotlin.math.ceil(it).toLong() }
    return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
}
private fun <T> Reading<T>.display(now: Long, format: (T) -> String = { it.toString() }): String = when {
    status == DataStatus.LOADING -> "확인 중"
    status == DataStatus.DENIED -> "권한 확인 필요"
    status != DataStatus.AVAILABLE || value == null -> "확인 불가"
    !isFresh(now) -> "다시 확인 필요"
    else -> format(value)
}

@Composable
fun SharemateApp(model: MainViewModel, driving: DrivingUiState, requestPermissions: ()->Unit) {
    val state by model.state.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sources by remember { mutableStateOf(false) }
    var demoRestricted by rememberSaveable { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val platformBlocked = if (driving.automotive) !driving.available || driving.restricted else state.sourceMode != SourceMode.DEMO
    val blocked = platformBlocked || (state.sourceMode == SourceMode.DEMO && demoRestricted)
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(blocked) { if (blocked) { keyboard?.hide(); ending = false; sources = false } }
    LaunchedEffect(state.session?.id) { tab = 0 }
    SharemateTheme {
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxSize(), color = colors.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val wide = maxWidth >= 900.dp
                Column(Modifier.fillMaxSize()) {
                    Row(Modifier.fillMaxWidth().background(colors.surface).padding(horizontal = if(wide) 32.dp else 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text("SHAREMATE /", fontSize = if(wide) 23.sp else 20.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.3.sp)
                            if (wide) Text("YOUR SPACE. YOUR PACE.", fontSize = 10.sp, letterSpacing = 1.7.sp, color = colors.onSurfaceVariant)
                        }
                        TextButton(onClick = { sources = true }, enabled = !driving.automotive || !platformBlocked,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.heightIn(min = 56.dp)) {
                            Box(Modifier.size(6.dp).background(colors.primary, RoundedCornerShape(3.dp)))
                            Spacer(Modifier.width(8.dp))
                            Text(if(state.sourceMode == SourceMode.DEMO) "시연 데이터" else "Pleos 에뮬레이터", fontSize = 16.sp)
                        }
                        if (wide) Text(clockTime(state.now), fontSize = 20.sp, color = colors.onSurfaceVariant)
                    }
                    HorizontalDivider()
                    if (state.sourceMode == SourceMode.DEMO) Row(Modifier.fillMaxWidth().background(colors.primaryContainer)
                        .padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("시연용 가상 차량 상태입니다", fontSize = 15.sp, modifier = Modifier.weight(1f), color = colors.onPrimaryContainer)
                        TextButton(onClick = { demoRestricted = !demoRestricted }, enabled = !platformBlocked) {
                            Text(if (demoRestricted) "시연 제한 해제" else "주행 제한 시연", fontSize = 15.sp)
                        }
                    }
                    when {
                        blocked -> ParkOnlyPanel(driving, state.sourceMode, demoRestricted, Modifier.weight(1f))
                        state.restoring -> Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) { CircularProgressIndicator() }
                        state.restoreError -> Column(Modifier.weight(1f).padding(32.dp),verticalArrangement=Arrangement.Center) {
                            Text("이전 이용 기록을 확인하지 못했어요",style=MaterialTheme.typography.titleLarge)
                            Muted("새 이용을 시작하려면 읽을 수 없는 이전 기록을 삭제해 주세요.")
                            PrimaryButton("이전 이용 기록 삭제",onClick=model::discardPendingSession)
                        }
                        state.pendingSession != null -> ResumePanel(model, Modifier.weight(1f))
                        state.session == null -> StartPanel(state, model, Modifier.weight(1f))
                        else -> Row(Modifier.weight(1f)) {
                            if (wide) Navigation(tab, { tab = it }, true)
                            Column(Modifier.weight(1f)) {
                                if (!wide) Navigation(tab, { tab = it }, false)
                                key(tab, state.session!!.id) {
                                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(if(wide) 32.dp else 20.dp),
                                        verticalArrangement = Arrangement.spacedBy(24.dp)) {
                                        when (tab) {
                                            0 -> HomePage(state, model) { if (state.session?.phase == Phase.ACTIVE) model.beginReturn(); tab = 3 }
                                            1 -> PersonalizePage(state, model)
                                            2 -> GuidePage(state)
                                            3 -> ReturnPage(state, model) { ending = true }
                                        }
                                        val session = state.session!!
                                        if (tab != 0) Text("${session.purpose.label}  ·  ${if(session.family) "가족 동반  ·  " else ""}${if(session.pet) "반려동물 동반  ·  " else ""}이번 대여에만 적용",
                                            color = colors.onSurfaceVariant, fontSize = 15.sp)
                                        Spacer(Modifier.height(12.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (sources && (!driving.automotive || !platformBlocked)) AlertDialog(onDismissRequest = { sources = false }, title = { Text("데이터 연결") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("연결을 바꾸면 반납 경로를 다시 확인해야 합니다. 시연 데이터는 실제 차량 정보가 아닙니다.")
                PrimaryButton("Pleos 에뮬레이터 연결") { model.setSourceMode(SourceMode.PLEOS); sources = false }
                OutlinedButton(onClick = { sources = false; requestPermissions() }, modifier = Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text("차량 정보 권한 확인") }
                HorizontalDivider()
                Text("시연 시나리오", fontWeight = FontWeight.Bold)
                listOf(DemoScenario.FAMILY_CHARGING to "가족 여행 · 충전 중", DemoScenario.PET_RETURN to "반려동물 동반 · 반납 준비",
                    DemoScenario.COMMUTE to "출퇴근 · 출발 준비", DemoScenario.REST_WORK to "휴식 · 업무", DemoScenario.UNAVAILABLE to "데이터 확인 불가")
                    .forEach { (scenario, label) -> OutlinedButton(onClick = {
                        model.setSourceMode(SourceMode.DEMO); model.setScenario(scenario); demoRestricted = false; sources = false
                    }, modifier = Modifier.fillMaxWidth().heightIn(min=56.dp)) { Text(label) } }
            } }, confirmButton = { TextButton(onClick = { sources = false }) { Text("닫기") } })
        if (state.error != null && !blocked) AlertDialog(onDismissRequest = model::clearError, title = { Text("확인이 필요해요") },
            text = { Text(state.error!!) }, confirmButton = { TextButton(onClick = model::clearError) { Text("확인") } },
            dismissButton = { if(state.session == null) TextButton(onClick = model::discardPendingSession) { Text("이전 이용 기록 삭제") } })
        if (ending && !blocked) AlertDialog(onDismissRequest = { ending = false }, title = { Text("이번 이용을 종료할까요?") },
            text = { Text("목적지·메모·맞춤 설정을 이 차량에서 삭제합니다. 실제 반납 완료는 대여 운영사에서 확인해 주세요.") },
            confirmButton = { TextButton(onClick = { model.endSession(); ending = false }) { Text("정보 삭제 후 종료") } },
            dismissButton = { TextButton(onClick = { ending = false }) { Text("계속 이용") } })
    }
}

@Composable private fun HomePage(state:AppState, model:MainViewModel, prepareReturn:()->Unit) {
    val s = state.session!!
    var pickup by rememberSaveable(s.id) { mutableStateOf(false) }
    var details by rememberSaveable(s.id) { mutableStateOf(false) }
    val link by model.link.collectAsState()
    val available = returnAvailableMillis(s, state.route, state.now)
    val overdue = s.returnAt <= state.now
    val battery = state.snapshot.soc.takeIf { it.isFresh(state.now) }?.value?.takeIf { it in 0..100 }
    val distance = rentalDistance(s, state.snapshot, state.now)
    val cost = rentalCost(s, state.snapshot, state.now)
    if (overdue) Notice("반납 예정시각이 지났어요", "운영사에서 이용 시간을 확인해 주세요.")
    else if (available != null && available <= 0) Notice("복귀 준비가 필요해요", "복귀 경로와 15분의 여유를 고려하면 출발할 시간입니다.")
    if (state.snapshot.warnings.isFresh(state.now) && !state.snapshot.warnings.value.isNullOrBlank()) Notice("차량 알림", state.snapshot.warnings.value!!)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(when(s.phase) { Phase.CHECK_IN -> "출발을 준비하는 시간"; Phase.ACTIVE -> "당신의 이동에 집중하세요"; Phase.RETURN -> "마지막까지, 편안하게" },
                style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            Tag(state.snapshot.parked.display(state.now) { if (it) "P · 주차 중" else "주차 상태 아님" })
        }
        Row(Modifier.widthIn(max = 650.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            StatusDial("배터리", battery?.let { "$it%" } ?: state.snapshot.soc.display(state.now),
                battery?.div(100f), Modifier.weight(1f))
            StatusDial(if (overdue) "반납시각 초과" else "반납까지", duration(remainingMillis(if(overdue) state.now else s.returnAt, if(overdue) s.returnAt else state.now)),
                rentalRemainingFraction(s.startedAt, s.returnAt, state.now), Modifier.weight(1f), overdue)
        }
        VehicleDrawing(Modifier.widthIn(max = 820.dp).fillMaxWidth().height(168.dp))
        Text("차량 연출 이미지 · 실제 차종과 다를 수 있어요", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.widthIn(max = 820.dp).fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Muted("이번 주행")
                Text(distance?.let { String.format(Locale.KOREA,"%.1f km",it) } ?: "확인 불가", style = MaterialTheme.typography.titleLarge)
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Muted("예상 요금")
                Text(cost?.totalWon?.let { "${NumberFormat.getIntegerInstance(Locale.KOREA).format(it)}원" }
                    ?: if(s.rates == null) "요금 미설정" else "정보 확인 필요", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text("${dateTime(s.returnAt)}  ·  ${s.returnPlace}", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium)
        PrimaryButton(when(s.phase) { Phase.CHECK_IN -> "출발 전 점검"; Phase.ACTIVE -> "반납 준비"; Phase.RETURN -> "반납 점검 이어가기" }, Modifier.widthIn(max = 440.dp)) {
            if (s.phase == Phase.CHECK_IN) pickup = true else prepareReturn()
        }
        TextButton(onClick = { details = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("차량 상태 · 요금 상세", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (link.configured) {
            Text(link.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Keep inspection alerts on the home screen even when the details are collapsed.
            InspectionSummary(link.remote?.optJSONObject("inspection"), compact = true)
        }
    }
    if (pickup && s.phase == Phase.CHECK_IN) DetailDialog("출발 전, 세 가지만 확인해요", { pickup = false }) {
        CheckLine("외관과 기존 손상을 직접 확인했어요", "pickup_exterior", s, model)
        CheckLine("필요한 비품과 소지품을 확인했어요", "pickup_supplies", s, model)
        CheckLine("낯선 조작은 차량 안내에서 확인했어요", "pickup_guide", s, model)
        if (link.configured) Muted("연결한 휴대폰의 탑승 전 촬영도 완료해 주세요.")
        PrimaryButton("점검 완료 · 이용 시작", enabled = pickupChecks.all { it in s.checks }) { pickup = false; model.completeCheckIn() }
    }
    if (details) DetailDialog("이번 이용 상세", { details = false }) {
        RentalMetricsPanel(state, model)
        VehicleStatusCard(state)
        Muted("충전: ${state.snapshot.charging.display(state.now) { if(it) "충전 중" else "충전 중 아님" }}")
        if(state.snapshot.rangeKm.isFresh(state.now)) Muted("차량 표시 주행 가능거리 ${state.snapshot.rangeKm.display(state.now) { "${it.toInt()} km" }}")
        ChargeEstimate(state)
        Text(when { available == null -> "반납 경로 확인 필요"; available <= 0 -> "지금 복귀 준비"; else -> "${duration(available)} 더 머물 수 있어요" })
        returnDepartureAt(s,state.route,state.now)?.let { Muted("복귀 출발 필요시각 ${dateTime(it)}") }
    }
}

@Composable private fun StatusDial(label:String, value:String, fraction:Float?, modifier:Modifier, alert:Boolean = false) {
    val accent = if(alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.outlineVariant
    Box(modifier.aspectRatio(1f).padding(8.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = 8.dp.toPx()
            val diameter = size.minDimension - inset * 2
            drawArc(track, -90f, 360f, false, Offset(inset,inset), Size(diameter,diameter), style = Stroke(4.dp.toPx(),cap = StrokeCap.Round))
            if(fraction != null) drawArc(accent, -90f, 360f*fraction, false, Offset(inset,inset), Size(diameter,diameter), style = Stroke(4.dp.toPx(),cap = StrokeCap.Round))
        }
        Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontSize = if(value.length <= 5) 42.sp else 25.sp, lineHeight = 32.sp, fontWeight = FontWeight.Medium,
                color = if(alert) accent else MaterialTheme.colorScheme.onSurface, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable private fun DetailDialog(title:String, close:()->Unit, content:@Composable ColumnScope.()->Unit) {
    val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * .88f
    androidx.compose.ui.window.Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.widthIn(max = 820.dp).fillMaxWidth(.94f).heightIn(max = maxHeight), shape = RoundedCornerShape(28.dp)) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = close, modifier = Modifier.heightIn(min = 56.dp)) { Text("닫기") }
                }
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
            }
        }
    }
}

@Composable private fun PersonalizePage(state:AppState,model:MainViewModel) {
    val s=state.session!!
    PageHeading("오늘의 이동에 맞추세요","목적과 동반 조건에 맞춰 필요한 안내를 모았어요.")
    SurfaceCard {
        Text("이번 이용",style=MaterialTheme.typography.titleLarge)
        PurposeSelector(s.purpose) { selected -> model.updateSession { it.copy(purpose=selected) } }
        CompanionSelector(s.family,{ selected -> model.updateSession { it.copy(family=selected) } },s.pet,{ selected -> model.updateSession { it.copy(pet=selected) } })
        SessionNoteField("이번 목적지 메모",s.destinationNote,s.id) { text -> model.updateSession { it.copy(destinationNote=text) } }
    }
    SurfaceCard {
        Text("${s.purpose.label} 준비",style=MaterialTheme.typography.titleLarge)
        val items=when(s.purpose) {
            Purpose.COMMUTE -> listOf("commute_bag" to "가방과 업무 물품 챙기기","commute_route" to "목적지와 일정을 직접 확인하기")
            Purpose.TRAVEL -> listOf("travel_luggage" to "짐과 준비물 확인하기","travel_stops" to "충전·휴식 계획 확인하기")
            Purpose.REST -> listOf("rest_place" to "휴식 가능한 주차 장소 확인하기","rest_items" to "휴식 후 소지품 챙기기")
            Purpose.WORK -> listOf("work_items" to "업무에 필요한 물품 확인하기","work_cleanup" to "업무 종료 후 자리 정리하기")
        }
        items.forEach { (key,label) -> CheckLine(label,key,s,model) }
        if(s.family) {
            HorizontalDivider(); Text("가족과 함께",fontWeight=FontWeight.Bold)
            CheckLine("카시트·탑승 준비를 직접 확인하기","family_seat",s,model)
            CheckLine("하차할 때 동승자와 짐 함께 확인하기","family_exit",s,model)
            Muted("안전벨트: ${state.snapshot.belts.display(state.now)}")
        }
        if(s.pet) {
            HorizontalDivider(); Text("반려동물과 함께",fontWeight=FontWeight.Bold)
            CheckLine("동반 허용 조건·이동장·고정 상태 확인하기","pet_carrier",s,model)
            CheckLine("물과 소지품 챙기고 이용 후 정리하기","pet_cleanup",s,model)
            Muted("공조: ${state.snapshot.hvac.display(state.now)}")
            Muted("표시된 공조 상태만으로 실내 환경의 안전을 판단하지 마세요.")
        }
    }
    SurfaceCard {
        Text("충전하는 동안",style=MaterialTheme.typography.titleLarge)
        Text("${state.snapshot.soc.display(state.now) { "$it%" }}  ·  ${state.snapshot.charging.display(state.now) { if(it) "충전 중" else "충전 중 아님" }}",fontSize=24.sp)
        ChargeEstimate(state)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Text("나의 목표 ${s.targetSoc}%",modifier=Modifier.weight(1f))
            OutlinedButton(onClick={model.updateSession { it.copy(targetSoc=(it.targetSoc-10).coerceAtLeast(10)) }},modifier=Modifier.sizeIn(minWidth=64.dp,minHeight=64.dp)) { Text("−",fontSize=24.sp) }
            OutlinedButton(onClick={model.updateSession { it.copy(targetSoc=(it.targetSoc+10).coerceAtMost(100)) }},modifier=Modifier.sizeIn(minWidth=64.dp,minHeight=64.dp)) { Text("+",fontSize=24.sp) }
        }
        if(state.snapshot.soc.isFresh(state.now) && (state.snapshot.soc.value?:-1)>=s.targetSoc) Tag("설정한 목표 SOC에 도달했어요")
        Muted("목표는 나의 확인 기준이며 차량의 충전 설정을 바꾸지 않습니다.")
    }
    SurfaceCard {
        Text("쉬어 가는 시간 · 업무 시간",style=MaterialTheme.typography.titleLarge)
        if(s.timerEnd==null) Text("종료 목표시각을 정해 보세요.")
        else {
            Text(if(s.timerEnd<=state.now) "목표시각이 되었어요" else "${duration(s.timerEnd-state.now)} 남음",fontSize=28.sp,fontWeight=FontWeight.Bold)
            DateTimePicker("종료 목표",s.timerEnd) { at -> model.updateSession { it.copy(timerEnd=at) } }
            val departure=returnDepartureAt(s,state.route,state.now)
            if(departure!=null && s.timerEnd>departure) Notice("복귀 시간과 겹쳐요","종료 목표를 앞당기거나 운영사에서 대여 시간을 확인해 주세요.")
        }
        returnDepartureAt(s,state.route,state.now)?.let { Muted("복귀 출발 필요시각 ${dateTime(it)}") }
        Row(horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            listOf(15,30,60).forEach { minutes -> OutlinedButton(onClick={model.updateSession { it.copy(timerEnd=System.currentTimeMillis()+minutes*60_000L) }},
                modifier=Modifier.weight(1f).heightIn(min=64.dp)) { Text("${minutes}분") } }
        }
        if(s.timerEnd!=null) TextButton(onClick={model.updateSession { it.copy(timerEnd=null) }}) { Text("시간 목표 해제") }
        Muted("이 화면에서 남은 시간을 안내합니다. 앱 밖에서는 정시 알림을 보내지 않습니다.")
    }
    SurfaceCard {
        Text("주차 위치 기억하기",style=MaterialTheme.typography.titleLarge)
        SessionNoteField("층·구역·찾아가는 설명",s.parkingNote,s.id) { text -> model.updateSession { it.copy(parkingNote=text) } }
        Muted("도어 잠금: ${state.snapshot.doorLocks.display(state.now)}")
    }
    CarConnectionPanel(model)
    FutureCameraCard()
}

@Composable private fun GuidePage(state:AppState) {
    PageHeading("낯선 차도 편하게","확인된 차량 상태와 공통 이용 안내입니다.")
    SurfaceCard { Text("차량 정보",style=MaterialTheme.typography.titleLarge); Text(state.snapshot.carName.display(state.now),fontSize=24.sp)
        Muted("차종별 세부 조작은 차량의 공식 설명서를 확인해 주세요.") }
    VehicleStatusCard(state)
    listOf("출발 전" to "좌석·미러·안전벨트와 차량 경고 표시를 직접 확인하세요. 조작 방법이 낯설다면 정차 상태에서 차량 설정과 공식 설명서를 확인하세요.",
        "충전할 때" to "차량과 충전기의 안내를 확인하세요. SHAREMATE는 지원되는 충전 상태만 보여주며 충전을 시작하거나 중지하지 않습니다.",
        "경고가 나타나면" to "차량의 경고 표시와 공식 설명서를 우선 확인하세요. 안전한 장소에 정차한 뒤 필요한 경우 대여 운영사에 문의하세요.",
        "반납할 때" to "소지품·외관·실내를 직접 확인하고 대여 운영사의 정식 반납 절차를 진행하세요.")
        .forEach { (title,body) -> SurfaceCard { Text(title,style=MaterialTheme.typography.titleLarge); Text(body) } }
}

@Composable private fun ReturnPage(state:AppState,model:MainViewModel,end:()->Unit) {
    val s=state.session!!
    PageHeading("마지막까지 가볍게","차량 상태와 내 물건을 확인하고 이번 이용을 마무리해요.")
    PairedInspectionStatus(model)
    SurfaceCard {
        Text("반납 일정",style=MaterialTheme.typography.titleLarge)
        SessionNoteField("반납 장소",s.returnPlace,s.id,maxLength=200) { text -> model.updateSession { it.copy(returnPlace=text) } }
        DateTimePicker("반납 예정",s.returnAt) { at -> model.updateSession { it.copy(returnAt=at) } }
        val route=state.route
        val validRoute=route!=null && state.now-route.receivedAt in 0..30_000
        Text(if(validRoute) "현재 내비 목적지: ${route!!.destinationName}" else "현재 내비 목적지를 확인할 수 없어요.")
        if(validRoute) {
            Text(if(s.boundDestinationId==route!!.destinationId) "반납지로 확인됨 · ${duration(route.remainingSeconds*1000)} 소요" else "반납 장소와 같은 목적지인지 확인해 주세요.")
            OutlinedButton(onClick={model.bindCurrentDestination(route!!.destinationId)},modifier=Modifier.fillMaxWidth().heightIn(min=64.dp)) { Text("현재 내비 목적지를 반납지로 지정") }
        }
        val available=returnAvailableMillis(s,state.route,state.now)
        if(available!=null) Text(if(available>0) "${duration(available)} 더 머물 수 있어요" else "복귀 준비가 필요해요",fontSize=24.sp,fontWeight=FontWeight.Bold)
        returnDepartureAt(s,state.route,state.now)?.let { Muted("복귀 출발 필요시각 ${dateTime(it)}") }
        Muted("복귀 소요시간에 15분의 여유를 더해 계산합니다. 내비 목적지는 앱에서 변경하지 않습니다.")
    }
    VehicleStatusCard(state)
    if(s.phase==Phase.CHECK_IN) SurfaceCard { Text("먼저 홈에서 인수 점검을 완료해 주세요.") }
    else if(s.phase!=Phase.RETURN) PrimaryButton("반납 준비 시작",onClick=model::beginReturn)
    else SurfaceCard {
        Text("직접 확인해 주세요",style=MaterialTheme.typography.titleLarge)
        CheckLine("좌석·콘솔·트렁크의 소지품을 챙겼어요","return_belongings",s,model)
        CheckLine("실내 정리와 청결 상태를 확인했어요","return_clean",s,model)
        CheckLine("외관과 차량 상태를 직접 확인했어요","return_exterior",s,model)
        CheckLine("운영사의 정식 반납 절차를 확인했어요","return_operator",s,model)
        Muted("종료하면 이번 이용의 장소·메모·설정을 삭제합니다. 이 앱의 종료가 대여 계약의 반납 완료를 의미하지 않습니다.")
        PrimaryButton("SHAREMATE 이용 종료",enabled=returnChecks.all { it in s.checks },onClick=end)
    }
}

@Composable private fun ChargeEstimate(state:AppState) {
    if(state.snapshot.charging.isFresh(state.now) && state.snapshot.charging.value==true) {
        val completion=chargeCompletionAt(state.snapshot,state.now)
        Muted(if(completion!=null) "차량 예상 충전 완료 ${dateTime(completion)} · 약 ${duration(remainingMillis(completion,state.now))} 남음" else "차량 예상 충전 완료시간 확인 불가")
        Muted("차량이 제공한 예상이며 나의 목표 SOC까지의 시간은 아닙니다.")
    }
}

@Composable private fun VehicleStatusCard(state:AppState) = SurfaceCard {
    Text("차량에서 확인한 상태",style=MaterialTheme.typography.titleLarge)
    val v=state.snapshot
    listOf("도어" to v.doors,"창문" to v.windows,"도어 잠금" to v.doorLocks,"타이어" to v.tires,"안전벨트" to v.belts,"공조" to v.hvac).forEachIndexed { index,(label,reading) ->
        if(index>0) HorizontalDivider(color=MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical=4.dp),horizontalArrangement=Arrangement.spacedBy(20.dp)) {
            Text(label,modifier=Modifier.width(100.dp),color=MaterialTheme.colorScheme.onSurfaceVariant)
            Text(reading.display(state.now),modifier=Modifier.weight(1f),fontWeight=FontWeight.Medium)
        }
    }
    Muted("확인 가능한 영역만 표시합니다. 확인 불가 항목은 직접 점검해 주세요.")
}

@Composable private fun FutureCameraCard() = SurfaceCard {
    Text("차량 카메라로 더 편하게",style=MaterialTheme.typography.titleLarge)
    Tag("개발 예정")
    listOf("하차 전 소지품 확인" to "여행·휴식·업무 후 놓고 내린 물건을 재확인하는 구상",
        "인수·반납 실내 상태 비교" to "가족·반려동물 동반 이용 전후의 실내 상태 기록 구상",
        "주차 위치 사진 카드" to "낯선 주차장의 위치와 주변 표지를 기억하는 구상").forEach { (title,body) ->
        Column(verticalArrangement=Arrangement.spacedBy(4.dp)) { Text(title,fontWeight=FontWeight.SemiBold); Muted(body) }
    }
    Muted("차량에 내장된 카메라의 촬영·분석은 개발 예정입니다. 공식 차량 카메라 지원과 권한 확인 후 연계합니다.")
}

@Composable private fun PurposeSelector(value:Purpose,onChange:(Purpose)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Purpose.entries.forEach { p ->
            val active=p==value
            OutlinedButton(onClick={onChange(p)},modifier=Modifier.weight(1f).heightIn(min=64.dp).semantics { selected=active },
                shape=RoundedCornerShape(12.dp),contentPadding=PaddingValues(horizontal=6.dp),
                colors=ButtonDefaults.outlinedButtonColors(
                    containerColor=if(active) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                    contentColor=if(active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant),
                border=androidx.compose.foundation.BorderStroke(1.dp,if(active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant)) {
                Text(p.label,fontSize=18.sp,fontWeight=if(active) FontWeight.SemiBold else FontWeight.Medium,maxLines=1)
            }
        }
    }
}

@Composable private fun CompanionSelector(family:Boolean,setFamily:(Boolean)->Unit,pet:Boolean,setPet:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        ToggleLine("가족 동반",family,setFamily,Modifier.weight(1f))
        ToggleLine("반려동물 동반",pet,setPet,Modifier.weight(1f))
    }
}
@Composable private fun ToggleLine(label:String,checked:Boolean,change:(Boolean)->Unit,modifier:Modifier=Modifier) {
    Row(modifier.heightIn(min=64.dp).clip(RoundedCornerShape(12.dp))
        .background(if(checked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
        .semantics { stateDescription=if(checked) "선택됨" else "선택 안 됨" }.clickable(role=Role.Checkbox) { change(!checked) }.padding(horizontal=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(checked,onCheckedChange=null)
        Text(label,fontSize=17.sp,modifier=Modifier.padding(start=6.dp))
    }
}
@Composable private fun CheckLine(label:String,key:String,s:RentalSession,model:MainViewModel) {
    Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clip(RoundedCornerShape(12.dp))
        .background(if(key in s.checks) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
        .semantics { stateDescription=if(key in s.checks) "확인함" else "확인 전" }
        .clickable(role=Role.Checkbox) { model.setCheck(key,key !in s.checks) }.padding(horizontal=4.dp,vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
        Checkbox(key in s.checks,onCheckedChange=null)
        Text(label,modifier=Modifier.padding(start=12.dp).weight(1f))
    }
}

@Composable private fun NoteField(label:String,value:String,change:(String)->Unit,singleLine:Boolean=false,maxLength:Int=300) {
    OutlinedTextField(value,onValueChange={if(it.length<=maxLength) change(it)},label={Text(label)},singleLine=singleLine,
        modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(12.dp),maxLines=if(singleLine) 1 else 3)
}

@Composable private fun SessionNoteField(label:String,value:String,sessionId:String,maxLength:Int=300,save:(String)->Unit) {
    var draft by remember(sessionId,label) { mutableStateOf(value) }
    Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
        NoteField(label,draft,{draft=it},maxLength=maxLength)
        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.SpaceBetween) {
            Muted(if(draft==value) "저장됨" else "변경 내용을 저장해 주세요")
            TextButton(onClick={save(draft)},enabled=draft!=value && (label!="반납 장소" || draft.isNotBlank()),modifier=Modifier.heightIn(min=56.dp)) { Text("저장") }
        }
    }
}

@Composable private fun DateTimePicker(label:String,at:Long,onChange:(Long)->Unit) {
    val context=LocalContext.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    DisposableEffect(Unit) { onDispose { dialog?.dismiss() } }
    OutlinedButton(onClick={
        val cal=Calendar.getInstance().apply { timeInMillis=at }
        dialog=DatePickerDialog(context,{_,year,month,day ->
            cal.set(Calendar.YEAR,year);cal.set(Calendar.MONTH,month);cal.set(Calendar.DAY_OF_MONTH,day)
            dialog=TimePickerDialog(context,{_,hour,minute ->
                cal.set(Calendar.HOUR_OF_DAY,hour);cal.set(Calendar.MINUTE,minute);cal.set(Calendar.SECOND,0);cal.set(Calendar.MILLISECOND,0)
                onChange(cal.timeInMillis)
            },cal.get(Calendar.HOUR_OF_DAY),cal.get(Calendar.MINUTE),true).also { it.show() }
        },cal.get(Calendar.YEAR),cal.get(Calendar.MONTH),cal.get(Calendar.DAY_OF_MONTH)).also { it.show() }
    },modifier=Modifier.fillMaxWidth().heightIn(min=64.dp),shape=RoundedCornerShape(12.dp)) {
        Text("$label   ${dateTime(at)}",fontSize=18.sp)
    }
}

@Composable private fun SurfaceCard(modifier:Modifier=Modifier,color:Color=MaterialTheme.colorScheme.surface,
    contentColor:Color=MaterialTheme.colorScheme.onSurface,content:@Composable ColumnScope.()->Unit) {
    Surface(modifier.fillMaxWidth(),shape=RoundedCornerShape(24.dp),color=color,contentColor=contentColor,
        border=androidx.compose.foundation.BorderStroke(1.dp,if(color==MaterialTheme.colorScheme.surface) MaterialTheme.colorScheme.outlineVariant else color)) {
        Column(Modifier.padding(22.dp),verticalArrangement=Arrangement.spacedBy(16.dp),content=content)
    }
}
@Composable private fun PrimaryButton(label:String,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    Button(onClick=onClick,enabled=enabled,modifier=modifier.fillMaxWidth().heightIn(min=68.dp),shape=RoundedCornerShape(14.dp),
        contentPadding=PaddingValues(horizontal=24.dp,vertical=16.dp)) { Text(label,fontSize=20.sp,fontWeight=FontWeight.SemiBold) }
}
@Composable private fun PageHeading(title:String,description:String) {
    Column(Modifier.padding(top=4.dp,bottom=2.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(title,style=MaterialTheme.typography.headlineLarge);Muted(description)
    }
}
@Composable private fun Muted(text:String) { Text(text,color=MaterialTheme.colorScheme.onSurfaceVariant,fontSize=17.sp,lineHeight=25.sp) }
@Composable private fun Eyebrow(text:String) { Text(text,color=MaterialTheme.colorScheme.primary,fontSize=12.sp,fontWeight=FontWeight.SemiBold,letterSpacing=1.8.sp) }
@Composable private fun Tag(text:String) { Text(text,fontSize=14.sp,fontWeight=FontWeight.Medium,color=MaterialTheme.colorScheme.onSurfaceVariant,
    modifier=Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp,7.dp)) }
@Composable private fun Notice(title:String,description:String) {
    Column(Modifier.fillMaxWidth().border(1.dp,Color(0xFFDAAF6B),RoundedCornerShape(14.dp)).padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title,fontWeight=FontWeight.Bold,color=Color(0xFFFFCC80))
        Text(description,fontSize=17.sp)
    }
}

@Composable internal fun VehicleDrawing(modifier:Modifier) {
    Image(painterResource(R.drawable.sharemate_silver_car),
        contentDescription = "차량 연출 이미지. 실제 차종이나 감지 결과를 나타내지 않습니다.",
        modifier = modifier, contentScale = ContentScale.Fit)
}

@Composable private fun Navigation(current: Int, onSelect: (Int)->Unit, vertical: Boolean) {
    val labels = listOf("내 차", "맞춤", "안내")
    val item: @Composable (Int, Modifier)->Unit = { index, modifier ->
        val active = index == current || (index == 0 && current == 3)
        val fg = if(active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
        Row(modifier.clip(RoundedCornerShape(16.dp)).background(if(active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .semantics { selected = active }.clickable(role = Role.Tab) { onSelect(index) }.padding(horizontal = 12.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (vertical) Text(listOf("◉", "≡", "i")[index], color = if(active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, letterSpacing=0.5.sp)
            Text(labels[index], fontWeight = if(active) FontWeight.SemiBold else FontWeight.Medium, fontSize = if(vertical) 19.sp else 16.sp,
                color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    if(vertical) Column(Modifier.width(124.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surface).padding(14.dp,30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("S / M",modifier=Modifier.padding(start=16.dp,bottom=14.dp),fontSize=11.sp,letterSpacing=1.6.sp,
            color=MaterialTheme.colorScheme.onSurfaceVariant,fontWeight=FontWeight.SemiBold)
        labels.indices.forEach { item(it,Modifier.fillMaxWidth()) }
    }
    else Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.indices.forEach { item(it,Modifier.weight(1f)) }
    }
}

@Composable private fun ParkOnlyPanel(driving:DrivingUiState, source:SourceMode, simulated:Boolean, modifier:Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp), verticalArrangement=Arrangement.Center, horizontalAlignment=Alignment.CenterHorizontally) {
        Text("P",fontSize=72.sp,fontWeight=FontWeight.Light,color=MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(22.dp))
        Text(if(!driving.automotive && source != SourceMode.DEMO) "Pleos 차량 연결이 필요해요" else "정차 후 이용해 주세요",style=MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(14.dp))
        Text(when {
            !driving.automotive && source != SourceMode.DEMO -> "일반 Android 기기에서는 위의 데이터 연결에서 시연 데이터를 선택할 수 있습니다."
            simulated -> "주행 제한 시연 중입니다. 입력과 상세 확인을 잠갔습니다."
            !driving.available -> "차량의 이용 제한 상태를 확인하고 있습니다. 연결 확인 전에는 상세 조작을 잠급니다."
            else -> "운전에 집중할 수 있도록 상세 조작을 잠갔습니다."
        }, color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.widthIn(max=560.dp))
    }
}

@Composable private fun ResumePanel(model:MainViewModel,modifier:Modifier) {
    Column(modifier.fillMaxWidth().padding(32.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
        Text("이용 중이던 기록이 있어요",style=MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(16.dp))
        Text("본인의 이용이라면 이어가세요. 새로운 이용자는 이전 정보를 삭제하고 시작합니다.",modifier=Modifier.widthIn(max=540.dp))
        Spacer(Modifier.height(26.dp))
        PrimaryButton("내 이용 이어가기",Modifier.widthIn(max=460.dp)) { model.resumeSession() }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick=model::discardPendingSession,modifier=Modifier.widthIn(max=460.dp).fillMaxWidth().heightIn(min=64.dp)) { Text("이전 정보 삭제 · 새 이용") }
    }
}

@Composable private fun StartPanel(state:AppState,model:MainViewModel,modifier:Modifier) {
    var place by rememberSaveable { mutableStateOf("") }
    var returnAt by rememberSaveable { mutableLongStateOf(System.currentTimeMillis()+4*60*60*1000) }
    var purpose by rememberSaveable { mutableStateOf(Purpose.TRAVEL) }
    var family by rememberSaveable { mutableStateOf(false) }
    var pet by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    val form: @Composable ()->Unit = {
        Text("이번 이용을 알려주세요",style=MaterialTheme.typography.titleLarge)
        Muted("예약한 반납 장소와 시각을 입력해 주세요.")
        NoteField("반납 장소",place,{place=it},singleLine=true,maxLength=200)
        DateTimePicker("반납 예정",returnAt,{returnAt=it})
        TextButton(onClick = { options = !options }, modifier = Modifier.heightIn(min = 56.dp)) { Text(if(options) "맞춤 선택 접기" else "목적·동반 조건 선택 (선택 사항)") }
        if(options) {
            PurposeSelector(purpose,{purpose=it})
            CompanionSelector(family,{family=it},pet,{pet=it})
        }
        Muted("설정과 메모는 이번 대여에만 저장됩니다.")
        PrimaryButton("공유차 이용 시작",enabled=place.isNotBlank() && returnAt>state.now) { model.startSession(returnAt,place,purpose,family,pet) }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(28.dp),verticalArrangement=Arrangement.spacedBy(24.dp)) {
        BoxWithConstraints {
            if(maxWidth>=860.dp) Row(horizontalArrangement=Arrangement.spacedBy(36.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                    Eyebrow("YOUR DAY, YOUR WAY")
                    Text("빌린 차에서도,\n내 생활 그대로.",style=MaterialTheme.typography.displayMedium)
                    Muted("출발부터 쉬어 가는 시간, 반납까지.\n이번 이동에 필요한 것만 함께 챙겨요.")
                    VehicleDrawing(Modifier.fillMaxWidth().height(280.dp))
                }
                SurfaceCard(Modifier.weight(1f)) { form() }
            } else SurfaceCard {
                Eyebrow("WELCOME TO SHAREMATE")
                Text("빌린 차에서도, 내 생활 그대로.",style=MaterialTheme.typography.titleLarge)
                form()
            }
        }
    }
}
