package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/*
 * The phone in the car's light (2.10): the same scene tokens, the city behind the home tab in the current phase, a greeting,
 * the four destinations the car home has (차량 정보 · 차량 예약 · 운행일지 확인 · 정보 내보내기), today and this month as widgets,
 * and a floating glass tab bar (a left rail in landscape). The phone still only shows and confirms what the car recorded; the
 * car stays the original. Layout follows LocalLayout: content keeps to its class's width, centred, with cards on an 8-dp grid.
 */

// ───────────────────────── pure copy and layout rules (unit-tested) ─────────────────────────

@Immutable
internal data class PhoneCardLines(val vehicle: String, val log: String, val logPending: Int, val export: String, val exportMonth: YearMonth, val booking: String)

/**
 * Short lines for the four phone cards, from the phone's copy (current mode only). The log count is the 운행 tab's own badge
 * (unclassified trips), so the card and the tab it opens never disagree; the export names the month 현황 opens on.
 */
internal fun phoneCardLines(data: TripLogData, today: LocalDate, zone: ZoneId, now: Long = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
    socPercent: Int? = null): PhoneCardLines {
    val mirror = data.copy(trips = phoneTrips(data))
    val care = careDueAlerts(data.care, data.vehicle.odometerKm, today, emptySet()).firstOrNull()?.title
    val vehicle = care ?: socPercent?.let { "배터리 $it%" } ?: data.vehicle.socPercent?.takeUnless { data.profile.demoMode }?.let { "마지막 저장 잔량 $it%" } ?: data.vehicle.odometerKm?.takeIf { it > 0 }?.let { "마지막 ${kmText(it)}" } ?: "차량 상태"
    val month = YearMonth.from(today)
    val monthTrips = tripsIn(mirror.trips, month.atDay(1), month.atEndOfMonth(), zone)
    val pending = mirror.trips.count { it.purpose == TripPurpose.UNCLASSIFIED && !it.open }
    val log = when {
        pending > 0 -> "미분류 ${pending}건"
        monthTrips.isEmpty() -> "${month.monthValue}월 운행 없음"
        else -> "${monthTrips.size}건 · 모두 확인"
    }
    val target = monthCloseTarget(mirror, today, zone)
    val exportMonth = if (target != null && target.month != month) target.month else month
    val export = if (exportMonth != month) "${monthLabel(exportMonth, today)} 마감 전 Excel" else "${month.monthValue}월 Excel"
    val booking = when (val a = vehicleAvailability(mirror, now)) {
        is VehicleAvailability.InUse -> "지금 운행 중"
        is VehicleAvailability.Reserved -> "예약 사용 중"
        is VehicleAvailability.Free -> a.next?.let { "다음 ${reservationStartText(it, now, zone)}" } ?: "지금 예약 가능"
    }
    return PhoneCardLines(vehicle, log, pending, export, exportMonth, booking)
}

/** The line under the phone greeting: the live link, or what the copy is when there is no car to ask. */
internal fun phoneSubtitle(paired: Boolean, connected: Boolean, open: Trip?): String = when {
    open != null -> "지금 운행 중 · ${hm(open.startAt)} 출발 · ${kmText(open.distanceKm)}"
    paired && connected -> "차량 기록을 실시간으로 받고 있어요"
    paired -> "연결 대기 · 마지막으로 받은 기록을 보여 줘요"
    else -> "파일 사본을 보고 있어요 · 설정에서 차량과 연결할 수 있어요"
}

/** Today on the phone copy: finished trips that started today, plus the open trip when it started today; and their expenses. */
internal data class PhoneToday(val km: Double, val trips: Int, val expenseWon: Long)

internal fun phoneToday(data: TripLogData, today: LocalDate, zone: ZoneId): PhoneToday {
    val trips = tripsIn(phoneTrips(data), today, today, zone)
    val open = data.open?.takeIf { it.demo == data.profile.demoMode && Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == today }
    // The open trip counts in both the distance and the number, so the two never disagree.
    return PhoneToday(trips.sumOf { it.distanceKm } + (open?.distanceKm ?: 0.0), trips.size + (if (open != null) 1 else 0), trips.sumOf { it.expenseWon })
}

/** Destination cards per row: one at a 1.3× font (titles never clip), four when the cards' width is a tablet's, else two. */
internal fun phoneHeroColumns(available: Dp, fontScale: Float): Int = when {
    fontScale > 1.25f -> 1
    available / fontScale.coerceAtLeast(1f) >= 640.dp -> 4
    else -> 2
}

/** Landscape on a phone (wider than tall and under 600 dp tall): the tabs become a left rail so the short height keeps the content. */
internal fun phoneUsesRail(width: Dp, height: Dp): Boolean = width > height && height < 600.dp

/**
 * The home puts the greeting beside the cards (40/60) only on a phone in landscape: wider than tall, under 600 dp tall (a tablet
 * keeps the stacked hero with four cards across) and at least 520 dp wide; never at a 1.3× font, where every card gets its own row.
 */
internal fun phoneHomeSplit(width: Dp, height: Dp, fontScale: Float = 1f): Boolean =
    fontScale <= 1.25f && width > height && height < 600.dp && width >= 520.dp

/** Where the day haze reaches its second stop: the measured greeting bottom when there is one, else about a quarter of the backdrop. */
internal fun phoneHazeEnd(greetingBottom: Float, height: Float): Float =
    if (greetingBottom > 0f && greetingBottom < height) greetingBottom else height * .26f

/** What the phone home lays over the sky, reported where it rests (root px) so the backdrop keeps its sun or moon clear of it. */
internal enum class PhoneSkyShape { DATE, HEADLINE, SUBTITLE, CARDS, HEADER_ICONS }

/** The phone backdrop's tower scale, and the day sun's disc as a share of the tower height (Sky.kt; the largest of the three phases). */
private const val PHONE_SKY_TOWERS = .30f
private const val PHONE_ORB_DISC = .045f

/** How long the home's shapes rest before the sun or moon follows them: longer than a page swap (220 ms, 40 ms enter delay). */
internal const val PHONE_ORB_SETTLE_MS = 300L

/**
 * Where the phone home's sun or moon rests, as fractions of the window: the free sky right of the greeting in portrait, and in
 * the landscape split the corner right of the header icons, above the card grid.
 */
internal fun phoneOrbAt(width: Dp, height: Dp, fontScale: Float = 1f): Offset =
    if (phoneHomeSplit(width, height, fontScale)) Offset(.93f, .12f) else Offset(.90f, .175f)

/** How far from its centre the sun or moon needs free sky in a window [height] tall: the day disc plus an 8-dp margin. */
internal fun phoneOrbReach(height: Dp): Dp = height * (PHONE_SKY_TOWERS * PHONE_ORB_DISC) + 8.dp

/** Whether the sun or moon at [center] (root px) with [reach] touches none of [shapes] (the greeting's lines, the cards, the header icons). */
internal fun phoneOrbClear(center: Offset, reach: Float, shapes: Collection<Rect>): Boolean {
    val orb = Rect(center, reach)
    return shapes.none { it.overlaps(orb) }
}

/** Reports where this element rests (root px) as [shape], for the backdrop's sun or moon; nothing when [onShape] is null. */
internal fun Modifier.reportSkyShape(shape: PhoneSkyShape, onShape: ((PhoneSkyShape, Rect) -> Unit)?): Modifier =
    if (onShape == null) this else onGloballyPositioned { onShape(shape, it.boundsInRoot()) }

/**
 * What a text's words cover (root px): its [box] cut back to the furthest line end in [lineRights] (px from the box's left).
 * A soft-wrapped Text measures as wide as its constraint, so a two-line greeting's box runs under the sun or moon even when
 * both lines end well before it. Until the lines are known, the box itself.
 */
internal fun phoneInkRect(box: Rect, lineRights: List<Float>): Rect {
    val end = lineRights.maxOrNull() ?: return box
    return Rect(box.left, box.top, (box.left + end).coerceIn(box.left, box.right), box.bottom)
}

/** A reported text's line ends (px from its left), kept from its layout for its positioning; plain fields, so nothing recomposes. */
private class SkyInk {
    var lineRights: List<Float> = emptyList()
    fun record(layout: TextLayoutResult) { lineRights = List(layout.lineCount) { layout.getLineRight(it) } }
}

/** [reportSkyShape] for a text: where its lines end ([phoneInkRect]), from the line ends [ink] kept at its last layout. */
private fun Modifier.reportSkyInk(shape: PhoneSkyShape, onShape: ((PhoneSkyShape, Rect) -> Unit)?, ink: SkyInk): Modifier =
    if (onShape == null) this else onGloballyPositioned { onShape(shape, phoneInkRect(it.boundsInRoot(), ink.lineRights)) }

// ───────────────────────── glass card helpers ─────────────────────────

/** The drop shadow under a tappable glass card: Scene.shadow, which is transparent by night so nothing is drawn there. */
@Composable internal fun Modifier.cardShadow(shape: Shape): Modifier {
    val tone = Scene.shadow
    val strength = (tone.alpha / .12f).coerceIn(0f, 1f)
    return if (strength <= 0f) this else this.shadow(6.dp, shape, clip = false, ambientColor = tone.copy(alpha = strength), spotColor = tone.copy(alpha = strength))
}

/**
 * The glass alpha of a card that casts [cardShadow]: opaque while a shadow is drawn, since the platform leaves the area under the
 * caster unshaded and translucent glass then shows a grey ring around a hard-edged lighter box; [alpha] by night, when Scene.shadow
 * is transparent and there is no shadow to hide.
 */
internal fun shadowedGlassAlpha(shadow: Color, alpha: Float): Float = if (shadow.alpha > 0f) 1f else alpha

/** The one material of the phone home's shadowed cards and widgets: Scene.glass, opaque by day, [alpha] by night. */
@Composable @ReadOnlyComposable internal fun shadowedGlass(alpha: Float = .88f): Color =
    Scene.glass.copy(alpha = shadowedGlassAlpha(Scene.shadow, alpha))

/**
 * The top stop of a glass card's sheen: the scene's lit edge at a quarter by night; none by day, where the edge token is a navy
 * hairline and a sheen drawn from it greys the white card's top (the car's HomeActionCard draws none by day either).
 */
internal fun glassSheenTop(colors: SceneColors): Color? = if (colors.isDay) null else colors.edgeTop.let { it.copy(alpha = it.alpha * .25f) }

/** The faint top sheen of a glass card by night ([glassSheenTop]; pass `Scene.colors`); by day the card stays plain white glass. */
internal fun Modifier.glassSheen(colors: SceneColors): Modifier {
    val top = glassSheenTop(colors) ?: return this
    return background(Brush.verticalGradient(listOf(top, Color.Transparent)))
}

// ───────────────────────── home hero ─────────────────────────

/**
 * The phone home's top: the date line, the greeting, the link line and the four destination cards (2 × 2 on a phone, four
 * across on a tablet, one per row at a 1.3× font). Drawn over the backdrop the shell lays behind the home tab; [onGreetingBottom]
 * reports where the greeting ends (root px) so the day haze can stop just under it, and [onShape] where the words and the cards
 * rest, so the sun or moon stays in free sky. No bottom padding: the home column spaces its sections.
 */
@Composable internal fun PhoneHomeHero(data: TripLogData, link: PhoneLink, paired: Boolean, now: Long, onVehicle: () -> Unit, onBook: () -> Unit,
    onLog: () -> Unit, onExport: (YearMonth) -> Unit, modifier: Modifier = Modifier, columns: Int = 2, onGreetingBottom: ((Float) -> Unit)? = null,
    onShape: ((PhoneSkyShape, Rect) -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PhoneHomeGreeting(data, link, paired, now, onBottom = onGreetingBottom, onShape = onShape)
        PhoneHomeCards(data, link, now, columns, onVehicle, onBook, onLog, onExport, Modifier.padding(top = 8.dp).reportSkyShape(PhoneSkyShape.CARDS, onShape))
    }
}

/**
 * The greeting block: the date in the brand tone, one headline that cross-fades when it changes, and the link line. [onShape]
 * gets each line's resting box (root px; only as wide as its words reach, [phoneInkRect], even when a line wraps), so the shell
 * can keep the sun or moon beside them, never under them.
 */
@Composable internal fun PhoneHomeGreeting(data: TripLogData, link: PhoneLink, paired: Boolean, now: Long, modifier: Modifier = Modifier,
    onBottom: ((Float) -> Unit)? = null, headlineLines: Int = 2, onShape: ((PhoneSkyShape, Rect) -> Unit)? = null) {
    val zone = remember { ZoneId.systemDefault() }
    val at = Instant.ofEpochMilli(now).atZone(zone)
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    Column(modifier.fillMaxWidth().then(if (onBottom != null) Modifier.onGloballyPositioned { onBottom(it.boundsInRoot().bottom) } else Modifier),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The phone status bar already shows the time and the header the brand, so the hero opens with the date in the brand tone.
        Text("${dayLabel(at.toLocalDate())}  ·  FLEET JOURNAL", style = MaterialTheme.typography.labelLarge, color = Scene.brand, letterSpacing = 1.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.clearAndSetSemantics {}.reportSkyShape(PhoneSkyShape.DATE, onShape))
        GreetingText(homeGreeting(at.hour, driver?.name, open != null), maxLines = headlineLines, onShape = onShape)
        // No live region here: the distance in this line changes on every poll while driving; the link badge announces state.
        val subtitleInk = remember { SkyInk() }
        Text(phoneSubtitle(paired, link.connected, open), style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
            onTextLayout = { subtitleInk.record(it) }, modifier = Modifier.reportSkyInk(PhoneSkyShape.SUBTITLE, onShape, subtitleInk))
    }
}

/**
 * The headline swaps with a 220 ms cross-fade and a 4 dp lift when its words change (a new employee, a trip starting), and its
 * height follows on the same clock, unclipped so the lift never cuts the words; all of it snaps under reduced motion.
 */
@Composable private fun GreetingText(text: String, modifier: Modifier = Modifier, maxLines: Int = 2, onShape: ((PhoneSkyShape, Rect) -> Unit)? = null) {
    val reduce = LocalReduceMotion.current
    val lift = with(LocalDensity.current) { 4.dp.roundToPx() }
    val color = Scene.text
    AnimatedContent(targetState = text, modifier = modifier, label = "greeting", transitionSpec = {
        val swap = if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
            else (fadeIn(tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing)) + slideInVertically(tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing)) { lift }) togetherWith
                (fadeOut(tween(140, easing = FastOutSlowInEasing)) + slideOutVertically(tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing)) { -lift })
        swap using SizeTransform(clip = false) { _, _ -> if (reduce) snap() else tween(MOTION_ENTER_MS, easing = FastOutSlowInEasing) }
    }) { value ->
        // The slot stays full width. Only the arriving headline reports, and it reports where its lines end: wrapped to two lines
        // the Text is as wide as the column, which would keep the sun or moon away all afternoon.
        val ink = remember { SkyInk() }
        Text(value, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
            onTextLayout = { ink.record(it) },
            modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.Start).semantics { heading() }
                .reportSkyInk(PhoneSkyShape.HEADLINE, onShape.takeIf { value == text }, ink))
    }
}

@Immutable
private class PhoneCard(val title: String, val subtitle: String, val icon: Int, val badge: Int, val onClick: () -> Unit)

/** The four destinations as glass cards, [columns] to a row; a short row is padded so every card keeps the same width. */
@Composable internal fun PhoneHomeCards(data: TripLogData, link: PhoneLink, now: Long, columns: Int, onVehicle: () -> Unit, onBook: () -> Unit, onLog: () -> Unit,
    onExport: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val demo = data.profile.demoMode
    val soc = link.live?.takeIf { it.demo == demo }?.socPercent
    // The minute is enough for the booking line; the clock tick does not rebuild the card copy every second.
    val minute = now / 60_000
    val lines = remember(data, today, minute, soc) { phoneCardLines(data, today, zone, now, soc) }
    val incidents = remember(data) { openIncidents(data).size }
    val cards = listOf(
        PhoneCard("차량 정보", lines.vehicle, R.drawable.ic_vehicle, incidents, onVehicle),
        PhoneCard("차량 예약", lines.booking, R.drawable.ic_calendar, 0, onBook),
        PhoneCard("운행일지 확인", lines.log, R.drawable.ic_journal, lines.logPending, onLog),
        PhoneCard("정보 내보내기", lines.export, R.drawable.ic_export, 0) { onExport(lines.exportMonth) })
    val gap = LocalLayout.current.cardGap
    val per = columns.coerceIn(1, cards.size)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        cards.chunked(per).forEach { row ->
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                row.forEach { card -> PhoneActionCard(card, vertical = per >= 4, Modifier.weight(1f).fillMaxHeight()) }
                repeat(per - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * A glass destination card: lit icon tile, the title on one line, one real line under it. At least 84 dp tall; .97 press scale,
 * the day shadow on opaque glass (.88 glass by night, where there is no shadow).
 */
@Composable private fun PhoneActionCard(card: PhoneCard, vertical: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(20.dp)
    val source = remember { MutableInteractionSource() }
    val description = "${card.title}, ${card.subtitle}" + if (card.badge > 0) ", 확인 필요 ${card.badge}건" else ""
    Surface(onClick = card.onClick, shape = shape, color = shadowedGlass(.88f), border = Scene.edge, interactionSource = source,
        modifier = modifier.cardShadow(shape).pressable(source).semantics(mergeDescendants = true) { contentDescription = description; role = Role.Button }) {
        val tile: @Composable () -> Unit = {
            Box {
                val tileShape = RoundedCornerShape(13.dp)
                Box(Modifier.size(44.dp).clip(tileShape).background(Scene.tile).border(Scene.edge, tileShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(card.icon), null, Modifier.size(24.dp), tint = Scene.onTile)
                }
                if (card.badge > 0) CountBadge(card.badge, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp))
            }
        }
        val lines: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.title, style = MaterialTheme.typography.titleSmall, color = Scene.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                DigitChange(card.subtitle, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 2)
            }
        }
        val gloss = Modifier.fillMaxWidth().glassSheen(Scene.colors)
        if (vertical) Column(gloss.heightIn(min = 120.dp).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { tile(); lines() }
        else Row(gloss.heightIn(min = 84.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            tile()
            Box(Modifier.weight(1f)) { lines() }
        }
    }
}

// ───────────────────────── widgets ─────────────────────────

/** Today and this month side by side (or [stacked]): numbers cross-fade when they change, the month ring sweeps to its value. */
@Composable internal fun PhoneWidgets(data: TripLogData, now: Long, onMonth: () -> Unit, modifier: Modifier = Modifier, onToday: (() -> Unit)? = null,
    stacked: Boolean = false) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = remember(data, today) { phoneToday(data, today, zone) }
    val month = remember(data, today) { monthWidgetInfo(data.copy(trips = phoneTrips(data)), today, zone) }
    val live = data.open?.takeIf { it.demo == data.profile.demoMode } != null
    val gap = LocalLayout.current.cardGap
    if (stacked) Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        PhoneTodayWidget(day, live, onToday, Modifier.fillMaxWidth())
        PhoneMonthWidget(month, onMonth, Modifier.fillMaxWidth())
    } else Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
        PhoneTodayWidget(day, live, onToday, Modifier.weight(1f).fillMaxHeight())
        PhoneMonthWidget(month, onMonth, Modifier.weight(1f).fillMaxHeight())
    }
}

/** 오늘 운행: the distance (plain while a trip is open, since live telemetry never animates), trips and expenses. */
@Composable private fun PhoneTodayWidget(day: PhoneToday, live: Boolean, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val source = remember { MutableInteractionSource() }
    val spoken = "오늘 운행 ${kmText(day.km)}, ${day.trips}건, 경비 ${formatWon(day.expenseWon)}"
    val km = String.format(Locale.KOREA, "%,.1f", day.km)
    val numberStyle = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum")
    val content: @Composable () -> Unit = {
        WidgetBody("오늘 운행") {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (live) Text(km, style = numberStyle, color = Scene.text, maxLines = 1)
                else DigitChange(km, style = numberStyle, color = Scene.text)
                Text("km", style = MaterialTheme.typography.labelLarge, color = Scene.textMuted, modifier = Modifier.padding(bottom = 4.dp))
            }
            DigitChange("${day.trips}건 · ${formatWon(day.expenseWon)}", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
        }
    }
    // The destination cards' material: opaque glass under the day shadow, .88 by night.
    val glass = shadowedGlass(.88f)
    if (onClick != null) Surface(onClick = onClick, shape = shape, color = glass, border = Scene.edge, interactionSource = source,
        modifier = modifier.cardShadow(shape).pressable(source).semantics(mergeDescendants = true) { contentDescription = "$spoken, 운행 열기"; role = Role.Button }) { content() }
    else Surface(shape = shape, color = glass, border = Scene.edge, modifier = modifier.semantics(mergeDescendants = true) { contentDescription = spoken }) { content() }
}

/** 이달 운행일지: one word for the month in its tone and the completion ring. Opens 현황. */
@Composable private fun PhoneMonthWidget(month: MonthWidgetInfo, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(22.dp)
    val source = remember { MutableInteractionSource() }
    val tone = when (month.tone) { 1 -> Scene.glow; 2 -> Scene.warn; else -> Scene.text }
    Surface(onClick = onClick, shape = shape, color = shadowedGlass(.88f), border = Scene.edge, interactionSource = source,
        modifier = modifier.cardShadow(shape).pressable(source).semantics(mergeDescendants = true) { contentDescription = "${month.title} ${month.headline}, ${month.detail}, 현황 열기"; role = Role.Button }) {
        WidgetBody("이달 운행일지", trailing = { PhoneRing(month.percent, tone, 52.dp, 6.dp) }) {
            DigitChange(month.headline, style = MaterialTheme.typography.titleMedium, color = tone, fontWeight = FontWeight.Bold, maxLines = 3)
        }
    }
}

/**
 * The two widgets' one layout, so their labels line up across the row: 16-dp padding, the label at the top left and the value
 * 6 dp under it; an optional [trailing] part (the month ring) sits centred at the end. The cards' night sheen lies under it.
 */
@Composable private fun WidgetBody(label: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null, value: @Composable () -> Unit) {
    Row(modifier.fillMaxHeight().heightIn(min = 120.dp).glassSheen(Scene.colors).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
            value()
        }
        if (trailing != null) Box(Modifier.align(Alignment.CenterVertically)) { trailing() }
    }
}

/** The phone month ring's name for [sweepProgress]: once it has swept in this process, returning to 홈 shows it at its value. */
private const val PHONE_MONTH_RING_KEY = "phone-month-ring"

/**
 * The month's completion as a small ring: the shared [sweepProgress] eases it from the previous value to the new one (600 ms the
 * first time, starting 120 ms after the card lands; 320 ms later; never replayed on a return to 홈) and the label cross-fades
 * instead of counting. Decorative for TalkBack; the widget speaks the numbers.
 */
@Composable private fun PhoneRing(percent: Int?, color: Color, diameter: Dp, thickness: Dp) {
    val track = Scene.ringTrack
    val accent = Scene.accent
    val text = Scene.text
    val sweep = sweepProgress((percent ?: 0).coerceIn(0, 100) / 100f, key = PHONE_MONTH_RING_KEY)
    Box(Modifier.size(diameter).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        Spacer(Modifier.matchParentSize().drawBehind {
            val stroke = thickness.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, -90f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            // The sweep is read here, in the draw, so only the ring redraws while it moves.
            val degrees = 360f * sweep.value
            if (degrees > 0f) drawArc(Brush.sweepGradient(listOf(accent, color, accent)), -90f, degrees, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        })
        DigitChange(percent?.let { "$it%" } ?: "—", style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), color = text, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────── bottom tabs ─────────────────────────

private val NavItemShape = RoundedCornerShape(24.dp)

/**
 * One tab: icon and label, lit in the glow when active, .97 press scale and a bounded ripple. A page change moves only the pill
 * and the tint (spec C: tab bars do not animate beyond that), so the icon keeps its size.
 */
@Composable private fun NavItem(tab: NavTab, active: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    // The lit state glides from the old tab to the new one on the page clock instead of jumping.
    val tint by animateColorAsState(if (active) Scene.glow else Scene.textSecondary, tabColorSpec(), label = "tab-tint")
    val label by animateColorAsState(if (active) Scene.glow else Scene.text, tabColorSpec(), label = "tab-label")
    Column(modifier.clip(NavItemShape).pressable(source).clickable(interactionSource = source, indication = LocalIndication.current, onClick = onClick)
        .clearAndSetSemantics {
            this.selected = active; role = Role.Tab
            contentDescription = if (tab.badge > 0) "${tab.label}, 확인 필요 ${tab.badge}건" else tab.label
        }
        .padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
        Box {
            Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = tint)
            if (tab.badge > 0) CountBadge(tab.badge, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp))
        }
        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = label, maxLines = 1)
    }
}

/**
 * The phone's five tabs as one floating glass pill, 72 dp tall with 10-dp margins, no wider than 560 dp and centred: the lit
 * pill (selection fill + 1.5-dp ring) glides to the chosen tab on the page clock while the icon and label tint with it. The
 * pages scroll on under it, so it is opaque glass by day (no words ghost through the tab labels) and .94 glass by night.
 */
@Composable internal fun PhoneGlassNav(tabs: List<NavTab>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(36.dp)
    val gap = 4.dp
    val fill = Scene.selectionFill
    val ring = Scene.selectionRing
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = shape, color = shadowedGlass(.94f), border = Scene.edge, modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().cardShadow(shape)) {
            BoxWithConstraints(Modifier.fillMaxWidth().padding(6.dp).height(60.dp)) {
                val n = tabs.size.coerceAtLeast(1)
                val tabWidth = (maxWidth - gap * (n - 1)) / n
                val x by animateDpAsState((tabWidth + gap) * selected.coerceIn(0, n - 1), tabColorSpec(), label = "nav-pill")
                Box(Modifier.offset { IntOffset(x.roundToPx(), 0) }.width(tabWidth).fillMaxHeight().clip(NavItemShape).background(fill).border(1.5.dp, ring, NavItemShape))
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    tabs.forEachIndexed { index, tab -> NavItem(tab, index == selected, Modifier.weight(1f).fillMaxHeight()) { onSelect(index) } }
                }
            }
        }
    }
}

/** Landscape on a phone: the same tabs as an 80-dp glass rail at the start, the lit pill gliding up and down; opaque by day, .94 by night. */
@Composable internal fun PhoneGlassRail(tabs: List<NavTab>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    val gap = 4.dp
    val item = 56.dp
    val fill = Scene.selectionFill
    val ring = Scene.selectionRing
    Box(modifier.fillMaxHeight().padding(start = 10.dp, top = 10.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Surface(shape = shape, color = shadowedGlass(.94f), border = Scene.edge, modifier = Modifier.width(80.dp).cardShadow(shape)) {
            Box(Modifier.padding(6.dp).width(68.dp)) {
                val y by animateDpAsState((item + gap) * selected.coerceIn(0, tabs.lastIndex.coerceAtLeast(0)), tabColorSpec(), label = "rail-pill")
                Box(Modifier.offset { IntOffset(0, y.roundToPx()) }.fillMaxWidth().height(item).clip(NavItemShape).background(fill).border(1.5.dp, ring, NavItemShape))
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    tabs.forEachIndexed { index, tab -> NavItem(tab, index == selected, Modifier.fillMaxWidth().height(item)) { onSelect(index) } }
                }
            }
        }
    }
}

// ───────────────────────── vehicle screen ─────────────────────────

/** 차량 정보 on the phone: the car card, care deadlines, maintenance and incidents from the copy. Read-only; the car keeps the original. */
@Composable internal fun PhoneVehicleScreen(data: TripLogData, link: PhoneLink, now: Long, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val profile = data.profile
    val vehicle = data.vehicle
    val demo = profile.demoMode
    val layout = LocalLayout.current
    val alerts = remember(data.care, vehicle.odometerKm, today) { careAlerts(data.care, vehicle.odometerKm, today) }
    val maintenance = remember(data.maintenance, demo) { data.maintenance.filter { it.demo == demo }.sortedByDescending { it.at } }
    val incidents = remember(data.incidents, demo) { data.incidents.filter { it.demo == demo }.sortedByDescending { it.at } }
    Column(Modifier.fillMaxSize()) {
        PhoneRouteHeader(title = "차량 정보", subtitle = profile.plateNumber.ifBlank { null }, onBack = onBack)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = layout.gutter).padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(layout.cardGap + 2.dp)) {
                val accent = Scene.accent
                Surface(shape = RoundedCornerShape(26.dp), color = Scene.glass, border = Scene.edge, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.background(Brush.linearGradient(listOf(accent.copy(alpha = .22f), Color.Transparent))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("MY FLEET", style = MaterialTheme.typography.labelMedium, color = Scene.brand, letterSpacing = 1.sp)
                        Text(profile.plateNumber.ifBlank { "차량번호 미등록" }, style = MaterialTheme.typography.headlineSmall, color = Scene.text, fontWeight = FontWeight.Bold)
                        Text((vehicle.model ?: profile.carModel).ifBlank { "차종 미등록" } + validVin(vehicle.vin)?.let { " · VIN …${it.takeLast(6)}" }.orEmpty(),
                            style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)
                        // A short window (phone landscape) keeps the plate and the numbers and drops the drawing.
                        if (!layout.short) VehicleDrawing(Modifier.fillMaxWidth().height(150.dp))
                        val live = link.live?.takeIf { it.demo == demo && link.connected && now - it.at in 0..45_000 }
                        val streaming = live != null && link.connected
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Stat(if (live?.socPercent != null) "배터리" else "마지막 저장 잔량", (live?.socPercent ?: vehicle.socPercent?.takeUnless { demo })?.let { "$it%" } ?: "확인 불가", Modifier.weight(1f))
                            Stat(if (streaming) "계기판 · 실시간" else "계기판 · 마지막 수신", (live?.odometerKm ?: vehicle.odometerKm)?.takeIf { it > 0 }?.let(::kmText) ?: "—", Modifier.weight(1.4f))
                        }
                        Text(if (streaming) "차량에서 실시간으로 받고 있어요." else "차량이 보낸 마지막 값이에요. 연결되면 실시간으로 바뀌어요.", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
                    }
                }
                Panel(padding = 18.dp) {
                    Eyebrow("차량 관리 기한")
                    if (alerts.isEmpty()) Muted("등록된 기한이 없어요. 차량의 설정 › 법인·차량에서 검사·보험·리스 만기와 정비 주기를 적으면 여기에도 보여요.")
                    else alerts.forEach { alert ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.semantics(mergeDescendants = true) {}) {
                            Box(Modifier.size(10.dp).clip(CircleShape).background(careLevelColor(alert.level)))
                            Column(Modifier.weight(1f)) {
                                Text(alert.title, style = MaterialTheme.typography.titleSmall)
                                Text(alert.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(careLevelState(alert.level), style = MaterialTheme.typography.labelMedium, color = careLevelColor(alert.level))
                        }
                    }
                }
                Panel(padding = 18.dp) {
                    Eyebrow("정비 기록 · ${maintenance.size}건")
                    if (maintenance.isEmpty()) Muted("정비 기록이 없어요. 정비소에서 법인카드로 결제하면 차량이 자동으로 남겨요.")
                    else maintenance.take(4).forEach { record ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(record.kind.label + (record.merchant?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(phoneTime(record.at, "M월 d일") + (record.odometerKm?.let { " · ${kmText(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (record.amountWon > 0) Text(formatWon(record.amountWon), style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                Panel(padding = 18.dp) {
                    val openCount = incidents.count { it.status == IncidentStatus.OPEN }
                    Eyebrow("사고·특이사항 · 대응 중 ${openCount}건")
                    if (incidents.isEmpty()) Muted("기록된 사고·특이사항이 없어요. 홈 아래 버튼으로 바로 기록할 수 있어요.")
                    else incidents.take(4).forEach { incident ->
                        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(incident.kind.label, style = MaterialTheme.typography.titleSmall)
                                Text(phoneTime(incident.at, "M월 d일 HH:mm") + (incident.address?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(if (incident.status == IncidentStatus.OPEN) "대응 중" else "완료", style = MaterialTheme.typography.labelMedium,
                                color = if (incident.status == IncidentStatus.OPEN) Scene.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Muted("휴대폰은 차량 기록의 사본이에요. 기한과 정비는 차량에서 입력하고, 바뀐 내용은 바로 여기에도 내려와요.", Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

// ───────────────────────── backdrop ─────────────────────────

/**
 * The phone home's backdrop: the city low behind the greeting and the cards (horizon at 40 %, towers at 30 % scale), fading into
 * the base colour before the task list. The sun or the moon sits at [orbAt] (phoneOrbAt: the free sky right of the greeting) at
 * [orbAlpha], read in the sky's draw: the shell fades it in on the home tab when it touches none of the words, the cards or the
 * header icons, and out with the page otherwise ([showOrb] false leaves it out altogether).
 * By night the scrim comes in from the left as built; by day a white haze (Scene.haze) runs down from the top, .88 → .76 at the
 * greeting's bottom ([hazeTo], root px; a quarter of the height until measured) → clear 120 dp lower, so the greeting reads on
 * white rather than on the sky. The two cross-fade with the phase, on the sky's own crossfade clock (LocalSceneChange, linear).
 */
@Composable internal fun PhoneHomeBackdrop(modifier: Modifier = Modifier, hazeTo: Float = 0f, showOrb: Boolean = true, orbAt: Offset = Offset(.90f, .175f),
    orbAlpha: () -> Float = { 1f }) {
    val haze = Scene.haze
    val bg = Scene.bg
    val reduce = LocalReduceMotion.current
    val crossfadeMs = LocalSceneChange.current.crossfadeMs
    val fade = with(LocalDensity.current) { 120.dp.toPx() }
    Box(modifier) {
        HomeBackdrop(Modifier.matchParentSize(), horizonAt = .40f, heightScale = PHONE_SKY_TOWERS, moonAt = orbAt, leftScrim = false, showOrb = showOrb, orbAlpha = orbAlpha)
        Crossfade(LocalScenePhase.current == ScenePhase.DAY, Modifier.matchParentSize(), animationSpec = if (reduce) snap() else tween(crossfadeMs, easing = LinearEasing),
            label = "phone-haze") { day ->
            if (day) Spacer(Modifier.fillMaxSize().drawWithCache {
                val end = phoneHazeEnd(hazeTo, size.height)
                val brush = Brush.verticalGradient(0f to haze.copy(alpha = .88f), end / (end + fade) to haze.copy(alpha = .76f), 1f to Color.Transparent, startY = 0f, endY = end + fade)
                onDrawBehind { drawRect(brush, size = Size(size.width, end + fade)) }
            })
            else Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to bg.copy(alpha = .92f), .38f to bg.copy(alpha = .62f), .74f to Color.Transparent)))
        }
    }
}
