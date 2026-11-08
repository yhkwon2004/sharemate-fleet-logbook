package ai.sharemate.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
internal data class PhoneCardLines(val vehicle: String, val log: String, val logPending: Int, val export: String, val exportMonth: YearMonth, val booking: String)

/**
 * Short lines for the four phone cards, from the phone's copy (current mode only). The log count is the 운행 tab's own badge
 * (unclassified trips), so the card and the tab it opens never disagree; the export names the month 현황 opens on.
 */
internal fun phoneCardLines(data: TripLogData, today: LocalDate, zone: ZoneId, now: Long = today.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
    socPercent: Int? = null): PhoneCardLines {
    val mirror = data.copy(trips = phoneTrips(data))
    val care = careDueAlerts(data.care, data.vehicle.odometerKm, today, emptySet()).firstOrNull()?.title
    val vehicle = care ?: (socPercent ?: data.vehicle.socPercent)?.let { "배터리 $it%" } ?: data.vehicle.odometerKm?.takeIf { it > 0 }?.let { "마지막 ${kmText(it)}" } ?: "차량 상태"
    val month = YearMonth.from(today)
    val monthTrips = tripsIn(mirror.trips, month.atDay(1), month.atEndOfMonth(), zone)
    val pending = mirror.trips.count { it.purpose == TripPurpose.UNCLASSIFIED && !it.open }
    val log = when {
        pending > 0 -> "미분류 ${pending}건"
        monthTrips.isEmpty() -> "${month.monthValue}월 운행 없음"
        else -> "${monthTrips.size}건 · 모두 확인"
    }
    val target = monthCloseTarget(mirror, today, zone)
    val exportMonth = if (target != null && target.month != month) target.month else month
    val export = if (exportMonth != month) "${monthLabel(exportMonth, today)} 마감 전 Excel" else "${month.monthValue}월 Excel"
    val booking = when (val a = vehicleAvailability(mirror, now)) {
        is VehicleAvailability.InUse -> "지금 운행 중"
        is VehicleAvailability.Reserved -> "예약 사용 중"
        is VehicleAvailability.Free -> a.next?.let { "다음 ${reservationStartText(it, now, zone)}" } ?: "지금 예약 가능"
    }
    return PhoneCardLines(vehicle, log, pending, export, exportMonth, booking)
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
    // The open trip counts in both the distance and the number, so the two never disagree.
    return PhoneToday(trips.sumOf { it.distanceKm } + (open?.distanceKm ?: 0.0), trips.size + (if (open != null) 1 else 0), trips.sumOf { it.expenseWon })
}

// ───────────────────────── home hero ─────────────────────────

/**
 * The phone home's top: the date line, the greeting, the link line and four destination cards in a 2×2 grid (차량 정보 ·
 * 차량 예약 · 운행일지 확인 · 정보 내보내기). Drawn over the night backdrop the shell lays behind the home tab.
 */
@Composable internal fun PhoneHomeHero(data: TripLogData, link: PhoneLink, paired: Boolean, now: Long, onVehicle: () -> Unit, onBook: () -> Unit,
    onLog: () -> Unit, onExport: (YearMonth) -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val demo = data.profile.demoMode
    val open = data.open?.takeIf { it.demo == demo }
    val driver = data.drivers.firstOrNull { it.id == data.currentDriverId }
    val soc = link.live?.takeIf { it.demo == demo }?.socPercent
    // The minute is enough for the booking line; the clock tick does not rebuild the card copy every second.
    val minute = now / 60_000
    val lines = remember(data, today, minute, soc) { phoneCardLines(data, today, zone, now, soc) }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // The phone status bar already shows the time and the header the brand, so the hero opens with the date in the brand tone.
        Text("${dayLabel(today)}  ·  FLEET JOURNAL", style = MaterialTheme.typography.labelLarge, color = Scene.brand, letterSpacing = 1.sp,
            modifier = Modifier.clearAndSetSemantics {})
        Text(homeGreeting(Instant.ofEpochMilli(now).atZone(zone).hour, driver?.name, open != null), style = MaterialTheme.typography.headlineMedium,
            color = Scene.text, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
        // No live region here: the distance in this line changes on every poll while driving; the link badge announces state.
        Text(phoneSubtitle(paired, link.connected, open), style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhoneActionCard("차량 정보", lines.vehicle, R.drawable.ic_vehicle, openIncidents(data).size, Modifier.weight(1f).fillMaxHeight(), onVehicle)
                PhoneActionCard("차량 예약", lines.booking, R.drawable.ic_calendar, 0, Modifier.weight(1f).fillMaxHeight(), onBook)
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PhoneActionCard("운행일지 확인", lines.log, R.drawable.ic_journal, lines.logPending, Modifier.weight(1f).fillMaxHeight(), onLog)
                PhoneActionCard("정보 내보내기", lines.export, R.drawable.ic_export, 0, Modifier.weight(1f).fillMaxHeight()) { onExport(lines.exportMonth) }
            }
        }
    }
}

/** A half-width glass card: lit icon tile on the left, the title on one line, one real number under it. At least 76 dp tall. */
@Composable private fun PhoneActionCard(title: String, subtitle: String, icon: Int, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Surface(onClick = onClick, shape = shape, color = Scene.glass.copy(alpha = .86f), border = Scene.edge,
        modifier = modifier.semantics(mergeDescendants = true) {
            contentDescription = "$title, $subtitle" + if (badge > 0) ", 확인 필요 ${badge}건" else ""; role = Role.Button
        }) {
        Row(Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = .07f), Color.Transparent))).heightIn(min = 76.dp)
            .padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                val tile = RoundedCornerShape(13.dp)
                Box(Modifier.size(44.dp).clip(tile).background(Scene.tile).border(Scene.edge, tile),
                    contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(24.dp), tint = Scene.onTile) }
                if (badge > 0) CountBadge(badge, Modifier.align(Alignment.TopEnd).offset(x = 8.dp, y = (-6).dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, color = Scene.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable internal fun PhoneWidgets(data: TripLogData, now: Long, onMonth: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val day = remember(data, today) { phoneToday(data, today, zone) }
    val month = remember(data, today) { monthWidgetInfo(data.copy(trips = phoneTrips(data)), today, zone) }
    val tone = when (month.tone) { 1 -> Scene.glow; 2 -> Scene.warn; else -> Scene.text }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(shape = RoundedCornerShape(22.dp), color = Scene.glass, border = Scene.edge, modifier = Modifier.weight(1f)
            .semantics(mergeDescendants = true) { contentDescription = "오늘 운행 ${kmText(day.km)}, ${day.trips}건, 경비 ${formatWon(day.expenseWon)}" }) {
            Column(Modifier.heightIn(min = 132.dp).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("오늘 운행", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(String.format(Locale.KOREA, "%,.1f", day.km), style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1)
                    Text("km", style = MaterialTheme.typography.labelLarge, color = Scene.textMuted, modifier = Modifier.padding(bottom = 4.dp))
                }
                Text("${day.trips}건 · ${formatWon(day.expenseWon)}", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1)
            }
        }
        Surface(onClick = onMonth, shape = RoundedCornerShape(22.dp), color = Scene.glass, border = Scene.edge, modifier = Modifier.weight(1f)
            .semantics(mergeDescendants = true) { contentDescription = "${month.title} ${month.headline}, ${month.detail}, 현황 열기"; role = Role.Button }) {
            Row(Modifier.heightIn(min = 132.dp).padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("이달 운행일지", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
                    Text(month.headline, style = MaterialTheme.typography.titleMedium, color = tone, fontWeight = FontWeight.Bold, maxLines = 3)
                }
                CompletionRing(month.percent, tone, 52.dp, 6.dp)
            }
        }
    }
}

// ───────────────────────── bottom tabs ─────────────────────────

/** The phone's four tabs as one floating glass pill; the active tab is lit cyan and counts sit on the icon. */
@Composable internal fun PhoneGlassNav(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp)) {
        Surface(shape = RoundedCornerShape(30.dp), color = Scene.glass.copy(alpha = .94f), border = Scene.edge, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                tabs.forEachIndexed { index, tab ->
                    val active = index == selected
                    // The lit state glides from the old tab to the new one on the page clock instead of jumping.
                    val tint by animateColorAsState(if (active) Scene.glow else Scene.textSecondary, tabColorSpec(), label = "tab-tint")
                    val fill by animateColorAsState(if (active) Scene.glow.copy(alpha = .14f) else Color.Transparent, tabColorSpec(), label = "tab-fill")
                    val ring by animateColorAsState(if (active) Scene.glow.copy(alpha = .45f) else Color.Transparent, tabColorSpec(), label = "tab-ring")
                    val label by animateColorAsState(if (active) Scene.glow else Scene.text, tabColorSpec(), label = "tab-label")
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(fill).border(1.dp, ring, RoundedCornerShape(24.dp))
                        .clickable { onSelect(index) }
                        .clearAndSetSemantics {
                            this.selected = active; role = Role.Tab
                            contentDescription = if (tab.badge > 0) "${tab.label}, 확인 필요 ${tab.badge}건" else tab.label
                        }
                        .heightIn(min = 58.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
                        Box {
                            Icon(painterResource(tab.icon), null, Modifier.size(24.dp), tint = tint)
                            if (tab.badge > 0) CountBadge(tab.badge, Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp))
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
@Composable internal fun PhoneVehicleScreen(data: TripLogData, link: PhoneLink, now: Long, onBack: () -> Unit) {
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
            Surface(shape = RoundedCornerShape(26.dp), color = Scene.glass, border = Scene.edge, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.background(Brush.linearGradient(listOf(Scene.accent.copy(alpha = .22f), Color.Transparent))).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("MY FLEET", style = MaterialTheme.typography.labelMedium, color = Scene.brand, letterSpacing = 1.sp)
                    Text(profile.plateNumber.ifBlank { "차량번호 미등록" }, style = MaterialTheme.typography.headlineSmall, color = Scene.text, fontWeight = FontWeight.Bold)
                    Text((vehicle.model ?: profile.carModel).ifBlank { "차종 미등록" } + validVin(vehicle.vin)?.let { " · VIN …${it.takeLast(6)}" }.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)
                    VehicleDrawing(Modifier.fillMaxWidth().height(150.dp))
                    val live = link.live?.takeIf { it.demo == demo }
                    val streaming = live != null && link.connected
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Stat("배터리", (live?.socPercent ?: vehicle.socPercent)?.let { "$it%" } ?: "—", Modifier.weight(1f))
                        Stat(if (streaming) "계기판 · 실시간" else "계기판 · 마지막 수신", (live?.odometerKm ?: vehicle.odometerKm)?.takeIf { it > 0 }?.let(::kmText) ?: "—", Modifier.weight(1.4f))
                    }
                    Text(if (streaming) "차량에서 실시간으로 받고 있어요." else "차량이 보낸 마지막 값이에요. 연결되면 실시간으로 바뀌어요.", style = MaterialTheme.typography.labelMedium, color = Scene.textMuted)
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
                            color = if (incident.status == IncidentStatus.OPEN) Scene.warn else MaterialTheme.colorScheme.onSurfaceVariant)
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

