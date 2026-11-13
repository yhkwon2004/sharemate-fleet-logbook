package ai.sharemate.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Cards that give the car app its reason to exist: things only the vehicle can do at the moment they happen.
 * Each card captures vehicle data by itself and asks the driver for one decision at most. 2.10: every colour here is a
 * scene token, so the same cards read on the day glass and in the night palette.
 */

private fun clock(at: Long) = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date(at))
private fun km(value: Double?) = value?.let { String.format(Locale.KOREA, "%,.1f km", it) } ?: "—"

internal fun openIncidents(data: TripLogData): List<Incident> =
    data.incidents.filter { it.demo == data.profile.demoMode && it.status == IncidentStatus.OPEN }.sortedByDescending { it.at }

internal fun recentIncidents(data: TripLogData, limit: Int = 12): List<Incident> =
    data.incidents.filter { it.demo == data.profile.demoMode }.sortedByDescending { it.at }.take(limit)

/** Pairing code shown as "123 456" so it reads at arm's length and over a phone call. */
internal fun pairingDisplay(code: String) = code.chunked(3).joinToString(" ")

/** The live code's countdown as "m:ss", or null without a live code (2.15 fix, §9.6: an expired code has none, not "0:00"). */
internal fun pairingRemaining(info: SyncInfo, now: Long): String? =
    livePairingCode(info, now)?.let { ((info.pairingExpiresAt - now) / 1000).coerceAtLeast(0) }?.let { "%d:%02d".format(it / 60, it % 60) }

/** The car caps a staggered entrance at five rows (spec C); later rows are simply there. */
internal const val CAR_APPEAR_CAP = 5

/**
 * Press feedback for a surface the driver taps on the car (spec C): the surface settles to .98 while held ([pressable]) and
 * no ripple draws (indication = null), as on the car's segmented controls and stepper arrows. A car card is built as
 * `Surface(modifier.carTap(source) { … })`, never `Surface(onClick = …)`, which brings Material's ripple back. The same
 * [source] goes nowhere else. [label] is the click label TalkBack reads.
 */
@Composable internal fun Modifier.carTap(source: MutableInteractionSource, enabled: Boolean = true, role: Role = Role.Button, label: String? = null,
    onClick: () -> Unit): Modifier =
    pressable(source, .98f).clickable(interactionSource = source, indication = null, enabled = enabled, onClickLabel = label, role = role, onClick = onClick)

// ───────────────────────── 사고·특이사항 ─────────────────────────

private fun incidentCaption(kind: IncidentKind) = when (kind) {
    IncidentKind.ACCIDENT -> "119 · 112 안내"
    IncidentKind.DAMAGE -> "촬영 안내"
    IncidentKind.BREAKDOWN -> "긴급출동 안내"
    IncidentKind.OTHER -> "메모로 남기기"
}

/** The compact dock's lead: the warning mark, 사고·특이사항 and its one line, 224 dp wide beside the buttons. */
private val DOCK_LEAD = 224.dp

/**
 * How strongly the tall dock's 사고 button is tinted with the negative over the card (pure, unit-tested): 10 % by day, 6 % by
 * night, the most that keeps its label in the negative and its caption in textMuted at AA on the tint. The negative is only
 * 4.81 on the bare night card, and 2.12's 16 % took the label down to 4.09.
 */
internal fun incidentTint(day: Boolean): Float = if (day) .10f else .06f

/**
 * One tap at the scene (홈's incident dock, pinned to the foot of the page, which never moves): the car fills time, odometer,
 * place, trip and driver, the driver only chooses what happened. A report answers "{종류} 기록됨 · 실행 취소": the card that
 * appears in the attention slot already says what was saved. Two forms ([form], 2.14), swapped on the card clock (a snap
 * under reduced motion) when the frame changes its choice:
 * - TALL (2.12's panel, [HOME_DOCK_TALL]): a header line (the warning mark, 사고·특이사항 and 한 번 누르면 차량 정보가 바로
 *   기록돼요; on its right, muted, 휴대폰에도 공유 while the phone link is on), then four equal 72-dp buttons, each the label
 *   over its caption: 사고 tinted with the negative ([incidentTint]), the others on the chip fill inside the chip border.
 * - COMPACT (2.13's row, [HOME_DOCK_COMPACT]): the lead (사고·특이사항, and whether the record reaches the phone too), then
 *   four 56-dp outlined stadiums, 사고 in the negative. A caption sits beside its label from 196 dp of button
 *   ([incidentCaptionBeside]) and under it, in a 72-dp button, below that: it is the safety hint, so it stays on screen.
 * Under 560 dp the buttons stand two by two in either form, the header or the lead on its own row above them; where the
 * tall header's one row does not fit ([dockHeaderOneRow]) its caption goes under the title. TalkBack hears
 * the header as one heading, "사고·특이사항, 한 번 누르면 차량 정보가 바로 기록돼요", and each button as "사고 기록, 119 · 112
 * 안내".
 */
@Composable internal fun IncidentDock(data: TripLogData, act: Act, repository: TripLogRepository, form: HomeForm, modifier: Modifier = Modifier) {
    val followUp = LocalFollowUp.current
    val haptic = LocalHapticFeedback.current
    val report: (IncidentKind) -> Unit = { kind ->
        act {
            val incident = repository.reportIncident(kind)
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            followUp(FollowUp("${kind.label} 기록됨", HomeCopy.UNDO) { repository.deleteIncident(incident.id) })
        }
    }
    val swap = cardSwap()
    val sync = data.profile.phoneSync
    AnimatedContent(targetState = form, modifier = modifier.fillMaxWidth(), transitionSpec = { swap }, label = "dock-form") { shown ->
        if (shown == HomeForm.TALL) TallDock(sync, report) else CompactDock(sync, report)
    }
}

/**
 * The tall dock ([IncidentDock]): the header line over four 72-dp buttons, two by two under 560 dp. Where the header's one
 * row does not fit ([dockHeaderOneRow]: a split window, or a large font) the caption takes its own line under the title, as
 * the compact dock's lead does there, with 휴대폰에도 공유 at the title row's end.
 */
@Composable private fun TallDock(sync: Boolean, report: (IncidentKind) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    CarCard(Modifier.heightIn(min = HOME_DOCK_TALL), padding = DOCK_TALL_PADDING) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val kinds = IncidentKind.entries
            val width = maxWidth
            Column(verticalArrangement = Arrangement.spacedBy(DOCK_HEADER_GAP)) {
                DockHeader(sync, oneRow = dockHeaderOneRow(width, fontScale))
                if (width < 560.dp) Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    kinds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) { pair.forEach { IncidentTile(it, Modifier.weight(1f)) { report(it) } } }
                    }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    kinds.forEach { IncidentTile(it, Modifier.weight(1f)) { report(it) } }
                }
            }
        }
    }
}

/**
 * The tall dock's header, one heading for TalkBack ([HomeCopy.SPOKEN_DOCK]): the warning mark, 사고·특이사항, the caption and,
 * while the phone link is on, 휴대폰에도 공유 on the right, muted. In [oneRow] all four stand in one 24-dp row (2.12's line);
 * otherwise the caption is a [CarLine] under the title, which a large font may wrap rather than cut.
 */
@Composable private fun DockHeader(sync: Boolean, oneRow: Boolean) {
    val title: @Composable (Modifier) -> Unit = { place ->
        Text(HomeCopy.DOCK_TITLE, place, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val shared: @Composable () -> Unit = {
        if (sync) {
            Spacer(Modifier.width(12.dp))
            Text(HomeCopy.DOCK_SHARED, style = MaterialTheme.typography.labelMedium, color = Scene.textMuted, maxLines = 1)
        }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = DOCK_HEADER).clearAndSetSemantics { contentDescription = HomeCopy.SPOKEN_DOCK; heading() }.padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(24.dp), tint = Car.skin.icon)
        Spacer(Modifier.width(10.dp))
        if (oneRow) {
            title(Modifier)
            Spacer(Modifier.width(12.dp))
            Text(HomeCopy.DOCK_CAPTION, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            shared()
        } else Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                title(Modifier.weight(1f))
                shared()
            }
            CarLine(HomeCopy.DOCK_CAPTION, color = Scene.textSecondary)
        }
    }
}

/**
 * One kind on the tall dock (2.14): a 72-dp tile, corner 16, the label in titleMedium over its caption in labelMedium
 * textMuted. 사고 is tinted with the negative ([incidentTint]) inside a negative outline, its label in the negative (4.78 /
 * 4.56 on the tint); the others sit on the chip fill inside the chip border. It settles to .98 with the press fill, no
 * ripple. TalkBack hears "사고 기록, 119 · 112 안내".
 */
@Composable private fun IncidentTile(kind: IncidentKind, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val skin = Car.skin
    val urgent = kind == IncidentKind.ACCIDENT
    val negative = Scene.negative
    val fill = if (urgent) negative.copy(alpha = incidentTint(Scene.isDay)) else skin.chipFill
    val outline = if (urgent) negative else skin.chipBorder
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.heightIn(min = DOCK_BUTTON_TALL).carTap(source, label = "${kind.label} 기록", onClick = onClick).clip(shape).background(fill)
        .border(1.dp, outline, shape).pressFill(source).semantics { contentDescription = "${kind.label} 기록, ${incidentCaption(kind)}" }
        .padding(horizontal = 12.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(kind.label, style = MaterialTheme.typography.titleMedium, color = if (urgent) negative else Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(incidentCaption(kind), style = MaterialTheme.typography.labelMedium, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The compact dock ([IncidentDock], 2.13's row): the lead beside four 56-dp stadiums; under 560 dp the lead over two by two. */
@Composable private fun CompactDock(sync: Boolean, report: (IncidentKind) -> Unit) {
    val fontScale = LocalDensity.current.fontScale
    val line = if (sync) HomeCopy.DOCK_LINE else HomeCopy.DOCK_LINE_OFF
    val lead: @Composable (Modifier) -> Unit = { place ->
        Row(place.clearAndSetSemantics { contentDescription = HomeCopy.SPOKEN_DOCK; heading() }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null, Modifier.size(24.dp), tint = Car.skin.icon)
            Spacer(Modifier.width(10.dp))
            Column {
                Text(HomeCopy.DOCK_TITLE, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CarLine(line)
            }
        }
    }
    CarCard(Modifier.heightIn(min = HOME_DOCK_COMPACT), padding = 12.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val gap = 12.dp
            val kinds = IncidentKind.entries
            if (maxWidth < 560.dp) {
                val beside = incidentCaptionBeside((maxWidth - gap) / 2, fontScale)
                Column(verticalArrangement = Arrangement.spacedBy(gap)) {
                    lead(Modifier.fillMaxWidth())
                    kinds.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(gap)) { pair.forEach { IncidentButton(it, beside, Modifier.weight(1f)) { report(it) } } }
                    }
                }
            } else {
                val beside = incidentCaptionBeside((maxWidth - DOCK_LEAD - gap * kinds.size) / kinds.size, fontScale)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(gap)) {
                    lead(Modifier.width(DOCK_LEAD))
                    kinds.forEach { IncidentButton(it, beside, Modifier.weight(1f)) { report(it) } }
                }
            }
        }
    }
}

/**
 * One kind on the dock: a 56-dp outlined stadium (72 with the caption under the label), the label in labelLarge and its
 * caption in textMuted; 사고 takes the negative for both label and outline (5.60 / 4.81). It settles to .98 with the press fill.
 * TalkBack hears "사고 기록, 119 · 112 안내".
 */
@Composable private fun IncidentButton(kind: IncidentKind, captionBeside: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val urgent = kind == IncidentKind.ACCIDENT
    val ink = if (urgent) Scene.negative else Scene.text
    val outline = if (urgent) Scene.negative else Car.skin.chipBorder
    val source = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Box(modifier.heightIn(min = if (captionBeside) 56.dp else 72.dp).carTap(source, label = "${kind.label} 기록", onClick = onClick).clip(shape).border(1.dp, outline, shape)
        .pressFill(source).semantics { contentDescription = "${kind.label} 기록, ${incidentCaption(kind)}" }.padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center) {
        val label: @Composable () -> Unit = { Text(kind.label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1) }
        val caption: @Composable () -> Unit = {
            Text(incidentCaption(kind), style = MaterialTheme.typography.labelMedium, color = Scene.textMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (captionBeside) Row(Modifier.clearAndSetSemantics {}, verticalAlignment = Alignment.CenterVertically) {
            label()
            Spacer(Modifier.width(8.dp))
            caption()
        } else Column(Modifier.clearAndSetSemantics {}, horizontalAlignment = Alignment.CenterHorizontally) {
            label()
            caption()
        }
    }
}

/**
 * Closes a report with a way back (2.13: 실행 취소 everywhere). A breakdown is offered a second life as a maintenance record
 * (남기기 beside the undo), because this is the only moment that connection is obvious; the reopen also stays one tap away in
 * the vehicle tab.
 */
internal fun closeIncident(incident: Incident, act: Act, repository: TripLogRepository, followUp: (FollowUp) -> Unit) = act {
    repository.updateIncident(incident.id, status = IncidentStatus.CLOSED)
    val reopen: suspend () -> Unit = { repository.updateIncident(incident.id, status = IncidentStatus.OPEN) }
    followUp(if (incident.kind == IncidentKind.BREAKDOWN) FollowUp("처리 완료 · 고장·경고를 정비 항목으로 남길까요?", HomeCopy.UNDO, second = FollowUpAction("남기기") {
        val record = repository.addMaintenance(MaintenanceKind.REPAIR, 0, null, incident.note.ifBlank { "고장·경고 특이사항에서 남김" }.take(300), incident.odometerKm)
        followUp(FollowUp("정비 항목으로 남겼어요 · 차량 탭에서 볼 수 있어요", HomeCopy.UNDO) { repository.removeMaintenance(record.id) })
    }, perform = reopen) else FollowUp("처리 완료로 바꿨어요", HomeCopy.UNDO, perform = reopen))
}

/** Removes a report and offers it back; photo files are gone for good, and the bar says so when there were any. */
internal fun deleteIncident(incident: Incident, act: Act, repository: TripLogRepository, followUp: (FollowUp) -> Unit) = act {
    repository.deleteIncident(incident.id)
    val photos = incident.photos.size
    followUp(FollowUp(if (photos > 0) "특이사항 기록과 사진 ${photos}장을 삭제했어요 · 사진은 복구되지 않아요" else "특이사항 기록을 삭제했어요", "실행 취소") {
        repository.restoreIncident(incident)
    })
}

/**
 * Thumbnails for a report's photos. A photo whose file is not on this device yet shows a placeholder and asks [missing]
 * to fetch it; [version] bumps when a fetch lands so the strip re-reads the files. The remove mark is a 28-dp disc whose
 * touch area is [tapTarget] square (48 on the phone, 56 on the car) and presses like the rest of its device: .98 with no
 * ripple on the car ([carTap]), .97 with Material's bounded ripple on the phone. The scrim under it stays black on purpose:
 * it sits on a photograph, not on the scene.
 */
@Composable internal fun PhotoStrip(photos: List<IncidentPhoto>, store: PhotoStore, version: Int = 0, size: Dp = 96.dp,
    missing: ((String) -> Unit)? = null, onRemove: ((IncidentPhoto) -> Unit)? = null, tapTarget: Dp = 48.dp) {
    if (photos.isEmpty()) return
    val placeholder = Scene.rail
    val car = LocalLayout.current.startAnchored
    Row(Modifier.horizontalScroll(rememberScrollState()).semantics { contentDescription = "첨부 사진 ${photos.size}장" }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        photos.forEach { photo ->
            val file = store.file(photo.id)
            val bitmap by produceState<android.graphics.Bitmap?>(null, photo.id, version) { value = withContext(Dispatchers.IO) { thumbnail(file) } }
            LaunchedEffect(photo.id, version) { if (!file.exists()) missing?.invoke(photo.id) }
            Box(Modifier.size(size).clip(RoundedCornerShape(14.dp)).background(placeholder)) {
                bitmap?.let { Image(it.asImageBitmap(), "사진 ${clock(photo.at)} · ${photo.source.label}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    ?: Text(if (missing != null) "받는 중" else "사진 없음", style = MaterialTheme.typography.labelSmall, color = Scene.textSecondary,
                        modifier = Modifier.align(Alignment.Center))
                if (onRemove != null) {
                    val source = remember(photo.id) { MutableInteractionSource() }
                    val area = Modifier.align(Alignment.TopEnd).size(tapTarget.coerceAtMost(size))
                    val press = if (car) area.carTap(source, label = "사진 삭제") { onRemove(photo) }
                        else area.pressable(source, .97f).clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button,
                            onClickLabel = "사진 삭제") { onRemove(photo) }
                    Box(press.semantics { contentDescription = "사진 삭제" }, contentAlignment = Alignment.TopEnd) {
                        Surface(shape = CircleShape, color = Color.Black.copy(alpha = .55f), contentColor = Color.White, modifier = Modifier.padding(4.dp).size(28.dp)) {
                            Text("×", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {})
                        }
                    }
                }
            }
        }
    }
}

/**
 * History for the vehicle page (2.12): the count as a tag beside the title, then one row per report with its state as a dot
 * (open in the negative, done in positive) and in words for TalkBack; a report closed too early can be reopened, and the
 * reopen's way back is 실행 취소, the one word for undo (2.13). Rows arrive staggered the first time only. The title's mark is
 * the line warning triangle of the car's icon family (2.13, ic_warning), where 2.12 drew Material's solid one.
 */
@Composable internal fun IncidentHistory(data: TripLogData, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier) {
    val items = recentIncidents(data)
    val followUp = LocalFollowUp.current
    // First composition only: rows a sync adds later, and the list on a return to this tab, are simply there.
    var introduced by rememberSaveable { mutableStateOf(false) }
    val stagger = remember { !introduced }
    val initial = remember { items.map { it.id }.toSet() }
    LaunchedEffect(Unit) { introduced = true }
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.INCIDENTS_TITLE, icon = painterResource(R.drawable.ic_warning), trailing = {
            CarTag("${data.incidents.count { it.demo == data.profile.demoMode }}건")
        })
    }) {
        if (items.isEmpty()) CarLine(VehicleCopy.INCIDENTS_EMPTY)
        else Column {
            items.forEachIndexed { index, incident ->
                val open = incident.status == IncidentStatus.OPEN
                val stamp = SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(Date(incident.at))
                val detail = listOfNotNull(incident.odometerKm?.let(::km), incident.address, incident.photos.size.takeIf { it > 0 }?.let { "사진 ${it}장" },
                    incident.note.ifBlank { null }).joinToString(" · ").ifBlank { "상세 없음" }
                // The rule above a row arrives with it.
                Appear(index = index.coerceAtMost(CAR_APPEAR_CAP - 1), animate = stagger && incident.id in initial) {
                    Column {
                        if (index > 0) CarDivider()
                        CarRow("${incident.kind.label} · $stamp", line = detail, state = incident.status.label,
                            lead = { Box(Modifier.size(12.dp).clip(CircleShape).background(if (open) Scene.negative else Scene.positive)) },
                            trailing = {
                                CarPillButton(if (open) "완료" else "다시 열기", {
                                    if (open) closeIncident(incident, act, repository, followUp)
                                    else act {
                                        repository.updateIncident(incident.id, status = IncidentStatus.OPEN)
                                        followUp(FollowUp("${incident.kind.label} 기록을 다시 열었어요 · 홈에 대처 안내가 떠요", "실행 취소") { repository.updateIncident(incident.id, status = IncidentStatus.CLOSED) })
                                    }
                                }, description = "${incident.kind.label} $stamp ${if (open) "처리 완료" else "다시 열기"}")
                            })
                    }
                }
            }
        }
    }
}

// ───────────────────────── 차량 카드 · 휴대폰 연결 ─────────────────────────

/** Identity chips read from the car itself; shows nothing until the vehicle has reported something. */
@Composable internal fun VehicleChips(state: TripLogState, modifier: Modifier = Modifier) {
    val card = state.data.vehicle
    val live = state.live?.takeIf { it.demo == state.data.profile.demoMode && System.currentTimeMillis() - it.at in 0..45_000 }
    val charging = live?.charging == true
    val chips = listOfNotNull(
        (state.vehicleModel ?: card.model)?.takeIf { it.isNotBlank() },
        live?.socPercent?.let { "배터리 $it%" } ?: card.socPercent?.takeUnless { state.data.profile.demoMode }?.let { "마지막 저장 잔량 $it%" },
        if (charging) "충전 중" else null,
        validVin(live?.vin ?: card.vin)?.takeLast(6)?.let { "VIN …$it" },
    )
    if (chips.isEmpty()) return
    Row(modifier.semantics(mergeDescendants = true) { contentDescription = "차량 정보 " + chips.joinToString(", ") }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        chips.forEach { chip ->
            val charge = chip == "충전 중"
            Surface(shape = RoundedCornerShape(50), color = if (charge) Scene.positive.copy(alpha = .18f) else Scene.fill, contentColor = if (charge) Scene.positive else Scene.text, border = Scene.edge) {
                Text(chip, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), maxLines = 1)
            }
        }
    }
}

/**
 * The phone link's state in a few words, with its tone (2.12, pure, unit-tested): off; the sync server down with an error, or
 * still starting; a phone on the line right now ([online]); phones paired but none on the line; or none paired yet. Waiting
 * and not-yet-paired take the ready tone: the server is up and listening.
 */
internal fun phoneLinkTag(enabled: Boolean, running: Boolean, error: String?, online: Boolean, paired: Int): Pair<String, PillTone> = when {
    // 2.15: off and the server's error in the 지금 column's 차량 tile's words, one constant for both ([nowVehiclePhone]).
    !enabled -> VehicleCopy.NOW_PHONE_OFF to PillTone.QUIET
    !running -> if (error != null) VehicleCopy.NOW_PHONE_ERROR to PillTone.NEGATIVE else VehicleCopy.NOW_PHONE_STARTING to PillTone.QUIET
    online -> "실시간 동기화 중" to PillTone.POSITIVE
    paired > 0 -> VehicleCopy.NOW_PHONE_WAITING to PillTone.READY
    else -> VehicleCopy.NOW_PHONE_UNPAIRED to PillTone.READY
}

/**
 * 휴대폰 연결 on the vehicle page (2.12): the link's state as a live tag beside the title ([phoneLinkTag]); while the link is on,
 * three small figures (the record version, the phone's last call, the phones paired) and, while the server runs, the pairing
 * ([PhonePairing]): since 2.15 the QR a phone scans, with the three things a phone types folded under it. The home keeps its own pairing card (2.13: the 휴대폰 연결 attention card, HomeAttention.kt).
 * What the link does sits behind ⓘ. Switched off (2.13), the card holds the switch itself, the same row as 설정 › 기록's
 * ([SettingsCopy.PHONE_SYNC_TITLE]), where 2.12 only said where to find it. 2.15 fix (docs/design/DESIGN_2_15.md §9.6): the
 * card stands across the 차량 page under its two columns ([VehiclePane]), and where it is wide enough ([stepsBeside],
 * [pairingStepsBeside]) the three typed steps stand beside the QR, open, instead of folded under it.
 */
@Composable internal fun PhoneSyncCard(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, modifier: Modifier = Modifier,
    stepsBeside: Boolean = false) {
    val info = state.sync
    val profile = state.data.profile
    val enabled = profile.phoneSync
    val seen = info.lastPhoneSeenAt.takeIf { it > 0 }?.let { (now - it) / 1000 }
    // A long-poll is held for up to 20 s, so anything under 45 s means the phone is sitting on the line right now.
    val online = seen != null && seen < 45
    val (tag, tone) = phoneLinkTag(enabled, info.running, info.error, online, info.pairedPhones)
    val phone = painterResource(R.drawable.ic_phone)
    CarCard(modifier, title = {
        CarSectionTitle(VehicleCopy.PHONE_TITLE, icon = phone, trailing = { CarTag(tag, tone = tone, live = true) })
    }) {
        // The title already carries the phone mark, so the switch row goes without its disc.
        if (!enabled) SwitchRow(SettingsCopy.PHONE_SYNC_TITLE, SettingsCopy.PHONE_SYNC_LINE, checked = false, spoken = SettingsCopy.SPOKEN_PHONE_SYNC) { value ->
            act { repository.updateProfile(profile.copy(phoneSync = value)) }
        }
        else {
            // The server's own words for what went wrong; the tag already says that it did.
            if (!info.running) info.error?.let { CarLine(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // The revision steps on every sync (it cross-fades); the seconds-ago figure is live and simply updates.
                CarMiniStat(painterResource(R.drawable.ic_journal), "기록 버전", "#${state.dataRevision}", Modifier.weight(1f))
                CarMiniStat(rememberVectorPainter(Icons.Outlined.Refresh), "최근 수신", seen?.let { if (it < 60) "${it}초 전" else "${it / 60}분 전" } ?: "없음",
                    Modifier.weight(1f), live = true)
                CarMiniStat(phone, "연결", "${info.pairedPhones}대", Modifier.weight(1f))
            }
            if (info.running) {
                CarDivider()
                PhonePairing(info, now, stepsBeside)
            }
            CarInfoRow(VehicleCopy.PHONE_NOTE, more = VehicleCopy.PHONE_NOTE_MORE)
        }
    }
}

/**
 * What the column beside the pairing QR says first (2.15, pure, unit-tested): what to do with the QR while there is a [link];
 * otherwise why there is none, in 2.13's words: no live [code] (another phone can still pair with a new one, or the code ran
 * out), or a code but no network address yet. 2.15 fix (§9.6): the caller passes the live code ([livePairingCode]), so the
 * moment a code runs out this says 연결 번호가 만료됐어요, never "scan this" over a dead code.
 */
internal fun pairingQrHeadline(link: String?, code: String?, pairedPhones: Int): String = when {
    link != null -> VehicleCopy.PHONE_QR
    code == null -> if (pairedPhones > 0) VehicleCopy.PHONE_ANOTHER else VehicleCopy.PHONE_EXPIRED
    else -> VehicleCopy.PHONE_ADDRESS_PENDING
}

/** From this card width (the width ÷ the font scale, §9.6) the three typed steps stand beside the QR, with no fold. */
internal val PAIRING_STEPS_BESIDE_MIN = 900.dp

/**
 * Whether the three steps a phone types stand beside the QR and its words (pure, unit-tested; 2.15 fix, docs/design/
 * DESIGN_2_15.md §9.6): from [PAIRING_STEPS_BESIDE_MIN] of card width, taken as [cardWidth] ÷ [fontScale] (never scaled
 * below 1×) as the window classes are, so a 1.3× font on the 1112-dp page (855 dp) folds them as a narrow card does. The
 * FULL page's card is 1112 dp: the QR (232), its words (248) and the steps (about 500) stand in one row.
 */
internal fun pairingStepsBeside(cardWidth: Dp, fontScale: Float): Boolean = cardWidth / fontScale.coerceAtLeast(1f) >= PAIRING_STEPS_BESIDE_MIN

/** The narrowest the column beside the QR gets before it stacks under the QR (a narrow card, a large font). */
private val PAIRING_SIDE_MIN = 200.dp

/** The column beside the QR where the steps stand beside it too (§9.6): the headline's one line (13 titleSmall units), the tag, the pill. */
private val PAIRING_SIDE_WIDTH = 248.dp

/** Between the words beside the QR and the steps: the hairline sits half way. */
private val PAIRING_STEPS_GAP = 24.dp

/** 직접 입력하기's fold and its arrow: the ⓘ row's 200 ms (spec C), a snap under reduced motion. */
private const val PAIRING_FOLD_MS = 200

/**
 * The pairing inside [PhoneSyncCard] (2.15, docs/design/DESIGN_2_15.md §6.2). The QR leads: the car's addresses, the live code
 * and the key in one link ([syncPairingLinkText]) on its white tile ([QrCode]); beside it what to do ([VehicleCopy.PHONE_QR]),
 * the countdown as a tag (it ticks in place, never announced) and the pill for a new code. Without a link a quiet tile keeps
 * the QR's place, so the card keeps its form when a code runs out, and the column says why ([pairingQrHeadline]) with 새
 * 연결 번호. The QR is rebuilt only when the link's text changes ([QrCode] remembers it), never on the clock's tick that
 * recomposes this card.
 *
 * 2.15 fix (§9.6): every one of those reads the live code ([livePairingCode] at [now]), so the QR, its headline, the
 * countdown and the pill drop a code the moment it runs out, before the server's publish at expiry reaches the state.
 *
 * Two forms. [stepsBeside] (a card of [PAIRING_STEPS_BESIDE_MIN] or more, [pairingStepsBeside]): one row, the QR with its
 * words in a 248-dp column, then a hairline and the three typed steps ([PairingSteps]) open, so the wide card is filled by
 * the code and its key rather than by air, and nothing is folded away. Otherwise as first built: the column beside the QR
 * stacks under it where it would be narrower than 200 dp (a FlowRow, so nothing asks a lazy or subcomposed child for its
 * intrinsic size), and under a hairline 직접 입력하기 folds the steps; it starts closed and keeps its state across a new code,
 * a page change, a rotation and a switch between the forms.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun PhonePairing(info: SyncInfo, now: Long, stepsBeside: Boolean) {
    val code = livePairingCode(info, now)
    val remaining = pairingRemaining(info, now)
    val link = syncPairingLinkText(info, now)
    var manual by rememberSaveable { mutableStateOf(false) }
    val reduce = LocalReduceMotion.current
    val ease = FastOutSlowInEasing
    val qr: @Composable () -> Unit = { if (link != null) QrCode(link, QR_MIN_SIDE) else PairingQrPlaceholder() }
    val words: @Composable (Modifier) -> Unit = { place ->
        Column(place, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(pairingQrHeadline(link, code, info.pairedPhones), style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            if (code != null && remaining != null) CarTag("$remaining 남음")
            CarPillButton(if (code == null) VehicleCopy.PHONE_NEW_CODE else VehicleCopy.PHONE_RENEW_CODE, { SyncServer.active?.refreshPairingCode() })
        }
    }
    if (stepsBeside) {
        // The QR and its words are one block, the words centred on the tile; the steps start level with the tile's top.
        val rule = Car.skin.cardBorder
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                qr()
                words(Modifier.width(PAIRING_SIDE_WIDTH))
            }
            Spacer(Modifier.width(PAIRING_STEPS_GAP))
            // The hairline is drawn at the steps' own start edge, as tall as they are: no intrinsic measurement of the row.
            PairingSteps(info, code, Modifier.weight(1f).drawBehind {
                val stroke = 1.dp.toPx()
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - stroke / 2 else stroke / 2
                drawLine(rule, Offset(x, 0f), Offset(x, size.height), stroke)
            }.padding(start = PAIRING_STEPS_GAP), reasonBeside = true)
        }
        return
    }
    Column(Modifier.fillMaxWidth()) {
        FlowRow(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            qr()
            words(Modifier.weight(1f).widthIn(min = PAIRING_SIDE_MIN))
        }
        CarDivider()
        PairingFoldRow(manual) { manual = it }
        AnimatedVisibility(manual, label = "pairing-manual",
            enter = if (reduce) fadeIn(snap()) else fadeIn(tween(PAIRING_FOLD_MS, easing = ease)) + expandVertically(tween(PAIRING_FOLD_MS, easing = ease)),
            exit = if (reduce) fadeOut(snap()) else fadeOut(tween(PAIRING_FOLD_MS, easing = ease)) + shrinkVertically(tween(PAIRING_FOLD_MS, easing = ease))) {
            PairingSteps(info, code)
        }
    }
}

/**
 * The QR's place while there is no link (2.15): a tile of the QR's size on the chip fill inside the chip border, the phone
 * mark on its disc in the middle. Decorative: the column beside it says why.
 */
@Composable private fun PairingQrPlaceholder() {
    val skin = Car.skin
    val shape = RoundedCornerShape(16.dp)
    Box(Modifier.size(QR_MIN_SIDE).clip(shape).background(skin.chipFill).border(1.dp, skin.chipBorder, shape).clearAndSetSemantics {},
        contentAlignment = Alignment.Center) {
        CarIconDisc(painterResource(R.drawable.ic_phone), size = 72.dp, iconSize = 36.dp)
    }
}

/**
 * 직접 입력하기 (2.15): a 56-dp toggle row in the ⓘ row's form ([CarInfoRow]), the edit mark, the words and an arrow that
 * turns as the steps unfold. TalkBack hears the row as a button with its state (접힘 / 펼침) and what a tap does.
 */
@Composable private fun PairingFoldRow(open: Boolean, onChange: (Boolean) -> Unit) {
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }
    val turn by animateFloatAsState(if (open) 180f else 0f, if (reduce) snap() else tween(PAIRING_FOLD_MS, easing = FastOutSlowInEasing), label = "pairing-arrow")
    // The outer semantics names the click (the toggle's own action carries no label) and says the state in words.
    Row(Modifier.fillMaxWidth().pressable(interaction, .98f).clip(RoundedCornerShape(12.dp))
        .semantics {
            onClick(label = if (open) "접기" else "펼치기") { onChange(!open); true }
            stateDescription = if (open) "펼침" else "접힘"
        }
        .toggleable(value = open, interactionSource = interaction, indication = null, role = Role.Button) { onChange(it) }
        .pressFill(interaction).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Edit, contentDescription = null, Modifier.size(20.dp), tint = Car.skin.icon)
        Spacer(Modifier.width(12.dp))
        Text(VehicleCopy.PHONE_MANUAL, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, Modifier.size(24.dp).graphicsLayer { rotationZ = turn }, tint = Scene.textSecondary)
    }
}

/**
 * The three steps a phone types by hand (2.13, behind 직접 입력하기 since 2.15), in the phone's order (address, code, key), each
 * led by its step disc ([CarStepDisc]) so the driver reads them off top to bottom as they are typed. ① The addresses, two
 * lines at most. ② 연결 번호, and under it the six digits as the phone shows them, or why there is no code; the countdown and
 * the pill stand beside the QR above. ③ The vehicle key, wrapping onto as many lines as it needs and never cut (a phone types
 * all 64 characters, and a 1.3× font in a narrow window takes three lines). TalkBack hears each step's number with what it
 * holds.
 *
 * 2.15 fix (§9.6): step ② shows the live [code] the caller read ([livePairingCode]), never one past its time. Where the steps
 * stand beside the QR ([reasonBeside]) the words next to the QR already say why there is no code, so step ② keeps the
 * digits' place with a muted [VehicleCopy.PHONE_CODE_NONE] (TalkBack hears the reason): the card does not change height
 * every time a code runs out.
 */
@Composable private fun PairingSteps(info: SyncInfo, code: String?, modifier: Modifier = Modifier, reasonBeside: Boolean = false) {
    val key = info.fingerprint.chunked(8).joinToString(" ")
    val addresses = if (info.addresses.isEmpty()) VehicleCopy.PHONE_ADDRESS_PENDING else info.addresses.joinToString("  ·  ") { "$it:${info.port}" }
    val reason = if (info.pairedPhones > 0) VehicleCopy.PHONE_ANOTHER else VehicleCopy.PHONE_EXPIRED
    val digits = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum")
    // Under a step's own row, the content starts where the step's words do: the 32-dp disc and the 12-dp gap after it.
    val under = 44.dp
    Column(modifier.fillMaxWidth()) {
        CarRow(addresses, line = VehicleCopy.PHONE_ADDRESS, lead = { CarStepDisc(1, done = false) }, titleMaxLines = 2,
            spoken = "1단계 ${VehicleCopy.SPOKEN_PHONE_ADDRESS}, $addresses")
        CarDivider(Modifier.padding(start = under))
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clearAndSetSemantics { contentDescription = "2단계 ${VehicleCopy.PHONE_CODE}" },
            verticalAlignment = Alignment.CenterVertically) {
            CarStepDisc(2, done = false)
            Spacer(Modifier.width(12.dp))
            Text(VehicleCopy.PHONE_CODE, style = MaterialTheme.typography.titleSmall, color = Scene.text, maxLines = 1)
        }
        Column(Modifier.fillMaxWidth().padding(start = under, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (code != null) {
                Text(pairingDisplay(code), style = digits, fontWeight = FontWeight.Medium, color = Scene.text,
                    modifier = Modifier.semantics { contentDescription = "연결 번호 " + code.toCharArray().joinToString(" ") })
                CarLine(VehicleCopy.PHONE_HOW)
            } else if (reasonBeside) {
                Text(VehicleCopy.PHONE_CODE_NONE, style = digits, fontWeight = FontWeight.Medium, color = Scene.textMuted, maxLines = 1,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = reason })
                CarLine(VehicleCopy.PHONE_HOW)
            } else Text(reason, style = MaterialTheme.typography.titleSmall, color = Scene.text)
        }
        CarDivider(Modifier.padding(start = under))
        CarRow(key, line = VehicleCopy.PHONE_KEY, lead = { CarStepDisc(3, done = false) }, titleMaxLines = Int.MAX_VALUE,
            spoken = "3단계 ${VehicleCopy.SPOKEN_PHONE_KEY}, $key")
    }
}

/**
 * Which card payments the car takes, as the saved [cards] stand (2.12, pure, unit-tested): with none registered only those it
 * recognises as fuel, charging, tolls or parking; with some, only theirs ("등록한 카드 2장의 결제만 받아요").
 */
internal fun cardRegistryLine(cards: List<String>): String =
    if (cards.isEmpty()) "카드 미등록 · 주유·충전·통행료·주차 결제만 받아요" else "등록한 카드 ${cards.size}장의 결제만 받아요"

/**
 * 설정 › 기록's 자동 완성 card (2.12): which cards count as the company's. One line says what the saved cards mean
 * ([cardRegistryLine]); TalkBack hears the whole of it instead ([SettingsCopy.SPOKEN_CARDS]). Then the field, its error and
 * 카드 저장, the card's one solid action. The switch itself is 기록 및 개인정보's.
 */
@Composable internal fun AutopilotSettings(saved: FleetProfile, act: Act, repository: TripLogRepository, field: @Composable (String, (String) -> Unit) -> Unit) {
    var cards by rememberSaveable(saved.cards) { mutableStateOf(saved.cards.joinToString(", ")) }
    val parsed = cards.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val valid = parsed.size <= 10 && parsed.all { it.matches(Regex("[0-9]{4}")) }
    CarCard(title = { CarSectionTitle(SettingsCopy.AUTOFILL_TITLE, icon = painterResource(R.drawable.ic_autofill)) }) {
        CarLine(cardRegistryLine(saved.cards), spoken = SettingsCopy.SPOKEN_CARDS)
        field(cards) { value -> if (value.length <= 60 && value.all { it.isDigit() || it == ',' || it == ' ' }) cards = value }
        if (!valid) Text(SettingsCopy.CARDS_ERROR, color = Scene.negative, style = MaterialTheme.typography.bodyMedium)
        PrimaryAction("카드 저장", Modifier.fillMaxWidth(), enabled = valid && parsed != saved.cards) { act { repository.updateProfile(saved.copy(cards = parsed)) } }
    }
}
