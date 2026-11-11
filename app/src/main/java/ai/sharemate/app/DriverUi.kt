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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
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
 * with [ring] the 1.5-dp selection ring. The letters take titleMedium from 48 dp up and labelLarge below, and step down only
 * where they would not fit the disc (two Hangul syllables at a 1.3× font are wider than 40 dp). Nobody (a null [driver], or
 * a person without a name) is the person glyph at 45 % of the disc in the icon ink, where 2.11 showed a dash: initials always
 * mean someone. Decorative: the card or row around it names the person.
 */
@Composable internal fun DriverAvatar(driver: Driver?, size: Dp, modifier: Modifier = Modifier, ring: Boolean = false) {
    val style = if (size >= 48.dp) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
    val ink = Scene.text
    val skin = Car.skin
    val monogram = driver?.name?.takeIf { it.isNotBlank() }?.let(::driverMonogram)
    Box(modifier.size(size).clip(CircleShape).background(skin.disc)
        .then(if (ring) Modifier.border(BorderStroke(1.5.dp, Scene.selectionRing), CircleShape) else Modifier)
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
 * person; the chosen one on the selection fill with its ring, the name in glow and a ✓. One tap commits and closes (the same
 * person just closes) and the follow-up bar says what changed, with undo where there was someone before
 * ([driverPickMessage]). Over eight 직원 bring a search field. The list takes what the title, the search field, the footnote
 * and the footer leave, at most 60 % of the window, so the footer's buttons stay whole on a short window; it opens scrolled to
 * the chosen card. 720 dp at most in the AlertDialog colour, and it fades in and out: car dialogs fade only (spec C), so the
 * window's own fade-and-slide gives way to [R.style.CarDialogFade] (160 / 120 ms), and to nothing under reduced motion.
 */
@Composable internal fun DriverPickerDialog(data: TripLogData, tripId: String?, act: Act, repository: TripLogRepository, dismiss: () -> Unit, now: Long,
    goSettings: () -> Unit) {
    val followUp = LocalFollowUp.current
    val forTrip = tripId != null
    val trip = tripId?.let { id -> data.trips.firstOrNull { it.id == id } ?: data.open?.takeIf { it.id == id } }
    val chosenId = if (forTrip) trip?.driverId else data.currentDriverId
    val title = if (forTrip) "이 운행을 운전한 직원" else "지금 운전하는 직원"
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
    val reduce = LocalReduceMotion.current
    Dialog(onDismissRequest = dismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Set while the dialog's content first composes, which is before the window's first layout, so the enter fade applies.
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, reduce) {
            window?.setWindowAnimations(if (reduce) 0 else R.style.CarDialogFade)
            onDispose {}
        }
        // The content wraps the card (the window spans the screen's width), so a tap beside the card still dismisses it.
        BoxWithConstraints(Modifier.padding(24.dp)) {
            val columns = pickerColumns(minOf(maxWidth, 720.dp) - 56.dp, LocalDensity.current.fontScale)
            // A dialog's window always bounds the card. Unbounded (a preview), a weighted list would get no height at all, so
            // there it is a plain child capped at 480 dp.
            val bounded = maxHeight.isFinite
            val listMax = if (bounded) maxHeight * .6f else 480.dp
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
            Surface(Modifier.widthIn(max = 720.dp).fillMaxWidth().semantics { paneTitle = title }, shape = RoundedCornerShape(28.dp),
                color = AlertDialogDefaults.containerColor, contentColor = MaterialTheme.colorScheme.onSurface, tonalElevation = AlertDialogDefaults.TonalElevation) {
                Column(Modifier.padding(28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, color = Scene.text, modifier = Modifier.semantics { heading() })
                        // With nobody to pick, the empty state below says everything there is to say.
                        val lead = when {
                            data.drivers.isEmpty() -> null
                            trip != null -> pickerTripLine(trip, zone)
                            forTrip -> null
                            data.open != null -> "새 운행과 기록 중인 운행에 바로 반영돼요."
                            else -> "새 운행부터 적용돼요."
                        }
                        lead?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary) }
                    }
                    if (data.drivers.isEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("등록된 직원이 없어요", style = MaterialTheme.typography.titleMedium, color = Scene.text)
                        Text("부서와 성명을 등록하면 운행마다 운전한 직원이 운행기록부 ④ 사용자 칸에 들어가요.", style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)
                    } else {
                        if (searchable) Field("이름이나 부서로 찾기", query, { value ->
                            if (value.length <= 30) { query = value; scope.launch { grid.scrollToItem(0) } }
                        }, done = true)
                        // The grid is the card's weighted child, so the column measures it last: the title, the search field, the
                        // footnote and the footer keep their height on a short window, and the list scrolls in what they leave. It
                        // does not fill, so a short list still wraps the card.
                        if (rows.isEmpty()) Text("맞는 직원이 없어요", style = MaterialTheme.typography.bodyLarge, color = Scene.textSecondary,
                            modifier = Modifier.padding(vertical = 12.dp).semantics { liveRegion = LiveRegionMode.Polite })
                        else LazyVerticalGrid(GridCells.Fixed(columns), Modifier.then(if (bounded) Modifier.weight(1f, fill = false) else Modifier).fillMaxWidth()
                            .heightIn(max = listMax).selectableGroup(), state = grid,
                            verticalArrangement = Arrangement.spacedBy(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(rows, key = { it.driver.id }) { row -> DriverPickCard(row, forTrip) { pick(row.driver) } }
                        }
                    }
                    if (forTrip && data.drivers.isNotEmpty()) Text("운행기록부 ④ 사용자 칸이 바뀌고 수정 이력에 남아요.", style = MaterialTheme.typography.bodySmall,
                        color = Scene.textMuted)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        DialogButton(if (data.drivers.isEmpty()) "직원 추가" else "직원 추가·관리", onClick = goSettings)
                        DialogButton("닫기", onClick = dismiss)
                    }
                }
            }
        }
    }
}

/**
 * One person in the picker: a card of at least 88 dp that settles to .98 and lights with the press fill. The chosen one sits
 * on the selection fill inside its 1.5-dp ring, with the name in glow and a trailing ✓; by day the others are white glass
 * ([pickerCardStyle] keeps every status line at AA on the dialog). TalkBack hears a radio button "Kim Jiwoo, Sales, 마지막
 * 운행 어제 18:47" and, when chosen, "지금 운전 중" (for a trip, "이 운행의 운전자").
 */
@Composable private fun DriverPickCard(row: DriverRowInfo, forTrip: Boolean, onPick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val chosen = row.selected
    val description = driverRowDescription(row)
    val paint = pickerCardStyle(Scene.colors, chosen)
    Surface(onClick = onPick, shape = RoundedCornerShape(20.dp), color = paint.fill, contentColor = Scene.text,
        border = if (chosen) BorderStroke(1.5.dp, Scene.selectionRing) else null, interactionSource = interaction,
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).pressable(interaction, .98f).semantics {
            selected = chosen; role = Role.RadioButton; contentDescription = description
            if (chosen) stateDescription = if (forTrip) "이 운행의 운전자" else "지금 운전 중"
        }) {
        Row(Modifier.pressFill(interaction).heightIn(min = 88.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DriverAvatar(row.driver, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(driverName(row.driver), style = MaterialTheme.typography.titleMedium, color = if (chosen) Scene.glow else Scene.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (row.driver.department.isNotBlank()) Text(row.driver.department, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(row.status, style = MaterialTheme.typography.labelMedium, color = paint.status, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (chosen) Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(24.dp), tint = Scene.glow)
        }
    }
}

// ───────────────────────── 설정 › 직원 ─────────────────────────

/** 설정 › 직원 puts the editor beside the list from this card width (two halves of 516 dp on the 1112-dp page at FULL), under it below. */
private val DRIVER_MANAGER_SIDE_BY_SIDE = 960.dp

/**
 * 설정 › 직원 (2.11): the list of 직원 and its editor, side by side when the card is 960 dp or wider, the editor under the list
 * otherwise. A row (avatar 48 · name over department, trips and last drive · the 지금 운전 chip on the person driving) opens
 * that person in the editor and brings it into view. Under the rows: 새 직원 (off at 50, saying why) and the daily driver
 * check, moved here from 기록. The editor adds a person (a same-name warning never blocks), fixes one, makes them the driver,
 * or deletes one without trips; every change answers on the follow-up bar with its way back. [data] is the shell's, its trips
 * those of the data set on screen; whether a person can be deleted is read from the repository's unfiltered data, the data
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
                Eyebrow("직원 · ④ 부서와 성명")
                if (rows.isEmpty()) {
                    Text("아직 등록된 직원이 없어요", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                    Muted("차량을 이용하는 직원의 부서와 성명을 등록하세요. 운행마다 운행기록부 ④ 사용자 칸에 들어가요.")
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("등록된 직원 ${rows.size}명", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).semantics { heading() })
                        Muted("최대 50명")
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        rows.forEach { row ->
                            key(row.driver.id) {
                                DriverListRow(row, editing = row.driver.id == editing?.id, history = driverHistoryLine(row.trips, row.lastAt, now, zone)) { edit(row.driver) }
                            }
                        }
                    }
                    QuietAction("새 직원", enabled = !full, onClick = startNew)
                    if (full) Muted("직원은 50명까지 등록할 수 있어요")
                }
                SwitchRow("출발 전 운전자 확인", "직원이 2명 이상이면 하루 한 번 지금 운전하는 직원이 맞는지 물어요 · 기본 켜짐", data.profile.confirmDriverDaily) { value ->
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
            Surface(place.fillMaxWidth().bringIntoViewRequester(editor), shape = RoundedCornerShape(24.dp), color = Scene.fill, contentColor = Scene.text) {
                Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (person == null) Text("새 직원", style = MaterialTheme.typography.titleLarge, color = Scene.text, modifier = Modifier.semantics { heading() })
                    else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            DriverAvatar(person, 56.dp, ring = current)
                            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(driverName(person), style = MaterialTheme.typography.titleLarge, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (person.department.isNotBlank()) Text(person.department, style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(driverHistoryLine(row?.trips ?: 0, row?.lastAt, now, zone), style = MaterialTheme.typography.bodyMedium, color = Scene.textMuted)
                            }
                        }
                        if (current) Text("지금 운전하는 직원이에요", style = MaterialTheme.typography.bodyLarge, color = Scene.glow)
                        else QuietAction("지금 운전하는 직원으로", Modifier.fillMaxWidth()) { makeCurrent(person) }
                    }
                    // 부서 and 성명 pair up only when the editor spans the card and the form has two columns; the half-card editor stacks them.
                    if (twoColumns && !wide) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { departmentField(Modifier.weight(1f)); nameField(Modifier.weight(1f)) }
                    else { departmentField(Modifier); nameField(Modifier) }
                    if (driverDuplicate(data.drivers, department, name, person?.id)) Text("같은 부서에 같은 이름이 있어요", style = MaterialTheme.typography.bodyMedium,
                        color = Scene.warn, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (person != null) Muted("고친 부서와 성명은 이 직원의 지난 운행 ④ 사용자 칸에도 그대로 들어가요.")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (person == null) PrimaryAction("직원 추가", Modifier.weight(1f), enabled = name.isNotBlank() && !full, onClick = add)
                        else {
                            val changed = department.trim() != person.department || name.trim() != person.name
                            PrimaryAction("수정 저장", Modifier.weight(1f), enabled = changed && name.isNotBlank()) { save(person) }
                            QuietAction("취소", Modifier.weight(1f)) { editingId = null }
                        }
                    }
                    if (person != null) {
                        if (driverRemovable(stored.data, person.id)) DialogButton("직원 삭제", color = Scene.negative) { delete(person) }
                        else Muted("운행 기록이 있어 삭제할 수 없어요 · 부서와 성명은 고칠 수 있어요")
                    }
                }
            }
        }
        Panel {
            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(LocalLayout.current.cardGap)) { list(Modifier.weight(1f)); form(Modifier.weight(1f)) }
            else { list(Modifier); form(Modifier) }
        }
    }
}

/**
 * One person in 설정 › 직원: a 72-dp row that settles to .98 with the press fill and opens the person in the editor. The one
 * being edited sits on the selection fill inside its ring ("수정 중" for TalkBack); the person driving now carries the chip.
 */
@Composable private fun DriverListRow(row: DriverRowInfo, editing: Boolean, history: String, onEdit: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(18.dp)
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).pressable(interaction, .98f).clip(shape)
        .background(if (editing) Scene.selectionFill else Color.Transparent)
        .then(if (editing) Modifier.border(1.5.dp, Scene.selectionRing, shape) else Modifier)
        .clickable(interactionSource = interaction, indication = null, onClickLabel = "수정", role = Role.Button, onClick = onEdit)
        .semantics { if (editing) stateDescription = "수정 중" }
        .pressFill(interaction).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        DriverAvatar(row.driver, 48.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(driverName(row.driver), style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(row.driver.department.trim(), history).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodyMedium,
                color = Scene.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (row.selected) NowChip()
    }
}

/** The 지금 운전 chip: the glow label on the row fill inside the selection ring, so it still reads on the row being edited. */
@Composable private fun NowChip() {
    val shape = RoundedCornerShape(50)
    Text("지금 운전", style = MaterialTheme.typography.labelMedium, color = Scene.glow, maxLines = 1,
        modifier = Modifier.clip(shape).background(Scene.fill).border(1.dp, Scene.selectionRing, shape).padding(horizontal = 12.dp, vertical = 6.dp))
}
