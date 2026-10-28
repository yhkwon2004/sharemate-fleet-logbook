package ai.sharemate.app

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
internal const val LOGBOOK_MIME = "application/vnd.sharemate.logbook+json"

internal data class LogbookSheet(val name: String, val columns: List<String>, val rows: List<List<Any>>)
private val logbookColumns = listOf("사용일자", "부서", "성명", "주행 전(km)", "주행 후(km)", "주행거리(km)", "출·퇴근(km)", "일반 업무(km)", "비고")
private fun logbookValues(item: LogbookRow): List<Any> = listOf("${item.date}(${weekday(item.date)})", item.department, item.name,
    item.beforeKm, item.afterKm, item.distanceKm, item.commuteKm, item.businessKm, item.note)

/** Inline strings keep names and notes as text, including text starting with '='. */
internal fun logbookXlsx(profile: FleetProfile, from: LocalDate, to: LocalDate, rows: List<LogbookRow>, totals: LogbookTotals): ByteArray =
    logbookWorkbook(profile, from, to, rows, totals, emptyList())

internal fun logbookXlsx(profile: FleetProfile, from: LocalDate, to: LocalDate, trips: List<Trip>, drivers: List<Driver>, zone: ZoneId = ZoneId.systemDefault()): ByteArray {
    val selected = tripsIn(trips.filter { it.demo == profile.demoMode }, from, to, zone)
    val supporting = logbookSheets(profile, from, to, selected, drivers, zone).drop(1).map { it.name to (listOf(it.columns) + it.rows) }
    return logbookWorkbook(profile, from, to, logbookRows(selected, drivers, zone), logbookTotals(selected), supporting)
}

/** The preview and workbook use the same rows, including filtering and review warnings. */
internal fun logbookSheets(profile: FleetProfile, from: LocalDate, to: LocalDate, trips: List<Trip>, drivers: List<Driver>, zone: ZoneId = ZoneId.systemDefault()): List<LogbookSheet> {
    require(!to.isBefore(from))
    val selected = tripsIn(trips.filter { it.demo == profile.demoMode }, from, to, zone)
    val byDriver = drivers.associateBy { it.id }
    fun date(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate().toString()
    val employees = employeeUsage(selected, drivers, profile.reimbursePerKmWon)
    val employeeRows = listOf<List<Any>>(listOf("부서", "성명", "운행(건)", "전체(km)", "업무(km)", "주유·충전(원)", "기타 경비(원)", "단가(원/km)", "거리정산 참고(원)", "확인")) +
        employees.map { item -> listOf(item.driver?.department.orEmpty(), item.driver?.name ?: "직원 미지정", item.totals.trips,
            item.totals.totalKm, item.totals.businessKm, item.fuelWon, item.totals.expenseWon - item.fuelWon,
            profile.reimbursePerKmWon, item.reimbursementWon,
            if (item.driver == null) "직원 지정 전 정산 보류" else if (profile.reimbursePerKmWon == 0L) "회사 단가 미설정" else "업무거리 × 회사 단가 · 실비와 별도" ) }
    val expenseRows = listOf<List<Any>>(listOf("등록일시", "운행일자", "부서", "성명", "경비 구분", "입력 금액(원)", "운행 목적", "등록 경로", "원본 증빙")) +
        selected.flatMap { trip -> trip.expenses.map { trip to it } }.sortedBy { it.second.at }.map { (trip, expense) ->
            val driver = byDriver[trip.driverId]
            listOf(Instant.ofEpochMilli(expense.at).atZone(zone).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")), date(trip.startAt),
                driver?.department.orEmpty(), driver?.name ?: "직원 미지정", expense.type.label, expense.amountWon,
                trip.purpose.label, expense.source.label, "영수증 별도 확인·보관")
        }
    val checkRows = mutableListOf<List<Any>>(listOf("확인 항목", "운행일자", "부서", "성명", "검토 내용"))
    listOf("법인명" to profile.companyName, "사업자등록번호" to profile.businessNumber, "차종" to profile.carModel, "차량번호" to profile.plateNumber)
        .filter { it.second.isBlank() }.forEach { checkRows += listOf("기본정보 누락", "", "", "", "${it.first} 항목을 입력해 주세요.") }
    val selectedById = selected.associateBy { it.id }
    auditTrips(selected).forEach { issue ->
        val trip = selectedById.getValue(issue.tripId); val driver = byDriver[trip.driverId]
        checkRows += listOf(issue.type.label, date(trip.startAt), driver?.department.orEmpty(), driver?.name ?: "직원 미지정", issue.type.detail)
    }
    if (profile.demoMode) checkRows += listOf("시연 데이터", "", "", "", "가상 기록입니다. 실제 법인 증빙으로 제출하지 마세요.")
    if (selected.isEmpty()) checkRows += listOf("조회기간 기록 없음", "", "", "", "선택한 기간과 실제/시연 모드를 확인해 주세요.")
    if (checkRows.size == 1) checkRows += listOf("자동 점검", "", "", "", "확인 항목이 발견되지 않았습니다. 증빙의 적정성을 보장하지 않습니다.")
    return listOf(LogbookSheet("운행기록부", logbookColumns, logbookRows(selected, drivers, zone).map(::logbookValues))) +
        listOf("직원별 현황" to employeeRows, "경비원장" to expenseRows, "확인사항" to checkRows).map { (name, content) ->
            LogbookSheet(name, content.first().map { it.toString() }, content.drop(1))
        }
}

private fun logbookWorkbook(profile: FleetProfile, from: LocalDate, to: LocalDate, rows: List<LogbookRow>, totals: LogbookTotals,
    supportingSheets: List<Pair<String, List<List<Any>>>>): ByteArray {
    fun xml(value: String) = value.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' && it != '\uFFFE' && it != '\uFFFF' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun cell(column: Int, row: Int, value: Any, style: Int = 0): String {
        val address = "${('A'.code + column).toChar()}$row"
        return if (value is Number) {
            require(value.toDouble().isFinite())
            "<c r=\"$address\" s=\"${if (style == 0) { if (value is Double || value is Float) 3 else 4 } else style}\"><v>$value</v></c>"
        } else "<c r=\"$address\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>"
    }
    val sheetRows = mutableListOf<String>()
    fun row(number: Int, values: List<Any>, style: Int = 0, height: Int = if (number == 1) 36 else if (number in 2..3 || number == rows.size + 10) 44 else 26) {
        sheetRows += "<row r=\"$number\" ht=\"$height\" customHeight=\"1\">" +
            values.mapIndexed { column, value -> cell(column, number, value, style) }.joinToString("") + "</row>"
    }
    row(1, listOf("업무용승용차 운행기록부"), 1)
    row(2, listOf("법인", profile.companyName, "차종", profile.carModel, "차량번호", profile.plateNumber))
    row(3, listOf("조회 기간", "$from ~ $to", "사업자등록번호", profile.businessNumber))
    row(5, logbookColumns, 2)
    rows.forEachIndexed { index, item ->
        // shortcut: conservative character widths approximate wrapping; use font metrics if exact print pagination is needed.
        val lines = item.note.lineSequence().map { line ->
            val width = line.fold(0) { count, char -> count + if (char.code >= 0x2E80) 2 else 1 }
            maxOf(1, (width + 51) / 52)
        }.sum()
        row(index + 6, logbookValues(item), height = (lines * 18).coerceIn(30, 409))
    }
    val end = rows.size + 7
    row(end, listOf("조회 기간 합계", "", "", "", "", totals.totalKm, totals.commuteKm, totals.generalKm), 4)
    row(end + 2, listOf("업무사용비율", totals.ratio?.let { "%.1f%%".format(java.util.Locale.US, it * 100) } ?: "-", "운행 건수", totals.trips,
        "기록 비용(원)", totals.expenseWon), 4)
    row(end + 3, listOf("확인 안내", "미분류·개인 운행은 업무거리에서 제외됩니다. 신고 전 원본 기록과 해당 사업연도·적용 요건을 확인하세요."))
    val output = ByteArrayOutputStream()
    val sheetNames = listOf("운행기록부") + supportingSheets.map { it.first }
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>${sheetNames.indices.joinToString("") { "<Override PartName=\"/xl/worksheets/sheet${it + 1}.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" }}<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>${sheetNames.mapIndexed { index, name -> "<sheet name=\"${xml(name)}\" sheetId=\"${index + 1}\" r:id=\"rId${index + 1}\"/>" }.joinToString("")}</sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">${sheetNames.indices.joinToString("") { "<Relationship Id=\"rId${it + 1}\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet${it + 1}.xml\"/>" }}<Relationship Id="rId${sheetNames.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        entry("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.0"/></numFmts><fonts count="3"><font><sz val="11"/><name val="맑은 고딕"/></font><font><b/><sz val="20"/><color rgb="FF17212B"/><name val="맑은 고딕"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="맑은 고딕"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF17212B"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        entry("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="5" topLeftCell="A6" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="21" customWidth="1"/><col min="2" max="3" width="17" customWidth="1"/><col min="4" max="8" width="19" customWidth="1"/><col min="9" max="9" width="60" customWidth="1"/></cols><sheetData>${sheetRows.joinToString("")}</sheetData><autoFilter ref="A5:I${maxOf(5, rows.size + 5)}"/><mergeCells count="2"><mergeCell ref="A1:I1"/><mergeCell ref="B${end + 3}:I${end + 3}"/></mergeCells><pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/></worksheet>""")
        supportingSheets.forEachIndexed { index, (title, content) ->
            val lastColumn = ('A'.code + content.first().size - 1).toChar()
            val note = when (title) {
                "직원별 현황" -> "거리정산은 회사 단가 기준 참고액입니다. 입력 실비와 합산 지급하지 않으며 회사 규정과 원본 증빙을 확인하세요. 직원 미지정은 정산 보류합니다."
                "경비원장" -> "직접·음성 입력한 비용 목록입니다. 영수증이나 세금계산서를 생성하지 않습니다. 각 비용의 원본 증빙과 업무 관련성을 별도로 확인하세요."
                else -> "국세청 운행기록부 항목을 참고한 제출 전 검토 자료입니다. 조회기간은 전체 사업연도와 다를 수 있습니다. 자동 점검은 신고 적정성을 보장하지 않습니다."
            }
            fun supportingRow(number: Int, values: List<Any>, style: Int = 0, height: Int = 30) =
                "<row r=\"$number\" ht=\"$height\" customHeight=\"1\">" + values.mapIndexed { column, value -> cell(column, number, value, style) }.joinToString("") + "</row>"
            val body = supportingRow(1, listOf(title), 1, 36) +
                supportingRow(2, listOf("법인", profile.companyName, "차량번호", profile.plateNumber), height = 44) +
                supportingRow(3, listOf("조회 기간", "$from ~ $to", "자료 구분", if (profile.demoMode) "시연 데이터" else "차량 운행기록"), height = 44) +
                content.mapIndexed { rowIndex, values -> supportingRow(rowIndex + 5, values, if (rowIndex == 0) 2 else 0, if (rowIndex == 0) 48 else 44) }.joinToString("") +
                supportingRow(content.size + 7, listOf("안내", note), height = 68)
            entry("xl/worksheets/sheet${index + 2}.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="5" topLeftCell="A6" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="${content.first().size - 1}" width="23" customWidth="1"/><col min="${content.first().size}" max="${content.first().size}" width="62" customWidth="1"/></cols><sheetData>$body</sheetData><autoFilter ref="A5:$lastColumn${content.size + 4}"/><mergeCells count="2"><mergeCell ref="A1:$lastColumn${1}"/><mergeCell ref="B${content.size + 7}:$lastColumn${content.size + 7}"/></mergeCells><pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/></worksheet>""")
        }
    }
    return output.toByteArray()
}
