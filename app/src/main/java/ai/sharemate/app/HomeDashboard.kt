package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
 * assistant, three glass cards (차량 정보 · 운행일지 확인 · 정보 내보내기), and how today and this month stand. Every number on
 * it is read from the log or the vehicle; nothing is decorative except the skyline itself. The block keeps to the layout
 * classes of [LocalLayout] (cards 3 across, widgets 2 across) and the motion rules of Motion.kt: press feedback, a gliding
 * selection pill, numbers that cross-fade instead of counting. 2.11: on a wide, tall window today, this month and the next
 * booking move to the 지금 column beside the page ([NowColumn]), and the sun or moon rests in the sky the hero leaves free.
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

/**
 * How [selectionGlide] marks the chosen item: the lit pill (selection fill and ring), a glow underline (the sheet tabs), or
 * the car's solid accent stadium (2.12: the header tabs and the car's segments), whose covered label turns to onAccent.
 */
internal enum class GlideMark { PILL, UNDERLINE, SOLID }

/** The underline mark: 3 dp of glow, inset 16 dp from each side of the chosen item. */
private val UNDERLINE_THICKNESS = 3.dp
private val UNDERLINE_INSET = 16.dp

/** The solid mark: a 46-dp stadium centred in the 56-dp item, the pill of the mock's selected tab. */
private val SOLID_HEIGHT = 46.dp

/**
 * Where the underline mark sits under an item with [bounds]: [thickness] high along its bottom edge, centred, inset by [inset]
 * a side but never narrower than four times its thickness (so a narrow tab still shows a bar, not a dot).
 */
internal fun underlineBar(bounds: Rect, thickness: Float, inset: Float): Rect {
    val width = (bounds.width - inset * 2).coerceAtLeast(thickness * 4)
    return Rect(bounds.center.x - width / 2, bounds.bottom - thickness, bounds.center.x + width / 2, bounds.bottom)
}

/**
 * Where the selection mark of a tab row or a segmented control stands: the measured bounds of the [selected] item, gliding
 * there from the old item in [durationMs] (FastOutSlowIn) on a change; the first placement and reduced motion snap. Null
 * until that item has been measured. The rect moves on every frame of a glide, so read it where it is drawn ([drawGlide]) or
 * through a `derivedStateOf` ([glideCovers]), never straight in composition.
 */
@Composable internal fun rememberGlideRect(bounds: ItemBounds, selected: Int, durationMs: Int): State<Rect?> {
    val reduce = LocalReduceMotion.current
    val target = bounds[selected]
    val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduce) {
        val next = target ?: return@LaunchedEffect
        if (!placed || reduce) { rect.snapTo(next); placed = true } else rect.animateTo(next, tween(durationMs, easing = FastOutSlowInEasing))
    }
    val visible = rememberUpdatedState(target != null)
    return remember { derivedStateOf { if (placed && visible.value) rect.value else null } }
}

/**
 * Draws the selection mark at [glide] ([rememberGlideRect]) behind the row's items: the lit pill (the selection fill and a
 * [ringWidth] ring, never colour alone), with [GlideMark.UNDERLINE] a 3-dp underline in [underlineColor] (a shape, so never
 * colour alone either), or with [GlideMark.SOLID] the car's accent as a 46-dp stadium centred in the item, on which the
 * covered label turns to onAccent ([glideCovers]). Only the draw reads the rect, so a glide redraws the row and recomposes
 * nothing. Apply it after the row's padding (or its scroll), so the drawing and the reported bounds share one coordinate space.
 */
@Composable internal fun Modifier.drawGlide(glide: State<Rect?>, mark: GlideMark, ringWidth: Dp = 1.5.dp, corner: Dp? = null,
    underlineColor: Color = Scene.glow): Modifier {
    val fill = Scene.selectionFill
    val ring = Scene.selectionRing
    val solid = Car.skin.accent
    val density = LocalDensity.current
    val stroke = with(density) { ringWidth.toPx() }
    val radius = corner?.let { with(density) { it.toPx() } }
    val thickness = with(density) { UNDERLINE_THICKNESS.toPx() }
    val inset = with(density) { UNDERLINE_INSET.toPx() }
    val solidHeight = with(density) { SOLID_HEIGHT.toPx() }
    return drawBehind {
        val r = glide.value ?: return@drawBehind
        if (mark == GlideMark.UNDERLINE) {
            val bar = underlineBar(r, thickness, inset)
            drawRoundRect(underlineColor, bar.topLeft, bar.size, CornerRadius(thickness / 2, thickness / 2))
            return@drawBehind
        }
        if (mark == GlideMark.SOLID) {
            val height = solidHeight.coerceAtMost(r.height)
            drawRoundRect(solid, Offset(r.left, r.center.y - height / 2), Size(r.width, height), CornerRadius(height / 2, height / 2))
            return@drawBehind
        }
        val round = radius ?: (r.height / 2f)
        drawRoundRect(fill, r.topLeft, r.size, CornerRadius(round, round))
        drawRoundRect(ring, Offset(r.left + stroke / 2, r.top + stroke / 2), Size(r.width - stroke, r.height - stroke),
            CornerRadius(round - stroke / 2, round - stroke / 2), style = Stroke(stroke))
    }
}

/**
 * The mark behind the selected item of a tab row or a segmented control ([rememberGlideRect] drawn by [drawGlide]): the lit
 * pill by default, the underline of the sheet tabs in [underlineColor] (the car's sheet tabs take the accent ink), or the
 * car's solid stadium. A row whose labels follow the mark ([glideCovers]) keeps the rect itself and calls the two directly.
 */
@Composable internal fun Modifier.selectionGlide(bounds: ItemBounds, selected: Int, durationMs: Int = MOTION_PAGE_MS, ringWidth: Dp = 1.5.dp, corner: Dp? = null,
    mark: GlideMark = GlideMark.PILL, underlineColor: Color = Scene.glow): Modifier =
    drawGlide(rememberGlideRect(bounds, selected, durationMs), mark, ringWidth, corner, underlineColor)

/**
 * True while the gliding mark covers at least half of [item]'s width (pure, unit-tested). On the solid mark an item's label
 * and icon turn to onAccent at that moment, read through `derivedStateOf`, so a glide recomposes each item at most once
 * instead of recolouring it pixel by pixel on an offscreen layer. A label can read the old colour for at most one glide; at
 * rest the covered item is exactly the selected one. False until both rects are known.
 */
internal fun glideCovers(glide: Rect?, item: Rect?): Boolean {
    if (glide == null || item == null || item.width <= 0f) return false
    val overlap = minOf(glide.right, item.right) - maxOf(glide.left, item.left)
    return overlap >= item.width / 2f
}

/**
 * The press fill of the spec, [color] over the content while the surface is held: [Scene.fill] by default (white by night,
 * navy by day, both a few percent); on an accent fill [CarSkin.pressOnAccent], which darkens it, where the white layer would
 * bring the white label down to 4.52 by night. It shows 90 ms in and 160 ms out and stays as the only feedback under reduced
 * motion. Draws over the content, so apply it inside the clipping surface (or under the label, on a layer of its own).
 */
@Composable internal fun Modifier.pressFill(interactionSource: InteractionSource, color: Color = Scene.fill): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val reduce = LocalReduceMotion.current
    val strength by animateFloatAsState(if (pressed) 1f else 0f,
        if (reduce) snap() else if (pressed) tween(90, easing = LinearOutSlowInEasing) else tween(160, easing = FastOutSlowInEasing), label = "press-fill")
    return drawWithContent { drawContent(); if (strength > 0f) drawRect(color.copy(alpha = color.alpha * strength)) }
}

// ───────────────────────── header tabs (every pane but home) ─────────────────────────

@Immutable
internal data class NavTab(val label: String, val icon: Int, val badge: Int)

/**
 * The five destinations as one pill on the card (2.12): the solid accent stadium glides to the chosen tab on the page clock
 * (220 ms, a snap on first placement and under reduced motion), and a tab's icon and label turn to onAccent the moment it
 * covers half of the tab ([glideCovers], read through `derivedStateOf`, so a glide recomposes each tab at most once). Counts
 * sit inside the tab they belong to; on the accent the badge wears its onAccent ring, since its red alone would read 1.09:1
 * there. The car's tabs never ripple: the .98 settle is the press. [compact] is the short-window header: 4 dp of pill padding
 * instead of 6.
 */
@Composable internal fun GlassTabs(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val skin = Car.skin
    val bounds = rememberItemBounds()
    val glide = rememberGlideRect(bounds, selected, MOTION_PAGE_MS)
    Surface(modifier, shape = RoundedCornerShape(50), color = skin.card, border = BorderStroke(1.dp, skin.cardBorder)) {
        Row(Modifier.padding(if (compact) 4.dp else 6.dp).drawGlide(glide, GlideMark.SOLID), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            tabs.forEachIndexed { index, tab ->
                val active = index == selected
                val interaction = remember { MutableInteractionSource() }
                val covered by remember { derivedStateOf { glideCovers(glide.value, bounds[index]) } }
                Row(Modifier.reportBounds(bounds, index).pressable(interaction, .98f).clip(RoundedCornerShape(50))
                    .clickable(interactionSource = interaction, indication = null) { onSelect(index) }
                    .clearAndSetSemantics {
                        this.selected = active; role = Role.Tab
                        contentDescription = if (tab.badge > 0) "${tab.label}, 확인 필요 ${tab.badge}건" else tab.label
                    }
                    .heightIn(min = 56.dp).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = if (covered) skin.onAccent else Scene.textSecondary)
                    Text(tab.label, style = MaterialTheme.typography.labelLarge, color = if (covered) skin.onAccent else Scene.text, maxLines = 1)
                    if (tab.badge > 0) CountBadge(tab.badge, ring = covered)
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
 * a short window hides the eyebrow and keeps the hero to [heroMin]. The clock is no longer the hero's: since 2.12 the header
 * shows it on every page and window ([headerClock]), so the time is never on screen twice.
 * [widgetsInColumn]: the 지금 column stands beside the page, so today, this month and the next booking live there and leave
 * the page, and the hero reports the sky it leaves free for the sun or moon.
 */
@Immutable
internal data class HomePlan(val cardColumns: Int, val widgetsSideBySide: Boolean, val compactWidgets: Boolean, val showEyebrow: Boolean,
    val heroMin: Dp, val widgetsInColumn: Boolean = false)

internal fun homePlan(layout: LayoutTokens, fontScale: Float, windowHeight: Dp, nowColumn: Boolean = false): HomePlan {
    val columns = when {
        !layout.expanded -> 1
        layout.full || fontScale <= 1.25f -> 3
        else -> 2
    }
    return HomePlan(cardColumns = columns, widgetsSideBySide = layout.expanded || layout.short, compactWidgets = layout.short,
        showEyebrow = !layout.short, heroMin = heroMinHeight(windowHeight, layout.short), widgetsInColumn = nowColumn)
}

/**
 * The sky the hero leaves free (pure, unit-tested), in the hero's own px: right of the greeting's reading measure
 * ([readingWidth] inside [padH]) and above the card row ([cardsHeight] at the bottom inside [padV]), [margin] clear of both
 * and of the hero's end, from the hero's top. Null when nothing is left, as on a page too narrow beside the reading measure.
 * Built from sizes, never positions, so neither the entrance lift nor the page slide moves it. The full window gives
 * (752, 0)–(1080, 386): (824, 92)–(1152, 478) on screen.
 */
internal fun heroBand(heroWidth: Float, heroHeight: Float, readingWidth: Float, cardsHeight: Float, padH: Float, padV: Float, margin: Float): Rect? {
    val left = padH + readingWidth + margin
    val right = heroWidth - padH - margin
    val bottom = heroHeight - padV - cardsHeight - margin
    return if (right > left && bottom > 0f) Rect(left, 0f, right, bottom) else null
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
internal data class HomeCardLines(val vehicle: String, val log: String, val logPending: Int, val export: String, val exportMonth: YearMonth)

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
 * log does; 정보 내보내기 names the month the Excel covers, [HomeCardLines.exportMonth], which 월간·Excel then opens on
 * ([reportLandingMonth], as the phone's card).
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
    val exportMonth = reportLandingMonth(data, today, zone)
    val export = if (exportMonth != month) "${monthLabel(exportMonth, today)} 운행기록부 · 마감 전 저장"
        else "${month.monthValue}월 운행기록부 · Excel 5개 시트"
    return HomeCardLines(vehicle, log, pending, export, exportMonth)
}

/**
 * The month widget's words. [line] is the 지금 column tile's one line under the word (2.12): [detail] itself, except where
 * the detail is a sentence too long for the tile ("이번 달 첫 운행" while the month's first trip records); the phone and the
 * home widget keep reading [detail], and TalkBack hears it on the tile too.
 */
@Immutable
internal data class MonthWidgetInfo(val title: String, val headline: String, val detail: String, val tone: Int, val auto: String? = null, val percent: Int? = null,
    val line: String = detail)

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
        status.trips == 0 && recording -> MonthWidgetInfo(title, "기록 중", "이번 달 첫 운행을 기록하고 있어요", 0, line = "이번 달 첫 운행")
        status.trips == 0 -> MonthWidgetInfo(title, "대기", "이번 달 운행이 아직 없어요", 0)
        // A closed month that gained an unconfirmed trip or card since is not 'done'; it says what is left.
        status.closed != null && status.issues > 0 -> MonthWidgetInfo(title, "마감 후 확인 ${status.issues}건", detail, 2, auto, status.percent)
        status.closed != null -> MonthWidgetInfo(title, "마감됨", detail, 1, auto, status.percent)
        status.issues == 0 -> MonthWidgetInfo(title, "완성", detail, 1, auto, status.percent)
        else -> MonthWidgetInfo(title, "확인 ${status.issues}건", detail, 2, auto, status.percent)
    }
}

/**
 * What the 지금 column's 오늘 운행 tile shows and what TalkBack hears for it ([todayTileInfo]). [expenseWon] (2.13) is the
 * registered expenses as a sum, which the tile draws in the one money style ([CarMiniStat]'s `won`); [expenses] is the same
 * sum in words, for what TalkBack hears.
 */
@Immutable
internal data class TodayTileInfo(val km: String, val trips: String, val expenses: String, val odometer: String, val spoken: String, val expenseWon: Long = 0)

/**
 * The 오늘 운행 tile's figures (2.12, pure, unit-tested): today's distance with a trip still recording counted in ("—" while
 * it is not known), the finished trips only (one still recording is not finished yet) and their registered expenses, and the
 * odometer while the signal is fresh, else 확인 필요. TalkBack hears them in one stop: "오늘 운행 0.0 km, 완료 0건, 등록 경비
 * 0원, 계기판 확인 필요". The distance and the freshness follow the driving screen's rule ([drivingOverviewInfo]).
 */
internal fun todayTileInfo(state: TripLogState, now: Long, zone: ZoneId): TodayTileInfo {
    val data = state.data
    val overview = drivingOverviewInfo(state, now, true, zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val finished = tripsIn(data.trips.filter { it.demo == data.profile.demoMode }, today, today, zone)
    val km = overview.todayKm
    val expenseWon = finished.sumOf { it.expenseWon }
    val expenses = formatWon(expenseWon)
    val odometer = state.live?.odometerKm?.takeIf { overview.fresh }?.let(::kmText) ?: "확인 필요"
    val spoken = "오늘 운행 ${km?.let(::kmText) ?: "정보 없음"}, 완료 ${finished.size}건, 등록 경비 $expenses, 계기판 $odometer"
    return TodayTileInfo(km?.let { String.format(Locale.KOREA, "%,.1f", it) } ?: "—", "${finished.size}건", expenses, odometer, spoken, expenseWon)
}

// ───────────────────────── hero ─────────────────────────

@Immutable
internal class HomeCardSpec(val title: String, val subtitle: String, val icon: Int, val badge: Int, val onClick: () -> Unit)

/** Set once the home has played its entrance, so the stagger runs once per process and never on a return to the tab or after a sync. */
private var homeEntranceDone = false

/** True the first time the home composes in this process: the hero block staggers in (greeting → search → cards); false afterwards. */
@Composable internal fun rememberHomeEntrance(): Boolean {
    val animate = remember { !homeEntranceDone }
    LaunchedEffect(Unit) { homeEntranceDone = true }
    return animate
}

/**
 * The greeting hero: brand line, greeting, the assistant entry and [cards], the three destinations, laid out by [plan].
 * 2.12 took the clock out: the header shows the date and time on every page and window, so the hero never repeats them.
 * Grows past [HomePlan.heroMin] when the font scale needs room instead of clipping; the greeting block keeps to the reading
 * width. [entrance] staggers the block in on the first composition of the session only. With [onBand] the hero measures
 * itself and its card row and reports the sky it leaves free ([heroBand], null when there is none).
 */
@Composable internal fun HomeHero(state: TripLogState, now: Long, plan: HomePlan, onAssistant: () -> Unit, requestPermissions: () -> Unit,
    demoControls: @Composable () -> Unit, cards: List<HomeCardSpec>, modifier: Modifier = Modifier, entrance: Boolean = false,
    onBand: ((Rect?) -> Unit)? = null) {
    val data = state.data
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val hour = Instant.ofEpochMilli(now).atZone(zone).hour
    val layout = LocalLayout.current
    val compact = plan.cardColumns == 1
    val padH = if (compact) 4.dp else 8.dp
    val padV = if (compact) 4.dp else 6.dp
    // Sizes only: a band built from positions would move with the entrance lift and the page slide. They are kept current
    // even while nobody asks (nothing reads them then), so a band asked for later starts from the hero as it stands.
    var heroSize by remember { mutableStateOf(IntSize.Zero) }
    var cardsHeight by remember { mutableIntStateOf(0) }
    if (onBand != null) {
        val band = with(LocalDensity.current) {
            heroBand(heroSize.width.toFloat(), heroSize.height.toFloat(), layout.readingMax.toPx(), cardsHeight.toFloat(), padH.toPx(), padV.toPx(), HERO_SKY_MARGIN.toPx())
        }
        val report by rememberUpdatedState(onBand)
        // Reported once per change; the clock recomposes the hero every second.
        DisposableEffect(band) { report(band); onDispose {} }
    }
    // No frame and no chips of its own: the shared header above carries status, employee and tabs on every page, and the
    // hero is laid straight onto the full-screen backdrop ([HomeBackdrop]).
    Column(modifier.onSizeChanged { heroSize = it }.fillMaxWidth().heightIn(min = plan.heroMin).padding(horizontal = padH, vertical = padV),
        verticalArrangement = Arrangement.SpaceBetween) {
        // The first child holds SpaceBetween's top anchor, where the clock stood until the header took it (2.12).
        Spacer(Modifier.height(4.dp))
        Appear(Modifier.readingWidth(), index = 0, animate = entrance) {
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
        Appear(Modifier.readingWidth(), index = 1, animate = entrance) { AssistantSearch(onAssistant) }
        Appear(Modifier.fillMaxWidth().padding(top = if (compact) 16.dp else 24.dp), index = 2, animate = entrance) {
            Column(Modifier.onSizeChanged { cardsHeight = it.height }, verticalArrangement = Arrangement.spacedBy(layout.cardGap)) {
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
 * Its outline is the solid accent, the one edge that marks it as a control on the hero haze (WCAG 1.4.11 asks 3:1; the .40
 * accent it had was 1.7:1). Since 2.12 that is the car's accent ([CarSkin.accent]), the colour of the chosen tab and of every
 * primary action, so the hero's one control matches the header above it. Darker than the scene's sweep accent, it gains
 * against the white day glass and still clears 3:1 against the night glass (3.25:1 on the opaque night card).
 */
@Composable private fun AssistantSearch(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    // Day glass that carries text stays at .88 or above, so the composite contrast holds; night keeps its lighter alpha.
    val glass = Scene.glass.copy(alpha = if (Scene.isDay) .88f else .80f)
    Surface(onClick = onClick, shape = RoundedCornerShape(50), color = glass, border = BorderStroke(1.dp, Car.skin.accent), interactionSource = interaction,
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
 * the white; by night the scene's glass at [translucent] (null keeps the token's own alpha), as built. The destination
 * cards take it at .78; the 지금 column's tiles left it in 2.12 for the opaque [CarCard].
 */
@Composable internal fun homeCardGlass(translucent: Float? = null): Color {
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
 * Shared with the 2.12 [CarCard].
 */
@Composable internal fun Modifier.dayCardShadow(shape: Shape): Modifier {
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
 * [ring] (2.12, the car's tab under the solid accent) draws a 2-dp onAccent ring inside the disc's edge: the red against the
 * accent is only 1.09:1, the ring 4.93:1 against the accent and 5.35:1 against the red. It sits inside the bounds, so a tab
 * never changes width as the accent glides over it.
 */
@Composable internal fun CountBadge(count: Int, modifier: Modifier = Modifier, ring: Boolean = false) {
    val reduce = LocalReduceMotion.current
    // White digits on the badge red clear 5.3:1 by day; the tile's foreground is the one white that is a scene token.
    val digits = Scene.onTile
    AnimatedContent(targetState = count.coerceAtMost(99), modifier = modifier.clearAndSetSemantics {}, label = "badge", transitionSpec = {
        val swap = if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
            else (fadeIn(tween(160, easing = FastOutSlowInEasing)) + scaleIn(tween(160, easing = FastOutSlowInEasing), initialScale = .85f)) togetherWith
                fadeOut(tween(120, easing = FastOutSlowInEasing))
        swap using SizeTransform(clip = false) { _, _ -> if (reduce) snap() else tween(160, easing = FastOutSlowInEasing) }
    }) { value ->
        val shape = RoundedCornerShape(50)
        Box(Modifier.heightIn(min = 26.dp).widthIn(min = 26.dp).clip(shape).background(Scene.badge)
            .then(if (ring) Modifier.border(2.dp, Car.skin.onAccent, shape) else Modifier).padding(horizontal = 8.dp),
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

/**
 * Two widgets side by side on the wide classes where the 지금 column does not stand (2 × 542 on a 1200-dp window), stacked
 * in a narrow window; one 120-dp row when [compact]. Beside the column, today and this month are its tiles instead.
 */
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

// ───────────────────────── 지금 column (wide car windows) ─────────────────────────

/** The space between the 지금 column's tiles. */
private val NOW_TILE_GAP = 16.dp

/** The padding inside the 지금 column's cards: 20 dp in every class, where the page's cards take 24 from EXPANDED up. */
internal val NOW_TILE_PADDING = 20.dp

/**
 * The 지금 column beside the page on a wide, tall car window ([carColumns]), in the mock's order: 사용자 ([driverCard], handed
 * the modifier that places it), 예약, 오늘 운행 and 이달 운행일지, each an opaque [CarCard] (2.12; by night too, where the
 * 2.11 tiles were glass at .78). The three under 사용자 open the page they sum up: 차량 ([openVehicle]), 운행기록 on this
 * month ([openToday]) and 월간·Excel ([openMonth]). The column stands outside the page change like the header, so it never
 * slides, and it appears with the shell (no stagger). TalkBack reaches it after the page. The booking line is kept on the
 * minute and the month's words on the day, because the clock recomposes the column every second.
 *
 * 2.13 (DESIGN_2_13_SPEC §4): the column never scrolls in the FULL window at 1×. Its tallest state (사용자 with a two-line
 * question, 323, then 84 + 199 + 172, three 16-dp gaps and the 8-dp foot: 834 dp) fits the 1097-dp page with room to spare,
 * so scrolling is off while the content fits ([ScrollState.maxValue] is 0) and a fling or a drag never nudges it. A larger
 * font or a shorter window that overflows turns it back on, so nothing is ever cut.
 */
@Composable internal fun NowColumn(state: TripLogState, now: Long, driverCard: @Composable (Modifier) -> Unit, openVehicle: () -> Unit, openToday: () -> Unit,
    openMonth: () -> Unit, modifier: Modifier = Modifier) {
    val scroll = rememberScrollState()
    // The bottom padding keeps the last tile's day shadow inside the scroll.
    Column(modifier.semantics { isTraversalGroup = true; traversalIndex = 1f }.fadingTopEdge(scroll).verticalScroll(scroll, enabled = scroll.maxValue > 0)
        .padding(bottom = 8.dp)) {
        // The driver slot carries its own gap, so a card that folds away takes the gap with it.
        driverCard(Modifier.fillMaxWidth().padding(bottom = NOW_TILE_GAP))
        Column(verticalArrangement = Arrangement.spacedBy(NOW_TILE_GAP)) {
            ReservationTile(state.data, now, openVehicle)
            TodayTile(state, now, openToday)
            MonthTile(state.data, now, openMonth)
        }
    }
}

/**
 * 예약 (2.12, the mock's layout): the bell, "예약" and one bold line, the booking in use or the next one however far ahead
 * ([reservationTileLine]), so 현재 잡힌 예약이 없어요 means there is none at all. "사용 중" and "곧 시작" carry the state where
 * 2.11 had a dot. A tap opens 차량, where the bookings are listed; TalkBack hears the long form, and a new line is announced.
 * Shared since 2.13: 홈 without the 지금 column shows it under its card while a booking is due.
 */
@Composable internal fun ReservationTile(data: TripLogData, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    // The line names its times to the minute.
    val minute = now / 60_000
    val line = remember(data, minute) { reservationTileLine(data, now, zone) }
    val fontScale = LocalDensity.current.fontScale
    CarCard(modifier, onClick = onOpen, onClickLabel = "예약 보기", description = line.spoken, live = true, padding = NOW_TILE_PADDING) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notifications, contentDescription = null, Modifier.size(28.dp), tint = Car.skin.icon)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                // The tile's name is a heading stop, like the titles of the tiles under it.
                Text("예약", style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, maxLines = 1, modifier = Modifier.semantics { heading() })
                // One line at the normal font (the longest, "11월 21일 (토) 18:00 · 김서연 님", takes 249 of 276 dp); two above
                // 1.05×, as a CarLine, so a larger font loses no words.
                Text(line.text, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = carLineMaxLines(fontScale), overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(24.dp), tint = Scene.textSecondary)
        }
    }
}

/**
 * 오늘 운행 (2.12): the distance large, then 완료 운행 · 등록 경비 · 계기판 as three mini stats ([todayTileInfo]). The distance
 * updates in place while a trip records ([homeShowsLiveTrip]) and cross-fades between trips; the odometer is live telemetry
 * and simply updates. A tap opens 운행기록 on this month, as the chevron in its title promises. Shared since 2.13 with 홈
 * without the 지금 column. 등록 경비 is money in the one style since 2.13 (the `won` mini stat): tabular digits with 원 in the
 * quieter ink, and a day without expenses shows 0원 muted instead of a full-ink zero; the tile's one ₩ mark is its label's.
 */
@Composable internal fun TodayTile(state: TripLogState, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val info = todayTileInfo(state, now, zone)
    CarCard(modifier, onClick = onOpen, onClickLabel = "운행기록 보기", description = info.spoken, padding = NOW_TILE_PADDING,
        title = { CarSectionTitle("오늘 운행", icon = painterResource(R.drawable.ic_report), chevron = true) }) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val kmStyle = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum")
            if (homeShowsLiveTrip(state.data)) Text(info.km, style = kmStyle, color = Scene.text, maxLines = 1)
            else DigitChange(info.km, style = kmStyle, color = Scene.text)
            Text("km", style = MaterialTheme.typography.titleMedium, color = Scene.textMuted, modifier = Modifier.padding(bottom = 6.dp))
        }
        // The tile already says the three figures in its one stop ([TodayTileInfo.spoken]); the stats' own stops would repeat them.
        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarMiniStat(painterResource(R.drawable.ic_vehicle), "완료 운행", info.trips, Modifier.weight(1f))
            CarMiniStat(painterResource(R.drawable.ic_won), "등록 경비", won = info.expenseWon, modifier = Modifier.weight(1f))
            CarMiniStat(painterResource(R.drawable.ic_gauge), "계기판", info.odometer, Modifier.weight(1f), live = true)
        }
    }
}

/**
 * 이달 운행일지 (2.12): the month's one word in its tone ([monthWidgetInfo]) over one line of where it stands
 * ([MonthWidgetInfo.line]), the completion ring at the end. What the car filled in by itself left the view for the one-line
 * rule; TalkBack still hears it after the detail. A tap opens 월간·Excel on this month. Shared since 2.13 with 홈 without the
 * 지금 column. 2.13: 완성 and 마감됨 (and their ring) take the scene's positive (6.21 / 9.08 on the card), as 월간's close card
 * draws a finished month, where 2.12's glow was the ready dot's teal; 확인 N건 keeps warn (6.80 / 9.09), the tone of what waits.
 */
@Composable internal fun MonthTile(data: TripLogData, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val today = localDate(now)
    val info = remember(data, today) { monthWidgetInfo(data, today, zone) }
    val color = when (info.tone) { 1 -> Scene.positive; 2 -> Scene.warn; else -> Scene.text }
    val fontScale = LocalDensity.current.fontScale
    CarCard(modifier, onClick = onOpen, onClickLabel = "월간 현황 열기", description = "${info.title} ${info.headline}, ${info.detail}${info.auto?.let { ", $it" }.orEmpty()}",
        padding = NOW_TILE_PADDING, title = { CarSectionTitle(info.title, chevron = true) }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DigitChange(info.headline, style = MaterialTheme.typography.headlineMedium, color = color, fontWeight = FontWeight.Bold)
                // A CarLine that cross-fades: one line at the normal font, two above 1.05×.
                DigitChange(info.line, style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary, maxLines = carLineMaxLines(fontScale))
            }
            CompletionRing(info.percent, color, diameter = 88.dp, thickness = 8.dp, sweepKey = "home-month-ring")
        }
    }
}
