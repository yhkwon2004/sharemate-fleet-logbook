package ai.sharemate.app

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
internal object Midnight {
    val bg = Color(0xFF0B111C)
    val rail = Color(0xFF0E1625)
    val glass = Color(0xFF16213A)
    val glassBorder = Color(0x24FFFFFF)
    val text = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFC3CCDA)
    val textMuted = Color(0xFF97A3B6)
    val glow = Color(0xFF4CE3F0)
    val warn = Color(0xFFFFB45C)
    // Count badges: white digits need 4.5:1, which the brighter status red (#FF4C28, about 3.3:1) does not give.
    val badge = Color(0xFFC8321A)
    val brand = Color(0xFFE4BFA6)
    /** A lit top edge fading down the sides: the glass catches light from above, like a dashboard bezel. */
    val edge = BorderStroke(1.dp, Brush.verticalGradient(listOf(Color(0x52FFFFFF), Color(0x14FFFFFF), Color(0x0AFFFFFF))))
}

/** The car app's backdrop: deep navy with a cool glow from the upper left and a faint violet one from the lower right. */
internal fun Modifier.midnightBackdrop(): Modifier = drawWithCache {
    val cool = Brush.radialGradient(listOf(Color(0x3D2D5BFF), Color.Transparent), center = Offset(size.width * .18f, 0f), radius = size.maxDimension * .62f)
    val warm = Brush.radialGradient(listOf(Color(0x2B8A4FD8), Color.Transparent), center = Offset(size.width, size.height), radius = size.maxDimension * .55f)
    onDrawBehind { drawRect(Midnight.bg); drawRect(cool); drawRect(warm) }
}

// ───────────────────────── header tabs (every pane but home) ─────────────────────────

@Immutable
internal data class NavTab(val label: String, val icon: Int, val badge: Int)

/** The five destinations as one glass pill; the active tab is lit cyan. Counts sit inside the tab they belong to. */
@Composable internal fun GlassTabs(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(50), color = Midnight.glass.copy(alpha = .92f), border = Midnight.edge) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val tint = if (active) Midnight.glow else Midnight.textSecondary
                Row(Modifier.clip(RoundedCornerShape(50)).background(if (active) Midnight.glow.copy(alpha = .14f) else Color.Transparent)
                    .then(if (active) Modifier.border(1.dp, Midnight.glow.copy(alpha = .45f), RoundedCornerShape(50)) else Modifier)
                    .clickable { onSelect(index) }
                    .clearAndSetSemantics {
                        this.selected = active; role = Role.Tab
                        contentDescription = if (tab.badge > 0) "${tab.label}, 확인 필요 ${tab.badge}건" else tab.label
                    }
                    .heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = tint)
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, color = if (active) Midnight.glow else Midnight.text, maxLines = 1)
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

// ───────────────────────── night skyline ─────────────────────────

private class SkyTower(val left: Float, val width: Float, val height: Float, val lit: List<Offset>, val crown: Int, val cool: Boolean, val reflect: Float)
private class SkyScene(val stars: List<Triple<Float, Float, Float>>, val far: List<SkyTower>, val near: List<SkyTower>, val ridge: List<Offset>)

/** A fixed, seeded city: the same skyline every launch, denser and taller to the right so the greeting on the left stays calm. */
private fun skylineScene(): SkyScene {
    val r = Random(2026)
    val stars = List(70) { Triple(r.nextFloat(), r.nextFloat() * .40f, .25f + r.nextFloat() * .55f) }
    fun towers(from: Float, maxH: Float, minW: Float, maxW: Float, density: Float, peak: Float): List<SkyTower> {
        val out = mutableListOf<SkyTower>()
        var x = from
        while (x < 1.02f) {
            val w = minW + r.nextFloat() * (maxW - minW)
            val cluster = 1f - (abs(x - peak) / .40f).coerceIn(0f, 1f)
            val h = maxH * (.22f + .78f * cluster) * (.55f + r.nextFloat() * .45f)
            val cols = (w * 150).toInt().coerceIn(2, 5)
            val rows = (h * 44).toInt().coerceIn(3, 18)
            val lit = buildList { for (c in 0 until cols) for (row in 0 until rows) if (r.nextFloat() < density) add(Offset((c + .5f) / cols, (row + .5f) / rows)) }
            out += SkyTower(x, w, h, lit, r.nextInt(4), r.nextFloat() < .28f, if (r.nextFloat() < .45f) .06f + r.nextFloat() * .16f else 0f)
            x += w + r.nextFloat() * .005f
        }
        return out
    }
    val ridge = List(9) { i -> Offset(.30f + i * .09f, .06f + r.nextFloat() * .10f) }
    return SkyScene(stars, towers(.34f, .32f, .012f, .028f, .16f, .76f), towers(.44f, .44f, .016f, .036f, .30f, .82f), ridge)
}

/** The decorative hero background: a dusk sky, a lit city across the river, a bridge, and reflections. No semantics. */
@Composable internal fun NightSkyline(modifier: Modifier = Modifier) {
    val scene = remember { skylineScene() }
    Spacer(modifier.clearAndSetSemantics {}.drawWithCache {
        val w = size.width
        val h = size.height
        val horizon = h * .62f
        val sky = Brush.verticalGradient(0f to Color(0xFF070C1E), .38f to Color(0xFF17193F), .58f to Color(0xFF3A2852),
            .66f to Color(0xFF7A4560), .70f to Color(0xFFC9785A), endY = horizon)
        val water = Brush.verticalGradient(0f to Color(0xFF231B3A), .35f to Color(0xFF121633), 1f to Midnight.bg, startY = horizon, endY = h)
        val glow = Brush.radialGradient(listOf(Color(0x80FF9C6B), Color(0x26B05A8A), Color(0x00FF9C6B)), center = Offset(w * .76f, horizon), radius = w * .52f)
        val ridge = Path().apply {
            moveTo(w * .26f, horizon)
            scene.ridge.forEach { lineTo(w * it.x, horizon - h * it.y) }
            lineTo(w, horizon - h * .08f); lineTo(w, horizon); close()
        }
        fun bodies(list: List<SkyTower>) = Path().apply {
            list.forEach { t ->
                val left = w * t.left; val right = left + w * t.width; val top = horizon - h * t.height
                when (t.crown) {
                    2 -> { moveTo(left, horizon); lineTo(left, top + h * .03f); lineTo(left + (right - left) * .25f, top + h * .03f)
                        lineTo(left + (right - left) * .25f, top); lineTo(right - (right - left) * .25f, top); lineTo(right - (right - left) * .25f, top + h * .03f)
                        lineTo(right, top + h * .03f); lineTo(right, horizon); close() }
                    3 -> { moveTo(left, horizon); lineTo(left, top + h * .025f); lineTo(right, top); lineTo(right, horizon); close() }
                    else -> addRect(Rect(left, top, right, horizon))
                }
            }
        }
        val farBodies = bodies(scene.far)
        val nearBodies = bodies(scene.near)
        fun windows(list: List<SkyTower>, cool: Boolean) = Path().apply {
            list.filter { it.cool == cool }.forEach { t ->
                val left = w * t.left; val tw = w * t.width; val th = h * t.height * .86f; val top = horizon - h * t.height + h * t.height * .1f
                t.lit.forEach { p -> addRect(Rect(Offset(left + tw * p.x - 1.6f, top + th * p.y - 2.2f), Size(3.2f, 4.4f))) }
            }
        }
        val farWarm = windows(scene.far, false); val farCool = windows(scene.far, true)
        val nearWarm = windows(scene.near, false); val nearCool = windows(scene.near, true)
        val antennas = scene.near.filter { it.crown == 1 && it.height > .26f }
        val moon = Offset(w * .53f, h * .40f)
        val moonHalo = Brush.radialGradient(listOf(Color(0x40F6EBD3), Color.Transparent), center = moon, radius = h * .16f)
        // A soft glow around every few warm windows so the towers read as lit from inside rather than dotted.
        val bloom = Path().apply {
            scene.near.filter { !it.cool }.forEach { t ->
                val left = w * t.left; val tw = w * t.width; val th = h * t.height * .86f; val top = horizon - h * t.height + h * t.height * .1f
                t.lit.forEachIndexed { i, p -> if (i % 4 == 0) addOval(Rect(Offset(left + tw * p.x, top + th * p.y), 6f)) }
            }
        }
        onDrawBehind {
            drawRect(sky, size = Size(w, horizon))
            drawRect(glow, size = Size(w, horizon))
            scene.stars.forEach { (x, y, a) -> drawCircle(Color.White.copy(alpha = a), radius = 1.2f + a, center = Offset(w * x, h * y)) }
            // A low moon resting just above the skyline, right of the search bar so it never sits behind text or chips.
            drawCircle(moonHalo, radius = h * .16f, center = moon)
            drawCircle(Color(0xFFF6EBD3), radius = h * .038f, center = moon)
            drawCircle(Color(0x1A0B111C), radius = h * .030f, center = moon + Offset(h * .012f, -h * .006f))
            drawPath(ridge, Color(0xFF2A2350))
            drawPath(farBodies, Color(0xFF1A1C3C))
            drawPath(farWarm, Color(0x66FFCF8A)); drawPath(farCool, Color(0x557FC8FF))
            drawPath(nearBodies, Color(0xFF0E1128))
            drawPath(bloom, Color(0x14FFD9A0))
            drawPath(nearWarm, Color(0xD9FFDDA0)); drawPath(nearCool, Color(0xB39FD8FF))
            antennas.forEach { t ->
                val x = w * (t.left + t.width / 2); val top = horizon - h * t.height
                drawLine(Color(0xFF0E1128), Offset(x, top), Offset(x, top - h * .05f), strokeWidth = 2.5f)
                drawCircle(Color(0xFFFF5A4E), radius = 3.2f, center = Offset(x, top - h * .05f))
            }
            drawRect(water, topLeft = Offset(0f, horizon), size = Size(w, h - horizon))
            scene.near.filter { it.reflect > 0f }.forEach { t ->
                val left = w * (t.left + t.width * .3f); val streak = h * t.reflect
                drawRect(Brush.verticalGradient(listOf(if (t.cool) Color(0x559FD8FF) else Color(0x66FFD49A), Color.Transparent), startY = horizon, endY = horizon + streak),
                    topLeft = Offset(left, horizon), size = Size(w * t.width * .4f, streak))
            }
            // The bridge: a dark deck with a string of warm lights and their wavering reflection.
            val deck = horizon - h * .012f
            drawLine(Color(0xFF262A4E), Offset(w * .22f, deck), Offset(w, deck), strokeWidth = 5f, cap = StrokeCap.Round)
            var x = w * .23f
            while (x < w) {
                drawLine(Color(0xFF1C1F3E), Offset(x, deck), Offset(x, horizon + h * .015f), strokeWidth = 3f)
                drawCircle(Color(0xF2FFE7B8), radius = 2.6f, center = Offset(x + w * .008f, deck - 3f))
                drawLine(Color(0x55FFE7B8), Offset(x + w * .008f, horizon + h * .03f), Offset(x + w * .008f, horizon + h * .07f), strokeWidth = 2f)
                x += w * .034f
            }
            for (i in 1..5) drawLine(Color.White.copy(alpha = .045f), Offset(w * .2f, horizon + h * .05f * i), Offset(w, horizon + h * .05f * i), strokeWidth = 1.5f)
        }
    })
}

// ───────────────────────── hero ─────────────────────────

@Immutable
internal class HomeCardSpec(val title: String, val subtitle: String, val icon: Int, val badge: Int, val onClick: () -> Unit)

/**
 * The greeting hero. [header] slots the driver check row under the clock; [cards] are the three destinations.
 * Grows past [minHeight] when the font scale needs room instead of clipping.
 */
@Composable internal fun HomeHero(state: TripLogState, now: Long, compact: Boolean, minHeight: Dp, showTutorial: Boolean,
    onDriver: () -> Unit, onTutorial: () -> Unit, onAssistant: () -> Unit, onSettings: () -> Unit, requestPermissions: () -> Unit,
    demoControls: @Composable () -> Unit, cards: List<HomeCardSpec>, header: @Composable () -> Unit = {}) {
    val data = state.data
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val overview = drivingOverviewInfo(state, now, true, zone)
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour
    val shape = RoundedCornerShape(32.dp)
    Box(Modifier.fillMaxWidth().clip(shape).border(Midnight.edge, shape)) {
        NightSkyline(Modifier.matchParentSize())
        // Scrims keep the copy at 7:1 or better over the brightest part of the sky and the cards over the water.
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0f to Midnight.bg.copy(alpha = .94f), .40f to Midnight.bg.copy(alpha = .66f), .80f to Color.Transparent)))
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(.42f to Color.Transparent, 1f to Midnight.bg.copy(alpha = .86f))))
        Column(Modifier.fillMaxWidth().heightIn(min = minHeight).padding(horizontal = if (compact) 22.dp else 36.dp, vertical = if (compact) 22.dp else 28.dp),
            verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                HeroHeader(now, overview, open != null, compact, driver?.label, showTutorial, onDriver, onTutorial, onSettings)
                header()
            }
            Column(Modifier.padding(vertical = if (compact) 16.dp else 22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.clearAndSetSemantics {}) {
                    Icon(painterResource(R.drawable.ic_splash_logo), null, Modifier.size(30.dp), tint = Midnight.brand)
                    Text("SHAREMATE  ·  FLEET JOURNAL", style = MaterialTheme.typography.labelLarge, color = Midnight.brand, letterSpacing = 3.sp)
                }
                Text(homeGreeting(hour, driver?.name, open != null), style = if (compact) MaterialTheme.typography.headlineLarge else scaledDisplay(MaterialTheme.typography.displaySmall, MaterialTheme.typography.headlineLarge),
                    color = Midnight.text, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                Text(homeSubtitle(state.signal, demo, open), style = MaterialTheme.typography.bodyLarge, color = Midnight.textSecondary)
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

@Composable private fun HeroHeader(now: Long, overview: DrivingOverviewInfo, recording: Boolean, compact: Boolean, driverLabel: String?, showTutorial: Boolean,
    onDriver: () -> Unit, onTutorial: () -> Unit, onSettings: () -> Unit) {
    val pleos = LocalPleosColors.current
    val clock: @Composable () -> Unit = {
        Row(Modifier.semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(hm(now), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = Midnight.text, fontWeight = FontWeight.Bold)
            Box(Modifier.width(1.dp).height(24.dp).background(Color.White.copy(alpha = .35f)))
            Text(dayLabel(localDate(now)), style = MaterialTheme.typography.titleMedium, color = Midnight.textSecondary)
        }
    }
    val chips: @Composable () -> Unit = {
        val dot = when {
            !overview.fresh -> pleos.negative
            recording -> pleos.active
            overview.demo -> Midnight.glow
            else -> pleos.active.copy(alpha = .55f)
        }
        // Polite live region: TalkBack announces recording start/stop and storage trouble without a touch.
        GlassChip(null, Modifier.semantics(mergeDescendants = true) { contentDescription = "기록 상태 ${overview.status}"; liveRegion = LiveRegionMode.Polite }) {
            Box(Modifier.size(12.dp).clip(CircleShape).background(dot))
            Text(overview.status, style = MaterialTheme.typography.labelLarge, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassChip(onDriver, Modifier.semantics { contentDescription = "이용 직원 ${driverLabel ?: "미선택"}, 변경" }) {
            Column {
                Text("이용 직원", style = MaterialTheme.typography.labelSmall, color = Midnight.textSecondary)
                Text(driverLabel ?: "직원 선택", style = MaterialTheme.typography.labelLarge, color = Midnight.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 220.dp))
            }
        }
        if (showTutorial) GlassChip(onTutorial, Modifier.semantics { role = Role.Button }) {
            Text("시연 체험", style = MaterialTheme.typography.labelLarge, color = Midnight.text)
        }
        // With no side rail, settings live here on home; every other pane has them in its header tabs.
        GlassChip(onSettings, Modifier.semantics { contentDescription = "설정 열기"; role = Role.Button }) {
            Icon(painterResource(R.drawable.ic_tune), null, Modifier.size(24.dp), tint = Midnight.text)
            Text("설정", style = MaterialTheme.typography.labelLarge, color = Midnight.text)
        }
    }
    if (compact) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        clock()
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) { chips() }
    } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        clock()
        Spacer(Modifier.weight(1f))
        chips()
    }
}

/** A frosted pill on the hero; tappable when [onClick] is set. 56 dp tall to stay a car-sized target. */
@Composable private fun GlassChip(onClick: (() -> Unit)?, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    val inner: @Composable () -> Unit = {
        Row(Modifier.heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
    // Near-opaque glass: the chips sit over the brightest part of the sunset, where a thin tint would drop text below 4.5:1.
    if (onClick != null) Surface(onClick = onClick, modifier = modifier, shape = shape, color = Midnight.glass.copy(alpha = .88f), border = Midnight.edge) { inner() }
    else Surface(modifier = modifier, shape = shape, color = Midnight.glass.copy(alpha = .88f), border = Midnight.edge) { inner() }
}

/** The search-bar entry to the assistant: it opens the sheet, where the driver picks a suggestion or types one line. */
@Composable private fun AssistantSearch(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = Midnight.glass.copy(alpha = .80f), border = BorderStroke(1.dp, Color(0x477FA6FF)),
        modifier = modifier.widthIn(max = 680.dp).fillMaxWidth().semantics { contentDescription = "운행 비서 열기. 예: 주차비 4,500원 추가해줘"; role = Role.Button }) {
        Row(Modifier.heightIn(min = 68.dp).padding(horizontal = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_search), null, Modifier.size(28.dp), tint = Color(0xFFDCE6F5))
            Text("운행 비서에게 물어보세요", style = MaterialTheme.typography.titleMedium, color = Midnight.text, maxLines = 1)
            Text("예: 주차비 4,500원 추가해줘", style = MaterialTheme.typography.bodyMedium, color = Midnight.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f))
        }
    }
}

/** One destination card. Vertical (icon over title) on the wide car screen; a row in narrow windows. */
@Composable internal fun HomeActionCard(spec: HomeCardSpec, modifier: Modifier = Modifier, horizontal: Boolean = false) {
    val shape = RoundedCornerShape(26.dp)
    val description = "${spec.title}, ${spec.subtitle}" + if (spec.badge > 0) ", 확인 필요 ${spec.badge}건" else ""
    Surface(onClick = spec.onClick, shape = shape, color = Midnight.glass.copy(alpha = .78f), border = Midnight.edge,
        modifier = modifier.semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }) {
        Box(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent)))) {
            if (horizontal) Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                IconTile(spec.icon)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spec.title, style = MaterialTheme.typography.titleLarge, color = Midnight.text, fontWeight = FontWeight.Bold)
                    Text(spec.subtitle, style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (spec.badge > 0) CountBadge(spec.badge)
                Chevron()
            } else Column(Modifier.fillMaxWidth().heightIn(min = 184.dp).padding(horizontal = 20.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                Box { IconTile(spec.icon); if (spec.badge > 0) CountBadge(spec.badge, Modifier.align(Alignment.TopEnd).padding(start = 52.dp)) }
                Text(spec.title, style = MaterialTheme.typography.headlineSmall, color = Midnight.text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(spec.subtitle, style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary, textAlign = TextAlign.Center,
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
    Box(Modifier.size(96.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(Midnight.glow.copy(alpha = .30f), Color.Transparent), center = center, radius = size.minDimension / 2), radius = size.minDimension / 2)
    }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(72.dp).clip(shape).background(Brush.linearGradient(listOf(Color(0x803B6FFF), Color(0x6639D5F0)))).border(Midnight.edge, shape),
            contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), null, Modifier.size(36.dp), tint = Color(0xFFF2F8FF))
        }
    }
}

@Composable private fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    Box(modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).clip(RoundedCornerShape(50)).background(Midnight.badge)
        .padding(horizontal = 8.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Text("${count.coerceAtMost(99)}", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun Chevron() =
    Text("›", style = MaterialTheme.typography.headlineSmall, color = Midnight.textMuted, modifier = Modifier.clearAndSetSemantics {})

// ───────────────────────── widgets under the hero ─────────────────────────

@Composable private fun HomeWidget(modifier: Modifier, onClick: (() -> Unit)?, description: String, content: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val inner: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().heightIn(min = 150.dp).padding(horizontal = 30.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(28.dp), content = content)
    }
    val semantics = Modifier.semantics(mergeDescendants = true) { contentDescription = description }
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = Midnight.glass, border = Midnight.edge, modifier = modifier.then(semantics)) { inner() }
    else Surface(shape = shape, color = Midnight.glass, border = Midnight.edge, modifier = modifier.then(semantics)) { inner() }
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
            Text("오늘 운행", style = MaterialTheme.typography.labelLarge, color = Midnight.textMuted)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(kmLabel, style = scaledDisplay(MaterialTheme.typography.displayMedium, MaterialTheme.typography.displaySmall).copy(fontFeatureSettings = "tnum"),
                    color = Midnight.text, maxLines = 1)
                Text("km", style = MaterialTheme.typography.titleMedium, color = Midnight.textMuted, modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Box(Modifier.width(1.dp).height(84.dp).background(Color.White.copy(alpha = .14f)))
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
    val color = when (info.tone) { 1 -> Midnight.glow; 2 -> Midnight.warn; else -> Midnight.text }
    HomeWidget(modifier, onOpen, "${info.title} ${info.headline}, ${info.detail}${info.auto?.let { ", $it" }.orEmpty()}, 월간 현황 열기") {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_report), null, Modifier.size(26.dp), tint = Midnight.textSecondary)
                Text(info.title, style = MaterialTheme.typography.titleMedium, color = Midnight.text)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(info.headline, style = MaterialTheme.typography.displaySmall, color = color, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(info.detail, style = MaterialTheme.typography.bodyLarge, color = Midnight.textMuted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 6.dp).weight(1f, fill = false))
            }
            info.auto?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        CompletionRing(info.percent, color)
        Chevron()
    }
}

/** How much of the month is confirmed, as a ring lit in the widget's tone; a dash when the month has no trips yet. Decorative for TalkBack (the widget speaks the numbers). */
@Composable private fun CompletionRing(percent: Int?, color: Color) {
    Box(Modifier.size(104.dp).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Spacer(Modifier.matchParentSize().drawBehind {
            val stroke = 10.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = .10f), -90f, 360f, false, Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
            if (percent != null && percent > 0) drawArc(Brush.sweepGradient(listOf(Color(0xFF3B6FFF), color, Color(0xFF3B6FFF))), -90f, 360f * percent / 100f, false,
                Offset(inset, inset), arc, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = StrokeCap.Round))
        })
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"), color = Midnight.text, fontWeight = FontWeight.Bold)
            Text("완성률", style = MaterialTheme.typography.labelSmall, color = Midnight.textMuted)
        }
    }
}

@Composable private fun WidgetLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Midnight.textMuted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Midnight.text, maxLines = 1)
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
