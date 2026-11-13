package ai.sharemate.app

import android.util.Log
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.util.Date
import java.util.Locale

/*
 * 홈's attention slot (2.13, docs/design/DESIGN_2_13_SPEC.md §3.2): one card under the destinations (2.14), the head of the
 * queue ([attentionQueue]) unless the driver paged to another item ([attentionIndex]), and since 2.14 the idle card, 최근
 * 운행, while the queue is empty, so the slot always holds one card and the page never falls back to a bare sky. Every card
 * is one [AttentionCard]: the title row with its state tag and, from two items, the pager; one headline with a figure at its
 * end; a line or two; at most one body block; and the action row, one line or link at its start and at most two buttons at
 * its end. Each card's blocks are the ones HomeFrame.kt counts ([attentionAnatomy]), with the same constants, so the frame's
 * budget is the card's. The bodies below read the data themselves; a card whose subject has gone draws nothing.
 */

/**
 * Where 홈's cards lead: the picker, the permission prompt, 설정's sections, 운행기록 (a filter and month, or one trip), 월간·Excel
 * and the tabs; and (2.15) the 시연 체험 walkthrough ([openTutorial]), the one the header's 시연 체험 opens.
 */
@Stable internal class HomeNav(
    val openDriver: (String?) -> Unit,
    val requestPermissions: () -> Unit,
    val goSettings: (Int) -> Unit,
    val openLog: (Int, YearMonth?) -> Unit,
    val openTrip: (String) -> Unit,
    val openReport: (YearMonth) -> Unit,
    val navigate: (Int) -> Unit,
    val openTutorial: () -> Unit,
)

/** The pager of a card from two items: where the driver is ([index] of [size]), the neighbours' names, and the turn. */
internal class AttentionPager(val index: Int, val size: Int, val previous: String?, val next: String?, val onPage: (Int) -> Unit)

/** Keeps the driver's page across a page change and a rotation; trip ids are UUIDs, so commas and bars never clash. */
private val AttentionPinSaver = Saver<AttentionPin?, String>(
    save = { pin -> pin?.let { listOf(it.item, it.head).joinToString("|") { item -> "${item.kind.name},${item.count},${item.id.orEmpty()}" } } ?: "" },
    restore = { saved ->
        val items = saved.split('|').mapNotNull { part ->
            val (kind, count, id) = part.split(',').takeIf { it.size == 3 } ?: return@mapNotNull null
            val k = AttentionKind.entries.firstOrNull { it.name == kind } ?: return@mapNotNull null
            AttentionItem(k, count.toIntOrNull() ?: 1, id.ifEmpty { null })
        }
        if (items.size == 2) AttentionPin(items[0], items[1]) else null
    })

/**
 * The slot under the destinations: the card [attentionIndex] picks from [queue], or the idle card ([IdleBody]) while the
 * queue is empty, 24 dp under the row. One card swaps for the next on [cardSwap] (160 ms, the slot's height following on the
 * same clock, so a taller card unfolds downward into the sky; a snap under reduced motion), and nothing above the slot moves.
 * A new head (something more urgent, or the head answered) drops the driver's page. [answers], [prompt] and [snoozes] are
 * 홈's state; [banner] is the batch of card payments the shell holds ([onBanner] clears it), [onReminderClosed] hides this
 * month's close reminder.
 */
@Composable internal fun AttentionSlot(state: TripLogState, now: Long, queue: List<AttentionItem>, nav: HomeNav, act: Act, repository: TripLogRepository,
    answers: ParkedStackAnswers, prompt: MutableState<ChargePrompt>, snoozes: MutableState<Set<String>>, banner: List<CardCharge>,
    onBanner: (List<CardCharge>) -> Unit, onReminderClosed: (YearMonth) -> Unit, modifier: Modifier = Modifier) {
    var pin by rememberSaveable(stateSaver = AttentionPinSaver) { mutableStateOf<AttentionPin?>(null) }
    val head = queue.firstOrNull()
    LaunchedEffect(head) { if (pin != null && pin?.head != head) pin = null }
    val index = attentionIndex(queue, pin)
    val shown = queue.getOrNull(index)
    val pageTo: (Int) -> Unit = { to -> queue.getOrNull(to)?.let { pin = AttentionPin(it, queue.first()) } }
    val pinKind: (AttentionKind) -> Unit = { kind -> queue.indexOfFirst { it.kind == kind }.takeIf { it >= 0 }?.let(pageTo) }
    val pager = if (queue.size >= 2) AttentionPager(index, queue.size, queue.getOrNull(index - 1)?.kind?.label, queue.getOrNull(index + 1)?.kind?.label, pageTo) else null
    val swap = cardSwap()
    AnimatedContent(targetState = shown, modifier = modifier.fillMaxWidth().padding(top = HOME_BLOCK_GAP), transitionSpec = { swap }, label = "attention-card") { item ->
        if (item != null) AttentionBody(item, state, now, nav, act, repository, answers, prompt, snoozes, banner, onBanner, onReminderClosed, pager, pinKind)
        else IdleBody(state.data, nav)
    }
}

/** The card for [item], from what the data holds now. */
@Composable private fun AttentionBody(item: AttentionItem, state: TripLogState, now: Long, nav: HomeNav, act: Act, repository: TripLogRepository,
    answers: ParkedStackAnswers, prompt: MutableState<ChargePrompt>, snoozes: MutableState<Set<String>>, banner: List<CardCharge>,
    onBanner: (List<CardCharge>) -> Unit, onReminderClosed: (YearMonth) -> Unit, pager: AttentionPager?, pinKind: (AttentionKind) -> Unit) {
    val data = state.data
    val today = localDate(now)
    val demo = data.profile.demoMode
    when (item.kind) {
        AttentionKind.INCIDENT -> openIncidents(data).firstOrNull()?.let { IncidentBody(it, data, act, repository, pager) }
        AttentionKind.PERMISSION -> PermissionBody(pager, nav.requestPermissions)
        AttentionKind.RECLASSIFY -> data.trips.firstOrNull { it.id == item.id && !it.open }?.let { trip ->
            PurposeBody(trip, 1, data, now, act, repository, nav, pager, reclassifying = true) { answers.reclassifyId = null }
        }
        AttentionKind.CARD_REVIEW -> {
            val reviews = data.charges.filter { it.state == ChargeState.REVIEW && it.demo == demo }.sortedBy { it.at }
            reviews.firstOrNull()?.let { ReviewBody(it, reviews.size, data, act, repository, pager) }
        }
        AttentionKind.CHARGE -> pendingChargeSession(data)?.let { ChargeBody(it, data, act, repository, pager) { prompt.value = ChargePrompt.WAIT } }
        AttentionKind.FINISHED -> data.trips.firstOrNull { it.id == item.id }?.let { FinishedBody(it, data, answers, nav, pager) }
        AttentionKind.MATCHED -> if (banner.isNotEmpty()) MatchedBody(banner, onBanner, nav, pager)
        AttentionKind.PURPOSE -> {
            val pending = data.trips.filter { it.demo == demo && !it.open && it.purpose == TripPurpose.UNCLASSIFIED }.sortedByDescending { it.startAt }
            pending.firstOrNull()?.let { PurposeBody(it, pending.size, data, now, act, repository, nav, pager, reclassifying = false) {} }
        }
        AttentionKind.SETUP -> SetupBody(data, nav, pager)
        AttentionKind.PAIRING -> PairingBody(state.sync, now, pager)
        AttentionKind.MONTH_CLOSE -> monthCloseTarget(data, today, zone)?.let { MonthCloseBody(it, today, act, repository, nav, pager) { pinKind(AttentionKind.CARD_REVIEW) } }
        AttentionKind.CARE -> CareBody(state, now, act, repository, snoozes, nav, pager)
        AttentionKind.CLOSE_REMINDER -> monthCloseTarget(data, today, zone)?.let { status ->
            CloseReminderBody(status, today, nav, pager, onReview = { pinKind(AttentionKind.CARD_REVIEW) }) { onReminderClosed(status.month) }
        }
        AttentionKind.CHARGE_WAITING -> hiddenPendingCharge(data, now, prompt.value)?.let { ChargeWaitingBody(data, now, it, prompt, pager) }
    }
}

// ───────────────────────── the card ─────────────────────────

/**
 * One attention card (§3.2): `CarCard(padding = 24.dp)` holding, in order, the title row (the [icon], the [title], the
 * card's one heading, its state [tag], and the [pager] at the end, 32 dp or 56 with the pager); 12 dp; the [headline]
 * (headlineSmall, one line, in [headlineColor], a polite live region when [live]; TalkBack hears [spokenHeadline] where one
 * is given, as for a route whose arrow is not read) with the [figure] at its end; each of the
 * [lines] 4 dp under it (one [CarLine] each, its spoken form for TalkBack); 16 dp and the [body]; 16 dp and the action row,
 * the [start] slot (a line, a link or a field) and the [actions], at most two buttons, the way on last. Under 560 dp of
 * content the start slot takes its own row above the buttons. A debug build logs (tag HomeFrame) a card taller than
 * [attentionCardMax] at the full window's normal font, where the frame's budget holds. [kind] null is the idle card. A
 * [headline] of null (2.15, the idle card's rows; 2.15.1, the pairing card, whose column beside the QR holds its own
 * headline and action row) leaves the headline row out, with its figure, [under] and [lines]: the body then follows the
 * title row's 12 dp directly, as [AttentionAnatomy.headline] counts it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AttentionCard(kind: AttentionKind?, title: String, icon: Painter, pager: AttentionPager?, headline: String?,
    spokenHeadline: String? = null, tag: (@Composable () -> Unit)? = null, headlineColor: Color = Scene.text, live: Boolean = false, figure: (@Composable () -> Unit)? = null,
    under: (@Composable () -> Unit)? = null, lines: List<HomeLine> = emptyList(), body: (@Composable ColumnScope.() -> Unit)? = null,
    start: (@Composable () -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)? = null) {
    val layout = LocalLayout.current
    val density = LocalDensity.current
    val check = BuildConfig.DEBUG && layout.full && density.fontScale <= 1f
    CarCard(if (check) Modifier.onSizeChanged { size ->
        val tall = with(density) { size.height.toDp() }
        if (tall > attentionCardMax(kind, pager != null) + 1.dp) Log.w("HomeFrame", "${kind ?: "IDLE"} card ${tall.value} dp > ${attentionCardMax(kind, pager != null).value}")
    } else Modifier, padding = ATTENTION_PADDING, gap = 0.dp) {
        FlowRow(Modifier.fillMaxWidth().heightIn(min = if (pager != null) ATTENTION_TITLE_PAGER else ATTENTION_TITLE), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically), itemVerticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, Modifier.size(24.dp), tint = Car.skin.icon)
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, color = Scene.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                if (tag != null) {
                    Spacer(Modifier.width(12.dp))
                    tag()
                }
            }
            if (pager != null) PagerRow(pager)
        }
        Spacer(Modifier.height(ATTENTION_TITLE_GAP))
        if (headline != null) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(headline, Modifier.weight(1f).alignByBaseline().then(if (live) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier)
                    .then(if (spokenHeadline != null) Modifier.semantics { contentDescription = spokenHeadline } else Modifier),
                    style = MaterialTheme.typography.headlineSmall, color = headlineColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (figure != null) {
                    Spacer(Modifier.width(16.dp))
                    Box(Modifier.alignByBaseline()) { figure() }
                }
            }
            under?.invoke()
            lines.forEach { line ->
                Spacer(Modifier.height(ATTENTION_LINE_GAP))
                CarLine(line.text, spoken = line.spoken.takeIf { it != line.text })
            }
        }
        if (body != null) {
            if (headline != null) Spacer(Modifier.height(ATTENTION_BLOCK_GAP))
            Column(Modifier.fillMaxWidth(), content = body)
        }
        if (start != null || actions != null) {
            Spacer(Modifier.height(ATTENTION_BLOCK_GAP))
            ActionRow(start, actions)
        }
    }
}

/** The pager: ‹ 1/3 ›, two 56-dp arrows on the disc (a dead one at 38 %) around a live tag that says where the driver is. */
@Composable private fun PagerRow(pager: AttentionPager) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        MonthStepButton(forward = false, description = pager.previous?.let { "${HomeCopy.PAGER_PREVIOUS}, $it" } ?: HomeCopy.PAGER_FIRST,
            enabled = pager.index > 0) { pager.onPage(pager.index - 1) }
        CarTag("${pager.index + 1}/${pager.size}", live = true, description = "${HomeCopy.QUEUE_HEAD} ${pager.size}건 중 ${pager.index + 1}번째")
        MonthStepButton(forward = true, description = pager.next?.let { "${HomeCopy.PAGER_NEXT}, $it" } ?: HomeCopy.PAGER_LAST,
            enabled = pager.index < pager.size - 1) { pager.onPage(pager.index + 1) }
    }
}

/** The action row (56 dp; 68 with the money field): [start] takes what the buttons leave; under 560 dp it stands on its own row above them. */
@Composable private fun ActionRow(start: (@Composable () -> Unit)?, actions: (@Composable RowScope.() -> Unit)?) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (start != null && actions != null && maxWidth < 560.dp) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = ATTENTION_ROW), contentAlignment = Alignment.CenterStart) { start() }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = actions)
        } else Row(Modifier.fillMaxWidth().heightIn(min = ATTENTION_ROW), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) { start?.invoke() }
            actions?.invoke(this)
        }
    }
}

/** A distance as a card's figure: the digits in displaySmall with tabular figures and km in titleMedium textMuted, on one baseline. */
@Composable private fun KmFigure(km: Double) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(String.format(Locale.KOREA, "%,.1f", km), Modifier.alignByBaseline(), style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            color = Scene.text, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Text("km", Modifier.alignByBaseline(), style = MaterialTheme.typography.titleMedium, color = Scene.textMuted, maxLines = 1)
    }
}

/**
 * The link at a purpose card's start: a Person glyph and who drove the trip, or 사용자 미지정, in the accent ink (AA on the
 * card only) with a chevron; it opens the picker for that trip. TalkBack hears what a tap does first.
 */
@Composable private fun PersonLink(driver: Driver?, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val ink = Car.skin.accentInk
    val label = driver?.label ?: HomeCopy.NO_DRIVER
    Row(Modifier.pressable(interaction, .98f).clip(RoundedCornerShape(12.dp))
        .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = "${if (driver != null) HomeCopy.SPOKEN_DRIVER_CHANGE else HomeCopy.SPOKEN_DRIVER_SET}, $label" }
        .pressFill(interaction).heightIn(min = 56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Person, contentDescription = null, Modifier.size(20.dp), tint = ink)
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 320.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(18.dp), tint = ink)
    }
}

// ───────────────────────── 특이사항 대응 ─────────────────────────

/**
 * An open incident: 지금 할 일 and where photos come from; the kind's steps to the letter (the shared safety copy), walking
 * in once the first time this report is seen; the note and the photos the phone sent; the captured facts beside 기록 삭제 and
 * 처리 완료. The card is opaque (no accident tint). A photo's × asks first, and so does deleting a record that has photos:
 * the record can come back, the pictures cannot.
 */
@Composable private fun IncidentBody(incident: Incident, data: TripLogData, act: Act, repository: TripLogRepository, pager: AttentionPager?) {
    val followUp = LocalFollowUp.current
    val driver = data.drivers.firstOrNull { it.id == incident.driverId }
    val steps = incidentSteps(incident.kind)
    var introduced by rememberSaveable(incident.id) { mutableStateOf(false) }
    val stagger = remember(incident.id) { !introduced }
    LaunchedEffect(incident.id) { introduced = true }
    var confirmDelete by remember(incident.id) { mutableStateOf(false) }
    var removing by remember(incident.id) { mutableStateOf<IncidentPhoto?>(null) }
    val warning = painterResource(R.drawable.ic_warning)
    AttentionCard(AttentionKind.INCIDENT, incident.kind.label, warning, pager, HomeCopy.INCIDENT_HEADLINE, live = true,
        tag = { CarTag("${incident.status.label} · ${hm(incident.at)}", tone = PillTone.NEGATIVE) },
        lines = listOf(incidentHint(incident.photos.size, data.profile.phoneSync)),
        body = {
            steps.take(INCIDENT_STEPS_MAX).forEachIndexed { index, step ->
                Appear(index = index.coerceAtMost(3), animate = stagger) {
                    Row(Modifier.fillMaxWidth().heightIn(min = INCIDENT_STEP_ROW).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically) {
                        CarStepDisc(index + 1, done = false, size = INCIDENT_STEP_DISC)
                        Spacer(Modifier.width(12.dp))
                        Text(step, style = MaterialTheme.typography.bodyLarge, color = Scene.text)
                    }
                }
            }
            if (incident.note.isNotBlank()) {
                Spacer(Modifier.height(INCIDENT_GAP))
                CarLine(incident.note, color = Scene.text)
            }
            if (incident.photos.isNotEmpty()) {
                Spacer(Modifier.height(INCIDENT_GAP))
                PhotoStrip(incident.photos, repository.photos, size = INCIDENT_PHOTO, onRemove = { removing = it }, tapTarget = 56.dp)
            }
        },
        start = { CarLine(incidentFacts(incident, driver)) },
        actions = {
            QuietAction(HomeCopy.INCIDENT_DELETE, color = Scene.negative) {
                if (incident.photos.isEmpty()) deleteIncident(incident, act, repository, followUp) else confirmDelete = true
            }
            PrimaryAction(HomeCopy.INCIDENT_DONE) { closeIncident(incident, act, repository, followUp) }
        })
    removing?.let { photo ->
        CarDialog(HomeCopy.PHOTO_DIALOG_TITLE, onDismiss = { removing = null }, icon = warning, lines = listOf(HomeCopy.PHOTO_DIALOG_LINE)) {
            QuietAction(HomeCopy.CANCEL) { removing = null }
            QuietAction(HomeCopy.DELETE, color = Scene.negative) {
                removing = null
                act { repository.removePhoto(incident.id, photo.id) }
            }
        }
    }
    if (confirmDelete) {
        val photos = incident.photos.size
        CarDialog("사진 ${photos}장도 함께 지워져요", onDismiss = { confirmDelete = false }, icon = warning, lines = listOf(HomeCopy.DELETE_DIALOG_LINE)) {
            QuietAction(HomeCopy.CANCEL) { confirmDelete = false }
            QuietAction(HomeCopy.DELETE, color = Scene.negative, description = "기록과 사진 ${photos}장 삭제") {
                confirmDelete = false
                deleteIncident(incident, act, repository, followUp)
            }
        }
    }
}

// ───────────────────────── 차량 권한 ─────────────────────────

/** The vehicle signal is denied: what the permission is for, and the one way to grant it. */
@Composable private fun PermissionBody(pager: AttentionPager?, request: () -> Unit) {
    AttentionCard(AttentionKind.PERMISSION, HomeCopy.PERMISSION_TITLE, rememberVectorPainter(Icons.Outlined.Lock), pager, HomeCopy.PERMISSION_HEADLINE,
        tag = { CarTag(HomeCopy.PERMISSION_TAG, tone = PillTone.NEGATIVE) }, lines = listOf(HomeLine(HomeCopy.PERMISSION_LINE)),
        actions = { PrimaryAction(HomeCopy.PERMISSION_ACTION, onClick = request) })
}

// ───────────────────────── 목적 확인 · 목적 바꾸기 ─────────────────────────

/**
 * The purpose question for [trip] ([count] waiting), or with [reclassifying] the purpose being changed from the 방금 운행 card.
 * The driver's own earlier answer is the one-tap default ([suggestedPurposeLabel]); the three tiles show without one, after
 * 다른 목적, and always while changing (the trip's purpose chosen). The person link opens the picker for this trip; a truthful
 * line says when the destination will label itself ([autoPurposeProgress]). [onAnswered] runs before the answer is written.
 */
@Composable private fun PurposeBody(trip: Trip, count: Int, data: TripLogData, now: Long, act: Act, repository: TripLogRepository, nav: HomeNav,
    pager: AttentionPager?, reclassifying: Boolean, onAnswered: () -> Unit) {
    val classify = rememberClassifier(data, act, repository)
    val suggestion = remember(trip.id, data.trips.size) { suggestPurpose(trip, data.trips)?.takeIf { !reclassifying || it.purpose != trip.purpose } }
    val sameCount = suggestion?.let { sameDestinationCount(trip, data.trips, it.purpose) } ?: 0
    var more by rememberSaveable(trip.id) { mutableStateOf(suggestion == null) }
    val driver = data.drivers.firstOrNull { it.id == trip.driverId }
    val answer: (TripPurpose, EditSource) -> Unit = { purpose, source -> onAnswered(); classify(trip, purpose, source) }
    val tag = purposeCardTag(count, if (reclassifying) trip.purpose else null)
    AttentionCard(if (reclassifying) AttentionKind.RECLASSIFY else AttentionKind.PURPOSE, if (reclassifying) HomeCopy.RECLASSIFY_TITLE else HomeCopy.PURPOSE_TITLE,
        painterResource(R.drawable.ic_journal), pager, if (reclassifying) HomeCopy.RECLASSIFY_HEADLINE else HomeCopy.PURPOSE_HEADLINE,
        tag = tag?.let { { CarTag(it, tone = if (reclassifying) null else PillTone.NEGATIVE) } },
        figure = { KmFigure(trip.distanceKm) },
        lines = listOfNotNull(HomeLine(purposeCardLine(trip, now, zone)), autoPurposeProgress(trip, data.trips, data.profile.autopilot)?.let { HomeLine(it) }),
        body = if (reclassifying || more || suggestion == null) { {
            PurposeOptions(if (reclassifying) trip.purpose else null) { purpose -> if (!reclassifying || purpose != trip.purpose) answer(purpose, EditSource.DRIVER) }
        } } else null,
        start = { PersonLink(driver) { nav.openDriver(trip.id) } },
        actions = {
            if (reclassifying) QuietAction(HomeCopy.PURPOSE_KEEP, onClick = onAnswered)
            else if (suggestion != null && !more) QuietAction(HomeCopy.PURPOSE_OTHER, description = HomeCopy.SPOKEN_PURPOSE_OTHER) { more = true }
            suggestion?.let { PrimaryAction(suggestedPurposeLabel(it.purpose, sameCount)) { answer(it.purpose, EditSource.SUGGESTED) } }
        })
}

// ───────────────────────── 카드 확인 ─────────────────────────

/**
 * One card payment the car could not place for sure: the headline says how sure it is ([chargeReviewHeadline]), the amount
 * waits in the pending tone, then the merchant and the trip it would join. Each answer says what it did and offers the way
 * back: 맞아요 → "… 13:30 운행에 넣었어요 · 회사 비용 아님"; 회사 비용 아님 → "… 회사 비용에서 뺐어요 · 실행 취소" (with a trip).
 */
@Composable private fun ReviewBody(charge: CardCharge, remaining: Int, data: TripLogData, act: Act, repository: TripLogRepository, pager: AttentionPager?) {
    val followUp = LocalFollowUp.current
    val trip = suggestedTrip(charge, data)
    val moment = SimpleDateFormat(if (charge.timeKnown) "M/d HH:mm" else "M/d", Locale.KOREA).format(Date(charge.at))
    val what = "${charge.merchant} ${formatWon(charge.amountWon)}"
    AttentionCard(AttentionKind.CARD_REVIEW, HomeCopy.REVIEW_TITLE, painterResource(R.drawable.ic_receipt), pager, chargeReviewHeadline(charge, trip),
        tag = { CarTag(if (remaining > 1) "${remaining}건 · ${HomeCopy.REVIEW_ONE_BY_ONE}" else "1건") },
        figure = { CarAmount(charge.amountWon, size = MoneySize.HERO, tone = MoneyTone.PENDING) },
        lines = listOfNotNull(HomeLine("${charge.merchant} · $moment · ${charge.category?.label ?: "차량 비용 추정"}"),
            trip?.let { HomeLine("${hm(it.startAt)} 출발 · ${kmText(it.distanceKm)} · ${it.destination ?: it.endAddress ?: "주소 미기록"}") }),
        start = charge.reason.takeIf { it.isNotBlank() }?.let { reason -> { CarLine("판단 근거 · $reason") } },
        actions = {
            QuietAction(HomeCopy.NOT_COMPANY) {
                act {
                    repository.excludeCardCharge(charge.id)
                    followUp(if (trip != null) FollowUp("${what}을 회사 비용에서 뺐어요", HomeCopy.UNDO) { repository.confirmCardCharge(charge.id, trip.id) }
                        else FollowUp("${what}을 회사 비용에서 뺐어요"))
                }
            }
            if (trip != null) PrimaryAction(HomeCopy.REVIEW_YES) {
                act {
                    repository.confirmCardCharge(charge.id, trip.id)
                    followUp(FollowUp("${what}을 ${hm(trip.startAt)} 운행에 넣었어요", HomeCopy.NOT_COMPANY) { repository.excludeCardCharge(charge.id) })
                }
            }
        })
}

// ───────────────────────── 충전 비용 ─────────────────────────

private val chargeQuickAmounts = listOf(10_000L, 20_000L, 30_000L, 50_000L)

/**
 * The car saw a charge (or a refuel) end; the amount is the only thing left, and even that can be skipped. Two rows of chips
 * at most: 비용 없음, what this car paid before here or for a similar charge ([recentChargeAmounts]), then four round amounts;
 * or the money field and 등록, which needs an amount in range and clears the field only once the amount is saved. 카드 승인
 * 기다리기 appears only when a company card is registered: only a card can settle this by itself.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun ChargeBody(session: ChargeSession, data: TripLogData, act: Act, repository: TripLogRepository, pager: AttentionPager?, onWait: () -> Unit) {
    val followUp = LocalFollowUp.current
    var digits by rememberSaveable(session.endAt) { mutableStateOf("") }
    val input = moneyInput(digits, min = 1, max = 10_000_000, hint = "")
    val minutes = ((session.endAt - session.startAt) / 60_000).coerceAtLeast(1)
    val word = if (session.fuel) "주유" else "충전"
    val level = if (session.socFrom != null && session.socTo != null) "${if (session.fuel) "연료" else "배터리"} ${session.socFrom}% → ${session.socTo}%" else "배터리 변화 미수신"
    val odometer = session.odometerKm?.takeIf { it.isFinite() }?.let { " · 계기판 ${kmText(it)}" }.orEmpty()
    val anchors = remember(session.id, session.endAt, data.stops.size, data.charges.size) { recentChargeAmounts(session, data) }
    val cardsRegistered = data.profile.cards.isNotEmpty()
    // The amount lands on the trip that arrived here; undo takes that one line back off again.
    val register: (Long, Boolean) -> Unit = { amount, typed ->
        act {
            repository.resolveCharge(amount, EditSource.DRIVER)
            if (typed) digits = ""
            followUp(FollowUp("$word ${formatWon(amount)}을 최근 운행 비용에 넣었어요", HomeCopy.UNDO) {
                val latest = repository.state.value.data
                val trip = latest.trips.filter { it.demo == session.demo && it.startAt <= session.endAt }.maxByOrNull { it.startAt }
                val index = trip?.expenses?.indexOfLast { it.type == ExpenseType.FUEL && it.cardChargeId == null && it.amountWon == amount } ?: -1
                if (trip != null && index >= 0) {
                    repository.removeExpense(trip.id, index)
                    followUp(FollowUp("$word 비용을 지웠어요 · 운행 상세에서 다시 등록할 수 있어요"))
                }
            })
        }
    }
    AttentionCard(AttentionKind.CHARGE, "$word 비용", painterResource(R.drawable.ic_won), pager, "$word 비용만 알려 주세요",
        tag = { CarTag("${HomeCopy.CHARGE_DETECTED} · ${hm(session.startAt)}–${hm(session.endAt)}", tone = PillTone.POSITIVE) },
        lines = listOf(HomeLine("$level · ${minutes}분$odometer")),
        body = {
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(CHARGE_CHIP_GAP),
                maxLines = CHARGE_CHIP_ROWS) {
                CarExampleChip(HomeCopy.CHARGE_NONE, {
                    act { repository.resolveCharge(null, EditSource.DRIVER); followUp(FollowUp("비용 없이 $word 기록을 정리했어요")) }
                })
                anchors.forEach { anchor -> CarExampleChip(anchor.label, { register(anchor.amountWon, false) }, actionLabel = HomeCopy.CHARGE_CHIP_ACTION) }
                chargeQuickAmounts.forEach { amount -> CarExampleChip(formatWon(amount), { register(amount, false) }, actionLabel = HomeCopy.CHARGE_CHIP_ACTION) }
            }
        },
        start = { CarMoneyField(HomeCopy.CHARGE_FIELD, digits, { digits = it }, input, maxDigits = 8, done = true) },
        actions = {
            PrimaryAction(HomeCopy.CHARGE_REGISTER, Modifier.semantics { contentDescription = HomeCopy.SPOKEN_CHARGE_REGISTER }, enabled = input.ok) {
                input.won?.let { register(it, true) }
            }
            if (cardsRegistered) QuietAction(HomeCopy.CHARGE_WAIT, onClick = onWait)
        })
}

// ───────────────────────── 방금 운행 ─────────────────────────

/**
 * The peak-end moment: the trip is already in the logbook with the purpose the driver gave this place before. One tap
 * acknowledges it; 다른 목적 reopens the question (the pager follows it, [attentionIndex]). The row's start says what a card
 * payment already added, or offers 비용 추가, which opens the trip in 운행기록.
 */
@Composable private fun FinishedBody(trip: Trip, data: TripLogData, answers: ParkedStackAnswers, nav: HomeNav, pager: AttentionPager?) {
    val charge = matchedChargeFor(trip, data)
    AttentionCard(AttentionKind.FINISHED, HomeCopy.FINISHED_TITLE, painterResource(R.drawable.ic_vehicle), pager, "${withRo(trip.purpose.label)} 자동 분류했어요",
        live = true, tag = { CarTag("${hm(trip.startAt)} – ${hm(trip.endAt ?: trip.updatedAt)} · ${durationText(trip.durationMillis)}") },
        figure = { KmFigure(trip.distanceKm) }, lines = listOf(HomeLine(autoFillMessage(trip, data.trips))),
        start = {
            if (charge != null) CarLine("${charge.category?.label ?: "카드 결제"} ${formatWon(charge.amountWon)} 반영 · ${charge.merchant}")
            else CarLink(HomeCopy.FINISHED_ADD_COST, { nav.openTrip(trip.id) }, description = HomeCopy.SPOKEN_FINISHED_ADD_COST)
        },
        actions = {
            QuietAction(HomeCopy.PURPOSE_OTHER) { answers.answer(trip.id, reclassify = true) }
            PrimaryAction(HomeCopy.FINISHED_CONFIRM) { answers.answer(trip.id) }
        })
}

// ───────────────────────── 카드 반영 ─────────────────────────

/**
 * Several card payments the car settled at once (a statement import, a phone catching up): the count and the sum, and the
 * list where each line can be taken back, on the month of the batch's first payment ([matchedBatchMonth]). 닫기 is not
 * remembered, so the next batch is announced again.
 */
@Composable private fun MatchedBody(charges: List<CardCharge>, onBanner: (List<CardCharge>) -> Unit, nav: HomeNav, pager: AttentionPager?) {
    AttentionCard(AttentionKind.MATCHED, HomeCopy.MATCHED_TITLE, painterResource(R.drawable.ic_receipt), pager, "카드 결제 ${charges.size}건을 운행에 반영했어요",
        live = true, tag = { CarTag("${charges.size}건") }, figure = { CarAmount(charges.sumOf { it.amountWon }, size = MoneySize.HERO) },
        lines = listOf(HomeLine(HomeCopy.MATCHED_LINE)),
        actions = {
            QuietAction(HomeCopy.CLOSE, description = HomeCopy.SPOKEN_MATCHED_CLOSE) { onBanner(emptyList()) }
            PrimaryAction(HomeCopy.MATCHED_SHOW) {
                val month = matchedBatchMonth(charges, zone)
                onBanner(emptyList())
                nav.openLog(2, month)
            }
        })
}

// ───────────────────────── 시작 설정 ─────────────────────────

/** Under this much card width the setup tiles stack instead of standing three across. */
private val SETUP_TILES_STACK_UNDER = 560.dp

/**
 * What the logbook needs before it can be filed ([setupSteps]), the first-run card (2.14, richer): the headline and the n/3
 * tag, then the three steps as tiles across (stacked under 560 dp of card width), each the button that opens its own place:
 * 설정 › 법인·차량, 설정 › 직원, the picker. A tile says what its step is for and, once done, what it holds; the first open
 * step's disc is the solid accent. 2.13's two buttons under the steps went: every step is one tap on its own tile.
 */
@Composable private fun SetupBody(data: TripLogData, nav: HomeNav, pager: AttentionPager?) {
    val steps = setupSteps(data)
    val go: (SetupTarget) -> Unit = { target ->
        when (target) {
            SetupTarget.COMPANY -> nav.goSettings(0)
            SetupTarget.DRIVERS -> nav.goSettings(1)
            SetupTarget.PICKER -> nav.openDriver(null)
        }
    }
    AttentionCard(AttentionKind.SETUP, HomeCopy.SETUP_TITLE, painterResource(R.drawable.ic_settings), pager, HomeCopy.SETUP_HEADLINE,
        tag = { CarTag("${steps.count { it.done }}/${steps.size}") },
        body = {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val gap = 12.dp
                if (maxWidth < SETUP_TILES_STACK_UNDER) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    steps.forEachIndexed { index, step -> SetupTile(index + 1, step, Modifier.fillMaxWidth()) { go(step.target) } }
                } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    steps.forEachIndexed { index, step -> SetupTile(index + 1, step, Modifier.weight(1f)) { go(step.target) } }
                }
            }
        })
}

/**
 * One setup step as a tile (2.14), [SETUP_TILES] tall at least, corner 16: the [CarStepDisc] (the [number]; a check once
 * done; the solid accent on the current step), 12 dp, the title (titleSmall) over its one line (bodySmall textMuted; two
 * above 1.05× as a [CarLine] takes, so a large font keeps the disabled tile's reason whole), and a chevron. An open step
 * sits on the chip fill inside the chip border, a done one is outlined only, and a step that cannot be taken yet (운전자
 * 선택 before any employee) has 2.13's disabled look: no fill, the outline at 38 %, everything muted. It
 * settles to .98 with the press fill, no ripple. TalkBack hears one button, "법인·차량 정보, 운행기록부 머리말에 들어가요",
 * with its state (완료 / 다음 단계 / 직원 등록 뒤), disabled where it cannot act.
 */
@Composable private fun SetupTile(number: Int, step: SetupStep, modifier: Modifier, onClick: () -> Unit) {
    val skin = Car.skin
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(16.dp)
    val open = !step.done && step.enabled
    val border = if (step.enabled) skin.chipBorder else skin.chipBorder.copy(alpha = skin.chipBorder.alpha * .38f)
    Row(modifier.then(if (step.enabled) Modifier.pressable(interaction, .98f) else Modifier).heightIn(min = SETUP_TILES).clip(shape)
        .then(if (open) Modifier.background(skin.chipFill) else Modifier).border(1.dp, border, shape)
        .clickable(interactionSource = interaction, indication = null, enabled = step.enabled, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = "${step.label}, ${step.spokenLine}"; stateDescription = step.state }
        .pressFill(interaction).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        CarStepDisc(number, step.done, idle = !step.enabled, current = step.current)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(step.label, style = MaterialTheme.typography.titleSmall, color = if (step.enabled) Scene.text else Scene.textMuted, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            Text(step.line, style = MaterialTheme.typography.bodySmall, color = Scene.textMuted, maxLines = carLineMaxLines(LocalDensity.current.fontScale),
                overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(20.dp),
            tint = if (step.enabled) Scene.textSecondary else Scene.textMuted)
    }
}

// ───────────────────────── 최근 운행 (idle) ─────────────────────────

/**
 * The idle card (2.14, while the queue is empty), so the slot never leaves the page bare: 최근 운행 with the quiet tag 확인할
 * 일 없음. 2.15 (docs/design/DESIGN_2_15.md §4): 2.14's one figure and one button left the card half empty, so it lists the
 * newest finished trips of the data set on screen, three at most ([homeIdle]), with no headline row: each row is a button
 * that opens 운행기록 on its trip ([IdleTripRows], [HomeNav.openTrip]); 운행기록 보기 opens the newest trip's month. Without
 * any trip the card says so and shows how a trip is recorded, three step tiles ([IdleStepTiles]), with 시연 체험 보기, the
 * walkthrough the header's 시연 체험 opens. Either form stays within the frame's idle card ([idleCardHeight]).
 */
@Composable private fun IdleBody(data: TripLogData, nav: HomeNav) {
    val idle = remember(data) { homeIdle(data) }
    val journal = painterResource(R.drawable.ic_journal)
    val tag: @Composable () -> Unit = { CarTag(HomeCopy.IDLE_TAG, description = HomeCopy.SPOKEN_IDLE_TAG) }
    if (idle.rows.isNotEmpty()) AttentionCard(null, HomeCopy.IDLE_TITLE, journal, null, headline = null, tag = tag,
        body = { IdleTripRows(idle.rows, nav.openTrip) },
        actions = idle.month?.let { month -> { QuietAction(HomeCopy.IDLE_ACTION) { nav.openLog(0, month) } } })
    else AttentionCard(null, HomeCopy.IDLE_TITLE, journal, null, HomeCopy.IDLE_EMPTY, tag = tag,
        body = { IdleStepTiles(HomeCopy.IDLE_STEPS) },
        actions = { PrimaryAction(HomeCopy.IDLE_DEMO, onClick = nav.openTutorial) })
}

// ───────────────────────── 휴대폰 연결 ─────────────────────────

/** The name's column of a [PairingLine] at 1×; it grows with a larger font, so 연결 번호 stays whole at 1.3×. */
private val PAIRING_LABEL = 72.dp

/**
 * Phone pairing from the driver's seat (2.15.1, docs/design/DESIGN_2_15.md §10): the 차량 page's QR, so the phone scans the car
 * instead of typing an address, six digits and a 64-character key. The title row as it was, the countdown as its tag (it ticks
 * in place and is never announced; TalkBack hears "4분 59초 남음", [pairingRemainingSpoken]); under it one row: the QR of the
 * live link ([syncPairingLinkText], [QrCode]) or, while there is none, the 차량 page's tile ([PairingQrPlaceholder]); 24 dp;
 * and [PairingColumn], as tall as the QR at the least. Where the card's content is under 536 dp ([pairingQrAbove]; more with
 * a larger font, so the column keeps its 1× room) the QR stands above the column. The card has no headline row and no action
 * row of its own (`headline = null`, no `actions`): the column holds both, so its height is the title row and the QR
 * ([pairingAnatomy]: 324 dp, 348 with the pager).
 *
 * The link is encoded here, once per text (`remember(link)`), so the column knows the tile's side the way [QrCode] takes it
 * ([qrTileSide]: larger than 232 dp only where a module would fall under 4 dp) and the tile draws that same matrix; the clock's
 * tick that recomposes the card never rebuilds it. Every reader of the code asks for the live one ([livePairingCode]), so the
 * second a code runs out the QR gives way to the tile with 연결 번호가 만료됐어요 and 새 연결 번호 (§9.6). While the server is
 * not running there is no QR, no countdown and nothing to press. 2.15.0's 차량 기록이 원본이에요 stays on the 차량 page's card,
 * behind its ⓘ row.
 */
@Composable private fun PairingBody(info: SyncInfo, now: Long, pager: AttentionPager?) {
    val code = livePairingCode(info, now)
    val remaining = pairingRemaining(info, now)
    val link = if (info.running) syncPairingLinkText(info, now) else null
    val matrix = remember(link) { link?.let(::qrMatrix) }
    val side = qrTileSide(matrix)
    val headline = if (info.running) pairingQrHeadline(link, code, info.pairedPhones) else HomeCopy.PAIRING_PREPARING
    AttentionCard(AttentionKind.PAIRING, VehicleCopy.PHONE_TITLE, painterResource(R.drawable.ic_phone), pager, headline = null,
        tag = if (info.running && code != null && remaining != null) { { CarTag("$remaining 남음", description = pairingRemainingSpoken(info, now)) } } else null,
        body = {
            // The column is sized from the QR's own side, never by asking the row (whose action row is a BoxWithConstraints) for intrinsics.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val qr: @Composable () -> Unit = { if (matrix != null) QrCode(matrix, QR_MIN_SIDE) else PairingQrPlaceholder(side) }
                val above = pairingQrAbove(maxWidth, side, LocalDensity.current.fontScale)
                if (above) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ATTENTION_BLOCK_GAP)) {
                    qr()
                    PairingColumn(info, code, headline, Modifier.fillMaxWidth(), qr = matrix != null)
                } else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    qr()
                    Spacer(Modifier.width(PAIRING_QR_GAP))
                    PairingColumn(info, code, headline, Modifier.weight(1f).heightIn(min = side), qr = matrix != null)
                }
            }
        })
}

/**
 * The column beside 홈's pairing QR (2.15.1, §10), as tall as the QR at the least, its blocks spread over that height (at FULL
 * 1× they need 222 dp, [PAIRING_COLUMN], so the 10 dp to spare go between them): the [headline] (headlineSmall, a polite live
 * region, so a code that runs out is heard once; one line in the FULL column's 808 dp, and two at the most where the column is
 * narrower, as the 차량 page's headline beside its QR takes them, since its longest forms, 새 번호로 휴대폰을 더 연결해요 and
 * 휴대폰 연결을 준비하고 있어요, are wider than the column's least of 280 dp and are wrapped there, never cut,
 * [PAIRING_HEADLINE_LINES]); 16 dp; the three things a phone types in the phone's order, 주소, 연결 번호 and 인증키
 * ([PairingLine], each one TalkBack stop; the code as "455 426" in titleMedium, heard digit by digit, or a muted "––– –––"
 * that TalkBack hears as the reason); 16 dp; and the action row at its foot, 같은 Wi-Fi나 핫스팟에서 찍어요
 * at the start while the QR shows ([qr]; else what brings it back, [VehicleCopy.pairingQrHint]) and 번호 다시 만들기, or 새 연결
 * 번호 without a live code, at the end. The addresses
 * are the ones the QR carries, the first four in the car's order. While the server is not running: the headline and one line
 * (the server's error, or 네트워크 주소 확인 중), centred beside the tile, and nothing to press.
 */
@Composable private fun PairingColumn(info: SyncInfo, code: String?, headline: String, modifier: Modifier, qr: Boolean) {
    val title: @Composable () -> Unit = {
        Text(headline, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, style = MaterialTheme.typography.headlineSmall, color = Scene.text,
            maxLines = PAIRING_HEADLINE_LINES, overflow = TextOverflow.Ellipsis)
    }
    if (!info.running) Column(modifier, verticalArrangement = Arrangement.spacedBy(ATTENTION_LINE_GAP, Alignment.CenterVertically)) {
        title()
        CarLine(info.error ?: VehicleCopy.PHONE_ADDRESS_PENDING)
    } else {
        val shown = info.addresses.take(PAIRING_LINK_MAX_ENDPOINTS)
        val addresses = if (shown.isEmpty()) VehicleCopy.PHONE_ADDRESS_PENDING else shown.joinToString("  ·  ") { "$it:${info.port}" }
        val key = info.fingerprint.chunked(8).joinToString(" ")
        val reason = if (info.pairedPhones > 0) VehicleCopy.PHONE_ANOTHER else VehicleCopy.PHONE_EXPIRED
        val digits = MaterialTheme.typography.titleMedium
        Column(modifier, verticalArrangement = Arrangement.SpaceBetween) {
            title()
            Column(Modifier.fillMaxWidth().padding(top = ATTENTION_BLOCK_GAP), verticalArrangement = Arrangement.spacedBy(PAIRING_LINE_GAP)) {
                PairingLine(VehicleCopy.PHONE_ADDRESS, addresses, VehicleCopy.SPOKEN_PHONE_ADDRESS)
                if (code != null) PairingLine(VehicleCopy.PHONE_CODE, pairingDisplay(code), VehicleCopy.PHONE_CODE, digits, spokenValue = code.toCharArray().joinToString(" "))
                else PairingLine(VehicleCopy.PHONE_CODE, VehicleCopy.PHONE_CODE_NONE, VehicleCopy.PHONE_CODE, digits, Scene.textMuted, spokenValue = reason)
                PairingLine(VehicleCopy.PHONE_KEY, key, VehicleCopy.SPOKEN_PHONE_KEY)
            }
            Box(Modifier.fillMaxWidth().padding(top = ATTENTION_BLOCK_GAP)) {
                // While the tile shows no QR the line says what brings one back, never to scan what is not there.
                ActionRow(start = { CarLine(VehicleCopy.pairingQrHint(qr, code != null)) }, actions = {
                    QuietAction(if (code == null) VehicleCopy.PHONE_NEW_CODE else VehicleCopy.PHONE_RENEW_CODE) { SyncServer.active?.refreshPairingCode() }
                })
            }
        }
    }
}

/**
 * One thing the phone types (2.15.1, §10): its name and the value on one baseline, in a slot [PAIRING_LINE] tall at least so the
 * three lines stand at one pitch; the value in [style] with tabular figures (bodyMedium; the code in titleMedium) and [color],
 * wrapping and never cut. The name's column is [PAIRING_LABEL], grown with a larger font. One stop for TalkBack,
 * "[spoken], [spokenValue]": the code is heard digit by digit, where "455 426" would be read as two numbers.
 */
@Composable private fun PairingLine(label: String, value: String, spoken: String, style: TextStyle = MaterialTheme.typography.bodyMedium,
    color: Color = Scene.text, spokenValue: String = value) {
    val name = PAIRING_LABEL * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(Modifier.fillMaxWidth().heightIn(min = PAIRING_LINE).clearAndSetSemantics { contentDescription = "$spoken, $spokenValue" },
        contentAlignment = Alignment.CenterStart) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, Modifier.width(name).alignByBaseline(), style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1)
            Text(value, Modifier.weight(1f).alignByBaseline(), style = style.copy(fontFeatureSettings = "tnum"), color = color)
        }
    }
}

// ───────────────────────── 월 마감 ─────────────────────────

/**
 * A month that passed without being closed: where it stands ([monthCloseHeadline]) over its completion bar, the first step
 * still open ([monthCloseTaskLine]), and the one thing to do next ([monthCloseNext]): 정리하기 opens 점검 필요 of that month,
 * 확인하기 turns the pager to 카드 확인 ([onReview]), and a ready month closes here with today's answer. The four steps are one
 * tap away (월간에서 보기).
 */
@Composable private fun MonthCloseBody(status: MonthStatus, today: LocalDate, act: Act, repository: TripLogRepository, nav: HomeNav, pager: AttentionPager?,
    onReview: () -> Unit) {
    val followUp = LocalFollowUp.current
    val name = monthLabel(status.month, today)
    val key = monthKey(status.month)
    val percent = status.percent
    val progress = sweepProgress((percent ?: 0) / 100f, key = "home-close-bar")
    val line = monthCloseTaskLine(status)
    AttentionCard(AttentionKind.MONTH_CLOSE, HomeCopy.CLOSE_TITLE, rememberVectorPainter(Icons.Outlined.CheckCircle), pager, monthCloseHeadline(status, today),
        tag = { CarTag("${status.trips}건") },
        under = {
            Spacer(Modifier.height(CLOSE_BAR_GAP))
            LinearProgressIndicator(progress = { progress.value }, modifier = Modifier.fillMaxWidth().height(CLOSE_BAR)
                .semantics { contentDescription = "완성률 ${percent ?: 0}퍼센트" }, color = Scene.positive, trackColor = Scene.ringTrack, drawStopIndicator = {})
        },
        lines = listOf(line),
        start = { CarLink(HomeCopy.CLOSE_OPEN_REPORT, { nav.openReport(status.month) }) },
        actions = {
            when (monthCloseNext(status)) {
                MonthCloseNext.TIDY -> PrimaryAction(HomeCopy.CLOSE_TIDY) { nav.openLog(1, status.month) }
                MonthCloseNext.REVIEW -> PrimaryAction(HomeCopy.CLOSE_REVIEW, onClick = onReview)
                MonthCloseNext.CLOSE -> LoadingAction("$name 마감", "마감 중…", act, enabled = status.ready) {
                    repository.closeMonth(key)
                    followUp(FollowUp("${name}을 마감했어요", "마감 해제") { repository.reopenMonth(key) })
                }
            }
        })
}

/**
 * This month's close reminder from the 25th ([monthCloseBannerText], the real days left and the real count), with the same
 * way on as the month-close card ([monthCloseNext]): 정리하기 opens 점검 필요 of that month, 확인하기 turns the pager to
 * 카드 확인 ([onReview]), 마감하기 opens 월간·Excel; 닫기 hides it for this month.
 */
@Composable private fun CloseReminderBody(status: MonthStatus, today: LocalDate, nav: HomeNav, pager: AttentionPager?, onReview: () -> Unit,
    onClose: () -> Unit) {
    AttentionCard(AttentionKind.CLOSE_REMINDER, HomeCopy.CLOSE_TITLE, rememberVectorPainter(Icons.Outlined.CheckCircle), pager, monthCloseBannerText(status, today),
        live = true, tag = { CarTag(HomeCopy.CLOSE_REMINDER_TAG) },
        actions = {
            QuietAction(HomeCopy.CLOSE, description = HomeCopy.SPOKEN_REMINDER_CLOSE, onClick = onClose)
            when (monthCloseNext(status)) {
                MonthCloseNext.TIDY -> PrimaryAction(HomeCopy.CLOSE_TIDY) { nav.openLog(1, status.month) }
                MonthCloseNext.REVIEW -> PrimaryAction(HomeCopy.CLOSE_REVIEW, onClick = onReview)
                MonthCloseNext.CLOSE -> PrimaryAction(HomeCopy.CLOSE_REMINDER_CLOSE) { nav.openReport(status.month) }
            }
        })
}

// ───────────────────────── 차량 관리 ─────────────────────────

/**
 * The most urgent unsnoozed deadline: what is due and how far away (an overdue headline in the negative), what 완료 writes
 * ([careTaskNote], the long guidance for TalkBack), and two buttons. 7일 뒤 다시 answers with its undo. 완료: a service adds
 * an oil and consumables record (which moves the service clock); an inspection adds its record and clears the date; insurance
 * and a lease clear theirs. Each answer offers 실행 취소, and those that cleared a date also 새 기한 입력 (설정 › 법인·차량).
 */
@Composable private fun CareBody(state: TripLogState, now: Long, act: Act, repository: TripLogRepository, snoozes: MutableState<Set<String>>, nav: HomeNav,
    pager: AttentionPager?) {
    val context = LocalContext.current
    val followUp = LocalFollowUp.current
    val care = state.data.care
    val (odometer, _) = careOdometer(state, now)
    val alert = careDueAlerts(care, odometer, localDate(now), snoozes.value).firstOrNull() ?: return
    val overdue = alert.level == CareLevel.OVERDUE
    val (note, spoken) = careTaskNote(alert.kind)
    val newDue = FollowUpAction(HomeCopy.CARE_NEW_DUE) { nav.goSettings(0) }
    AttentionCard(AttentionKind.CARE, HomeCopy.CARE_TITLE, painterResource(R.drawable.ic_calendar), pager, alert.title,
        tag = { CarTag(careLevelState(alert.level), tone = if (overdue) PillTone.NEGATIVE else null) },
        headlineColor = if (overdue) Scene.negative else Scene.text, lines = listOf(HomeLine(alert.detail)),
        start = { CarLine(note, spoken = spoken) },
        actions = {
            QuietAction(HomeCopy.CARE_SNOOZE) {
                val key = careSnoozeKey(alert, care)
                snoozes.value = snoozeCare(context, key, now)
                followUp(FollowUp(HomeCopy.CARE_SNOOZED, HomeCopy.UNDO) { snoozes.value = unsnoozeCare(context, key) })
            }
            PrimaryAction(careDoneLabel(alert.kind)) {
                act {
                    when (alert.kind) {
                        CareKind.SERVICE -> {
                            val record = repository.addMaintenance(MaintenanceKind.OIL, 0, null, "정비 완료", odometer)
                            val interval = care.serviceIntervalKm?.let { " · 다음 정비까지 ${"%,d".format(it)} km" }.orEmpty()
                            followUp(FollowUp("정비 완료로 기록했어요$interval", HomeCopy.UNDO) { repository.removeMaintenance(record.id); repository.updateCare(care) })
                        }
                        CareKind.INSPECTION -> {
                            val record = repository.addMaintenance(MaintenanceKind.INSPECTION, 0, null, "정기검사 완료", odometer)
                            repository.updateCare(care.copy(inspectionDue = null))
                            followUp(FollowUp("검사 완료로 기록했어요", HomeCopy.UNDO, second = newDue) { repository.removeMaintenance(record.id); repository.updateCare(care) })
                        }
                        CareKind.INSURANCE -> {
                            repository.updateCare(care.copy(insuranceDue = null))
                            followUp(FollowUp("보험 만기일을 지웠어요", HomeCopy.UNDO, second = newDue) { repository.updateCare(care) })
                        }
                        CareKind.LEASE -> {
                            repository.updateCare(care.copy(leaseEnd = null))
                            followUp(FollowUp("리스·렌트 만기일을 지웠어요", HomeCopy.UNDO, second = newDue) { repository.updateCare(care) })
                        }
                    }
                }
            }
        })
}

// ───────────────────────── 충전 비용 (카드 승인 대기) ─────────────────────────

/**
 * A charge whose question waits for the card approval ([hiddenPendingCharge]): the car says it is waiting and when it will
 * ask ([chargeWaitingShort]; TalkBack hears [chargeWaitingLine]), offers 지금 입력, and while automatic completion is on shows
 * what it filled in by itself this month.
 */
@Composable private fun ChargeWaitingBody(data: TripLogData, now: Long, waiting: ChargeSession, prompt: MutableState<ChargePrompt>, pager: AttentionPager?) {
    val today = localDate(now)
    val summary = if (data.profile.autopilot) autopilotSummary(data, today.withDayOfMonth(1), today, zone) else null
    AttentionCard(AttentionKind.CHARGE_WAITING, "${if (waiting.fuel) "주유" else "충전"} 비용", painterResource(R.drawable.ic_won), pager, HomeCopy.WAITING_HEADLINE,
        live = true, tag = { CarTag(HomeCopy.WAITING_TAG) },
        lines = listOf(HomeLine(chargeWaitingShort(waiting, prompt.value, zone), chargeWaitingLine(waiting, prompt.value, zone))),
        body = summary?.let { s -> {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CarMiniStat(painterResource(R.drawable.ic_report), HomeCopy.WAITING_MONTH, s.percent?.let { "$it%" } ?: "—", Modifier.weight(1f))
                CarMiniStat(painterResource(R.drawable.ic_journal), HomeCopy.WAITING_AUTO_PURPOSE, "${s.autoPurpose}건", Modifier.weight(1f), muted = s.autoPurpose == 0)
                CarMiniStat(painterResource(R.drawable.ic_receipt), HomeCopy.WAITING_AUTO_CARD, "${s.cardMatched}건 · ${formatWon(s.cardWon)}", Modifier.weight(1f),
                    muted = s.cardMatched == 0)
            }
        } },
        actions = { QuietAction(HomeCopy.WAITING_NOW) { prompt.value = ChargePrompt.NOW } })
}
