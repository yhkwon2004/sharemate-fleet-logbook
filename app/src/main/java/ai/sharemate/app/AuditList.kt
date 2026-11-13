package ai.sharemate.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp

/*
 * 검토's 증빙 준비 상태 as a list of what is left (2.15, docs/design/DESIGN_2_15.md §5.2). The card said "확인이 필요한 항목
 * 7개" and sent the driver to an Excel sheet to find out which. Under its headline it now lists the kinds of issue the period
 * has, the ones a close needs first, in the order 운행기록's 점검 필요 lists them ([issueOrder], [issueRequired]); under 월간
 * a row opens 점검 필요 on the month. The issues are 점검 필요's own ([periodAudit]), so the card and the list it opens agree.
 */

/** One kind of issue in a period and how many of it ([auditBreakdown]); [required] kinds keep the month from closing. */
internal data class AuditKind(val type: IssueType, val count: Int) {
    val required: Boolean get() = issueRequired(type)
}

/**
 * The issues 점검 필요 lists for the trips of a period (pure, unit-tested): the whole log [all] is audited, as 운행기록 and
 * 홈's count do, then only the issues of the [period]'s trips are kept. So a stretch driven unrecorded before the period's
 * first trip counts here too; auditing the period alone would miss it, and the row that opens 점검 필요 would say one less.
 */
internal fun periodAudit(all: List<Trip>, period: List<Trip>): List<TripIssue> {
    if (period.isEmpty()) return emptyList()
    val ids = period.mapTo(HashSet()) { it.id }
    return auditTrips(all).filter { it.tripId in ids }
}

/**
 * What is left in a period, kind by kind (pure, unit-tested): the kinds of [issues] with their counts, in the order 점검 필요
 * lists the issues ([issueOrder] over [trips]): the two kinds a close needs first, then the rest, each part led by the kind of
 * its newest issue. Without [trips] the order within each part is the order of [issues]. Empty for no issues.
 */
internal fun auditBreakdown(issues: List<TripIssue>, trips: Map<String, Trip> = emptyMap()): List<AuditKind> {
    val ordered = issueOrder(issues, trips)
    return ordered.map { it.type }.distinct().map { type -> AuditKind(type, ordered.count { it.type == type }) }
}

/** A kind's count at the row's end (pure): "3건". */
internal fun auditCountText(count: Int): String = "${count}건"

/** What TalkBack says for a kind's row, its one stop (pure): "목적 미분류 3건, 마감 전 필수". */
internal fun auditKindSpoken(kind: AuditKind): String =
    "${kind.type.label} ${auditCountText(kind.count)}, ${if (kind.required) LogCopy.REQUIRED else LogCopy.REFERENCE}"

/**
 * 증빙 준비 상태's rows (spec §5.2), under the card's headline: a [CarRow] per kind of [kinds] ([auditBreakdown]), hairlines
 * between, each with 점검 필요's negative dot, the kind's label, 마감 전 필수 or 참고 as its line and the count at the end
 * ("3건", titleMedium with tabular digits). With [onOpen] (월간) a row is a button with a chevron that settles to .98 under the
 * press fill and opens 운행기록 › 점검 필요 on the month; without it (연간) the rows are not buttons. With nothing left, one
 * row: 모든 운행이 확인됐어요 after the positive check. TalkBack hears one stop per row ([auditKindSpoken]).
 */
@Composable internal fun AuditRows(kinds: List<AuditKind>, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    if (kinds.isEmpty()) {
        CarRow(ReportCopy.AUDIT_ALL_CLEAR, modifier, lead = {
            Icon(Icons.Outlined.CheckCircle, contentDescription = null, Modifier.size(24.dp), tint = Scene.positive)
        })
        return
    }
    Column(modifier.fillMaxWidth()) {
        kinds.forEachIndexed { index, kind ->
            if (index > 0) CarDivider()
            AuditKindRow(kind, onOpen)
        }
    }
}

/** One kind of [AuditRows]: a [CarRow] that speaks as one ([auditKindSpoken]), a button with a chevron when [onOpen] is set. */
@Composable private fun AuditKindRow(kind: AuditKind, onOpen: (() -> Unit)?) {
    val interaction = remember { MutableInteractionSource() }
    val spoken = auditKindSpoken(kind)
    // The row's words, count and chevron are drawn only: the row itself is the one stop, the click kept outside the clear.
    val stop = if (onOpen == null) Modifier.clearAndSetSemantics { contentDescription = spoken }
        else Modifier.pressable(interaction, .98f).clip(RoundedCornerShape(14.dp))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClickLabel = ReportCopy.AUDIT_ROW_ACTION, onClick = onOpen)
            .clearAndSetSemantics { contentDescription = spoken }
            .pressFill(interaction)
    CarRow(kind.type.label, stop, line = if (kind.required) LogCopy.REQUIRED else LogCopy.REFERENCE,
        lead = { Box(Modifier.size(12.dp).clip(CircleShape).background(Scene.negative)) },
        trailing = {
            Text(auditCountText(kind.count), style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"), color = Scene.text, maxLines = 1)
            if (onOpen != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, Modifier.size(24.dp), tint = Scene.textSecondary)
        })
}
