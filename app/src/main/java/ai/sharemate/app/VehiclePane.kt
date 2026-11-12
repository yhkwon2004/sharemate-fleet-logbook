package ai.sharemate.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal fun vehicleSampleFresh(state: TripLogState, now: Long): Boolean {
    val sample = state.live ?: return false
    if (sample.demo != state.data.profile.demoMode || now < 0 || sample.at !in 0L..now ||
        state.signal !in setOf(SignalStatus.READY, SignalStatus.RECORDING, SignalStatus.DEMO)) return false
    return driveSignalsFresh(sample.gear, sample.odometerKm, now - sample.at, now - sample.at)
}

// ───────────────────────── layout (pure, unit-tested) ─────────────────────────

/** How the 차량 pane arranges itself: the car card beside the info column or over it, the drawing's height (0 = hidden), and the cards under them two up or stacked. */
@Immutable
internal data class VehiclePaneLayout(val twoColumn: Boolean, val drawingHeight: Dp, val twoUp: Boolean)

/**
 * Spec B (차량): the car card beside the info column (720 : 456, so 666 + 422 in the 1112-dp page at FULL), and the cards under
 * them two up (2 × 544); one column otherwise; a SHORT window drops the drawing and keeps the plate and the odometer. The
 * drawing is 260 dp tall in both arrangements (2.13): the bitmap (1942 × 809) draws 258 dp tall in the 666-dp card, so 2.12's
 * 420-dp box left 160 dp of air above and below it and 차량 정보 half empty; now the row is about 463 dp and the cards under
 * it rise by 160. [paneWidth] is the pane's own width: the shell's tokens come from the window, and a pane with less than
 * 1000 dp of effective width (its width ÷ [fontScale]) cannot hold two columns whatever the class says, so a 1.3× font and
 * the 832-dp page of a 1400-dp window stack.
 */
internal fun vehiclePaneLayout(tokens: LayoutTokens, paneWidth: Dp, fontScale: Float = 1f): VehiclePaneLayout {
    val twoColumn = tokens.expanded && paneWidth / fontScale.coerceAtLeast(1f) >= 1000.dp
    val drawing = if (tokens.short) 0.dp else VEHICLE_DRAWING_HEIGHT
    return VehiclePaneLayout(twoColumn, drawing, twoUp = twoColumn)
}

/** The car drawing's height on 차량 wherever it shows (2.13): the bitmap's own height at the card's width, rounded up. */
internal val VEHICLE_DRAWING_HEIGHT = 260.dp

/** What the car card says about the signal ([vehicleHeroCopy]): the status tag and its tone, the gear and the ⓘ footer. */
@Immutable
internal data class VehicleHeroCopy(val tag: String, val tone: PillTone, val gear: String, val footer: String)

/**
 * The car card's words (2.12, pure, unit-tested). One status tag says what the signal is doing: demo data first (시연 신호),
 * then a link still starting (연결 중), a signal that cannot record (its own label, such as 차량 정보 권한 필요), a fresh sample
 * (수신 중) and otherwise a stale one (확인 필요). The gear shows only from a fresh sample. The footer is one ⓘ line: what the
 * card shows, or that a demo signal is not the car's; the recording and stale footers of 2.11 are gone, since the header's
 * status and this tag already say them.
 */
internal fun vehicleHeroCopy(demo: Boolean, fresh: Boolean, signal: SignalStatus, gear: GearState?): VehicleHeroCopy {
    val (tag, tone) = when {
        demo -> "시연 신호" to PillTone.DEMO
        signal == SignalStatus.STARTING -> "연결 중" to PillTone.QUIET
        signal in setOf(SignalStatus.DENIED, SignalStatus.UNSUPPORTED, SignalStatus.ERROR) -> signal.label to PillTone.NEGATIVE
        fresh -> "수신 중" to PillTone.POSITIVE
        else -> VehicleCopy.CHECK to PillTone.NEGATIVE
    }
    val shown = if (!fresh) VehicleCopy.CHECK else when (gear) {
        GearState.PARK -> "P"
        GearState.MOVING -> "D · R · N"
        else -> VehicleCopy.CHECK
    }
    return VehicleHeroCopy(tag, tone, shown, if (demo) VehicleCopy.FOOTER_DEMO else VehicleCopy.FOOTER)
}

/**
 * The vehicle as the app knows it (2.12): the car card beside 차량 정보, then the deadlines (차량 관리), the maintenance records
 * (정비 기록) and the phone link with its pairing (휴대폰 연결) in the left column, the bookings and what happened to the car in
 * the right one; one column in the same order where the page is narrow ([vehiclePaneLayout]). 휴대폰 연결 carries its own
 * switch while the link is off (2.13), so it gets [act] and [repository] too.
 */
@Composable
internal fun VehiclePane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, goSettings: (() -> Unit)? = null) {
    val tokens = LocalLayout.current
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = vehiclePaneLayout(tokens, maxWidth, fontScale)
        val gap = tokens.cardGap
        val fresh = vehicleSampleFresh(state, now)
        val upkeep: @Composable ColumnScope.() -> Unit = {
            VehicleCareCard(state, now, Modifier.fillMaxWidth(), goSettings)
            MaintenanceCard(state, now, act, repository, Modifier.fillMaxWidth())
            PhoneSyncCard(state, now, act, repository, Modifier.fillMaxWidth())
        }
        val record: @Composable ColumnScope.() -> Unit = {
            CarReservationsCard(state = state, now = now, act = act, repository = repository)
            IncidentHistory(state.data, act, repository)
        }
        // The pane fills the page column: the whole rail, or what the 지금 column beside it leaves.
        val scroll = rememberScrollState()
        Column(Modifier.contentWidth().align(Alignment.TopStart).fadingTopEdge(scroll).verticalScroll(scroll).padding(bottom = tokens.sectionGap),
            verticalArrangement = Arrangement.spacedBy(gap)) {
            // A short window keeps the cards' padding and their blocks tight, so the plate and the odometer stay in view.
            val roomy = !tokens.short
            val padding = if (roomy) carCardPadding() else 20.dp
            if (layout.twoColumn) {
                // The taller card sets the row's height (as a rule the car card, its drawing fixed at 260); the other fills to
                // match, and both cards share one padding so their titles sit on one line.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    VehicleHero(state, fresh, layout.drawingHeight, Modifier.weight(720f).fillMaxHeight(), padding = padding)
                    VehicleDetails(state, fresh, now, roomy = roomy, Modifier.weight(456f).fillMaxHeight(), padding = padding)
                }
            } else {
                VehicleHero(state, fresh, layout.drawingHeight, Modifier.fillMaxWidth(), padding = padding)
                VehicleDetails(state, fresh, now, roomy = false, Modifier.fillMaxWidth(), padding = padding)
            }
            if (layout.twoUp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap), content = upkeep)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap), content = record)
            } else {
                upkeep()
                record()
            }
        }
    }
}

/**
 * The car card (2.12): the plate as the card's title with one status tag beside it ([vehicleHeroCopy]), the odometer and the
 * gear as the car reports them, the drawing, and one ⓘ line. The odometer and the gear update in place (spec C), and each
 * reads as one node with its full name (계기판 누적거리, 수신 기어).
 */
@Composable
private fun VehicleHero(state: TripLogState, fresh: Boolean, drawingHeight: Dp, modifier: Modifier = Modifier, padding: Dp = carCardPadding()) {
    val profile = state.data.profile
    val copy = vehicleHeroCopy(profile.demoMode, fresh, state.signal, state.live?.gear)
    val odometer = if (fresh) String.format(Locale.KOREA, "%,.1f km", state.live?.odometerKm) else VehicleCopy.CHECK
    CarCard(modifier, padding = padding, title = {
        CarSectionTitle(profile.plateNumber.ifBlank { VehicleCopy.NO_PLATE }, icon = painterResource(R.drawable.ic_vehicle), trailing = {
            CarTag(copy.tag, tone = copy.tone, description = "차량 신호 ${copy.tag}")
        })
    }) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f).clearAndSetSemantics { contentDescription = "${VehicleCopy.SPOKEN_ODOMETER} $odometer" }) {
                Text(VehicleCopy.ODOMETER, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                // Live telemetry simply updates (spec C): no cross-fade, no count-up.
                Text(odometer, style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.clearAndSetSemantics { contentDescription = "${VehicleCopy.SPOKEN_GEAR} ${copy.gear}" }, horizontalAlignment = Alignment.End) {
                Text(VehicleCopy.GEAR, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                // Gear P is a status in glow (10.32 / 6.14 on the card), a value the car cannot vouch for is muted.
                Text(copy.gear, style = MaterialTheme.typography.titleLarge, color = if (fresh) Scene.glow else Scene.textMuted)
            }
        }
        if (drawingHeight > 0.dp) Image(painterResource(R.drawable.sharemate_silver_car), "연출용 차량 이미지 · 실제 차종과 다를 수 있음",
            Modifier.fillMaxWidth().height(drawingHeight), contentScale = ContentScale.Fit)
        CarInfoRow(copy.footer)
    }
}

/**
 * 차량 정보 (2.12): what the car reports about itself, as label · value rows; the source as a tag beside the title. Who is
 * driving is no longer here: the 사용자 card or the header chip says it on every layout. Then the latest completed trip.
 * 2.13: stretched to the car card's height, the facts keep to the top and the latest trip, under a hairline, to the bottom
 * (the space between them goes between the two blocks, never under the last line), at least [roomy] 24 dp or 16 apart; the
 * gaps inside sit on the 8-12-16-24 scale. A trip without its person reads 사용자 미지정, the one word for it.
 */
@Composable
private fun VehicleDetails(state: TripLogState, fresh: Boolean, now: Long, roomy: Boolean, modifier: Modifier = Modifier, padding: Dp = carCardPadding()) {
    val profile = state.data.profile
    val latest = state.data.trips.filter { !it.open && it.demo == profile.demoMode }.maxByOrNull { it.startAt }
    val formatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(formatter)
    val update = state.live?.takeIf { it.demo == profile.demoMode && it.at in 0L..now }?.let {
        if (fresh) "${((now - it.at) / 1000).coerceAtLeast(0)}초 전" else "${(now - it.at) / 60_000}분 전"
    } ?: "수신 전"
    val sdkModel = state.vehicleModel?.takeIf { !profile.demoMode && it.isNotBlank() }
    CarCard(modifier, padding = padding, title = {
        CarSectionTitle(VehicleCopy.DETAILS_TITLE, icon = rememberVectorPainter(Icons.Outlined.Info), trailing = {
            CarTag(if (profile.demoMode) VehicleCopy.SOURCE_DEMO else VehicleCopy.SOURCE)
        })
    }) {
        val gap = if (roomy) 24.dp else 16.dp
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceBetween) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                VehicleInfoRow(if (sdkModel != null) "차량 수신 차종" else "등록 차종", sdkModel ?: state.data.vehicle.model ?: profile.carModel.ifBlank { "미등록" })
                validVin(state.data.vehicle.vin)?.let { VehicleInfoRow(VehicleCopy.VIN, it) }
                val energy = state.live?.takeIf { it.demo == profile.demoMode && now - it.at in 0..45_000 }
                VehicleInfoRow("배터리", energy?.socPercent?.let { "$it%" + if (energy.charging == true) " · 충전 중" else "" } ?: VehicleCopy.BATTERY_UNKNOWN)
                if (energy?.socPercent == null && !profile.demoMode) state.data.vehicle.socPercent?.let {
                    VehicleInfoRow("마지막 저장 잔량", "$it%", spoken = "마지막 저장 잔량 $it%, ${VehicleCopy.SPOKEN_STORED_CHARGE}")
                }
                // Seconds ago while the link is fresh, minutes since the last sample when it is not; updated in place.
                VehicleInfoRow(if (fresh) "최근 수신" else "이전 수신", update)
            }
            // The gap over the hairline holds where the card is not stretched (one column), where SpaceBetween adds nothing.
            Column(Modifier.padding(top = gap), verticalArrangement = Arrangement.spacedBy(gap)) {
                CarDivider()
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A label over values inside the card: grey, the card's own title says what the card is.
                    Eyebrow(if (latest?.recovered == true) "최근 운행 · 종료시각 확인" else "최근 완료 운행", Scene.textSecondary)
                    if (latest == null) Text(VehicleCopy.NO_TRIP_YET, style = MaterialTheme.typography.titleSmall)
                    else {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(time(latest.endAt ?: latest.updatedAt), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(String.format(Locale.KOREA, "%,.1f km", latest.distanceKm), style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
                        }
                        Text("${latest.purpose.label} · ${state.data.drivers.firstOrNull { it.id == latest.driverId }?.name ?: IssueType.NO_DRIVER.label}",
                            style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * One fact of 차량 정보: the [label] in grey at the start, the [value] at the end on one line. One node for TalkBack, which reads
 * [spoken] instead where the value needs a caveat the row leaves out (the last stored charge may be old).
 */
@Composable
private fun VehicleInfoRow(label: String, value: String, spoken: String? = null) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { spoken?.let { contentDescription = it } },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Scene.text, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
