package ai.sharemate.app

import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
internal const val LOGBOOK_MIME = "application/vnd.sharemate.logbook+json"

/** Inline strings keep names and notes as text, including text starting with '='. */
internal fun logbookXlsx(profile: FleetProfile, from: LocalDate, to: LocalDate, rows: List<LogbookRow>, totals: LogbookTotals): ByteArray {
    fun xml(value: String) = value.filter { it == '\n' || it == '\r' || it == '\t' || it >= ' ' && it != '\uFFFE' && it != '\uFFFF' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    fun cell(column: Int, row: Int, value: Any, style: Int = 0): String {
        val address = "${('A'.code + column).toChar()}$row"
        return if (value is Number) {
            require(value.toDouble().isFinite())
            "<c r=\"$address\" s=\"${if (style == 0) 3 else style}\"><v>$value</v></c>"
        } else "<c r=\"$address\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>"
    }
    val sheetRows = mutableListOf<String>()
    fun row(number: Int, values: List<Any>, style: Int = 0) {
        sheetRows += "<row r=\"$number\" ht=\"${if (number == 1) 36 else if (number in 2..3 || number == rows.size + 10) 44 else 26}\" customHeight=\"1\">" +
            values.mapIndexed { column, value -> cell(column, number, value, style) }.joinToString("") + "</row>"
    }
    row(1, listOf("업무용승용차 운행기록부"), 1)
    row(2, listOf("법인", profile.companyName, "차종", profile.carModel, "차량번호", profile.plateNumber))
    row(3, listOf("조회 기간", "$from ~ $to", "사업자등록번호", profile.businessNumber))
    row(5, listOf("사용일자", "부서", "성명", "주행 전(km)", "주행 후(km)", "주행거리(km)", "출·퇴근(km)", "일반 업무(km)", "비고"), 2)
    rows.forEachIndexed { index, item -> row(index + 6, listOf("${item.date}(${weekday(item.date)})", item.department, item.name,
        item.beforeKm, item.afterKm, item.distanceKm, item.commuteKm, item.businessKm, item.note)) }
    val end = rows.size + 7
    row(end, listOf("조회 기간 합계", "", "", "", "", totals.totalKm, totals.commuteKm, totals.generalKm), 4)
    row(end + 2, listOf("업무사용비율", totals.ratio?.let { "%.1f%%".format(java.util.Locale.US, it * 100) } ?: "-", "운행 건수", totals.trips,
        "기록 비용(원)", totals.expenseWon), 4)
    row(end + 3, listOf("확인 안내", "미분류·개인 운행은 업무거리에서 제외됩니다. 신고 전 원본 기록과 해당 사업연도·적용 요건을 확인하세요."))
    val output = ByteArrayOutputStream()
    ZipOutputStream(output).use { zip ->
        fun entry(name: String, body: String) { zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
        entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/></Types>""")
        entry("_rels/.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
        entry("xl/workbook.xml", """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="운행기록부" sheetId="1" r:id="rId1"/></sheets></workbook>""")
        entry("xl/_rels/workbook.xml.rels", """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
        entry("xl/styles.xml", """<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.0"/></numFmts><fonts count="3"><font><sz val="11"/><name val="맑은 고딕"/></font><font><b/><sz val="20"/><color rgb="FF17212B"/><name val="맑은 고딕"/></font><font><b/><sz val="11"/><color rgb="FFFFFFFF"/><name val="맑은 고딕"/></font></fonts><fills count="3"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF17212B"/><bgColor indexed="64"/></patternFill></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="5"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"><alignment vertical="center" wrapText="1"/></xf><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0"><alignment horizontal="center" vertical="center"/></xf><xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/></cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>""")
        entry("xl/worksheets/sheet1.xml", """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="5" topLeftCell="A6" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="21" customWidth="1"/><col min="2" max="3" width="17" customWidth="1"/><col min="4" max="8" width="19" customWidth="1"/><col min="9" max="9" width="60" customWidth="1"/></cols><sheetData>${sheetRows.joinToString("")}</sheetData><autoFilter ref="A5:I${maxOf(5, rows.size + 5)}"/><mergeCells count="2"><mergeCell ref="A1:I1"/><mergeCell ref="B${end + 3}:I${end + 3}"/></mergeCells><pageMargins left="0.25" right="0.25" top="0.4" bottom="0.4" header="0.2" footer="0.2"/><pageSetup paperSize="9" orientation="landscape" fitToWidth="1" fitToHeight="0"/></worksheet>""")
    }
    return output.toByteArray()
}
