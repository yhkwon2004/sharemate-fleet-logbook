package ai.sharemate.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs
import kotlin.random.Random

/**
 * The car home, 2.8: a night-city hero that greets the driver, a search-style entry to the assistant, three large
 * glass cards (차량 정보 · 운행일지 확인 · 정보 내보내기), and two widgets that say how today and this month stand.
 * Every number on it is read from the log or the vehicle; nothing is decorative except the skyline itself.
 */
// ───────────────────────── header tabs (every pane but home) ─────────────────────────

@Immutable
internal data class NavTab(val label: String, val icon: Int, val badge: Int)

/** The five destinations as one glass pill; the active tab is lit cyan. Counts sit inside the tab they belong to. */
@Composable internal fun GlassTabs(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = Scene.glass.copy(alpha = .92f), border = Scene.edge) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                // The lit state glides from the old tab to the new one on the page clock instead of jumping.
                val tint by animateColorAsState(if (active) Scene.glow else Scene.textSecondary, tabColorSpec(), label = "tab-tint")
                val fill by animateColorAsState(if (active) Scene.glow.copy(alpha = .14f) else Color.Transparent, tabColorSpec(), label = "tab-fill")
                val ring by animateColorAsState(if (active) Scene.glow.copy(alpha = .45f) else Color.Transparent, tabColorSpec(), label = "tab-ring")
                val label by animateColorAsState(if (active) Scene.glow else Scene.text, tabColorSpec(), label = "tab-label")
                Row(Modifier.clip(RoundedCornerShape(50)).background(fill)
                    .border(1.dp, ring, RoundedCornerShape(50))
                    .clickable { onSelect(index) }
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

// ───────────────────────── pure copy (unit-tested) ─────────────────────────

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

/**
 * The greeting hero: clock, brand line, greeting, the assistant entry and [cards], the three destinations.
 * Grows past [minHeight] when the font scale needs room instead of clipping.
 */
@Composable internal fun HomeHero(state: TripLogState, now: Long, compact: Boolean, minHeight: Dp, onAssistant: () -> Unit, requestPermissions: () -> Unit,
    demoControls: @Composable () -> Unit, cards: List<HomeCardSpec>) {
    val data = state.data
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour
    // No frame and no chips of its own: the shared header above carries status, employee and tabs on every page, and the
    // hero is laid straight onto the full-screen night backdrop ([HomeBackdrop]).
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = if (compact) 4.dp else 8.dp, vertical = if (compact) 4.dp else 6.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.clearAndSetSemantics { contentDescription = "${hm(now)}, ${dayLabel(localDate(now))}" }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(hm(now), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, fontWeight = FontWeight.Bold)
                Box(Modifier.width(1.dp).height(24.dp).background(Scene.divider))
                Text(dayLabel(localDate(now)), style = MaterialTheme.typography.titleMedium, color = Scene.textSecondary)
            }
            Column(Modifier.padding(vertical = if (compact) 16.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clearAndSetSemantics {}) {
                    Icon(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(30.dp), tint = Scene.brand)
                    Text("SHAREMATE  ·  FLEET JOURNAL", style = MaterialTheme.typography.labelLarge, color = Scene.brand, letterSpacing = 3.sp)
                }
                Text(homeGreeting(hour, driver?.name, open != null), style = if (compact) MaterialTheme.typography.headlineLarge else scaledDisplay(MaterialTheme.typography.displaySmall, MaterialTheme.typography.headlineLarge),
                    color = Scene.text, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(homeSubtitle(state.signal, demo, open), style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary)
                if (state.signal == SignalStatus.DENIED) PrimaryAction("차량 정보 권한 허용", onClick = requestPermissions)
                AssistantSearch(onAssistant, Modifier.padding(top = 10.dp))
            }
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (compact) cards.forEach { HomeActionCard(it, Modifier.fillMaxWidth(), horizontal = true) }
                else Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) { cards.forEach { HomeActionCard(it, Modifier.weight(1f)) } }
                demoControls()
            }
        }
    }
}

/** The search-bar entry to the assistant: it opens the sheet, where the driver picks a suggestion or types one line. */
@Composable private fun AssistantSearch(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Scene.glass.copy(alpha = .80f), border = BorderStroke(1.dp, Scene.accent.copy(alpha = .40f)),
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth().semantics { contentDescription = "운행 비서 열기. 예: 주차비 4,500원 추가해줘"; role = Role.Button }) {
        Row(Modifier.heightIn(min = 68.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(28.dp), tint = Scene.textSecondary)
            Text("운행 비서에게 물어보세요", style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1)
            Text("예: 주차비 4,500원 추가해줘", style = MaterialTheme.typography.bodyMedium, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
    }
}

/** One destination card. Vertical (icon over title) on the wide car screen; a row in narrow windows. */
@Composable internal fun HomeActionCard(spec: HomeCardSpec, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val shape = RoundedCornerShape(26.dp)
    val description = "${spec.title}, ${spec.subtitle}" + if (spec.badge > 0) ", 확인 필요 ${spec.badge}건" else ""
    Surface(onClick = spec.onClick, shape = shape, color = Scene.glass.copy(alpha = .78f), border = Scene.edge,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }) {
        Box(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent)))) {
            if (horizontal) Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconTile(spec.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleLarge, color = Scene.text, fontWeight = FontWeight.Bold)
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

/** The card icon on a blue-to-cyan tile, with a soft cyan halo behind it so the three destinations read as lit buttons. */
@Composable private fun IconTile(icon: Int) {
    val shape = RoundedCornerShape(20.dp)
    val halo = Scene.glow.copy(alpha = .30f)
    val tile = Scene.tile
    Box(Modifier.size(96.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(halo, Color.Transparent), center = center, radius = size.minDimension / 2), radius = size.minDimension / 2)
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).clip(shape).background(tile).border(Scene.edge, shape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(36.dp), tint = Scene.onTile)
        }
    }
}

@Composable internal fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).clip(RoundedCornerShape(50)).background(Scene.badge)
        .padding(horizontal = 8.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text("${count.coerceAtMost(99)}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun Chevron() =
    Text("›", style = MaterialTheme.typography.headlineSmall, color = Scene.textMuted, modifier = Modifier.clearAndSetSemantics {})

// ───────────────────────── widgets under the hero ─────────────────────────

@Composable private fun HomeWidget(modifier: Modifier, onClick: (() -> Unit)?, description: String, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val inner: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(horizontal = 30.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp), content = content)
    }
    val semantics = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = Scene.glass, border = Scene.edge, modifier = modifier.then(semantics)) { inner() }
    else Surface(shape = shape, color = Scene.glass, border = Scene.edge, modifier = modifier.then(semantics)) { inner() }
}

/** Today at a glance: distance (the live trip included), finished trips, expenses, and the odometer when the signal is fresh. */
@Composable internal fun TodayWidget(state: TripLogState, now: Long, modifier: Modifier = Modifier) {
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
    HomeWidget(modifier, null, spoken) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("오늘 운행", style = MaterialTheme.typography.labelLarge, color = Scene.textMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kmLabel, style = scaledDisplay(MaterialTheme.typography.displayMedium, MaterialTheme.typography.displaySmall).copy(fontFeatureSettings = "tnum"),
                    color = Scene.text, maxLines = 1)
                Text("km", style = MaterialTheme.typography.titleMedium, color = Scene.textMuted, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Box(Modifier.width(1.dp).height(84.dp).background(Scene.divider))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WidgetLine("완료 운행", "${finished.size}건")
            WidgetLine("등록 경비", formatWon(finished.sumOf { it.expenseWon }))
            WidgetLine("계기판", odometer?.let(::kmText) ?: "확인 필요")
        }
    }
}

/** This month in one word, like a traffic widget: 대기 · 완성 · 마감됨 · 확인 N건, and where the month stands. Opens 월간·Excel. */
@Composable internal fun MonthWidget(data: TripLogData, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val info = monthWidgetInfo(data, localDate(now), zone)
    val color = when (info.tone) { 1 -> Scene.glow; 2 -> Scene.warn; else -> Scene.text }
    HomeWidget(modifier, onOpen, "${info.title} ${info.headline}, ${info.detail}${info.auto?.let { ", $it" }.orEmpty()}, 월간 현황 열기") {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_report), null, Modifier.size(26.dp), tint = Scene.textSecondary)
                Text(info.title, style = MaterialTheme.typography.titleMedium, color = Scene.text)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(info.headline, style = MaterialTheme.typography.displaySmall, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(info.detail, style = MaterialTheme.typography.bodyLarge, color = Scene.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp).weight(1f, fill = false))
            }
            info.auto?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        CompletionRing(info.percent, color)
        Chevron()
    }
}

/** How much of the month is confirmed, as a ring lit in the widget's tone; a dash when the month has no trips yet. Decorative for TalkBack (the widget speaks the numbers). */
@Composable internal fun CompletionRing(percent: Int?, color: Color, diameter: Dp = 104.dp, thickness: Dp = 10.dp) {
    val track = Scene.ringTrack
    val accent = Scene.accent
    Box(Modifier.size(diameter).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Spacer(Modifier.matchParentSize().drawBehind {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (percent != null && percent > 0) drawArc(Brush.sweepGradient(listOf(accent, color, accent)), -90f, 360f * percent / 100f, false,
                Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Scene.text, fontWeight = FontWeight.Bold)
            Text("완성률", style = MaterialTheme.typography.labelSmall, color = Scene.textMuted)
        }
    }
}

@Composable private fun WidgetLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Scene.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1)
    }
}

/** Two widgets side by side on the wide screen, stacked in a narrow window. */
@Composable internal fun HomeWidgets(state: TripLogState, now: Long, onOpenMonth: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 760.dp) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            TodayWidget(state, now, Modifier.weight(1f))
            MonthWidget(state.data, now, onOpenMonth, Modifier.weight(1f))
        } else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            TodayWidget(state, now, Modifier.fillMaxWidth())
            MonthWidget(state.data, now, onOpenMonth, Modifier.fillMaxWidth())
        }
    }
}
