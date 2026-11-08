package ai.sharemate.app

import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.semantics.liveRegion
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
    // Bumped by every goLog so the log page starts fresh even when its old instance is still fading out.
    var logGen by rememberSaveable { mutableIntStateOf(0) }
    // The log page keeps its own filter, month and selection while the driver browses; a button that opens it on something
    // specific drops that saved state first, so the list shows what the button promised.
    val goLog: (Int, YearMonth?, String?) -> Unit = { filter, month, tripId ->
        pageStates.removeState(1)
        logGen++
        logFilter = filter; logMonth = month?.toString(); expenseTripId = tripId; tab = 1
    }
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
    SceneTheme(hold = blocked) {
      CompositionLocalProvider(LocalFollowUp provides followUp) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // The layout classes of the window (gutter, content maximum, gaps) come from one measurement at the shell root.
            ProvideLayout(compactApp = false, Modifier.fillMaxSize().sceneBackdrop().safeDrawingPadding()) { layout ->
                val reduce = LocalReduceMotion.current
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
                                    // The 네 row leaves by folding up (200 ms) once the driver has answered; a snap under reduced motion. While it
                                    // folds it still shows the question it answered: the row renders nothing once today is confirmed, so it is
                                    // handed the answer set from before the tap until the fold is over.
                                    val confirmDue = driverConfirmDue(state.data, now, zone, driverConfirmed.split(',').filter { it.isNotBlank() }.toSet()) != null
                                    val asked = remember { LastShown<String>() }
                                    if (confirmDue) asked.value = driverConfirmed
                                    AnimatedVisibility(confirmDue,
                                        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)) + expandVertically(tween(200, easing = FastOutSlowInEasing)),
                                        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(200, easing = FastOutSlowInEasing)) + shrinkVertically(tween(200, easing = FastOutSlowInEasing))) {
                                        DriverConfirmRow(state, now, openDriver, asked.value ?: driverConfirmed, { driverConfirmed = it }, Modifier.padding(bottom = 12.dp))
                                    }
                                    val swapForward = pageSwap(forward = true)
                                    val swapBack = pageSwap(forward = false)
                                    AnimatedContent(targetState = tab, modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp, bottom = 20.dp),
                                        transitionSpec = { if (targetState > initialState) swapForward else swapBack }, label = "parked-page") { page ->
                                      pageStates.SaveableStateProvider(page) {
                                        when (page) {
                                            0 -> DrivePane(state, now, act, repository, openDriver, requestPermissions, { tab = 4 }, { tab = it }, openLog,
                                                { assistant = true }, snoozes, bannerCharges, { bannerCharges = it })
                                            1 -> key(logGen) { LogPane(state, now, act, repository, openDriver, expenseTripId, logFilter, logMonth) }
                                            2 -> ReportPane(state, now, pendingExportName != null, export, act, repository, { tab = 4 }, { tab = 0 }) { month -> openLog(1, month) }
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
            compact = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } < 1100.dp, initialText = assistantText)
        if (tutorial && !blocked) DemoTutorial(pendingExportName != null, export) { tutorial = false }
        val message = error ?: state.error
        if (message != null && !blocked) AlertDialog(onDismissRequest = { error = null; repository.clearError() }, title = { Text("확인이 필요해요") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { error = null; repository.clearError() }) { Text("확인") } })
        info?.takeUnless { blocked }?.let { text -> AlertDialog(onDismissRequest = { info = null }, title = { Text("저장 위치") }, text = { Text(text) }, confirmButton = { TextButton(onClick = { info = null }) { Text("확인") } }) }
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
    val target = when (tone) {
        PillTone.NEGATIVE -> Scene.negative
        PillTone.QUIET -> Scene.textMuted
        PillTone.POSITIVE -> Scene.positive
        PillTone.DEMO -> LocalPleosColors.current.gleoSpeaker
    }
    // A changed state tints the dot on the page clock (one transition, never a pulse); an instant swap under reduced motion.
    val dot by animateColorAsState(target, tabColorSpec(), label = "status-dot")
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

@Composable internal fun Eyebrow(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = color, letterSpacing = 0.6.sp)

@Composable internal fun Muted(text: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier,
        maxLines = maxLines, overflow = if (maxLines == Int.MAX_VALUE) TextOverflow.Clip else TextOverflow.Ellipsis)

@Composable internal fun PrimaryAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(20.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)) { Text(label, style = MaterialTheme.typography.labelLarge) }
}

/** [description] replaces a symbolic label such as "‹" for screen readers. */
@Composable internal fun QuietAction(label: String, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface,
    description: String? = null, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp),
        modifier = modifier.heightIn(min = 60.dp).then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        colors = ButtonDefaults.filledTonalButtonColors(containerColor = LocalPleosColors.current.surfaceHigh, contentColor = color),
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

/** The business-use ratio as a ring: the arc sweeps to its value ([rememberRingSweep]) and the label cross-fades; colours are read in composition. */
@Composable internal fun RatioRing(ratio: Double?, diameter: Dp, caption: String) {
    val track = Scene.ringTrack
    val arcColor = LocalPleosColors.current.chartActive
    val sweep = rememberRingSweep(ratio?.toFloat()?.coerceIn(0f, 1f) ?: 0f)
    Box(Modifier.size(diameter).semantics { contentDescription = "$caption ${ratioText(ratio)}" }, contentAlignment = Alignment.Center) {
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

/** How long after a trip ends the home keeps the task stack composed for its finished-trip card and a 다른 목적 choice made from it. */
private const val STACK_GRACE_MS = 10 * 60_000L

/**
 * Whether the parked task stack has anything it could show: a task, or the car's last finished trip within [graceMs] of its
 * end while it still carries an automatic purpose (its card, or the purpose choice opened from that card, may be up). The
 * stack keeps its own dismissals, so the home composes it only while this holds and an idle home pays the section gap once.
 */
internal fun taskStackHasContent(tasks: List<ParkedTask>, data: TripLogData, lastFinishedId: String?, now: Long, graceMs: Long = STACK_GRACE_MS): Boolean {
    if (tasks.isNotEmpty()) return true
    if (!data.profile.autopilot || lastFinishedId == null) return false
    val trip = data.trips.firstOrNull { it.id == lastFinishedId } ?: return false
    val end = trip.endAt ?: return false
    return trip.demo == data.profile.demoMode && trip.purposeSource == EditSource.AUTO && now - end in 0 until graceMs
}

/**
 * The car home (2.10): a city hero in the scene's light with the greeting, the assistant entry and three destinations
 * (차량 정보 · 운행일지 확인 · 정보 내보내기); under it whatever the car is waiting on, one card at a time in the order the
 * moment demands (safety, then money the car measured, then paperwork, then the manager's deadlines); then today and
 * this month as two widgets, and the one-tap incident row. Laid out by [homePlan] for the window's class and height.
 */
@Composable private fun DrivePane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository,
    openDriver: (String?) -> Unit, requestPermissions: () -> Unit, goSettings: () -> Unit, navigate: (Int) -> Unit, openLog: (Int, YearMonth?) -> Unit,
    assistant: () -> Unit, snoozes: MutableState<Set<String>>, banner: List<CardCharge> = emptyList(), onBanner: (List<CardCharge>) -> Unit = {}) {
    val layout = LocalLayout.current
    val wide = layout.expanded
    val data = state.data
    val today = localDate(now)
    val prompt = rememberChargePrompt(data)
    val taskStack: @Composable () -> Unit = {
        // The stack grows and shrinks with its card; the change reflows over 260 ms (a snap under reduced motion).
        ParkedTaskStack(state, now, wide, blocked = false, act, repository, openDriver, goSettings, modifier = Modifier.fillMaxWidth().animateContentSize(enterSpec(260)),
            chargePrompt = prompt.value, onWaitForCard = { prompt.value = ChargePrompt.WAIT }, snoozedCare = snoozes.value, showAllDone = false) { task ->
            when (task.kind) {
                TaskKind.MONTH_CLOSE -> monthCloseTarget(data, today, zone)?.let {
                    MonthCloseCard(it, onIssues = { openLog(1, it.month) }, onReviews = { navigate(0) }, onExport = { navigate(2) }, exporting = false, act = act, repository = repository)
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
    // The stack is composed only while it could show something, so an idle home pays the section gap once rather than around an
    // empty block; see taskStackHasContent for why a just-finished trip keeps it a while.
    val stackHasContent = taskStackHasContent(tasks, data, state.lastFinishedId, now)
    val live = state.live?.takeIf { it.demo == data.profile.demoMode && now - it.at in 0..45_000 }
    val (odometer, odometerLive) = careOdometer(state, now)
    val soc = live?.socPercent
    // The clock recomposes this pane every second; the card lines only change with the data, the readings and the day.
    val lines = remember(data, soc, odometer, odometerLive, today, snoozes.value) { homeCardLines(data, soc, odometer, odometerLive, today, zone, snoozes.value) }
    val cards = listOf(
        HomeCardSpec("차량 정보", lines.vehicle, R.drawable.ic_vehicle, openIncidents(data).size) { navigate(3) },
        HomeCardSpec("운행일지 확인", lines.log, R.drawable.ic_journal, lines.logPending) { openLog(if (lines.logPending > 0) 1 else 0, YearMonth.from(today)) },
        HomeCardSpec("정보 내보내기", lines.export, R.drawable.ic_export, 0) { navigate(2) },
    )
    val demoControls: @Composable () -> Unit = {
        if (data.profile.demoMode) DemoControls { act { repository.ingestCardEvents(listOf(demoCardApproval(repository.state.value.data, System.currentTimeMillis()))) } }
    }
    // The hero block staggers in once per session; a return to the tab or a sync lands in place.
    val entrance = rememberHomeEntrance()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val plan = homePlan(layout, LocalDensity.current.fontScale, maxHeight)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(layout.sectionGap)) {
            // The hero grows with its text (a permission button, a second greeting line); the change reflows over 260 ms.
            HomeHero(state, now, plan, assistant, requestPermissions, demoControls, cards, Modifier.animateContentSize(enterSpec(260)), entrance = entrance)
            MatchedBanner(banner, onShow = { onBanner(emptyList()); openLog(2, YearMonth.from(today)) }, onDismiss = { onBanner(emptyList()) })
            NextReservationLine(data = state.data, now = now)
            if (!closeCardShown) MonthCloseBannerHost(data, now, onOpen = { navigate(2) })
            if (stackHasContent) taskStack()
            HomeWidgets(state, now, sideBySide = plan.widgetsSideBySide, compact = plan.compactWidgets) { navigate(2) }
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
        "운전자(사용자) 등록" to data.drivers.isNotEmpty(),
        "현재 사용자 선택" to (data.currentDriverId != null),
    )
    Panel {
        Eyebrow("시작 설정 · ${steps.count { it.second }}/${steps.size}")
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
            if (data.drivers.isNotEmpty() && data.currentDriverId == null) QuietAction("사용자 선택", Modifier.weight(1f), onClick = pickDriver)
        }
    }
}



// ───────────────────────── 일지 ─────────────────────────

/** The three filters of the log list with their counts; the long 자동 반영 label only where the row has room. */
internal fun logFilterLabels(trips: Int, issues: Int, matched: Int, wide: Boolean): List<String> =
    listOf("전체 $trips", "점검 필요 $issues", if (wide) "자동 반영 $matched" else "자동 $matched")

/**
 * 운행기록: the month stepper, the 3-way filter and the totals on one 64-dp row, the employee chip under it, then the list
 * (560 wide at FULL) beside the detail (616). MEDIUM and below keep the full-width list and open the detail as a side sheet;
 * a short window folds the filters into one dropdown chip and trims the rows to 64 dp.
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
    val matchedRows = matchedTripsThisMonth(state.data, zone, localDate(now))
    val filters = logFilterLabels(trips.size, issues.size, matchedRows.size, wide)
    val totalsLine = "총 ${kmText(totals.totalKm)} · 업무 ${ratioText(totals.ratio)} · ${totals.trips}건"
    val employeeLabel = state.data.drivers.firstOrNull { it.id == employeeFilter }?.label ?: "전체 직원"
    val stepper: @Composable RowScope.() -> Unit = {
        QuietAction("‹", Modifier.width(64.dp), description = "이전 달") { month = period.minusMonths(1).toString(); selectedId = null }
        Text("${period.year}년 ${period.monthValue}월", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, maxLines = 1,
            modifier = Modifier.widthIn(min = 180.dp).semantics { heading() })
        QuietAction("›", Modifier.width(64.dp), enabled = period < YearMonth.from(localDate(now)), description = "다음 달") { month = period.plusMonths(1).toString(); selectedId = null }
    }
    val employeeItems: @Composable ColumnScope.(() -> Unit) -> Unit = { close ->
        DropdownMenuItem(text = { Text("전체 직원") }, onClick = { employeeFilter = null; selectedId = null; close() })
        state.data.drivers.forEach { driver -> DropdownMenuItem(text = { Text(driver.label) }, onClick = { employeeFilter = driver.id; selectedId = null; close() }) }
    }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            // A short window: the three filters and the employee fold into one 56-dp dropdown chip on the stepper's row.
            layout.short -> Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                stepper()
                DropdownChip("${filters[filter]} · $employeeLabel") { close ->
                    filters.forEachIndexed { index, label -> DropdownMenuItem(text = { Text(label) }, onClick = { filter = index; close() }) }
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
        val list: @Composable (Modifier) -> Unit = { modifier ->
            when (filter) {
                0 -> TripList(trips, state.data, issues, selectedId, modifier, compact = layout.short) { selectedId = it }
                1 -> IssueList(issues, state.data, modifier, act, repository, openDriver) { selectedId = it }
                else -> MatchedTripList(state.data, now, act, repository, modifier) { selectedId = it }
            }
        }
        if (wide) Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
            // FULL: list 560 + gap 24 + detail 616 fill the 1200-dp column; EXPANDED splits what it has.
            list(if (layout.full) Modifier.width(560.dp).fillMaxHeight() else Modifier.weight(1f).fillMaxHeight())
            Surface(Modifier.weight(1f).fillMaxHeight(), shape = RoundedCornerShape(32.dp), color = Scene.glass, border = Scene.edge) {
                if (selected == null) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Muted("운행을 선택하면 계기판 기록과 목적·비용·수정 이력을 볼 수 있어요.", Modifier.widthIn(max = 420.dp))
                } else TripDetail(selected, state.data, act, repository, openDriver) { selectedId = null }
            }
        } else Box(Modifier.weight(1f).fillMaxWidth()) {
            list(Modifier.fillMaxSize())
            SideSheet(selected, onDismiss = { selectedId = null }, widthFraction = if (layout.compact) .94f else .62f) { trip ->
                TripDetail(trip, state.data, act, repository, openDriver) { selectedId = null }
            }
        }
    }
}

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
 * lifts 24 dp and fades in (200 ms) and drops out (160 ms); a fade snap under reduced motion. Tapping the scrim closes it.
 */
@Composable private fun <T : Any> BoxScope.SideSheet(item: T?, onDismiss: () -> Unit, widthFraction: Float = .62f, content: @Composable (T) -> Unit) {
    val reduce = LocalReduceMotion.current
    val scrim = Scene.bg
    val lift = with(LocalDensity.current) { 24.dp.roundToPx() }
    val last = remember { LastShown<T>() }
    if (item != null) last.value = item
    val shown = last.value
    AnimatedVisibility(item != null, Modifier.matchParentSize(), enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)),
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(160, easing = FastOutLinearInEasing))) {
        Box(Modifier.fillMaxSize().background(scrim.copy(alpha = .55f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = "상세 닫기", onClick = onDismiss))
    }
    AnimatedVisibility(item != null, Modifier.align(Alignment.CenterEnd).fillMaxHeight().fillMaxWidth(widthFraction),
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(200, easing = FastOutSlowInEasing)) + slideInVertically(tween(200, easing = FastOutSlowInEasing)) { lift },
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(160, easing = FastOutLinearInEasing)) + slideOutVertically(tween(160, easing = FastOutLinearInEasing)) { lift }) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(32.dp), color = Scene.glass, border = Scene.edge) { if (shown != null) content(shown) }
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
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
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

/** One trip of the list (88 dp, or 64 when [compact]). The selected row carries the selection fill and ring, never colour alone; it settles to .98 while held. */
@Composable internal fun TripRow(trip: Trip, data: TripLogData, flagged: Boolean, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val shape = RoundedCornerShape(if (compact) 18.dp else 24.dp)
    val interaction = remember { MutableInteractionSource() }
    val noRipple = LocalLayout.current.startAnchored
    Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(shape).background(if (selected) Scene.selectionFill else Scene.glass)
        .then(if (selected) Modifier.border(1.5.dp, Scene.selectionRing, shape) else Modifier)
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
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                Eyebrow(dayLabel(localDate(trip.startAt)) + if (trip.demo) " · 시연 데이터" else "")
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
        Eyebrow("운행 목적")
        PurposeOptions(trip.purpose) { purpose -> if (purpose != trip.purpose) classify(trip, purpose, EditSource.DRIVER) }
        trip.purposeSource?.let { Muted("목적: ${trip.purpose.label} · ${it.label}") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Stat("④ 사용자", driver?.label ?: "미지정", Modifier.weight(1f))
            QuietAction("변경") { openDriver(trip.id) }
        }
        Eyebrow("운행 비용")
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
        Eyebrow("⑩ 비고")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Field("방문처·업무 내용", note, { if (it.length <= 300) note = it }, Modifier.weight(1f), done = true, hint = "${note.length} / 300")
            PrimaryAction("저장", enabled = note != trip.note) { act { repository.setNote(trip.id, note.trim()) } }
        }
        if (trip.history.isNotEmpty()) {
            Eyebrow("수정 이력")
            trip.history.takeLast(8).reversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if (!trip.open) TextButton(onClick = { deleting = true }, modifier = Modifier.heightIn(min = 56.dp)) { Text("이 기록 삭제", color = negative) }
    }
    if (deleting) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("운행 기록을 삭제할까요?") },
        text = { Text("삭제하면 계기판 거리가 끊겨 ‘기록되지 않은 주행’으로 표시될 수 있어요. 시동만 켠 기록이나 시연 기록만 삭제하는 것을 권장해요.") },
        confirmButton = { TextButton(onClick = { deleting = false; close(); act {
            repository.deleteTrip(trip.id)
            followUp(FollowUp("${kmText(trip.distanceKm)} 운행 기록을 삭제했어요", "실행 취소") { repository.restoreTrip(trip) })
        } }) { Text("삭제", color = negative) } },
        dismissButton = { TextButton(onClick = { deleting = false }) { Text("취소") } })
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

@Composable internal fun DriverPicker(data: TripLogData, tripId: String?, act: Act, repository: TripLogRepository, dismiss: () -> Unit, goSettings: () -> Unit) {
    val current = if (tripId == null) data.currentDriverId else (data.trips.firstOrNull { it.id == tripId } ?: data.open)?.driverId
    AlertDialog(onDismissRequest = dismiss, title = { Text(if (tripId == null) "지금 운전하는 사용자" else "이 운행의 사용자") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (data.drivers.isEmpty()) Text("등록된 사용자가 없어요. 설정에서 부서·성명을 추가해 주세요.")
            data.drivers.forEach { driver ->
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).selectable(selected = driver.id == current, role = Role.RadioButton) {
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

/** Two blocks of a page: side by side with [gap] between them when [sideBySide], one under the other otherwise. */
@Composable private fun SideBySide(sideBySide: Boolean, gap: Dp, first: @Composable (Modifier) -> Unit, second: @Composable (Modifier) -> Unit) {
    if (sideBySide) Row(horizontalArrangement = Arrangement.spacedBy(gap)) { first(Modifier.weight(1f)); second(Modifier.weight(1f)) }
    else Column(verticalArrangement = Arrangement.spacedBy(gap)) { first(Modifier); second(Modifier) }
}

/** The bar pinned under the Excel preview (72 dp, max 760): one line of context and the save action, on glass. */
@Composable private fun SaveBar(hint: String, modifier: Modifier = Modifier, action: @Composable () -> Unit) {
    Surface(modifier.widthIn(max = 760.dp).fillMaxWidth(), shape = RoundedCornerShape(22.dp), color = Scene.glass, border = Scene.edge) {
        Row(Modifier.heightIn(min = 72.dp).padding(start = 22.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Muted(hint, Modifier.weight(1f), maxLines = 2)
            action()
        }
    }
}

/**
 * 월간·Excel: the period choice (480 wide) with Excel 저장 at the column's right, the month-close card (max 760) while this
 * month is chosen, the four views as 56-dp tabs (max 960), then the view. The Excel view keeps its save bar (72 dp, max 760)
 * pinned under the preview; the two cards of the other views sit side by side from EXPANDED up and stack below it.
 */
@Composable private fun ReportPane(state: TripLogState, now: Long, exporting: Boolean, saveFile: (String, String, ByteArray) -> Unit,
    act: Act, repository: TripLogRepository, goSettings: () -> Unit, goHome: () -> Unit, goReview: (YearMonth) -> Unit) {
    val layout = LocalLayout.current
    val wide = layout.expanded
    val data = state.data
    var period by rememberSaveable { mutableIntStateOf(0) }
    var view by rememberSaveable { mutableIntStateOf(2) }
    var confirmExport by remember { mutableStateOf(false) }
    val today = localDate(now)
    val from = if (period == 0) today.withDayOfMonth(1) else today.withDayOfYear(1)
    val trips = tripsIn(data.trips, from, today, zone)
    val totals = logbookTotals(trips)
    val yearTotals = logbookTotals(tripsIn(data.trips, today.withDayOfYear(1), today, zone))
    val exportRange: (LocalDate, LocalDate) -> Unit = { start, end ->
        val plate = data.profile.plateNumber.ifBlank { "차량" }.replace(Regex("[^\\p{L}\\p{N}-]"), "_")
        val name = "${if (data.profile.demoMode) "시연_" else ""}운행기록부_${plate}_${start}_${end}.xlsx"
        val bytes = logbookXlsx(data.copy(profile = data.profile.copy(carModel = data.profile.carModel.ifBlank { state.vehicleModel.orEmpty() })), start, end,
            tripsIn(data.trips, start, end, zone), zone = zone)
        saveFile(name, XLSX_MIME, bytes)
    }
    val export: () -> Unit = { exportRange(from, today) }
    // The close panel follows the month the home card and the badge point at: last month while it is still open, else this one.
    val closeStatus = monthCloseTarget(data, today, zone) ?: monthStatus(data, YearMonth.from(today), zone)
    val closeEnd = closeStatus.month.atEndOfMonth().let { if (it.isAfter(today)) today else it }
    val exportClose: () -> Unit = { exportRange(closeStatus.month.atDay(1), closeEnd) }
    val ratio: @Composable (Modifier) -> Unit = { modifier -> RatioCard(totals, modifier, compact = !wide) }
    val tax: @Composable (Modifier) -> Unit = { modifier -> TaxCard(data.profile, yearTotals, modifier, goSettings) }
    val settle: @Composable (Modifier) -> Unit = { modifier -> SettlementCard(trips, data, modifier, goSettings) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The period row and the month-close card are one block: the card comes with 이번 달 and goes with 올해, the block
        // reflows over 260 ms (a snap under reduced motion) and an absent card costs no gap.
        Column(Modifier.fillMaxWidth().animateContentSize(enterSpec(260)), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // 이번 달 · 올해 keeps to 480 at the start of the row; Excel 저장 sits at the column's right.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Segmented(listOf("이번 달", "올해"), period, Modifier.weight(1f, fill = false).widthIn(max = 480.dp)) { period = it }
                LoadingAction("Excel 저장", loading = exporting, modifier = Modifier.padding(start = 16.dp), loadingLabel = "만드는 중…", enabled = trips.isNotEmpty()) {
                    if (totals.unclassifiedKm > 0 || trips.any { it.driverId == null }) confirmExport = true else export()
                }
            }
            if (period == 0) MonthClosePanel(closeStatus, onIssues = { goReview(closeStatus.month) }, onReviews = goHome, onExport = exportClose, exporting = exporting,
                act = act, repository = repository, modifier = Modifier.widthIn(max = 760.dp), compact = !wide, editedAfterClose = editedAfterCloseCount(data, closeStatus.month, zone))
        }
        Segmented(listOf("운행 현황", "경비·정산", "Excel", "검토"), view, Modifier.widthIn(max = 960.dp)) { view = it }
        Text("${if (data.profile.demoMode) "시연 데이터 · " else ""}${from} ~ ${today} · ${totals.trips}건", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Crossfade(view, Modifier.weight(1f).fillMaxWidth(), animationSpec = pageFade(), label = "report-section") { page ->
            if (page == 2) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExcelPreview(data.copy(profile = data.profile.copy(carModel = data.profile.carModel.ifBlank { state.vehicleModel.orEmpty() })), trips, from, today,
                    Modifier.weight(1f).fillMaxWidth(), compact = !wide)
                // The save bar stays put under the preview while the sheets scroll above it.
                SaveBar("휴대폰 SHAREMATE에서 여는 운행 파일이에요") {
                    LoadingAction("휴대폰용 운행파일 저장", loading = exporting, loadingLabel = "만드는 중…", enabled = data.trips.isNotEmpty()) {
                        saveFile("SHAREMATE_운행기록.smlog", LOGBOOK_MIME, TripLogCodec.encode(data.copy(open = null)))
                    }
                }
            } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                when (page) {
                    0 -> SideBySide(wide, layout.cardGap, ratio) { modifier -> EmployeeUsageCard(trips, data, modifier) }
                    1 -> SideBySide(wide, layout.cardGap, { modifier -> ExpenseOverview(trips, modifier) }, settle)
                    else -> {
                        // Reading panels keep to the month-close card's measure (760) instead of spanning the rail.
                        Panel(Modifier.widthIn(max = 760.dp)) {
                            Eyebrow("증빙 준비 상태")
                            val issues = auditTrips(trips)
                            Text(if (issues.isEmpty() && trips.isNotEmpty()) "기록 기본 점검 완료" else "확인이 필요한 항목 ${issues.size}개", style = MaterialTheme.typography.titleLarge)
                            Muted("직원·운행 목적·계기판 거리의 연결을 점검합니다. Excel 확인사항 시트에서 세부 항목을 볼 수 있어요.")
                            Muted("국세청 운행기록부 항목 참고 · 제출 전 검토용. 영수증과 세무 요건은 별도로 확인해야 합니다.")
                        }
                        tax(Modifier.widthIn(max = 760.dp))
                    }
                }
            }
        }
    }
    if (confirmExport) AlertDialog(onDismissRequest = { confirmExport = false }, title = { Text("정리되지 않은 운행이 있어요") },
        text = { Text("미분류 ${kmText(totals.unclassifiedKm)}는 업무용 사용거리에서 제외되고, 사용자 미지정 운행은 ④란이 비어 있게 저장돼요. 그래도 저장할까요?") },
        confirmButton = { TextButton(onClick = { confirmExport = false; export() }) { Text("저장") } },
        dismissButton = { TextButton(onClick = { confirmExport = false; goReview(YearMonth.from(today)) }) { Text("정리하러 가기") } })
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
            HorizontalDivider(color = Scene.divider)
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

@Composable private fun RatioCard(totals: LogbookTotals, modifier: Modifier, compact: Boolean = false) {
    val pleos = LocalPleosColors.current
    // The bar tracks share the ring's track token so they read on day glass as well as night.
    val track = Scene.ringTrack
    Panel(modifier.fillMaxHeight()) {
        Eyebrow("업무사용비율 ⑬ = ⑫ ÷ ⑪")
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

@Composable private fun TaxCard(profile: FleetProfile, year: LogbookTotals, modifier: Modifier, goSettings: () -> Unit) {
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
                Eyebrow("PLEOS SDK / 필요한 신호만 사용")
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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
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
        Eyebrow("FLEET PROFILE")
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
    val followUp = LocalFollowUp.current
    var department by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<Driver?>(null) }
    Panel {
        Eyebrow("사용자 · ④ 부서와 성명")
        if (data.drivers.isEmpty()) Muted("차량을 이용하는 직원을 등록하세요. 운행마다 사용자가 기록됩니다.")
        data.drivers.forEach { driver ->
            Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).selectable(selected = driver.id == data.currentDriverId, role = Role.RadioButton) {
                    act { repository.selectDriver(driver.id) }
                }.heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = driver.id == data.currentDriverId, onClick = null, modifier = Modifier.padding(start = 8.dp))
                    Text(driver.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                }
                TextButton(onClick = { editing = driver; department = driver.department; name = driver.name },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "${driver.label} 수정" }) { Text("수정") }
                TextButton(onClick = { act { repository.removeDriver(driver.id); followUp(FollowUp("${driver.name} 님을 삭제했어요", "실행 취소") { repository.restoreDriver(driver) }) } },
                    modifier = Modifier.heightIn(min = 56.dp).semantics { contentDescription = "${driver.label} 삭제" }) { Text("삭제", color = Scene.negative) }
            }
        }
        SideBySide(twoColumns, 12.dp, { modifier -> Field("부서", department, { if (it.length <= 30) department = it }, modifier) }) { modifier ->
            Field("성명", name, { if (it.length <= 30) name = it }, modifier)
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

@Composable private fun CostCard(saved: FleetProfile, act: Act, repository: TripLogRepository, showInfo: (String) -> Unit, twoColumns: Boolean = false) {
    var rate by rememberSaveable(saved.reimbursePerKmWon) { mutableStateOf(saved.reimbursePerKmWon.takeIf { it > 0 }?.toString().orEmpty()) }
    var cost by rememberSaveable(saved.annualCostWon) { mutableStateOf(saved.annualCostWon?.toString().orEmpty()) }
    val updated = saved.copy(reimbursePerKmWon = rate.toLongOrNull() ?: 0, annualCostWon = cost.toLongOrNull())
    val validation = runCatching { validateProfile(updated) }.exceptionOrNull()?.message
    Panel {
        Eyebrow("정산·세무")
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
            Eyebrow("기록 방식·개인정보")
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onTutorial, modifier = Modifier.heightIn(min = 48.dp)) { Text("시연 체험 다시 보기") }
        }
        SwitchRow("출발·도착 주소 저장", "끄면 계기판 거리와 시각만 남기고 내비 주소는 저장하지 않아요.", saved.storeAddresses) { value ->
            act { repository.updateProfile(saved.copy(storeAddresses = value)) }
        }
        SwitchRow("출발 전 운전자 확인", "직원이 2명 이상이면 하루 한 번 현재 운전자가 맞는지 물어요 · 기본 켜짐", saved.confirmDriverDaily) { value ->
            act { repository.updateProfile(saved.copy(confirmDriverDaily = value)) }
        }
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
