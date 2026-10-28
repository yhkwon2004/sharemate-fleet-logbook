package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

/** A read-only data preview of the same four sheets that Excel export writes. */
@Composable
internal fun ExcelPreview(data: TripLogData, trips: List<Trip>, from: LocalDate, to: LocalDate,
    modifier: Modifier = Modifier, compact: Boolean = false) {
    var sheetIndex by rememberSaveable { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var detail by remember { mutableStateOf<Pair<Int, List<Any>>?>(null) }
    val result by produceState<Result<List<LogbookSheet>>?>(null, data.profile, trips, data.drivers, from, to) {
        value = null
        value = try { Result.success(withContext(Dispatchers.Default) { logbookSheets(data.profile, from, to, trips, data.drivers) }) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { Result.failure(e) }
    }
    LaunchedEffect(data.profile, trips, data.drivers, from, to) { page = 0; detail = null }
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(modifier) {
    val rowsPerPage = ((maxHeight.value - if (compact) 340 else 470) / if (compact) 64 else 72).toInt().coerceIn(1, if (compact) 3 else 6)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("업무용승용차 운행기록부", style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
            Text(if (data.profile.demoMode) "시연 데이터 · 제출용이 아닙니다" else "${data.profile.companyName.ifBlank { "법인명 미입력" }} · ${data.profile.plateNumber.ifBlank { "차량번호 미입력" }}",
                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Segmented(listOf("운행", "직원", "경비", "확인"), sheetIndex) { sheetIndex = it; page = 0; detail = null }
        when {
            result == null -> Box(Modifier.fillMaxWidth().height(140.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            result!!.isFailure -> Panel { Text("미리보기를 만들지 못했어요"); Muted("기록은 변경하지 않았습니다. 조회 기간을 다시 선택해 주세요.") }
            else -> {
                val sheet = result!!.getOrThrow()[sheetIndex]
                val pageSize = rowsPerPage
                val pages = maxOf(1, (sheet.rows.size + pageSize - 1) / pageSize)
                val currentPage = page.coerceIn(0, pages - 1)
                val start = currentPage * pageSize
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(sheet.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${sheet.rows.size}행", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                if (sheet.rows.isEmpty()) Panel(padding = 24.dp) {
                    Text(if (sheetIndex == 2) "등록된 경비가 없어요" else "이 범위의 기록이 없어요", style = MaterialTheme.typography.titleMedium)
                    Muted("조회 기간과 직원 선택을 확인하세요. 저장 시에도 빈 내역으로 표시됩니다.")
                } else {
                    val widths = sheet.columns.mapIndexed { index, title -> when {
                        index == sheet.columns.lastIndex -> if (compact) 260.dp else 360.dp
                        title.contains("일시") -> 180.dp
                        title.contains("일자") -> 180.dp
                        else -> if (compact) 136.dp else 170.dp
                    } }
                    val tableWidth = widths.fold(48.dp) { width, next -> width + next }
                    Surface(shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, colors.outlineVariant), color = colors.surface) {
                        Column(Modifier.horizontalScroll(rememberScrollState())) {
                            Row(Modifier.width(tableWidth).background(colors.primaryContainer), verticalAlignment = Alignment.CenterVertically) {
                                Text("행", Modifier.width(48.dp).padding(vertical = 16.dp), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
                                sheet.columns.forEachIndexed { index, label -> Text(label, Modifier.width(widths[index]).padding(12.dp),
                                    style = MaterialTheme.typography.labelLarge, maxLines = 2, color = colors.onPrimaryContainer) }
                            }
                            sheet.rows.subList(start, minOf(start + pageSize, sheet.rows.size)).forEachIndexed { offset, row ->
                                val number = start + offset + 6
                                HorizontalDivider(color = colors.outlineVariant.copy(alpha = .6f))
                                Row(Modifier.width(tableWidth).heightIn(min = if (compact) 64.dp else 72.dp)
                                    .background(if (offset % 2 == 0) colors.surface else colors.surfaceContainerLow)
                                    .clickable(role = Role.Button, onClickLabel = "${number}행 전체 내용 보기") { detail = number to row },
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text(number.toString(), Modifier.width(48.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                                    row.forEachIndexed { index, cell -> Text(previewCell(cell), Modifier.width(widths[index]).padding(12.dp),
                                        style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                                        textAlign = if (cell is Number) TextAlign.End else TextAlign.Start) }
                                }
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page = currentPage - 1 }, enabled = currentPage > 0, modifier = Modifier.heightIn(min = if (compact) 48.dp else 56.dp)) { Text("‹ 이전") }
                        Text("${currentPage + 1} / $pages", style = MaterialTheme.typography.labelLarge)
                        TextButton(onClick = { page = currentPage + 1 }, enabled = currentPage + 1 < pages, modifier = Modifier.heightIn(min = if (compact) 48.dp else 56.dp)) { Text("다음 ›") }
                    }
                    Text("가로로 더 보기 · 행을 누르면 전체 내용 표시", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                }
                Text("실제 저장 데이터 · Excel 인쇄 배치와 다를 수 있어요. 증빙·세무 요건은 제출 전 별도로 확인하세요.", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                detail?.let { (number, row) -> AlertDialog(onDismissRequest = { detail = null }, title = { Text("${sheet.name} · ${number}행") },
                    text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEachIndexed { index, cell -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(sheet.columns[index], style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                            Text(previewCell(cell).ifBlank { "—" }, style = MaterialTheme.typography.bodyLarge)
                        } }
                    } }, confirmButton = { TextButton(onClick = { detail = null }) { Text("닫기") } }) }
            }
        }
    }
    }
}

private fun previewCell(value: Any): String = when (value) {
    is Double -> String.format(Locale.KOREA, "%,.1f", value)
    is Float -> String.format(Locale.KOREA, "%,.1f", value)
    is Long -> String.format(Locale.KOREA, "%,d", value)
    is Int -> String.format(Locale.KOREA, "%,d", value)
    else -> value.toString()
}
