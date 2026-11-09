package ai.sharemate.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** The row-number column of the preview table. */
private val ROW_NUMBER_WIDTH = 48.dp

/** The Excel view's measure on the car (spec B): the preview, its sheet tabs and the save bar under it, like the month-close card. */
internal val EXCEL_VIEW_MAX_WIDTH = 760.dp

/** Column widths of the preview table: the remarks column (last) widest, date columns 180, the rest 170 (136 on the phone). */
internal fun excelColumnWidths(columns: List<String>, compact: Boolean): List<Dp> = columns.mapIndexed { index, title -> when {
    index == columns.lastIndex -> if (compact) 260.dp else 360.dp
    title.contains("일시") || title.contains("일자") -> 180.dp
    else -> if (compact) 136.dp else 170.dp
} }

/** A cell as the preview prints it: thousands separators, one decimal for distances, the text as is. */
internal fun previewCell(value: Any): String = when (value) {
    is Double -> String.format(Locale.KOREA, "%,.1f", value)
    is Float -> String.format(Locale.KOREA, "%,.1f", value)
    is Long -> String.format(Locale.KOREA, "%,d", value)
    is Int -> String.format(Locale.KOREA, "%,d", value)
    else -> value.toString()
}

/**
 * A read-only data preview of the same five sheets that Excel export writes (운행·직원·경비·차량관리·확인). The sheets are
 * text tabs ([SheetTabs], max 760), a lighter level than the view tabs above the preview. The table keeps its row number and
 * first column pinned while the rest scrolls sideways (spec B); a row opens its full contents.
 */
@Composable
internal fun ExcelPreview(data: TripLogData, trips: List<Trip>, from: LocalDate, to: LocalDate,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    var sheetIndex by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Pair<Int, List<Any>>?>(null) }
    val result by produceState<Result<List<LogbookSheet>>?>(null, data.profile, trips, data.drivers, data.maintenance, data.care, from, to) {
        value = null
        value = try { Result.success(withContext(Dispatchers.Default) { logbookSheets(data.profile, from, to, trips, data.drivers, zone, data.maintenance, data.care) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    LaunchedEffect(data.profile, trips, data.drivers, from, to) { page = 0; detail = null }
    val closed = remember(data.closedMonths, data.profile.demoMode, from, to) { closedMonthFor(data, from, to) }
    val colors = MaterialTheme.colorScheme
    // Touch targets follow the device, not the width: 56 dp on the car (a narrow car window too), 48 on the phone.
    val tapHeight = if (LocalLayout.current.startAnchored) 56.dp else 48.dp
    BoxWithConstraints(modifier) {
    val rowsPerPage = ((maxHeight.value - if (compact) 340 else 470) / if (compact) 64 else 72).toInt().coerceIn(1, if (compact) 3 else 6)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("업무용승용차 운행기록부", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The previewed month is closed: say so where the file name would, never as a lock.
                closed?.let { ClosedChip(it) }
            }
            Text(if (data.profile.demoMode) "시연 데이터 · 제출용이 아닙니다" else "${data.profile.companyName.ifBlank { "법인명 미입력" }} · ${data.profile.plateNumber.ifBlank { "차량번호 미입력" }}",
                style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SheetTabs(listOf("운행", "직원", "경비", "차량", "확인"), sheetIndex, Modifier.widthIn(max = EXCEL_VIEW_MAX_WIDTH)) { sheetIndex = it; page = 0; detail = null }
        when {
            result == null -> SkeletonTable(columns = if (compact) 4 else 6, rows = rowsPerPage, compact = compact)
            result!!.isFailure -> Panel { Text("미리보기를 만들지 못했어요"); Muted("기록은 변경하지 않았습니다. 조회 기간을 다시 선택해 주세요.") }
            else -> {
                val sheet = result!!.getOrThrow()[sheetIndex]
                val pageSize = rowsPerPage
                val pages = maxOf(1, (sheet.rows.size + pageSize - 1) / pageSize)
                val currentPage = page.coerceIn(0, pages - 1)
                val start = currentPage * pageSize
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sheet.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${sheet.rows.size}행", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                }
                if (sheet.rows.isEmpty()) Panel(padding = 24.dp) {
                    Text(when (sheetIndex) { 2 -> "등록된 경비가 없어요"; 3 -> "정비 기록과 기한이 없어요"; else -> "이 범위의 기록이 없어요" }, style = MaterialTheme.typography.titleMedium)
                    Muted("조회 기간과 직원 선택을 확인하세요. 저장 시에도 빈 내역으로 표시됩니다.")
                } else {
                    val widths = excelColumnWidths(sheet.columns, compact)
                    // One scroll state for every row keeps the scrolling part of the table in step while the first column stays put.
                    val sideways = rememberScrollState()
                    Surface(shape = RoundedCornerShape(18.dp), border = Scene.edge, color = colors.surface, contentColor = Scene.text) {
                        Column {
                            PreviewRow("행", sheet.columns, widths, sideways, header = true, compact = compact)
                            sheet.rows.subList(start, minOf(start + pageSize, sheet.rows.size)).forEachIndexed { offset, row ->
                                val number = start + offset + 6
                                HorizontalDivider(color = Scene.divider)
                                PreviewRow(number.toString(), row.map(::previewCell), widths, sideways, header = false, compact = compact,
                                    zebra = offset % 2 == 1, numeric = row.map { it is Number }, clickLabel = "${number}행 전체 내용 보기") { detail = number to row }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0, modifier = Modifier.heightIn(min = tapHeight)) { Text("‹ 이전") }
                        Text("${currentPage + 1} / $pages", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage + 1 < pages, modifier = Modifier.heightIn(min = tapHeight)) { Text("다음 ›") }
                    }
                    Text("가로로 더 보기 · 행을 누르면 전체 내용 표시", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                }
                Text("실제 저장 데이터 · Excel 인쇄 배치와 다를 수 있어요. 증빙·세무 요건은 제출 전 별도로 확인하세요.", style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary)
                detail?.let { (number, row) -> AlertDialog(onDismissRequest = { detail = null }, title = { Text("${sheet.name} · ${number}행") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { index, cell -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(sheet.columns[index], style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(previewCell(cell).ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
                        } }
                    } }, confirmButton = { TextButton(onClick = { detail = null }, modifier = Modifier.heightIn(min = tapHeight)) { Text("닫기") } }) }
            }
        }
    }
    }
}

/**
 * The preview's sheet tabs, one level under the 월간·Excel view tabs: plain text tabs (56 dp on the car, 48 on the phone) on a
 * hairline instead of a glass pill. The chosen sheet's label takes the glow colour over a 3-dp glow underline (a shape, so
 * never colour alone) that glides to a new sheet in 180 ms, a snap under reduced motion. Cap the width with [modifier].
 */
@Composable private fun SheetTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val car = LocalLayout.current.startAnchored
    val bounds = rememberItemBounds()
    val reduce = LocalReduceMotion.current
    val hairline = Scene.divider
    Box(modifier.fillMaxWidth().drawBehind {
        val stroke = 1.dp.toPx()
        drawRect(hairline, Offset(0f, size.height - stroke), Size(size.width, stroke))
    }) {
        Row(Modifier.horizontalScroll(rememberScrollState()).underlineGlide(bounds, selected), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (active) Scene.glow else Scene.textSecondary, if (reduce) snap() else tween(180, easing = FastOutSlowInEasing), label = "sheet-tab")
                Box(Modifier.reportBounds(bounds, index).pressable(interaction, .98f).heightIn(min = if (car) 56.dp else 48.dp).widthIn(min = 72.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { this.selected = active; role = Role.Tab }
                    .clickable(interactionSource = interaction, indication = if (car) null else LocalIndication.current) { onSelect(index) }
                    .padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/**
 * The chosen sheet's underline: 3 dp of glow along the bottom of its tab (inset 16 dp a side), gliding to a new tab in
 * [durationMs] (FastOutSlowIn); the first placement and reduced motion snap. Apply it to the tab row itself, so the drawing
 * and the bounds the tabs report share one coordinate space.
 */
@Composable private fun Modifier.underlineGlide(bounds: ItemBounds, selected: Int, durationMs: Int = 180): Modifier {
    val reduce = LocalReduceMotion.current
    val color = Scene.glow
    val target = bounds[selected]
    val rect = remember { Animatable(Rect.Zero, Rect.VectorConverter) }
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduce) {
        val next = target ?: return@LaunchedEffect
        if (!placed || reduce) { rect.snapTo(next); placed = true } else rect.animateTo(next, tween(durationMs, easing = FastOutSlowInEasing))
    }
    val density = LocalDensity.current
    val thickness = with(density) { 3.dp.toPx() }
    val inset = with(density) { 16.dp.toPx() }
    val visible = target != null
    return drawBehind {
        if (!placed || !visible) return@drawBehind
        val r = rect.value
        val width = (r.width - inset * 2).coerceAtLeast(thickness * 4)
        drawRoundRect(color, Offset(r.center.x - width / 2, r.bottom - thickness), Size(width, thickness), CornerRadius(thickness / 2, thickness / 2))
    }
}

/**
 * One table row: the row number and the first column pinned, the other cells in a row that scrolls sideways on the shared
 * [sideways] state. A data row is a 72-dp (64 when [compact]) button that opens its full contents, pressed with the car's
 * scale and no ripple on the car (a narrow car window too), or the phone's ripple.
 */
@Composable private fun PreviewRow(number: String, cells: List<String>, widths: List<Dp>, sideways: ScrollState, header: Boolean, compact: Boolean,
    zebra: Boolean = false, numeric: List<Boolean> = emptyList(), clickLabel: String? = null, onClick: (() -> Unit)? = null) {
    val source = remember { MutableInteractionSource() }
    val car = LocalLayout.current.startAnchored
    val rowHeight = if (compact) 64.dp else 72.dp
    val fill = when { header -> Scene.rail; zebra -> Scene.fill; else -> Color.Transparent }
    val divider = Scene.divider
    val base = Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = if (header) 0.dp else rowHeight).background(fill)
    val tappable = when {
        onClick == null -> base
        !car -> base.pressable(source, .97f).clickable(interactionSource = source, indication = LocalIndication.current, onClickLabel = clickLabel, role = Role.Button, onClick = onClick)
        else -> base.carTap(source, label = clickLabel, onClick = onClick)
    }
    val cellStyle = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium
    val cell: @Composable (Int, String) -> Unit = { index, text ->
        Text(text, Modifier.width(widths[index]).padding(12.dp), style = cellStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = Scene.text, textAlign = if (!header && numeric.getOrNull(index) == true) TextAlign.End else TextAlign.Start)
    }
    Row(tappable, verticalAlignment = Alignment.CenterVertically) {
        Text(number, Modifier.width(ROW_NUMBER_WIDTH), style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelSmall,
            color = Scene.textSecondary, textAlign = TextAlign.Center)
        if (cells.isNotEmpty()) cell(0, cells[0])
        Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
        Row(Modifier.weight(1f).horizontalScroll(sideways), verticalAlignment = Alignment.CenterVertically) {
            cells.drop(1).forEachIndexed { offset, text -> cell(offset + 1, text) }
        }
    }
}
