package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
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

private val TUTORIAL_TITLES = listOf("오늘의 운전자를 선택해요", "출발하면 자동으로 기록해요", "주차 후, 목적만 확인해요", "경비도 운행과 함께 기록해요", "직원·거리·비용을 한눈에", "Excel로 정리까지 끝내요")
private val TUTORIAL_NEXT = listOf("이 직원으로 출발 체험", "P 주차로 운행 마무리", "목적 확인", "시연 경비 등록", "Excel 내보내기 안내", "시연 Excel 저장")
private val TUTORIAL_PURPOSES = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)

/** The walkthrough's company rate (its profile's reimbursePerKmWon): the rate line and the reference sum use it. */
private const val TUTORIAL_PER_KM_WON = 200L

/**
 * The six-step walkthrough (2.10: in the scene in effect, not a fixed night; 2.13 in the car's 2.12 look,
 * docs/design/DESIGN_2_13_SPEC.md §8.5). The window fades only ([CarDialogFade]); the backdrop is the page colour inside a
 * hairline, and the story and the detail are [CarCard]s. The header is the car's: 시연 체험 with the clock icon (the 설정 row
 * that opens it), the step as a tag (6단계 중 n단계 for TalkBack) and 닫기 as a pill. Every supporting line is one line ([CarLine]),
 * the longer form in an ⓘ row's `more` or in TalkBack; the English step eyebrows of 2.10 are gone. A step change swaps both
 * cards with the card motion, and the 4-dp progress bar sweeps rather than jumps; the step's title is a heading in a polite
 * live region, so TalkBack hears the new step without a touch. The last step saves the demo file with [LoadingAction] (만드는
 * 중… while [exporting]) and closes the walkthrough once the save is over, so its answer (the bar, or the 저장 위치 dialog)
 * is seen rather than left under the walkthrough.
 */
@Composable
internal fun DemoTutorial(exporting: Boolean, saveFile: (String, String, ByteArray) -> Unit, close: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var employee by rememberSaveable { mutableIntStateOf(0) }
    var purpose by rememberSaveable { mutableStateOf(TripPurpose.BUSINESS) }
    var voiceSummary by remember { mutableStateOf<String?>(null) }
    val at = rememberSaveable { System.currentTimeMillis() }
    val data = remember(step, employee, purpose, at) { tutorialData(step, employee, purpose, at) }
    val swap = cardSwap()
    // Keyed: reopening the walkthrough (or the activity coming back) shows the bar at its step instead of sweeping from empty.
    val progress = sweepProgress((step + 1) / 6f, key = "tutorial-progress")
    // True from the last step's tap: the shell's save sets exporting in the same frame, so the walkthrough closes when it clears.
    var saving by rememberSaveable { mutableStateOf(false) }
    val latestClose by rememberUpdatedState(close)
    LaunchedEffect(saving, exporting) { if (saving && !exporting) latestClose() }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CarDialogFade()
        Surface(Modifier.fillMaxWidth(.95f).fillMaxHeight(.94f).semantics { paneTitle = OverlayCopy.TUTORIAL_TITLE }, shape = RoundedCornerShape(28.dp),
            color = Scene.bg, contentColor = Scene.text, border = BorderStroke(1.dp, Car.skin.cardBorder)) {
            BoxWithConstraints(Modifier.padding(24.dp)) {
                val wide = maxWidth >= 900.dp
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        CarSectionTitle(OverlayCopy.TUTORIAL_TITLE, Modifier.weight(1f), icon = painterResource(R.drawable.ic_clock)) {
                            CarTag(OverlayCopy.tutorialStep(step + 1), description = OverlayCopy.spokenTutorialStep(step + 1))
                        }
                        Spacer(Modifier.width(16.dp))
                        CarPillButton("닫기", onClick = close, description = OverlayCopy.SPOKEN_TUTORIAL_CLOSE)
                    }
                    LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Scene.text, trackColor = Scene.ringTrack, drawStopIndicator = {})
                    CarInfoRow(OverlayCopy.TUTORIAL_DATA, spoken = OverlayCopy.SPOKEN_TUTORIAL_DATA)
                    AnimatedContent(targetState = step, modifier = Modifier.weight(1f), transitionSpec = { swap }, label = "tutorial-step") { shown ->
                        val shownData = remember(shown, employee, purpose, at) { tutorialData(shown, employee, purpose, at) }
                        val story: @Composable (Modifier) -> Unit = { modifier ->
                            CarCard(modifier) {
                                Text(TUTORIAL_TITLES[shown], style = MaterialTheme.typography.headlineMedium, color = Scene.text,
                                    modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                                VehicleDrawing(Modifier.fillMaxWidth().height(if (wide) 180.dp else 105.dp))
                                Text(when (shown) { 0 -> shownData.drivers[employee].label; 1 -> "D → 운행 기록 시작"; 2 -> "P → 12.4 km 저장"; 3 -> "주유·충전 55,000원"; 4 -> "운행·직원·경비 통합"; else -> "운행기록부 + 직원별 현황 + 경비원장" },
                                    style = MaterialTheme.typography.titleLarge, color = Scene.text)
                                val line = OverlayCopy.TUTORIAL_STORY[shown]
                                val more = OverlayCopy.TUTORIAL_STORY_MORE[shown]
                                if (more.isNotEmpty()) CarInfoRow(line, more = more)
                                else CarLine(line, spoken = OverlayCopy.SPOKEN_TUTORIAL_OVERVIEW.takeIf { shown == 4 })
                            }
                        }
                        val detail: @Composable (Modifier) -> Unit = { modifier ->
                            CarCard(modifier) {
                                when (shown) {
                                    0 -> {
                                        CarSectionTitle(OverlayCopy.TUTORIAL_DRIVER, icon = rememberVectorPainter(Icons.Outlined.Person))
                                        Segmented(shownData.drivers.map { it.name }, employee) { employee = it }
                                        CarLine(OverlayCopy.TUTORIAL_DRIVER_LINE)
                                    }
                                    1 -> {
                                        CarTag(OverlayCopy.TUTORIAL_RECORDING, tone = PillTone.POSITIVE)
                                        Stat("출발 계기판", "12,400.0 km", large = true)
                                        CarLine(OverlayCopy.TUTORIAL_RECORDING_LINE)
                                    }
                                    2 -> {
                                        Stat("완료 운행", "12.4 km", large = true)
                                        Segmented(listOf("출·퇴근", "일반 업무", "개인"), TUTORIAL_PURPOSES.indexOf(purpose)) { purpose = TUTORIAL_PURPOSES[it] }
                                        CarLine(OverlayCopy.TUTORIAL_PURPOSE_LINE)
                                    }
                                    3 -> {
                                        // The amount in the one money style; TalkBack hears "주유·충전, 55,000원" as one stop.
                                        Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Text(OverlayCopy.TUTORIAL_EXPENSE, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                                            CarAmount(55_000, size = MoneySize.TOTAL)
                                        }
                                        CarInfoRow(OverlayCopy.TUTORIAL_EXPENSE_NOTE, more = OverlayCopy.TUTORIAL_EXPENSE_MORE)
                                    }
                                    else -> {
                                        val totals = logbookTotals(shownData.trips)
                                        Stat("${shownData.drivers[employee].label} · 이번 운행", "12.4 km", large = true)
                                        // Car mini stats, so step 4's 55,000원 reads here in the same money style (원 muted, tabular digits).
                                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            CarMiniStat(painterResource(R.drawable.ic_gauge), "업무 거리", kmText(totals.businessKm), Modifier.weight(1f))
                                            CarMiniStat(painterResource(R.drawable.ic_won), "등록 경비", won = totals.expenseWon, modifier = Modifier.weight(1f))
                                        }
                                        CarLine(OverlayCopy.tutorialRate(TUTORIAL_PER_KM_WON, reimbursements(shownData.trips, TUTORIAL_PER_KM_WON).sumOf { it.amountWon }))
                                        if (shown == 5) {
                                            CarDivider()
                                            Text(OverlayCopy.TUTORIAL_VOICE, style = MaterialTheme.typography.titleSmall, color = Scene.text, modifier = Modifier.semantics { heading() })
                                            CarExampleChip(OverlayCopy.TUTORIAL_VOICE_CHIP, actionLabel = OverlayCopy.TUTORIAL_VOICE_ACTION, onClick = {
                                                val z = ZoneId.systemDefault()
                                                val date = Instant.ofEpochMilli(shownData.trips.single().startAt).atZone(z).toLocalDate()
                                                val reply = gleoDataSummaryReply(shownData, "today", date, z)
                                                val result = org.json.JSONArray(reply.data).getJSONObject(0)
                                                voiceSummary = "${result.getInt("trips")}건 · ${result.getDouble("totalKm")} km · 경비 ${formatWon(result.getLong("expenseWon"))}"
                                            })
                                            voiceSummary?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = Scene.text,
                                                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                                            CarInfoRow(OverlayCopy.TUTORIAL_VOICE_NOTE, more = OverlayCopy.TUTORIAL_VOICE_MORE)
                                        }
                                    }
                                }
                            }
                        }
                        if (wide) BoxWithConstraints(Modifier.fillMaxSize()) {
                            val height = maxHeight
                            Row(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = height), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(20.dp)) { story(Modifier.weight(1f)); detail(Modifier.weight(1f)) }
                        }
                        else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { story(Modifier); detail(Modifier) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (step > 0) QuietAction("이전", Modifier.heightIn(min = 60.dp)) { step-- }
                        if (step < 5) PrimaryAction(TUTORIAL_NEXT[step], Modifier.weight(1f).heightIn(min = 60.dp)) { step++ }
                        else LoadingAction(TUTORIAL_NEXT[step], loading = exporting, modifier = Modifier.weight(1f).heightIn(min = 60.dp),
                            loadingLabel = OverlayCopy.TUTORIAL_SAVING) {
                            val z = ZoneId.systemDefault()
                            val from = Instant.ofEpochMilli(data.trips.single().startAt).atZone(z).toLocalDate()
                            val to = Instant.ofEpochMilli(at).atZone(z).toLocalDate()
                            saveFile("SHAREMATE_시연_운행기록부.xlsx", XLSX_MIME, logbookXlsx(data.profile, from, to, data.trips, data.drivers, z))
                            saving = true
                        }
                    }
                }
            }
        }
    }
}
