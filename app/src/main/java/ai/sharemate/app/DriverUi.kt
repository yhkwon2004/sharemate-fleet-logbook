package ai.sharemate.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isFinite
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/*
 * Driver management on the car (2.11): one avatar per person, the 사용자 card that heads the 지금 column, the picker that
 * switches the driver in one tap with undo, and 설정 › 직원 as a list beside its editor. Switching never needs typing, targets
 * are 56-88 dp, nothing ripples (the .98 press and the press fill instead), and none of it is reachable while driving: the
 * shell composes none of it while blocked. The pure rules live in DriverRoster.kt.
 */

// ───────────────────────── 아바타 ─────────────────────────

/**
 * A person's avatar (2.12): their [driverMonogram] in bold [Scene.text] on the car's disc (14.80 / 13.26), with no edge, or
 * with [ring] (2.13) a 2-dp accentInk ring, the one selection paint's (4.20 / 6.35 against the disc; the 1.5-dp teal ring of
 * 2.12 was the second selection colour beside the blue accent). The letters take titleMedium from 48 dp up and labelLarge
 * below, and step down only where they would not fit the disc (two Hangul syllables at a 1.3× font are wider than 40 dp).
 * Nobody (a null [driver], or a person without a name) is the person glyph at 45 % of the disc in the icon ink, where 2.11
 * showed a dash: initials always mean someone. Decorative: the card or row around it names the person.
 */
@Composable internal fun DriverAvatar(driver: Driver?, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    val style = if (size >= 48.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
    val ink = Scene.text
    val skin = Car.skin
    val monogram = driver?.name?.takeIf { it.isNotBlank() }?.let(::driverMonogram)
    Box(modifier.size(size).clip(CircleShape).background(skin.disc)
        .then(if (ring) Modifier.border(BorderStroke(2.dp, skin.accentInk), CircleShape) else Modifier)
        .padding(horizontal = size * .14f).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (monogram == null) Icon(Icons.Outlined.Person, contentDescription = null, Modifier.size(size * .45f), tint = skin.icon)
        else BasicText(monogram, style = style.copy(fontWeight = FontWeight.Bold, lineHeight = TextUnit.Unspecified, textAlign = TextAlign.Center),
            color = { ink }, softWrap = false, maxLines = 1, autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = style.fontSize, stepSize = .5.sp))
    }
}

// ───────────────────────── 사용자 ─────────────────────────

/** The answered question folds away in 200 ms (spec C), as the narrow window's question row does. */
private const val NOW_FOLD_MS = 200

/** The no-직원 card's one line beside the avatar (2.12), and the long form TalkBack hears in its place. */
internal const val NOW_CARD_NO_DRIVERS_LINE = "부서·성명이 ④ 사용자 칸에 들어가요"
internal const val NOW_CARD_NO_DRIVERS_SPOKEN = "운행기록부 ④ 사용자 칸에 들어갈 부서와 성명을 등록해 주세요"

/**
 * 사용자, the card at the top of the 지금 column (2.12, the mock's card; 지금 운전 in 2.11): who drives now, and today's
 * question while [driverConfirmDue] asks it. The title row is drawn in every state, the heading, with the recording state at
 * its end ([StatusTag]: 자동 기록 대기 sits right of 사용자, where 2.11 had it in the header). By default the avatar 56 ·
 * name over department · 바꾸기, then the last drive on one line; TalkBack hears the person as one stop
 * ([driverNowDescription], which also says whether today is confirmed). While the question is due, a divider and "오늘도 …
 * 님이 운전하나요?" join with 네 and 다른 직원 (half width each) and 바꾸기 steps aside. Both answers record today in
 * [confirmed], the shell's day keys shared with the narrow window's [DriverConfirmRow]; 다른 직원 also opens the picker. The
 * answered block folds away in 200 ms (FastOutSlowIn, a snap under reduced motion). With nobody chosen it asks "누가
 * 운전하나요?"; with no 직원 it leads to 설정 › 직원 ([openDrivers]). The lines are remembered on the data and the day, since
 * [now] ticks every second.
 */
@Composable internal fun DriverNowCard(state: TripLogState, now: Long, confirmed: String, onConfirmed: (String) -> Unit, openPicker: () -> Unit,
    openDrivers: () -> Unit, modifier: Modifier = Modifier) {
    val data = state.data
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val dates = confirmed.toIdSet()
    val due = driverConfirmDue(data, now, zone, dates)
    // The answered question folds away still naming the person it asked about, then leaves the composition.
    var asked by remember { mutableStateOf(due) }
    if (due != null) asked = due
    val question = remember { MutableTransitionState(due != null) }
    question.targetState = due != null
    val reduce = LocalReduceMotion.current
    // The blocks keep their own gaps (gap 0), so the question's fold can carry the gap above it and close it with itself.
    CarCard(modifier, padding = NOW_TILE_PADDING, gap = 0.dp, title = { CarSectionTitle("사용자", trailing = { StatusTag(state, now) }) }) {
        when {
            data.drivers.isEmpty() -> NowCardPrompt("등록된 직원이 없어요", NOW_CARD_NO_DRIVERS_LINE, NOW_CARD_NO_DRIVERS_SPOKEN, "직원 추가", openDrivers)
            driver == null -> NowCardPrompt("누가 운전하나요?", null, null, "운전자 선택", openPicker)
            else -> {
                val lastAt = remember(data.trips, data.profile.demoMode, driver.id) { lastDriveAt(data, driver.id) }
                val answered = dateKey(now, zone) in dates
                val day = localDate(now)
                val status = remember(lastAt, day, answered) { driverStatusLine(lastAt, now, zone, answered) }
                val lastDrive = remember(lastAt, day) { lastDriveLabel(lastAt, now, zone) }
                val name = driverName(driver)
                // 바꾸기 keeps the mock's 151 dp until the name needs the room, then gives way down to 112 before the name ellipsises
                // ([changeButtonWidth]); both are measured in the styles they are drawn in, at the font in effect.
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val nameStyle = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)
                val labelStyle = MaterialTheme.typography.labelLarge
                val nameWidth = remember(name, nameStyle, density) { with(density) { measurer.measure(name, nameStyle, maxLines = 1, softWrap = false).size.width.toDp() } }
                val labelWidth = remember(labelStyle, density) { with(density) { measurer.measure("바꾸기", labelStyle, maxLines = 1, softWrap = false).size.width.toDp() } }
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val changeWidth = changeButtonWidth(maxWidth - 56.dp - 12.dp - 16.dp, nameWidth, labelWidth)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // The person, one stop for TalkBack; the heading is the title row's.
                            Row(Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = driverNowDescription(driver, status) },
                                verticalAlignment = Alignment.CenterVertically) {
                                DriverAvatar(driver, 56.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(name, style = nameStyle, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (driver.department.isNotBlank()) Text(driver.department, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                            // 바꾸기 steps aside while the question offers 다른 직원, and fades back once it is answered.
                            AnimatedVisibility(due == null, enter = fadeIn(if (reduce) snap() else tween(NOW_FOLD_MS, easing = FastOutSlowInEasing)),
                                exit = ExitTransition.None, label = "driver-now-change") {
                                Row {
                                    Spacer(Modifier.width(16.dp))
                                    CarPillButton("바꾸기", openPicker, width = changeWidth, description = "운전자 바꾸기")
                                }
                            }
                        }
                    }
                    // The last drive keeps the card's full width. TalkBack already heard it in the person's stop.
                    CarLine(lastDrive, Modifier.clearAndSetSemantics {}, color = Scene.textSecondary)
                }
                asked?.let { person ->
                    val ease = FastOutSlowInEasing
                    // The gap above the divider sits inside the fold, so it closes with the block instead of jumping after it.
                    AnimatedVisibility(visibleState = question, label = "driver-now-question",
                        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(NOW_FOLD_MS, easing = ease)) + expandVertically(tween(NOW_FOLD_MS, easing = ease)),
                        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(NOW_FOLD_MS, easing = ease)) + shrinkVertically(tween(NOW_FOLD_MS, easing = ease))) {
                        val answer = { onConfirmed((dates + dateKey(now, zone)).joined()) }
                        Column(Modifier.padding(top = 16.dp)) {
                            CarDivider()
                            Spacer(Modifier.height(16.dp))
                            // Content, not a supporting line: it may take two lines (a long name, a larger font).
                            Text(driverQuestion(person), style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.semantics { heading(); liveRegion = LiveRegionMode.Polite })
                            Spacer(Modifier.height(12.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                PrimaryAction("네", Modifier.weight(1f), onClick = answer)
                                QuietAction("다른 직원", Modifier.weight(1f)) { answer(); openPicker() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The 사용자 title row's tag: what the car is doing ([statusPillInfo], the header pill's words and tone, read at [now] by the
 * driving screen's rule), so 자동 기록 대기 stands right of 사용자 as the user asked. Its own polite live region: TalkBack
 * announces a recording that starts or stops without a touch.
 */
@Composable private fun StatusTag(state: TripLogState, now: Long) {
    val overview = drivingOverviewInfo(state, now, true, zone)
    val (label, tone) = statusPillInfo(state, overview.fresh, overview.status)
    CarTag(label, tone = tone, live = true)
}

/**
 * The card when there is nobody to show: the person glyph beside what is missing (and, for no 직원 at all, one [line] that
 * TalkBack hears as [spoken]), then the one action that fixes it across the card.
 */
@Composable private fun NowCardPrompt(title: String, line: String?, spoken: String?, action: String, onAction: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DriverAvatar(null, 56.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Scene.text)
                line?.let { CarLine(it, spoken = spoken) }
            }
        }
        PrimaryAction(action, Modifier.fillMaxWidth(), onClick = onAction)
    }
}

// ───────────────────────── 운전자 선택 ─────────────────────────

/**
 * The driver picker's dialog ([DriverPicker] in the shell), for the person driving now ([tripId] null) or for one trip. Cards
 * in [pickerColumns] columns in the stable order of [driverRows]: avatar 56, name, department and what is next for that
 * person; the chosen one in the one selection paint ([carSelection]: the chip fill inside a 2-dp accentInk ring) with a ✓
 * beside the name. One tap commits and closes (the same person just closes) and the follow-up bar says what changed, with
 * undo where there was someone before ([driverPickMessage]). Over eight 직원 bring a search field. The list takes what the
 * title, the search field, the footnote and the footer leave, at most 60 % of the window, so the footer's buttons stay whole
 * on a short window; it opens scrolled to the chosen card. Since 2.13 it is the car's dialog ([CarDialogFrame], 720 dp at
 * most): the card colour with its hairline, where Material's AlertDialog colour left the accent ink at 4.26 by day, and the
 * window fades only (spec C). Its lines are one each ([pickerLead]; the trip note an ⓘ row), and its footer is pill buttons,
 * or, with nobody registered, 닫기 beside the one way on, 직원 추가.
 */
@Composable internal fun DriverPickerDialog(data: TripLogData, tripId: String?, act: Act, repository: TripLogRepository, dismiss: () -> Unit, now: Long,
    goSettings: () -> Unit) {
    val followUp = LocalFollowUp.current
    val forTrip = tripId != null
    val trip = tripId?.let { id -> data.trips.firstOrNull { it.id == id } ?: data.open?.takeIf { it.id == id } }
    val chosenId = if (forTrip) trip?.driverId else data.currentDriverId
    val title = if (forTrip) OverlayCopy.PICKER_TRIP_TITLE else OverlayCopy.PICKER_NOW_TITLE
    val searchable = data.drivers.size >= PICKER_SEARCH_FROM
    var query by rememberSaveable { mutableStateOf("") }
    val shownQuery = if (searchable) query else ""
    // The statuses read the clock to the minute; the shell recomposes every second.
    val rows = remember(data, chosenId, shownQuery, now / 60_000) { driverRows(data, chosenId, now, zone, shownQuery) }
    val pick: (Driver) -> Unit = { driver ->
        dismiss()
        val previous = data.drivers.firstOrNull { it.id == chosenId }
        driverPickMessage(driver, previous, forTrip)?.let { message ->
            act {
                if (tripId != null) repository.setTripDriver(tripId, driver.id) else repository.selectDriver(driver.id)
                followUp(if (previous == null) FollowUp(message) else FollowUp(message, "실행 취소") {
                    if (tripId != null) repository.setTripDriver(tripId, previous.id) else repository.selectDriver(previous.id)
                })
            }
        }
    }
    CarDialogFrame(title, dismiss, maxWidth = 720.dp) {
        // The frame's content: the card less its 28-dp padding, inside the window less the frame's 24-dp margins.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = pickerColumns(maxWidth, LocalDensity.current.fontScale)
            // A dialog's window always bounds the card. Unbounded (a preview), a weighted list would get no height at all, so
            // there it is a plain child capped at 480 dp. Bounded, the list keeps 2.11's rule: at most 60 % of the room around
            // the card (the window less its margins), which is this content's room plus the card's padding.
            val bounded = maxHeight.isFinite
            val listMax = if (bounded) (maxHeight + 56.dp) * .6f else 480.dp
            // Opens with the chosen card's row second from the top (first when it is in the first row), clamped at the end.
            val chosenIndex = rows.indexOfFirst { it.selected }
            val grid = rememberLazyGridState(initialFirstVisibleItemIndex = if (chosenIndex < 0) 0 else ((chosenIndex / columns - 1).coerceAtLeast(0)) * columns)
            // A short window can leave the list less than those two rows. Once it is laid out, a chosen card that does not show
            // whole leads the list instead.
            LaunchedEffect(grid) {
                if (chosenIndex < 0) return@LaunchedEffect
                val laidOut = snapshotFlow { grid.layoutInfo }.first { it.visibleItemsInfo.isNotEmpty() }
                val card = laidOut.visibleItemsInfo.firstOrNull { it.index == chosenIndex }
                if (card == null || card.offset.y + card.size.height > laidOut.viewportEndOffset) grid.scrollToItem(chosenIndex)
            }
            val scope = rememberCoroutineScope()
            Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    CarSectionTitle(title, icon = rememberVectorPainter(Icons.Outlined.Person))
                    // With nobody to pick, the empty state below says everything there is to say.
                    pickerLead(data.drivers.isEmpty(), trip?.let { pickerTripLine(it, zone) }, forTrip, recording = data.open != null)?.let { CarLine(it) }
                }
                if (data.drivers.isEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                    DriverAvatar(null, 56.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(OverlayCopy.PICKER_EMPTY, style = MaterialTheme.typography.titleSmall, color = Scene.text)
                        CarLine(NOW_CARD_NO_DRIVERS_LINE, spoken = NOW_CARD_NO_DRIVERS_SPOKEN)
                    }
                } else {
                    if (searchable) Field(OverlayCopy.PICKER_SEARCH, query, { value ->
                        if (value.length <= 30) { query = value; scope.launch { grid.scrollToItem(0) } }
                    }, done = true)
                    // The grid is the column's weighted child, so the column measures it last: the title, the search field, the
                    // footnote and the footer keep their height on a short window, and the list scrolls in what they leave. It
                    // does not fill, so a short list still wraps the card.
                    if (rows.isEmpty()) Text(OverlayCopy.PICKER_NO_MATCH, style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary,
                        modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                    else LazyVerticalGrid(GridCells.Fixed(columns), Modifier.then(if (bounded) Modifier.weight(1f, fill = false) else Modifier).fillMaxWidth()
                        .heightIn(max = listMax).selectableGroup(), state = grid,
                        verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(rows, key = { it.driver.id }) { row -> DriverPickCard(row, forTrip) { pick(row.driver) } }
                    }
                }
                if (forTrip && data.drivers.isNotEmpty()) CarInfoRow(OverlayCopy.PICKER_TRIP_NOTE, spoken = OverlayCopy.SPOKEN_PICKER_TRIP_NOTE)
                // Nobody registered: closing and the one way on, as halves (the way on at the right). Otherwise two pills at the end.
                if (data.drivers.isEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    QuietAction("닫기", Modifier.weight(1f), onClick = dismiss)
                    PrimaryAction(OverlayCopy.PICKER_ADD, Modifier.weight(1f), onClick = goSettings)
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    CarPillButton(OverlayCopy.PICKER_MANAGE, onClick = goSettings)
                    CarPillButton("닫기", onClick = dismiss)
                }
            }
        }
    }
}

/**
 * One person in the picker: a card of at least 88 dp, corner 20, that settles to .98 and lights with the press fill. The others
 * are outlined with the chip border; the chosen one takes the one selection paint ([carSelection]: the chip fill inside a 2-dp
 * accentInk ring), its name stays [Scene.text] (2.12's glow name was the teal second selection colour) and the accentInk ✓
 * sits in the name's row, so the status line keeps its width ("마지막 운행 2025년 12월 30일" was cut on the chosen card).
 * The status is textMuted in both states (5.82 / 6.27 on the card, 5.19 / 5.88 on the chip fill). TalkBack hears a radio
 * button "Kim Jiwoo, Sales, 마지막 운행 어제 18:47" and, when chosen, "지금 운전 중" (for a trip, "이 운행의 운전자").
 */
@Composable private fun DriverPickCard(row: DriverRowInfo, forTrip: Boolean, onPick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val chosen = row.selected
    val description = driverRowDescription(row)
    val shape = RoundedCornerShape(20.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).pressable(interaction, .98f).carSelection(chosen, shape, outlined = true).clip(shape)
        .selectable(selected = chosen, interactionSource = interaction, indication = null, role = Role.RadioButton, onClick = onPick)
        .semantics {
            contentDescription = description
            if (chosen) stateDescription = if (forTrip) "이 운행의 운전자" else "지금 운전 중"
        }
        .pressFill(interaction).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        DriverAvatar(row.driver, 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(driverName(row.driver), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, color = Scene.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chosen) {
                    Spacer(Modifier.width(8.dp))
                    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp), tint = Car.skin.accentInk)
                }
            }
            if (row.driver.department.isNotBlank()) Text(row.driver.department, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(row.status, style = MaterialTheme.typography.labelMedium, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────── 설정 › 직원 ─────────────────────────

/**
 * 설정 › 직원 puts the editor beside the list from this card width, under it below. At FULL the 1112-dp page gives two halves of
 * 516 dp either side of the hairline (24-dp card padding, twice the card gap between them, 16 dp since 2.13), so a line
 * beside the daily-check switch keeps 388 dp.
 */
private val DRIVER_MANAGER_SIDE_BY_SIDE = 960.dp

/**
 * 설정 › 직원 (2.11; 2.12 one [CarCard] across the page): the list of 직원 and its editor, side by side with a hairline between
 * them when the card is 960 dp or wider, the editor under the list after a divider otherwise. The list's title counts the
 * 직원 beside the cap of 50; a row (avatar 48 · name over [driverListLine] on one line · the 지금 운전 tag on the person
 * driving) opens that person in the editor and brings it into view. Under the rows: 새 직원 (off at 50, saying why) and the
 * daily driver check, moved here from 기록. The editor sits on the card itself, never on a fill, since its accent button
 * would read weaker there; it adds a person (a same-name warning never blocks), fixes one, makes them the driver, or deletes
 * one without trips; every change answers on the follow-up bar with its way back. [data] is the shell's, its trips those of
 * the data set on screen; whether a person can be deleted is read from the repository's unfiltered data, the data
 * [TripLogRepository.removeDriver] checks, so 직원 삭제 is never offered where it would be refused. [twoColumns] lets 부서 and
 * 성명 share a row when the editor spans the card.
 */
@Composable internal fun DriverManager(data: TripLogData, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier, twoColumns: Boolean = false) {
    val followUp = LocalFollowUp.current
    val stored by repository.state.collectAsState()
    // The pane has no clock of its own; the rows' dates only move with the data.
    val now = remember(data) { System.currentTimeMillis() }
    val rows = remember(data, now) { driverRows(data, data.currentDriverId, now, zone) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = data.drivers.firstOrNull { it.id == editingId }
    // The fields start from the saved person and follow a save or an undo of it; what is typed in between stays.
    var department by rememberSaveable(editing?.id, editing?.department) { mutableStateOf(editing?.department.orEmpty()) }
    var name by rememberSaveable(editing?.id, editing?.name) { mutableStateOf(editing?.name.orEmpty()) }
    val editor = remember { BringIntoViewRequester() }
    // Bumped by a row or 새 직원; the editor comes into view once its new content is laid out.
    var reveal by remember { mutableIntStateOf(0) }
    LaunchedEffect(reveal) { if (reveal > 0) editor.bringIntoView() }
    val full = data.drivers.size >= MAX_DRIVERS
    val edit: (Driver) -> Unit = { person -> editingId = person.id; reveal++ }
    val startNew: () -> Unit = { editingId = null; department = ""; name = ""; reveal++ }
    val add: () -> Unit = {
        val person = Driver(UUID.randomUUID().toString(), department.trim(), name.trim())
        act {
            repository.saveDriver(person)
            department = ""; name = ""
            // The repository makes the first person it gets the driver; then there is nothing to offer.
            followUp(if (repository.state.value.data.currentDriverId == person.id) FollowUp("${driverName(person)} 님을 추가하고 지금 운전하는 직원으로 정했어요")
                else FollowUp("${driverName(person)} 님을 추가했어요", "운전자로 선택") { repository.selectDriver(person.id) })
        }
    }
    val save: (Driver) -> Unit = { before ->
        val after = before.copy(department = department.trim(), name = name.trim())
        act {
            repository.saveDriver(after)
            followUp(FollowUp("${driverName(after)} 님 정보를 고쳤어요", "실행 취소") { repository.saveDriver(before) })
        }
    }
    val makeCurrent: (Driver) -> Unit = { person ->
        val previous = data.drivers.firstOrNull { it.id == data.currentDriverId }
        act {
            repository.selectDriver(person.id)
            driverPickMessage(person, previous, forTrip = false)?.let { message ->
                followUp(if (previous == null) FollowUp(message) else FollowUp(message, "실행 취소") { repository.selectDriver(previous.id) })
            }
        }
    }
    val delete: (Driver) -> Unit = { person ->
        // Undo puts the person back where they were in the list, and back at the wheel if they were driving.
        val index = data.drivers.indexOfFirst { it.id == person.id }
        val wasCurrent = data.currentDriverId == person.id
        act {
            repository.removeDriver(person.id)
            editingId = null
            followUp(FollowUp("${driverName(person)} 님을 삭제했어요", "실행 취소") { repository.restoreDriver(person, index, wasCurrent) })
        }
    }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val wide = maxWidth >= DRIVER_MANAGER_SIDE_BY_SIDE
        val list: @Composable (Modifier) -> Unit = { place ->
            Column(place, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                CarSectionTitle(SettingsCopy.driversTitle(rows.size), icon = rememberVectorPainter(Icons.Outlined.Person)) {
                    Text(SettingsCopy.DRIVERS_CAP, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1)
                }
                if (rows.isEmpty()) CarLine(SettingsCopy.DRIVERS_EMPTY)
                else {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        rows.forEach { row ->
                            key(row.driver.id) {
                                DriverListRow(row, editing = row.driver.id == editing?.id, line = driverListLine(row.driver, row.trips, row.lastAt, now, zone)) {
                                    edit(row.driver)
                                }
                            }
                        }
                    }
                    QuietAction("새 직원", enabled = !full, onClick = startNew)
                    if (full) CarLine(SettingsCopy.DRIVERS_FULL)
                }
                CarDivider()
                SwitchRow(SettingsCopy.CONFIRM_TITLE, SettingsCopy.CONFIRM_LINE, data.profile.confirmDriverDaily,
                    icon = rememberVectorPainter(Icons.Outlined.CheckCircle), spoken = SettingsCopy.SPOKEN_CONFIRM) { value ->
                    act { repository.updateProfile(data.profile.copy(confirmDriverDaily = value)) }
                }
            }
        }
        val form: @Composable (Modifier) -> Unit = { place ->
            val person = editing
            val row = person?.let { p -> rows.firstOrNull { it.driver.id == p.id } }
            val current = person != null && person.id == data.currentDriverId
            val departmentField: @Composable (Modifier) -> Unit = { field ->
                Field("부서", department, { if (it.length <= 30) department = it }, field, hint = if (person == null) "예: 영업1팀" else null)
            }
            val nameField: @Composable (Modifier) -> Unit = { field ->
                Field("성명", name, { if (it.length <= 30) name = it }, field, done = true, hint = if (person == null) "예: 김서연" else null)
            }
            // The editor sits on the card itself (2.12): the accent button never stands on a fill.
            Column(place.fillMaxWidth().bringIntoViewRequester(editor), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (person == null) CarSectionTitle("새 직원", icon = rememberVectorPainter(Icons.Outlined.Add))
                else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        DriverAvatar(person, 56.dp, ring = current)
                        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(driverName(person), style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            CarLine(driverListLine(person, row?.trips ?: 0, row?.lastAt, now, zone))
                        }
                        if (current) NowChip()
                    }
                    if (!current) QuietAction(SettingsCopy.MAKE_DRIVER, Modifier.fillMaxWidth(), description = SettingsCopy.makeDriverSpoken(driverName(person))) {
                        makeCurrent(person)
                    }
                }
                // 부서 and 성명 pair up only when the editor spans the card and the form has two columns; the half-card editor stacks them.
                if (twoColumns && !wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { departmentField(Modifier.weight(1f)); nameField(Modifier.weight(1f)) }
                else { departmentField(Modifier); nameField(Modifier) }
                if (driverDuplicate(data.drivers, department, name, person?.id)) Text("같은 부서에 같은 이름이 있어요", style = MaterialTheme.typography.bodyMedium,
                    color = Scene.warn, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                if (person != null) CarLine(SettingsCopy.EDIT_NOTE)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (person == null) PrimaryAction("직원 추가", Modifier.weight(1f), enabled = name.isNotBlank() && !full, onClick = add)
                    else {
                        val changed = department.trim() != person.department || name.trim() != person.name
                        PrimaryAction("수정 저장", Modifier.weight(1f), enabled = changed && name.isNotBlank()) { save(person) }
                        QuietAction("취소", Modifier.weight(1f)) { editingId = null }
                    }
                }
                if (person != null) {
                    if (driverRemovable(stored.data, person.id)) CarPillButton(SettingsCopy.DELETE_DRIVER, onClick = { delete(person) }, color = Scene.negative)
                    else CarLine(SettingsCopy.KEEP_DRIVER, spoken = SettingsCopy.SPOKEN_KEEP_DRIVER)
                }
            }
        }
        CarCard {
            if (wide) {
                // The hairline halfway between the two equal halves, as tall as the taller of them (decorative).
                val rule = Car.skin.cardBorder
                Row(Modifier.fillMaxWidth().drawBehind {
                    val x = size.width / 2
                    drawLine(rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                }, horizontalArrangement = Arrangement.spacedBy(LocalLayout.current.cardGap * 2)) { list(Modifier.weight(1f)); form(Modifier.weight(1f)) }
            } else {
                list(Modifier)
                CarDivider()
                form(Modifier)
            }
        }
    }
}

/**
 * One person in 설정 › 직원: a 72-dp row that settles to .98 with the press fill and opens the person in the editor. The name
 * over one line ([driverListLine]) in textSecondary, which keeps AA on the selection fill (6.77 / 7.07; textMuted would fall
 * to 4.48 by night). The one being edited sits on the selection fill inside its ring ("수정 중" for TalkBack); the person
 * driving now carries the 지금 운전 tag, which TalkBack hears as the row's state, so each person stays one stop.
 */
@Composable private fun DriverListRow(row: DriverRowInfo, editing: Boolean, line: String, onEdit: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    val state = listOfNotNull(SettingsCopy.NOW_DRIVING.takeIf { row.selected }, "수정 중".takeIf { editing }).joinToString(", ")
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).pressable(interaction, .98f).clip(shape)
        .background(if (editing) Scene.selectionFill else Color.Transparent)
        .then(if (editing) Modifier.border(1.5.dp, Scene.selectionRing, shape) else Modifier)
        .clickable(interactionSource = interaction, indication = null, onClickLabel = "수정", role = Role.Button, onClick = onEdit)
        .semantics { if (state.isNotEmpty()) stateDescription = state }
        .pressFill(interaction).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DriverAvatar(row.driver, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(driverName(row.driver), style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            CarLine(line, color = Scene.textSecondary)
        }
        // Drawn only: the row's state already says it, and a tag of its own would split the row into two stops.
        if (row.selected) NowChip(Modifier.clearAndSetSemantics {})
    }
}

/**
 * The 지금 운전 tag (2.12): the ready dot in glow on the tag fill, the mark 자동 기록 대기 also carries. On its own fill, it reads
 * the same on the selection fill of the row being edited.
 */
@Composable private fun NowChip(modifier: Modifier = Modifier) = CarTag(SettingsCopy.NOW_DRIVING, modifier, tone = PillTone.READY)
