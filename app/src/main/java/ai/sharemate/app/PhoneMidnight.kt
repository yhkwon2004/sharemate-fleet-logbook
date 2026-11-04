package ai.sharemate.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

/*
 * The phone in the car's night look (2.8): the same Midnight palette, the night city behind the home tab, a greeting,
 * the three destinations the car home has (차량 정보 · 운행일지 확인 · 정보 내보내기), today and this month as widgets,
 * and a floating glass tab bar. The phone still only shows and confirms what the car recorded; the car stays the original.
 */

// ───────────────────────── pure copy (unit-tested) ─────────────────────────

@Immutable
internal data class PhoneCardLines(val vehicle: String, val log: String, val logPending: Int, val export: String)

/** Short lines for the three phone cards, from the phone's copy (current mode only), so they fit a third of a phone width. */
internal fun phoneCardLines(data: TripLogData, today: LocalDate, zone: ZoneId): PhoneCardLines {
    val mirror = data.copy(trips = phoneTrips(data))
    val care = careDueAlerts(data.care, data.vehicle.odometerKm, today, emptySet()).firstOrNull()?.title
    val vehicle = care ?: data.vehicle.socPercent?.let { "배터리 $it%" } ?: data.vehicle.odometerKm?.takeIf { it > 0 }?.let { "마지막 ${kmText(it)}" } ?: "차량 상태"
    val month = YearMonth.from(today)
    val review = logReview(mirror, month, zone)
    val log = when {
        review.trips == 0 -> "${month.monthValue}월 운행 없음"
        review.issues > 0 -> "확인 ${review.issues}건 남음"
        else -> "${review.trips}건 · 모두 확인"
    }
    val target = monthCloseTarget(mirror, today, zone)
    val export = if (target != null && target.month != month) "${monthLabel(target.month, today)} 마감 전 Excel" else "${month.monthValue}월 Excel"
    return PhoneCardLines(vehicle, log, review.issues, export)
}

/** The line under the phone greeting: the live link, or what the copy is when there is no car to ask. */
internal fun phoneSubtitle(paired: Boolean, connected: Boolean, open: Trip?): String = when {
    open != null -> "지금 운행 중 · ${hm(open.startAt)} 출발 · ${kmText(open.distanceKm)}"
    paired && connected -> "차량 기록을 실시간으로 받고 있어요"
    paired -> "연결 대기 · 마지막으로 받은 기록을 보여 줘요"
    else -> "파일 사본을 보고 있어요 · 설정에서 차량과 연결할 수 있어요"
}

/** Today on the phone copy: finished trips that started today, plus the open trip when it started today; and their expenses. */
internal data class PhoneToday(val km: Double, val trips: Int, val expenseWon: Long)

internal fun phoneToday(data: TripLogData, today: LocalDate, zone: ZoneId): PhoneToday {
    val trips = tripsIn(phoneTrips(data), today, today, zone)
    val open = data.open?.takeIf { it.demo == data.profile.demoMode && Instant.ofEpochMilli(it.startAt).atZone(zone).toLocalDate() == today }
    return PhoneToday(trips.sumOf { it.distanceKm } + (open?.distanceKm ?: 0.0), trips.size, trips.sumOf { it.expenseWon })
}

// ───────────────────────── home hero ─────────────────────────

/**
 * The phone home's top: clock and link state, the brand line, the greeting and the three cards. Drawn over the night
 * backdrop the shell lays behind the home tab.
 */
@Composable internal fun PhoneHomeHero(data: TripLogData, link: PhoneLink, paired: Boolean, now: Long, onVehicle: () -> Unit, onLog: (Int) -> Unit, onExport: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val lines = remember(data, today) { phoneCardLines(data, today, zone) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The phone status bar already shows the time and the header the brand, so the hero opens with the date in the brand tone.
        Text("${dayLabel(today)}  ·  FLEET JOURNAL", style = MaterialTheme.typography.labelLarge, color = Midnight.brand, letterSpacing = 1.sp,
            modifier = Modifier.clearAndSetSemantics {})
        Text(homeGreeting(Instant.ofEpochMilli(now).atZone(zone).hour, driver?.name, open != null), style = MaterialTheme.typography.headlineMedium,
            color = Midnight.text, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        Text(phoneSubtitle(paired, link.connected, open), style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PhoneActionCard("차량 정보", lines.vehicle, R.drawable.ic_vehicle, openIncidents(data).size, Modifier.weight(1f), onVehicle)
            PhoneActionCard("운행일지 확인", lines.log, R.drawable.ic_journal, lines.logPending, Modifier.weight(1f)) { onLog(if (lines.logPending > 0) 1 else 0) }
            PhoneActionCard("정보 내보내기", lines.export, R.drawable.ic_export, 0, Modifier.weight(1f), onExport)
        }
    }
}

/** A third-width glass card: lit icon tile, two-line title, one-line real number. At least 120 dp tall. */
@Composable private fun PhoneActionCard(title: String, subtitle: String, icon: Int, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    Surface(onClick = onClick, shape = shape, color = Midnight.glass.copy(alpha = .84f), border = Midnight.edge,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$title, $subtitle" + if (badge > 0) ", 확인 필요 ${badge}건" else ""; role = Role.Button
        }) {
        Column(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent))).heightIn(min = 128.dp)
            .padding(horizontal = 10.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                val tile = RoundedCornerShape(14.dp)
                Box(Modifier.size(64.dp).drawBehind {
                    drawCircle(Brush.radialGradient(listOf(Midnight.glow.copy(alpha = .28f), Color.Transparent), center = center, radius = size.minDimension / 2), radius = size.minDimension / 2)
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(46.dp).clip(tile).background(Brush.linearGradient(listOf(Color(0x803B6FFF), Color(0x6639D5F0)))).border(Midnight.edge, tile),
                        contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Color(0xFFF2F8FF)) }
                }
                if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd))
            }
            Text(title, style = MaterialTheme.typography.titleSmall, color = Midnight.text, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Midnight.textSecondary, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Today and this month side by side: distance, trips and expenses today; the month in one word with its completion ring. */
@Composable internal fun PhoneWidgets(data: TripLogData, now: Long, onMonth: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = remember(data, today) { phoneToday(data, today, zone) }
    val month = remember(data, today) { monthWidgetInfo(data.copy(trips = phoneTrips(data)), today, zone) }
    val tone = when (month.tone) { 1 -> Midnight.glow; 2 -> Midnight.warn; else -> Midnight.text }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = Midnight.glass, border = Midnight.edge, modifier = Modifier.weight(1f)
            .semantics(mergeDescendants = true) { contentDescription = "오늘 운행 ${kmText(day.km)}, ${day.trips}건, 경비 ${formatWon(day.expenseWon)}" }) {
            Column(Modifier.heightIn(min = 132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("오늘 운행", style = MaterialTheme.typography.labelMedium, color = Midnight.textMuted)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(String.format(Locale.KOREA, "%,.1f", day.km), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = Midnight.text, maxLines = 1)
                    Text("km", style = MaterialTheme.typography.labelLarge, color = Midnight.textMuted, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("${day.trips}건 · ${formatWon(day.expenseWon)}", style = MaterialTheme.typography.labelMedium, color = Midnight.textSecondary, maxLines = 1)
            }
        }
        Surface(onClick = onMonth, shape = RoundedCornerShape(22.dp), color = Midnight.glass, border = Midnight.edge, modifier = Modifier.weight(1f)
            .semantics(mergeDescendants = true) { contentDescription = "${month.title} ${month.headline}, ${month.detail}, 현황 열기"; role = Role.Button }) {
            Row(Modifier.heightIn(min = 132.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("이달 운행일지", style = MaterialTheme.typography.labelMedium, color = Midnight.textMuted)
                    Text(month.headline, style = MaterialTheme.typography.titleLarge, color = tone, fontWeight = FontWeight.Bold, maxLines = 2)
                }
                CompletionRing(month.percent, tone, 64.dp, 7.dp)
            }
        }
    }
}

// ───────────────────────── bottom tabs ─────────────────────────

/** The phone's four tabs as one floating glass pill; the active tab is lit cyan and counts sit on the icon. */
@Composable internal fun PhoneGlassNav(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Surface(shape = RoundedCornerShape(30.dp), color = Midnight.glass.copy(alpha = .94f), border = Midnight.edge, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selected
                    // The lit state glides from the old tab to the new one on the page clock instead of jumping.
                    val tint by animateColorAsState(if (active) Midnight.glow else Midnight.textSecondary, tabColorSpec(), label = "tab-tint")
                    val fill by animateColorAsState(if (active) Midnight.glow.copy(alpha = .14f) else Color.Transparent, tabColorSpec(), label = "tab-fill")
                    val ring by animateColorAsState(if (active) Midnight.glow.copy(alpha = .45f) else Color.Transparent, tabColorSpec(), label = "tab-ring")
                    val label by animateColorAsState(if (active) Midnight.glow else Midnight.text, tabColorSpec(), label = "tab-label")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(fill).border(1.dp, ring, RoundedCornerShape(24.dp))
                        .clickable { onSelect(index) }
                        .clearAndSetSemantics {
                            this.selected = active; role = Role.Tab
                            contentDescription = if (tab.badge > 0) "${tab.label}, 확인 필요 ${tab.badge}건" else tab.label
                        }
                        .heightIn(min = 58.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                        Box {
                            Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = tint)
                            if (tab.badge > 0) CountBadge(tab.badge, Modifier.align(Alignment.TopEnd).padding(start = 18.dp))
                        }
                        Text(tab.label, style = MaterialTheme.typography.labelMedium, color = label, maxLines = 1)
                    }
                }
            }
        }
    }
}

// ───────────────────────── vehicle screen ─────────────────────────

/** 차량 정보 on the phone: the car card, care deadlines, maintenance and incidents from the copy. Read-only; the car keeps the original. */
@Composable internal fun PhoneVehicleScreen(data: TripLogData, now: Long, onBack: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val profile = data.profile
    val vehicle = data.vehicle
    val demo = profile.demoMode
    val alerts = remember(data.care, vehicle.odometerKm, today) { careAlerts(data.care, vehicle.odometerKm, today) }
    val maintenance = remember(data.maintenance, demo) { data.maintenance.filter { it.demo == demo }.sortedByDescending { it.at } }
    val incidents = remember(data.incidents, demo) { data.incidents.filter { it.demo == demo }.sortedByDescending { it.at } }
    Column(Modifier.fillMaxSize()) {
        PhoneRouteHeader(title = "차량 정보", subtitle = profile.plateNumber.ifBlank { null }, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(shape = RoundedCornerShape(26.dp), color = Color(0xFF0E1522), border = Midnight.edge, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.background(Brush.linearGradient(listOf(Color(0xFF1E2C47), Color(0xFF0E1522)))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MY FLEET", style = MaterialTheme.typography.labelMedium, color = Midnight.brand, letterSpacing = 1.sp)
                    Text(profile.plateNumber.ifBlank { "차량번호 미등록" }, style = MaterialTheme.typography.headlineSmall, color = Midnight.text, fontWeight = FontWeight.Bold)
                    Text((vehicle.model ?: profile.carModel).ifBlank { "차종 미등록" } + validVin(vehicle.vin)?.let { " · VIN …${it.takeLast(6)}" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, color = Midnight.textSecondary)
                    VehicleDrawing(Modifier.fillMaxWidth().height(150.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("배터리", vehicle.socPercent?.let { "$it%" } ?: "—", Modifier.weight(1f))
                        Stat("계기판 · 마지막 수신", vehicle.odometerKm?.takeIf { it > 0 }?.let(::kmText) ?: "—", Modifier.weight(1.4f))
                    }
                    Text("차량이 보낸 마지막 값이에요. 실시간 계기판은 차량 화면에서 확인해요.", style = MaterialTheme.typography.labelMedium, color = Midnight.textMuted)
                }
            }
            Panel(padding = 18.dp) {
                Eyebrow("차량 관리 기한")
                if (alerts.isEmpty()) Muted("등록된 기한이 없어요. 차량의 설정 › 법인·차량에서 검사·보험·리스 만기와 정비 주기를 적으면 여기에도 보여요.")
                else alerts.forEach { alert ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.semantics(mergeDescendants = true) {}) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(careLevelColor(alert.level)))
                        Column(Modifier.weight(1f)) {
                            Text(alert.title, style = MaterialTheme.typography.titleSmall)
                            Text(alert.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(careLevelState(alert.level), style = MaterialTheme.typography.labelMedium, color = careLevelColor(alert.level))
                    }
                }
            }
            Panel(padding = 18.dp) {
                Eyebrow("정비 기록 · ${maintenance.size}건")
                if (maintenance.isEmpty()) Muted("정비 기록이 없어요. 정비소에서 법인카드로 결제하면 차량이 자동으로 남겨요.")
                else maintenance.take(4).forEach { record ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(record.kind.label + (record.merchant?.let { " · $it" } ?: ""), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(phoneTime(record.at, "M월 d일") + (record.odometerKm?.let { " · ${kmText(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (record.amountWon > 0) Text(formatWon(record.amountWon), style = MaterialTheme.typography.titleSmall)
                    }
                }
            }
            Panel(padding = 18.dp) {
                val openCount = incidents.count { it.status == IncidentStatus.OPEN }
                Eyebrow("사고·특이사항 · 대응 중 ${openCount}건")
                if (incidents.isEmpty()) Muted("기록된 사고·특이사항이 없어요. 홈 아래 버튼으로 바로 기록할 수 있어요.")
                else incidents.take(4).forEach { incident ->
                    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(incident.kind.label, style = MaterialTheme.typography.titleSmall)
                            Text(phoneTime(incident.at, "M월 d일 HH:mm") + (incident.address?.let { " · $it" } ?: ""), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(if (incident.status == IncidentStatus.OPEN) "대응 중" else "완료", style = MaterialTheme.typography.labelMedium,
                            color = if (incident.status == IncidentStatus.OPEN) Midnight.warn else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Muted("휴대폰은 차량 기록의 사본이에요. 기한과 정비는 차량에서 입력하고, 바뀐 내용은 바로 여기에도 내려와요.", Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** Sets the phone home's night backdrop: the city low behind the greeting and cards, fading into navy before the task list. */
@Composable internal fun PhoneHomeBackdrop(modifier: Modifier = Modifier) =
    HomeBackdrop(modifier, horizonAt = .40f, heightScale = .30f, moonAt = Offset(.86f, .235f))

