package ai.sharemate.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.heading
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
 * Spec B (차량): car card 720 + info column 456 with a 420-tall drawing at EXPANDED and FULL, and 2 × 588 under them;
 * one column with a 260 drawing at MEDIUM and below; a SHORT window drops the drawing and keeps the plate and the odometer.
 * [paneWidth] is the pane's own width: the shell's tokens come from the window, and a pane narrower than 1000 dp cannot
 * hold two columns whatever the class says.
 */
internal fun vehiclePaneLayout(tokens: LayoutTokens, paneWidth: Dp): VehiclePaneLayout {
    val twoColumn = tokens.expanded && paneWidth >= 1000.dp
    val drawing = when { tokens.short -> 0.dp; twoColumn -> 420.dp; else -> 260.dp }
    return VehiclePaneLayout(twoColumn, drawing, twoUp = twoColumn)
}

/** The vehicle as the app knows it: live signals, identity card, care deadlines and maintenance, the phone link, and what happened to the car. */
@Composable
internal fun VehiclePane(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, goSettings: (() -> Unit)? = null) {
    val tokens = LocalLayout.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = vehiclePaneLayout(tokens, maxWidth)
        val gap = tokens.cardGap
        val fresh = vehicleSampleFresh(state, now)
        val link: @Composable ColumnScope.() -> Unit = {
            PhoneSyncCard(state, now)
            if (state.data.profile.phoneSync && state.sync.running) PairingCard(state.sync, now, compact = !layout.twoColumn)
        }
        // Start-anchored: the column keeps to the content maximum (1200 at FULL) and the sky keeps the rest of the window.
        Column(Modifier.contentWidth().align(Alignment.TopStart).verticalScroll(rememberScrollState()).padding(bottom = tokens.sectionGap),
            verticalArrangement = Arrangement.spacedBy(gap)) {
            if (layout.twoColumn) {
                // The car card sets the height (its drawing is fixed); the info column fills to match with its content at the
                // top, and both cards share one padding so their eyebrows sit on one line.
                val roomy = !tokens.short
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    VehicleHero(state, fresh, layout.drawingHeight, Modifier.weight(720f).fillMaxHeight(), padding = vehicleCardPadding(tokens, roomy))
                    VehicleDetails(state, fresh, now, roomy = roomy, Modifier.weight(456f).fillMaxHeight())
                }
            } else {
                VehicleHero(state, fresh, layout.drawingHeight, Modifier.fillMaxWidth())
                VehicleDetails(state, fresh, now, roomy = false, Modifier.fillMaxWidth())
            }
            if (layout.twoUp) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) { VehicleCareCard(state, now, act, repository, Modifier, goSettings); link() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                    CarReservationsCard(state = state, now = now, act = act, repository = repository)
                    IncidentHistory(state.data, act, repository)
                }
            } else {
                VehicleCareCard(state, now, act, repository, Modifier.fillMaxWidth(), goSettings)
                link()
                CarReservationsCard(state = state, now = now, act = act, repository = repository)
                IncidentHistory(state.data, act, repository)
            }
        }
    }
}

/**
 * The car card's surface (2.10): the scene's glass with a low-alpha wash of the accent from the top left, so it reads as
 * a bright key card by day and as the navy card of the night scene without a fixed palette. The tutorial's story panel
 * uses it too.
 */
@Composable internal fun KeyCard(modifier: Modifier = Modifier, padding: Dp = 24.dp, content: @Composable ColumnScope.() -> Unit) {
    val accent = Scene.accent
    val glow = Scene.glow
    // Hoisted out of the draw: the brush is built in composition from the scene in effect.
    val wash = Brush.linearGradient(listOf(accent.copy(alpha = .18f), Color.Transparent, glow.copy(alpha = .08f)))
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = Scene.glass, contentColor = Scene.text, border = Scene.edge) {
        Column(Modifier.fillMaxSize().background(wash).padding(padding), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

/** The inner padding of the car card and the info column beside it: the layout's panel padding, 20 dp when the window is not roomy. */
private fun vehicleCardPadding(tokens: LayoutTokens, roomy: Boolean): Dp = if (roomy) tokens.panelPadding else 20.dp

/** The key card: one headline (the plate), the odometer and the gear as the car reports them, the drawing, one supporting line. */
@Composable
private fun VehicleHero(state: TripLogState, fresh: Boolean, drawingHeight: Dp, modifier: Modifier = Modifier, padding: Dp = 24.dp) {
    val profile = state.data.profile
    val gear = if (!fresh) "확인 필요" else when (state.live?.gear) {
        GearState.PARK -> "P"
        GearState.MOVING -> "D · R · N"
        else -> "확인 필요"
    }
    val open = state.data.open?.takeIf { it.demo == profile.demoMode }
    val footer = if (profile.demoMode) "가상 신호 · 실제 차량 상태가 아닙니다" else if (!fresh) state.signal.label
        else if (open != null) "자동 기록 중 · ${String.format(Locale.KOREA, "%,.1f km", open.distanceKm)}" else "수신한 차량 정보만 표시합니다"
    KeyCard(modifier, padding = padding) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Eyebrow("MY FLEET", Scene.brand)
            Spacer(Modifier.weight(1f))
            Text(if (profile.demoMode) "시연 신호" else "차량 신호", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
        }
        Text(profile.plateNumber.ifBlank { "차량번호 미등록" }, style = MaterialTheme.typography.headlineSmall, color = Scene.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                Eyebrow("계기판 누적거리", Scene.textSecondary)
                // Live telemetry simply updates (spec C): no cross-fade, no count-up.
                Text(if (fresh) String.format(Locale.KOREA, "%,.1f km", state.live?.odometerKm) else "확인 필요",
                    style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End) {
                Eyebrow("수신 기어", Scene.textSecondary)
                Text(gear, style = MaterialTheme.typography.titleLarge, color = if (fresh) Scene.glow else Scene.textMuted)
            }
        }
        if (drawingHeight > 0.dp) Image(painterResource(R.drawable.sharemate_silver_car), "연출용 차량 이미지 · 실제 차종과 다를 수 있음",
            Modifier.fillMaxWidth().height(drawingHeight), contentScale = ContentScale.Fit)
        Text(footer, style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun VehicleDetails(state: TripLogState, fresh: Boolean, now: Long, roomy: Boolean, modifier: Modifier = Modifier) {
    val profile = state.data.profile
    val driver = state.data.drivers.firstOrNull { it.id == state.data.currentDriverId }
    val latest = state.data.trips.filter { !it.open && it.demo == profile.demoMode }.maxByOrNull { it.startAt }
    val formatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(formatter)
    val update = state.live?.takeIf { it.demo == profile.demoMode && it.at in 0L..now }?.let {
        if (fresh) "${((now - it.at) / 1000).coerceAtLeast(0)}초 전" else "${(now - it.at) / 60_000}분 전"
    } ?: "수신 전"
    val sdkModel = state.vehicleModel?.takeIf { !profile.demoMode && it.isNotBlank() }
    val tokens = LocalLayout.current
    Panel(modifier, padding = vehicleCardPadding(tokens, roomy)) {
        // Top-aligned: stretched to the car card's height, the column keeps its eyebrow level with the car card's and leaves the
        // spare height at the bottom.
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (roomy) 28.dp else 20.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("차량 정보", Scene.brand)
                    Text(if (profile.demoMode) "시연 데이터" else "Pleos SDK · AAOS", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                }
                VehicleInfoRow("현재 직원", driver?.label ?: "직원 미지정")
                VehicleInfoRow(if (sdkModel != null) "차량 수신 차종" else "등록 차종", sdkModel ?: state.data.vehicle.model ?: profile.carModel.ifBlank { "미등록" })
                validVin(state.data.vehicle.vin)?.let { VehicleInfoRow("차대번호(VIN)", it) }
                val energy = state.live?.takeIf { it.demo == profile.demoMode && now - it.at in 0..45_000 }
                VehicleInfoRow("배터리", energy?.socPercent?.let { "$it%" + if (energy.charging == true) " · 충전 중" else "" } ?: "현재 확인 불가")
                if (energy?.socPercent == null && !profile.demoMode) state.data.vehicle.socPercent?.let {
                    VehicleInfoRow("마지막 저장 잔량", "$it% · 현재 상태와 다를 수 있음")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // 수신 중 is the scene's positive (AA on day glass); a stale link is its negative.
                Stat("데이터 연결", if (fresh) "수신 중" else "확인 필요", Modifier.weight(1f), valueColor = if (fresh) Scene.positive else Scene.negative, large = roomy)
                Stat(if (fresh) "최근 수신" else "이전 수신", update, Modifier.weight(1f), large = roomy)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(if (latest?.recovered == true) "최근 운행 · 종료시각 확인" else "최근 완료 운행")
                if (latest == null) {
                    Text("완료된 운행이 아직 없어요", style = MaterialTheme.typography.titleSmall)
                    Text("출발하면 자동으로 기록을 시작해요.", style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(time(latest.endAt ?: latest.updatedAt), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(Locale.KOREA, "%,.1f km", latest.distanceKm), style = MaterialTheme.typography.titleLarge)
                    }
                    Text("${latest.purpose.label} · ${state.data.drivers.firstOrNull { it.id == latest.driverId }?.name ?: "직원 미지정"}",
                        style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun VehicleInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, color = Scene.text, textAlign = TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
