package ai.sharemate.app

import android.content.ActivityNotFoundException
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBox
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
import kotlin.math.ceil
import kotlin.math.roundToInt

internal val zone: ZoneId get() = ZoneId.systemDefault()
internal fun hm(at: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
internal fun localDate(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
internal fun dayLabel(date: LocalDate) = "${date.monthValue}월 ${date.dayOfMonth}일 (${weekday(date)})"

internal fun kmText(value: Double) = String.format(Locale.KOREA, "%,.1f km", value)
internal fun ratioText(ratio: Double?) = ratio?.let { "${(it * 100).roundToInt()}%" } ?: "–"
internal fun durationText(ms: Long): String {
    val minutes = ceil(ms.coerceAtLeast(0) / 60_000.0).toLong()
    return if (minutes >= 60) "${minutes / 60}시간 ${minutes % 60}분" else "${minutes}분"
}
internal fun routeText(trip: Trip) = listOfNotNull(trip.startAddress, trip.destination ?: trip.endAddress)
    .filter { it.isNotBlank() }.joinToString("  →  ").ifBlank { "주소 미기록 · 계기판 거리로 기록" }

internal typealias Act = (suspend () -> Unit) -> Unit
internal val purposeChoices = listOf(TripPurpose.COMMUTE, TripPurpose.BUSINESS, TripPurpose.PERSONAL)

/**
 * A confirmation shown after a change, with one optional follow-up such as undo or applying the same choice to more trips.
 * Since 2.13 the car's bar can offer a [second] action beside it (실행 취소 with "3건도 같게", 새 기한 입력 with 실행 취소): the
 * second is drawn first and the undo ([action]) last. The phone's bar shows [action] alone.
 *
 * The second constructor keeps the written form `FollowUp(message, action) { undo }`, and `FollowUp(message, action, second
 * = …) { undo }`, valid: a trailing lambda always lands on [perform], which the data class's own order (message, action,
 * perform, second) would otherwise hand to [second].
 */
internal data class FollowUp(val message: String, val action: String? = null, val perform: (suspend () -> Unit)? = null, val second: FollowUpAction? = null) {
    constructor(message: String, action: String?, second: FollowUpAction? = null, perform: suspend () -> Unit) : this(message, action, perform, second)
}

/** A follow-up's second action ([FollowUp.second]): its [label] and what it does. */
internal data class FollowUpAction(val label: String, val perform: suspend () -> Unit)

internal val LocalFollowUp = staticCompositionLocalOf<(FollowUp) -> Unit> { {} }

// Korean particle: 로 after a vowel or ㄹ final, 으로 otherwise.
internal fun withRo(word: String): String {
    val last = word.lastOrNull() ?: return word
    if (last !in '가'..'힣') return "$word(으)로"
    val final = (last - '가') % 28
    return if (final == 0 || final == 8) "${word}로" else "${word}으로"
}

@Composable internal fun purposeColor(purpose: TripPurpose): Color {
    val pleos = LocalPleosColors.current
    return when (purpose) {
        TripPurpose.COMMUTE -> pleos.positive
        TripPurpose.BUSINESS -> pleos.chartActive
        TripPurpose.PERSONAL -> pleos.textTertiary
        TripPurpose.UNCLASSIFIED -> pleos.negative
    }
}

/**
 * Classifies a trip with haptic confirmation, then answers on the follow-up bar ([classifyFollowUp]): always 실행 취소, and,
 * while other trips of that day are still unclassified, "3건도 같게" beside it (2.13; in 2.12 the bulk offer took the undo's
 * place, so a wrong tap could not be taken back). The bulk answers in turn with its own 실행 취소, which puts those trips
 * back to 미분류, as they were when it was offered.
 */
@Composable internal fun rememberClassifier(data: TripLogData, act: Act, repository: TripLogRepository): (Trip, TripPurpose, EditSource) -> Unit {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    return { trip, purpose, source ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val others = bulkClassifyCandidates(data.trips, trip, zone).map { it.id }
        act {
            repository.classify(trip.id, purpose, source)
            followUp(classifyFollowUp(purpose, others.size,
                undo = { repository.classify(trip.id, trip.purpose, trip.purposeSource ?: EditSource.DRIVER) },
                sameForOthers = {
                    repository.classifyMany(others, purpose, EditSource.DRIVER)
                    followUp(classifyManyFollowUp(purpose, others.size) { repository.classifyMany(others, TripPurpose.UNCLASSIFIED, EditSource.DRIVER) })
                }))
        }
    }
}

/** Car screen of the automatic corporate trip log. Recording itself runs in [TripLogService]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogbookApp(driving: DrivingUiState, requestPermissions: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { TripLogRepository.get(context) }
    val stored by repository.state.collectAsState()
    val state = stored.copy(data = stored.data.copy(trips = stored.data.trips.filter { it.demo == stored.data.profile.demoMode }))
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val pageStates = rememberSaveableStateHolder()
    var error by remember { mutableStateOf<String?>(null) }
    // The path of a file saved to the app's folder when the car has no file picker; null while there is nothing to show.
    var info by remember { mutableStateOf<String?>(null) }
    var driverFor by remember { mutableStateOf<String?>(null) }
    var pickingDriver by remember { mutableStateOf(false) }
    var tutorial by rememberSaveable { mutableStateOf(false) }
    var expenseTripId by rememberSaveable { mutableStateOf<String?>(null) }
    // 0 = 전체, 1 = 점검 필요, 2 = 자동 반영; with logMonth, set by every button that opens the log on something specific.
    var logFilter by rememberSaveable { mutableIntStateOf(0) }
    var logMonth by rememberSaveable { mutableStateOf<String?>(null) }
    // The day keys on which the driver already answered "오늘도 … 님이 운전하나요?"; one set for the hero and the top bar.
    var driverConfirmed by rememberSaveable { mutableStateOf("") }
    // The parked pages composed right now, kept by the page host. A page stays composed for about 260 ms after the driver leaves
    // it (pageSwap), and 운행기록 stays composed under the assistant sheet. removeState on a composed page detaches its registry
    // from pageStates for the rest of that composition: nothing it holds is saved again, not when it leaves, when a drive takes
    // the screen or when the activity is rebuilt. key(logGen) and key(reportGen) already start such an instance fresh, so goLog
    // and goReport drop a page's saved state only while the page is gone.
    val composedPages = remember { mutableSetOf<Int>() }
    val dropPageState: (Int) -> Unit = { page -> if (page !in composedPages) pageStates.removeState(page) }
    // Bumped by every goLog so the log page starts fresh even when its old instance is still fading out.
    var logGen by rememberSaveable { mutableIntStateOf(0) }
    // The log page keeps its own filter, month and selection while the driver browses; a button that opens it on something
    // specific drops that saved state first, so the list shows what the button promised.
    val goLog: (Int, YearMonth?, String?) -> Unit = { filter, month, tripId ->
        dropPageState(1)
        logGen++
        logFilter = filter; logMonth = month?.toString(); expenseTripId = tripId; tab = 1
    }
    // 월간·Excel keeps its own period, unit and view the same way. Every button that opens it on a month (the home close card,
    // the banner, 정보 내보내기, the month widget) drops that state first, so it lands on 월간 and the Excel view of that month;
    // the header tab and a return to it keep what the driver left there.
    var reportMonth by rememberSaveable { mutableStateOf<String?>(null) }
    // Bumped by every goReport so 월간·Excel starts fresh even while its old instance is still fading out (as logGen).
    var reportGen by rememberSaveable { mutableIntStateOf(0) }
    val goReport: (YearMonth) -> Unit = { m -> dropPageState(2); reportGen++; reportMonth = m.toString(); tab = 2 }
    // 설정 keeps its sub-section here, so a button that opens it on something (설정에서 입력 → 정산·세무, 직원 추가·관리 → 직원)
    // lands there, and the header's 설정 tab returns to the section the driver left.
    var settingsSection by rememberSaveable { mutableIntStateOf(0) }
    val goSettings: (Int) -> Unit = { section -> settingsSection = section; tab = 4 }
    // The local assistant: rules over the real log, standing in for Gleo until the voice integration is approved.
    var assistant by rememberSaveable { mutableStateOf(false) }
    var assistantText by rememberSaveable { mutableStateOf<String?>(null) }
    val assistantWriter = remember(repository) { AssistantRunner(repository) }
    val scope = rememberCoroutineScope()
    val act: Act = { block ->
        scope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message ?: "입력 내용을 확인해 주세요." }
            catch (_: Exception) { error = "저장하지 못했어요. 다시 시도해 주세요." }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val followUp: (FollowUp) -> Unit = { item -> scope.showFollowUp(snackbar, act, item) }
    // The shell's tick: countdowns, freshness and the recording distance read it. No clock is drawn (2.13): the car's own
    // status bar already shows the time.
    val tick by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1_000) } }
    // A new sample can be newer than the previous tick.
    val now = remember(tick, state.live) { System.currentTimeMillis() }
    LaunchedEffect(Unit) { repository.ensureLoaded() }
    val blocked = driving.automotive && (!driving.available || driving.restricted)
    val latestBlocked by rememberUpdatedState(blocked)
    // The car tells the driver what it just settled, once, with a way to take it back; while driving the bar stays hidden.
    // One match rides the follow-up bar; a batch (a statement import) becomes a banner that opens the 자동 반영 list.
    var matchedSeen by remember { mutableStateOf<Set<String>?>(null) }
    var bannerCharges by remember { mutableStateOf<List<CardCharge>>(emptyList()) }
    val snoozes = rememberCareSnoozes()
    LaunchedEffect(stored.data.charges, stored.loaded) {
        if (!stored.loaded) return@LaunchedEffect
        val previous = matchedSeen
        val current = matchedIds(stored.data)
        matchedSeen = current
        if (previous == null) return@LaunchedEffect
        // A cancelled approval must not leave "applied" on screen.
        if (current.size < previous.size) snackbar.currentSnackbarData?.dismiss()
        val fresh = newlyMatched(previous, stored.data)
        when {
            fresh.size == 1 -> fresh.single().let { charge ->
                followUp(FollowUp("${charge.merchant} ${formatWon(charge.amountWon)} 카드 결제를 운행에 반영했어요", "회사 비용 아님") { repository.excludeCardCharge(charge.id) })
            }
            fresh.size >= 2 -> bannerCharges = fresh
        }
    }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(blocked) {
        if (blocked) { pickingDriver = false; tutorial = false; assistant = false; info = null; snackbar.currentSnackbarData?.dismiss(); focus.clearFocus(force = true); keyboard?.hide() }
    }
    val openDriver: (String?) -> Unit = { tripId -> if (!blocked) { driverFor = tripId; pickingDriver = true } }
    // The home's free sky while the 지금 column stands: the band in the page's own px, and the page slot's resting origin in
    // the window. Only the sky reads the two (SkyLayer), so a re-measured band or a scroll never recomposes the shell.
    var paneSky by remember { mutableStateOf<HeroSky?>(null) }
    var slotOrigin by remember { mutableStateOf(Offset.Zero) }
    val heroSky = remember { derivedStateOf { paneSky?.let { sky -> HeroSky(sky.band.translate(slotOrigin), sky.scroll) } } }
    // True while 월간·Excel pins its save bar: the follow-up bar then rides above it instead of covering it.
    var saveBarUp by remember { mutableStateOf(false) }
    // The height of 홈's bottom band (demo strip, destinations, incident dock) while the page rests on it, else 0: the
    // follow-up bar rides above the band so it never covers the incident buttons or the card.
    var homeBand by remember { mutableStateOf(0.dp) }
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
                followUp(FollowUp("운행기록부를 저장했어요 · 엑셀에서 열어 확인해 주세요"))
            } finally { pendingExportName = null }
        }
    }
    val export: (String, String, ByteArray) -> Unit = { name, mime, bytes ->
        if (pendingExportName == null && !latestBlocked) {
            pendingExportName = name
            act {
                try {
                    withContext(Dispatchers.IO) { exportFile.writeBytes(bytes) }
                    if (latestBlocked) {
                        pendingExportName = null
                        error = "주행 상태가 바뀌어 파일 선택을 중단했어요. 정차 후 다시 저장해 주세요."
                        return@act
                    }
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
                        // The path alone: the dialog that shows it ([ShellOverlays]) writes the sentence around it.
                        info = file.absolutePath
                    }
                } catch (e: Exception) { pendingExportName = null; throw e }
            }
        }
    }
    // 2.10: the scene (day · dusk · night) follows the clock or the driver's choice and cross-fades; it holds still while
    // driving (hold = blocked), so the change lands at the next stop. See SceneTheme.
    // Spec C: a press on the car is the .98 settle and the press fill, never a ripple. Material's surfaces, buttons and menu
    // rows draw theirs from LocalRippleConfiguration, so the whole car shell (its dialogs and sheets too) provides none.
    SceneTheme(hold = blocked) { CompositionLocalProvider(LocalRippleConfiguration provides null) {
      CompositionLocalProvider(LocalFollowUp provides followUp) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // The layout classes of the window (gutter, content maximum, gaps) come from one measurement at the shell root.
            ProvideLayout(compactApp = false, Modifier.fillMaxSize().sceneBackdrop().safeDrawingPadding()) { layout ->
                val openLog: (Int, YearMonth?) -> Unit = { filter, month -> goLog(filter, month, null) }
                // 홈's cards lead to the picker, the permission prompt, 설정's sections, 운행기록 (a filter, or one trip) and 월간·Excel.
                val homeNav = HomeNav(openDriver = openDriver, requestPermissions = requestPermissions, goSettings = goSettings, openLog = openLog,
                    openTrip = { tripId ->
                        val trip = state.data.trips.firstOrNull { it.id == tripId }
                        goLog(0, trip?.let { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) }, tripId)
                    }, openReport = goReport, navigate = { tab = it })
                // One background layer for every page: the city at full strength on home, dimmed behind the other pages,
                // moving on the same clock as the page change so the layers never jump.
                val skyLevel = when { blocked || !state.loaded -> 0f; tab == 0 -> 1f; else -> .34f }
                // The fade lives in its own scope (SkyLayer), so the 220 ms animation recomposes only the backdrop, not the shell;
                // so does the home's free sky, which the sky alone reads.
                SkyLayer(skyLevel, { heroSky.value }, Modifier.matchParentSize())
                when {
                    // The driving screen and the splash keep the whole window; nothing of the parked shell is composed under them.
                    blocked -> DrivingOverview(state, now, driving.available, Modifier.fillMaxSize())
                    !state.loaded -> SharemateSplash(Modifier.fillMaxSize())
                    else -> {
                        val closeIssues = (monthCloseTarget(state.data, localDate(now), zone) ?: monthStatus(state.data, YearMonth.from(localDate(now)), zone)).issues
                        val homeTasks = parkedTasks(state.data, state.sync, now, zone, snoozes.value)
                        // The 운행기록 badge is the 점검 필요 count of this month, the same number the home card and the list show.
                        val reviewCount = remember(state.data.trips, YearMonth.from(localDate(now))) { logReview(state.data, YearMonth.from(localDate(now)), zone).issues }
                        val tabs = listOf(
                            NavTab("홈", R.drawable.ic_dashboard, parkedTaskBadge(homeTasks)),
                            NavTab("운행기록", R.drawable.ic_journal, reviewCount),
                            NavTab("월간·Excel", R.drawable.ic_calendar, closeIssues),
                            NavTab("차량", R.drawable.ic_vehicle, openIncidents(state.data).size),
                            NavTab("설정", R.drawable.ic_settings, 0),
                        )
                        // The rail fills the window between the gutters. On a wide, tall window it splits into the page column, where
                        // the five pages slide, and the 지금 column beside it, which stands outside the page change like the header
                        // ([carColumns]). The header spans the rail and never appears or disappears between pages; only its lit tab moves.
                        val rail = (maxWidth - layout.gutter * 2).coerceAtMost(layout.contentMax)
                        val columns = carColumns(layout, rail, LocalDensity.current.fontScale)
                        Box(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
                            Box(Modifier.contentWidth().fillMaxHeight().align(if (layout.startAnchored) Alignment.TopStart else Alignment.TopCenter)) {
                                Column(Modifier.fillMaxSize()) {
                                    TopBar(state, now, layout, tabs, tab, { tab = it }, { openDriver(null) }, { tutorial = true }, { assistant = true },
                                        nowColumn = columns.split)
                                    // Without the column, the daily question rides under the header. The 네 row folds itself away (200 ms,
                                    // a snap under reduced motion) once the driver has answered, still showing the question it answered while
                                    // it folds; the gap under it folds with it.
                                    if (!columns.split) DriverConfirmRow(state, now, openDriver, driverConfirmed, { driverConfirmed = it }, Modifier.padding(bottom = 12.dp))
                                    val swapForward = pageSwap(forward = true)
                                    val swapBack = pageSwap(forward = false)
                                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(columns.gap)) {
                                        // The slot's resting origin turns the home's band into window px: the slide moves the page inside the
                                        // slot, never the slot, so the sun does not travel with a page change.
                                        AnimatedContent(targetState = tab, modifier = Modifier.width(columns.page).fillMaxHeight().padding(top = 8.dp, bottom = 20.dp)
                                            .onGloballyPositioned { slotOrigin = it.positionInRoot() },
                                            transitionSpec = { if (targetState > initialState) swapForward else swapBack }, label = "parked-page") { page ->
                                          // Tells goLog and goReport which pages are still composed (see composedPages).
                                          DisposableEffect(page) { composedPages += page; onDispose { composedPages -= page } }
                                          pageStates.SaveableStateProvider(page) {
                                            when (page) {
                                                0 -> DrivePane(state, now, act, repository, homeNav, snoozes, bannerCharges, { bannerCharges = it },
                                                    nowColumn = columns.split, onHeroSky = { paneSky = it }, onBand = { homeBand = it })
                                                1 -> key(logGen) { LogPane(state, now, act, repository, openDriver, expenseTripId, logFilter, logMonth, openReport = goReport) }
                                                2 -> key(reportGen) { ReportPane(state, now, pendingExportName != null, export, act, repository, { goSettings(2) }, { tab = 0 },
                                                    initialMonth = reportMonth, onSaveBar = { saveBarUp = it }) { month -> openLog(1, month) } }
                                                3 -> VehiclePane(state, now, act, repository, goSettings = { goSettings(0) })
                                                // Saved-settings confirmations ride the bar.
                                                else -> SettingsPane(state, now, act, repository, settingsSection, { settingsSection = it }, { tutorial = true },
                                                    { phrase -> assistantText = phrase; assistant = true }) { text -> followUp(FollowUp(text)) }
                                            }
                                          }
                                        }
                                        // Who is driving (asking today's question while it is due, on the same answered days as the narrow
                                        // window's row), the next booking, today and this month. The card's 직원 추가 opens 설정 › 직원; 오늘 운행
                                        // opens 운행기록 on this month, whose list starts with the newest day (today's, once it has a trip).
                                        if (columns.split) NowColumn(state, now,
                                            driverCard = { modifier -> DriverNowCard(state, now, driverConfirmed, { driverConfirmed = it }, openPicker = { openDriver(null) },
                                                openDrivers = { goSettings(1) }, modifier = modifier) },
                                            openVehicle = { tab = 3 }, openToday = { openLog(0, YearMonth.from(localDate(now))) },
                                            openMonth = { goReport(YearMonth.from(localDate(now))) },
                                            modifier = Modifier.width(columns.side).fillMaxHeight().padding(top = 8.dp, bottom = 20.dp))
                                    }
                                }
                                // The follow-up bar centres on the page column, clear of the 지금 column, and rides above 월간·Excel's save bar
                                // and above 홈's bottom band: 24 dp over the band, as over the window's edge (the band ends at the page slot's
                                // 20-dp bottom padding). 홈's sky keeps at least 137 dp at FULL; the bar needs 88.
                                ShellFollowUpBar(snackbar, columns.page, lift = when {
                                    tab == 2 && saveBarUp -> SAVE_BAR_RESERVE
                                    tab == 0 && homeBand > 0.dp -> homeBand + 20.dp
                                    else -> 0.dp
                                })
                            }
                        }
                    }
                }
            }
        }
        // Inside the follow-up provider, so the picker's confirmations and their undo reach the shell's bar.
        ShellOverlays(state, now, blocked, act, repository, followUp,
            picking = pickingDriver, driverFor = driverFor, closePicker = { pickingDriver = false }, openDrivers = { pickingDriver = false; goSettings(1) },
            assistant = assistant, assistantText = assistantText, assistantWriter = assistantWriter, closeAssistant = { assistant = false; assistantText = null },
            openTrip = { tripId ->
                assistant = false
                val trip = state.data.trips.firstOrNull { it.id == tripId }
                goLog(0, trip?.let { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) }, tripId)
            },
            tutorial = tutorial, exporting = pendingExportName != null, export = export, closeTutorial = { tutorial = false },
            error = error, clearError = { error = null; repository.clearError() }, info = info, clearInfo = { info = null })
      }
    } }
}

/**
 * Shows [item] on the shell's follow-up bar (2.13, a seam of LogbookApp): the bar it shows replaces the one on screen, it
 * stays long while it offers an action, and every action runs through [act], so its errors reach the shell's dialog. The
 * car's bar ([CarFollowUpBar]) gets the [FollowUp.second] action too ([FollowUpVisuals]): tapping it closes the bar and runs
 * it, so the undo beside it is not run.
 */
internal fun CoroutineScope.showFollowUp(snackbar: SnackbarHostState, act: Act, item: FollowUp) {
    launch {
        snackbar.currentSnackbarData?.dismiss()
        val second = item.second?.let { action -> FollowUpSecond(action.label) { act(action.perform) } }
        val result = snackbar.showSnackbar(FollowUpVisuals(item.message, item.action, second))
        if (result == SnackbarResult.ActionPerformed) item.perform?.let(act)
    }
}

/**
 * The shell's follow-up bar (2.13, a seam of LogbookApp): [CarFollowUpBar], centred on the page column ([pageWidth], clear
 * of the 지금 column), 760 dp at most, 24 dp above the window's bottom plus [lift] (월간·Excel's save bar), sliding in and
 * out on the bar's clock.
 */
@Composable private fun BoxScope.ShellFollowUpBar(snackbar: SnackbarHostState, pageWidth: Dp, lift: Dp) {
    Box(Modifier.width(pageWidth).fillMaxHeight().align(Alignment.BottomStart)) {
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(top = 24.dp, bottom = 24.dp + lift).widthIn(max = 760.dp)) { data ->
            AnimatedVisibility(visible = true, enter = barEnter(), exit = barExit()) { CarFollowUpBar(data) }
        }
    }
}

/**
 * Everything the shell lays over its pages (2.13, a seam of LogbookApp), none of it while driving ([blocked]): the driver
 * picker ([picking], for [driverFor] or for the person driving now), the assistant sheet, the 시연 체험 walkthrough, the
 * error dialog ([error], or the repository's) and the saved-file dialog ([info] is the file's path). Composed inside the
 * follow-up provider, so the picker's confirmations and their undo reach the shell's bar. The two dialogs are the car's
 * ([CarDialog]): the error acknowledges its message one sentence a line ([dialogLines]; the repository's messages stay as
 * written), and the saved file says where it went over its path, which may take three lines so the file name is not cut.
 */
@Composable private fun ShellOverlays(state: TripLogState, now: Long, blocked: Boolean, act: Act, repository: TripLogRepository, followUp: (FollowUp) -> Unit,
    picking: Boolean, driverFor: String?, closePicker: () -> Unit, openDrivers: () -> Unit,
    assistant: Boolean, assistantText: String?, assistantWriter: AssistantRunner, closeAssistant: () -> Unit, openTrip: (String) -> Unit,
    tutorial: Boolean, exporting: Boolean, export: (String, String, ByteArray) -> Unit, closeTutorial: () -> Unit,
    error: String?, clearError: () -> Unit, info: String?, clearInfo: () -> Unit) {
    if (picking && !blocked) DriverPicker(state.data, driverFor, act, repository, closePicker, now, openDrivers)
    AssistantSheet(open = assistant && !blocked && state.loaded, onDismiss = closeAssistant, data = state.data, now = now,
        writer = assistantWriter, act = act, followUp = followUp, onOpenTrip = openTrip,
        // The car's sheet in every window: a split car window is still the car, with its 56-60 dp targets and no ripple.
        compact = false, initialText = assistantText)
    if (tutorial && !blocked) DemoTutorial(exporting, export, closeTutorial)
    val message = error ?: state.error
    if (message != null && !blocked) CarDialog(OverlayCopy.ERROR_TITLE, clearError, icon = painterResource(R.drawable.ic_warning), lines = dialogLines(message)) {
        PrimaryAction("확인", onClick = clearError)
    }
    info?.takeUnless { blocked }?.let { path ->
        CarDialog(OverlayCopy.SAVED_TITLE, clearInfo, icon = rememberVectorPainter(Icons.Outlined.Info), lines = listOf(OverlayCopy.SAVED_LINE), body = {
            Text(path, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }) { PrimaryAction("확인", onClick = clearInfo) }
    }
}

/** Car-sized snackbar: large text, one action, inverse surface so it reads in day and night mode. */
@Composable internal fun FollowUpBar(data: SnackbarData) {
    val colors = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(20.dp), color = colors.inverseSurface, contentColor = colors.inverseOnSurface,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.heightIn(min = 64.dp).padding(start = 24.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(data.visuals.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = data::performAction, modifier = Modifier.heightIn(min = 56.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.inversePrimary)) { Text(label, style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

/**
 * Whether the header's driver chip shows the person's avatar in [chipMax], the width the header row leaves the chip. The
 * avatar and the chip's padding take 74 dp (12 + 32 + 10 + 20); from 140 dp at 1×, more with a larger font, the caption
 * ‘사용자’ still fits beside them. Narrower, the chip is the text pill of 2.10, 20 dp in: its label ellipsises, then its
 * caption, and the header keeps its one 68-dp row instead of wrapping the caption or squeezing the avatar into an oval.
 */
internal fun driverChipAvatar(chipMax: Dp, fontScale: Float): Boolean = driverChipMode(chipMax, fontScale) == DriverChipMode.AVATAR_AND_NAME

/** The three ways the header's driver chip draws itself, widest first ([driverChipMode]). */
internal enum class DriverChipMode { AVATAR_AND_NAME, NAME, AVATAR }

/**
 * How the header's driver chip draws itself in [chipMax]: the avatar beside ‘사용자’ and the name where both fit (74 dp of
 * avatar and padding plus 66 dp of text at 1×, the text growing with the font); the text pill alone (40 dp of padding plus
 * the text); and in the tightest header (운행기록 or 월간·Excel in a narrow window, where the assistant button joins the row)
 * the avatar alone in a 56-dp pill. A pill of ellipses would say nothing; the person's initials still say who is driving,
 * and the chip keeps its target and its spoken name.
 */
internal fun driverChipMode(chipMax: Dp, fontScale: Float): DriverChipMode {
    val text = 66.dp * fontScale.coerceAtLeast(1f)
    return when {
        chipMax >= 74.dp + text -> DriverChipMode.AVATAR_AND_NAME
        chipMax >= 40.dp + text -> DriverChipMode.NAME
        else -> DriverChipMode.AVATAR
    }
}

/**
 * What TalkBack says for the header's driver chip (pure, unit-tested): the caption on screen first, then the person's label
 * and what a tap does, "사용자 Sales · Kim Jiwoo, 바꾸기", so the spoken name starts with the visible words (WCAG 2.5.3); with
 * nobody chosen, "사용자 미선택, 운전자 선택".
 */
internal fun driverChipLabel(driver: Driver?): String = driver?.let { "사용자 ${it.label}, 바꾸기" } ?: "사용자 미선택, 운전자 선택"

/**
 * How the header lays itself out (2.12; 2.13 took the clock out; pure, unit-tested). [oneRow]: the mark, the tabs and the
 * cluster share one row, which the windows with the 지금 column have room for (its 사용자 card takes the chip and the
 * recording state off the header), and so does the full window without it (a short one). [statusInHeader]: the driver chip
 * and the recording state are header pills wherever no 지금 column carries them.
 */
@Immutable internal data class HeaderPlan(val oneRow: Boolean, val statusInHeader: Boolean)

internal fun headerPlan(layout: LayoutTokens, nowColumn: Boolean): HeaderPlan =
    HeaderPlan(oneRow = nowColumn || layout.full, statusInHeader = !nowColumn)

/** How the header's search bar draws itself in the room the cluster leaves it, widest first ([searchBarMode]). */
internal enum class SearchBarMode { EXAMPLE, PHRASE, LABEL, ICON }

/**
 * The search bar's form at [width] (2.13, pure, unit-tested), the font scale counted (one below 1× counts as 1×): the phrase
 * and the example from 496 dp, the phrase alone from 280 (where it shows whole), the icon with 운행 비서 from 160 (a header
 * tool at its own width), and the 56-dp icon below that.
 */
internal fun searchBarMode(width: Dp, fontScale: Float): SearchBarMode {
    val scale = fontScale.coerceAtLeast(1f)
    return when {
        width >= 496.dp * scale -> SearchBarMode.EXAMPLE
        width >= SEARCH_BAR_MIN * scale -> SearchBarMode.PHRASE
        width >= 160.dp * scale -> SearchBarMode.LABEL
        else -> SearchBarMode.ICON
    }
}

/** The least the search bar keeps as a bar (its phrase, [SearchBarMode.PHRASE]) at a 1× font. */
private val SEARCH_BAR_MIN = 280.dp

/** The space between the header cluster's pieces. */
private val HEADER_GAP = 16.dp

/**
 * The most the driver chip may take in a header cluster [cluster] wide whose fixed pieces (시연 체험 and the status pill, with
 * their gaps) take [fixed] (2.13, pure, unit-tested): what is left once the bar keeps its least width as a bar (280 dp × the
 * font scale) and the chip its gap, and never under the chip's 56-dp avatar pill. So the chip steps down to its avatar
 * ([driverChipMode]) before the bar drops to its label.
 */
internal fun headerChipMax(cluster: Dp, fixed: Dp, fontScale: Float): Dp =
    (cluster - fixed - HEADER_GAP - SEARCH_BAR_MIN * fontScale.coerceAtLeast(1f)).coerceAtLeast(56.dp)

/**
 * The header of every page (2.13), spanning the rail and laid out by [headerPlan]: the mark and the five tabs at the start,
 * then one cluster, in the same order everywhere: the search bar to the assistant, 시연 체험, the driver chip and the
 * recording state ([HeaderCluster]). There is no clock: the car's own status bar shows the time. Beside the 지금 column one row
 * holds the tabs and the bar, which runs to the rail's end over the column (its 사용자 card says who drives and what the car
 * is doing). Without the column the chip and the state join the cluster, on the full window's one row, or on a second row
 * under the tabs, which scroll from MEDIUM down. Every piece sits on an opaque card pill: over the 홈 sky, textSecondary on
 * its own would read 2.76:1. One row is 84 dp (76 short), two rows 152 (136).
 */
@Composable private fun TopBar(state: TripLogState, now: Long, layout: LayoutTokens, tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit,
    onDriver: () -> Unit, onTutorial: () -> Unit, onAssistant: () -> Unit, nowColumn: Boolean) {
    val plan = headerPlan(layout, nowColumn)
    val wide = layout.expanded
    val skin = Car.skin
    val driver = state.data.drivers.firstOrNull { it.id == state.data.currentDriverId }
    // The tutorial is for a car that has not recorded anything yet; once real trips exist it stays reachable from settings. A
    // one-row header keeps it to 홈.
    val showTutorial = state.data.trips.none { !it.demo } && (selected == 0 || !plan.oneRow)
    val chip: @Composable () -> Unit = {
        val interaction = remember { MutableInteractionSource() }
        // A control, so it is outlined in the chip border (3.47 / 3.69 on the card) where the pills that only inform take the hairline.
        Surface(onClick = onDriver, shape = RoundedCornerShape(50), color = skin.card, contentColor = Scene.text, border = BorderStroke(1.dp, skin.chipBorder),
            interactionSource = interaction, modifier = Modifier.heightIn(min = 56.dp).pressable(interaction, .98f).semantics { contentDescription = driverChipLabel(driver) }) {
            // The person's avatar (the one the picker and 설정 › 직원 show) sits 12 dp in, concentric with the pill's round end;
            // the chip keeps what the cluster leaves it ([headerChipMax], [driverChipMode]). The pill's 56-dp minimum still reaches the row.
            BoxWithConstraints(Modifier.pressFill(interaction), propagateMinConstraints = true) {
                val mode = driverChipMode(maxWidth, LocalDensity.current.fontScale)
                if (mode == DriverChipMode.AVATAR) Box(Modifier.padding(12.dp), contentAlignment = Alignment.Center) { DriverAvatar(driver, 32.dp) }
                else Row(Modifier.padding(start = if (mode == DriverChipMode.AVATAR_AND_NAME) 12.dp else 20.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (mode == DriverChipMode.AVATAR_AND_NAME) DriverAvatar(driver, 32.dp)
                    Column {
                        // One line each: in a tight header the caption ellipsises too, rather than wrapping and growing the row. The
                        // name alone (the department is in the spoken label and in the picker): it is what tells the drivers apart.
                        Text("사용자", style = MaterialTheme.typography.labelSmall, color = Scene.textSecondary, maxLines = 1, softWrap = false,
                            overflow = TextOverflow.Ellipsis)
                        Text(driver?.let { it.name.ifBlank { it.label } } ?: "직원 선택", style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (wide) 230.dp else 140.dp))
                    }
                }
            }
        }
    }
    val cluster: @Composable (Modifier) -> Unit = { modifier ->
        HeaderCluster(modifier, bar = { HeaderSearchBar(onAssistant) },
            tutorial = if (showTutorial) { { HeaderTool(icon = null, label = HeaderCopy.TUTORIAL, description = null, onClick = onTutorial) } } else null,
            chip = if (plan.statusInHeader) chip else null,
            status = if (plan.statusInHeader) { { StatusPill(state, now) } } else null)
    }
    // In the text colour: black by day, as in the mock, and white by night.
    val mark: @Composable () -> Unit = {
        Icon(painterResource(R.drawable.ic_splash_logo), contentDescription = "SHAREMATE", tint = Scene.text, modifier = Modifier.size(52.dp))
    }
    // Rows 12 dp apart inside 8 dp of padding, 8 and 4 in a short window. The tab pill is 68 dp (56-dp tabs inside 6 dp of
    // padding, 4 when short), so one row makes an 84-dp header (76 short) and two rows 152 (136).
    Column(Modifier.fillMaxWidth().padding(vertical = if (layout.short) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(if (layout.short) 8.dp else 12.dp)) {
        if (plan.oneRow) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                mark()
                GlassTabs(tabs, selected, onSelect, compact = layout.short)
            }
            // The cluster takes what the pill leaves of the rail, flush with its end.
            cluster(Modifier.weight(1f).padding(start = HEADER_GAP))
        } else {
            // EXPANDED without the column, and MEDIUM and COMPACT, where the tab pill scrolls between the mark and the end.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 12.dp)) {
                mark()
                if (wide) GlassTabs(tabs, selected, onSelect, compact = layout.short)
                else Box(Modifier.weight(1f)) { GlassTabs(tabs, selected, onSelect, Modifier.horizontalScroll(rememberScrollState()), compact = layout.short) }
            }
            cluster(Modifier.fillMaxWidth())
        }
    }
}

/**
 * The header's cluster (2.13): the [bar], then [tutorial], [chip] and [status] flush with the row's end, 16 dp apart. A
 * custom layout, because a Row cannot make two children give way in turn: the status pill and 시연 체험 take their own widths
 * (the fixed part), the chip what [headerChipMax] allows (its three modes), and the bar the rest, choosing its form for it
 * ([searchBarMode]). A bar narrower than its room (LABEL, ICON) sits at the row's start. Nothing here asks for intrinsics.
 */
@Composable private fun HeaderCluster(modifier: Modifier, bar: @Composable () -> Unit, tutorial: (@Composable () -> Unit)?, chip: (@Composable () -> Unit)?,
    status: (@Composable () -> Unit)?) {
    val fontScale = LocalDensity.current.fontScale
    Layout(contents = listOf(bar, tutorial ?: {}, chip ?: {}, status ?: {}), modifier = modifier) { (bars, tutorials, chips, statuses), constraints ->
        val width = constraints.maxWidth
        val gap = HEADER_GAP.roundToPx()
        val loose = Constraints(maxWidth = width, maxHeight = constraints.maxHeight)
        val statusPlaced = statuses.firstOrNull()?.measure(loose)
        val tutorialPlaced = tutorials.firstOrNull()?.measure(loose)
        val fixed = listOfNotNull(statusPlaced, tutorialPlaced).sumOf { it.width + gap }
        val chipMax = headerChipMax(width.toDp(), fixed.toDp(), fontScale).roundToPx().coerceAtMost(width)
        val chipPlaced = chips.firstOrNull()?.measure(loose.copy(maxWidth = chipMax))
        val room = (width - fixed - (chipPlaced?.let { it.width + gap } ?: 0)).coerceAtLeast(0)
        val barPlaced = bars.first().measure(loose.copy(maxWidth = room))
        val height = listOfNotNull(barPlaced, tutorialPlaced, chipPlaced, statusPlaced).maxOf { it.height }
        layout(width, height) {
            barPlaced.placeRelative(0, (height - barPlaced.height) / 2)
            var end = width
            listOfNotNull(statusPlaced, chipPlaced, tutorialPlaced).forEach { placed ->
                end -= placed.width
                placed.placeRelative(end, (height - placed.height) / 2)
                end -= gap
            }
        }
    }
}

/**
 * The entry to 운행 비서 on every page (2.13): a button, not a field, that opens the assistant sheet ([onClick]). In its
 * room ([searchBarMode]) a 56-dp stadium on the card with the chip border (never the accent, which marks the chosen tab
 * beside it): the search icon, the phrase in textSecondary and, from 496 dp, the example in textMuted, which ellipsises
 * first. Narrower it is a header tool, 운행 비서 beside the icon or the icon alone. TalkBack hears the same words in every
 * form, "운행 비서에게 물어보세요, 예: 주차비 4,500원 추가해줘", a button whose click label is 운행 비서 열기.
 */
@Composable private fun HeaderSearchBar(onClick: () -> Unit) {
    BoxWithConstraints {
        val mode = searchBarMode(maxWidth, LocalDensity.current.fontScale)
        // The car's own magnifier at the 1.6 stroke of its other glyphs: ic_search is the phone header's and keeps its 1.8.
        val icon = painterResource(R.drawable.ic_search_bar)
        when (mode) {
            SearchBarMode.ICON -> HeaderTool(icon, label = null, description = HeaderCopy.SPOKEN_BAR, onClick = onClick, clickLabel = HeaderCopy.BAR_CLICK)
            SearchBarMode.LABEL -> HeaderTool(icon, label = HeaderCopy.BAR_LABEL, description = HeaderCopy.SPOKEN_BAR, onClick = onClick, clickLabel = HeaderCopy.BAR_CLICK)
            else -> {
                val interaction = remember { MutableInteractionSource() }
                val skin = Car.skin
                val shape = RoundedCornerShape(50)
                Row(Modifier.fillMaxWidth().pressable(interaction, .98f).heightIn(min = 56.dp).clip(shape).background(skin.card).border(1.dp, skin.chipBorder, shape)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = HeaderCopy.BAR_CLICK, onClick = onClick)
                    .semantics { contentDescription = HeaderCopy.SPOKEN_BAR }
                    .pressFill(interaction).padding(start = 20.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = skin.icon)
                    Spacer(Modifier.width(12.dp))
                    // Beside the example the phrase keeps its own width and the example gives way; alone it may ellipsise at the bar's least width.
                    Text(HeaderCopy.BAR_PHRASE, (if (mode == SearchBarMode.EXAMPLE) Modifier else Modifier.weight(1f, fill = false)).clearAndSetSemantics {},
                        style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (mode == SearchBarMode.EXAMPLE) {
                        Spacer(Modifier.width(12.dp))
                        Text(HeaderCopy.BAR_EXAMPLE, Modifier.weight(1f).clearAndSetSemantics {}, style = MaterialTheme.typography.bodyMedium,
                            color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A header tool (2.12): a 56-dp stadium on the card, outlined in the chip border like every control in the header, 56 × 56
 * with its [icon] alone. The icon takes the line ink, the [label] the text colour. It settles to .98 with the press fill (no
 * ripple on the car). [description] replaces what it shows for TalkBack and contains its label; [clickLabel] names what a
 * tap does (2.13: the search bar's narrow forms, 운행 비서 열기).
 */
@Composable private fun HeaderTool(icon: Painter?, label: String?, description: String?, onClick: () -> Unit, clickLabel: String? = null) {
    val interaction = remember { MutableInteractionSource() }
    val skin = Car.skin
    val shape = RoundedCornerShape(50)
    Row(Modifier.pressable(interaction, .98f).heightIn(min = 56.dp).widthIn(min = 56.dp).clip(shape).background(skin.card).border(1.dp, skin.chipBorder, shape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = clickLabel, onClick = onClick)
        .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
        .pressFill(interaction).padding(horizontal = if (label != null) 18.dp else 0.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
        if (icon != null) Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = skin.icon)
        if (label != null) Text(label, style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1, softWrap = false,
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier)
    }
}

/**
 * The tone of a status dot ([pillDotColor]): a failure or a stale signal, waiting (connecting), ready to record (2.12: the
 * glow dot of 자동 기록 대기, as in the mock), recording, or demo data.
 */
internal enum class PillTone { NEGATIVE, QUIET, READY, POSITIVE, DEMO }

/**
 * The recording state's line and tone, for the header pill and (2.12) the 사용자 card's status tag: a save failure first,
 * then a signal that cannot record, then staleness, then what the car is doing. [fresh] and [status] come from
 * [drivingOverviewInfo], the same rule as the driving screen.
 */
internal fun statusPillInfo(state: TripLogState, fresh: Boolean, status: String): Pair<String, PillTone> {
    val recording = state.data.open != null
    return when {
        state.loadFailed || state.error != null -> "기록 저장 확인 필요" to PillTone.NEGATIVE
        state.signal in setOf(SignalStatus.DENIED, SignalStatus.UNSUPPORTED, SignalStatus.ERROR) -> state.signal.label to PillTone.NEGATIVE
        state.signal == SignalStatus.STARTING -> state.signal.label to PillTone.QUIET
        !fresh && !recording -> status to PillTone.NEGATIVE
        recording -> "기록 중 · ${kmText(state.data.open!!.distanceKm)}" to PillTone.POSITIVE
        state.signal == SignalStatus.READY -> "자동 기록 대기" to PillTone.READY
        state.signal == SignalStatus.DEMO -> "시연 데이터" to PillTone.DEMO
        else -> state.signal.label to PillTone.NEGATIVE
    }
}

/**
 * The recording state as a header pill (2.12), only where no 지금 column carries it (the 사용자 card shows the same words as a
 * tag): a 56-dp stadium on the card with the hairline, a 12-dp dot in its tone ([pillDotColor]) and the label. Freshness is
 * read at [now], the shell's tick, by the driving screen's rule, so a stale signal never reads as "ready".
 */
@Composable private fun StatusPill(state: TripLogState, now: Long) {
    val overview = drivingOverviewInfo(state, now, true, zone)
    val (label, tone) = statusPillInfo(state, overview.fresh, overview.status)
    val skin = Car.skin
    val shape = RoundedCornerShape(50)
    // Polite live region: TalkBack announces recording start/stop without the driver touching the screen. A status colour never
    // animates (spec C): a changed state swaps the dot at once, with no tint transition and no pulse.
    Row(Modifier.heightIn(min = 56.dp).clip(shape).background(skin.card).border(1.dp, skin.cardBorder, shape)
        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(12.dp).clip(CircleShape).background(pillDotColor(tone)))
        Text(label, style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1)
    }
}

/**
 * Segment control. On the car (2.12, [isCar]) a stadium on the card colour whose solid accent pill glides to a new choice in
 * 180 ms (a snap under reduced motion); the label and its optional [icons] turn white as the pill covers them ([glideCovers]).
 * [separated] lays the choices out as their own 46-dp pills 8 dp apart, radio buttons in one group (자동 · 낮 · 밤), the
 * chosen one filled with the accent; that swap is instant, because the scene's fade-through that follows is the motion. The
 * phone draws the glass control of 2.10 (the chosen segment on the selection fill and ring), unchanged: it reaches this
 * through the phone's scene card. Segments are 56 dp; cap the width with the modifier.
 */
@Composable internal fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, icons: List<Painter>? = null,
    separated: Boolean = false, onSelect: (Int) -> Unit) {
    if (isCar()) {
        if (separated) CarSegmentedSeparated(labels, selected, modifier, icons, onSelect) else CarSegmented(labels, selected, modifier, icons, onSelect)
        return
    }
    val bounds = rememberItemBounds()
    val noRipple = LocalLayout.current.startAnchored
    val reduce = LocalReduceMotion.current
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Scene.glass, border = Scene.edge) {
        Row(Modifier.padding(5.dp).selectionGlide(bounds, selected, durationMs = 180, corner = 17.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (active) Scene.glow else Scene.textSecondary, if (reduce) snap() else tween(180, easing = FastOutSlowInEasing), label = "segment")
                Box(Modifier.weight(1f).reportBounds(bounds, index).pressable(interaction, .98f).heightIn(min = 56.dp).clip(RoundedCornerShape(17.dp))
                    .semantics { this.selected = active; role = Role.Tab }
                    .clickable(interactionSource = interaction, indication = if (noRipple) null else LocalIndication.current) { onSelect(index) }, contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
        }
    }
}

/**
 * The car's joined [Segmented]: a stadium on the card with a hairline, the solid accent pill (a 46-dp stadium in each 56-dp
 * segment) gliding in 180 ms. Each segment reads the glide through `derivedStateOf`, so it recomposes once, when the pill
 * covers half of it and its label and icon turn to onAccent; at rest that is exactly the chosen one.
 */
@Composable private fun CarSegmented(labels: List<String>, selected: Int, modifier: Modifier, icons: List<Painter>?, onSelect: (Int) -> Unit) {
    val skin = Car.skin
    val bounds = rememberItemBounds()
    val glide = rememberGlideRect(bounds, selected, durationMs = 180)
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(50), color = skin.card, border = BorderStroke(1.dp, skin.cardBorder)) {
        Row(Modifier.padding(5.dp).drawGlide(glide, GlideMark.SOLID), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val interaction = remember { MutableInteractionSource() }
                val covered by remember { derivedStateOf { glideCovers(glide.value, bounds[index]) } }
                Row(Modifier.weight(1f).reportBounds(bounds, index).pressable(interaction, .98f).heightIn(min = 56.dp).clip(RoundedCornerShape(50))
                    .semantics { this.selected = active; role = Role.Tab }
                    .clickable(interactionSource = interaction, indication = null) { onSelect(index) }.padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    icons?.getOrNull(index)?.let { icon ->
                        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = if (covered) skin.onAccent else skin.icon)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (covered) skin.onAccent else Scene.text, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/**
 * The car's [Segmented] with [separated] choices (화면 밝기: 자동 · 낮 · 밤): each its own 46-dp pill in a 56-dp target, 8 dp
 * apart, radio buttons in one selectable group. The chosen one is filled with the accent (onAccent icon and label, darkened
 * by pressOnAccent while held); the others are outlined with the chip border, the icon in ink and the label in text.
 */
@Composable private fun CarSegmentedSeparated(labels: List<String>, selected: Int, modifier: Modifier, icons: List<Painter>?, onSelect: (Int) -> Unit) {
    val skin = Car.skin
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, label ->
            val active = index == selected
            val interaction = remember { MutableInteractionSource() }
            val shape = RoundedCornerShape(50)
            val ink = if (active) skin.onAccent else Scene.text
            Box(Modifier.weight(1f).pressable(interaction, .98f)
                .selectable(selected = active, interactionSource = interaction, indication = null, role = Role.RadioButton) { onSelect(index) }
                .padding(vertical = 5.dp).heightIn(min = 46.dp).clip(shape), contentAlignment = Alignment.Center) {
                // The fill and its press layer sit under the label, so the press darkens the accent and never the white label on it.
                Box(Modifier.matchParentSize().then(if (active) Modifier.background(skin.accent) else Modifier.border(1.dp, skin.chipBorder, shape))
                    .pressFill(interaction, if (active) skin.pressOnAccent else Scene.fill))
                Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    icons?.getOrNull(index)?.let { icon ->
                        Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = if (active) skin.onAccent else skin.icon)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** A glass panel: the scene's glass with its lit edge, padded to the layout's panel padding unless [padding] says otherwise. */
@Composable internal fun Panel(modifier: Modifier = Modifier, color: Color = Scene.glass, padding: Dp = LocalLayout.current.panelPadding, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = color, contentColor = MaterialTheme.colorScheme.onSurface,
        border = Scene.edge) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

/**
 * A section label: the scene's brand colour, one per card or section (spec A: brand is the eyebrow token). A label that names
 * a field inside a card (계기판 누적거리, 운행 목적) passes [Scene.textSecondary] instead, so a card never mixes two eyebrow colours.
 */
@Composable internal fun Eyebrow(text: String, color: Color = Scene.brand) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, letterSpacing = 0.6.sp)

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier,
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)

/**
 * Press feedback for a Material button on the car, where nothing ripples (LogbookApp provides no ripple): the .98 settle of
 * [pressable], driven by the button's own [interaction]. Nothing is added on the phone, which keeps Material's ripple.
 */
@Composable private fun Modifier.carPress(interaction: MutableInteractionSource): Modifier =
    if (LocalLayout.current.startAnchored) pressable(interaction, .98f) else this

/**
 * A card's one primary action (2.12): a 56-dp stadium in the solid accent, the label (and an optional 20-dp [icon]) in
 * white, one line. While held it settles to .98 and the accent darkens by pressOnAccent ([primaryActionColors]). Disabled
 * (2.13) it has no fill, the chip border at 38 % and a textMuted label, [QuietAction]'s disabled look: the 2.12 slab on the
 * chip fill read as a field, and it never reads stronger than an enabled button.
 */
@Composable internal fun PrimaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: Painter? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.carPress(interaction).heightIn(min = 56.dp), shape = RoundedCornerShape(50),
        colors = primaryActionColors(interaction), border = if (enabled) null else quietActionBorder(enabled = false),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp), interactionSource = interaction) {
        if (icon != null) {
            Icon(icon, contentDescription = null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * A dialog's action: Material's text button at the device's touch target, 56 dp on the car (where it settles to .98 while
 * held instead of rippling) and 48 on the phone. [color] tints a destructive label. Every car dialog uses this one button.
 */
@Composable internal fun DialogButton(label: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val car = LocalLayout.current.startAnchored
    TextButton(onClick = onClick, modifier = modifier.carPress(interaction).heightIn(min = if (car) 56.dp else 48.dp), interactionSource = interaction) {
        Text(label, color = color)
    }
}

/**
 * A secondary action (2.12): a 56-dp outlined stadium, the chip border around a label in [color], no fill but the press fill
 * while held ([quietActionColors]), so the card's one solid button stays the accent. Disabled, outline and label drop to
 * 38 %, so it never reads stronger than an enabled one. [description] replaces a symbolic label for screen readers.
 */
@Composable internal fun QuietAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface,
    description: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    OutlinedButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(50), interactionSource = interaction,
        modifier = modifier.carPress(interaction).heightIn(min = 56.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        colors = quietActionColors(interaction, color), border = quietActionBorder(enabled),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
            modifier = if (description != null) Modifier.clearAndSetSemantics {} else Modifier)
    }
}

@Composable internal fun Stat(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = MaterialTheme.colorScheme.onSurface, large: Boolean = false) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = (if (large) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge).copy(fontFeatureSettings = "tnum"), color = valueColor)
    }
}

/**
 * A trip's purpose as a tag. On the car (2.13) it is a [CarTag] led by the purpose's dot ([purposeColor], 4.0 or more on
 * the tag fill in both scenes), the one tag shape of a trip row; the phone keeps its pastel chip.
 */
@Composable internal fun PurposeChip(purpose: TripPurpose) {
    if (isCar()) { CarTag(purpose.label, dot = purposeColor(purpose)); return }
    val colors = MaterialTheme.colorScheme
    val (container, text) = when (purpose) {
        TripPurpose.COMMUTE -> colors.secondaryContainer to colors.onSecondaryContainer
        TripPurpose.BUSINESS -> colors.tertiaryContainer to colors.onTertiaryContainer
        TripPurpose.UNCLASSIFIED -> colors.errorContainer to colors.onErrorContainer
        TripPurpose.PERSONAL -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(shape = RoundedCornerShape(12.dp), color = container) {
        Text(purpose.label, style = MaterialTheme.typography.labelMedium, color = text,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 1)
    }
}

/**
 * The business-use ratio as a ring: the arc sweeps to its value ([sweepProgress] under [sweepKey]: from empty the first time
 * in the process, from where it stood after that, so a return to 월간 or a view switch does not replay it) and the label
 * cross-fades; colours are read in composition. Without [showCaption] (2.12, a card whose title already names the ratio) the
 * ring shows the figure alone; TalkBack hears "[caption] 80%" either way.
 */
@Composable internal fun RatioRing(ratio: Double?, diameter: Dp, caption: String, modifier: Modifier = Modifier, sweepKey: String = "report-ratio-ring",
    showCaption: Boolean = true) {
    val track = Scene.ringTrack
    val arcColor = LocalPleosColors.current.chartActive
    val sweep = sweepProgress(ratio?.toFloat() ?: 0f, key = sweepKey)
    Box(modifier.size(diameter).semantics { contentDescription = "$caption ${ratioText(ratio)}" }, contentAlignment = Alignment.Center) {
        Spacer(Modifier.fillMaxSize().drawBehind {
            val stroke = size.minDimension * .045f
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            val fraction = sweep.value
            if (fraction > 0f) drawArc(arcColor, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            DigitChange(ratioText(ratio), style = if (diameter >= 200.dp) MaterialTheme.typography.displayMedium else MaterialTheme.typography.headlineLarge)
            if (showCaption) Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────────────────────── 운행 ─────────────────────────

/**
 * The car home (2.13, docs/design/DESIGN_2_13_SPEC.md §3): one column that never scrolls in the full window. The greeting
 * block with the status line ([homeStatusLine]); 24 dp under it the attention card ([AttentionSlot]), the head of what the
 * car is waiting on ([attentionQueue]: safety, the permission, money the car measured, then paperwork and the manager's
 * deadlines) with the rest one page away; without the 지금 column the due booking and today and this month; then the sky,
 * which takes whatever height is left; and the bottom band, which never moves: the demo strip in demo mode, the three
 * destinations and the incident dock. Scrolling is off while the content fits ([homeFrame] proves it does at FULL 1×); where
 * it ever does not (a large font, a short window), the same column scrolls rather than cuts. A debug build logs (tag
 * HomeFrame) a full window at 1× that needs to scroll.
 *
 * Beside the 지금 column the page tells the sky ([onHeroSky]) where it leaves sky free right of the greeting and above the
 * first card ([heroBand], from sizes only), so the sun or moon can rest there; and while the page rests on its band it tells
 * the shell the band's height ([onBand]), so the follow-up bar rides above the incident buttons.
 */
@Composable private fun DrivePane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, nav: HomeNav, snoozes: MutableState<Set<String>>,
    banner: List<CardCharge>, onBanner: (List<CardCharge>) -> Unit, nowColumn: Boolean, onHeroSky: (HeroSky?) -> Unit, onBand: (Dp) -> Unit) {
    val layout = LocalLayout.current
    val density = LocalDensity.current
    val data = state.data
    val demo = data.profile.demoMode
    val today = localDate(now)
    val plan = homePlan(layout, nowColumn)
    val prompt = rememberChargePrompt(data)
    // The answers to the 방금 운행 card live here, so they outlive a card leaving the composition.
    val answers = rememberParkedStackAnswers()
    // This month's close reminder, hidden for the month its 닫기 was pressed in (it comes back next month).
    var reminderClosed by rememberSaveable { mutableStateOf("") }
    val live = state.live?.takeIf { it.demo == demo }
    val tasks = parkedTasks(data, state.sync, now, zone, snoozes.value, prompt.value, live?.odometerKm)
    val closeTarget = monthCloseTarget(data, today, zone)
    val queue = attentionQueue(tasks, state.signal, answers.finishedId(data, state.lastFinishedId, now), answers.reopenedId(data, state.lastFinishedId),
        banner.size, closeTarget != null && reminderClosed != monthKey(closeTarget.month), hiddenPendingCharge(data, now, prompt.value) != null)
    val open = data.open?.takeIf { it.demo == demo }
    val tidy = queue.isEmpty() && state.signal == SignalStatus.READY && finishedTripToday(data, today, zone)
    val status = homeStatusLine(state.signal, demo, open, queue, tidy)
    val fresh = live?.takeIf { now - it.at in 0..45_000 }
    val (odometer, odometerLive) = careOdometer(state, now)
    val soc = fresh?.socPercent
    // The tick recomposes this pane every second; the card lines only change with the data, the readings and the day. The
    // 차량 정보 line keeps a snoozed deadline (2.13): a snooze quiets the card, not the vehicle's own summary.
    val lines = remember(data, soc, odometer, odometerLive, today) { homeCardLines(data, soc, odometer, odometerLive, today, zone) }
    val cards = listOf(
        HomeCardSpec("차량 정보", lines.vehicle, R.drawable.ic_vehicle, openIncidents(data).size) { nav.navigate(3) },
        HomeCardSpec("운행일지 확인", lines.log, R.drawable.ic_journal, lines.logPending) { nav.openLog(if (lines.logPending > 0) 1 else 0, YearMonth.from(today)) },
        HomeCardSpec("정보 내보내기", lines.export, R.drawable.ic_export, 0) { nav.openReport(lines.exportMonth) },
    )
    // The greeting block rises in once per session; a return to the tab or a sync lands in place.
    val entrance = rememberHomeEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        val pageWidth = maxWidth
        val scroll = rememberScrollState()
        var greetingHeight by remember { mutableIntStateOf(0) }
        var bandHeight by remember { mutableIntStateOf(0) }
        // The sky above the first card: the attention card's top while one shows, else the band's. Sizes only, so neither the
        // entrance lift nor the page slide moves it; null until both are measured, and without the column there is none.
        val firstCardTop = if (queue.isNotEmpty()) greetingHeight + with(density) { HOME_CARD_GAP.toPx() } else with(density) { viewport.toPx() } - bandHeight
        val band = if (!nowColumn || greetingHeight == 0 || bandHeight == 0) null else with(density) {
            heroBand(pageWidth.toPx(), firstCardTop, layout.readingMax.toPx(), 0f, 0f, 0f, HERO_SKY_MARGIN.toPx())
        }
        val reportSky by rememberUpdatedState(onHeroSky)
        DisposableEffect(band) {
            reportSky(band?.let { HeroSky(it) { scroll.value } })
            onDispose { reportSky(null) }
        }
        // The page rests on its band while nothing scrolls; the follow-up bar then rides above it.
        val resting = scroll.maxValue == 0
        val reportBand by rememberUpdatedState(onBand)
        val bandDp = with(density) { bandHeight.toDp() }
        DisposableEffect(resting, bandDp) {
            reportBand(if (resting) bandDp else 0.dp)
            onDispose { reportBand(0.dp) }
        }
        // Only where the frame's budget holds: the full, tall window beside the 지금 column at the normal font, and not
        // while the keyboard takes the lower half of the page (the page then scrolls the field into view on purpose).
        if (BuildConfig.DEBUG && layout.full && nowColumn && density.fontScale <= 1f) LaunchedEffect(scroll.maxValue) {
            if (scroll.maxValue > 0 && viewport >= HOME_PAGE_FULL - 8.dp) {
                Log.w("HomeFrame", "홈 needs ${scroll.maxValue} px more than the ${viewport.value} dp page: ${queue.map { it.kind }}")
            }
        }
        Column(Modifier.fillMaxSize().fadingTopEdge(scroll).verticalScroll(scroll, enabled = scroll.maxValue > 0)) {
            // At least the page's height: under the scroll's unbounded maximum the column weighs its sky against this minimum,
            // so the sky takes what the content leaves and the band sits on the page's bottom edge.
            Column(Modifier.fillMaxWidth().heightIn(min = viewport)) {
                HomeGreeting(state, now, status, Modifier.onSizeChanged { greetingHeight = it.height }
                    .then(if (nowColumn) Modifier.heightIn(min = HOME_GREETING_MIN) else Modifier), entrance = entrance)
                AttentionSlot(state, now, queue, nav, act, repository, answers, prompt, snoozes, banner, onBanner, { month -> reminderClosed = monthKey(month) })
                if (!plan.widgetsInColumn) HomeTiles(state, now, plan, nav)
                // The sky: 24 dp at least, then whatever the page has left.
                Spacer(Modifier.height(HOME_SKY_MIN))
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().onSizeChanged { bandHeight = it.height }, verticalArrangement = Arrangement.spacedBy(HOME_BAND_GAP)) {
                    if (demo) DemoStrip { act { repository.ingestCardEvents(listOf(demoCardApproval(repository.state.value.data, System.currentTimeMillis()))) } }
                    DestinationRow(cards, plan.cardColumns)
                    // Held to the frame's slot, as the destinations are, so the band is the height homeFrame budgets.
                    IncidentDock(data, act, repository, Modifier.heightIn(min = HOME_DOCK))
                }
            }
        }
    }
}

/**
 * Without the 지금 column, 홈 carries the column's tiles between the card and the sky: the booking while one is in use or
 * starts within a day ([reservationDueWithinDay]), then 오늘 운행 beside 이달 운행일지 (stacked below EXPANDED). The tiles open
 * what they sum up: 차량, 운행기록 on this month, 월간·Excel on this month.
 */
@Composable private fun HomeTiles(state: TripLogState, now: Long, plan: HomePlan, nav: HomeNav) {
    val gap = LocalLayout.current.cardGap
    val month = YearMonth.from(localDate(now))
    Column(Modifier.fillMaxWidth().padding(top = HOME_CARD_GAP), verticalArrangement = Arrangement.spacedBy(gap)) {
        if (reservationDueWithinDay(state.data, now)) ReservationTile(state.data, now, { nav.navigate(3) })
        val today: @Composable (Modifier) -> Unit = { modifier -> TodayTile(state, now, { nav.openLog(0, month) }, modifier) }
        val thisMonth: @Composable (Modifier) -> Unit = { modifier -> MonthTile(state.data, now, { nav.openReport(month) }, modifier) }
        if (plan.widgetsSideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            today(Modifier.weight(1f))
            thisMonth(Modifier.weight(1f))
        } else {
            today(Modifier)
            thisMonth(Modifier)
        }
    }
}

/**
 * The presenter's controls while demo mode is on (2.13, an 80-dp strip above the destinations): the 시연 모드 tag, then drive
 * and park at 15× (the card's one solid action), a charge (only while parked) and a card approval, at the same weights as
 * before. Under 560 dp the tag stands over the buttons. It is held to the frame's slot ([HOME_DEMO_STRIP]), the height
 * [homeFrame] budgets for it.
 */
@Composable private fun DemoStrip(demoCard: () -> Unit) {
    val moving by DemoDrive.moving.collectAsState()
    val charging by DemoDrive.charging.collectAsState()
    CarCard(Modifier.heightIn(min = HOME_DEMO_STRIP), padding = 12.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val buttons: @Composable RowScope.() -> Unit = {
                PrimaryAction(if (moving) "시연 주차 (P)" else "시연 출발 (D)", Modifier.weight(1.3f)) { DemoDrive.setMoving(!moving) }
                QuietAction(if (charging) "충전 종료" else "충전 시작", Modifier.weight(1f), enabled = !moving) { DemoDrive.setCharging(!charging) }
                QuietAction("카드 승인", Modifier.weight(1f), onClick = demoCard)
            }
            if (maxWidth < 560.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                CarTag(HomeCopy.DEMO_TAG, tone = PillTone.DEMO)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), content = buttons)
            } else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarTag(HomeCopy.DEMO_TAG, Modifier.padding(start = 8.dp), tone = PillTone.DEMO)
                buttons()
            }
        }
    }
}

/** What a purpose tile says under the purpose: whether the trip counts toward the business distance. */
private fun purposeCaption(purpose: TripPurpose) = if (purpose.business) "업무 거리 포함" else "업무 합계 제외"

/**
 * The three purposes as [CarChoiceTile]s (2.13, on 홈's purpose card and in 운행 상세): each with its dot ([purposeColor]),
 * its label and whether it counts toward the business distance, three across from 420 dp of width and stacked below. With
 * a [selected] purpose the tiles are a radio group and the trip's purpose is filled with the accent (none is, while the
 * trip is unclassified); without one (a question) they are buttons that answer at once.
 */
@Composable internal fun PurposeOptions(selected: TripPurpose? = null, onSelect: (TripPurpose) -> Unit) {
    val group = if (selected != null) Modifier.selectableGroup() else Modifier
    val tile: @Composable (TripPurpose, Modifier) -> Unit = { purpose, modifier ->
        CarChoiceTile(purpose.label, purposeCaption(purpose), { onSelect(purpose) }, modifier, selected = selected?.let { it == purpose }, dot = purposeColor(purpose))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 420.dp) Column(group.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            purposeChoices.forEach { purpose -> tile(purpose, Modifier.fillMaxWidth()) }
        } else Row(group.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            purposeChoices.forEach { purpose -> tile(purpose, Modifier.weight(1f)) }
        }
    }
}



// ───────────────────────── 일지 ─────────────────────────

/** The three filters of the log list with their counts; the long 자동 반영 label only where the row has room. */
internal fun logFilterLabels(trips: Int, issues: Int, matched: Int, wide: Boolean): List<String> =
    listOf("전체 $trips", "점검 필요 $issues", if (wide) "자동 반영 $matched" else "자동 $matched")

/** Which list an empty card stands for, and so its mark: no trips (ic_journal), nothing to check (CheckCircle), no matches (ic_receipt). */
internal enum class LogEmptyKind { TRIPS, ISSUES, MATCHED }

/**
 * The log's one empty state (2.13): a short [title] (the stepper and the chip already name the month and the employee), the
 * long title TalkBack keeps ([spoken]), an optional second line, whether 필터 해제 is offered beside 이전 달 보기, and the
 * [kind] that picks the card's mark.
 */
@Immutable
internal data class LogEmptyCopy(val title: String, val spoken: String, val detail: String? = null, val clearable: Boolean = false,
    val kind: LogEmptyKind = LogEmptyKind.TRIPS)

/**
 * What the log says when the list under [filter] (0 전체, 1 점검 필요, 2 자동 반영) has nothing to pick in [month]: no trips,
 * no trip left to check, or no card payment that went in by itself. [monthTrips] counts the month's trips after the employee
 * filter, whose label is [employee] when one is set. The title is the short one of [LogCopy]; TalkBack hears the long one,
 * with the month and the employee ("11월 영업 · 권 님의 운행 기록이 없어요"). 필터 해제 ([LogEmptyCopy.clearable]) whenever a
 * filter other than 전체 or an employee is set; 이전 달 보기 always.
 */
internal fun logEmptyCopy(filter: Int, month: YearMonth, monthTrips: Int, employee: String?, today: LocalDate): LogEmptyCopy {
    val name = monthLabel(month, today)
    val who = employee?.let { " $it 님의" }.orEmpty()
    val clearable = filter != 0 || employee != null
    return when {
        filter == 1 && monthTrips > 0 -> LogEmptyCopy(LogCopy.EMPTY_ISSUES, "$name$who 점검할 운행이 없어요", LogCopy.EMPTY_ISSUES_LINE, clearable, LogEmptyKind.ISSUES)
        filter == 2 -> LogEmptyCopy(LogCopy.EMPTY_MATCHED, "$name 카드 결제가 자동으로 들어간 운행이 없어요", clearable = clearable, kind = LogEmptyKind.MATCHED)
        else -> LogEmptyCopy(LogCopy.EMPTY_TRIPS, "$name$who 운행 기록이 없어요", clearable = clearable)
    }
}

/**
 * The 운행기록 list's width when the page holds the list beside the detail, else null: the list then fills the page and the
 * detail opens as a side sheet (pure, unit-tested). Two panes need EXPANDED or wider and 900 dp of effective page (its width
 * ÷ the font scale); the list takes 48 % of what the [gap] leaves, 526 of the 1112-dp page at FULL (the detail 570). A 1.3×
 * font (805 effective) and the 832-dp page of a 1400-dp window keep the side sheet.
 */
internal fun logListWidth(layout: LayoutTokens, page: Dp, gap: Dp, fontScale: Float): Dp? =
    if (layout.expanded && page / fontScale.coerceAtLeast(1f) >= 900.dp) (page - gap) * .48f else null

/**
 * 운행기록 (2.13, docs/design/DESIGN_2_13_SPEC.md §7). Where the page holds the list beside the detail ([logListWidth]) the
 * header is one row, the month stepper · the 3-way filter (up to 560) · the employee chip, over one 32-dp row of tags (the
 * month's totals, 시연 데이터, and on 점검 필요 its 필수 · 참고 counts); the detail then always shows a trip, the one tapped or
 * else the list's first row ([logSelection]), so the page opens on its newest trip, first issue or first match instead of an
 * empty half. Otherwise the list fills the page under the narrow rows (stepper ··· chip, then the filter; COMPACT puts the
 * chip under the stepper) and the detail opens as a side sheet, never by itself. A short window folds the filters and the
 * employee into one chip on the stepper's row, the tags at its end.
 *
 * The tap ([selectedId]) is cleared by a month, employee or filter change and by a delete, so the detail goes back to
 * following the list; an edit in the pane pins its trip, so a fix that drops it from 점검 필요 never swaps the detail under
 * the driver's finger. An empty list is one card in the list column; its 점검 필요 card leads to 월간·Excel ([openReport])
 * while the month is ready to close.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LogPane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit,
    initialTripId: String?, initialFilter: Int = 0, initialMonth: String? = null, openReport: (YearMonth) -> Unit = {}) {
    val layout = LocalLayout.current
    val fontScale = LocalDensity.current.fontScale
    val today = localDate(now)
    var month by rememberSaveable { mutableStateOf(initialMonth?.takeIf { runCatching { YearMonth.parse(it) }.isSuccess } ?: YearMonth.from(today).toString()) }
    var filter by rememberSaveable(initialFilter) { mutableIntStateOf(initialFilter) }
    // The row the driver tapped, or the trip a button opened the page on; null while the detail follows the list.
    var selectedId by rememberSaveable(initialTripId) { mutableStateOf(initialTripId) }
    var employeeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val period = YearMonth.parse(month)
    val all = state.data.trips
    val byId = remember(all) { all.associateBy { it.id } }
    val audit = remember(all) { auditTrips(all) }
    val trips = tripsIn(all, period.atDay(1), period.atEndOfMonth(), zone).filter { employeeFilter == null || it.driverId == employeeFilter }
    val totals = logbookTotals(trips)
    val ids = trips.map { it.id }.toSet()
    // 점검 필요 in the order a month is finished: 마감 전 필수 first, then the rest, newest first.
    val issues = issueOrder(audit.filter { it.tripId in ids }, byId)
    val required = issues.count { issueRequired(it.type) }
    val flagged = issues.mapTo(HashSet()) { it.tripId }
    // 자동 반영 follows the stepper's month like the other two lists (its list reads the month from the clock it is handed).
    val thisMonth = YearMonth.from(today)
    val periodClock = if (period == thisMonth) now else period.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val matchedRows = matchedTripsThisMonth(state.data, zone, period.atDay(1))
    val demo = state.data.profile.demoMode
    val employeeName = state.data.drivers.firstOrNull { it.id == employeeFilter }?.label
    val employeeLabel = employeeName ?: LogCopy.ALL_EMPLOYEES
    val previousMonth = { month = period.minusMonths(1).toString(); selectedId = null }
    val showFilter: (Int) -> Unit = { index -> filter = index; selectedId = null }
    val stepper: @Composable () -> Unit = {
        PeriodStepper(reportStepperCopy(ReportPeriod(period, false), today), period < thisMonth, previousMonth) { month = period.plusMonths(1).toString(); selectedId = null }
    }
    val employeeItems: @Composable ColumnScope.(() -> Unit) -> Unit = { close ->
        ChipMenuItem(LogCopy.ALL_EMPLOYEES, current = employeeFilter == null) { employeeFilter = null; selectedId = null; close() }
        state.data.drivers.forEach { driver -> ChipMenuItem(driver.label, current = employeeFilter == driver.id) { employeeFilter = driver.id; selectedId = null; close() } }
    }
    val employeeChip: @Composable (Modifier) -> Unit = { modifier ->
        DropdownChip(employeeLabel, LogCopy.SPOKEN_EMPLOYEE_FILTER, modifier.widthIn(max = 280.dp), icon = rememberVectorPainter(Icons.Outlined.Person),
            active = employeeFilter != null, content = employeeItems)
    }
    // The facts of the list on screen, each a tag: the totals (none in an empty month), demo data once, the 점검 필요 counts.
    val countLine = if (filter == 1) issueCountLine(required, issues.size - required) else ""
    val hasTags = trips.isNotEmpty() || demo || countLine.isNotEmpty()
    val tags: @Composable (Modifier, Arrangement.Horizontal) -> Unit = { modifier, arrangement ->
        FlowRow(modifier, horizontalArrangement = arrangement, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (trips.isNotEmpty()) CarTag(logTotalsLine(totals))
            if (demo) CarTag(LogCopy.DEMO, tone = PillTone.DEMO)
            if (countLine.isNotEmpty()) CarTag(countLine)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The header follows the list: one row over a split page, the narrow rows over a full-width list.
        val listWidth = logListWidth(layout, maxWidth, layout.cardGap, fontScale)
        val twoPane = listWidth != null
        val filters = logFilterLabels(trips.size, issues.size, matchedRows.size, twoPane)
        val shownId = logSelection(selectedId, byId.keys, logTopId(filter, trips, issues, matchedRows), twoPane)
        val shown = shownId?.let(byId::get)
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    // A short window: the three filters and the employee fold into one 56-dp chip on the stepper's row, the tags at its end.
                    layout.short -> Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        stepper()
                        DropdownChip("${filters[filter]} · $employeeLabel", LogCopy.SPOKEN_LIST_FILTER, Modifier.widthIn(max = 320.dp),
                            active = filter != 0 || employeeFilter != null) { close ->
                            filters.forEachIndexed { index, label -> ChipMenuItem(label, current = index == filter) { showFilter(index); close() } }
                            HorizontalDivider(color = Car.skin.cardBorder)
                            employeeItems(close)
                        }
                        if (hasTags) tags(Modifier.weight(1f), Arrangement.spacedBy(8.dp, Alignment.End)) else Spacer(Modifier.weight(1f))
                    }
                    twoPane -> {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            stepper()
                            // The filter takes what is left of the row, up to 560; the employee chip ends the row.
                            Box(Modifier.weight(1f)) { Segmented(filters, filter, Modifier.widthIn(max = 560.dp), onSelect = showFilter) }
                            employeeChip(Modifier)
                        }
                        if (hasTags) tags(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp))
                    }
                    else -> {
                        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            stepper()
                            Spacer(Modifier.weight(1f))
                            if (!layout.compact) employeeChip(Modifier)
                        }
                        if (layout.compact) employeeChip(Modifier)
                        Segmented(filters, filter, onSelect = showFilter)
                        if (hasTags) tags(Modifier.fillMaxWidth(), Arrangement.spacedBy(8.dp))
                    }
                }
            }
            // An empty list is one card in the list column, never a blank column beside an empty detail panel.
            val empty = when (filter) { 0 -> trips.isEmpty(); 1 -> issues.isEmpty(); else -> matchedRows.isEmpty() }
            // The empty 점검 필요 card of a month with nothing left to finish leads to its close.
            val closable = filter == 1 && monthStatus(state.data, period, zone).ready
            val list: @Composable (Modifier) -> Unit = { modifier ->
                val group = modifier.semantics { isTraversalGroup = true }
                if (empty) Box(group.verticalScroll(rememberScrollState())) {
                    LogEmptyCard(logEmptyCopy(filter, period, trips.size, employeeName, today), onPrevious = previousMonth,
                        onClear = { filter = 0; employeeFilter = null; selectedId = null },
                        closeLabel = if (closable) "${monthLabel(period, today)} 마감하러 가기" else null, onClose = { openReport(period) })
                } else when (filter) {
                    // Opened on a trip (the assistant's citation), the list scrolls that day into view until another row is tapped.
                    0 -> TripList(trips, state.data, flagged, shownId, group, compact = layout.short, scrollTo = initialTripId?.takeIf { it == selectedId }) { selectedId = it }
                    1 -> IssueList(issues, state.data, shownId, group, act, repository, openDriver) { selectedId = it }
                    else -> MatchedTripList(state.data, periodClock, act, repository, shownId, group) { selectedId = it }
                }
            }
            if (listWidth != null) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                // FULL: list 526 + gap 16 + detail 570 share the 1112-dp page.
                list(Modifier.width(listWidth).fillMaxHeight())
                val skin = Car.skin
                if (shown != null) Surface(Modifier.weight(1f).fillMaxHeight().semantics { isTraversalGroup = true }, shape = RoundedCornerShape(20.dp),
                    color = skin.card, contentColor = Scene.text, border = BorderStroke(1.dp, skin.cardBorder)) {
                    TripDetail(shown, state.data, audit.filter { it.tripId == shown.id }, act, repository, openDriver, inPane = true,
                        pin = { selectedId = shown.id }) { selectedId = null }
                }
            } else Box(Modifier.weight(1f).fillMaxWidth()) {
                // While the detail sheet is up, the list under its scrim is out of TalkBack's reach: the sheet is the one pane to read.
                list(Modifier.fillMaxSize().then(if (shown != null) Modifier.clearAndSetSemantics {} else Modifier))
                SideSheet(shown, onDismiss = { selectedId = null }, widthFraction = if (layout.compact) .94f else .62f, title = LogCopy.DETAIL) { trip ->
                    TripDetail(trip, state.data, audit.filter { it.tripId == trip.id }, act, repository, openDriver, inPane = false,
                        pin = { selectedId = trip.id }) { selectedId = null }
                }
            }
        }
    }
}

/**
 * The car's period stepper (운행기록, 월간·Excel; 2.12): one 66-dp stadium on the card colour with its hairline, two round
 * arrows inside 5 dp of padding and the period between them, a heading TalkBack re-reads when it changes. The arrows' names
 * come from [copy] ([reportStepperCopy]), the dead › says why it is off.
 */
@Composable private fun PeriodStepper(copy: StepperCopy, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    val skin = Car.skin
    Surface(shape = RoundedCornerShape(50), color = skin.card, contentColor = Scene.text, border = BorderStroke(1.dp, skin.cardBorder)) {
        Row(Modifier.padding(5.dp), verticalAlignment = Alignment.CenterVertically) {
            MonthStepButton(forward = false, description = copy.back, onClick = onBack)
            Text(copy.label, style = MaterialTheme.typography.headlineSmall, color = Scene.text, textAlign = TextAlign.Center, maxLines = 1,
                modifier = Modifier.widthIn(min = 180.dp).padding(horizontal = 8.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite })
            MonthStepButton(forward = true, description = copy.forward, enabled = canForward, onClick = onForward)
        }
    }
}

/**
 * One arrow of a [PeriodStepper]: a round 56-dp target on the icon disc with a 28-dp chevron in the ink of line icons. It settles
 * to .98 under the press fill, never a ripple. Disabled, the disc goes and the chevron drops to 38 %, so a dead arrow never
 * reads stronger than a live one. [description] is what TalkBack reads (이전 달 · 다음 해 …), with the reason when it is off.
 * Shared since 2.13: 홈's attention card pages its queue with the same pair.
 */
@Composable internal fun MonthStepButton(forward: Boolean, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val skin = Car.skin
    val interaction = remember { MutableInteractionSource() }
    val tint = if (enabled) skin.icon else skin.icon.copy(alpha = skin.icon.alpha * .38f)
    Box(modifier.size(56.dp).pressable(interaction, .98f).clip(CircleShape)
        .then(if (enabled) Modifier.background(skin.disc) else Modifier)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .pressFill(interaction)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(if (forward) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = null,
            modifier = Modifier.size(28.dp), tint = tint)
    }
}

/**
 * The log's one empty state (2.13): a car card at the top of the list column (max 560) with the list's mark on the disc, the
 * short title (TalkBack hears the long one, with the month and the employee) and its line, then the ways on: 이전 달 보기,
 * 필터 해제 while a filter or an employee narrows the list ([LogEmptyCopy.clearable]), and, on an empty 점검 필요 of a month
 * that is ready to close, [closeLabel] ("10월 마감하러 가기"), the card's one primary action, which opens 월간·Excel on it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun LogEmptyCard(copy: LogEmptyCopy, onPrevious: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier,
    closeLabel: String? = null, onClose: () -> Unit = {}) {
    val icon = when (copy.kind) {
        LogEmptyKind.TRIPS -> painterResource(R.drawable.ic_journal)
        LogEmptyKind.ISSUES -> rememberVectorPainter(Icons.Outlined.CheckCircle)
        LogEmptyKind.MATCHED -> painterResource(R.drawable.ic_receipt)
    }
    CarCard(modifier.widthIn(max = 560.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CarIconDisc(icon)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(copy.title, style = MaterialTheme.typography.titleSmall, color = Scene.text,
                    modifier = Modifier.semantics { heading(); contentDescription = copy.spoken })
                copy.detail?.let { CarLine(it) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
            itemVerticalAlignment = Alignment.CenterVertically) {
            CarPillButton(LogCopy.PREVIOUS_MONTH, onPrevious)
            if (copy.clearable) CarPillButton(LogCopy.CLEAR_FILTER, onClear)
            if (closeLabel != null) PrimaryAction(closeLabel, onClick = onClose)
        }
    }
}

/**
 * One row of a [DropdownChip]'s menu (2.13): Material's menu item at the car's 56-dp touch target (its own minimum is 48), the
 * label in text and, on the [current] one, a check in the accent ink (4.93 / 7.66 on the menu's card); TalkBack hears it as
 * selected.
 */
@Composable private fun ChipMenuItem(label: String, current: Boolean, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }, onClick = onClick,
        modifier = Modifier.heightIn(min = 56.dp).semantics { selected = current },
        trailingIcon = if (current) { { Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(20.dp), tint = Car.skin.accentInk) } } else null,
        colors = MenuDefaults.itemColors(textColor = Scene.text))

/**
 * A 56-dp chip that opens a menu (2.13): the log's employee filter, and in a short window the whole filter set. A stadium on
 * the card with the chip border: an optional 20-dp [icon] (the employee chip's person), the [label], a 20-dp arrow; it settles
 * to .98 under the press fill, never a ripple. While it narrows the list ([active]) it takes [carSelection], so a forgotten
 * filter shows. TalkBack hears one drop-down list, "[name], [label]" ("직원 필터, 전체 직원"). The menu is the car's card
 * (corner 20, the hairline); [content] gets a close call.
 */
@Composable private fun DropdownChip(label: String, name: String, modifier: Modifier = Modifier, icon: Painter? = null, active: Boolean = false,
    content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val skin = Car.skin
    val shape = RoundedCornerShape(50)
    Box(modifier) {
        Row(Modifier.pressable(interaction, .98f).clip(shape).background(skin.card)
            .then(if (active) Modifier.carSelection(true, shape) else Modifier.border(1.dp, skin.chipBorder, shape))
            .clickable(interactionSource = interaction, indication = null, role = Role.DropdownList) { open = true }
            .semantics { contentDescription = "$name, $label" }
            .pressFill(interaction).heightIn(min = 56.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (icon != null) Icon(icon, contentDescription = null, Modifier.size(20.dp), tint = skin.icon)
            Text(label, style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics {})
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, Modifier.size(20.dp), tint = Scene.textSecondary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, shape = RoundedCornerShape(20.dp), containerColor = skin.card,
            border = BorderStroke(1.dp, skin.cardBorder)) { content { open = false } }
    }
}

/** Keeps the last value shown while a sheet animates out after its item is cleared. */
private class LastShown<T> { var value: T? = null }

/**
 * The car's side sheet for a window too narrow for two columns: a scrim over the list and a sheet at the end edge that lifts
 * 24 dp and fades in (200 ms) and drops out (160 ms); a fade snap under reduced motion. Tapping the scrim or Back closes it.
 * 2.13: the sheet is the car's card (the hairline, corner 28) and the scrim is black at [SHEET_SCRIM_ALPHA], the assistant
 * sheet's, where the page colour at .55 laid a white veil over the list by day. TalkBack meets the sheet as one pane named
 * [title]; the scrim is for touch only (the sheet carries its own 닫기), and the caller takes what lies under the scrim out
 * of TalkBack's reach while [item] is set.
 */
@Composable private fun <T : Any> BoxScope.SideSheet(item: T?, onDismiss: () -> Unit, modifier: Modifier = Modifier, widthFraction: Float = .62f,
    title: String = "상세", content: @Composable (T) -> Unit) {
    val reduce = LocalReduceMotion.current
    val skin = Car.skin
    val lift = with(LocalDensity.current) { 24.dp.roundToPx() }
    val last = remember { LastShown<T>() }
    if (item != null) last.value = item
    val shown = last.value
    BackHandler(enabled = item != null, onBack = onDismiss)
    AnimatedVisibility(item != null, Modifier.matchParentSize(), enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(160, easing = FastOutLinearInEasing))) {
        // Cleared before the click so its action goes too: a screen-wide unlabeled target would only get in TalkBack's way.
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}.background(Color.Black.copy(alpha = SHEET_SCRIM_ALPHA))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(item != null, modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(widthFraction),
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { lift },
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(160, easing = FastOutLinearInEasing)) + slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { lift }) {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = title; isTraversalGroup = true }, shape = RoundedCornerShape(28.dp), color = skin.card,
            contentColor = Scene.text, border = BorderStroke(1.dp, skin.cardBorder)) { if (shown != null) content(shown) }
    }
}

/**
 * 전체 (2.13): one car card per day, newest first, its date a heading with the day's count and distance at its end, and its
 * trips as [TripRow]s 4 dp apart; the cards stand the page's card gap apart. [flagged] holds the trips 점검 필요 lists,
 * [selectedId] the one the detail shows. Opened on a trip ([scrollTo]), the list first brings that day into view.
 */
@Composable private fun TripList(trips: List<Trip>, data: TripLogData, flagged: Set<String>, selectedId: String?, modifier: Modifier, compact: Boolean = false,
    scrollTo: String? = null, onSelect: (String) -> Unit) {
    val days = trips.reversed().groupBy { localDate(it.startAt) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) {
        val day = scrollTo?.let { id -> days.values.indexOfFirst { dayTrips -> dayTrips.any { it.id == id } } } ?: -1
        if (day > 0) list.scrollToItem(day)
    }
    LazyColumn(modifier.fadingTopEdge(list), state = list, verticalArrangement = Arrangement.spacedBy(LocalLayout.current.cardGap)) {
        days.forEach { (date, dayTrips) ->
            item(key = "d$date") {
                CarCard(padding = 16.dp, gap = 4.dp, title = {
                    CarSectionTitle(dayLabel(date)) {
                        Text("${dayTrips.size}건 · ${kmText(dayTrips.sumOf { it.distanceKm })}", style = MaterialTheme.typography.labelMedium,
                            color = Scene.textSecondary, maxLines = 1)
                    }
                }) {
                    dayTrips.forEach { trip -> key(trip.id) { TripRow(trip, data, trip.id in flagged, trip.id == selectedId, compact) { onSelect(trip.id) } } }
                }
            }
        }
    }
}

/**
 * One trip of a list (2.13, docs/design/DESIGN_2_13_SPEC.md §7.3): a 72-dp row (64 when [compact]) in a 14-dp shape, the
 * open one in [carSelection]. A 12-dp negative dot when [flagged] (its slot is kept, so the times align) before the start
 * time in tabular figures; the title is the route, or the distance and time without an address ([tripRowTitle]), over one
 * line ([tripRowLine]: 마감 후 수정, the person or 사용자 미지정, a cost); the purpose tag ends the row. One TalkBack stop
 * ([tripRowSpoken]) that says it is selected and why it is flagged; it settles to .98 under the press fill, never a ripple.
 */
@Composable internal fun TripRow(trip: Trip, data: TripLogData, flagged: Boolean, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val edited = editedAfterClose(trip)
    val shape = RoundedCornerShape(14.dp)
    val interaction = remember { MutableInteractionSource() }
    val spoken = tripRowSpoken(trip, driver, edited)
    val lines = carLineMaxLines(LocalDensity.current.fontScale)
    Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(shape).carSelection(selected, shape)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = LogCopy.ROW_ACTION, onClick = onClick)
        .semantics { contentDescription = spoken; this.selected = selected; if (flagged) stateDescription = LogCopy.ROW_FLAGGED }
        .pressFill(interaction).heightIn(min = if (compact) 64.dp else 72.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        // The row speaks as one ([spoken]); its parts are drawn only.
        Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).then(if (flagged) Modifier.clip(CircleShape).background(Scene.negative) else Modifier))
            Spacer(Modifier.width(8.dp))
            Text(hm(trip.startAt), style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(tripRowTitle(trip), style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(tripRowLine(trip, driver?.let { it.name.ifBlank { it.label } }, edited), style = MaterialTheme.typography.bodySmall, color = Scene.textMuted,
                maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.clearAndSetSemantics {}) { PurposeChip(trip.purpose) }
    }
}

/**
 * Adds the stretch the odometer says was driven unrecorded before an issue's trip (기록되지 않은 주행) as a trip of its own,
 * and answers "누락 구간 12.0 km를 추가했어요" with an undo that deletes the new trip (2.13: it used to write the trip without
 * a word). The issue card's button and the detail's 구간 추가 share it.
 */
@Composable private fun rememberGapFiller(act: Act, repository: TripLogRepository): (TripIssue) -> Unit {
    val followUp = LocalFollowUp.current
    return { issue ->
        val previous = issue.previousTripId
        if (previous != null) act {
            val before = repository.state.value.data.trips.mapTo(HashSet()) { it.id }
            repository.fillGap(previous, issue.tripId)
            val added = repository.state.value.data.trips.firstOrNull { it.id !in before && it.origin == TripOrigin.GAP_FILL }
            val line = gapAddedLine(added?.distanceKm ?: issue.gapKm ?: 0.0)
            followUp(if (added == null) FollowUp(line) else FollowUp(line, LogCopy.UNDO) { repository.deleteTrip(added.id) })
        }
    }
}

/**
 * 점검 필요 (2.13): one car card per issue, in [issueOrder]. The card opens its trip (자세히 went); while the detail shows that
 * trip ([selectedId]) it takes the accentInk ring alone, so its buttons keep the card under them. Its title is the issue with
 * its distance, then a tag, 마감 전 필수 or 참고 ([issueRequired]); under it the trip's day and start, and one line
 * ([issueLine]) whose long form TalkBack hears. The buttons fix what can be fixed here: the three purposes, 사용자 지정,
 * 기록 삭제 for a 0 km trip, and 누락 구간을 운행으로 추가, each answered on the follow-up bar with its undo.
 */
@Composable private fun IssueList(issues: List<TripIssue>, data: TripLogData, selectedId: String?, modifier: Modifier, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, onOpen: (String) -> Unit) {
    val trips = data.trips.associateBy { it.id }
    val classify = rememberClassifier(data, act, repository)
    val fillGap = rememberGapFiller(act, repository)
    val followUp = LocalFollowUp.current
    val list = rememberLazyListState()
    val ring = Car.skin.accentInk
    LazyColumn(modifier.fadingTopEdge(list), state = list, verticalArrangement = Arrangement.spacedBy(LocalLayout.current.cardGap)) {
        items(issues, key = { "${it.type}-${it.tripId}" }) { issue ->
            val trip = trips[issue.tripId] ?: return@items
            val open = trip.id == selectedId
            CarCard(Modifier.then(if (open) Modifier.border(2.dp, ring, RoundedCornerShape(20.dp)) else Modifier), selected = open,
                padding = 20.dp, onClick = { onOpen(trip.id) }, onClickLabel = LogCopy.ROW_ACTION) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(Scene.negative))
                        Spacer(Modifier.width(12.dp))
                        Text(issueTitle(issue), style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).semantics { heading() })
                        Spacer(Modifier.width(12.dp))
                        CarTag(if (issueRequired(issue.type)) LogCopy.REQUIRED else LogCopy.REFERENCE)
                    }
                    Text("${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)}", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                    CarLine(issueLine(issue.type, trip), spoken = issue.type.detail)
                }
                when (issue.type) {
                    IssueType.UNCLASSIFIED -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        purposeChoices.forEach { purpose -> QuietAction(purpose.label, Modifier.weight(1f)) { classify(trip, purpose, EditSource.DRIVER) } }
                    }
                    IssueType.NO_DRIVER -> QuietAction(LogCopy.ASSIGN_DRIVER) { openDriver(trip.id) }
                    IssueType.ZERO_DISTANCE -> QuietAction(LogCopy.DELETE_RECORD, color = Scene.negative) {
                        act { repository.deleteTrip(trip.id); followUp(FollowUp("0 km 기록을 삭제했어요", LogCopy.UNDO) { repository.restoreTrip(trip) }) }
                    }
                    IssueType.ODOMETER_GAP -> if (issue.previousTripId != null) QuietAction(LogCopy.FILL_GAP) { fillGap(issue) }
                    else -> {}
                }
            }
        }
    }
}

/** The fold of 운행 비용's form (추가 / 닫기): 200 ms like an ⓘ row, a snap under reduced motion. The only new motion of 운행기록. */
private const val EXPENSE_FOLD_MS = 200

/**
 * 운행 상세 (2.13, docs/design/DESIGN_2_13_SPEC.md §7.4), on the card of the pane ([inPane]) or of the side sheet, in blocks a
 * divider apart: the title (day, start and end, a heading that the pane announces politely, since it changes with the row
 * the driver opens; 시연 데이터 and 마감 후 수정 as tags; 닫기 in the sheet only); the distance with ⑤ 주행 전 · ⑥ 주행 후 · 주행
 * 시간 and why the odometer cannot be edited, then the trip's other issues ([issues] less the two the tiles and the person
 * row answer), a gap with its 구간 추가; 출발 · 도착; 운행 목적 as [PurposeOptions] with its source as a tag; the person (④
 * 사용자) with 바꾸기 or 지정; 운행 비용, its sum trailing the title and its rows in the one money style, the navigation's
 * toll as a one-tap chip ([tollSuggestion]), and the form folded behind 추가 (five type chips and a [CarMoneyField]); ⑩ 비고;
 * 수정 이력 behind ⓘ; and 이 기록 삭제, which asks first ([CarDialog], destroy). Every edit answers on the follow-up bar with
 * its undo, and calls [pin] first, so the pane keeps this trip while a fix drops it from the list. [close] closes the sheet
 * and clears the tap; a delete calls it too. A new trip composes fresh (its figures swap at once, the form folds, the
 * scroll returns to the top); only the title stays, so its live region is heard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun TripDetail(trip: Trip, data: TripLogData, issues: List<TripIssue>, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit,
    inPane: Boolean, pin: () -> Unit, close: () -> Unit) {
    val negative = Scene.negative
    val classify = rememberClassifier(data, act, repository)
    val fillGap = rememberGapFiller(act, repository)
    val followUp = LocalFollowUp.current
    val reduce = LocalReduceMotion.current
    val scroll = remember(trip.id) { ScrollState(0) }
    var deleting by remember(trip.id) { mutableStateOf(false) }
    val edited = editedAfterClose(trip)
    // A cost the driver adds (a typed amount or the toll chip), answered with an undo that removes that very cost.
    val addExpense: (ExpenseType, Long, () -> Unit) -> Unit = { type, won, done ->
        pin()
        act {
            repository.addExpense(trip.id, type, won, EditSource.DRIVER)
            done()
            val added = repository.state.value.data.trips.firstOrNull { it.id == trip.id }?.expenses?.lastOrNull()
            followUp(if (added == null) FollowUp(expenseAddedLine(type, won)) else FollowUp(expenseAddedLine(type, won), LogCopy.UNDO) {
                val index = repository.state.value.data.trips.firstOrNull { it.id == trip.id }?.expenses?.indexOfLast { it == added } ?: -1
                if (index >= 0) repository.removeExpense(trip.id, index)
            })
        }
    }
    Column(Modifier.fillMaxSize().fadingTopEdge(scroll).verticalScroll(scroll).padding(carCardPadding()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        val hasTags = trip.demo || edited || !inPane
        CarSectionTitle(tripDetailTitle(trip), icon = painterResource(R.drawable.ic_vehicle), live = inPane, trailing = if (!hasTags) null else { {
            if (trip.demo) CarTag(LogCopy.DEMO, tone = PillTone.DEMO)
            if (edited) CarTag(LogCopy.EDITED)
            if (!inPane) CarPillButton(LogCopy.CLOSE, close)
        } })
        key(trip.id) {
            var note by rememberSaveable(trip.note) { mutableStateOf(trip.note) }
            var expenseType by rememberSaveable { mutableIntStateOf(1) }
            var amount by rememberSaveable { mutableStateOf("") }
            var adding by rememberSaveable { mutableStateOf(false) }
            // The form unfolds under the trip's cost rows, often past the pane's edge: 추가 scrolls it into view once it has
            // opened. Bumped by the pill only, so a trip reopened with the form standing does not scroll by itself.
            val form = remember { BringIntoViewRequester() }
            var reveal by remember { mutableIntStateOf(0) }
            LaunchedEffect(reveal) {
                if (reveal > 0) {
                    if (!reduce) delay(EXPENSE_FOLD_MS.toLong())
                    form.bringIntoView()
                }
            }
            val driver = data.drivers.firstOrNull { it.id == trip.driverId }
            CarDivider()
            // The distance as 오늘 운행 shows it, then the form's two odometer columns and the time.
            Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(String.format(Locale.KOREA, "%,.1f", trip.distanceKm), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
                    color = Scene.text, maxLines = 1)
                Text("km", style = MaterialTheme.typography.titleMedium, color = Scene.textMuted, modifier = Modifier.padding(bottom = 6.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val gauge = painterResource(R.drawable.ic_gauge)
                val before = kmText(trip.startOdometerKm)
                val after = kmText(trip.endOdometerKm)
                val time = durationText(trip.durationMillis)
                CarMiniStat(gauge, LogCopy.BEFORE, before, Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${LogCopy.SPOKEN_BEFORE} $before" })
                CarMiniStat(gauge, LogCopy.AFTER, after, Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${LogCopy.SPOKEN_AFTER} $after" })
                CarMiniStat(painterResource(R.drawable.ic_clock), LogCopy.DURATION, time, Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${LogCopy.DURATION} $time" })
            }
            CarInfoRow(odometerNote(trip.origin))
            // A flagged row never said why: the trip's other issues, each with what it found (목적 and 사용자 are answered below).
            issues.filterNot { issueRequired(it.type) }.forEach { issue ->
                CarRow(issueTitle(issue), line = issueLine(issue.type, trip), spoken = "${issueTitle(issue)}, ${issue.type.detail}",
                    lead = { Box(Modifier.size(12.dp).clip(CircleShape).background(Scene.negative)) },
                    trailing = if (issue.type != IssueType.ODOMETER_GAP || issue.previousTripId == null) null else { {
                        CarPillButton(LogCopy.FILL_GAP_SHORT, { pin(); fillGap(issue) }, description = LogCopy.FILL_GAP)
                    } })
            }
            CarDivider()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DetailPlaceRow(LogCopy.FROM, trip.startAddress?.takeIf { it.isNotBlank() } ?: LogCopy.NO_ADDRESS)
                DetailPlaceRow(LogCopy.TO, listOfNotNull(trip.destination, trip.endAddress).filter { it.isNotBlank() }.distinct().joinToString(" · ")
                    .ifBlank { if (trip.open) LogCopy.RECORDING else LogCopy.NO_ADDRESS })
            }
            CarDivider()
            val source = trip.purposeSource
            CarSectionTitle(LogCopy.PURPOSE, icon = painterResource(R.drawable.ic_report), trailing = if (source == null) null else { { CarTag(source.label) } })
            PurposeOptions(trip.purpose) { purpose -> if (purpose != trip.purpose) { pin(); classify(trip, purpose, EditSource.DRIVER) } }
            CarDivider()
            val person = driver?.let { it.name.ifBlank { it.label } }
            CarRow(person ?: LogCopy.NO_DRIVER, line = driver?.let { listOf(LogCopy.DRIVER, it.department).filter(String::isNotBlank).joinToString(" · ") } ?: LogCopy.DRIVER_EMPTY,
                lead = { DriverAvatar(driver, 40.dp) }, trailing = {
                    CarPillButton(if (driver != null) LogCopy.CHANGE else LogCopy.ASSIGN, { pin(); openDriver(trip.id) },
                        description = if (driver != null) LogCopy.SPOKEN_CHANGE else LogCopy.SPOKEN_ASSIGN)
                })
            CarDivider()
            CarSectionTitle(LogCopy.COSTS, icon = painterResource(R.drawable.ic_won)) {
                if (trip.expenses.isNotEmpty()) CarAmount(trip.expenseWon)
                CarPillButton(if (adding) LogCopy.CLOSE else LogCopy.ADD, { adding = !adding; if (adding) reveal++ },
                    Modifier.semantics { stateDescription = if (adding) LogCopy.UNFOLDED else LogCopy.FOLDED },
                    description = if (adding) LogCopy.SPOKEN_CLOSE_COST else LogCopy.SPOKEN_ADD_COST)
            }
            if (trip.expenses.isEmpty()) CarLine(LogCopy.COSTS_EMPTY)
            trip.expenses.forEachIndexed { index, expense ->
                CarMoneyRow(expense.type.label, expense.amountWon, line = expenseLine(expense), action = {
                    CarPillButton(LogCopy.DELETE, {
                        pin()
                        act {
                            repository.removeExpense(trip.id, index)
                            followUp(FollowUp("${expense.type.label} ${formatWon(expense.amountWon)}을 삭제했어요", LogCopy.UNDO) {
                                val charge = expense.cardChargeId
                                if (charge != null) repository.confirmCardCharge(charge, trip.id) else repository.addExpense(trip.id, expense.type, expense.amountWon, expense.source)
                            })
                        }
                    }, color = negative, description = "${expense.type.label} ${formatWon(expense.amountWon)} 삭제")
                })
            }
            // The fare the navigation expected for a road with gates, one tap from the trip's costs.
            tollSuggestion(trip)?.let { fare ->
                CarExampleChip(tollChipText(fare), { addExpense(ExpenseType.TOLL, fare) {} }, actionLabel = LogCopy.SPOKEN_TOLL_ACTION)
            }
            AnimatedVisibility(adding, label = "expense-form",
                enter = if (reduce) fadeIn(snap()) else fadeIn(tween(EXPENSE_FOLD_MS, easing = FastOutSlowInEasing)) + expandVertically(tween(EXPENSE_FOLD_MS, easing = FastOutSlowInEasing)),
                exit = if (reduce) fadeOut(snap()) else fadeOut(tween(EXPENSE_FOLD_MS, easing = FastOutSlowInEasing)) + shrinkVertically(tween(EXPENSE_FOLD_MS, easing = FastOutSlowInEasing))) {
                Column(Modifier.bringIntoViewRequester(form), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ExpenseType.entries.forEachIndexed { index, type -> CarChoiceChip(type.label, index == expenseType) { expenseType = index } }
                    }
                    val input = moneyInput(amount, 1, 10_000_000, LogCopy.AMOUNT_HINT)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        CarMoneyField(LogCopy.AMOUNT, amount, { amount = it }, input, Modifier.weight(1f), done = true)
                        PrimaryAction(LogCopy.ADD, enabled = input.ok) {
                            // The field clears once the cost is in, never before: a refused amount stays to be fixed.
                            input.won?.let { won -> addExpense(ExpenseType.entries[expenseType], won) { amount = "" } }
                        }
                    }
                }
            }
            CarDivider()
            CarSectionTitle(LogCopy.NOTE, icon = rememberVectorPainter(Icons.Outlined.Edit))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Field(LogCopy.NOTE_FIELD, note, { if (it.length <= 300) note = it }, Modifier.weight(1f), done = true, hint = "${note.length} / 300")
                PrimaryAction(LogCopy.SAVE, enabled = note != trip.note) {
                    val before = trip.note
                    val text = note.trim()
                    pin()
                    act {
                        repository.setNote(trip.id, text)
                        followUp(FollowUp(LogCopy.NOTE_SAVED, LogCopy.UNDO) { repository.setNote(trip.id, before) })
                    }
                }
            }
            if (trip.history.isNotEmpty() || !trip.open) CarDivider()
            if (trip.history.isNotEmpty()) CarInfoRow(historyLine(trip.history.size), more = trip.history.takeLast(8).reversed())
            if (!trip.open) CarPillButton(LogCopy.DELETE_TRIP, { deleting = true }, color = negative)
        }
    }
    if (deleting) CarDialog(LogCopy.DELETE_TITLE, { deleting = false }, icon = painterResource(R.drawable.ic_warning), lines = LogCopy.DELETE_LINES) {
        QuietAction(LogCopy.CANCEL) { deleting = false }
        QuietAction(LogCopy.DELETE, color = negative, description = LogCopy.SPOKEN_DELETE) {
            deleting = false
            close()
            act {
                repository.deleteTrip(trip.id)
                followUp(FollowUp("${kmText(trip.distanceKm)} 운행 기록을 삭제했어요", LogCopy.UNDO) { repository.restoreTrip(trip) })
            }
        }
    }
}

/** 출발 or 도착 in 운행 상세: the label (labelMedium textSecondary, 40 dp) beside the place (titleSmall, two lines: it is data), one stop. */
@Composable private fun DetailPlaceRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1, modifier = Modifier.width(40.dp).alignByBaseline())
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alignByBaseline())
    }
}

@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, suffix: String? = null,
    done: Boolean = false, hint: String? = null, error: String? = null) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The field sits on glass: the row fill when idle, glass when focused; the outline token for its border. Focused, the car
    // (2.13) rings it in the accent ink (4.93 / 7.66), the ink of its selection ring and links: focus is not a status, and the
    // teal glow beside the blue accent was a second selection colour. The phone keeps the glow.
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().heightIn(min = 68.dp), label = { Text(label) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
        suffix = suffix?.let { { Text(it) } }, shape = RoundedCornerShape(18.dp),
        supportingText = (error ?: hint)?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } }, isError = error != null,
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Scene.fill, focusedContainerColor = Scene.glass,
            unfocusedBorderColor = Scene.outline, focusedBorderColor = if (isCar()) Car.skin.accentInk else Scene.glow),
        keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Number else KeyboardType.Text, imeAction = if (done) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { keyboard?.hide(); focus.clearFocus() }))
}

/**
 * The driver picker (2.11) for the person driving now ([tripId] null) or for one trip: avatar cards that commit in one tap
 * with undo on the follow-up bar; [goSettings] is its 직원 추가·관리. Drawn by [DriverPickerDialog]; [now] dates the cards'
 * booking and last-drive lines.
 */
@Composable internal fun DriverPicker(data: TripLogData, tripId: String?, act: Act, repository: TripLogRepository, dismiss: () -> Unit,
    now: Long = System.currentTimeMillis(), goSettings: () -> Unit) = DriverPickerDialog(data, tripId, act, repository, dismiss, now, goSettings)

// ───────────────────────── 리포트 ─────────────────────────

/**
 * Two blocks of a page: side by side with [gap] between them when [sideBySide], one under the other otherwise. With
 * [equalHeight] (2.13, two cards side by side) the row is as tall as the taller block (`IntrinsicSize.Min`) and both blocks
 * are handed `fillMaxHeight`, so a pair of cards ends on one line where 2.12's ended ragged (v13: 284 and 213 dp). A block
 * stretched that way keeps its content at the top. Only for blocks that answer intrinsic measurements: nothing in them may be
 * a SubcomposeLayout, a BoxWithConstraints or a lazy list, which throw when a Row asks them for their minimum height.
 */
@Composable private fun SideBySide(sideBySide: Boolean, gap: Dp, first: @Composable (Modifier) -> Unit, equalHeight: Boolean = false,
    second: @Composable (Modifier) -> Unit) {
    if (sideBySide && equalHeight) Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
        first(Modifier.weight(1f).fillMaxHeight()); second(Modifier.weight(1f).fillMaxHeight())
    }
    else if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(gap)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    else Column(verticalArrangement = Arrangement.spacedBy(gap)) { first(Modifier); second(Modifier) }
}

/** What the sticky save bar keeps clear at the bottom of the 월간·Excel pane: the bar's 72 dp and the 16-dp gap above it. */
internal val SAVE_BAR_RESERVE = 72.dp + 16.dp

/**
 * The height of the range tags' row over a view (2.13): a [CarTag] is at least 32 dp, its labelMedium line (19 dp at 1×)
 * inside 5 dp of padding above and below, so a font past about 1.16× makes it taller.
 */
internal fun reportRangeRowHeight(fontScale: Float = 1f): Dp = maxOf(32.dp, 10.dp + 19.dp * fontScale.coerceAtLeast(1f))

/**
 * The height of the car's Excel preview card, folded (spec B; the preview sits in a card since 2.12): the pane's height less
 * the sticky save bar's room and the view tabs and range tags above the card, so a scroll to the end shows the tabs, the whole
 * card and the bar together. Never under [EXCEL_PREVIEW_MIN_HEIGHT] (scaled up with a large font, whose taller text the
 * preview's chrome needs) plus 80 dp for the card: its padding (2 × 24 dp from EXPANDED) and the 32 dp more that the ⓘ row's
 * 56-dp toggle takes than 2.11's one-line note. So a short pane still leaves the table about 2.11's room, a row with the pager
 * and the hint under it, and scrolls further instead of squeezing them. An unbounded pane gets the minimum. 2.13: the range
 * is a row of tags ([reportRangeRowHeight]) where 2.12 had a 16-dp text line, so the card is 16 dp shorter at 1×.
 */
internal fun excelPreviewHeight(pane: Dp, fontScale: Float = 1f): Dp {
    val scale = fontScale.coerceAtLeast(1f)
    val floor = EXCEL_PREVIEW_MIN_HEIGHT * scale + 80.dp
    if (!pane.value.isFinite()) return floor
    // The view tabs (56-dp segments in 5 dp of padding), a 16-dp gap, the range tags' row and the gap under it.
    val viewHeader = 66.dp + 16.dp + reportRangeRowHeight(scale) + 16.dp
    return (pane - SAVE_BAR_RESERVE - viewHeader).coerceAtLeast(floor)
}

/**
 * What the car's Excel preview card keeps under the preview (2.12): the card's 16-dp gap and the ⓘ row's 56-dp toggle, folded.
 * The caveat sits there, outside the preview, so opening it lengthens the card in the scroll instead of taking the table's
 * room or paging the table again.
 */
internal val PREVIEW_NOTE_RESERVE = 16.dp + 56.dp

/**
 * The preview's own height inside its card: the folded [card] height ([excelPreviewHeight]) less the card's [padding] above and
 * below and the ⓘ row ([PREVIEW_NOTE_RESERVE]). It does not change while the ⓘ is open, so the table keeps its page.
 */
internal fun excelPreviewInnerHeight(card: Dp, padding: Dp): Dp = card - padding * 2 - PREVIEW_NOTE_RESERVE

/**
 * The one period 월간·Excel shows: [month] under 월간, its year under 연간. Both units keep the same anchor [month]: from
 * 2026년 11월, 연간 then ‹ shows 2025년, and 월간 again shows 2025년 11월.
 */
@Immutable
internal data class ReportPeriod(val month: YearMonth, val yearly: Boolean)

/** What a car period stepper shows and TalkBack reads: the period's label and the names of its two arrows. */
@Immutable
internal data class StepperCopy(val label: String, val back: String, val forward: String)

/** The saved anchor month read back: a value that does not parse, or a month after this one (a clock set back), is this month. */
internal fun reportPeriod(saved: String, yearly: Boolean, today: LocalDate): ReportPeriod {
    val thisMonth = YearMonth.from(today)
    val month = runCatching { YearMonth.parse(saved) }.getOrNull()?.takeIf { it <= thisMonth } ?: thisMonth
    return ReportPeriod(month, yearly)
}

/**
 * The dates [period] covers: the 1st (1 January under 연간) to its last day, cut at [today] while it is still running, so the
 * start never passes the end. The same ranges as the assistant's 지난달 · 이번 달 · 올해 and Gleo's this_month · this_year.
 */
internal fun reportRange(period: ReportPeriod, today: LocalDate): Pair<LocalDate, LocalDate> {
    val month = minOf(period.month, YearMonth.from(today))
    val start = if (period.yearly) LocalDate.of(month.year, 1, 1) else month.atDay(1)
    val end = if (period.yearly) LocalDate.of(month.year, 12, 31) else month.atEndOfMonth()
    return start to if (end.isAfter(today)) today else end
}

/**
 * The range tag over 월간·Excel's views (2.13, pure, unit-tested): the dates [reportRange] gives and the [trips] in them, in
 * the words the stepper uses, "10월 1일 ~ 31일 · 9건" or "1월 1일 ~ 11월 12일 · 54건"; a range in another year than [today]'s
 * leads with its year (2025년 10월 1일 ~ 31일), a range of one day names it once. 2.12 printed the raw ISO dates on the page.
 */
internal fun reportRangeLabel(from: LocalDate, to: LocalDate, trips: Int, today: LocalDate): String {
    val start = (if (from.year != today.year) "${from.year}년 " else "") + "${from.monthValue}월 ${from.dayOfMonth}일"
    val end = when {
        to == from -> null
        to.year != from.year -> "${to.year}년 ${to.monthValue}월 ${to.dayOfMonth}일"
        to.monthValue != from.monthValue -> "${to.monthValue}월 ${to.dayOfMonth}일"
        else -> "${to.dayOfMonth}일"
    }
    return listOfNotNull(start, end).joinToString(" ~ ") + " · ${trips}건"
}

/** Whether › is live: a month before this one ([canGoForward], the phone's rule) under 월간, a year before this one under 연간. */
internal fun reportCanGoForward(period: ReportPeriod, today: LocalDate): Boolean =
    if (period.yearly) period.month.year < today.year else canGoForward(period.month, today)

/** One step: a month under 월간, twelve under 연간, never past this month (› is off there); no floor, as 운행기록 and the phone. */
internal fun reportStep(period: ReportPeriod, forward: Boolean, today: LocalDate): ReportPeriod {
    if (forward && !reportCanGoForward(period, today)) return period
    val months = if (period.yearly) 12L else 1L
    return period.copy(month = minOf(if (forward) period.month.plusMonths(months) else period.month.minusMonths(months), YearMonth.from(today)))
}

/** The stepper's label (2026년 10월 · 2026년) and its arrows' TalkBack names; a dead › says why, as the phone's month row does. */
internal fun reportStepperCopy(period: ReportPeriod, today: LocalDate): StepperCopy {
    val forward = reportCanGoForward(period, today)
    return if (period.yearly) StepperCopy("${period.month.year}년", "이전 해", if (forward) "다음 해" else "다음 해 · 아직 오지 않은 해")
    else StepperCopy("${period.month.year}년 ${period.month.monthValue}월", "이전 달", if (forward) "다음 달" else "다음 달 · 아직 오지 않은 달")
}

/**
 * The close card for the period on screen: none under 연간; this month always, so it can say what is still to come; a past
 * month only while it has something to show (trips, a card question or a close record).
 */
internal fun reportCloseStatus(data: TripLogData, period: ReportPeriod, today: LocalDate, zone: ZoneId): MonthStatus? {
    if (period.yearly) return null
    val status = monthStatus(data, period.month, zone)
    return status.takeIf { period.month >= YearMonth.from(today) || it.trips > 0 || it.reviews > 0 || it.closed != null }
}

/**
 * Where 정리하러 가기 opens 운행기록: the month of the earliest trip still without a purpose or a driver (oldest first, like
 * [monthCloseTarget]), else [fallback]. Under 월간 that is the month on screen; under 연간 the first month to tidy.
 */
internal fun tidyMonth(trips: List<Trip>, fallback: YearMonth, zone: ZoneId): YearMonth =
    trips.filter { it.purpose == TripPurpose.UNCLASSIFIED || it.driverId == null }.minByOrNull { it.startAt }
        ?.let { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) } ?: fallback

/** The workbook's file name: the plate (차량 when blank; anything but letters, digits and hyphens as _) and the exact range, 시연_ first in demo mode. */
internal fun logbookFileName(profile: FleetProfile, from: LocalDate, to: LocalDate): String {
    val plate = profile.plateNumber.ifBlank { "차량" }.replace(Regex("[^\\p{L}\\p{N}-]"), "_")
    return "${if (profile.demoMode) "시연_" else ""}운행기록부_${plate}_${from}_${to}.xlsx"
}

/**
 * The 검토 view's headline for a period with [trips] trips and [issues] audit findings. A period without trips (easy to reach
 * now that the pane steps back through months and years) has nothing to check, rather than "0 items to check".
 */
internal fun auditHeadline(trips: Int, issues: Int): String = when {
    trips == 0 -> "점검할 운행이 없어요"
    issues == 0 -> "기록 기본 점검 완료"
    else -> "확인이 필요한 항목 ${issues}개"
}

/** A year as the tax card names it: 올해 for this one, else 2025년 (as [monthLabel] names a month). */
private fun yearLabel(year: Int, today: LocalDate): String = if (year == today.year) "올해" else "${year}년"

/** The tax card's basis tag (2.12, beside its title 비용 참고): the year whose business ratio it applies, 올해 for this one. */
internal fun taxBasis(year: Int, today: LocalDate): String = "${yearLabel(year, today)} 업무사용비율 기준"

/**
 * What the tax card says instead of its figures, one line, or null when it can compute them. A missing annual cost comes
 * first, and only that state offers 설정에서 입력. With the cost set, a [year] without trips has no business ratio (the card
 * reads the year on screen, and the stepper has no floor) and no setting can give it one, so the card says that instead of
 * asking for the cost.
 */
internal fun taxCardBlocker(cost: Long?, ratio: Double?, year: Int, today: LocalDate): String? = when {
    cost == null -> "연간 차량 비용을 입력하면 계산해요"
    // Says why there is no figure and asks for nothing: no setting gives a year without trips a ratio.
    ratio == null -> "${yearLabel(year, today)} 운행이 없어 비율을 계산할 수 없어요"
    else -> null
}

/**
 * The tax card's last figure in words (pure, unit-tested): how far the ratio-applied amount stands above the no-logbook
 * comparison, or, where it does not, that it is the smaller one or that the two are equal. One line in titleSmall.
 */
internal fun taxDifferenceLine(differenceWon: Long, ratio: Double): String = when {
    differenceWon > 0 -> "두 계산의 차이 ${formatWon(differenceWon)}"
    differenceWon < 0 -> "비율 ${ratioText(ratio)} 적용액이 더 작아요"
    else -> "두 계산액이 같아요"
}

/**
 * The body of the dialog before an Excel save with untidy trips (pure, unit-tested): one line for each thing that will be
 * missing from the file, and only those that apply, unclassified distance first. The dialog joins them with line breaks.
 */
internal fun exportConfirmLines(unclassifiedKm: Double, driverless: Int): List<String> = listOfNotNull(
    "미분류 ${kmText(unclassifiedKm)}는 업무 거리에서 빠져요".takeIf { unclassifiedKm > 0 },
    "사용자 미지정 ${driverless}건은 ④란이 비어요".takeIf { driverless > 0 },
)

/** What the fuel settlement card says around its figures ([settlementCopy]). */
@Immutable
internal data class SettlementCopy(val tag: String?, val total: String, val empty: String)

/**
 * The settlement card's words for a [rateWon] per km and the [period] its totals cover (pure, unit-tested): the rate as a tag
 * beside the title while one is set ("km당 300원"), the total's label and the line for a period without business trips. 월간·Excel
 * has the period on screen and passes none; 설정 passes "이번 달", since nothing around its card says which month it sums.
 */
internal fun settlementCopy(rateWon: Long, period: String?): SettlementCopy = SettlementCopy(
    tag = if (rateWon > 0) "km당 ${formatWon(rateWon)}" else null,
    total = listOfNotNull(period, "정산 참고 합계").joinToString(" "),
    empty = "${period ?: "이 기간"} 업무용 운행이 없어요",
)

/**
 * The Excel view's save bar (72 dp, as wide as the preview above it), pinned to the bottom of the pane (2.12): the card colour
 * with its hairline, so it reads as a card over whatever scrolls under it; the phone mark, one [line] of context and the
 * save [action].
 */
@Composable private fun SaveBar(line: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    val skin = Car.skin
    Surface(modifier.widthIn(max = EXCEL_VIEW_MAX_WIDTH).fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = skin.card, contentColor = Scene.text,
        border = BorderStroke(1.dp, skin.cardBorder)) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 22.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_phone), contentDescription = null, Modifier.size(22.dp), tint = skin.icon)
            Spacer(Modifier.width(12.dp))
            CarLine(line, Modifier.weight(1f))
            Spacer(Modifier.width(16.dp))
            action()
        }
    }
}

/**
 * 월간·Excel, one vertical scroll over one period on screen ([ReportPeriod]: a month under 월간, a year under 연간), stepped
 * like 운행기록 and never past this month. From EXPANDED up the stepper, 월간 · 연간 (320 wide) and Excel 저장 share one
 * row; below that the stepper has its own row over the other two (480 wide). Then the month-close card of the month on
 * screen ([reportCloseStatus], [ReportCloseCard]) across the page, the four views as 56-dp tabs across the page (2.12, as
 * the mock's sub-tabs), the range as a tag ([reportRangeLabel]) and the view. The label, the card, the views, the preview, every Excel 저장 and its
 * file name, and 정리하러 가기 all read the one range [reportRange] gives. The pane opens on [initialMonth] when a button
 * hands it one, else where the home card, the banner and the tab badge point ([reportLandingMonth]); after that only the
 * driver moves it. The Excel view fills the page like the month-close card: the preview in a card of its own, folded at
 * [excelPreviewHeight] (560 at least), with its lighter sheet tabs, its table paging to the rows that fit and its caveat as an
 * ⓘ row under it, which lengthens the card when opened instead of squeezing the table; and the save bar (72 dp) pinned to
 * the bottom of the pane while the rest scrolls under it; [onSaveBar] tells the shell while the bar is pinned, so
 * its follow-up bar rides above it. The two cards of the other views (검토's too) sit side by side from EXPANDED up, ending on
 * one line (2.13), and stack below it. Every Excel 저장 follows one rule ([excelSaveAvailable]); before one with untidy trips
 * the car's dialog lists what the file will miss ([exportConfirmLines]).
 */
@Composable private fun ReportPane(state: TripLogState, now: Long, exporting: Boolean, saveFile: (String, String, ByteArray) -> Unit,
    act: Act, repository: TripLogRepository, goSettings: () -> Unit, goHome: () -> Unit, initialMonth: String? = null, onSaveBar: (Boolean) -> Unit = {},
    goReview: (YearMonth) -> Unit) {
    val layout = LocalLayout.current
    val wide = layout.expanded
    val data = state.data
    val today = localDate(now)
    // One anchor month for both units (0 월간, 1 연간), resolved once: a close or a reopen changes the card in place, and the
    // month moves only when the driver steps.
    var period by rememberSaveable { mutableIntStateOf(0) }
    var month by rememberSaveable { mutableStateOf(initialMonth?.takeIf { runCatching { YearMonth.parse(it) }.isSuccess }
        ?: reportLandingMonth(data, today, zone).toString()) }
    var view by rememberSaveable { mutableIntStateOf(2) }
    var confirmExport by remember { mutableStateOf(false) }
    // Read back clamped to this month, so a clock set back never shows a period that has not begun.
    val shown = reportPeriod(month, period == 1, today)
    val step: (Boolean) -> Unit = { forward -> month = reportStep(shown, forward, today).month.toString() }
    val (from, to) = reportRange(shown, today)
    // The clock recomposes the pane every second; the period's trips and figures only change with the data and the range.
    val trips = remember(data.trips, from, to) { tripsIn(data.trips, from, to, zone) }
    val totals = remember(trips) { logbookTotals(trips) }
    // The tax card reads the year of the period on screen.
    val (yearFrom, yearTo) = reportRange(shown.copy(yearly = true), today)
    val yearTotals = remember(data.trips, yearFrom, yearTo) { logbookTotals(tripsIn(data.trips, yearFrom, yearTo, zone)) }
    val exportRange: (LocalDate, LocalDate) -> Unit = { start, end ->
        val bytes = logbookXlsx(data.copy(profile = data.profile.copy(carModel = data.profile.carModel.ifBlank { state.vehicleModel.orEmpty() })), start, end,
            tripsIn(data.trips, start, end, zone), zone = zone)
        saveFile(logbookFileName(data.profile, start, end), XLSX_MIME, bytes)
    }
    val export: () -> Unit = { exportRange(from, to) }
    // The row's Excel 저장, the card's ③ and the closed card's Excel 저장 all save the range on screen, behind one check.
    val requestExport: () -> Unit = { if (totals.unclassifiedKm > 0 || trips.any { it.driverId == null }) confirmExport = true else export() }
    // The same rule as the month-close card's ③: a range without trips has nothing to save, and TalkBack hears why.
    val canSave = excelSaveAvailable(trips.size)
    // The close card follows the month on screen: none under 연간, and a past month only with something to show.
    val closeStatus = remember(data, shown, today) { reportCloseStatus(data, shown, today, zone) }
    val ratio: @Composable (Modifier) -> Unit = { modifier -> RatioCard(totals, modifier, compact = !wide) }
    val tax: @Composable (Modifier) -> Unit = { modifier ->
        TaxCard(data.profile, yearTotals, taxBasis(shown.month.year, today), taxCardBlocker(data.profile.annualCostWon, yearTotals.ratio, shown.month.year, today), modifier, goSettings)
    }
    val settle: @Composable (Modifier) -> Unit = { modifier -> SettlementCard(trips, data, modifier, goSettings) }
    // The header's three parts: the stepper (the same one as 운행기록), 월간 · 연간 and Excel 저장, placed per window below.
    val stepper: @Composable RowScope.() -> Unit = { PeriodStepper(reportStepperCopy(shown, today), reportCanGoForward(shown, today), { step(false) }) { step(true) } }
    val periodUnit: @Composable (Modifier) -> Unit = { modifier -> Segmented(listOf("월간", "연간"), period, modifier) { period = it } }
    val save: @Composable (Modifier) -> Unit = { modifier ->
        LoadingAction("Excel 저장", loading = exporting, modifier = modifier.semantics { if (!canSave) stateDescription = ReportCopy.NOTHING_TO_SAVE }, loadingLabel = "만드는 중…",
            enabled = canSave, icon = painterResource(R.drawable.ic_export), onClick = requestExport)
    }
    val excel = view == 2
    // While the save bar is pinned the shell lifts its follow-up bar over it; a pane that goes takes the bar with it.
    val reportSaveBar by rememberUpdatedState(onSaveBar)
    DisposableEffect(excel) { reportSaveBar(excel); onDispose { reportSaveBar(false) } }
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val previewHeight = excelPreviewHeight(maxHeight, fontScale)
        // One scroll for the whole pane; on the Excel view its end keeps clear of the pinned save bar.
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().fadingTopEdge(scroll).verticalScroll(scroll).padding(bottom = if (excel) SAVE_BAR_RESERVE else 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // The header and the month-close card are one block. The header never moves: a step or a unit switch updates it in
            // place. The card follows the period (none under 연간), and the block reflows over 260 ms when the card comes, goes
            // or changes height (a snap under reduced motion); an absent card costs no gap.
            Column(Modifier.fillMaxWidth().animateContentSize(enterSpec(260)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (wide) Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    stepper()
                    // 월간 · 연간 takes what the stepper leaves, up to 320; Excel 저장 sits at the column's right.
                    Box(Modifier.weight(1f)) { periodUnit(Modifier.widthIn(max = 320.dp)) }
                    save(Modifier)
                } else {
                    // The stepper on its own row at the start, as in 운행기록; then 월간 · 연간 (480 at most) and Excel 저장 at the right.
                    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        stepper()
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        periodUnit(Modifier.weight(1f, fill = false).widthIn(max = 480.dp))
                        save(Modifier.padding(start = 16.dp))
                    }
                }
                // One card instance per month: its bar lands at that month's completion, and a close in progress never carries over.
                closeStatus?.let { s -> key(s.month) {
                    val edited = remember(data) { editedAfterCloseCount(data, s.month, zone) }
                    ReportCloseCard(s, onIssues = { goReview(s.month) }, onReviews = goHome, onExport = requestExport, exporting = exporting, act = act, repository = repository,
                        editedAfterClose = edited, today = today, onPrevious = { month = s.month.minusMonths(1).toString() })
                } }
            }
            // The views fill the page, as the mock's sub-tabs do.
            Segmented(listOf("운행 현황", "경비·정산", "Excel", "검토"), view, Modifier.fillMaxWidth()) { view = it }
            // The range on screen as a tag in the stepper's words (2.13; 2.12 printed the raw ISO dates on the bare page), and
            // 시연 데이터 beside it in demo mode. One row, as tall as excelPreviewHeight counts it.
            Row(Modifier.heightIn(min = reportRangeRowHeight(fontScale)), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CarTag(reportRangeLabel(from, to, totals.trips, today))
                if (data.profile.demoMode) CarTag(ReportCopy.RANGE_DEMO, tone = PillTone.DEMO)
            }
            Crossfade(view, Modifier.fillMaxWidth(), animationSpec = pageFade(), label = "report-section") { page ->
                // The preview has a height of its own in the scroll: folded, its card is that tall, the preview takes what the
                // card's padding and the ⓘ row leave, and its table pages to the rows that fit. Opened, the ⓘ lengthens the card
                // instead, so the table never loses its pager or jumps to another page.
                if (page == 2) CarCard(Modifier.widthIn(max = EXCEL_VIEW_MAX_WIDTH).fillMaxWidth()) {
                    ExcelPreview(data.copy(profile = data.profile.copy(carModel = data.profile.carModel.ifBlank { state.vehicleModel.orEmpty() })), trips, from, to,
                        Modifier.fillMaxWidth().height(excelPreviewInnerHeight(previewHeight, carCardPadding())), compact = !wide, fillHeight = true)
                    CarInfoRow(ReportCopy.PREVIEW_NOTE, more = ReportCopy.PREVIEW_NOTE_MORE)
                }
                else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                    // Side by side, each pair ends on one line (2.13). None of these cards holds a BoxWithConstraints, a
                    // SubcomposeLayout or a lazy list, so the row may ask them for their intrinsic height.
                    when (page) {
                        0 -> SideBySide(wide, layout.cardGap, equalHeight = true, first = ratio) { modifier -> EmployeeUsageCard(trips, data, modifier) }
                        1 -> SideBySide(wide, layout.cardGap, equalHeight = true, first = { modifier -> ExpenseOverview(trips, modifier) }, second = settle)
                        else -> {
                            // The two cards share the page from EXPANDED up; stacked, each keeps to a 760-dp measure.
                            val measure = if (wide) Modifier else Modifier.widthIn(max = 760.dp)
                            SideBySide(wide, layout.cardGap, equalHeight = true, first = { modifier -> AuditCard(trips, modifier.then(measure)) }) { modifier ->
                                tax(modifier.then(measure))
                            }
                        }
                    }
                }
            }
        }
        // The Excel view's own action stays in reach wherever the pane is scrolled: pinned to its bottom while that view is
        // chosen, fading with the view change (a snap under reduced motion).
        AnimatedVisibility(excel, Modifier.align(Alignment.BottomStart), enter = fadeIn(pageFade()), exit = fadeOut(pageFade()), label = "save-bar") {
            // The phone file carries the whole log, whatever period is on screen; it is the page's second save, so it stays outlined.
            val phoneFile = data.trips.isNotEmpty()
            SaveBar(ReportCopy.SAVE_BAR_LINE) {
                LoadingAction("휴대폰용 운행파일 저장", loading = exporting, modifier = Modifier.semantics { if (!phoneFile) stateDescription = ReportCopy.NOTHING_TO_SAVE },
                    loadingLabel = "만드는 중…", enabled = phoneFile, quiet = true) {
                    saveFile("SHAREMATE_운행기록.smlog", LOGBOOK_MIME, TripLogCodec.encode(data.copy(open = null)))
                }
            }
        }
    }
    // The car's own dialog (2.13): one line per thing the file will miss, then decide, the way to tidy first and the save
    // last, at the right. Back and a tap beside the card leave without either.
    if (confirmExport) CarDialog(ReportCopy.CONFIRM_TITLE, onDismiss = { confirmExport = false }, icon = painterResource(R.drawable.ic_export),
        lines = exportConfirmLines(totals.unclassifiedKm, trips.count { it.driverId == null })) {
        QuietAction(ReportCopy.CONFIRM_TIDY) { confirmExport = false; goReview(tidyMonth(trips, shown.month, zone)) }
        PrimaryAction(ReportCopy.CONFIRM_SAVE) { confirmExport = false; export() }
    }
}

/**
 * 검토's first card: how ready the period's evidence is ([auditHeadline]), where the details are, and, behind ⓘ, what the check
 * covers and what it does not.
 */
@Composable private fun AuditCard(trips: List<Trip>, modifier: Modifier) {
    CarCard(modifier, title = { CarSectionTitle(ReportCopy.AUDIT_TITLE, icon = painterResource(R.drawable.ic_journal)) }) {
        val issues = remember(trips) { auditTrips(trips) }
        Text(auditHeadline(trips.size, issues.size), style = MaterialTheme.typography.titleLarge, color = Scene.text)
        CarLine(ReportCopy.AUDIT_LINE)
        CarInfoRow(ReportCopy.AUDIT_NOTE, more = ReportCopy.AUDIT_NOTE_MORE)
    }
}

/**
 * 운행 현황's second card (2.12): each employee's trips in the period as rows (the count and the business distance under the
 * name, the total at the end), unassigned trips as 사용자 미지정 (2.13: the one word for a trip without its person, the issue's
 * own label, where 2.12 said 직원 미지정 here); where a past trip's employee is fixed sits behind ⓘ.
 */
@Composable private fun EmployeeUsageCard(trips: List<Trip>, data: TripLogData, modifier: Modifier) {
    CarCard(modifier, title = { CarSectionTitle(ReportCopy.USAGE_TITLE, icon = rememberVectorPainter(Icons.Outlined.Person)) }) {
        val rows = employeeUsage(trips, data.drivers, data.profile.reimbursePerKmWon)
        if (rows.isEmpty()) CarLine(ReportCopy.USAGE_EMPTY)
        else Column {
            rows.forEachIndexed { index, row ->
                if (index > 0) CarDivider()
                CarRow(row.driver?.label ?: IssueType.NO_DRIVER.label, line = "${row.totals.trips}건 · 업무 ${kmText(row.totals.businessKm)}", trailing = {
                    Text(kmText(row.totals.totalKm), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1)
                })
            }
        }
        CarInfoRow(ReportCopy.USAGE_NOTE)
    }
}

/**
 * The registered expenses of [trips] by type, in [ExpenseType]'s order, without the types that have none (pure, unit-tested):
 * a list of money leaves its zero rows out (DESIGN_2_13_SPEC §5).
 */
internal fun expenseTypeSums(trips: List<Trip>): List<Pair<ExpenseType, Long>> {
    val expenses = trips.flatMap { it.expenses }
    return ExpenseType.entries.map { type -> type to expenses.filter { it.type == type }.sumOf { it.amountWon } }.filter { it.second > 0 }
}

/**
 * 경비·정산's first card (2.12): the registered expenses of the period; what they are not sits behind ⓘ. 2.13 sets it as a
 * list of money in the one style: a row per type that has any ([expenseTypeSums]), a hairline, then the total row (경비 합계,
 * TOTAL size on the same right edge). 2.12 led with the total and listed all five types, zeros in full ink and digits
 * proportional; a period without expenses now says so in one line and draws no 0원 total.
 */
@Composable private fun ExpenseOverview(trips: List<Trip>, modifier: Modifier) {
    CarCard(modifier, title = { CarSectionTitle(ReportCopy.EXPENSE_TITLE, icon = painterResource(R.drawable.ic_receipt)) }) {
        val sums = remember(trips) { expenseTypeSums(trips) }
        if (sums.isEmpty()) CarLine(ReportCopy.EXPENSE_EMPTY)
        else Column {
            sums.forEach { (type, won) -> CarMoneyRow(type.label, won) }
            CarDivider()
            CarMoneyRow(ReportCopy.EXPENSE_TOTAL, sums.sumOf { it.second }, total = true)
        }
        CarInfoRow(ReportCopy.EXPENSE_NOTE, more = ReportCopy.EXPENSE_NOTE_MORE)
    }
}

/**
 * 운행 현황's first card (2.12): the business ratio as the ring, without its caption (the title says it; TalkBack still hears
 * "업무사용비율 80%"), the form's ⑬ = ⑫ ÷ ⑪ as a tag that reads as words, the four kinds of distance as bars and ⑪ under them.
 */
@Composable private fun RatioCard(totals: LogbookTotals, modifier: Modifier, compact: Boolean = false) {
    val pleos = LocalPleosColors.current
    // The bar tracks share the ring's track token so they read on the card by day as well as by night.
    val track = Scene.ringTrack
    CarCard(modifier.fillMaxHeight(), title = {
        CarSectionTitle(ReportCopy.RATIO_TITLE, icon = painterResource(R.drawable.ic_report), trailing = {
            CarTag(ReportCopy.RATIO_FORMULA, description = ReportCopy.SPOKEN_RATIO_FORMULA)
        })
    }) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            RatioRing(totals.ratio, if (compact) 148.dp else 220.dp, ReportCopy.RATIO_TITLE, showCaption = false)
        }
        val rows = listOf(
            Triple("출·퇴근 ⑧", totals.commuteKm, pleos.positive), Triple("일반 업무 ⑨", totals.generalKm, pleos.chartActive),
            Triple("개인", totals.personalKm, pleos.textTertiary), Triple("미분류", totals.unclassifiedKm, pleos.negative))
        rows.forEach { (label, km, color) ->
            Column(Modifier.semantics(mergeDescendants = true) {
                contentDescription = "$label ${kmText(km)}" + if (totals.totalKm > 0) ", ${(km / totals.totalKm * 100).roundToInt()}퍼센트" else ""
            }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row { Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f)); Text(kmText(km), style = MaterialTheme.typography.labelLarge) }
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(track)) {
                    if (totals.totalKm > 0 && km > 0) Box(Modifier.fillMaxWidth((km / totals.totalKm).toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(color))
                }
            }
        }
        Stat("총주행 거리 ⑪", kmText(totals.totalKm))
    }
}

/**
 * The annual cost reference at the business ratio of [year], the totals of the year on screen, whose year the [basis] tag
 * names ([taxBasis]). While it cannot compute, [blocker] ([taxCardBlocker]) says why in one line: a missing cost comes with
 * why it matters (behind ⓘ) and the way to enter it; a year without trips sits above the cost already entered (a money row),
 * with no 설정 action, since the form already holds the value. Computed (2.13, a list of money in the one style): the cost and
 * the no-logbook comparison as rows, a hairline, then the ratio-applied amount as the total row with [taxDifferenceLine]
 * under its label. That amount is the card's one tone: positive while it exceeds the no-logbook figure, the case the logbook
 * pays for. The card's ₩ mark is its title's alone (2.12 drew three, and the result in Material's onTertiaryContainer).
 */
@Composable private fun TaxCard(profile: FleetProfile, year: LogbookTotals, basis: String, blocker: String?, modifier: Modifier, goSettings: () -> Unit) {
    CarCard(modifier.fillMaxHeight(), title = { CarSectionTitle(ReportCopy.TAX_TITLE, icon = painterResource(R.drawable.ic_won), trailing = { CarTag(basis) }) }) {
        val cost = profile.annualCostWon
        val ratio = year.ratio
        if (blocker != null) {
            Text(blocker, style = MaterialTheme.typography.titleSmall, color = Scene.text)
            if (cost == null) {
                CarInfoRow(ReportCopy.TAX_CAP_NOTE, more = ReportCopy.TAX_CAP_MORE)
                QuietAction("설정에서 입력", onClick = goSettings)
            } else CarMoneyRow(ReportCopy.TAX_COST, cost)
        } else if (cost != null && ratio != null) {
            val effect = taxEffect(cost, ratio)
            Column {
                CarMoneyRow(ReportCopy.TAX_COST, cost)
                CarMoneyRow(ReportCopy.TAX_WITHOUT_LOG, effect.withoutLogWon)
                CarDivider()
                CarMoneyRow(ReportCopy.TAX_WITH_LOG, effect.withLogWon, line = taxDifferenceLine(effect.differenceWon, ratio), total = true,
                    tone = if (effect.differenceWon > 0) MoneyTone.POSITIVE else MoneyTone.NEUTRAL)
            }
        }
        CarInfoRow(ReportCopy.TAX_NOTE, more = ReportCopy.TAX_NOTE_MORE)
    }
}

/**
 * The fuel settlement of [trips] at the saved km rate, per employee (2.12): the rate as a tag beside the title, a row per
 * employee with business trips (the business distance under the name, the amount at the end), then the total. Without a rate
 * it asks for one, with 설정에서 입력 where [goSettings] is given; 설정 › 정산·세무 shows it beside the form that sets the
 * rate and passes none. [period] names the span the totals cover where the page around the card does not (설정 passes
 * "이번 달"); the words come from [settlementCopy]. 2.13 sets it as a list of money in the one style: the rows, then a
 * hairline and the total row only while there are rows, so a period without business trips says so in one line where 2.12
 * drew a 30-sp 0원 under it (v13).
 */
@Composable private fun SettlementCard(trips: List<Trip>, data: TripLogData, modifier: Modifier, goSettings: (() -> Unit)? = null, period: String? = null) {
    val rate = data.profile.reimbursePerKmWon
    val copy = settlementCopy(rate, period)
    val tag = copy.tag
    CarCard(modifier.fillMaxHeight(), title = {
        CarSectionTitle(ReportCopy.SETTLEMENT_TITLE, icon = painterResource(R.drawable.ic_won), trailing = if (tag != null) { { CarTag(tag) } } else null)
    }) {
        if (rate <= 0) {
            Text(ReportCopy.SETTLEMENT_ASK, style = MaterialTheme.typography.titleSmall, color = Scene.text)
            goSettings?.let { QuietAction("설정에서 입력", onClick = it) }
        } else {
            val list = employeeUsage(trips, data.drivers, rate).filter { it.driver != null && it.totals.businessKm > 0 }
            if (list.isEmpty()) CarLine(copy.empty)
            else Column {
                list.take(6).forEach { item -> CarMoneyRow(item.driver!!.label, item.reimbursementWon, line = kmText(item.totals.businessKm)) }
                CarDivider()
                CarMoneyRow(copy.total, list.sumOf { it.reimbursementWon }, total = true)
            }
            CarInfoRow(ReportCopy.SETTLEMENT_NOTE, more = ReportCopy.SETTLEMENT_NOTE_MORE)
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

/** How many field columns a settings form shows: two from EXPANDED up at the normal font, one below that and always one at a large font (1.3×). */
internal fun settingsFieldColumns(layout: LayoutTokens, fontScale: Float): Int = if (layout.expanded && fontScale <= 1.25f) 2 else 1

/**
 * How many cards a 설정 section sets side by side (pure, unit-tested): two from EXPANDED up while the [page] keeps 760 dp of
 * effective width (its width ÷ the font scale) and is at least 984 dp wide, so 2 × 544 at FULL (a line beside a switch gets
 * 368 dp) and still two at a 1.3× font (1046 ÷ 1.3 = 805; 2 × 513); one below that, in the 720-dp form column. The 984-dp
 * floor (2.12) sends the 1296–1400-dp windows beside the 지금 column (pages of 780–884 dp) to one column, where two up would
 * leave a line beside a switch less than about 306 dp.
 */
internal fun settingsCardColumns(layout: LayoutTokens, page: Dp, fontScale: Float): Int =
    if (layout.expanded && page / fontScale.coerceAtLeast(1f) >= 760.dp && page >= 984.dp) 2 else 1

/**
 * 설정 (2.12): the four sub-tabs as the car's segmented bar across the whole page, as in the mock, over the section, which
 * scrolls under them. Every card is a [CarCard]. Where the page has room ([settingsCardColumns]) a section's cards sit two
 * up, each with its fields in one column and each pair ending on one line (2.13): the profile beside 차량 관리 기한, 정산·세무
 * beside this month's settlement at the saved rate, 화면 밝기 above 기록 및 개인정보 beside 자동 완성 above 운행 비서; 직원
 * takes the whole page. Cards stand 16 dp apart in either arrangement (2.13). Otherwise one form
 * column at the start of the page (max 720), in the same order, with fields paired from EXPANDED at the normal font
 * ([settingsFieldColumns]). The PLEOS SDK card of 2.11 is gone: its facts are an ⓘ row of 기록 및 개인정보. The shell holds the
 * [section], so a button that opens 설정 on one lands there and the header's tab returns to the last one.
 */
@Composable private fun SettingsPane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, section: Int, onSection: (Int) -> Unit,
    onTutorial: () -> Unit = {}, onAssistant: (String?) -> Unit = {}, showInfo: (String) -> Unit) {
    val layout = LocalLayout.current
    val fontScale = LocalDensity.current.fontScale
    val month = YearMonth.from(localDate(now))
    // The clock recomposes the pane every second; this month's trips only change with the data and the month.
    val monthTrips = remember(state.data.trips, month) { tripsIn(state.data.trips, month.atDay(1), month.atEndOfMonth(), zone) }
    val sections = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val twoUp = settingsCardColumns(layout, maxWidth, fontScale) == 2
        // Beside another card a form keeps one field column; alone in the form column it pairs its fields where it can.
        val pairFields = settingsFieldColumns(layout, fontScale) == 2
        val twoColumns = !twoUp && pairFields
        // One card rhythm (2.13): 16 between cards, two up or one up (2.12 set 20 in the form column).
        val gap = if (twoUp) layout.cardGap else 16.dp
        // Two up, every pair ends on one line (2.13, [SideBySide]'s equalHeight). The cards hold text fields, switches, flow rows
        // and ⓘ rows, none of them a BoxWithConstraints, a SubcomposeLayout or a lazy list, so the row may ask their intrinsic
        // height. Where a side is two cards, the lower one takes what the row leaves, so both sides end together.
        val profile: @Composable () -> Unit = {
            SideBySide(twoUp, gap, equalHeight = true, first = { modifier -> ProfileCard(state, act, repository, showInfo, modifier, twoColumns) }) { modifier ->
                CareSettingsCard(state, act, repository, modifier = modifier, columns = if (twoColumns) 2 else 1)
            }
        }
        val drivers: @Composable () -> Unit = { DriversCard(state.data, act, repository, pairFields) }
        val costs: @Composable () -> Unit = {
            SideBySide(twoUp, gap, equalHeight = true, first = { modifier -> CostCard(state.data.profile, act, repository, showInfo, modifier, twoColumns) }) { modifier ->
                SettlementCard(monthTrips, state.data, modifier, period = SettingsCopy.SETTLEMENT_PERIOD)
            }
        }
        // Stacked (one up), a Column gives no height to share, so the lower card simply wraps its content.
        val fill: ColumnScope.() -> Modifier = { if (twoUp) Modifier.weight(1f) else Modifier }
        val privacy: @Composable () -> Unit = {
            SideBySide(twoUp, gap, equalHeight = true, first = { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                    // The scene choice leads the section: it is the one setting a driver changes on the spot.
                    CarSceneCard()
                    PrivacyCard(state.data.profile, act, repository, fill(), onTutorial)
                }
            }) { modifier ->
                Column(modifier, verticalArrangement = Arrangement.spacedBy(gap)) {
                    AutopilotSettings(state.data.profile, act, repository) { value, change -> Field("법인카드 끝 4자리 (쉼표로 구분)", value, change, number = true, done = true, hint = "예: 1234, 5678") }
                    AssistantPreviewCard(compact = !layout.expanded, onOpen = { onAssistant(null) }, modifier = fill(), onOpenWith = { phrase -> onAssistant(phrase) })
                }
            }
        }
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Segmented(SettingsCopy.SECTIONS, section, Modifier.fillMaxWidth()) { onSection(it) }
            Crossfade(section, Modifier.weight(1f).fillMaxWidth(), animationSpec = pageFade(), label = "settings-section") { page ->
                sections.SaveableStateProvider(page) {
                    // Two up, the cards share the page; one up, a form column at the start of the page, 720 at most. The section
                    // scrolls, the sub-tabs stay.
                    val scroll = rememberScrollState()
                    Column(Modifier.fillMaxSize().fadingTopEdge(scroll).verticalScroll(scroll)) {
                        Box((if (twoUp) Modifier.fillMaxWidth() else Modifier.widthIn(max = 720.dp).fillMaxWidth()).padding(bottom = 8.dp)) {
                            when (page) { 0 -> profile(); 1 -> drivers(); 2 -> costs(); else -> privacy() }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 법인·차량 정보 (2.12): the company and the car as the logbook's header cells carry them, saved together. One line says where
 * they go; 차량에서 가져오기 offers the model the car reports when it differs from the typed one.
 */
@Composable private fun ProfileCard(state: TripLogState, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit, modifier: Modifier = Modifier,
    twoColumns: Boolean = false) {
    val saved = state.data.profile
    var company by rememberSaveable(saved.companyName) { mutableStateOf(saved.companyName) }
    var number by rememberSaveable(saved.businessNumber) { mutableStateOf(saved.businessNumber) }
    var model by rememberSaveable(saved.carModel) { mutableStateOf(saved.carModel) }
    var plate by rememberSaveable(saved.plateNumber) { mutableStateOf(saved.plateNumber) }
    val numberError = if (number.isNotBlank() && !BUSINESS_NUMBER.matches(number.trim())) "000-00-00000 형식으로 입력해 주세요" else null
    val vehicleModel = state.vehicleModel?.takeIf { it.isNotBlank() && it != "시연 차량" }
    CarCard(modifier, title = { CarSectionTitle(SettingsCopy.PROFILE_TITLE, icon = rememberVectorPainter(Icons.Outlined.AccountBox)) }) {
        CarLine(SettingsCopy.PROFILE_LINE)
        // 법인명 and 사업자등록번호 pair up only when the form has two columns ([settingsFieldColumns]).
        SideBySide(twoColumns, 16.dp, { field -> Field("법인명", company, { if (it.length <= 60) company = it }, field) }) { field ->
            Field("사업자등록번호", number, { value -> if (value.length <= 12 && value.all { it.isDigit() || it == '-' }) number = value }, field,
                hint = "예: 123-45-67890", error = numberError)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("① 차종", model, { if (it.length <= 60) model = it }, Modifier.weight(1f))
            if (vehicleModel != null && vehicleModel != model) QuietAction("차량에서 가져오기") { model = vehicleModel }
        }
        Field("② 자동차등록번호", plate, { if (it.length <= 20) plate = it }, done = true, hint = "예: 123가 4567")
        val changed = company != saved.companyName || number != saved.businessNumber || model != saved.carModel || plate != saved.plateNumber
        PrimaryAction(if (changed) SettingsCopy.PROFILE_SAVE else SettingsCopy.PROFILE_SAVED, Modifier.fillMaxWidth(), enabled = changed && numberError == null) {
            act {
                repository.updateProfile(saved.copy(companyName = company.trim(), businessNumber = number.trim(), carModel = model.trim(), plateNumber = plate.trim()))
                showInfo(SettingsCopy.PROFILE_DONE)
            }
        }
    }
}

@Composable private fun DriversCard(data: TripLogData, act: Act, repository: TripLogRepository, twoColumns: Boolean = false) {
    // 설정 › 직원 (2.11): the list and its editor, side by side once the card is 960 dp wide; see DriverManager.
    DriverManager(data, act, repository, twoColumns = twoColumns)
}

/**
 * 정산·세무 기준 (2.12): the km rate the settlement applies and the car's yearly cost the tax note compares. The yearly cost's
 * hint says what the sum covers while the field is empty, where 2.11 had a paragraph under the form. 2.13: both are money
 * fields ([CarMoneyField]): the digits grouped as typed with 원 straight after them, the range said in the field's own
 * error ([moneyInput]: 10만 원 and 100억 원 at most) and the yearly cost read back from 1만 up ("3,200만 원"), where 2.12
 * echoed "32,000,000원" under "32000000" and put a loose red sentence under the form. 기준 저장 needs both fields in range;
 * the repository's own check stays as the backstop.
 */
@Composable private fun CostCard(saved: FleetProfile, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit, modifier: Modifier = Modifier,
    twoColumns: Boolean = false) {
    var rate by rememberSaveable(saved.reimbursePerKmWon) { mutableStateOf(saved.reimbursePerKmWon.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by rememberSaveable(saved.annualCostWon) { mutableStateOf(saved.annualCostWon?.toString().orEmpty()) }
    val rateInput = moneyInput(rate, min = 0, max = 100_000, hint = "회사에서 정한 km당 단가")
    val costInput = moneyInput(cost, min = 0, max = 10_000_000_000, hint = SettingsCopy.COST_HINT, reading = true)
    val updated = saved.copy(reimbursePerKmWon = rateInput.won ?: 0, annualCostWon = costInput.won)
    CarCard(modifier, title = { CarSectionTitle(SettingsCopy.COST_TITLE, icon = painterResource(R.drawable.ic_tune)) }) {
        SideBySide(twoColumns, 16.dp, first = { field -> CarMoneyField("유류비 정산 단가 (km당)", rate, { rate = it }, rateInput, field, maxDigits = 6) }) { field ->
            CarMoneyField("연간 차량 관련 비용", cost, { cost = it }, costInput, field, maxDigits = 11, done = true)
        }
        val changed = updated.reimbursePerKmWon != saved.reimbursePerKmWon || updated.annualCostWon != saved.annualCostWon
        PrimaryAction(if (changed) SettingsCopy.COST_SAVE else SettingsCopy.COST_SAVED, Modifier.fillMaxWidth(), enabled = changed && rateInput.ok && costInput.ok) {
            act {
                repository.updateProfile(updated)
                showInfo(SettingsCopy.COST_DONE)
            }
        }
    }
}

/**
 * 기록 및 개인정보 (2.12, as in the mock): 시연 체험 다시보기 as a link in the title row, then the four switches, each on its icon
 * disc with one line (TalkBack hears the long form) and a hairline above it, and two ⓘ rows: where the records live, and which
 * car signals are read (the facts of 2.11's PLEOS SDK card). Turning 시연 모드 off also stops the demo drive. [modifier]
 * (2.13) lets 설정 stretch the card to end on one line with the cards beside it.
 */
@Composable private fun PrivacyCard(saved: FleetProfile, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier, onTutorial: () -> Unit = {}) {
    // The rows carry their own 8-dp padding and a hairline between them, so the card's blocks sit closer than its default 16.
    CarCard(modifier, gap = 4.dp, title = {
        CarSectionTitle(SettingsCopy.PRIVACY_TITLE, icon = painterResource(R.drawable.ic_journal)) { CarLink(SettingsCopy.TUTORIAL_LINK, onClick = onTutorial) }
    }) {
        CarDivider()
        SwitchRow(SettingsCopy.ADDRESS_TITLE, SettingsCopy.ADDRESS_LINE, saved.storeAddresses, icon = rememberVectorPainter(Icons.Outlined.LocationOn),
            spoken = SettingsCopy.SPOKEN_ADDRESS) { value ->
            act { repository.updateProfile(saved.copy(storeAddresses = value)) }
        }
        // 출발 전 운전자 확인 lives with the people it asks about, in 설정 › 직원 (DriverManager), since 2.11.
        CarDivider()
        SwitchRow(SettingsCopy.AUTOPILOT_TITLE, SettingsCopy.AUTOPILOT_LINE, saved.autopilot, icon = painterResource(R.drawable.ic_bolt),
            spoken = SettingsCopy.SPOKEN_AUTOPILOT) { value ->
            act { repository.updateProfile(saved.copy(autopilot = value)) }
        }
        CarDivider()
        SwitchRow(SettingsCopy.PHONE_SYNC_TITLE, SettingsCopy.PHONE_SYNC_LINE, saved.phoneSync, icon = painterResource(R.drawable.ic_phone),
            spoken = SettingsCopy.SPOKEN_PHONE_SYNC) { value ->
            act { repository.updateProfile(saved.copy(phoneSync = value)) }
        }
        CarDivider()
        SwitchRow(SettingsCopy.DEMO_TITLE, SettingsCopy.DEMO_LINE, saved.demoMode, icon = painterResource(R.drawable.ic_clock), spoken = SettingsCopy.SPOKEN_DEMO) { value ->
            if (!value) { DemoDrive.setMoving(false); DemoDrive.setCharging(false) }
            act { repository.updateProfile(saved.copy(demoMode = value)) }
        }
        CarDivider()
        CarInfoRow(SettingsCopy.STORAGE_INFO, more = SettingsCopy.STORAGE_MORE)
        CarInfoRow(SettingsCopy.SIGNALS_INFO, more = SettingsCopy.SIGNALS_MORE)
    }
}

/**
 * A setting that is on or off (2.12): the whole row is the switch, at least 72 dp. An optional [icon] on its disc, the
 * [title] (one line) over one [line] ([CarLine]; [spoken] is the long form TalkBack reads instead), and the switch in the
 * car's tokens ([carSwitchColors]). TalkBack hears the title, the line or [spoken], then on or off. [onChange] comes last,
 * so a call with a trailing lambda still compiles.
 */
@Composable internal fun SwitchRow(title: String, line: String, checked: Boolean, icon: Painter? = null, spoken: String? = null, onChange: (Boolean) -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(RoundedCornerShape(18.dp))
        .toggleable(value = checked, interactionSource = interaction, indication = null, role = Role.Switch, onValueChange = onChange)
        .pressFill(interaction).heightIn(min = 72.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (icon != null) CarIconDisc(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CarLine(line, spoken = spoken)
        }
        Switch(checked = checked, onCheckedChange = null, colors = carSwitchColors())
    }
}
