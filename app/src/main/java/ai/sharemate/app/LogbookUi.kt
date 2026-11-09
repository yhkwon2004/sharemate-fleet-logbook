package ai.sharemate.app

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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

/** A confirmation shown after a change, with one optional follow-up such as undo or applying the same choice to more trips. */
internal data class FollowUp(val message: String, val action: String? = null, val perform: (suspend () -> Unit)? = null)
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

/** Classifies a trip with haptic confirmation, then offers undo or a same-day bulk apply in the follow-up bar. */
@Composable internal fun rememberClassifier(data: TripLogData, act: Act, repository: TripLogRepository): (Trip, TripPurpose, EditSource) -> Unit {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    return { trip, purpose, source ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val others = bulkClassifyCandidates(data.trips, trip, zone)
        act {
            repository.classify(trip.id, purpose, source)
            followUp(if (others.isEmpty()) FollowUp("${withRo(purpose.label)} 기록했어요", "실행 취소") {
                repository.classify(trip.id, trip.purpose, trip.purposeSource ?: EditSource.DRIVER)
            } else FollowUp("${withRo(purpose.label)} 기록 · 같은 날 미분류 ${others.size}건", "${others.size}건도 같게") {
                repository.classifyMany(others.map { it.id }, purpose, EditSource.DRIVER)
            })
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
    val followUp: (FollowUp) -> Unit = { item ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(item.message, item.action, duration = if (item.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) item.perform?.let(act)
        }
    }
    val clock by produceState(System.currentTimeMillis()) { while (true) { value = System.currentTimeMillis(); delay(1_000) } }
    // A new sample can be newer than the previous UI clock tick.
    val now = remember(clock, state.live) { System.currentTimeMillis() }
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
                        info = "파일 선택기를 사용할 수 없어 앱 보관 폴더에 저장했어요.\n${file.absolutePath}"
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
                // One background layer for every page: the city at full strength on home, dimmed behind the other pages,
                // moving on the same clock as the page change so the layers never jump.
                val skyLevel = when { blocked || !state.loaded -> 0f; tab == 0 -> 1f; else -> .34f }
                // The fade lives in its own scope (SkyLayer), so the 220 ms animation recomposes only the backdrop, not the shell.
                SkyLayer(skyLevel, Modifier.matchParentSize())
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
                            NavTab("월간·Excel", R.drawable.ic_report, closeIssues),
                            NavTab("차량", R.drawable.ic_vehicle, openIncidents(state.data).size),
                            NavTab("설정", R.drawable.ic_tune, 0),
                        )
                        // The rail: one column inside the gutters, capped at the content maximum and anchored to the start so the
                        // right of a wide window stays sky with nothing interactive on it. The header, the daily driver question and
                        // every page share it; the header never appears or disappears between pages, only its lit tab moves.
                        Box(Modifier.fillMaxSize().padding(horizontal = layout.gutter)) {
                            Box(Modifier.contentWidth().fillMaxHeight().align(if (layout.startAnchored) Alignment.TopStart else Alignment.TopCenter)) {
                                Column(Modifier.fillMaxSize()) {
                                    TopBar(state, layout, tabs, tab, { tab = it }, { openDriver(null) }, { tutorial = true }, { assistant = true },
                                        clock = if (layout.short) hm(now) else null)
                                    // The 네 row folds itself away (200 ms, a snap under reduced motion) once the driver has answered, still
                                    // showing the question it answered while it folds; the gap under it folds with it.
                                    DriverConfirmRow(state, now, openDriver, driverConfirmed, { driverConfirmed = it }, Modifier.padding(bottom = 12.dp))
                                    val swapForward = pageSwap(forward = true)
                                    val swapBack = pageSwap(forward = false)
                                    AnimatedContent(targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                                        transitionSpec = { if (targetState > initialState) swapForward else swapBack }, label = "parked-page") { page ->
                                      // Tells goLog and goReport which pages are still composed (see composedPages).
                                      DisposableEffect(page) { composedPages += page; onDispose { composedPages -= page } }
                                      pageStates.SaveableStateProvider(page) {
                                        when (page) {
                                            0 -> DrivePane(state, now, act, repository, openDriver, requestPermissions, { tab = 4 }, { tab = it }, openLog, goReport,
                                                { assistant = true }, snoozes, bannerCharges, { bannerCharges = it })
                                            1 -> key(logGen) { LogPane(state, now, act, repository, openDriver, expenseTripId, logFilter, logMonth) }
                                            2 -> key(reportGen) { ReportPane(state, now, pendingExportName != null, export, act, repository, { tab = 4 }, { tab = 0 }, initialMonth = reportMonth) { month -> openLog(1, month) } }
                                            3 -> VehiclePane(state, now, act, repository, goSettings = { tab = 4 })
                                            // Saved-settings confirmations ride the bar; a modal is kept for text the driver must read, such as a file path.
                                            else -> SettingsPane(state, act, repository, { tutorial = true }, { phrase -> assistantText = phrase; assistant = true }) { text -> if (text.contains('\n')) info = text else followUp(FollowUp(text)) }
                                        }
                                      }
                                    }
                                }
                                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(vertical = 24.dp).widthIn(max = 760.dp)) { data ->
                                    AnimatedVisibility(visible = true, enter = barEnter(), exit = barExit()) { FollowUpBar(data) }
                                }
                            }
                        }
                    }
                }
            }
        }
      }
        if (pickingDriver && !blocked) DriverPicker(state.data, driverFor, act, repository, { pickingDriver = false }) { pickingDriver = false; tab = 4 }
        AssistantSheet(open = assistant && !blocked && state.loaded, onDismiss = { assistant = false; assistantText = null }, data = state.data, now = now,
            writer = assistantWriter, act = act, followUp = followUp, onOpenTrip = { tripId ->
                assistant = false
                val trip = state.data.trips.firstOrNull { it.id == tripId }
                goLog(0, trip?.let { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) }, tripId)
            },
            // The car's sheet in every window: a split car window is still the car, with its 56-60 dp targets and no ripple.
            compact = false, initialText = assistantText)
        if (tutorial && !blocked) DemoTutorial(pendingExportName != null, export) { tutorial = false }
        val message = error ?: state.error
        if (message != null && !blocked) AlertDialog(onDismissRequest = { error = null; repository.clearError() }, title = { Text("확인이 필요해요") }, text = { Text(message) },
            confirmButton = { DialogButton("확인") { error = null; repository.clearError() } })
        info?.takeUnless { blocked }?.let { text -> AlertDialog(onDismissRequest = { info = null }, title = { Text("저장 위치") }, text = { Text(text) }, confirmButton = { DialogButton("확인") { info = null } }) }
    } }
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
 * The header of every pane except home (home is the hub: its cards and its 설정 chip lead everywhere). One glass row:
 * the brand mark, the five tabs with their counts, then the recording state, the employee and the assistant. In a narrow
 * window the tabs drop to their own scrolling row.
 */
@Composable private fun TopBar(state: TripLogState, layout: LayoutTokens, tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit,
    onDriver: () -> Unit, onTutorial: () -> Unit, onAssistant: () -> Unit = {}, clock: String? = null) {
    val wide = layout.expanded
    val driver = state.data.drivers.firstOrNull { it.id == state.data.currentDriverId }
    // The tutorial is for a car that has not recorded anything yet; once real trips exist it stays reachable from settings.
    val showTutorial = state.data.trips.none { !it.demo }
    val employee: @Composable (Modifier) -> Unit = { modifier ->
        val interaction = remember { MutableInteractionSource() }
        Surface(onClick = onDriver, shape = RoundedCornerShape(50), color = Scene.glass, border = Scene.edge, interactionSource = interaction,
            modifier = modifier.heightIn(min = 56.dp).pressable(interaction, .98f).semantics { contentDescription = "이용 직원 ${driver?.label ?: "미선택"}, 변경" }) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("이용 직원", style = MaterialTheme.typography.labelSmall, color = Scene.textSecondary)
                Text(driver?.label ?: "직원 선택", style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = if (wide) 230.dp else 140.dp))
            }
        }
    }
    val tools: @Composable () -> Unit = {
        // On home the hero's search bar is the assistant entry; one entry per screen keeps focus order and TalkBack simple. The
        // wide header has no slack beside the tab pill, so there the entry is its icon alone (56 dp) and the tutorial button
        // keeps to home (it stays reachable from 설정 › 기록).
        if (selected != 0) OutlinedButton(onClick = onAssistant, border = BorderStroke(1.dp, Scene.outline),
            modifier = Modifier.heightIn(min = 56.dp).then(if (wide) Modifier.widthIn(min = 56.dp) else Modifier).semantics { contentDescription = "운행 비서 열기" },
            contentPadding = if (wide) PaddingValues(0.dp) else ButtonDefaults.ContentPadding) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(22.dp))
            if (!wide) { Spacer(Modifier.width(8.dp)); Text("운행 비서") }
        }
        if (showTutorial && (selected == 0 || !wide)) OutlinedButton(onClick = onTutorial, modifier = Modifier.heightIn(min = 56.dp), border = BorderStroke(1.dp, Scene.outline)) { Text("시연 체험") }
    }
    val mark: @Composable () -> Unit = {
        Icon(painterResource(R.drawable.ic_splash_logo), contentDescription = "SHAREMATE", tint = Scene.brand, modifier = Modifier.size(52.dp))
    }
    // The short window's clock: plain text, updated in place every second (never a cross-fade).
    val time: @Composable () -> Unit = {
        if (clock != null) Text(clock, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Scene.text, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { contentDescription = "현재 시각 $clock" })
    }
    // The header pill is 68 dp (56-dp tabs inside 6 dp of padding); the short header trims the padding to 4.
    Column(Modifier.fillMaxWidth().padding(vertical = if (layout.short) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (wide) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                mark()
                GlassTabs(tabs, selected, onSelect, compact = layout.short)
            }
            // The right cluster takes what the pill leaves of the rail. The employee chip is the one that gives, its label
            // ellipsising before anything is pushed past the column's edge.
            Row(Modifier.weight(1f, fill = false).padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                time()
                StatusPill(state)
                employee(Modifier.weight(1f, fill = false))
                tools()
            }
        } else {
            // Narrow windows: the mark sits beside the tab pill (which scrolls when it does not fit), then one row for the
            // recording state, the short window's clock, the employee and the tools. Two rows instead of three, so a short,
            // narrow window keeps most of its height for the page.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                mark()
                GlassTabs(tabs, selected, onSelect, Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), compact = layout.short)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.weight(1f)) { StatusPill(state) }
                time()
                employee(Modifier.weight(1f, fill = false))
                tools()
            }
        }
    }
}

/** The tone of the header pill's dot: a failure or a stale signal, waiting, recording, or demo data. */
internal enum class PillTone { NEGATIVE, QUIET, POSITIVE, DEMO }

/**
 * The header pill's line and tone: a save failure first, then a signal that cannot record, then staleness, then what the
 * car is doing. [fresh] and [status] come from [drivingOverviewInfo], the same rule as the driving screen.
 */
internal fun statusPillInfo(state: TripLogState, fresh: Boolean, status: String): Pair<String, PillTone> {
    val recording = state.data.open != null
    return when {
        state.loadFailed || state.error != null -> "기록 저장 확인 필요" to PillTone.NEGATIVE
        state.signal in setOf(SignalStatus.DENIED, SignalStatus.UNSUPPORTED, SignalStatus.ERROR) -> state.signal.label to PillTone.NEGATIVE
        state.signal == SignalStatus.STARTING -> state.signal.label to PillTone.QUIET
        !fresh && !recording -> status to PillTone.NEGATIVE
        recording -> "기록 중 · ${kmText(state.data.open!!.distanceKm)}" to PillTone.POSITIVE
        state.signal == SignalStatus.READY -> "자동 기록 대기" to PillTone.QUIET
        state.signal == SignalStatus.DEMO -> "시연 데이터" to PillTone.DEMO
        else -> state.signal.label to PillTone.NEGATIVE
    }
}

@Composable private fun StatusPill(state: TripLogState) {
    // Freshness comes from the same rule as the driving screen, so a stale signal never reads as "ready".
    val overview = drivingOverviewInfo(state, System.currentTimeMillis(), true, zone)
    val (label, tone) = statusPillInfo(state, overview.fresh, overview.status)
    // A status colour never animates (spec C): a changed state swaps the dot at once, with no tint transition and no pulse.
    val dot = when (tone) {
        PillTone.NEGATIVE -> Scene.negative
        PillTone.QUIET -> Scene.textMuted
        PillTone.POSITIVE -> Scene.positive
        PillTone.DEMO -> LocalPleosColors.current.gleoSpeaker
    }
    Surface(shape = RoundedCornerShape(20.dp), color = Scene.glass, border = Scene.edge) {
        // Polite live region: TalkBack announces recording start/stop without the driver touching the screen.
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 18.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Text(label, style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1)
        }
    }
}

/**
 * Segment control in the same glass as the header tabs: the chosen segment carries the selection fill and ring, and the pill
 * slides to a new choice in 180 ms (a snap under reduced motion). Segments are 56 dp; cap the width with the modifier.
 */
@Composable internal fun Segmented(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
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

@Composable internal fun PrimaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Button(onClick = onClick, enabled = enabled, modifier = modifier.carPress(interaction).heightIn(min = 64.dp), shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp), interactionSource = interaction) { Text(label, style = MaterialTheme.typography.labelLarge) }
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
 * A secondary action on the row fill. Disabled, it drops its container and keeps the label at 38 %, so it never reads stronger
 * than an enabled one (Material's disabled container is a darker slab than this light fill). [description] replaces a
 * symbolic label for screen readers.
 */
@Composable internal fun QuietAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface,
    description: String? = null, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), interactionSource = interaction,
        modifier = modifier.carPress(interaction).heightIn(min = 60.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = LocalPleosColors.current.surfaceHigh, contentColor = color,
            disabledContainerColor = Color.Transparent, disabledContentColor = color.copy(alpha = .38f)),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)) {
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

@Composable internal fun PurposeChip(purpose: TripPurpose) {
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
 * cross-fades; colours are read in composition.
 */
@Composable internal fun RatioRing(ratio: Double?, diameter: Dp, caption: String, modifier: Modifier = Modifier, sweepKey: String = "report-ratio-ring") {
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
            Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────────────────────── 운행 ─────────────────────────

/**
 * The car home (2.10): a city hero in the scene's light with the greeting, the assistant entry and three destinations
 * (차량 정보 · 운행일지 확인 · 정보 내보내기); under it whatever the car is waiting on, one card at a time in the order the
 * moment demands (safety, then money the car measured, then paperwork, then the manager's deadlines); then today and
 * this month as two widgets, and the one-tap incident row. Laid out by [homePlan] for the window's class and height. Every way
 * into 월간·Excel from here ([openReport]) names the month it opens on: the close card's and the banner's, the month
 * 정보 내보내기 names, this month for the month widget.
 */
@Composable private fun DrivePane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, requestPermissions: () -> Unit, goSettings: () -> Unit, navigate: (Int) -> Unit, openLog: (Int, YearMonth?) -> Unit,
    openReport: (YearMonth) -> Unit, assistant: () -> Unit, snoozes: MutableState<Set<String>>, banner: List<CardCharge> = emptyList(),
    onBanner: (List<CardCharge>) -> Unit = {}) {
    val layout = LocalLayout.current
    val wide = layout.expanded
    val data = state.data
    val today = localDate(now)
    val prompt = rememberChargePrompt(data)
    // The stack's answers (finished-trip cards acknowledged, a purpose being changed) live here, so they outlive the stack
    // leaving the composition and the pane can ask whether it draws a card before composing it.
    val stackAnswers = rememberParkedStackAnswers()
    val taskStack: @Composable (Modifier) -> Unit = { place ->
        // The stack grows and shrinks with its card; the change reflows over 260 ms (a snap under reduced motion).
        ParkedTaskStack(state, now, wide, blocked = false, act, repository, openDriver, goSettings, modifier = place.fillMaxWidth().animateContentSize(enterSpec(260)),
            chargePrompt = prompt.value, onWaitForCard = { prompt.value = ChargePrompt.WAIT }, snoozedCare = snoozes.value, showAllDone = false,
            answers = stackAnswers) { task ->
            when (task.kind) {
                TaskKind.MONTH_CLOSE -> monthCloseTarget(data, today, zone)?.let {
                    MonthCloseCard(it, onIssues = { openLog(1, it.month) }, onReviews = { navigate(0) }, onExport = { openReport(it.month) }, exporting = false, act = act, repository = repository)
                }
                TaskKind.CARE -> CareDueTask(state, now, act, repository, snoozed = snoozes.value, onSnoozed = { snoozes.value = it })
                else -> {}
            }
        }
    }
    // The same task list the stack reads (its odometer included), so the pane and the stack agree on what is on screen.
    val tasks = parkedTasks(data, state.sync, now, zone, snoozes.value, prompt.value, state.live?.takeIf { it.demo == data.profile.demoMode }?.odometerKm)
    // The month-close banner is a reminder for when the card is not on screen; while the stack shows the card itself, one is enough.
    val closeCardShown = tasks.firstOrNull()?.kind == TaskKind.MONTH_CLOSE
    // The stack is composed only while it draws a card (the rule it applies itself), so an empty stack never holds a gap open.
    val stackShown = stackAnswers.key(tasks, data, state.lastFinishedId, now).showsCard
    val live = state.live?.takeIf { it.demo == data.profile.demoMode && now - it.at in 0..45_000 }
    val (odometer, odometerLive) = careOdometer(state, now)
    val soc = live?.socPercent
    // The clock recomposes this pane every second; the card lines only change with the data, the readings and the day.
    val lines = remember(data, soc, odometer, odometerLive, today, snoozes.value) { homeCardLines(data, soc, odometer, odometerLive, today, zone, snoozes.value) }
    val cards = listOf(
        HomeCardSpec("차량 정보", lines.vehicle, R.drawable.ic_vehicle, openIncidents(data).size) { navigate(3) },
        HomeCardSpec("운행일지 확인", lines.log, R.drawable.ic_journal, lines.logPending) { openLog(if (lines.logPending > 0) 1 else 0, YearMonth.from(today)) },
        HomeCardSpec("정보 내보내기", lines.export, R.drawable.ic_export, 0) { openReport(lines.exportMonth) },
    )
    val demoControls: @Composable () -> Unit = {
        if (data.profile.demoMode) DemoControls { act { repository.ingestCardEvents(listOf(demoCardApproval(repository.state.value.data, System.currentTimeMillis()))) } }
    }
    // The hero block staggers in once per session; a return to the tab or a sync lands in place.
    val entrance = rememberHomeEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plan = homePlan(layout, LocalDensity.current.fontScale, maxHeight)
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxSize().fadingTopEdge(scroll).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(layout.sectionGap)) {
            // The hero grows with its text (a permission button, a second greeting line); the change reflows over 260 ms.
            HomeHero(state, now, plan, assistant, requestPermissions, demoControls, cards, Modifier.animateContentSize(enterSpec(260)), entrance = entrance)
            MatchedBanner(banner, onShow = { onBanner(emptyList()); openLog(2, YearMonth.from(today)) }, onDismiss = { onBanner(emptyList()) })
            NextReservationLine(data = state.data, now = now)
            if (!closeCardShown) MonthCloseBannerHost(data, now, onOpen = openReport)
            // The stack and the widgets share one slot and the stack carries the section gap under it, so the stack unfolds and
            // folds away together with its gap (260 ms, a snap under reduced motion) and the widgets move up smoothly behind it.
            Column(Modifier.fillMaxWidth()) {
                AnimatedVisibility(stackShown, enter = fadeIn(enterSpec(220)) + expandVertically(enterSpec(260)),
                    exit = fadeOut(enterSpec(160)) + shrinkVertically(enterSpec(260)), label = "task-stack") {
                    taskStack(Modifier.padding(bottom = layout.sectionGap))
                }
                HomeWidgets(state, now, sideBySide = plan.widgetsSideBySide, compact = plan.compactWidgets) { openReport(YearMonth.from(today)) }
            }
            // A charge whose card approval is still on its way: the strip says so and offers to type it now.
            hiddenPendingCharge(data, now, prompt.value)?.let { waiting ->
                AutopilotStrip(data, now, waiting = waiting, waitingPrompt = prompt.value, onEnterNow = { prompt.value = ChargePrompt.NOW })
            }
            IncidentQuickRow(data, act, repository)
        }
    }
}

/** Presenter controls while demo mode is on: drive/park at 15x, a charge, and a card approval. */
@Composable private fun DemoControls(demoCard: () -> Unit) {
    val moving by DemoDrive.moving.collectAsState()
    val charging by DemoDrive.charging.collectAsState()
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PrimaryAction(if (moving) "시연 주차 (P)" else "시연 출발 (D)", Modifier.weight(1.3f)) { DemoDrive.setMoving(!moving) }
        QuietAction(if (charging) "충전 종료" else "충전 시작", Modifier.weight(1f), enabled = !moving) { DemoDrive.setCharging(!charging) }
        QuietAction("카드 승인", Modifier.weight(1f), onClick = demoCard)
    }
}

/** One purpose as a 96-dp tile: its status dot, label and caption. The chosen one carries the selection fill and ring; it settles to .98 while held. */
@Composable internal fun PurposeTile(purpose: TripPurpose, modifier: Modifier, selected: Boolean = false, onClick: () -> Unit) {
    val color = purposeColor(purpose)
    val caption = if (purpose.business) "업무 거리 포함" else "업무 합계 제외"
    val shape = RoundedCornerShape(24.dp)
    val interaction = remember { MutableInteractionSource() }
    Surface(onClick = onClick, modifier = modifier.heightIn(min = 96.dp).pressable(interaction, .98f).semantics { this.selected = selected; role = Role.RadioButton },
        shape = shape, color = if (selected) Scene.selectionFill else Scene.fill, interactionSource = interaction,
        border = if (selected) BorderStroke(1.5.dp, Scene.selectionRing) else null) {
        Column(Modifier.pressFill(interaction).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Text(purpose.label, style = MaterialTheme.typography.titleMedium, color = if (selected) Scene.glow else Scene.text, maxLines = 1)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable internal fun PurposeOptions(selected: TripPurpose? = null, onSelect: (TripPurpose) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 480.dp) Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            purposeChoices.forEach { purpose ->
                val active = purpose == selected
                Surface(onClick = { onSelect(purpose) }, shape = RoundedCornerShape(16.dp),
                    color = if (active) Scene.selectionFill else Scene.fill, border = if (active) BorderStroke(1.5.dp, Scene.selectionRing) else null,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).semantics { this.selected = active; role = Role.RadioButton }) {
                    Text(purpose.label, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center, color = if (active) Scene.glow else Scene.text,
                        modifier = Modifier.padding(16.dp))
                }
            }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            purposeChoices.forEach { purpose -> PurposeTile(purpose, Modifier.weight(1f), purpose == selected) { onSelect(purpose) } }
        }
    }
}

@Composable internal fun SetupCard(data: TripLogData, goSettings: () -> Unit, pickDriver: () -> Unit) {
    val steps = listOf(
        "법인·차량 정보" to (data.profile.companyName.isNotBlank() && data.profile.plateNumber.isNotBlank()),
        "직원 등록" to data.drivers.isNotEmpty(),
        "지금 운전하는 직원 선택" to (data.currentDriverId != null),
    )
    Panel {
        Eyebrow("시작 설정 · ${steps.count { it.second }}/${steps.size}", Scene.brand)
        Text("운행기록부 양식에 들어갈 정보를 한 번만 등록해 주세요", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        steps.forEachIndexed { index, (label, done) ->
            Row(Modifier.semantics(mergeDescendants = true) { stateDescription = if (done) "완료" else "미완료" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // A done step is the scene's positive green with its own foreground (tertiary / onTertiary follow the scene); an open one sits on the row fill.
                val colors = MaterialTheme.colorScheme
                Box(Modifier.size(36.dp).clip(CircleShape).background(if (done) colors.tertiary else Scene.fill), contentAlignment = Alignment.Center) {
                    Text(if (done) "✓" else "${index + 1}", style = MaterialTheme.typography.labelLarge, color = if (done) colors.onTertiary else colors.onSurface,
                        modifier = Modifier.clearAndSetSemantics {})
                }
                Text(label, style = MaterialTheme.typography.bodyLarge, color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryAction("설정 열기", Modifier.weight(1f), onClick = goSettings)
            if (data.drivers.isNotEmpty() && data.currentDriverId == null) QuietAction("운전자 선택", Modifier.weight(1f), onClick = pickDriver)
        }
    }
}



// ───────────────────────── 일지 ─────────────────────────

/** The three filters of the log list with their counts; the long 자동 반영 label only where the row has room. */
internal fun logFilterLabels(trips: Int, issues: Int, matched: Int, wide: Boolean): List<String> =
    listOf("전체 $trips", "점검 필요 $issues", if (wide) "자동 반영 $matched" else "자동 $matched")

/** The log's one empty state: a title, an optional second line, and whether 필터 해제 is offered beside 이전 달 보기. */
@Immutable
internal data class LogEmptyCopy(val title: String, val detail: String? = null, val clearable: Boolean = false)

/**
 * What the log says when the list under [filter] (0 전체, 1 점검 필요, 2 자동 반영) has nothing to pick in [month]: no trips,
 * no trip left to check, or no card payment that went in by itself. [monthTrips] counts the month's trips after the employee
 * filter, whose label is [employee] when one is set. 필터 해제 ([LogEmptyCopy.clearable]) whenever a filter other than 전체 or
 * an employee is set; 이전 달 보기 always.
 */
internal fun logEmptyCopy(filter: Int, month: YearMonth, monthTrips: Int, employee: String?, today: LocalDate): LogEmptyCopy {
    val name = monthLabel(month, today)
    val who = employee?.let { " $it 님의" }.orEmpty()
    val clearable = filter != 0 || employee != null
    return when {
        filter == 1 && monthTrips > 0 -> LogEmptyCopy("$name$who 점검할 운행이 없어요", "계기판 누적거리가 끊김 없이 이어져요", clearable)
        filter == 2 -> LogEmptyCopy("$name 카드 결제가 자동으로 들어간 운행이 없어요", clearable = clearable)
        else -> LogEmptyCopy("$name$who 운행 기록이 없어요", clearable = clearable)
    }
}

/**
 * 운행기록: the month stepper, the 3-way filter and the totals on one 64-dp row, the employee chip under it, then the list
 * (560 wide at FULL) beside the detail (616). MEDIUM and below keep the full-width list and open the detail as a side sheet;
 * a short window folds the filters into one dropdown chip and trims the rows to 64 dp. When the chosen list is empty the list
 * column holds one glass card that says so (max 560) and the detail panel stays away: there is nothing to pick.
 */
@Composable private fun LogPane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, openDriver: (String?) -> Unit,
    initialTripId: String?, initialFilter: Int = 0, initialMonth: String? = null) {
    val layout = LocalLayout.current
    val wide = layout.expanded
    var month by rememberSaveable { mutableStateOf(initialMonth?.takeIf { runCatching { YearMonth.parse(it) }.isSuccess } ?: YearMonth.from(localDate(now)).toString()) }
    var filter by rememberSaveable(initialFilter) { mutableIntStateOf(initialFilter) }
    var selectedId by rememberSaveable(initialTripId) { mutableStateOf(initialTripId) }
    var employeeFilter by rememberSaveable { mutableStateOf<String?>(null) }
    val period = YearMonth.parse(month)
    val all = state.data.trips
    val trips = tripsIn(all, period.atDay(1), period.atEndOfMonth(), zone).filter { employeeFilter == null || it.driverId == employeeFilter }
    val totals = logbookTotals(trips)
    val ids = trips.map { it.id }.toSet()
    val issues = auditTrips(all).filter { it.tripId in ids }
    val selected = all.firstOrNull { it.id == selectedId }
    // 자동 반영 follows the stepper's month like the other two lists (its list reads the month from the clock it is handed).
    val thisMonth = YearMonth.from(localDate(now))
    val periodClock = if (period == thisMonth) now else period.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val matchedRows = matchedTripsThisMonth(state.data, zone, period.atDay(1))
    val filters = logFilterLabels(trips.size, issues.size, matchedRows.size, wide)
    val totalsLine = "총 ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)} · ${totals.trips}건"
    val employeeName = state.data.drivers.firstOrNull { it.id == employeeFilter }?.label
    val employeeLabel = employeeName ?: "전체 직원"
    val previousMonth = { month = period.minusMonths(1).toString(); selectedId = null }
    val stepper: @Composable RowScope.() -> Unit = {
        PeriodStepper(reportStepperCopy(ReportPeriod(period, false), localDate(now)), period < thisMonth, previousMonth) { month = period.plusMonths(1).toString(); selectedId = null }
    }
    val employeeItems: @Composable ColumnScope.(() -> Unit) -> Unit = { close ->
        ChipMenuItem("전체 직원") { employeeFilter = null; selectedId = null; close() }
        state.data.drivers.forEach { driver -> ChipMenuItem(driver.label) { employeeFilter = driver.id; selectedId = null; close() } }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            // A short window: the three filters and the employee fold into one 56-dp dropdown chip on the stepper's row.
            layout.short -> Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                stepper()
                DropdownChip("${filters[filter]} · $employeeLabel") { close ->
                    filters.forEachIndexed { index, label -> ChipMenuItem(label) { filter = index; close() } }
                    HorizontalDivider(color = Scene.divider)
                    employeeItems(close)
                }
                Spacer(Modifier.weight(1f))
                Muted(totalsLine, maxLines = 1)
            }
            wide -> {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    stepper()
                    // The filter takes what is left of the row, up to 720; the totals sit at the column's right.
                    Box(Modifier.weight(1f)) { Segmented(filters, filter, Modifier.widthIn(max = 720.dp)) { filter = it } }
                    Muted(totalsLine, maxLines = 1)
                }
                DropdownChip("직원: $employeeLabel", content = employeeItems)
            }
            else -> {
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    stepper()
                    if (!layout.compact) { Spacer(Modifier.weight(1f)); Muted(totalsLine, maxLines = 1) }
                }
                if (layout.compact) Muted(totalsLine)
                Segmented(filters, filter) { filter = it }
                DropdownChip("직원: $employeeLabel", content = employeeItems)
            }
        }
        // An empty list is one card in the list column, never a blank column beside an empty detail panel.
        val empty = when (filter) { 0 -> trips.isEmpty(); 1 -> issues.isEmpty(); else -> matchedRows.isEmpty() }
        val list: @Composable (Modifier) -> Unit = { modifier ->
            if (empty) Box(modifier.verticalScroll(rememberScrollState())) {
                LogEmptyCard(logEmptyCopy(filter, period, trips.size, employeeName, localDate(now)), onPrevious = previousMonth,
                    onClear = { filter = 0; employeeFilter = null; selectedId = null })
            } else when (filter) {
                0 -> TripList(trips, state.data, issues, selectedId, modifier, compact = layout.short) { selectedId = it }
                1 -> IssueList(issues, state.data, modifier, act, repository, openDriver) { selectedId = it }
                else -> MatchedTripList(state.data, periodClock, act, repository, modifier) { selectedId = it }
            }
        }
        if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
            // FULL: list 560 + gap 24 + detail 616 fill the 1200-dp column; EXPANDED splits what it has.
            list(if (layout.full) Modifier.width(560.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight())
            // The detail and its placeholder only when there are rows to pick (or a trip is already open).
            if (!empty || selected != null) Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(32.dp), color = Scene.glass, border = Scene.edge) {
                if (selected == null) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Muted("운행을 선택하면 계기판 기록과 목적·비용·수정 이력을 볼 수 있어요.", Modifier.widthIn(max = 420.dp))
                } else TripDetail(selected, state.data, act, repository, openDriver) { selectedId = null }
            }
        } else Box(Modifier.weight(1f).fillMaxWidth()) {
            // While the detail sheet is up, the list under its scrim is out of TalkBack's reach: the sheet is the one pane to read.
            list(Modifier.fillMaxSize().then(if (selected != null) Modifier.clearAndSetSemantics {} else Modifier))
            SideSheet(selected, onDismiss = { selectedId = null }, widthFraction = if (layout.compact) .94f else .62f, title = "운행 상세") { trip ->
                TripDetail(trip, state.data, act, repository, openDriver) { selectedId = null }
            }
        }
    }
}

/** The car's period stepper (운행기록, 월간·Excel): 64-dp glass arrows around a heading TalkBack re-reads when it changes. */
@Composable private fun RowScope.PeriodStepper(copy: StepperCopy, canForward: Boolean, onBack: () -> Unit, onForward: () -> Unit) {
    MonthStepButton(forward = false, description = copy.back, onClick = onBack)
    Text(copy.label, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 1,
        modifier = Modifier.widthIn(min = 180.dp).semantics { heading(); liveRegion = LiveRegionMode.Polite })
    MonthStepButton(forward = true, description = copy.forward, enabled = canForward, onClick = onForward)
}

/**
 * One arrow of a [PeriodStepper], a 64-dp target: a 28-dp chevron on glass with the scene's lit edge. Disabled, the container
 * goes transparent and the chevron drops to 38 %, so a dead arrow never reads stronger than a live one. Settles to .98 while
 * held; no ripple on the car. [description] is what TalkBack reads (이전 달 · 다음 해 …), with the reason when the arrow is off.
 */
@Composable private fun MonthStepButton(forward: Boolean, description: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val interaction = remember { MutableInteractionSource() }
    val noRipple = LocalLayout.current.startAnchored
    val tint = if (enabled) Scene.text else Scene.text.copy(alpha = .38f)
    Box(modifier.size(64.dp).pressable(interaction, .98f).clip(shape)
        .then(if (enabled) Modifier.background(Scene.glass).border(Scene.edge, shape) else Modifier)
        .clickable(interactionSource = interaction, indication = if (noRipple) null else LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Icon(if (forward) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null,
            modifier = Modifier.size(28.dp), tint = tint)
    }
}

/**
 * The log's one empty state: a glass card at the top of the list column (max 560) with what the month is missing and the way
 * on, 이전 달 보기, plus 필터 해제 while a filter or an employee narrows the list ([LogEmptyCopy.clearable]).
 */
@Composable private fun LogEmptyCard(copy: LogEmptyCopy, onPrevious: () -> Unit, onClear: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.widthIn(max = 560.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(copy.title, style = MaterialTheme.typography.titleMedium, color = Scene.text)
            copy.detail?.let { Muted(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            QuietAction("이전 달 보기", onClick = onPrevious)
            if (copy.clearable) QuietAction("필터 해제", onClick = onClear)
        }
    }
}

/** One row of a [DropdownChip]'s menu: Material's menu item at the car's 56-dp touch target (its own minimum is 48). */
@Composable private fun ChipMenuItem(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = modifier.heightIn(min = 56.dp))

/** A 56-dp glass chip that opens a menu: the log's employee filter, and in a short window the whole filter set. [content] gets a close call. */
@Composable private fun DropdownChip(label: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Surface(onClick = { open = true }, shape = RoundedCornerShape(50), color = Scene.glass, border = Scene.edge, interactionSource = interaction,
            modifier = Modifier.heightIn(min = 56.dp).pressable(interaction, .98f).semantics { role = Role.DropdownList }) {
            Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("▾", style = MaterialTheme.typography.labelLarge, color = Scene.textSecondary, modifier = Modifier.clearAndSetSemantics {})
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) { content { open = false } }
    }
}

/** Keeps the last value shown while a sheet animates out after its item is cleared. */
private class LastShown<T> { var value: T? = null }

/**
 * The car's side sheet for a window too narrow for two columns: a scrim over the list and a glass sheet at the end edge that
 * lifts 24 dp and fades in (200 ms) and drops out (160 ms); a fade snap under reduced motion. Tapping the scrim or Back
 * closes it. TalkBack meets the sheet as one pane named [title]; the scrim is for touch only (the sheet carries its own 닫기),
 * and the caller takes what lies under the scrim out of TalkBack's reach while [item] is set.
 */
@Composable private fun <T : Any> BoxScope.SideSheet(item: T?, onDismiss: () -> Unit, modifier: Modifier = Modifier, widthFraction: Float = .62f,
    title: String = "상세", content: @Composable (T) -> Unit) {
    val reduce = LocalReduceMotion.current
    val scrim = Scene.bg
    val lift = with(LocalDensity.current) { 24.dp.roundToPx() }
    val last = remember { LastShown<T>() }
    if (item != null) last.value = item
    val shown = last.value
    BackHandler(enabled = item != null, onBack = onDismiss)
    AnimatedVisibility(item != null, Modifier.matchParentSize(), enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(160, easing = FastOutLinearInEasing))) {
        // Cleared before the click so its action goes too: a screen-wide unlabeled target would only get in TalkBack's way.
        Box(Modifier.fillMaxSize().clearAndSetSemantics {}.background(scrim.copy(alpha = .55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss))
    }
    AnimatedVisibility(item != null, modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(widthFraction),
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { lift },
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(160, easing = FastOutLinearInEasing)) + slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { lift }) {
        Surface(Modifier.fillMaxSize().semantics { paneTitle = title; isTraversalGroup = true }, shape = RoundedCornerShape(32.dp), color = Scene.glass,
            border = Scene.edge) { if (shown != null) content(shown) }
    }
}

@Composable private fun TripList(trips: List<Trip>, data: TripLogData, issues: List<TripIssue>, selectedId: String?, modifier: Modifier, compact: Boolean = false,
    onSelect: (String) -> Unit) {
    if (trips.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Muted("이 달의 운행 기록이 없어요.") }
        return
    }
    val flagged = issues.map { it.tripId }.toSet()
    val days = trips.reversed().groupBy { localDate(it.startAt) }
    val list = rememberLazyListState()
    LazyColumn(modifier.fadingTopEdge(list), state = list, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        days.forEach { (date, list) ->
            item(key = "d$date") {
                Row(Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 12.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)) {
                    Text(dayLabel(date), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Muted("${list.size}건 · ${kmText(list.sumOf { it.distanceKm })}")
                }
            }
            items(list, key = { it.id }) { trip -> TripRow(trip, data, trip.id in flagged, trip.id == selectedId, compact) { onSelect(trip.id) } }
        }
    }
}

/**
 * One trip of the list (88 dp, or 64 when [compact]). Every row sits on glass; the selected one lays the selection fill over
 * that glass and adds the 1.5-dp ring (never colour alone, and never a bare tint over the sky). Settles to .98 while held.
 */
@Composable internal fun TripRow(trip: Trip, data: TripLogData, flagged: Boolean, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val shape = RoundedCornerShape(if (compact) 18.dp else 24.dp)
    val interaction = remember { MutableInteractionSource() }
    val noRipple = LocalLayout.current.startAnchored
    Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(shape).background(Scene.glass)
        .then(if (selected) Modifier.background(Scene.selectionFill).border(1.5.dp, Scene.selectionRing, shape) else Modifier)
        .semantics { this.selected = selected; if (flagged) stateDescription = "점검 필요" }
        .clickable(interactionSource = interaction, indication = if (noRipple) null else LocalIndication.current, role = Role.Button, onClickLabel = "운행 상세 보기", onClick = onClick)
        .heightIn(min = if (compact) 64.dp else 88.dp).padding(horizontal = 20.dp, vertical = if (compact) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Column(Modifier.width(68.dp)) {
            Text(hm(trip.startAt), style = MaterialTheme.typography.titleMedium)
            if (!compact) Text(hm(trip.endAt ?: trip.updatedAt), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(routeText(trip), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(kmText(trip.distanceKm), durationText(trip.durationMillis), driver?.label, if (trip.demo) "시연" else null,
                if (trip.expenses.isNotEmpty()) formatWon(trip.expenseWon) else null).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (flagged) Box(Modifier.size(10.dp).clip(CircleShape).background(Scene.negative))
        PurposeChip(trip.purpose)
        if (editedAfterClose(trip)) MonthCloseChip("마감 후 수정")
    }
}

@Composable private fun IssueList(issues: List<TripIssue>, data: TripLogData, modifier: Modifier, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, onOpen: (String) -> Unit) {
    if (issues.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) { Muted("점검할 항목이 없어요. 계기판 누적거리가 끊김 없이 이어집니다.") }
        return
    }
    val trips = data.trips.associateBy { it.id }
    val classify = rememberClassifier(data, act, repository)
    val followUp = LocalFollowUp.current
    val list = rememberLazyListState()
    LazyColumn(modifier.fadingTopEdge(list), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(issues, key = { "${it.type}-${it.tripId}" }) { issue ->
            val trip = trips[issue.tripId] ?: return@items
            Panel(padding = 22.dp) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Scene.negative))
                    Text(issue.type.label + (issue.gapKm?.let { " · ${kmText(it)}" } ?: ""), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).semantics { heading() })
                    Muted("${dayLabel(localDate(trip.startAt))} ${hm(trip.startAt)}")
                }
                Muted(issue.type.detail)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    when (issue.type) {
                        IssueType.UNCLASSIFIED -> purposeChoices.forEach { purpose ->
                            QuietAction(purpose.label, Modifier.weight(1f)) { classify(trip, purpose, EditSource.DRIVER) }
                        }
                        IssueType.NO_DRIVER -> QuietAction("사용자 지정", Modifier.weight(1f)) { openDriver(trip.id) }
                        IssueType.ZERO_DISTANCE -> QuietAction("기록 삭제", Modifier.weight(1f), color = Scene.negative) {
                            act { repository.deleteTrip(trip.id); followUp(FollowUp("0 km 기록을 삭제했어요", "실행 취소") { repository.restoreTrip(trip) }) }
                        }
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
    val negative = Scene.negative
    var note by rememberSaveable(trip.id, trip.note) { mutableStateOf(trip.note) }
    var expenseType by rememberSaveable(trip.id) { mutableIntStateOf(1) }
    var amount by rememberSaveable(trip.id) { mutableStateOf("") }
    var deleting by remember(trip.id) { mutableStateOf(false) }
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val classify = rememberClassifier(data, act, repository)
    val followUp = LocalFollowUp.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Eyebrow(dayLabel(localDate(trip.startAt)) + if (trip.demo) " · 시연 데이터" else "", Scene.brand)
                Text("${hm(trip.startAt)} – ${if (trip.open) "기록 중" else hm(trip.endAt!!)}", style = MaterialTheme.typography.headlineSmall)
            }
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 56.dp)) { Text("닫기") }
        }
        Surface(shape = RoundedCornerShape(24.dp), color = Scene.fill) {
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
        // The date above is the sheet's one section label (brand); the labels inside it name its fields.
        Eyebrow("운행 목적", Scene.textSecondary)
        PurposeOptions(trip.purpose) { purpose -> if (purpose != trip.purpose) classify(trip, purpose, EditSource.DRIVER) }
        trip.purposeSource?.let { Muted("목적: ${trip.purpose.label} · ${it.label}") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat("④ 사용자", driver?.label ?: "미지정", Modifier.weight(1f))
            QuietAction("변경") { openDriver(trip.id) }
        }
        Eyebrow("운행 비용", Scene.textSecondary)
        trip.expenses.forEachIndexed { index, expense ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${expense.type.label}  ${formatWon(expense.amountWon)}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Muted(expense.merchant?.let { "$it · ${expense.source.label}" } ?: expense.source.label)
                TextButton(onClick = { act {
                    repository.removeExpense(trip.id, index)
                    followUp(FollowUp("${expense.type.label} ${formatWon(expense.amountWon)}을 삭제했어요", "실행 취소") {
                        val charge = expense.cardChargeId
                        if (charge != null) repository.confirmCardCharge(charge, trip.id) else repository.addExpense(trip.id, expense.type, expense.amountWon, expense.source)
                    })
                } }, modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "${expense.type.label} ${formatWon(expense.amountWon)} 삭제" }) { Text("삭제") }
            }
        }
        Segmented(ExpenseType.entries.map { it.label }, expenseType) { expenseType = it }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("금액", amount, { if (it.length <= 8 && it.all(Char::isDigit)) amount = it }, Modifier.weight(1f), number = true, suffix = "원", done = true,
                hint = amount.toLongOrNull()?.let(::formatWon) ?: "1원~1,000만 원")
            PrimaryAction("추가", enabled = amount.toLongOrNull()?.let { it in 1..10_000_000 } == true) {
                val value = amount.toLong()
                act { repository.addExpense(trip.id, ExpenseType.entries[expenseType], value, EditSource.DRIVER); amount = "" }
            }
        }
        Eyebrow("⑩ 비고", Scene.textSecondary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("방문처·업무 내용", note, { if (it.length <= 300) note = it }, Modifier.weight(1f), done = true, hint = "${note.length} / 300")
            PrimaryAction("저장", enabled = note != trip.note) { act { repository.setNote(trip.id, note.trim()) } }
        }
        if (trip.history.isNotEmpty()) {
            Eyebrow("수정 이력", Scene.textSecondary)
            trip.history.takeLast(8).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!trip.open) TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("이 기록 삭제", color = negative) }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("운행 기록을 삭제할까요?") },
        text = { Text("삭제하면 계기판 거리가 끊겨 ‘기록되지 않은 주행’으로 표시될 수 있어요. 시동만 켠 기록이나 시연 기록만 삭제하는 것을 권장해요.") },
        confirmButton = { DialogButton("삭제", color = negative) { deleting = false; close(); act {
            repository.deleteTrip(trip.id)
            followUp(FollowUp("${kmText(trip.distanceKm)} 운행 기록을 삭제했어요", "실행 취소") { repository.restoreTrip(trip) })
        } } },
        dismissButton = { DialogButton("취소") { deleting = false } })
}

@Composable internal fun Field(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, number: Boolean = false, suffix: String? = null,
    done: Boolean = false, hint: String? = null, error: String? = null) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // The field sits on glass: the row fill when idle, glass when focused; the outline token for its border and the selection colour when focused.
    OutlinedTextField(value, onChange, modifier.fillMaxWidth().heightIn(min = 68.dp), label = { Text(label) }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge,
        suffix = suffix?.let { { Text(it) } }, shape = RoundedCornerShape(18.dp),
        supportingText = (error ?: hint)?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } }, isError = error != null,
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Scene.fill, focusedContainerColor = Scene.glass,
            unfocusedBorderColor = Scene.outline, focusedBorderColor = Scene.glow),
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

/** Two blocks of a page: side by side with [gap] between them when [sideBySide], one under the other otherwise. */
@Composable private fun SideBySide(sideBySide: Boolean, gap: Dp, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(gap)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    else Column(verticalArrangement = Arrangement.spacedBy(gap)) { first(Modifier); second(Modifier) }
}

/** What the sticky save bar keeps clear at the bottom of the 월간·Excel pane: the bar's 72 dp and the 16-dp gap above it. */
internal val SAVE_BAR_RESERVE = 72.dp + 16.dp

/**
 * The car's Excel preview height (spec B): the pane's height less the sticky save bar's room and the view tabs and range line
 * above the preview, so a scroll to the end shows the tabs, the whole preview and the bar together. Never under
 * [EXCEL_PREVIEW_MIN_HEIGHT] (scaled up with a large font, whose taller text the preview's chrome needs), where the pane
 * scrolls further instead of squeezing the table. An unbounded pane gets the minimum.
 */
internal fun excelPreviewHeight(pane: Dp, fontScale: Float = 1f): Dp {
    val scale = fontScale.coerceAtLeast(1f)
    val floor = EXCEL_PREVIEW_MIN_HEIGHT * scale
    if (!pane.value.isFinite()) return floor
    // The view tabs (56-dp segments in 5 dp of padding), a 16-dp gap, the range line (one labelMedium line) and the gap under it.
    val viewHeader = 66.dp + 16.dp + 16.dp * scale + 16.dp
    return (pane - SAVE_BAR_RESERVE - viewHeader).coerceAtLeast(floor)
}

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

/** The tax card's eyebrow: the year whose business ratio it applies, 올해 for this one. */
internal fun taxEyebrow(year: Int, today: LocalDate): String = "비용 참고 · ${yearLabel(year, today)} 업무사용비율 기준"

/**
 * What the tax card says instead of its figures, or null when it can compute them. A missing annual cost comes first, and only
 * that state offers 설정에서 입력. With the cost set, a [year] without trips has no business ratio (the card reads the year on
 * screen, and the stepper has no floor) and no setting can give it one, so the card says that instead of asking for the cost.
 */
internal fun taxCardBlocker(cost: Long?, ratio: Double?, year: Int, today: LocalDate): String? = when {
    cost == null -> "연간 차량 비용을 입력하면 운행기록부의 효과를 계산해요"
    // In the pattern of logEmptyCopy and monthCloseHeadline ("… 운행 기록이 없어요"): says why there is no figure, asks for nothing.
    ratio == null -> "${yearLabel(year, today)} 운행 기록이 없어 업무사용비율을 계산할 수 없어요"
    else -> null
}

/** The Excel view's save bar (72 dp, max 760), pinned to the bottom of the pane: one line of context and the save action, on glass. */
@Composable private fun SaveBar(hint: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Surface(modifier.widthIn(max = EXCEL_VIEW_MAX_WIDTH).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Scene.glass, border = Scene.edge) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 22.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Muted(hint, Modifier.weight(1f), maxLines = 2)
            action()
        }
    }
}

/**
 * 월간·Excel, one vertical scroll over one period on screen ([ReportPeriod]: a month under 월간, a year under 연간), stepped
 * like 운행기록 and never past this month. From EXPANDED up the stepper, 월간 · 연간 (320 wide) and Excel 저장 share one
 * 64-dp row; below that the stepper has its own row over the other two (480 wide). Then the month-close card (max 760) of
 * the month on screen ([reportCloseStatus]), the four views as 56-dp tabs (max 960), the range line and the view. The label,
 * the card, the views, the preview, every Excel 저장 and its file name, and 정리하러 가기 all read the one range
 * [reportRange] gives. The pane opens on [initialMonth] when a button hands it one, else where the home card, the banner
 * and the tab badge point ([reportLandingMonth]); after that only the driver moves it. The Excel view keeps to 760 like the
 * month-close card: the preview with its lighter sheet tabs at [excelPreviewHeight] (480 at least), its table paging to the
 * rows that fit, and the save bar (72 dp) pinned to the bottom of the pane while the rest scrolls under it. The two cards of
 * the other views sit side by side from EXPANDED up and stack below it. Every Excel 저장 follows one rule ([excelSaveAvailable]).
 */
@Composable private fun ReportPane(state: TripLogState, now: Long, exporting: Boolean, saveFile: (String, String, ByteArray) -> Unit,
    act: Act, repository: TripLogRepository, goSettings: () -> Unit, goHome: () -> Unit, initialMonth: String? = null, goReview: (YearMonth) -> Unit) {
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
        TaxCard(data.profile, yearTotals, taxEyebrow(shown.month.year, today), taxCardBlocker(data.profile.annualCostWon, yearTotals.ratio, shown.month.year, today), modifier, goSettings)
    }
    val settle: @Composable (Modifier) -> Unit = { modifier -> SettlementCard(trips, data, modifier, goSettings) }
    // The header's three parts: the stepper (the same one as 운행기록), 월간 · 연간 and Excel 저장, placed per window below.
    val stepper: @Composable RowScope.() -> Unit = { PeriodStepper(reportStepperCopy(shown, today), reportCanGoForward(shown, today), { step(false) }) { step(true) } }
    val periodUnit: @Composable (Modifier) -> Unit = { modifier -> Segmented(listOf("월간", "연간"), period, modifier) { period = it } }
    val save: @Composable (Modifier) -> Unit = { modifier ->
        LoadingAction("Excel 저장", loading = exporting, modifier = modifier.semantics { if (!canSave) stateDescription = "저장할 운행이 없어요" }, loadingLabel = "만드는 중…",
            enabled = canSave, onClick = requestExport)
    }
    val excel = view == 2
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
                    MonthClosePanel(s, onIssues = { goReview(s.month) }, onReviews = goHome, onExport = requestExport, exporting = exporting, act = act, repository = repository,
                        modifier = Modifier.widthIn(max = 760.dp), compact = !wide, editedAfterClose = edited, today = today, onPrevious = { month = s.month.minusMonths(1).toString() })
                } }
            }
            Segmented(listOf("운행 현황", "경비·정산", "Excel", "검토"), view, Modifier.widthIn(max = 960.dp)) { view = it }
            Text("${if (data.profile.demoMode) "시연 데이터 · " else ""}${from} ~ ${to} · ${totals.trips}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Crossfade(view, Modifier.fillMaxWidth(), animationSpec = pageFade(), label = "report-section") { page ->
                // The preview has a height of its own in the scroll, and its table pages to the rows that fit in it.
                if (page == 2) ExcelPreview(data.copy(profile = data.profile.copy(carModel = data.profile.carModel.ifBlank { state.vehicleModel.orEmpty() })), trips, from, to,
                    Modifier.widthIn(max = EXCEL_VIEW_MAX_WIDTH).fillMaxWidth().height(previewHeight), compact = !wide, fillHeight = true)
                else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                    when (page) {
                        0 -> SideBySide(wide, layout.cardGap, ratio) { modifier -> EmployeeUsageCard(trips, data, modifier) }
                        1 -> SideBySide(wide, layout.cardGap, { modifier -> ExpenseOverview(trips, modifier) }, settle)
                        else -> {
                            // Reading panels keep to the month-close card's measure (760) instead of spanning the rail.
                            Panel(Modifier.widthIn(max = 760.dp)) {
                                Eyebrow("증빙 준비 상태", Scene.brand)
                                val issues = auditTrips(trips)
                                Text(auditHeadline(trips.size, issues.size), style = MaterialTheme.typography.titleLarge)
                                Muted("직원·운행 목적·계기판 거리의 연결을 점검합니다. Excel 확인사항 시트에서 세부 항목을 볼 수 있어요.")
                                Muted("국세청 운행기록부 항목 참고 · 제출 전 검토용. 영수증과 세무 요건은 별도로 확인해야 합니다.")
                            }
                            tax(Modifier.widthIn(max = 760.dp))
                        }
                    }
                }
            }
        }
        // The Excel view's own action stays in reach wherever the pane is scrolled: pinned to its bottom while that view is
        // chosen, fading with the view change (a snap under reduced motion).
        AnimatedVisibility(excel, Modifier.align(Alignment.BottomStart), enter = fadeIn(pageFade()), exit = fadeOut(pageFade()), label = "save-bar") {
            // The phone file carries the whole log, whatever period is on screen.
            SaveBar("휴대폰 SHAREMATE에서 여는 전체 운행 파일이에요") {
                LoadingAction("휴대폰용 운행파일 저장", loading = exporting, loadingLabel = "만드는 중…", enabled = data.trips.isNotEmpty()) {
                    saveFile("SHAREMATE_운행기록.smlog", LOGBOOK_MIME, TripLogCodec.encode(data.copy(open = null)))
                }
            }
        }
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("정리되지 않은 운행이 있어요") },
        text = { Text("미분류 ${kmText(totals.unclassifiedKm)}는 업무용 사용거리에서 제외되고, 사용자 미지정 운행은 ④란이 비어 있게 저장돼요. 그래도 저장할까요?") },
        confirmButton = { DialogButton("저장") { confirmExport = false; export() } },
        dismissButton = { DialogButton("정리하러 가기") { confirmExport = false; goReview(tidyMonth(trips, shown.month, zone)) } })
}

@Composable private fun EmployeeUsageCard(trips: List<Trip>, data: TripLogData, modifier: Modifier) {
    Panel(modifier) {
        Eyebrow("EMPLOYEES / 직원별 이번 기간 이용", Scene.brand)
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
            HorizontalDivider(color = Scene.divider)
        }
        Muted("운전하는 직원은 ‘지금 운전’에서 바꾸고, 지난 운행은 운행 상세에서 고칠 수 있어요.")
    }
}

@Composable private fun ExpenseOverview(trips: List<Trip>, modifier: Modifier) {
    Panel(modifier) {
        Eyebrow("EXPENSES / 등록 경비", Scene.brand)
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

@Composable private fun RatioCard(totals: LogbookTotals, modifier: Modifier, compact: Boolean = false) {
    val pleos = LocalPleosColors.current
    // The bar tracks share the ring's track token so they read on day glass as well as night.
    val track = Scene.ringTrack
    Panel(modifier.fillMaxHeight()) {
        Eyebrow("업무사용비율 ⑬ = ⑫ ÷ ⑪", Scene.brand)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { RatioRing(totals.ratio, if (compact) 148.dp else 220.dp, "업무사용비율") }
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
 * The annual cost reference at the business ratio of [year], the totals of the year on screen, which [eyebrow] names
 * ([taxEyebrow]). While it cannot compute, [blocker] ([taxCardBlocker]) says why: a missing cost comes with the way to enter
 * it; a year without trips sits above the cost already entered, with no 설정 action, since the form already holds the value.
 */
@Composable private fun TaxCard(profile: FleetProfile, year: LogbookTotals, eyebrow: String, blocker: String?, modifier: Modifier, goSettings: () -> Unit) {
    Panel(modifier.fillMaxHeight()) {
        Eyebrow(eyebrow, Scene.brand)
        val cost = profile.annualCostWon
        val ratio = year.ratio
        if (blocker != null) {
            Text(blocker, style = MaterialTheme.typography.titleMedium)
            if (cost == null) {
                Muted("감가상각비·유류비·보험료·자동차세 등 해당 차량의 연간 비용입니다. 운행기록부가 없으면 차량 1대당 연 1,500만 원까지만 업무용으로 인정돼요.")
                QuietAction("설정에서 입력", onClick = goSettings)
            } else Stat("연간 차량 관련 비용", formatWon(cost))
        } else if (cost != null && ratio != null) {
            val effect = taxEffect(cost, ratio)
            Stat("연간 차량 관련 비용", formatWon(cost))
            Stat("업무 비율 적용액 · 단순 비교", formatWon(effect.withLogWon), valueColor = MaterialTheme.colorScheme.onTertiaryContainer, large = true)
            Stat("미작성 방식 비교액", formatWon(effect.withoutLogWon))
            HorizontalDivider(color = Scene.divider)
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
        Eyebrow("유류비 정산 · 업무용 거리 × km당 단가", Scene.brand)
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
            HorizontalDivider(color = Scene.divider)
            Stat("정산 참고 합계 · km당 ${formatWon(rate)}", formatWon(list.sumOf { it.reimbursementWon }), large = true)
            Muted("직원 미지정 운행은 정산 보류입니다. 등록 경비를 이 금액에 자동으로 더하지 않아요.")
        }
    }
}

// ───────────────────────── 설정 ─────────────────────────

/** How many field columns a settings form shows: two from EXPANDED up at the normal font, one below that and always one at a large font (1.3×). */
internal fun settingsFieldColumns(layout: LayoutTokens, fontScale: Float): Int = if (layout.expanded && fontScale <= 1.25f) 2 else 1

/**
 * 설정: four sub-tabs as 56-dp text tabs (max 960) over one start-anchored form column (max 720); the rest of the rail stays
 * backdrop. Fields pair up in two columns from EXPANDED at the normal font ([settingsFieldColumns]); 화면 분위기 leads 기록.
 */
@Composable private fun SettingsPane(state: TripLogState, act: Act, repository: TripLogRepository, onTutorial: () -> Unit = {},
    onAssistant: (String?) -> Unit = {}, showInfo: (String) -> Unit) {
    val layout = LocalLayout.current
    val twoColumns = settingsFieldColumns(layout, LocalDensity.current.fontScale) == 2
    var section by rememberSaveable { mutableIntStateOf(0) }
    val profile: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ProfileCard(state, act, repository, showInfo, twoColumns)
            CareSettingsCard(state, act, repository)
        }
    }
    val drivers: @Composable () -> Unit = { DriversCard(state.data, act, repository, twoColumns) }
    val costs: @Composable () -> Unit = { CostCard(state.data.profile, act, repository, showInfo, twoColumns) }
    val privacy: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // The scene choice leads the section: it is the one setting a driver changes on the spot.
            SceneModeCard()
            PrivacyCard(state.data.profile, act, repository, onTutorial)
            AutopilotSettings(state.data.profile, act, repository) { value, change -> Field("법인카드 끝 4자리 (쉼표로 구분)", value, change, number = true, done = true, hint = "예: 1234, 5678") }
            AssistantPreviewCard(compact = !layout.expanded, onOpen = { onAssistant(null) }, onOpenWith = { phrase -> onAssistant(phrase) })
            Panel {
                Eyebrow("PLEOS SDK / 필요한 신호만 사용", Scene.brand)
                Text("${state.signal.label} · 기어·누적거리·충전상태·배터리·VIN", style = MaterialTheme.typography.titleMedium)
                Muted("Vehicle SDK로 거리·배터리·VIN을, AAOS로 기어와 충전 상태를 읽습니다. NaviHelper 주소는 수신한 경우에만 기록합니다.")
                Muted("권한·미지원·수신 지연은 확인 필요로 표시합니다. 차량 제어와 내비 목적지 변경은 하지 않습니다.")
            }
        }
    }
    val sections = rememberSaveableStateHolder()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Segmented(listOf("법인·차량", "사용자", "정산·세무", "기록"), section, Modifier.widthIn(max = 960.dp)) { section = it }
        Crossfade(section, Modifier.weight(1f).fillMaxWidth(), animationSpec = pageFade(), label = "settings-section") { page ->
            sections.SaveableStateProvider(page) {
                // One form column at the start of the rail, 720 at most; the column scrolls, the sub-tabs stay.
                val scroll = rememberScrollState()
                Column(Modifier.fillMaxSize().fadingTopEdge(scroll).verticalScroll(scroll)) {
                    Box(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(bottom = 8.dp)) { when (page) { 0 -> profile(); 1 -> drivers(); 2 -> costs(); else -> privacy() } }
                }
            }
        }
    }
}

@Composable private fun ProfileCard(state: TripLogState, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit, twoColumns: Boolean = false) {
    val saved = state.data.profile
    var company by rememberSaveable(saved.companyName) { mutableStateOf(saved.companyName) }
    var number by rememberSaveable(saved.businessNumber) { mutableStateOf(saved.businessNumber) }
    var model by rememberSaveable(saved.carModel) { mutableStateOf(saved.carModel) }
    var plate by rememberSaveable(saved.plateNumber) { mutableStateOf(saved.plateNumber) }
    val numberError = if (number.isNotBlank() && !BUSINESS_NUMBER.matches(number.trim())) "000-00-00000 형식으로 입력해 주세요" else null
    val vehicleModel = state.vehicleModel?.takeIf { it.isNotBlank() && it != "시연 차량" }
    Panel {
        Eyebrow("FLEET PROFILE", Scene.brand)
        Text("운행일지의 기본 정보", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
        Muted("한 번 저장하면 이후 운행과 Excel에 함께 사용합니다.")
        // 법인명 and 사업자등록번호 pair up only when the form has two columns ([settingsFieldColumns]).
        SideBySide(twoColumns, 16.dp, { modifier -> Field("법인명", company, { if (it.length <= 60) company = it }, modifier) }) { modifier ->
            Field("사업자등록번호", number, { value -> if (value.length <= 12 && value.all { it.isDigit() || it == '-' }) number = value }, modifier,
                hint = "예: 123-45-67890", error = numberError)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("① 차종", model, { if (it.length <= 60) model = it }, Modifier.weight(1f))
            if (vehicleModel != null && vehicleModel != model) QuietAction("차량에서 가져오기") { model = vehicleModel }
        }
        Field("② 자동차등록번호", plate, { if (it.length <= 20) plate = it }, done = true, hint = "예: 123가 4567")
        val changed = company != saved.companyName || number != saved.businessNumber || model != saved.carModel || plate != saved.plateNumber
        PrimaryAction(if (changed) "변경 내용 저장" else "저장된 정보", Modifier.fillMaxWidth(), enabled = changed && numberError == null) {
            act {
                repository.updateProfile(saved.copy(companyName = company.trim(), businessNumber = number.trim(), carModel = model.trim(), plateNumber = plate.trim()))
                showInfo("법인·차량 정보를 저장했어요.")
            }
        }
    }
}

@Composable private fun DriversCard(data: TripLogData, act: Act, repository: TripLogRepository, twoColumns: Boolean = false) {
    // 설정 › 직원 (2.11): the list and its editor, side by side once the card is 960 dp wide; see DriverManager.
    DriverManager(data, act, repository, twoColumns = twoColumns)
}

@Composable private fun CostCard(saved: FleetProfile, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit, twoColumns: Boolean = false) {
    var rate by rememberSaveable(saved.reimbursePerKmWon) { mutableStateOf(saved.reimbursePerKmWon.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by rememberSaveable(saved.annualCostWon) { mutableStateOf(saved.annualCostWon?.toString().orEmpty()) }
    val updated = saved.copy(reimbursePerKmWon = rate.toLongOrNull() ?: 0, annualCostWon = cost.toLongOrNull())
    val validation = runCatching { validateProfile(updated) }.exceptionOrNull()?.message
    Panel {
        Eyebrow("정산·세무", Scene.brand)
        SideBySide(twoColumns, 16.dp, { modifier -> Field("유류비 정산 단가 (km당)", rate, { if (it.length <= 6 && it.all(Char::isDigit)) rate = it }, modifier, number = true, suffix = "원",
            hint = rate.toLongOrNull()?.let(::formatWon) ?: "회사에서 정한 km당 단가") }) { modifier ->
            Field("연간 차량 관련 비용", cost, { if (it.length <= 11 && it.all(Char::isDigit)) cost = it }, modifier, number = true, suffix = "원", done = true, hint = cost.toLongOrNull()?.let(::formatWon))
        }
        validation?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        Muted("연간 비용은 감가상각비(또는 리스·렌트료), 유류비, 보험료, 자동차세, 수선비, 통행료 등의 합계입니다.")
        val changed = (rate.toLongOrNull() ?: 0) != saved.reimbursePerKmWon || cost.toLongOrNull() != saved.annualCostWon
        PrimaryAction("변경 내용 저장", Modifier.fillMaxWidth(), enabled = changed && validation == null) {
            act {
                repository.updateProfile(updated)
                showInfo("정산·세무 기준을 저장했어요.")
            }
        }
    }
}

@Composable private fun PrivacyCard(saved: FleetProfile, act: Act, repository: TripLogRepository, onTutorial: () -> Unit = {}) {
    Panel {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("기록 방식·개인정보", Scene.brand)
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onTutorial, modifier = Modifier.heightIn(min = 56.dp)) { Text("시연 체험 다시 보기") }
        }
        SwitchRow("출발·도착 주소 저장", "끄면 계기판 거리와 시각만 남기고 내비 주소는 저장하지 않아요.", saved.storeAddresses) { value ->
            act { repository.updateProfile(saved.copy(storeAddresses = value)) }
        }
        // 출발 전 운전자 확인 lives with the people it asks about, in 설정 › 직원 (DriverManager), since 2.11.
        SwitchRow("자동 완성 · 기본 켜짐", "반복 도착지의 목적과 카드 결제 비용을 사람 손 없이 채웁니다. 자동으로 들어간 항목은 ‘자동 분류’·‘카드 자동 매칭’으로 표시되고 언제든 되돌릴 수 있어요. 끄면 정차 후 직접 확인해요.", saved.autopilot) { value ->
            act { repository.updateProfile(saved.copy(autopilot = value)) }
        }
        SwitchRow("휴대폰 실시간 연동", "같은 Wi-Fi·핫스팟의 휴대폰 SHAREMATE와 암호화 연결로 운행·경비·특이사항을 실시간 공유해요. 휴대폰에서 바꾼 내용은 ‘휴대폰’ 출처로 차량에 기록돼요.", saved.phoneSync) { value ->
            act { repository.updateProfile(saved.copy(phoneSync = value)) }
        }
        SwitchRow("시연 모드", "실제 차량 대신 15배속 시연 주행으로 기록해요. 시연 기록은 따로 표시되고 실제 기록과 섞이지 않아요.", saved.demoMode) { value ->
            if (!value) { DemoDrive.setMoving(false); DemoDrive.setCharging(false) }
            act { repository.updateProfile(saved.copy(demoMode = value)) }
        }
        HorizontalDivider(color = Scene.divider)
        Muted("기록은 이 차량의 앱 저장소에 보관되고 백업·외부 서버로 전송하지 않아요. 내비 주소는 초기 연결, 출발·주차 전환과 운행 중에 조회하며 저장 여부는 위 설정을 따릅니다.")
        Muted("Gleo 음성 연동은 심사·호출자 검증 후 제공 예정입니다. 운행 목적과 비용은 정차 후 화면에서 기록해 주세요.")
    }
}

@Composable internal fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
        .heightIn(min = 72.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Muted(description)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
