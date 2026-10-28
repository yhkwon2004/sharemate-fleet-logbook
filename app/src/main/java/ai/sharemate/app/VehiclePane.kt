package ai.sharemate.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
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

/** A read-only view of the signals already used by automatic recording. */
@Composable
internal fun VehiclePane(state: TripLogState, now: Long) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 960.dp
        val scroll = maxHeight < 740.dp || LocalDensity.current.fontScale > 1.25f
        val height = if (scroll) 800.dp * LocalDensity.current.fontScale.coerceAtLeast(1f) else maxHeight
        val fresh = vehicleSampleFresh(state, now)
        val hero: @Composable (Modifier) -> Unit = { VehicleHero(state, fresh, it) }
        val details: @Composable (Modifier) -> Unit = { VehicleDetails(state, fresh, now, it) }
        if (wide) {
            Row(Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .height(height), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                hero(Modifier.weight(1.15f).fillMaxHeight())
                details(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize().then(if (scroll) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                hero(Modifier.fillMaxWidth().height((height * .48f).coerceIn(300.dp, 430.dp)))
                details(Modifier.fillMaxWidth().height(height - (height * .48f).coerceIn(300.dp, 430.dp) - 16.dp))
            }
        }
    }
}

@Composable
private fun VehicleHero(state: TripLogState, fresh: Boolean, modifier: Modifier) {
    val profile = state.data.profile
    val gear = if (!fresh) "확인 필요" else when (state.live?.gear) {
        GearState.PARK -> "P"
        GearState.MOVING -> "D · R · N"
        else -> "확인 필요"
    }
    SharemateTheme(dark = true) {
        Surface(modifier, shape = RoundedCornerShape(24.dp), color = Color(0xFF17181B), contentColor = Color.White) {
            Column(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF302B2A), Color(0xFF17181B))))
                .padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Eyebrow("MY FLEET", Color(0xFFE4BFA6))
                    Spacer(Modifier.weight(1f))
                    Text(if (profile.demoMode) "시연 신호" else "차량 신호", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC7C9CE))
                }
                Text(profile.plateNumber.ifBlank { "차량번호 미등록" }, style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("계기판 누적거리")
                        Text(if (fresh) String.format(Locale.KOREA, "%,.1f km", state.live?.odometerKm) else "확인 필요",
                            style = MaterialTheme.typography.headlineLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Eyebrow("수신 기어")
                        Text(gear, style = MaterialTheme.typography.titleLarge, color = if (fresh) Color(0xFFE4BFA6) else Color(0xFFC7C9CE))
                    }
                }
                Image(painterResource(R.drawable.sharemate_silver_car), "연출용 차량 이미지 · 실제 차종과 다를 수 있음",
                    Modifier.fillMaxWidth().weight(1f), contentScale = ContentScale.Fit)
                val open = state.data.open?.takeIf { it.demo == profile.demoMode }
                Text(if (profile.demoMode) "가상 신호 · 실제 차량 상태가 아닙니다" else if (!fresh) state.signal.label
                    else if (open != null) "자동 기록 중 · ${String.format(Locale.KOREA, "%,.1f km", open.distanceKm)}" else "수신한 차량 정보만 표시합니다",
                    style = MaterialTheme.typography.bodySmall, color = Color(0xFFC7C9CE), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun VehicleDetails(state: TripLogState, fresh: Boolean, now: Long, modifier: Modifier) {
    val profile = state.data.profile
    val driver = state.data.drivers.firstOrNull { it.id == state.data.currentDriverId }
    val latest = state.data.trips.filter { !it.open && it.demo == profile.demoMode }.maxByOrNull { it.startAt }
    val formatter = DateTimeFormatter.ofPattern("M월 d일 HH:mm")
    fun time(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(formatter)
    val update = state.live?.takeIf { it.demo == profile.demoMode && it.at in 0L..now }?.let {
        if (fresh) "${((now - it.at) / 1000).coerceAtLeast(0)}초 전" else "${(now - it.at) / 60_000}분 전"
    } ?: "수신 전"
    val sdkModel = state.vehicleModel?.takeIf { !profile.demoMode && it.isNotBlank() }
    BoxWithConstraints(modifier) {
        val roomy = maxHeight >= 600.dp && maxWidth >= 480.dp
        Panel(Modifier.fillMaxSize(), padding = if (roomy) 28.dp else 20.dp) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (roomy) 32.dp else 20.dp, Alignment.CenterVertically)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Eyebrow("차량 정보")
                    Text(if (profile.demoMode) "시연 데이터" else "Pleos SDK · AAOS", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                VehicleInfoRow("현재 직원", driver?.label ?: "직원 미지정")
                VehicleInfoRow(if (sdkModel != null) "SDK 차종" else "등록 차종", sdkModel ?: profile.carModel.ifBlank { "미등록" })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Stat("데이터 연결", if (fresh) "수신 중" else "확인 필요", Modifier.weight(1f),
                    valueColor = if (fresh) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.error, large = roomy)
                Stat(if (fresh) "최근 수신" else "이전 수신", update, Modifier.weight(1f), large = roomy)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Eyebrow(if (latest?.recovered == true) "최근 운행 · 종료시각 확인" else "최근 완료 운행")
                if (latest == null) {
                    Text("완료된 운행이 아직 없어요", style = MaterialTheme.typography.titleSmall)
                    Text("출발하면 자동으로 기록을 시작해요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(time(latest.endAt ?: latest.updatedAt), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(String.format(Locale.KOREA, "%,.1f km", latest.distanceKm), style = MaterialTheme.typography.titleLarge)
                    }
                    Text("${latest.purpose.label} · ${state.data.drivers.firstOrNull { it.id == latest.driverId }?.name ?: "직원 미지정"}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
    }
}

@Composable
private fun VehicleInfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, textAlign = androidx.compose.ui.text.style.TextAlign.End,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
