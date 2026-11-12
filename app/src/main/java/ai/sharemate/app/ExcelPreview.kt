package ai.sharemate.app

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
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

/**
 * The Excel view's measure on the car: none since 2.11. The preview, its sheet tabs and the save bar fill the page column
 * like the month-close card, so a wide window shows more of the sheet's columns before it scrolls sideways.
 */
internal val EXCEL_VIEW_MAX_WIDTH = Dp.Infinity

/**
 * The car's Excel preview floor as 2.11 drew it: the preview alone, with its one-line note. Since 2.12 the preview sits in a
 * card over an ⓘ row, and [excelPreviewHeight] adds their room on top; a shorter pane scrolls further instead of squeezing
 * the table.
 */
internal val EXCEL_PREVIEW_MIN_HEIGHT = 480.dp

/** The most rows one page holds when the table pages to fit its height (the skeleton draws at most as many). */
internal const val PREVIEW_MAX_ROWS = 8

/**
 * How many rows one page of the preview table holds. Given the table's own height ([tableHeight]: its room under the sheet
 * name, the pager and the hint taken off), the rows that fit under its header: rows of up to two lines (72 dp, 64 when
 * [compact], taller at a large font) with their hairlines, at least one and at most [PREVIEW_MAX_ROWS]. Without a definite
 * height (null or unbounded: a preview that wraps its content in a page that scrolls) a fixed page, 3 compact and 6
 * otherwise, so the count never rests on an infinite height.
 */
internal fun previewRowsPerPage(tableHeight: Dp?, compact: Boolean, fontScale: Float = 1f): Int {
    if (tableHeight == null || !tableHeight.value.isFinite()) return if (compact) 3 else 6
    val scale = fontScale.coerceAtLeast(1f)
    // The header row: up to two lines of labelLarge (20 sp) in 12 dp of padding, and the card's 1-dp edge above and below.
    val header = 24.dp + 40.dp * scale + 2.dp
    // A data row: its minimum or two lines of bodyMedium (20 sp) in 12 dp of padding, whichever is taller, and its divider.
    val row = maxOf(if (compact) 64.dp else 72.dp, 24.dp + 40.dp * scale) + 1.dp
    return ((tableHeight - header) / row).toInt().coerceIn(1, PREVIEW_MAX_ROWS)
}

/**
 * How many ruled, empty rows the preview draws under an empty sheet's header row (2.15, docs/design/DESIGN_2_15.md §2.3; pure,
 * unit-tested), so the form the file will hold shows before the first trip. None on the phone ([car] false): its preview does
 * not change. On the car [PREVIEW_MAX_ROWS] (eight) at the data rows' height, as many of them as [room] holds under the header,
 * counted as [previewRowsPerPage] counts a page (a 66-dp header, 72 + 1 dp a row, 64 + 1 when [compact], taller at a large
 * font), with no pager or hint to leave room for. A room without one row draws no table; without a definite height, all eight.
 */
internal fun emptySheetRows(room: Dp?, compact: Boolean, fontScale: Float = 1f, car: Boolean = true): Int {
    if (!car) return 0
    if (room == null || !room.value.isFinite()) return PREVIEW_MAX_ROWS
    val scale = fontScale.coerceAtLeast(1f)
    val header = 24.dp + 40.dp * scale + 2.dp
    val row = maxOf(if (compact) 64.dp else 72.dp, 24.dp + 40.dp * scale) + 1.dp
    return ((room - header) / row).toInt().coerceIn(0, PREVIEW_MAX_ROWS)
}

/** What the pager and the hint take under the table on its page: two 12-dp gaps, the pager's touch target and one labelMedium line. */
internal fun previewPagerHeight(tapHeight: Dp, fontScale: Float = 1f): Dp = 12.dp + tapHeight + 12.dp + 16.dp * fontScale.coerceAtLeast(1f)

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
 * text tabs ([SheetTabs]), a lighter level than the view tabs above the preview. The table keeps its row number and
 * first column pinned while the rest scrolls sideways (spec B); a row opens its full contents.
 *
 * The preview never scrolls vertically itself; the page around it does. With [fillHeight] (the car's 월간·Excel) [modifier]
 * gives it a definite height and the table pages to the rows that fit its own room ([previewRowsPerPage]); otherwise (the
 * phone's 현황 card) it wraps its content and pages a fixed count, so an empty sheet leaves no blank under it.
 *
 * On the car (2.12, [isCar]) the preview sits in its own card, so it speaks the car's one line: the closed month as a tag, an
 * empty sheet or a failed build as a title over one line instead of a nested panel, and the chosen sheet in the accent ink.
 * The caveat leaves the preview for the card's ⓘ row under it, so its long form never takes the table's room. Since 2.15 an
 * empty sheet on the car also draws the sheet's header row and ruled, empty rows under its two lines ([EmptySheet]), so the
 * preview of an empty month is the form, not a hollow card. The phone draws it as before.
 */
@Composable
internal fun ExcelPreview(data: TripLogData, trips: List<Trip>, from: LocalDate, to: LocalDate,
    modifier: Modifier = Modifier, compact: Boolean = false, fillHeight: Boolean = false) {
    val car = isCar()
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
    val fontScale = LocalDensity.current.fontScale
    val columns = if (compact) 4 else 6
    Column(modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("업무용승용차 운행기록부", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The previewed month is closed: say so where the file name would, never as a lock.
                closed?.let { if (car) CarTag("마감됨 · ${closedDay(it.closedAt)}", tone = PillTone.POSITIVE) else ClosedChip(it) }
            }
            Text(if (data.profile.demoMode) (if (car) ReportCopy.PREVIEW_DEMO else "시연 데이터 · 제출용이 아닙니다")
                else "${data.profile.companyName.ifBlank { "법인명 미입력" }} · ${data.profile.plateNumber.ifBlank { "차량번호 미입력" }}",
                style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        SheetTabs(listOf("운행", "직원", "경비", "차량", "확인"), sheetIndex, Modifier.widthIn(max = EXCEL_VIEW_MAX_WIDTH)) { sheetIndex = it; page = 0; detail = null }
        val sheets = result
        when {
            // The skeleton holds the table's place: in a definite height it takes the room the table will get (its activity line
            // taken off), otherwise one fixed page. Content shorter than a definite height leaves the slack at the bottom.
            sheets == null -> if (fillHeight) BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                SkeletonTable(columns, rows = previewRowsPerPage(maxHeight - 12.dp - 16.dp * fontScale.coerceAtLeast(1f), compact, fontScale), compact = compact)
            } else SkeletonTable(columns, rows = previewRowsPerPage(null, compact), compact = compact)
            sheets.isFailure -> if (car) PreviewNotice(ReportCopy.PREVIEW_FAILED, ReportCopy.PREVIEW_FAILED_LINE)
                else Panel { Text("미리보기를 만들지 못했어요"); Muted("기록은 변경하지 않았습니다. 조회 기간을 다시 선택해 주세요.") }
            else -> {
                val sheet = sheets.getOrThrow()[sheetIndex]
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sheet.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${sheet.rows.size}행", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                }
                val emptyTitle = when (sheetIndex) { 2 -> "등록된 경비가 없어요"; 3 -> "정비 기록과 기한이 없어요"; else -> "이 범위의 기록이 없어요" }
                if (sheet.rows.isEmpty()) {
                    if (car) {
                        PreviewNotice(emptyTitle, ReportCopy.PREVIEW_EMPTY_LINE)
                        // 2.15: under the two lines, the sheet's header row and its ruled, empty rows in the room the table would get.
                        if (fillHeight) BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                            EmptySheet(sheet.columns, emptySheetRows(maxHeight, compact, fontScale), compact)
                        } else EmptySheet(sheet.columns, emptySheetRows(null, compact), compact)
                    }
                    else Panel(padding = 24.dp) {
                        Text(emptyTitle, style = MaterialTheme.typography.titleMedium)
                        Muted("조회 기간과 직원 선택을 확인하세요. 저장 시에도 빈 내역으로 표시됩니다.")
                    }
                } else {
                    val showPage: @Composable (Int) -> Unit = { rowsPerPage ->
                        SheetPage(sheet, rowsPerPage, page, compact, tapHeight, onPage = { page = it }) { number, row -> detail = number to row }
                    }
                    // In a definite height (the car's) the page gets what the rest of the preview leaves, and the table what the
                    // pager and the hint leave of that; the page wraps its rows, so any slack stays at the bottom of the preview.
                    if (fillHeight) BoxWithConstraints(Modifier.weight(1f, fill = false).fillMaxWidth()) {
                        showPage(previewRowsPerPage(maxHeight - previewPagerHeight(tapHeight, fontScale), compact, fontScale))
                    } else showPage(previewRowsPerPage(null, compact))
                }
                // The car's caveat is its card's ⓘ row under the preview (ReportPane), where opening it lengthens the card
                // instead of taking the table's room; the phone keeps its line here.
                if (!car) Text("실제 저장 데이터 · Excel 인쇄 배치와 다를 수 있어요. 증빙·세무 요건은 제출 전 별도로 확인하세요.", style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary)
                // A row's full contents. The car's is its own dialog (2.13, [CarDialog], read only): the cells scroll in what the
                // title and 닫기 leave, so the button stays whole on a short window. The phone keeps Material's.
                detail?.let { (number, row) ->
                    val title = "${sheet.name} · ${number}행"
                    if (car) CarDialog(title, { detail = null }, icon = painterResource(R.drawable.ic_report), body = {
                        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEachIndexed { index, cell -> Column(Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(sheet.columns[index], style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
                                Text(previewCell(cell).ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum"), color = Scene.text)
                            } }
                        }
                    }) { QuietAction("닫기") { detail = null } }
                    else AlertDialog(onDismissRequest = { detail = null }, title = { Text(title) },
                        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEachIndexed { index, cell -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                Text(sheet.columns[index], style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                                Text(previewCell(cell).ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
                            } }
                        } }, confirmButton = { DialogButton("닫기") { detail = null } })
                }
            }
        }
    }
}

/**
 * The car's word in place of the table (2.12): a [title] over one [line] ([CarLine]), straight on the preview's card, where
 * the phone nests a panel: an empty sheet, or a preview that could not be built.
 */
@Composable private fun PreviewNotice(title: String, line: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Scene.text)
        CarLine(line)
    }
}

/**
 * The car's empty sheet (2.15, §2.3): the table's frame with the sheet's [columns] as its header row and [rows] ruled, empty rows
 * ([emptySheetRows]) numbered from the sheet's first data row, at the data rows' height and zebra, scrolling sideways together
 * as the data table does, so the form the file will hold is visible before the first trip. Not buttons: TalkBack hears the
 * header row as one stop (the columns the file will hold) and skips the empty rows. No rows, no table.
 */
@Composable private fun EmptySheet(columns: List<String>, rows: Int, compact: Boolean) {
    if (rows <= 0) return
    val widths = excelColumnWidths(columns, compact)
    val sideways = rememberScrollState()
    val blank = List(columns.size) { "" }
    Surface(shape = RoundedCornerShape(18.dp), border = Scene.edge, color = MaterialTheme.colorScheme.surface, contentColor = Scene.text) {
        Column {
            Box(Modifier.semantics(mergeDescendants = true) {}) { PreviewRow("행", columns, widths, sideways, header = true, compact = compact) }
            Column(Modifier.clearAndSetSemantics {}) {
                repeat(rows) { offset ->
                    HorizontalDivider(color = Scene.divider)
                    PreviewRow((offset + 6).toString(), blank, widths, sideways, header = false, compact = compact, zebra = offset % 2 == 1)
                }
            }
        }
    }
}

/**
 * One page of a sheet: the table with [rowsPerPage] rows under its header (row number and first column pinned, the other
 * columns scrolling sideways together), then the pager and the hint. [page] is held to the sheet's pages; [onRow] opens a
 * row's full contents.
 */
@Composable private fun SheetPage(sheet: LogbookSheet, rowsPerPage: Int, page: Int, compact: Boolean, tapHeight: Dp, onPage: (Int) -> Unit,
    modifier: Modifier = Modifier, onRow: (Int, List<Any>) -> Unit) {
    val pages = maxOf(1, (sheet.rows.size + rowsPerPage - 1) / rowsPerPage)
    val currentPage = page.coerceIn(0, pages - 1)
    val start = currentPage * rowsPerPage
    val widths = excelColumnWidths(sheet.columns, compact)
    // One scroll state for every row keeps the scrolling part of the table in step while the first column stays put.
    val sideways = rememberScrollState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(18.dp), border = Scene.edge, color = MaterialTheme.colorScheme.surface, contentColor = Scene.text) {
            Column {
                PreviewRow("행", sheet.columns, widths, sideways, header = true, compact = compact)
                sheet.rows.subList(start, minOf(start + rowsPerPage, sheet.rows.size)).forEachIndexed { offset, row ->
                    val number = start + offset + 6
                    HorizontalDivider(color = Scene.divider)
                    PreviewRow(number.toString(), row.map(::previewCell), widths, sideways, header = false, compact = compact,
                        zebra = offset % 2 == 1, numeric = row.map { it is Number }, clickLabel = "${number}행 전체 내용 보기") { onRow(number, row) }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { onPage(currentPage - 1) }, enabled = currentPage > 0, modifier = Modifier.heightIn(min = tapHeight)) { Text("‹ 이전") }
            Text("${currentPage + 1} / $pages", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { onPage(currentPage + 1) }, enabled = currentPage + 1 < pages, modifier = Modifier.heightIn(min = tapHeight)) { Text("다음 ›") }
        }
        Text("가로로 더 보기 · 행을 누르면 전체 내용 표시", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary)
    }
}

/** The width [count] sheet tabs need at their natural size: 72 dp each with 4 dp between them. */
internal fun sheetTabsNaturalWidth(count: Int): Dp = 72.dp * count + 4.dp * (count - 1).coerceAtLeast(0)

/**
 * The preview's sheet tabs, one level under the 월간·Excel view tabs: plain text tabs (56 dp on the car, 48 on the phone) on a
 * hairline instead of a glass pill. The chosen sheet's label takes the glow colour over the glow underline of [selectionGlide]
 * ([GlideMark.UNDERLINE]: a shape, so never colour alone) that glides to a new sheet in 180 ms, a snap under reduced motion.
 * On the car (2.12) both take the accent ink instead, AA on the preview's card (4.93 / 7.66). Cap the width with [modifier].
 */
@Composable private fun SheetTabs(labels: List<String>, selected: Int, modifier: Modifier = Modifier, onSelect: (Int) -> Unit) {
    val car = LocalLayout.current.startAnchored
    val bounds = rememberItemBounds()
    val reduce = LocalReduceMotion.current
    val hairline = Scene.divider
    val ink = if (isCar()) Car.skin.accentInk else Scene.glow
    BoxWithConstraints(modifier.fillMaxWidth().drawBehind {
        val stroke = 1.dp.toPx()
        drawRect(hairline, Offset(0f, size.height - stroke), Size(size.width, stroke))
    }) {
        // Where the tabs fit at their natural width they sit at the start; where they do not (a phone card is about 335 dp),
        // they share the width equally so every sheet stays visible instead of the last one being cut at the edge.
        val fits = maxWidth >= sheetTabsNaturalWidth(labels.size)
        Row((if (fits) Modifier.horizontalScroll(rememberScrollState()) else Modifier.fillMaxWidth())
            .selectionGlide(bounds, selected, durationMs = 180, mark = GlideMark.UNDERLINE, underlineColor = ink), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            labels.forEachIndexed { index, label ->
                val active = index == selected
                val interaction = remember { MutableInteractionSource() }
                val tint by animateColorAsState(if (active) ink else Scene.textSecondary, if (reduce) snap() else tween(180, easing = FastOutSlowInEasing), label = "sheet-tab")
                Box(Modifier.reportBounds(bounds, index).then(if (fits) Modifier.widthIn(min = 72.dp) else Modifier.weight(1f))
                    .pressable(interaction, .98f).heightIn(min = if (car) 56.dp else 48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .semantics { this.selected = active; role = Role.Tab }
                    .clickable(interactionSource = interaction, indication = if (car) null else LocalIndication.current) { onSelect(index) }
                    .padding(horizontal = if (fits) 18.dp else 4.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}

/**
 * One table row: the row number and the first column pinned, the other cells in a row that scrolls sideways on the shared
 * [sideways] state. A data row is a 72-dp (64 when [compact]) button that opens its full contents, pressed with the car's
 * scale and no ripple on the car (a narrow car window too), or the phone's ripple. On the car (2.13, [isCar]) a numeric cell
 * (an amount, a distance) sets its digits tabular, so the right-aligned figures of a column line up digit for digit as they
 * do in the one money style; the phone keeps its proportional digits.
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
    val figures = if (isCar()) cellStyle.copy(fontFeatureSettings = "tnum") else cellStyle
    val cell: @Composable (Int, String) -> Unit = { index, text ->
        val figure = !header && numeric.getOrNull(index) == true
        Text(text, Modifier.width(widths[index]).padding(12.dp), style = if (figure) figures else cellStyle, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = Scene.text, textAlign = if (figure) TextAlign.End else TextAlign.Start)
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
