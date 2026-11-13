package ai.sharemate.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.MultiContentMeasurePolicy
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId

/*
 * 2.15 (docs/design/DESIGN_2_15.md §3): the 지금 column ends where the page ends. 2.14's column stopped about 290 dp above
 * the page's foot on every page, and open space reads as empty, so a fifth tile closes it: 차량, the plate, the car's picture
 * as the 차량 page draws it and three live figures under it. The tile takes what the page leaves ([nowVehicleHeight]); the
 * column's own layout ([NowColumnLayout]) measures the four tiles above it and gives the tile the rest, so the column never
 * scrolls in the FULL window at 1× and still scrolls, the tile at its least, where a large font or a short window overflows.
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
 * The most the tile takes (§3). At 280 dp the drawing is 134 dp tall and 322 wide, nearly the tile's 352-dp width (the bitmap
 * is 2.4 : 1), so a taller tile would only add air around it.
 */
internal val NOW_VEHICLE_MAX = 280.dp

/** The space under the column's last tile (2.13's bottom padding): it keeps the tile's day shadow inside the scroll. */
internal val NOW_COLUMN_FOOT = 8.dp

/**
 * The 차량 tile's height in a column [page] tall whose four tiles above take [others] with their three gaps (pure,
 * unit-tested): what is left after the 16-dp gap over the tile and the 8-dp foot, at most [NOW_VEHICLE_MAX]; null when even
 * the least does not fit, and the column then scrolls with the tile at its least. [min] is the tile's least at the font in
 * effect, which the column measures; it never counts for less than [NOW_VEHICLE_MIN]. In the FULL window at 1× the tallest
 * state (사용자 with a two-line question, 826 dp with the gaps) leaves 1,097 − 826 − 16 − 8 = 247 dp, and a short 사용자 card
 * (no question, first run) leaves the tile its 280.
 */
internal fun nowVehicleHeight(page: Dp, others: Dp, min: Dp = NOW_VEHICLE_MIN): Dp? {
    val least = min.coerceAtLeast(NOW_VEHICLE_MIN)
    val room = page - others - NOW_TILE_GAP - NOW_COLUMN_FOOT
    return if (room < least) null else room.coerceAtMost(NOW_VEHICLE_MAX.coerceAtLeast(least))
}

// ───────────────────────── the words (pure, unit-tested) ─────────────────────────

/**
 * What the 차량 tile shows: the [plate] for the title's tag (null while none is saved), the three figures and the [spoken]
 * form TalkBack hears as the tile's one stop.
 */
@Immutable
internal data class NowVehicleInfo(val plate: String?, val battery: String, val gear: String, val signal: String, val spoken: String)

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
 * The 차량 tile's words at [now] (pure, unit-tested). 배터리 is the charge of a sample under 45 s old in the mode on screen
 * (the rule of 홈's card and 차량 정보); 기어 is the gear as the 차량 page's card reads it ([vehicleHeroCopy]: only from a
 * fresh sample); 신호 is [nowVehicleSignal] of the header pill's state, read by the driving screen's rule as the 사용자 card's
 * tag reads it. A figure the car does not report is "—", heard as 정보 없음. The spoken form names the plate first.
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
    return NowVehicleInfo(plate, battery ?: VehicleCopy.NOW_NONE, gear ?: VehicleCopy.NOW_NONE, signal, spoken)
}

// ───────────────────────── the tile ─────────────────────────

/**
 * 차량 (2.15, §3), the 지금 column's last tile: the title row (the vehicle icon, 차량, the plate as a tag while one is saved,
 * the chevron), the car drawing of the 차량 page fitted and centred in all the height the tile has left, and one row of three
 * mini stats from the live signal, 배터리 · 기어 · 신호 ([nowVehicleInfo]); they are telemetry, so they update in place. A tap
 * opens 차량 ([onOpen], "차량 정보 보기"). TalkBack hears one stop, the plate and the three figures, then the drawing's own
 * words ([VehicleCopy.DRAWING]: a staged picture, not the car's model); the tag and the stats keep no stops of their own.
 *
 * The column always measures the tile at a height (what the page leaves, or its least): the drawing's slot takes the rest of
 * it ([PictureSlotPolicy]). Asked for its least height, the tile answers with the drawing at [NOW_VEHICLE_PICTURE_MIN] and its
 * other parts at the font in effect; it holds no SubcomposeLayout, BoxWithConstraints or lazy list (a CarCard, the title's
 * FlowRow, a tag, the slot and three live mini stats), so the column may ask it.
 */
@Composable internal fun VehicleTile(state: TripLogState, now: Long, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val info = nowVehicleInfo(state, now, zone)
    val plate = info.plate
    CarCard(modifier, onClick = onOpen, onClickLabel = VehicleCopy.NOW_OPEN, description = info.spoken, padding = NOW_TILE_PADDING,
        title = {
            // The plate is in the tile's one stop already; its tag would be a stop of its own.
            CarSectionTitle(VehicleCopy.NOW_TITLE, icon = painterResource(R.drawable.ic_vehicle), chevron = true,
                trailing = if (plate != null) { { Box(Modifier.clearAndSetSemantics {}) { CarTag(plate) } } } else null)
        }) {
        Layout(content = { Image(painterResource(R.drawable.sharemate_silver_car), VehicleCopy.DRAWING, contentScale = ContentScale.Fit) },
            modifier = Modifier.weight(1f).fillMaxWidth(), measurePolicy = PictureSlotPolicy)
        Row(Modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CarMiniStat(painterResource(R.drawable.ic_battery), VehicleCopy.NOW_BATTERY, info.battery, Modifier.weight(1f), live = true,
                muted = info.battery == VehicleCopy.NOW_NONE)
            CarMiniStat(painterResource(R.drawable.ic_gear_shift), VehicleCopy.GEAR, info.gear, Modifier.weight(1f), live = true,
                muted = info.gear == VehicleCopy.NOW_NONE)
            CarMiniStat(painterResource(R.drawable.ic_signal), VehicleCopy.NOW_SIGNAL, info.signal, Modifier.weight(1f), live = true)
        }
    }
}

/**
 * The drawing's slot: all the height the tile's column gives it (the weight's share, the drawing fitted and centred in it),
 * and [NOW_VEHICLE_PICTURE_MIN] as its intrinsic height, so the tile's least never reads the bitmap's own size.
 */
private object PictureSlotPolicy : MeasurePolicy {
    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else constraints.minWidth
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else NOW_VEHICLE_PICTURE_MIN.roundToPx().coerceAtLeast(constraints.minHeight)
        val placeables = measurables.map { it.measure(Constraints.fixed(width, height)) }
        return layout(width, height) { placeables.forEach { it.place(0, 0) } }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = NOW_VEHICLE_PICTURE_MIN.roundToPx()
    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int = NOW_VEHICLE_PICTURE_MIN.roundToPx()
    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = 0
    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int = 0
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
