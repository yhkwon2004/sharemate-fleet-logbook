package ai.sharemate.app

import androidx.core.content.edit
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

/*
 * The phone shell: five tabs (홈 · 예약 · 운행 · 경비 · 현황), a small stack of full-screen routes on top (설정, 운행 상세,
 * 직원 드릴다운, 차량 정보), one snackbar channel for every confirmation, and the pull-to-refresh and offline banner that tell
 * the truth about the link. Routing is a sealed class and a saved list, not a navigation library; the back button
 * pops the list. Every edit still goes to the car as a SyncCommand and comes back through the mirrored copy; the
 * shell only remembers which purposes it just sent so the row can show them before the mirror catches up.
 * 2.10: the root provides LocalLayout; every frame keeps to the class's content width, centred, and in a short landscape
 * window the tab pill becomes a left rail. The tab pages run on under the floating pill: the shell pads them at the top and the
 * sides only and provides LocalNavInset, which each tab adds to the end of its own scroll.
 */

/** Where the phone is, on top of the tab row. [Tab] is the bottom of the stack and is never stored in it. */
sealed class PhoneRoute {
    data object Tab : PhoneRoute()
    data object Settings : PhoneRoute()
    data class TripDetail(val id: String) : PhoneRoute()
    data class Employee(val id: String) : PhoneRoute()
    data object Vehicle : PhoneRoute()
}

internal fun encodeRoute(route: PhoneRoute): String = when (route) {
    PhoneRoute.Tab -> "tab"
    PhoneRoute.Settings -> "settings"
    is PhoneRoute.TripDetail -> "trip:${route.id}"
    is PhoneRoute.Employee -> "employee:${route.id}"
    PhoneRoute.Vehicle -> "vehicle"
}

internal fun decodeRoute(text: String): PhoneRoute? = when {
    text == "tab" -> PhoneRoute.Tab
    text == "settings" -> PhoneRoute.Settings
    text == "vehicle" -> PhoneRoute.Vehicle
    text.startsWith("trip:") -> PhoneRoute.TripDetail(text.removePrefix("trip:"))
    text.startsWith("employee:") -> PhoneRoute.Employee(text.removePrefix("employee:"))
    else -> null
}

/** A purpose the phone sent and has not yet seen come back; [revision] is the phone copy's dataRevision at send time. */
data class PendingEdit(val purpose: TripPurpose?, val sentAt: Long, val revision: Long)

/** Everything the shell keeps across rotation: the tab, the route stack, the filters, and what is in flight. */
data class PhoneUiState(
    val tab: Int = 0,
    val routes: List<PhoneRoute> = emptyList(),
    val employeeFilter: String? = null,
    val month: String = YearMonth.now().toString(),
    val pendingEdits: Map<String, PendingEdit> = emptyMap(),
    val dismissedBanners: Set<String> = emptySet(),
) {
    val route: PhoneRoute get() = routes.lastOrNull() ?: PhoneRoute.Tab
    fun push(route: PhoneRoute) = copy(routes = if (routes.lastOrNull() == route) routes else routes + route)
    fun pop() = copy(routes = routes.dropLast(1))
}

internal val PhoneUiStateSaver: Saver<PhoneUiState, Any> = listSaver(
    save = { s -> listOf(s.tab, s.routes.map(::encodeRoute), s.employeeFilter ?: "\u0000", s.month,
        s.pendingEdits.map { (id, e) -> "$id|${e.purpose?.name ?: ""}|${e.sentAt}|${e.revision}" }, s.dismissedBanners.toList()) },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        PhoneUiState(tab = list[0] as Int, routes = (list[1] as List<String>).mapNotNull(::decodeRoute),
            employeeFilter = (list[2] as String).takeIf { it != "\u0000" }, month = list[3] as String,
            pendingEdits = (list[4] as List<String>).mapNotNull { line ->
                val parts = line.split('|'); if (parts.size != 4) null
                else parts[0] to PendingEdit(parts[1].takeIf { it.isNotEmpty() }?.let { runCatching { TripPurpose.valueOf(it) }.getOrNull() }, parts[2].toLongOrNull() ?: 0, parts[3].toLongOrNull() ?: 0)
            }.toMap(), dismissedBanners = (list[5] as List<String>).toSet())
    })

internal enum class PhoneTab(val label: String, val icon: Int, val badgeLabel: String) {
    HOME("홈", R.drawable.ic_dashboard, "할 일"), BOOK("예약", R.drawable.ic_calendar, "예약"), TRIPS("운행", R.drawable.ic_journal, "미분류"),
    EXPENSES("경비", R.drawable.ic_receipt, "확인 필요"), REPORT("현황", R.drawable.ic_report, "확인사항")
}

/** The trips the phone shows: the copy filtered to the car's current mode (real or demo). */
internal fun phoneTrips(data: TripLogData): List<Trip> = data.trips.filter { it.demo == data.profile.demoMode }

internal fun phoneHasWorkspace(data: TripLogData, paired: Boolean): Boolean =
    paired || data.trips.isNotEmpty() || data.open != null || data.profile.plateNumber.isNotBlank()

/**
 * Whether a frame runs on under the tab bar: only the tab pages (a workspace on the tab route), which pad the end of their own
 * scroll by LocalNavInset. The full-screen routes and the connect screen have no bar and keep clear of the bottom inset.
 */
internal fun phoneFrameUnderBar(route: PhoneRoute, hasLog: Boolean): Boolean = route == PhoneRoute.Tab && hasLog

internal suspend fun sendAndConfirm(command: SyncCommand, send: suspend (SyncCommand) -> Unit, confirmed: () -> Unit) {
    send(command)
    confirmed()
}

/** Filter semantics shared by every tab: null = everyone, "" = trips without a driver, otherwise one driver id. */
internal fun matchesEmployee(trip: Trip, filter: String?) = filter == null || trip.driverId.orEmpty() == filter

/** What the home stack will ask for, counted the car's way: open incidents, card questions, a charge waiting for a price, unclassified trips. */
internal fun phoneHomeTasks(data: TripLogData): Int {
    val demo = data.profile.demoMode
    return openIncidents(data).size + data.charges.count { it.state == ChargeState.REVIEW && it.demo == demo } +
        (if (data.pendingCharge?.let { it.demo == demo } == true) 1 else 0) + phoneTrips(data).count { it.purpose == TripPurpose.UNCLASSIFIED && !it.open }
}

/** Tab badges in [PhoneTab] order, real counts only: home = tasks, booking = none (a booking needs no action), trips = unclassified, expenses = card reviews, report = audit issues in the viewed month. */
internal fun phoneBadges(data: TripLogData, month: YearMonth, zone: ZoneId): List<Int> {
    val demo = data.profile.demoMode
    val trips = phoneTrips(data)
    val monthTrips = tripsIn(trips, month.atDay(1), month.atEndOfMonth(), zone)
    val ids = monthTrips.map { it.id }.toSet()
    return listOf(phoneHomeTasks(data), 0, trips.count { it.purpose == TripPurpose.UNCLASSIFIED && !it.open },
        data.charges.count { it.state == ChargeState.REVIEW && it.demo == demo }, auditTrips(trips).count { it.tripId in ids })
}

/** Drops in-flight edits the mirror has settled; returns the edits still pending and the ids where the car decided differently. */
internal fun reconcilePendingEdits(pending: Map<String, PendingEdit>, data: TripLogData, now: Long, staleMillis: Long = 45_000): Pair<Map<String, PendingEdit>, List<String>> {
    val conflicts = mutableListOf<String>()
    val kept = pending.filter { (id, edit) ->
        val trip = (data.trips + listOfNotNull(data.open)).firstOrNull { it.id == id }
        when {
            trip == null -> false
            trip.purpose == edit.purpose -> false
            trip.updatedAt > edit.sentAt -> { conflicts += id; false }
            now - edit.sentAt > staleMillis -> false
            else -> true
        }
    }
    return kept to conflicts
}

/** "방금" / "3분 전" / "14:02" for the last time the car answered; nothing when it never has. */
internal fun lastSeenText(lastSeenAt: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String? {
    if (lastSeenAt <= 0) return null
    val minutes = ((now - lastSeenAt) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> "방금"
        minutes < 60 -> "${minutes}분 전"
        else -> Instant.ofEpochMilli(lastSeenAt).atZone(zone).let { "%02d:%02d".format(it.hour, it.minute) }
    }
}

/** Per-phone UI memory (filters, one-time hints); never the log. */
internal fun phoneUiPrefs(context: Context) = context.getSharedPreferences("phone-ui", Context.MODE_PRIVATE)
private const val PREF_EMPLOYEE = "employeeFilter"
private const val PREF_SWIPE_HINT = "swipeHintShown"

internal fun rememberedEmployeeFilter(context: Context): String? = phoneUiPrefs(context).getString(PREF_EMPLOYEE, "\u0000")?.takeIf { it != "\u0000" }
internal fun rememberEmployeeFilter(context: Context, filter: String?) { phoneUiPrefs(context).edit { putString(PREF_EMPLOYEE, filter ?: "\u0000") } }
internal fun swipeHintShown(context: Context) = phoneUiPrefs(context).getBoolean(PREF_SWIPE_HINT, false)
internal fun markSwipeHintShown(context: Context) { phoneUiPrefs(context).edit { putBoolean(PREF_SWIPE_HINT, true) } }

/** Arguments the shell hands to the 경비 tab (built by PhoneExpensesTab in PhoneExpenses.kt). */
internal class PhoneExpensesArgs(val data: TripLogData, val month: YearMonth, val employeeFilter: String?, val paired: Boolean, val busy: Boolean,
    val send: (SyncCommand) -> Unit, val cardAlerts: Boolean, val queued: Int, val openAlertSettings: () -> Unit, val zone: ZoneId)

/** Arguments the shell hands to the 현황 tab (built by PhoneReportTab in PhoneReport.kt). */
internal class PhoneReportArgs(val data: TripLogData, val month: YearMonth, val onMonth: (YearMonth) -> Unit, val employeeFilter: String?, val paired: Boolean,
    val busy: Boolean, val send: (SyncCommand) -> Unit, val onOpenTrip: (String) -> Unit, val onOpenEmployee: (String) -> Unit, val exportXlsx: suspend (List<Trip>, String) -> Unit)

/** Arguments the shell hands to the 예약 tab (built by PhoneReservationsTab). */
internal class PhoneBookingArgs(val data: TripLogData, val paired: Boolean, val connected: Boolean, val busy: Boolean, val now: Long, val send: (SyncCommand, () -> Unit) -> Unit)

/** What the activity owns and the shell borrows: the sync client, the launchers, and the two tabs built elsewhere. */
internal class PhoneHost(
    val repository: TripLogRepository,
    val sync: PhoneSyncClient,
    val capture: (String) -> Unit,
    val fetchPhoto: (String) -> Unit,
    val importStatement: () -> Unit,
    val pickFile: () -> Unit,
    val exportXlsx: suspend (List<Trip>, String) -> Unit,
    val openAlertSettings: () -> Unit,
    val pair: suspend (String, String, String) -> Unit,
    val unpair: suspend () -> Unit,
    val expensesTab: @Composable (PhoneExpensesArgs) -> Unit,
    val reportTab: @Composable (PhoneReportArgs) -> Unit,
    /** The local assistant sheet, when wired: receives close and open-trip callbacks. Null hides the 도우미 button. */
    val assistant: (@Composable (onClose: () -> Unit, onOpenTrip: (String) -> Unit) -> Unit)? = null,
    /** The 예약 tab body. */
    val bookingTab: @Composable (PhoneBookingArgs) -> Unit = { PhoneEmpty("예약", detail = "차량과 연결하면 예약할 수 있어요") },
    /** The company-vehicle picker shown at the head of every tab; null keeps the plain brand line. */
    val vehicleSwitcher: (@Composable () -> Unit)? = null,
    /** The signed-in account card at the top of 설정. */
    val accountCard: (@Composable () -> Unit)? = null,
)

/** Actions the shell exposes to its tabs; each sends a command, remembers it as pending where a row can show it, and offers undo. */
internal class PhoneCommands(
    val send: (SyncCommand) -> Unit,
    val classify: (Trip, TripPurpose) -> Unit,
    val classifyMany: (List<Trip>, TripPurpose) -> Unit,
    val pickDriver: (List<String>) -> Unit,
    val openTrip: (String) -> Unit,
    val openSettings: () -> Unit,
    val openTab: (Int) -> Unit,
    /** Opens 현황 on [YearMonth], so a card or widget that names a month lands on that month. */
    val openReport: (YearMonth) -> Unit = {},
)

private data class RouteFrame(val route: PhoneRoute, val depth: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun PhoneShell(state: TripLogState, link: PhoneLink, paired: Boolean, host: PhoneHost, feedback: Pair<SnackbarHostState, (FollowUp) -> Unit>,
    cardAlerts: Boolean, queued: Int, photoVersion: Int, hostBusy: Boolean = false) {
    val context = LocalContext.current
    val zone = remember { ZoneId.systemDefault() }
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val (snackbar, followUp) = feedback
    val data = state.data
    var ui by rememberSaveable(stateSaver = PhoneUiStateSaver) { mutableStateOf(PhoneUiState(employeeFilter = rememberedEmployeeFilter(context))) }
    val pageStates = rememberSaveableStateHolder()
    var inFlight by remember { mutableIntStateOf(0) }
    val busy = hostBusy || inFlight > 0
    val latestState by rememberUpdatedState(state)
    val month = remember(ui.month) { runCatching { YearMonth.parse(ui.month) }.getOrDefault(YearMonth.now()) }
    val hasLog = phoneHasWorkspace(data, paired)
    val now = System.currentTimeMillis()

    // One place sends every command: a failure is a bar with the same command behind "다시 시도", a refusal is the car's own sentence.
    val sendSuspend: suspend (SyncCommand) -> Unit = { command -> host.sync.send(command) }
    fun dropPending(command: SyncCommand) {
        val ids = when (command) { is SyncCommand.Classify -> listOf(command.tripId); is SyncCommand.ClassifyMany -> command.tripIds; else -> emptyList() }
        if (ids.isNotEmpty()) ui = ui.copy(pendingEdits = ui.pendingEdits - ids.toSet())
    }
    val sendConfirmed: (SyncCommand, () -> Unit) -> Unit = { command, confirmed ->
        scope.launch {
            inFlight++
            try { sendAndConfirm(command, sendSuspend, confirmed) }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { dropPending(command); followUp(FollowUp(e.message ?: "차량이 변경을 받지 않았어요.")) }
            catch (_: Exception) { dropPending(command); followUp(sendFailureFollowUp { sendAndConfirm(command, sendSuspend, confirmed) }) }
            finally { inFlight-- }
        }
    }
    val send: (SyncCommand) -> Unit = { sendConfirmed(it, {}) }
    fun markPending(ids: List<String>, purpose: TripPurpose) {
        val stamp = System.currentTimeMillis()
        ui = ui.copy(pendingEdits = ui.pendingEdits + ids.associateWith { PendingEdit(purpose, stamp, latestState.dataRevision) })
    }
    val classify: (Trip, TripPurpose) -> Unit = { trip, purpose ->
        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
        val others = bulkClassifyCandidates(phoneTrips(latestState.data), trip, zone)
        markPending(listOf(trip.id), purpose)
        send(SyncCommand.Classify(trip.id, purpose))
        followUp(if (others.isEmpty()) FollowUp("${withRo(purpose.label)} 기록했어요", "실행 취소") {
            markPending(listOf(trip.id), trip.purpose); sendSuspend(SyncCommand.Classify(trip.id, trip.purpose))
        } else FollowUp("${withRo(purpose.label)} 기록 · 같은 날 미분류 ${others.size}건", "${others.size}건도 같게") {
            markPending(others.map { it.id }, purpose); sendSuspend(SyncCommand.ClassifyMany(others.map { it.id }, purpose))
        })
    }
    val classifyMany: (List<Trip>, TripPurpose) -> Unit = { trips, purpose ->
        if (trips.isNotEmpty()) {
            haptic.performHapticFeedback(HapticFeedbackType.Confirm)
            markPending(trips.map { it.id }, purpose)
            send(SyncCommand.ClassifyMany(trips.map { it.id }, purpose))
            followUp(FollowUp("${trips.size}건을 ${withRo(purpose.label)} 기록했어요", "실행 취소") {
                trips.groupBy { it.purpose }.forEach { (previous, group) -> markPending(group.map { it.id }, previous); sendSuspend(SyncCommand.ClassifyMany(group.map { it.id }, previous)) }
            })
        }
    }
    var driverFor by remember { mutableStateOf<List<String>?>(null) }
    val commands = PhoneCommands(send, classify, classifyMany, pickDriver = { driverFor = it }, openTrip = { ui = ui.push(PhoneRoute.TripDetail(it)) },
            openSettings = { ui = ui.push(PhoneRoute.Settings) }, openTab = { ui = ui.copy(tab = it.coerceIn(0, PhoneTab.entries.lastIndex), routes = emptyList()) },
            openReport = { ui = ui.copy(tab = PhoneTab.REPORT.ordinal, month = it.toString(), routes = emptyList()) })

    // The mirror settles what the phone sent: drop what matches, say so when the car chose differently, forget what went stale.
    LaunchedEffect(state.dataRevision, ui.pendingEdits.size) {
        if (ui.pendingEdits.isEmpty()) return@LaunchedEffect
        val (kept, conflicts) = reconcilePendingEdits(ui.pendingEdits, state.data, System.currentTimeMillis())
        if (kept.size != ui.pendingEdits.size) ui = ui.copy(pendingEdits = kept)
        if (conflicts.isNotEmpty()) followUp(FollowUp("차량에서 먼저 바뀌었어요 · 차량 기록을 보여 줄게요"))
        if (kept.isNotEmpty()) { delay(5_000); ui = ui.copy(pendingEdits = reconcilePendingEdits(ui.pendingEdits, latestState.data, System.currentTimeMillis()).first) }
    }
    LaunchedEffect(ui.employeeFilter) { rememberEmployeeFilter(context, ui.employeeFilter) }
    // A route to a trip that left the mirror cannot stay open.
    LaunchedEffect(data.trips.size, ui.routes) {
        val top = ui.route
        if (top is PhoneRoute.TripDetail && data.trips.none { it.id == top.id } && data.open?.id != top.id) { delay(1_600); if (ui.route == top) ui = ui.pop() }
    }
    BackHandler(enabled = ui.routes.isNotEmpty()) { ui = ui.pop() }

    // Pull to refresh: a real fetch with a real answer, or the file picker when there is no car to ask.
    var refreshing by remember { mutableStateOf(false) }
    val refresh: () -> Unit = {
        if (!paired) host.pickFile()
        else if (!refreshing) scope.launch {
            refreshing = true
            val ok = try { host.sync.refreshNow() } catch (e: CancellationException) { throw e } catch (_: Exception) { false }
            refreshing = false
            followUp(FollowUp(if (ok) "차량 기록을 방금 받았어요" else "차량에 닿지 않아요" + (lastSeenText(host.sync.link.value.lastSeenAt, System.currentTimeMillis(), zone)?.let { " · 마지막 수신 $it" } ?: "")))
        }
    }
    // The offline banner waits ten seconds so a slow poll does not flash it, and comes back by itself after a reconnect.
    var offline by remember { mutableStateOf(false) }
    LaunchedEffect(paired, link.connected) {
        if (paired && !link.connected) { delay(10_000); offline = true } else { offline = false; if (ui.dismissedBanners.contains("offline")) ui = ui.copy(dismissedBanners = ui.dismissedBanners - "offline") }
    }
    val minute by produceState(0) { while (true) { delay(60_000); value = value + 1 } }
    val lastSeen = remember(link.lastSeenAt, minute, offline) { lastSeenText(link.lastSeenAt, System.currentTimeMillis(), zone) }

    var assistantOpen by rememberSaveable { mutableStateOf(false) }
    val badges = remember(data, month) { phoneBadges(data, month, zone) }
    val navTabs = remember(badges) { PhoneTab.entries.mapIndexed { index, tab -> NavTab(tab.label, tab.icon, badges[index]) } }
    val reduce = LocalReduceMotion.current
    val swapForward = pageSwap(forward = true)
    val swapBack = pageSwap(forward = false)
    // Where the greeting ends (root px), so the day haze stops just under it.
    var greetingBottom by remember { mutableFloatStateOf(0f) }
    // Where the home's words and cards rest and where the header icons sit (root px), so the sun or moon only shows in free sky.
    val skyShapes = remember { mutableStateMapOf<PhoneSkyShape, Rect>() }
    val reportSkyShape = remember { { shape: PhoneSkyShape, rect: Rect -> if (skyShapes[shape] != rect) skyShapes[shape] = rect } }
    var orbClear by remember { mutableStateOf<Boolean?>(null) }

    CompositionLocalProvider(LocalFollowUp provides followUp) {
        // 2.8: the phone shares the car's backdrop; the scaffold is transparent so the glow and the city show through.
        // 2.10: the root measures the window once and provides the layout tokens every frame reads.
        ProvideLayout(compactApp = true, Modifier.fillMaxSize().sceneBackdrop()) { tokens ->
            val showTabs = hasLog && ui.routes.isEmpty()
            val rail = showTabs && phoneUsesRail(maxWidth, maxHeight)
            val homeShown = hasLog && ui.route == PhoneRoute.Tab && ui.tab == PhoneTab.HOME.ordinal
            // One background layer for every screen: the city at full strength behind home, dimmed behind the other
            // tabs and full-screen routes, moving on the page clock so header, nav and backdrop never jump between screens.
            val skyLevel = if (!hasLog) .34f else if (homeShown) 1f else .30f
            val skyAlpha by animateFloatAsState(skyLevel, if (reduce) snap() else tween(MOTION_PAGE_MS), label = "phone-sky")
            // The sun or moon belongs to the home and to free sky: it stays out on the other screens and wherever it would touch the
            // greeting's words, the cards or the header icons. A change waits until those have rested for PHONE_ORB_SETTLE_MS, so a
            // page swap sliding the words past it never flashes it; the first answer is immediate.
            val density = LocalDensity.current
            val orbAt = phoneOrbAt(maxWidth, maxHeight, density.fontScale)
            val orbCenter = with(density) { Offset(maxWidth.toPx() * orbAt.x, maxHeight.toPx() * orbAt.y) }
            val orbReach = with(density) { phoneOrbReach(maxHeight).toPx() }
            LaunchedEffect(orbCenter, orbReach, reduce) {
                snapshotFlow { if (skyShapes.size == PhoneSkyShape.entries.size) phoneOrbClear(orbCenter, orbReach, skyShapes.values) else null }
                    .filterNotNull()
                    .collectLatest { clear -> if (orbClear != null && !reduce) delay(PHONE_ORB_SETTLE_MS); orbClear = clear }
            }
            // It fades with the page on the page clock instead of popping on the first frame of a tab change; the sky reads the
            // value in its draw, so the fade redraws the sky and recomposes nothing.
            val orbFade = animateFloatAsState(if (homeShown && orbClear == true) 1f else 0f, if (reduce) snap() else tween(MOTION_PAGE_MS), label = "phone-orb")
            PhoneHomeBackdrop(Modifier.matchParentSize().graphicsLayer { alpha = skyAlpha }, hazeTo = greetingBottom, orbAt = orbAt, orbAlpha = { orbFade.value })
            Scaffold(containerColor = Color.Transparent,
                snackbarHost = { PhoneSnackbarHost(snackbar, Modifier.widthIn(max = 600.dp).padding(horizontal = 12.dp)) },
                bottomBar = { if (showTabs && !rail) PhoneGlassNav(navTabs, ui.tab) { ui = ui.copy(tab = it) } }) { padding ->
                // The tab pages run on under the floating bar: the row keeps the top and side insets only, and LocalNavInset carries
                // what the bar covers (beside the rail, the gesture bar), so each tab ends its own scroll above it instead of on a hard
                // line. The snackbar still sits above the bar (the scaffold places it) and the pull indicator at the top of the page.
                val direction = LocalLayoutDirection.current
                val navInset = padding.calculateBottomPadding()
                Row(Modifier.fillMaxSize().padding(start = padding.calculateStartPadding(direction), top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(direction))) {
                    if (rail) PhoneGlassRail(navTabs, ui.tab, Modifier.padding(bottom = navInset)) { ui = ui.copy(tab = it) }
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        AnimatedContent(targetState = RouteFrame(ui.route, ui.routes.size), label = "phone-route", transitionSpec = {
                            if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
                            else if (targetState.depth > initialState.depth) slideInHorizontally(tween(160, easing = FastOutSlowInEasing)) { it } + fadeIn(tween(160)) togetherWith
                                slideOutHorizontally(tween(160, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(120))
                            else slideInHorizontally(tween(160, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(160)) togetherWith
                                slideOutHorizontally(tween(160, easing = FastOutSlowInEasing)) { it } + fadeOut(tween(120))
                        }) { frame ->
                            // Every frame keeps to the class's content width and sits centred; the backdrop breathes around it. A tab page
                            // runs on under the bar; a full-screen route or the connect screen stops above the bottom inset as before.
                            val underBar = phoneFrameUnderBar(frame.route, hasLog)
                            Box(Modifier.fillMaxSize().padding(bottom = if (underBar) 0.dp else navInset), contentAlignment = Alignment.TopCenter) {
                                CompositionLocalProvider(LocalNavInset provides (if (underBar) navInset else 0.dp)) { Box(Modifier.contentWidth().fillMaxHeight()) {
                                    when (val route = frame.route) {
                                        PhoneRoute.Settings -> PhoneSettingsPage(data, link, paired, busy, host.pair, host.unpair, cardAlerts, queued, host.openAlertSettings,
                                            host.importStatement, host.pickFile, onBack = { ui = ui.pop() }, accountSlot = { host.accountCard?.invoke() })
                                        is PhoneRoute.TripDetail -> PhoneTripDetailScreen(route.id, data, paired, busy, send, onBack = { ui = ui.pop() },
                                            onPickDriver = { driverFor = listOf(route.id) }, pendingEdit = ui.pendingEdits[route.id], classify = classify)
                                        is PhoneRoute.Employee -> PhoneEmployeeScreen(route.id, data, paired, busy, now, ui.pendingEdits, commands, onBack = { ui = ui.pop() })
                                        PhoneRoute.Vehicle -> PhoneVehicleScreen(data, link, now, onBack = { ui = ui.pop() })
                                        PhoneRoute.Tab -> Column(Modifier.fillMaxSize()) {
                                            PhoneHeader(data, link, paired, hasAssistant = host.assistant != null, onAssistant = { assistantOpen = true }, onSettings = { ui = ui.push(PhoneRoute.Settings) },
                                                vehicleSwitcher = host.vehicleSwitcher, onIcons = { reportSkyShape(PhoneSkyShape.HEADER_ICONS, it) })
                                            PhoneBanner(offline && !ui.dismissedBanners.contains("offline"),
                                                "차량과 연결되지 않아요" + (lastSeen?.let { " · 마지막 수신 $it" } ?: "") + " · 보던 사본을 보여 주고 있어요",
                                                Modifier.padding(horizontal = tokens.gutter, vertical = 4.dp), onDismiss = { ui = ui.copy(dismissedBanners = ui.dismissedBanners + "offline") })
                                            if (!hasLog) PhoneConnectScreen(state, link, paired, busy, host.pair, host.pickFile, Modifier.weight(1f))
                                            else {
                                                if (ui.tab == PhoneTab.TRIPS.ordinal || ui.tab == PhoneTab.EXPENSES.ordinal) PhoneEmployeeChips(data, ui.employeeFilter) { ui = ui.copy(employeeFilter = it) }
                                                AnimatedContent(targetState = ui.tab, modifier = Modifier.weight(1f).fillMaxWidth(), transitionSpec = { if (targetState > initialState) swapForward else swapBack }, label = "phone-tab") { tab ->
                                                    pageStates.SaveableStateProvider(tab) {
                                                        val pullable = tab != PhoneTab.REPORT.ordinal && tab != PhoneTab.BOOK.ordinal
                                                        val body: @Composable () -> Unit = {
                                                            when (tab) {
                                                                0 -> PhoneHomeTab(data, link, paired, busy, now, host.repository.photos, photoVersion, host.capture, host.fetchPhoto, commands, ui.pendingEdits,
                                                                    onVehicle = { ui = ui.push(PhoneRoute.Vehicle) }, onGreetingBottom = { greetingBottom = it }, onSkyShape = reportSkyShape)
                                                                PhoneTab.BOOK.ordinal -> host.bookingTab(PhoneBookingArgs(data, paired, link.connected, busy, now, sendConfirmed))
                                                                PhoneTab.TRIPS.ordinal -> PhoneTripList(data, ui.employeeFilter, paired, busy, now, ui.pendingEdits, commands, Modifier.fillMaxSize())
                                                                PhoneTab.EXPENSES.ordinal -> host.expensesTab(PhoneExpensesArgs(data, month, ui.employeeFilter, paired, busy, send, cardAlerts, queued, host.openAlertSettings, zone))
                                                                else -> host.reportTab(PhoneReportArgs(data, month, { ui = ui.copy(month = it.toString()) }, ui.employeeFilter, paired, busy, send,
                                                                    commands.openTrip, { ui = ui.push(PhoneRoute.Employee(it)) }, host.exportXlsx))
                                                            }
                                                        }
                                                        if (pullable) {
                                                            // Material's indicator, in the scene's glass with the glow for the spinner; the content never stretches.
                                                            val pull = rememberPullToRefreshState()
                                                            PullToRefreshBox(isRefreshing = refreshing, onRefresh = refresh, modifier = Modifier.fillMaxSize(), state = pull, indicator = {
                                                                PullToRefreshDefaults.Indicator(state = pull, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter),
                                                                    containerColor = Scene.glass, color = Scene.glow)
                                                            }) { body() }
                                                        } else Box(Modifier.fillMaxSize()) { body() }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } }
                            }
                        }
                    }
                }
            }
            driverFor?.let { ids ->
                val trips = (data.trips + listOfNotNull(data.open)).filter { it.id in ids }
                PhoneDriverSheet(data, if (trips.size == 1) "이 운행의 사용자" else "${trips.size}건의 사용자", current = trips.singleOrNull()?.driverId,
                    onPick = { driver ->
                        driverFor = null
                        trips.forEach { send(SyncCommand.SetTripDriver(it.id, driver.id)) }
                        followUp(FollowUp(if (trips.size == 1) "사용자를 ${driver.label}으로 지정했어요" else "${trips.size}건의 사용자를 ${driver.label}으로 지정했어요"))
                    }, onDismiss = { driverFor = null })
            }
            if (assistantOpen) host.assistant?.invoke({ assistantOpen = false }, { id -> assistantOpen = false; ui = ui.push(PhoneRoute.TripDetail(id)) })
        }
    }
}

/**
 * Press feedback for a control that owns its own click (the vehicle chip from PhoneFleetUi): the wrapper watches the
 * touch without consuming it and settles the chip to 97 % while it is held, through the same [pressable] the cards use.
 */
@Composable private fun PressFeedback(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(modifier.pressable(source).pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = PressInteraction.Press(down.position)
            source.tryEmit(press)
            val up = waitForUpOrCancellation()
            source.tryEmit(if (up != null) PressInteraction.Release(press) else PressInteraction.Cancel(press))
        }
    }) { content() }
}

/**
 * The phone header on every tab, 56 dp: the brand mark, then the company-vehicle switcher (plate and live status, opens the
 * fleet sheet), then icon buttons for the assistant and settings in the Material on-surface colour. [onIcons] gets the box the
 * icon glyphs fill (root px, 12 dp inside the 48-dp buttons), so the backdrop's sun or moon keeps clear of them.
 */
@Composable private fun PhoneHeader(data: TripLogData, link: PhoneLink, paired: Boolean, hasAssistant: Boolean, onAssistant: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier, vehicleSwitcher: (@Composable () -> Unit)? = null, onIcons: ((Rect) -> Unit)? = null) {
    val gutter = LocalLayout.current.gutter
    val tint = MaterialTheme.colorScheme.onSurface
    val glyphInset = with(LocalDensity.current) { 12.dp.toPx() }
    Row(modifier.fillMaxWidth().padding(start = gutter - 4.dp, end = (gutter - 12.dp).coerceAtLeast(4.dp), top = 4.dp, bottom = 4.dp).heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_splash_logo), contentDescription = "SHAREMATE", tint = Scene.brand, modifier = Modifier.size(30.dp))
        if (vehicleSwitcher != null) Box(Modifier.weight(1f)) { PressFeedback { vehicleSwitcher() } }
        else {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                Text("SHAREMATE", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, maxLines = 1, softWrap = false)
                Text(listOf(data.profile.plateNumber.ifBlank { "차량번호 미입력" }, data.profile.companyName).filter { it.isNotBlank() }.joinToString(" · ") +
                    if (data.profile.demoMode) " · 시연" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (paired) PhoneLinkBadge(link)
        }
        Row(Modifier.then(if (onIcons != null) Modifier.onGloballyPositioned { onIcons(it.boundsInRoot().deflate(glyphInset)) } else Modifier),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (hasAssistant) IconButton(onClick = onAssistant, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.ic_search), contentDescription = "운행 비서 열기", modifier = Modifier.size(24.dp), tint = tint)
            }
            IconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) { Icon(painterResource(R.drawable.ic_tune), contentDescription = "설정", modifier = Modifier.size(24.dp), tint = tint) }
        }
    }
}

/** One row of 48-dp chips: everyone, each registered driver, and trips without a driver. Remembered per phone; wraps at a 1.3× font. */
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PhoneEmployeeChips(data: TripLogData, filter: String?, onChange: (String?) -> Unit) {
    val options = listOf<Pair<String?, String>>(null to "전체 직원") + data.drivers.map { it.id to it.label } + ("" to "직원 미지정")
    val gutter = LocalLayout.current.gutter
    val wrap = LocalDensity.current.fontScale > 1.25f
    val chip: @Composable (String?, String) -> Unit = { id, label ->
        val selected = filter == id
        // Selected = fill + ring + glow label, never colour alone.
        FilterChip(selected = selected, onClick = { onChange(id) }, label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Scene.selectionFill, selectedLabelColor = Scene.glow),
            border = FilterChipDefaults.filterChipBorder(enabled = true, selected = selected, borderColor = Scene.outline, selectedBorderColor = Scene.selectionRing, selectedBorderWidth = 1.5.dp),
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "직원 필터 $label" })
    }
    if (wrap) FlowRow(Modifier.fillMaxWidth().padding(horizontal = gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> chip(id, label) }
    } else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (id, label) -> chip(id, label) }
    }
}

/** 현황 › 직원: the trip list fixed to one driver, with the breadcrumb and a back arrow. */
@Composable private fun PhoneEmployeeScreen(driverId: String, data: TripLogData, paired: Boolean, busy: Boolean, now: Long, pending: Map<String, PendingEdit>,
    commands: PhoneCommands, onBack: () -> Unit) {
    val driver = data.drivers.firstOrNull { it.id == driverId }
    Column(Modifier.fillMaxSize()) {
        PhoneRouteHeader(title = "현황 › ${driver?.name ?: "직원 미지정"}", subtitle = driver?.department?.takeIf { it.isNotBlank() }, onBack = onBack)
        PhoneTripList(data, driverId, paired, busy, now, pending, commands, Modifier.weight(1f).fillMaxWidth())
    }
}

/** Header for a full-screen route, 56 dp: a 48dp back button that reads "뒤로", then the title as a heading. */
@Composable internal fun PhoneRouteHeader(title: String, subtitle: String? = null, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val inset = (LocalLayout.current.gutter - 12.dp).coerceAtLeast(4.dp)
    Row(Modifier.fillMaxWidth().padding(start = inset, end = inset + 8.dp, top = 4.dp, bottom = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로", tint = MaterialTheme.colorScheme.onSurface) }
        Column(Modifier.weight(1f).padding(start = 4.dp).semantics(mergeDescendants = true) { heading() }) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}
