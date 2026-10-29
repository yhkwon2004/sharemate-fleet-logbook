package ai.sharemate.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

internal data class DrivingOverviewInfo(
    val todayKm: Double?, val currentKm: Double?, val status: String,
    val fresh: Boolean, val demo: Boolean,
)

internal fun drivingOverviewInfo(state: TripLogState, now: Long, restrictionsAvailable: Boolean, zone: ZoneId): DrivingOverviewInfo {
    val data = state.data
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val live = state.live?.takeIf { it.demo == demo }
    val expectedSignal = if (demo) state.signal == SignalStatus.DEMO else state.signal in setOf(SignalStatus.READY, SignalStatus.RECORDING)
    val age = live?.let { now - it.at } ?: Long.MAX_VALUE
    val fresh = expectedSignal && live != null && driveSignalsFresh(live.gear, live.odometerKm, age, age)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val completed = tripsIn(data.trips.filter { it.demo == demo && (it.endAt ?: Long.MAX_VALUE) <= now }, today, today, zone)
    val known = state.loaded && !state.loadFailed
    val todayKnown = known && (fresh || completed.isNotEmpty() || open != null)
    // Odometer history has no midnight boundary sample; assign the whole trip to its departure date.
    val openToday = open?.takeIf { Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == today }
    val distance = if (todayKnown) completed.sumOf { it.distanceKm } + (openToday?.distanceKm ?: 0.0) else null
    val status = when {
        !state.loaded -> "기록 불러오는 중"
        state.loadFailed || state.error != null -> "기록 저장 확인 필요"
        !restrictionsAvailable -> "주행 상태 확인 중"
        state.signal == SignalStatus.DENIED -> "차량 정보 권한 필요"
        state.signal == SignalStatus.UNSUPPORTED -> "차량 데이터 미지원"
        state.signal == SignalStatus.ERROR -> "차량 연결 확인 필요"
        !fresh -> if (live == null) "차량 신호 수신 대기" else "거리 갱신 확인 필요"
        demo -> if (open != null) "시연 기록 중" else "시연 기록 대기"
        open != null -> "운행 기록 중"
        else -> "자동 기록 대기"
    }
    return DrivingOverviewInfo(distance, open?.distanceKm?.takeIf { known }, status,
        fresh && known && state.error == null, demo)
}

/** Restricted-state content: no input, navigation, scrolling, animation, or actionable semantics. */
@Composable
internal fun DrivingOverview(state: TripLogState, now: Long, restrictionsAvailable: Boolean, modifier: Modifier = Modifier) {
    val info = drivingOverviewInfo(state, now, restrictionsAvailable, ZoneId.systemDefault())
    val colors = MaterialTheme.colorScheme
    fun distance(value: Double?) = value?.let { String.format(Locale.KOREA, "%,.1f km", it) } ?: "—"
    Surface(modifier.fillMaxSize(), color = colors.background) {
        BoxWithConstraints(Modifier.fillMaxSize().padding(24.dp)) {
            val horizontal = maxWidth >= 600.dp
            val short = maxHeight < 340.dp
            val metric: @Composable (String, String, Modifier) -> Unit = { label, value, place ->
                Column(place, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(label, style = MaterialTheme.typography.titleMedium, color = colors.onSurfaceVariant)
                    Text(value, style = (if (short) MaterialTheme.typography.displaySmall else MaterialTheme.typography.displayMedium).copy(fontFeatureSettings = "tnum"),
                        fontWeight = FontWeight.Medium)
                }
            }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(if (short) 12.dp else 24.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (!short) Eyebrow(if (info.demo) "SHAREMATE · 시연 데이터" else "SHAREMATE · 자동 운행일지")
                Text(info.status, style = MaterialTheme.typography.titleLarge, color = colors.onSurface)
                if (horizontal) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    metric("오늘 기록 거리", distance(info.todayKm), Modifier.weight(1f))
                    metric("이번 운행", distance(info.currentKm), Modifier.weight(1f))
                } else Column(verticalArrangement = Arrangement.spacedBy(if (short) 12.dp else 24.dp)) {
                    metric("오늘 기록 거리", distance(info.todayKm), Modifier)
                    metric("이번 운행", distance(info.currentKm), Modifier)
                }
                if (!short) Text("출발일 기준 · ${if (info.demo) "시연값" else if (info.fresh) "차량 수신값" else "마지막 기록값"}",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                // Nothing to act on while moving; this only tells the driver whether the car will ask for anything once parked.
                val pending = state.data.trips.count { it.demo == info.demo && it.purpose == TripPurpose.UNCLASSIFIED }
                val incidents = openIncidents(state.data).size
                val later = listOfNotNull(pending.takeIf { it > 0 }?.let { "목적 확인 ${it}건" }, incidents.takeIf { it > 0 }?.let { "특이사항 대응 중 ${it}건" })
                if (!short) Text(if (later.isEmpty()) "정차 후 할 일 없음" else "정차 후 · " + later.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
    }
}
