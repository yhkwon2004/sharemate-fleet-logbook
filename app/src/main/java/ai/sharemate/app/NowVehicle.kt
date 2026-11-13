package ai.sharemate.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId

/*
 * 2.15 (docs/design/DESIGN_2_15.md §3): the 지금 column ends where the page ends. 2.14's column stopped about 290 dp above
 * the page's foot on every page, and open space reads as empty, so a fifth tile closes it: 차량, the plate, the car's picture
 * as the 차량 page draws it and three live figures under it. The tile takes what the page leaves ([nowVehicleHeight]); the
 * column's own layout ([NowColumnLayout]) measures the four tiles above it and gives the tile the rest, so the column never
 * scrolls in the FULL window at 1× and still scrolls, the tile at its least, where a large font or a short window overflows.
 * §9.4 (the corrections after the first build): the tile takes the room in every state, up to 400 dp, so the column always
 * ends on the page's foot; height the picture cannot use becomes a second row of figures (관리 · 충전 · 휴대폰), not air.
 */

// ───────────────────────── the budget (pure, unit-tested) ─────────────────────────

/** The drawing's least height in the tile (§3). */
internal val NOW_VEHICLE_PICTURE_MIN = 64.dp

/**
 * The tile's least height at a 1× font: 20 + the 32-dp title row + 12 + the drawing at [NOW_VEHICLE_PICTURE_MIN] + 16 + the
 * mini stats (the 19-dp label row, 4, the 23-dp value: 46) + 20 = 210 dp. The spec's 148 dp ("the picture at 64") leaves the
 * row of mini stats and its gap out (62 dp); the FULL window still holds the tile at its least with 37 dp to spare.
 */
internal val NOW_VEHICLE_MIN = 210.dp

/**
 * The most the tile takes (§9.4, which replaces §3's 280 dp): enough for the room the page leaves in every state of the four
 * tiles above it in the FULL window at 1×, so the column ends on the page's foot: 247 dp with the daily question on two lines,
 * 399 once it is answered, 358 on a first run (사용자 asking for the first 직원). 2.15's first build stopped at 280, and the
 * answered column ended 119 dp above the foot.
 */
internal val NOW_VEHICLE_MAX = 400.dp

/** The second row of mini stats with the gap over it (§9.4): 16 + the mini stats' 46 dp at 1×. */
internal val NOW_VEHICLE_MORE = 62.dp

/** The car drawing's height over its width (the bitmap is 1942 × 809), where the painter reports no size of its own. */
internal const val NOW_VEHICLE_ASPECT = 809f / 1942f

/** The space under the column's last tile (2.13's bottom padding): it keeps the tile's day shadow inside the scroll. */
internal val NOW_COLUMN_FOOT = 8.dp

/**
 * The 차량 tile's height in a column [page] tall whose four tiles above take [others] with their three gaps (pure,
 * unit-tested): what is left after the 16-dp gap over the tile and the 8-dp foot, at most [NOW_VEHICLE_MAX]; null when even
 * the least does not fit, and the column then scrolls with the tile at its least. [min] is the tile's least at the font in
 * effect, which the column measures; it never counts for less than [NOW_VEHICLE_MIN]. In the FULL window at 1× the tallest
 * state (사용자 with a two-line question, 826 dp with the gaps) leaves 1,097 − 826 − 16 − 8 = 247 dp; the answered card (171)
 * leaves 399 and a first run (212) 358, all under the most, so the column ends on the page's foot in every state.
 */
internal fun nowVehicleHeight(page: Dp, others: Dp, min: Dp = NOW_VEHICLE_MIN): Dp? {
    val least = min.coerceAtLeast(NOW_VEHICLE_MIN)
    val room = page - others - NOW_TILE_GAP - NOW_COLUMN_FOOT
    return if (room < least) null else room.coerceAtMost(NOW_VEHICLE_MAX.coerceAtLeast(least))
}

/**
 * Whether the 차량 tile stands its second row of mini stats (§9.4; pure, unit-tested): when the drawing's slot (what the tile
 * leaves the picture over the first row) exceeds the drawing's own height at the tile's width, [picture], by [need] or more:
 * the second row and its gap, [NOW_VEHICLE_MORE] at 1×. Under that the slot's spare height stays around the drawing. In the
 * FULL window at 1× the drawing is 147 dp tall in its 352-dp tile, so the row shows once the question is answered (a 253-dp
 * slot) and on a first run (212), not while it is asked (101), and the drawing never falls under its own height for it.
 */
internal fun nowVehicleMoreRow(slot: Dp, picture: Dp, need: Dp = NOW_VEHICLE_MORE): Boolean = slot - picture >= need

// ───────────────────────── the words (pure, unit-tested) ─────────────────────────

/** One figure of the tile: what the mini stat shows ("D-7", "—"), what TalkBack hears in the tile's one stop, and whether it is drawn quietly. */
@Immutable
internal data class NowFigure(val value: String, val spoken: String, val muted: Boolean = value == VehicleCopy.NOW_NONE)

/**
 * What the 차량 tile shows: the [plate] for the title's tag (null while none is saved), the three figures of the first row and
 * the [spoken] form TalkBack hears as the tile's one stop; since §9.4 the second row's [care], [charge] and [phone], which
 * [spokenMore] adds while that row stands.
 */
@Immutable
internal data class NowVehicleInfo(val plate: String?, val battery: String, val gear: String, val signal: String, val spoken: String,
    val care: NowFigure, val charge: NowFigure, val phone: NowFigure) {
    /** The tile's one stop while the second row stands: the first row's words, then 관리, 충전 and 휴대폰. */
    val spokenMore: String get() = "$spoken, ${care.spoken}, ${charge.spoken}, ${phone.spoken}"
}

/**
 * 신호 in the header pill's terms (pure, unit-tested): the tone [statusPillInfo] gives the pill, said in one short word that
 * fits a mini stat. Ready, recording and demo samples are 수신 중; a link still starting is 연결 중; a save failure, a signal
 * that cannot record or a stale one is 확인 필요, the word the 차량 page uses. The pill's own labels ("마지막 수신 5분 전",
 * "기록 중 · 12.3 km") are longer than a third of the tile, and the 사용자 card above already shows them whole.
 */
internal fun nowVehicleSignal(tone: PillTone): String = when (tone) {
    PillTone.NEGATIVE -> VehicleCopy.CHECK
    PillTone.QUIET -> VehicleCopy.NOW_CONNECTING
    PillTone.READY, PillTone.POSITIVE, PillTone.DEMO -> VehicleCopy.NOW_RECEIVING
}

/**
 * 관리 (§9.4; pure, unit-tested): the next dated deadline among [alerts] ([careAlerts], most urgent first, the 차량 page's
 * list: 정기검사, 보험 만기, 리스·렌트 만기, snoozed or not) as a D-day, "D-7", 오늘 or "3일 지남"; heard with its kind
 * ("관리 정기검사까지 7일"). The service interval counts kilometres, not days, so it is left to 차량 관리; without a date the
 * figure is "—", heard as 등록된 기한 없음.
 */
internal fun nowVehicleCare(alerts: List<CareAlert>): NowFigure {
    val next = alerts.firstOrNull { it.daysLeft != null }
        ?: return NowFigure(VehicleCopy.NOW_NONE, "${VehicleCopy.NOW_CARE} ${VehicleCopy.SPOKEN_NOW_NO_CARE}")
    val days = next.daysLeft!!
    return NowFigure(VehicleCopy.nowCareDays(days), "${VehicleCopy.NOW_CARE} ${VehicleCopy.spokenNowCare(next.kind.label, days)}")
}

/**
 * 충전 (§9.4; pure, unit-tested): 충전 중 while the fresh sample in the mode on screen reports the plug ([charging] true, the
 * 차량 page's rule); otherwise "—", heard as 충전 중 아님 when the car says so and as 충전 정보 없음 when no fresh sample says
 * anything ([charging] null).
 */
internal fun nowVehicleCharge(charging: Boolean?): NowFigure = when (charging) {
    true -> NowFigure(VehicleCopy.NOW_CHARGING, VehicleCopy.NOW_CHARGING)
    false -> NowFigure(VehicleCopy.NOW_NONE, VehicleCopy.SPOKEN_NOT_CHARGING)
    null -> NowFigure(VehicleCopy.NOW_NONE, "${VehicleCopy.NOW_CHARGE} ${VehicleCopy.SPOKEN_NOW_NONE}")
}

/**
 * 휴대폰 (§9.4; pure, unit-tested): the phone link as the 차량 page's 휴대폰 연결 card counts it. 꺼짐 (drawn quietly) while
 * the link is off; "연결 2대" while the server runs with phones paired (the card's 연결 figure); 서버 오류, the card's own tag,
 * when the server stopped on an [error]; otherwise 대기, the server starting or listening for a first phone.
 */
internal fun nowVehiclePhone(enabled: Boolean, running: Boolean, error: String?, paired: Int): NowFigure {
    val value = when {
        !enabled -> VehicleCopy.NOW_PHONE_OFF
        !running && error != null -> VehicleCopy.NOW_PHONE_ERROR
        running && paired > 0 -> VehicleCopy.nowPhoneLinked(paired)
        else -> VehicleCopy.NOW_PHONE_WAITING
    }
    return NowFigure(value, "${VehicleCopy.NOW_PHONE} $value", muted = !enabled)
}

/**
 * The 차량 tile's words at [now] (pure, unit-tested). 배터리 is the charge of a sample under 45 s old in the mode on screen
 * (the rule of 홈's card and 차량 정보); 기어 is the gear as the 차량 page's card reads it ([vehicleHeroCopy]: only from a
 * fresh sample); 신호 is [nowVehicleSignal] of the header pill's state, read by the driving screen's rule as the 사용자 card's
 * tag reads it. A figure the car does not report is "—", heard as 정보 없음. The spoken form names the plate first. The
 * second row (§9.4): 관리 from the deadlines at the odometer the car trusts ([careOdometer], [nowVehicleCare]), 충전 from the
 * same fresh sample ([nowVehicleCharge]) and 휴대폰 from the sync server ([nowVehiclePhone]).
 */
internal fun nowVehicleInfo(state: TripLogState, now: Long, zone: ZoneId): NowVehicleInfo {
    val profile = state.data.profile
    val plate = profile.plateNumber.trim().takeIf { it.isNotEmpty() }
    val sample = state.live?.takeIf { it.demo == profile.demoMode && now - it.at in 0..45_000 }
    val battery = sample?.socPercent?.takeIf { it in 0..100 }?.let { "$it%" }
    val gear = vehicleHeroCopy(profile.demoMode, vehicleSampleFresh(state, now), state.signal, state.live?.gear).gear.takeUnless { it == VehicleCopy.CHECK }
    val overview = drivingOverviewInfo(state, now, true, zone)
    val signal = nowVehicleSignal(statusPillInfo(state, overview.fresh, overview.status).second)
    val spoken = listOf(
        listOfNotNull(VehicleCopy.NOW_TITLE, plate).joinToString(" "),
        "${VehicleCopy.NOW_BATTERY} ${battery ?: VehicleCopy.SPOKEN_NOW_NONE}",
        "${VehicleCopy.GEAR} ${gear ?: VehicleCopy.SPOKEN_NOW_NONE}",
        "${VehicleCopy.NOW_SIGNAL} $signal",
    ).joinToString(", ")
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val care = nowVehicleCare(careAlerts(state.data.care, careOdometer(state, now).first, today))
    val sync = state.sync
    return NowVehicleInfo(plate, battery ?: VehicleCopy.NOW_NONE, gear ?: VehicleCopy.NOW_NONE, signal, spoken, care,
        nowVehicleCharge(sample?.charging), nowVehiclePhone(profile.phoneSync, sync.running, sync.error, sync.pairedPhones))
}

// ───────────────────────── the tile ─────────────────────────

/**
 * 차량 (2.15, §3), the 지금 column's last tile: the title row (the vehicle icon, 차량, the plate as a tag while one is saved,
 * the chevron), the car drawing of the 차량 page fitted and centred in all the height the tile has left, and one row of three
 * mini stats from the live signal, 배터리 · 기어 · 신호 ([nowVehicleInfo]); they are telemetry, so they update in place. Where
 * the drawing's slot has [NOW_VEHICLE_MORE] beyond the drawing's own height (§9.4, [nowVehicleMoreRow]), a second row stands
 * under the first, 관리 · 충전 · 휴대폰, so the height the page leaves holds figures instead of air. A tap opens 차량
 * ([onOpen], "차량 정보 보기"). TalkBack hears one stop, the plate and the figures shown ([NowVehicleInfo.spokenMore] while the
 * second row stands), then the drawing's own words ([VehicleCopy.DRAWING]: a staged picture, not the car's model); the tag and
 * the stats keep no stops of their own.
 *
 * The column always measures the tile at a height (what the page leaves, or its least): the body's layout
 * ([VehicleBodyPolicy]) gives the drawing the rest of it and decides the second row there, from the room it has, and tells
 * the tile so its one stop names what is drawn. Asked for its least height, the tile answers with the drawing at
 * [NOW_VEHICLE_PICTURE_MIN] and one row at the font in effect; it holds no SubcomposeLayout, BoxWithConstraints or lazy list (a
 * CarCard, the title's FlowRow, a tag, the body and live mini stats), so the column may ask it.
 */
@Composable internal fun VehicleTile(state: TripLogState, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val info = nowVehicleInfo(state, now, zone)
    val plate = info.plate
    // Whether the second row stands is the body's call, made from the height the column gives the tile.
    var more by remember { mutableStateOf(false) }
    val picture = painterResource(R.drawable.sharemate_silver_car)
    val aspect = picture.intrinsicSize.let { if (it.isSpecified && it.width > 0f) it.height / it.width else NOW_VEHICLE_ASPECT }
    val policy = remember(aspect) { VehicleBodyPolicy(aspect) { shown -> more = shown } }
    // The second row arrives with a short fade where the room opens under it (the daily question folding away), never as a pop
    // in the middle of that fold; it snaps under reduced motion, and leaves at once with the room.
    val reveal by animateFloatAsState(if (more) 1f else 0f, enterSpec(160), label = "now-vehicle-more")
    CarCard(modifier, onClick = onOpen, onClickLabel = VehicleCopy.NOW_OPEN, description = if (more) info.spokenMore else info.spoken,
        padding = NOW_TILE_PADDING,
        title = {
            // The plate is in the tile's one stop already; its tag would be a stop of its own.
            CarSectionTitle(VehicleCopy.NOW_TITLE, icon = painterResource(R.drawable.ic_vehicle), chevron = true,
                trailing = if (plate != null) { { Box(Modifier.clearAndSetSemantics {}) { CarTag(plate) } } } else null)
        }) {
        Layout(contents = listOf(
            { Image(picture, VehicleCopy.DRAWING, contentScale = ContentScale.Fit) },
            {
                Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CarMiniStat(painterResource(R.drawable.ic_battery), VehicleCopy.NOW_BATTERY, info.battery, Modifier.weight(1f), live = true,
                        muted = info.battery == VehicleCopy.NOW_NONE)
                    CarMiniStat(painterResource(R.drawable.ic_gear_shift), VehicleCopy.GEAR, info.gear, Modifier.weight(1f), live = true,
                        muted = info.gear == VehicleCopy.NOW_NONE)
                    CarMiniStat(painterResource(R.drawable.ic_signal), VehicleCopy.NOW_SIGNAL, info.signal, Modifier.weight(1f), live = true)
                }
            },
            {
                // §9.4: composed always, placed only where the body has room for it.
                Row(Modifier.graphicsLayer { alpha = reveal }.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CarMiniStat(painterResource(R.drawable.ic_calendar), VehicleCopy.NOW_CARE, info.care.value, Modifier.weight(1f), live = true,
                        muted = info.care.muted)
                    CarMiniStat(painterResource(R.drawable.ic_bolt), VehicleCopy.NOW_CHARGE, info.charge.value, Modifier.weight(1f), live = true,
                        muted = info.charge.muted)
                    CarMiniStat(painterResource(R.drawable.ic_phone), VehicleCopy.NOW_PHONE, info.phone.value, Modifier.weight(1f), live = true,
                        muted = info.phone.muted)
                }
            },
        ), modifier = Modifier.weight(1f).fillMaxWidth(), measurePolicy = policy)
    }
}

/**
 * The 차량 tile's body under its title (§3, §9.4): the drawing, 16 dp, the first row of mini stats and, where
 * [nowVehicleMoreRow] allows it, 16 dp and the second row. The tile's column gives the body the rest of the tile's height (the
 * weight's share); the rows keep their own heights and the drawing, fitted and centred, takes what they leave. The drawing's
 * own height is the body's width at the bitmap's [aspect]; the second row stands only where the slot exceeds it by the row and
 * its gap (62 dp at 1×, more at a larger font), and [onMore] hears the decision whenever the body is measured. Asked for its
 * intrinsic height, the body answers with the drawing at [NOW_VEHICLE_PICTURE_MIN] and the first row, so the tile's least never
 * reads the bitmap's size or counts the second row; it reports no intrinsic width.
 */
private class VehicleBodyPolicy(private val aspect: Float, private val onMore: (Boolean) -> Unit) : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val (picture, first, second) = measurables
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val rows = Constraints(minWidth = width, maxWidth = width)
        val gap = NOW_TILE_GAP.roundToPx()
        val top = first.map { it.measure(rows) }
        val topHeight = top.sumOf { it.height }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight
            else (NOW_VEHICLE_PICTURE_MIN.roundToPx() + gap + topHeight).coerceAtLeast(constraints.minHeight)
        val slot = (height - gap - topHeight).coerceAtLeast(0)
        // Measured to learn its height; placed only where the slot has room for it beyond the drawing's own height.
        val below = second.map { it.measure(rows) }
        val belowHeight = below.sumOf { it.height }
        val need = maxOf(NOW_VEHICLE_MORE, (gap + belowHeight).toDp())
        val shown = below.isNotEmpty() && nowVehicleMoreRow(slot.toDp(), (width * aspect).toDp(), need)
        onMore(shown)
        val pictureHeight = if (shown) (slot - gap - belowHeight).coerceAtLeast(0) else slot
        val drawn = picture.map { it.measure(Constraints.fixed(width, pictureHeight)) }
        return layout(width, height) {
            drawn.forEach { it.place(0, 0) }
            var y = pictureHeight + gap
            top.forEach { placeable -> placeable.place(0, y); y += placeable.height }
            if (shown) below.forEach { it.place(0, y + gap) }
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int =
        NOW_VEHICLE_PICTURE_MIN.roundToPx() + NOW_TILE_GAP.roundToPx() + measurables[1].sumOf { it.minIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<List<IntrinsicMeasurable>>, width: Int): Int =
        NOW_VEHICLE_PICTURE_MIN.roundToPx() + NOW_TILE_GAP.roundToPx() + measurables[1].sumOf { it.maxIntrinsicHeight(width) }

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int = 0
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<List<IntrinsicMeasurable>>, height: Int): Int = 0
}

// ───────────────────────── the column ─────────────────────────

/**
 * The 지금 column's arrangement (2.15, §3): [driver] (the 사용자 card with its own 16-dp gap under it), [tiles] (예약, 오늘
 * 운행 and 이달 운행일지 with their gaps), 16 dp, [vehicle] and the 8-dp foot, top to bottom. The four tiles above are measured
 * at the column's width with no height limit; the 사용자 card is never asked for intrinsics (it holds a BoxWithConstraints and
 * flow rows). Only [vehicle] is asked for its least height at the font in effect, then given [nowVehicleHeight] of the page,
 * or its least where that is null. The page is the column's minimum height: the column fills its slot ([modifier] carries
 * fillMaxHeight before the scroll), and a vertical scroll passes the minimum on while it lifts the maximum. So the column
 * rests exactly on the page's foot while everything fits (nothing to scroll), and outgrows the page by the overflow where it
 * does not. Pixel rounding never makes the tile taller than the room, so a fitting column never scrolls by a pixel.
 */
@Composable internal fun NowColumnLayout(driver: @Composable () -> Unit, tiles: @Composable () -> Unit, vehicle: @Composable () -> Unit,
    modifier: Modifier = Modifier) {
    Layout(contents = listOf(driver, tiles, vehicle), modifier = modifier, measurePolicy = NowColumnPolicy)
}

/** [NowColumnLayout]'s measure: see there. */
private object NowColumnPolicy : MultiContentMeasurePolicy {
    override fun MeasureScope.measure(measurables: List<List<Measurable>>, constraints: Constraints): MeasureResult {
        val (driver, tiles, vehicle) = measurables
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val free = Constraints(minWidth = width, maxWidth = width)
        val above = (driver + tiles).map { it.measure(free) }
        val others = above.sumOf { it.height }
        val gap = NOW_TILE_GAP.roundToPx()
        val foot = NOW_COLUMN_FOOT.roundToPx()
        val page = constraints.minHeight
        val least = vehicle.maxOfOrNull { it.minIntrinsicHeight(width) } ?: 0
        val fit = nowVehicleHeight(page.toDp(), others.toDp(), least.toDp())
        val tile = fit?.roundToPx()?.coerceAtMost(page - others - gap - foot) ?: least.coerceAtLeast(NOW_VEHICLE_MIN.roundToPx())
        val placed = vehicle.map { it.measure(Constraints(minWidth = width, maxWidth = width, minHeight = tile, maxHeight = tile)) }
        val content = others + (if (placed.isEmpty()) 0 else gap + tile) + foot
        return layout(width, content.coerceAtLeast(page)) {
            var y = 0
            above.forEach { placeable -> placeable.place(0, y); y += placeable.height }
            y += gap
            placed.forEach { it.place(0, y) }
        }
    }
}
