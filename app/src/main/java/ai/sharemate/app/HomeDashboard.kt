package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.toSize
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/**
 * The car home, 2.10: a city hero in the current scene's light that greets the driver, a search-style entry to the
 * assistant, three glass cards (차량 정보 · 운행일지 확인 · 정보 내보내기), and two widgets that say how today and this month
 * stand. Every number on it is read from the log or the vehicle; nothing is decorative except the skyline itself. The
 * block keeps to the layout classes of [LocalLayout] (a 1200-dp column at FULL, cards 3 across, widgets 2 across) and
 * the motion rules of Motion.kt: press feedback, a gliding selection pill, numbers that cross-fade instead of counting.
 */

// ───────────────────────── selection pill (tabs and segments) ─────────────────────────

/** The measured bounds of the items of a tab row or a segmented control, keyed by index, in the row's own pixels. */
internal typealias ItemBounds = SnapshotStateMap<Int, Rect>

@Composable internal fun rememberItemBounds(): ItemBounds = remember { mutableStateMapOf() }

/** Reports this item's bounds to [bounds] under [index]; put it first in the item's modifier chain so it sees the whole item. */
internal fun Modifier.reportBounds(bounds: ItemBounds, index: Int): Modifier = onPlaced { coordinates ->
    val rect = Rect(coordinates.positionInParent(), coordinates.size.toSize())
    if (bounds[index] != rect) bounds[index] = rect
}

/** How [selectionGlide] marks the chosen item: the lit pill (selection fill and ring), or a glow underline (the sheet tabs). */
internal enum class GlideMark { PILL, UNDERLINE }

/** The underline mark: 3 dp of glow, inset 16 dp from each side of the chosen item. */
private val UNDERLINE_THICKNESS = 3.dp
private val UNDERLINE_INSET = 16.dp

/**
 * Where the underline mark sits under an item with [bounds]: [thickness] high along its bottom edge, centred, inset by [inset]
 * a side but never narrower than four times its thickness (so a narrow tab still shows a bar, not a dot).
 */
internal fun underlineBar(bounds: Rect, thickness: Float, inset: Float): Rect {
    val width = (bounds.width - inset * 2).coerceAtLeast(thickness * 4)
    return Rect(bounds.center.x - width / 2, bounds.bottom - thickness, bounds.center.x + width / 2, bounds.bottom)
}

/**
 * The mark behind the selected item of a tab row or a segmented control, drawn at the selected item's measured bounds: the
 * lit pill (the selection fill and a 1.5 dp ring, never colour alone), or with [GlideMark.UNDERLINE] a 3-dp glow underline
 * (a shape, so never colour alone either). On a change it glides from the old item to the new one in [durationMs]
 * (FastOutSlowIn); the first placement and reduced motion snap. Apply it after the row's padding (or its scroll), so the
 * drawing and the reported bounds share one coordinate space.
 */
@Composable internal fun Modifier.selectionGlide(bounds: ItemBounds, selected: Int, durationMs: Int = MOTION_PAGE_MS, ringWidth: Dp = 1.5.dp, corner: Dp? = null,
    mark: GlideMark = GlideMark.PILL): Modifier {
    val reduce = LocalReduceMotion.current
    val fill = Scene.selectionFill
    val ring = Scene.selectionRing
    val glow = Scene.glow
    val target = bounds[selected]
    val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduce) {
        val next = target ?: return@LaunchedEffect
        if (!placed || reduce) { rect.snapTo(next); placed = true } else rect.animateTo(next, tween(durationMs, easing = FastOutSlowInEasing))
    }
    val density = LocalDensity.current
    val stroke = with(density) { ringWidth.toPx() }
    val radius = corner?.let { with(density) { it.toPx() } }
    val thickness = with(density) { UNDERLINE_THICKNESS.toPx() }
    val inset = with(density) { UNDERLINE_INSET.toPx() }
    val visible = target != null
    return drawBehind {
        if (!placed || !visible) return@drawBehind
        val r = rect.value
        if (mark == GlideMark.UNDERLINE) {
            val bar = underlineBar(r, thickness, inset)
            drawRoundRect(glow, bar.topLeft, bar.size, CornerRadius(thickness / 2, thickness / 2))
            return@drawBehind
        }
        val round = radius ?: (r.height / 2f)
        drawRoundRect(fill, r.topLeft, r.size, CornerRadius(round, round))
        drawRoundRect(ring, Offset(r.left + stroke / 2, r.top + stroke / 2), Size(r.width - stroke, r.height - stroke),
            CornerRadius(round - stroke / 2, round - stroke / 2), style = Stroke(stroke))
    }
}

/**
 * The press fill of the spec, [Scene.fill] (white by night, navy by day, both a few percent): it shows while the surface is
 * held, 90 ms in and 160 ms out, and stays as the only feedback under reduced motion. Draws over the content, so apply it
 * inside the clipping surface.
 */
@Composable internal fun Modifier.pressFill(interactionSource: InteractionSource): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val fill = Scene.fill
    val reduce = LocalReduceMotion.current
    val strength by animateFloatAsState(if (pressed) 1f else 0f,
        if (reduce) snap() else if (pressed) tween(90, easing = LinearOutSlowInEasing) else tween(160, easing = FastOutSlowInEasing), label = "press-fill")
    return drawWithContent { drawContent(); if (strength > 0f) drawRect(fill.copy(alpha = fill.alpha * strength)) }
}

/** True on the car, where tappable surfaces give no ripple (press feedback is the scale and the fill); the phone keeps Material's. */
@Composable private fun carNoRipple(): Boolean = LocalLayout.current.startAnchored

// ───────────────────────── header tabs (every pane but home) ─────────────────────────

@Immutable
internal data class NavTab(val label: String, val icon: Int, val badge: Int)

/**
 * The five destinations as one glass pill; the lit pill glides to the chosen tab on the page clock and the labels tint with
 * it. Counts sit inside the tab they belong to. [compact] is the short-window header: 4 dp of pill padding instead of 6.
 */
@Composable internal fun GlassTabs(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val bounds = rememberItemBounds()
    val noRipple = carNoRipple()
    Surface(modifier, shape = RoundedCornerShape(50), color = Scene.glass.copy(alpha = .92f), border = Scene.edge) {
        Row(Modifier.padding(if (compact) 4.dp else 6.dp).selectionGlide(bounds, selected), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val interaction = remember { MutableInteractionSource() }
                // The icon and the label tint on the same clock as the pill glides and the page changes.
                val tint by animateColorAsState(if (active) Scene.glow else Scene.textSecondary, tabColorSpec(), label = "tab-tint")
                val label by animateColorAsState(if (active) Scene.glow else Scene.text, tabColorSpec(), label = "tab-label")
                Row(Modifier.reportBounds(bounds, index).pressable(interaction, .98f).clip(RoundedCornerShape(50))
                    .clickable(interactionSource = interaction, indication = if (noRipple) null else LocalIndication.current) { onSelect(index) }
                    .clearAndSetSemantics {
                        this.selected = active; role = Role.Tab
                        contentDescription = if (tab.badge > 0) "${tab.label}, 확인 필요 ${tab.badge}건" else tab.label
                    }
                    .heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = tint)
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, color = label, maxLines = 1)
                    if (tab.badge > 0) CountBadge(tab.badge)
                }
            }
        }
    }
}

// ───────────────────────── pure copy and layout (unit-tested) ─────────────────────────

/** The hero headline: a recording trip first, otherwise a time-of-day line that names the current employee when one is set. */
internal fun homeGreeting(hour: Int, name: String?, recording: Boolean): String {
    val who = name?.takeIf { it.isNotBlank() }
    if (recording) return "운행을 기록하고 있어요"
    return when (hour) {
        in 5..10 -> if (who != null) "좋은 아침이에요, $who 님" else "좋은 아침이에요"
        in 11..17 -> if (who != null) "오늘도 안전한 운행 되세요, $who 님" else "오늘도 안전한 운행 되세요"
        in 18..22 -> if (who != null) "오늘도 수고 많으셨어요, $who 님" else "오늘도 수고 많으셨어요"
        else -> "늦은 시간이에요 · 천천히 안전하게"
    }
}

/** The line under the headline: what the car is doing for the driver right now, in its real state. */
internal fun homeSubtitle(signal: SignalStatus, demo: Boolean, open: Trip?): String = when {
    demo -> "시연 주행은 15배속으로 기록되고 ‘시연 데이터’로 구분돼요."
    open != null -> "${hm(open.startAt)} 출발 · ${kmText(open.distanceKm)} · 주차하면 목적과 비용을 정리해 드려요."
    signal == SignalStatus.READY || signal == SignalStatus.RECORDING -> "출발하면 자동으로 기록하고, 주차하면 목적과 비용을 정리해 드려요."
    signal == SignalStatus.DENIED -> "계기판 누적거리를 읽으려면 차량 정보 권한이 필요해요."
    signal == SignalStatus.STARTING -> "차량과 연결하고 있어요."
    else -> "차량 데이터를 받을 수 없어요. 연결 상태를 확인해 주세요."
}

/**
 * True while a trip of the data set on screen is being recorded. Its distance is then live telemetry inside the hero's
 * supporting line ([homeSubtitle]) and the 오늘 운행 figure, so those update in place instead of cross-fading (spec C).
 */
internal fun homeShowsLiveTrip(data: TripLogData): Boolean = data.open?.let { it.demo == data.profile.demoMode } == true

/**
 * How the home lays itself out in one window ([LayoutTokens] plus the font scale and the window height):
 * [cardColumns] 3 across on the wide classes, 2 + 1 below at a large font on the classes under FULL (the third card one
 * column wide, [homeCardRows]), one 96-dp row per card in narrow windows; the widgets sit side by side from EXPANDED up
 * (and on one 120-dp row in a short window, [compactWidgets]);
 * a short window hides the eyebrow, moves the clock into the header and keeps the hero to [heroMin].
 */
@Immutable
internal data class HomePlan(val cardColumns: Int, val widgetsSideBySide: Boolean, val compactWidgets: Boolean, val showEyebrow: Boolean,
    val clockInHeader: Boolean, val heroMin: Dp)

internal fun homePlan(layout: LayoutTokens, fontScale: Float, windowHeight: Dp): HomePlan {
    val columns = when {
        !layout.expanded -> 1
        layout.full || fontScale <= 1.25f -> 3
        else -> 2
    }
    return HomePlan(cardColumns = columns, widgetsSideBySide = layout.expanded || layout.short, compactWidgets = layout.short,
        showEyebrow = !layout.short, clockInHeader = layout.short, heroMin = heroMinHeight(windowHeight, layout.short))
}

/** The hero keeps 70 % of the window between 420 and 600 dp; a short window keeps 55 % from 300 dp. A minimum only: the column grows when a large font needs the room. */
internal fun heroMinHeight(windowHeight: Dp, short: Boolean): Dp =
    if (short) (windowHeight * .55f).coerceIn(300.dp, 420.dp) else (windowHeight * .70f).coerceIn(420.dp, 600.dp)

/**
 * The destination cards as rows of [columns] card indices. A short last row is padded with nulls (empty cells), so at 2 + 1
 * the third card keeps one column's width and the vertical anatomy, start-aligned under the first, instead of stretching
 * into a full-width strip. One column gives one card per row (the 96-dp horizontal rows).
 */
internal fun homeCardRows(count: Int, columns: Int): List<List<Int?>> {
    val per = columns.coerceAtLeast(1)
    return (0 until count.coerceAtLeast(0)).chunked(per).map { row -> row + List(per - row.size) { null } }
}

@Immutable
internal data class HomeCardLines(val vehicle: String, val log: String, val logPending: Int, val export: String)

/**
 * The 운행기록 tab's own numbers for [month]: its 전체 count and its 점검 필요 count (audit issues over the whole odometer
 * chain, kept to that month's trips). The home card and the rail badge use this so they never disagree with the list they open.
 */
internal data class LogReview(val trips: Int, val issues: Int)

internal fun logReview(data: TripLogData, month: YearMonth, zone: ZoneId): LogReview {
    val trips = tripsIn(data.trips, month.atDay(1), month.atEndOfMonth(), zone)
    val ids = trips.map { it.id }.toSet()
    return LogReview(trips.size, auditTrips(data.trips).count { it.tripId in ids })
}

/**
 * What each home card says under its title. 차량 정보 leads with a due care deadline, then battery and odometer (a value
 * that is not live is marked 마지막, and a zero or missing reading is left out); 운행일지 확인 counts this month the way the
 * log does; 정보 내보내기 names the month the Excel covers.
 */
internal fun homeCardLines(data: TripLogData, socPercent: Int?, odometerKm: Double?, odometerLive: Boolean, today: LocalDate, zone: ZoneId,
    snoozedCare: Set<String> = emptySet()): HomeCardLines {
    val care = careDueAlerts(data.care, odometerKm, today, snoozedCare).firstOrNull()?.title
    val odometer = odometerKm?.takeIf { it.isFinite() && it > 0 }?.let { if (odometerLive) kmText(it) else "마지막 ${kmText(it)}" }
    val vehicle = listOfNotNull(care, socPercent?.let { "배터리 $it%" }, odometer).take(2).joinToString(" · ").ifBlank { "차량 상태 · 관리 기한" }
    val month = YearMonth.from(today)
    val review = logReview(data, month, zone)
    val recording = data.open?.let { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) == month } == true
    val log = when {
        review.trips == 0 && recording -> "${month.monthValue}월 첫 운행 기록 중"
        review.trips == 0 -> "${month.monthValue}월 운행이 아직 없어요"
        review.issues > 0 -> "${month.monthValue}월 ${review.trips}건 · 확인 ${review.issues}건 남음"
        else -> "${month.monthValue}월 ${review.trips}건 · 모두 확인됨"
    }
    val pending = review.issues
    val target = monthCloseTarget(data, today, zone)
    val export = if (target != null && target.month != month) "${monthLabel(target.month, today)} 운행기록부 · 마감 전 저장"
        else "${month.monthValue}월 운행기록부 · Excel 5개 시트"
    return HomeCardLines(vehicle, log, pending, export)
}

@Immutable
internal data class MonthWidgetInfo(val title: String, val headline: String, val detail: String, val tone: Int, val auto: String? = null, val percent: Int? = null)

/**
 * The month widget's one word (대기 · 완성 · 마감됨 · 확인 N건) and its detail line, from [monthStatus] and the totals.
 * tone: 0 quiet, 1 done, 2 attention. [MonthWidgetInfo.auto] counts what the car filled in by itself, only while autopilot is on.
 */
internal fun monthWidgetInfo(data: TripLogData, today: LocalDate, zone: ZoneId): MonthWidgetInfo {
    val month = YearMonth.from(today)
    val status = monthStatus(data, month, zone)
    val demo = data.profile.demoMode
    val totals = logbookTotals(tripsIn(data.trips.filter { it.demo == demo }, month.atDay(1), month.atEndOfMonth(), zone))
    val title = "이달 운행일지 · ${month.monthValue}월"
    val detail = "업무 ${ratioText(totals.ratio)} · ${kmText(totals.totalKm)} · ${totals.trips}건"
    val auto = if (data.profile.autopilot && status.trips > 0) autopilotSummary(data, month.atDay(1), month.atEndOfMonth(), zone).let {
        "자동 분류 ${it.autoPurpose}건 · 카드 반영 ${it.cardMatched}건 ${formatWon(it.cardWon)}"
    } else null
    val recording = data.open?.takeIf { it.demo == demo }?.let { YearMonth.from(Instant.ofEpochMilli(it.startAt).atZone(zone)) == month } == true
    return when {
        status.trips == 0 && recording -> MonthWidgetInfo(title, "기록 중", "이번 달 첫 운행을 기록하고 있어요", 0)
        status.trips == 0 -> MonthWidgetInfo(title, "대기", "이번 달 운행이 아직 없어요", 0)
        // A closed month that gained an unconfirmed trip or card since is not 'done'; it says what is left.
        status.closed != null && status.issues > 0 -> MonthWidgetInfo(title, "마감 후 확인 ${status.issues}건", detail, 2, auto, status.percent)
        status.closed != null -> MonthWidgetInfo(title, "마감됨", detail, 1, auto, status.percent)
        status.issues == 0 -> MonthWidgetInfo(title, "완성", detail, 1, auto, status.percent)
        else -> MonthWidgetInfo(title, "확인 ${status.issues}건", detail, 2, auto, status.percent)
    }
}

// ───────────────────────── hero ─────────────────────────

@Immutable
internal class HomeCardSpec(val title: String, val subtitle: String, val icon: Int, val badge: Int, val onClick: () -> Unit)

/** Set once the home has played its entrance, so the stagger runs once per process and never on a return to the tab or after a sync. */
private var homeEntranceDone = false

/** True the first time the home composes in this process: the hero block staggers in (clock → greeting → search → cards); false afterwards. */
@Composable internal fun rememberHomeEntrance(): Boolean {
    val animate = remember { !homeEntranceDone }
    LaunchedEffect(Unit) { homeEntranceDone = true }
    return animate
}

/**
 * The greeting hero: clock, brand line, greeting, the assistant entry and [cards], the three destinations, laid out by
 * [plan]. Grows past [HomePlan.heroMin] when the font scale needs room instead of clipping; the greeting block keeps to the
 * reading width. [entrance] staggers the block in on the first composition of the session only.
 */
@Composable internal fun HomeHero(state: TripLogState, now: Long, plan: HomePlan, onAssistant: () -> Unit, requestPermissions: () -> Unit,
    demoControls: @Composable () -> Unit, cards: List<HomeCardSpec>, modifier: Modifier = Modifier, entrance: Boolean = false) {
    val data = state.data
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour
    val layout = LocalLayout.current
    val compact = plan.cardColumns == 1
    // No frame and no chips of its own: the shared header above carries status, employee and tabs on every page, and the
    // hero is laid straight onto the full-screen backdrop ([HomeBackdrop]).
    Column(modifier.fillMaxWidth().heightIn(min = plan.heroMin).padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        // The clock is plain text on a one-second tick: it never cross-fades (truth does not animate). A short window shows it in the header instead.
        if (!plan.clockInHeader) Appear(index = 0, animate = entrance) {
            Row(Modifier.clearAndSetSemantics { contentDescription = "${hm(now)}, ${dayLabel(localDate(now))}" }, verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(hm(now), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, fontWeight = FontWeight.Bold)
                Box(Modifier.width(1.dp).height(24.dp).background(Scene.divider))
                Text(dayLabel(localDate(now)), style = MaterialTheme.typography.titleMedium, color = Scene.textSecondary)
            }
        } else Spacer(Modifier.height(4.dp))
        Appear(Modifier.readingWidth(), index = 1, animate = entrance) {
            Column(Modifier.fillMaxWidth().padding(vertical = if (compact || plan.compactWidgets) 12.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (plan.showEyebrow) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clearAndSetSemantics {}) {
                    Icon(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(30.dp), tint = Scene.brand)
                    Text("SHAREMATE  ·  FLEET JOURNAL", style = MaterialTheme.typography.labelLarge, color = Scene.brand, letterSpacing = 3.sp)
                }
                // One headline, one supporting line. Both cross-fade with a small lift when the text changes (a new employee, a trip starting),
                // except that while a trip records the line carries its live distance, which updates in place (telemetry never cross-fades).
                DigitChange(homeGreeting(hour, driver?.name, open != null), Modifier.semantics { heading() },
                    style = if (compact) MaterialTheme.typography.headlineLarge else scaledDisplay(MaterialTheme.typography.displaySmall, MaterialTheme.typography.headlineLarge),
                    color = Scene.text, fontWeight = FontWeight.Bold, maxLines = 2, lift = 4.dp)
                val subtitle = homeSubtitle(state.signal, demo, open)
                if (homeShowsLiveTrip(data)) Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else DigitChange(subtitle, style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary, maxLines = 2, lift = 4.dp)
                if (state.signal == SignalStatus.DENIED) PrimaryAction("차량 정보 권한 허용", onClick = requestPermissions)
            }
        }
        Appear(Modifier.readingWidth(), index = 2, animate = entrance) { AssistantSearch(onAssistant) }
        Appear(Modifier.fillMaxWidth().padding(top = if (compact) 16.dp else 24.dp), index = 3, animate = entrance) {
            Column(verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                // 3 across, or 2 + 1 at a large font: every card keeps the vertical anatomy at one column's width, and an
                // empty cell holds the place of a missing one so a lone card never stretches across the row.
                if (plan.cardColumns == 1) cards.forEach { HomeActionCard(it, Modifier.fillMaxWidth(), horizontal = true) }
                else homeCardRows(cards.size, plan.cardColumns).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
                        row.forEach { index -> if (index != null) HomeActionCard(cards[index], Modifier.weight(1f)) else Spacer(Modifier.weight(1f)) }
                    }
                }
                demoControls()
            }
        }
    }
}

/**
 * The search-bar entry to the assistant (640 × 68): it opens the sheet, where the driver picks a suggestion or types one line.
 * Its outline is the solid accent, the one edge that marks it as a control on the hero haze (3.6:1 at the haze's weakest point,
 * where WCAG 1.4.11 asks 3:1; the .40 accent it had was 1.7:1).
 */
@Composable private fun AssistantSearch(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    // Day glass that carries text stays at .88 or above, so the composite contrast holds; night keeps its lighter alpha.
    val glass = Scene.glass.copy(alpha = if (Scene.isDay) .88f else .80f)
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = glass, border = BorderStroke(1.dp, Scene.accent), interactionSource = interaction,
        modifier = modifier.widthIn(max = 640.dp).fillMaxWidth().pressable(interaction, .98f)
            .semantics { contentDescription = "운행 비서 열기. 예: 주차비 4,500원 추가해줘"; role = Role.Button }) {
        Row(Modifier.pressFill(interaction).heightIn(min = 68.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(28.dp), tint = Scene.textSecondary)
            Text("운행 비서에게 물어보세요", style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1)
            Text("예: 주차비 4,500원 추가해줘", style = MaterialTheme.typography.bodyMedium, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
    }
}

/**
 * The glass of the home's cards and widgets: opaque [Scene.glass] by day, so neither the towers nor the bridge show through
 * the white; by night the scene's glass at [translucent] (null keeps the token's own alpha), as built.
 */
@Composable private fun homeCardGlass(translucent: Float? = null): Color {
    val glass = Scene.glass
    return when {
        Scene.isDay -> glass.copy(alpha = 1f)
        translucent != null -> glass.copy(alpha = translucent)
        else -> glass
    }
}

/**
 * The drop shadow under a tappable car card: the phone's day shadow ([Scene.shadow], 6 dp, as under its cards). The token is
 * transparent by night, so nothing is drawn there. Put it before the press scale so the card settles inside its shadow.
 */
@Composable private fun Modifier.dayCardShadow(shape: Shape): Modifier {
    val tone = Scene.shadow
    if (tone.alpha <= 0f) return this
    // The platform scales shadow colours by its own ambient and spot alphas, so the token's tone goes in at full strength.
    val colour = tone.copy(alpha = (tone.alpha / .12f).coerceIn(0f, 1f))
    return shadow(6.dp, shape, clip = false, ambientColor = colour, spotColor = colour)
}

/**
 * One destination card (184 dp tall on the wide car screen, a 96-dp row in narrow windows). Settles to .98 while held; no
 * ripple on the car. By day it is opaque white glass with the phone's drop shadow and no sheen (the day edge token is a dark
 * hairline, so a sheen drawn from it greys the top, and glass the city shows through reads grey); by night it keeps the
 * translucent glass with its lit top edge.
 */
@Composable internal fun HomeActionCard(spec: HomeCardSpec, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val shape = RoundedCornerShape(26.dp)
    val description = "${spec.title}, ${spec.subtitle}" + if (spec.badge > 0) ", 확인 필요 ${spec.badge}건" else ""
    val interaction = remember { MutableInteractionSource() }
    val day = Scene.isDay
    val sheen = if (day) null else Brush.verticalGradient(listOf(Scene.colors.edgeTop, Color.Transparent))
    Surface(onClick = spec.onClick, shape = shape, color = homeCardGlass(translucent = .78f), border = Scene.edge, interactionSource = interaction,
        modifier = modifier.dayCardShadow(shape).pressable(interaction, .98f).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }) {
        Box(Modifier.then(if (sheen != null) Modifier.background(sheen) else Modifier).pressFill(interaction)) {
            if (horizontal) Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconTile(spec.icon, small = true)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleLarge, color = Scene.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(spec.subtitle, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (spec.badge > 0) CountBadge(spec.badge)
                Chevron()
            } else Column(Modifier.fillMaxWidth().heightIn(min = 184.dp).padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                Box { IconTile(spec.icon); if (spec.badge > 0) CountBadge(spec.badge, Modifier.align(Alignment.TopEnd).padding(start = 52.dp)) }
                Text(spec.title, style = MaterialTheme.typography.headlineSmall, color = Scene.text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spec.subtitle, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, textAlign = TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Chevron()
                }
            }
        }
    }
}

/** The card icon on a blue-to-cyan tile, with a soft halo behind it so the three destinations read as lit buttons. [small] for the 96-dp row. */
@Composable private fun IconTile(icon: Int, small: Boolean = false) {
    val shape = RoundedCornerShape(if (small) 16.dp else 20.dp)
    val halo = Scene.glow.copy(alpha = .30f)
    val tile = Scene.tile
    Box(Modifier.size(if (small) 72.dp else 96.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(halo, Color.Transparent), center = center, radius = size.minDimension / 2), radius = size.minDimension / 2)
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(if (small) 56.dp else 72.dp).clip(shape).background(tile).border(Scene.edge, shape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(if (small) 28.dp else 36.dp), tint = Scene.onTile)
        }
    }
}

/**
 * A count on its red disc. A changed count cross-fades its digits while the disc scales in from .85 (160 ms, no bounce);
 * an instant swap under reduced motion. The disc's bounds follow on the same clock without clipping (9 → 10 widens it),
 * and snap with the rest under reduced motion. Decorative for TalkBack: the tab or card it sits in speaks the count.
 */
@Composable internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    // White digits on the badge red clear 5.3:1 by day; the tile's foreground is the one white that is a scene token.
    val digits = Scene.onTile
    AnimatedContent(targetState = count.coerceAtMost(99), modifier = modifier.clearAndSetSemantics {}, label = "badge", transitionSpec = {
        val swap = if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
            else (fadeIn(tween(160, easing = FastOutSlowInEasing)) + scaleIn(tween(160, easing = FastOutSlowInEasing), initialScale = .85f)) togetherWith
                fadeOut(tween(120, easing = FastOutSlowInEasing))
        swap using SizeTransform(clip = false) { _, _ -> if (reduce) snap() else tween(160, easing = FastOutSlowInEasing) }
    }) { value ->
        Box(Modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).clip(RoundedCornerShape(50)).background(Scene.badge).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center) {
            Text("$value", style = MaterialTheme.typography.labelMedium, color = digits, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable private fun Chevron() =
    Text("›", style = MaterialTheme.typography.headlineSmall, color = Scene.textMuted, modifier = Modifier.clearAndSetSemantics {})

// ───────────────────────── widgets under the hero ─────────────────────────

/**
 * One glass widget (150 dp, or the 120-dp [compact] row of a short window), on opaque glass by day like the cards above it.
 * A tappable one settles to .98 while held and carries the day drop shadow of the tappable cards.
 */
@Composable private fun HomeWidget(modifier: Modifier, onClick: (() -> Unit)?, description: String, compact: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val interaction = remember { MutableInteractionSource() }
    val glass = homeCardGlass()
    val inner: @Composable () -> Unit = {
        Row(Modifier.then(if (onClick != null) Modifier.pressFill(interaction) else Modifier).fillMaxWidth().heightIn(min = if (compact) 120.dp else 150.dp)
            .padding(horizontal = if (compact) 24.dp else 30.dp, vertical = if (compact) 16.dp else 24.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp), content = content)
    }
    val semantics = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = glass, border = Scene.edge, interactionSource = interaction,
        modifier = modifier.dayCardShadow(shape).pressable(interaction, .98f).then(semantics)) { inner() }
    else Surface(shape = shape, color = glass, border = Scene.edge, modifier = modifier.then(semantics)) { inner() }
}

/**
 * Today at a glance: distance (the live trip included), finished trips, expenses, and the odometer when the signal is fresh.
 * The count and the expenses cross-fade when they change, and so does the distance between trips; while a trip records the
 * distance is live telemetry like the odometer, and both simply update in place.
 */
@Composable internal fun TodayWidget(state: TripLogState, now: Long, modifier: Modifier = Modifier, compact: Boolean = false) {
    val data = state.data
    val overview = drivingOverviewInfo(state, now, true, zone)
    val today = localDate(now)
    val demo = data.profile.demoMode
    val finished = tripsIn(data.trips.filter { it.demo == demo }, today, today, zone)
    val km = overview.todayKm
    val odometer = state.live?.odometerKm?.takeIf { overview.fresh }
    val kmLabel = km?.let { String.format(Locale.KOREA, "%,.1f", it) } ?: "—"
    val spoken = "오늘 운행 ${km?.let(::kmText) ?: "정보 없음"}, 완료 ${finished.size}건, 등록 경비 ${formatWon(finished.sumOf { it.expenseWon })}, " +
        "계기판 ${odometer?.let(::kmText) ?: "확인 필요"}"
    HomeWidget(modifier, null, spoken, compact) {
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 2.dp else 6.dp)) {
            Text("오늘 운행", style = MaterialTheme.typography.labelLarge, color = Scene.textMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val kmStyle = (if (compact) scaledDisplay(MaterialTheme.typography.displaySmall, MaterialTheme.typography.headlineLarge)
                    else scaledDisplay(MaterialTheme.typography.displayMedium, MaterialTheme.typography.displaySmall)).copy(fontFeatureSettings = "tnum")
                if (homeShowsLiveTrip(data)) Text(kmLabel, style = kmStyle, color = Scene.text, maxLines = 1)
                else DigitChange(kmLabel, style = kmStyle, color = Scene.text)
                Text("km", style = MaterialTheme.typography.titleMedium, color = Scene.textMuted, modifier = Modifier.padding(bottom = if (compact) 6.dp else 10.dp))
            }
        }
        Box(Modifier.width(1.dp).height(if (compact) 64.dp else 84.dp).background(Scene.divider))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp)) {
            WidgetLine("완료 운행", "${finished.size}건")
            WidgetLine("등록 경비", formatWon(finished.sumOf { it.expenseWon }))
            WidgetLine("계기판", odometer?.let(::kmText) ?: "확인 필요", live = true)
        }
    }
}

/** This month in one word, like a traffic widget: 대기 · 완성 · 마감됨 · 확인 N건, and where the month stands. Opens 월간·Excel. */
@Composable internal fun MonthWidget(data: TripLogData, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val info = monthWidgetInfo(data, localDate(now), zone)
    val color = when (info.tone) { 1 -> Scene.glow; 2 -> Scene.warn; else -> Scene.text }
    HomeWidget(modifier, onOpen, "${info.title} ${info.headline}, ${info.detail}${info.auto?.let { ", $it" }.orEmpty()}, 월간 현황 열기", compact) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_report), null, Modifier.size(26.dp), tint = Scene.textSecondary)
                Text(info.title, style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                DigitChange(info.headline, style = if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displaySmall, color = color, fontWeight = FontWeight.Bold)
                DigitChange(info.detail, Modifier.padding(bottom = 6.dp).weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = Scene.textMuted, maxLines = 2)
            }
            if (!compact) info.auto?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        CompletionRing(info.percent, color, diameter = if (compact) 44.dp else 104.dp, thickness = if (compact) 5.dp else 10.dp, sweepKey = "home-month-ring")
        Chevron()
    }
}

/**
 * How much of the month is confirmed, as a ring lit in the widget's tone; a dash when the month has no trips yet. The arc
 * sweeps to its value ([sweepProgress]: from empty the first time in the process, from where it stood after that, so a
 * return to 홈 does not replay it; keyed by [sweepKey]) and the label cross-fades. Decorative for TalkBack (the widget
 * speaks the numbers).
 */
@Composable internal fun CompletionRing(percent: Int?, color: Color, modifier: Modifier = Modifier, diameter: Dp = 104.dp, thickness: Dp = 10.dp,
    sweepKey: String = "completion-ring") {
    val track = Scene.ringTrack
    val accent = Scene.accent
    val sweep = sweepProgress((percent ?: 0).coerceIn(0, 100) / 100f, key = sweepKey)
    Box(modifier.size(diameter).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Spacer(Modifier.matchParentSize().drawBehind {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            val fraction = sweep.value
            if (fraction > 0f) drawArc(Brush.sweepGradient(listOf(accent, color, accent)), -90f, 360f * fraction, false,
                Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val style = when {
                diameter >= 96.dp -> MaterialTheme.typography.titleLarge
                diameter >= 64.dp -> MaterialTheme.typography.labelLarge
                else -> MaterialTheme.typography.labelMedium
            }
            DigitChange(percent?.let { "$it%" } ?: "—", style = style.copy(fontFeatureSettings = "tnum"), color = Scene.text, fontWeight = FontWeight.Bold)
            if (diameter >= 96.dp) Text("완성률", style = MaterialTheme.typography.labelSmall, color = Scene.textMuted)
        }
    }
}

/** One label-and-value line of a widget. A [live] value (telemetry) updates in place; the others cross-fade. */
@Composable private fun WidgetLine(label: String, value: String, live: Boolean = false) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Scene.textMuted, modifier = Modifier.weight(1f))
        val style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum")
        if (live) Text(value, style = style, color = Scene.text, maxLines = 1) else DigitChange(value, style = style, color = Scene.text)
    }
}

/** Two widgets side by side on the wide classes (2 × 588 at FULL), stacked in a narrow window; one 120-dp row when [compact]. */
@Composable internal fun HomeWidgets(state: TripLogState, now: Long, sideBySide: Boolean = LocalLayout.current.expanded, compact: Boolean = false, onOpenMonth: () -> Unit) {
    val layout = LocalLayout.current
    if (sideBySide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(layout.cardGap)) {
        TodayWidget(state, now, Modifier.weight(1f), compact)
        MonthWidget(state.data, now, onOpenMonth, Modifier.weight(1f), compact)
    } else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
        TodayWidget(state, now, Modifier.fillMaxWidth(), compact)
        MonthWidget(state.data, now, onOpenMonth, Modifier.fillMaxWidth(), compact)
    }
}
