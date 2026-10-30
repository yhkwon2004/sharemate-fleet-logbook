package ai.sharemate.app

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/*
 * Turns what card companies actually hand out into CardApproval records: approval alerts on the phone and downloaded
 * statements. Layouts differ per issuer, so parsing works from the shape of the text and of the header row, not from one
 * fixed template. Nothing here keeps the original text, and a line that cannot be read confidently is skipped.
 */

private val ISSUERS = listOf("신한", "KB", "국민", "삼성", "현대", "롯데", "우리", "하나", "NH", "농협", "BC", "비씨", "IBK", "기업", "씨티", "수협", "카카오", "토스", "케이뱅크", "광주", "전북", "제주")
private val ACTION = Regex("승인|결제|일시불|할부|취소")
private val NOT_A_PAYMENT = Regex("거절|실패|한도\\s*초과|결제\\s*(예정|일|금액|대금)|청구|명세서|납부|광고|이벤트|혜택|캐시백|안내|인증번호|오픈뱅킹")
private val AMOUNT = Regex("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원")
private val NOT_THE_AMOUNT = Regex("(누적|잔액|잔여|한도|포인트|합계)[^0-9]{0,6}$")
private val DATE = Regex("(?<![0-9])(?:(20[0-9]{2})[./-])?([01]?[0-9])[/.월-]\\s?([0-3]?[0-9])일?(?![0-9])")
private val TIME = Regex("(?<![0-9])([0-2]?[0-9]):([0-5][0-9])(?::[0-5][0-9])?")
private val LAST4 = Regex("\\(([0-9]{4})\\)|\\*{2,}([0-9]{4})|카드\\s?([0-9]{4})(?![0-9,])|승인\\s([0-9]{4})\\s")
private val RUNNING_TOTAL = Regex("(누적|잔액|잔여|한도|포인트).*$")
private val NOISE = Regex("\\[[^]]*]|\\(?[0-9]{4}\\)|[가-힣]\\*+[가-힣]?|\\*+[0-9]*|[0-9]+개월|일시불|할부|승인취소|취소승인|승인|취소|결제|체크|신용|Web발신|[가-힣A-Za-z]*카드")

/** Reads one alert, or returns null for anything that is not clearly a card payment. Raw text never leaves this function. */
internal fun parseCardNotification(title: String?, text: String, postedAt: Long, zone: ZoneId = ZoneId.systemDefault()): CardApproval? {
    val body = listOfNotNull(title, text).joinToString(" ").replace(Regex("\\s+"), " ").trim()
    if (body.isEmpty() || !ACTION.containsMatchIn(body) || NOT_A_PAYMENT.containsMatchIn(body)) return null
    if (!body.contains("카드") && ISSUERS.none { body.contains(it) }) return null
    val amount = AMOUNT.findAll(body).firstOrNull { !NOT_THE_AMOUNT.containsMatchIn(body.substring(0, it.range.first)) } ?: return null
    val won = amount.groupValues[1].replace(",", "").toLongOrNull()?.takeIf { it in 1..10_000_000 } ?: return null
    val cleaned = body.removeRange(amount.range.first, amount.range.last + 1).let { it.substring(0, amount.range.first) + " " + it.substring(amount.range.first) }
    val time = TIME.find(cleaned)
    val date = DATE.find(if (time != null) cleaned.replace(time.value, " ".repeat(time.value.length)) else cleaned)
    val posted = Instant.ofEpochMilli(postedAt).atZone(zone)
    val at = if (date != null && time != null) {
        val (year, month, dayOfMonth) = date.destructured
        runCatching {
            val parsed = LocalDateTime.of(year.toIntOrNull() ?: posted.year, month.toInt(), dayOfMonth.toInt(), time.groupValues[1].toInt(), time.groupValues[2].toInt())
            // January alert about a December purchase: without a printed year it belongs to last year.
            (if (year.isEmpty() && parsed.toLocalDate().isAfter(posted.toLocalDate().plusDays(1))) parsed.minusYears(1) else parsed).atZone(zone).toInstant().toEpochMilli()
        }.getOrDefault(postedAt)
    } else postedAt
    val last4 = LAST4.find(body)?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
    val withoutCard = if (last4 == null) cleaned else cleaned.replace(Regex("(?<![0-9])$last4(?![0-9])"), " ".repeat(last4.length))
    val anchor = listOfNotNull(time?.range?.last, date?.range?.last).maxOrNull()?.plus(1)
    fun tidy(part: String) = part.replace(RUNNING_TOTAL, " ").replace(NOISE, " ").replace(TIME, " ").replace(DATE, " ")
        .replace(Regex("[,:/|]"), " ").replace(Regex("\\s+"), " ").trim()
    val merchant = (anchor?.let { tidy(withoutCard.substring(minOf(it, withoutCard.length))) }?.takeIf { it.length >= 2 } ?: tidy(withoutCard)).take(60)
    if (merchant.length < 2) return null
    val cancel = body.contains("취소")
    return CardApproval(cardApprovalId(at, true, won, merchant, last4, cancel), at, true, won, merchant, last4, CardSource.NOTIFICATION, cancel)
}

// ───────────────────────── 카드 명세서 ─────────────────────────

private val DATE_HEADERS = listOf("이용일시", "승인일시", "거래일시", "이용일자", "승인일자", "거래일자", "이용일", "승인일", "거래일", "일자", "날짜")
private val TIME_HEADERS = listOf("이용시간", "승인시간", "승인시각", "거래시간", "시간")
private val MERCHANT_HEADERS = listOf("가맹점명", "이용가맹점", "가맹점", "이용처", "사용처", "거래처", "상호")
private val AMOUNT_HEADERS = listOf("승인금액", "이용금액", "거래금액", "결제금액", "금액")
private val CARD_HEADERS = listOf("카드번호", "이용카드", "카드")
private val STATE_HEADERS = listOf("승인구분", "거래구분", "구분", "상태", "취소여부", "취소")

private fun normalizedHeader(cell: String) = cell.replace(Regex("[\\s()*]"), "").replace("원", "")
private fun List<String>.column(names: List<String>): Int {
    names.forEach { name -> indexOfFirst { normalizedHeader(it) == name }.takeIf { it >= 0 }?.let { return it } }
    names.forEach { name -> indexOfFirst { normalizedHeader(it).contains(name) }.takeIf { it >= 0 }?.let { return it } }
    return -1
}

private val FULL_DATE = Regex("(20[0-9]{2})[./년-]?\\s?([01][0-9])[./월-]?\\s?([0-3][0-9])")
private val SHORT_DATE = Regex("(?<![0-9])([0-9]{2})[./-]([01][0-9])[./-]([0-3][0-9])(?![0-9])")

/** Accepts the date spellings seen in issuer downloads, including Excel serial numbers. Returns the moment and whether it has a time. */
internal fun parseStatementMoment(dateCell: String, timeCell: String?, zone: ZoneId): Pair<Long, Boolean>? {
    val text = dateCell.trim()
    text.toDoubleOrNull()?.takeIf { it in 30_000.0..80_000.0 }?.let { serial ->
        val whole = serial.toLong()
        val seconds = ((serial - whole) * 86_400).toLong()
        val moment = LocalDate.of(1899, 12, 30).plusDays(whole).atStartOfDay().plusSeconds(seconds)
        return (if (seconds > 0) moment else moment.withHour(12)).atZone(zone).toInstant().toEpochMilli() to (seconds > 0)
    }
    val parts = FULL_DATE.find(text)?.groupValues?.drop(1)?.map(String::toInt)
        ?: SHORT_DATE.find(text)?.groupValues?.drop(1)?.map(String::toInt)?.let { listOf(2000 + it[0], it[1], it[2]) } ?: return null
    val clock = TIME.find(text.substring((FULL_DATE.find(text) ?: SHORT_DATE.find(text))!!.range.last + 1)) ?: timeCell?.let { TIME.find(it) }
    val date = runCatching { LocalDate.of(parts[0], parts[1], parts[2]) }.getOrNull() ?: return null
    return (if (clock != null) date.atTime(clock.groupValues[1].toInt().coerceAtMost(23), clock.groupValues[2].toInt()) else date.atTime(12, 0))
        .atZone(zone).toInstant().toEpochMilli() to (clock != null)
}

/** Maps rows to approvals once a header row is found. Identical lines stay separate through their position in the file. */
internal fun statementApprovals(rows: List<List<String>>, zone: ZoneId = ZoneId.systemDefault()): List<CardApproval> {
    val headerIndex = rows.indexOfFirst { it.column(DATE_HEADERS) >= 0 && it.column(MERCHANT_HEADERS) >= 0 && it.column(AMOUNT_HEADERS) >= 0 }
    require(headerIndex >= 0) { "이용일·가맹점·금액 열을 찾지 못했어요. 카드사에서 내려받은 내역 파일인지 확인해 주세요." }
    val header = rows[headerIndex]
    val dateAt = header.column(DATE_HEADERS); val merchantAt = header.column(MERCHANT_HEADERS); val amountAt = header.column(AMOUNT_HEADERS)
    val timeAt = header.column(TIME_HEADERS).takeIf { it != dateAt }; val cardAt = header.column(CARD_HEADERS).takeIf { it != merchantAt && it != amountAt }
    val stateAt = header.column(STATE_HEADERS).takeIf { it != merchantAt && it != amountAt && it != cardAt }
    val seen = mutableMapOf<String, Int>()
    return rows.drop(headerIndex + 1).mapNotNull { row ->
        val merchant = row.getOrNull(merchantAt)?.trim().orEmpty().take(60)
        val rawAmount = row.getOrNull(amountAt)?.replace(Regex("[,\\s원]"), "").orEmpty()
        val amount = rawAmount.toDoubleOrNull()?.toLong() ?: return@mapNotNull null
        val moment = parseStatementMoment(row.getOrNull(dateAt).orEmpty(), timeAt?.takeIf { it >= 0 }?.let(row::getOrNull), zone) ?: return@mapNotNull null
        if (merchant.length < 2 || amount == 0L || kotlin.math.abs(amount) > 10_000_000) return@mapNotNull null
        val cancel = amount < 0 || stateAt?.takeIf { it >= 0 }?.let(row::getOrNull)?.contains("취소") == true
        val last4 = cardAt?.takeIf { it >= 0 }?.let(row::getOrNull)?.let { Regex("([0-9]{4})\\D*$").find(it)?.groupValues?.get(1) }
        val key = "${moment.first}|$amount|$merchant|$cancel"
        val occurrence = seen.merge(key, 1, Int::plus)!! - 1
        val won = kotlin.math.abs(amount)
        CardApproval(cardApprovalId(moment.first, moment.second, won, merchant, last4, cancel, occurrence), moment.first, moment.second, won, merchant, last4, CardSource.STATEMENT, cancel)
    }
}

/** CSV with quotes; card sites still hand out MS949 as often as UTF-8. */
internal fun decodeStatementText(bytes: ByteArray): String {
    val utf8 = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    }.recoverCatching { if (it is CharacterCodingException) null else throw it }.getOrNull()
    return (utf8 ?: String(bytes, Charset.forName("MS949"))).removePrefix(BOM)
}

internal fun parseCsv(text: String): List<List<String>> {
    val rows = mutableListOf<List<String>>()
    var row = mutableListOf<String>()
    val cell = StringBuilder()
    var quoted = false
    var i = 0
    fun endCell() { row += cell.toString(); cell.clear() }
    fun endRow() { endCell(); if (row.any { it.isNotBlank() }) rows += row; row = mutableListOf() }
    while (i < text.length) {
        val c = text[i]
        when {
            quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { cell.append('"'); i++ }
            c == '"' -> quoted = !quoted
            quoted -> cell.append(c)
            c == ',' || c == '\t' -> endCell()
            c == '\n' -> endRow()
            c != '\r' -> cell.append(c)
        }
        i++
    }
    if (cell.isNotEmpty() || row.isNotEmpty()) endRow()
    return rows
}

/** Many ".xls" downloads are really HTML tables. */
internal fun parseHtmlTable(text: String): List<List<String>> = Regex("(?is)<tr[^>]*>(.*?)</tr>").findAll(text).map { row ->
    Regex("(?is)<t[dh][^>]*>(.*?)</t[dh]>").findAll(row.groupValues[1]).map { cell ->
        cell.groupValues[1].replace(Regex("(?s)<[^>]+>"), " ").replace("&nbsp;", " ").replace("&amp;", "&").trim()
    }.toList()
}.filter { it.isNotEmpty() }.toList()

private fun xml(bytes: ByteArray) = DocumentBuilderFactory.newInstance().apply {
    setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    isNamespaceAware = false
}.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

private fun columnIndex(reference: String): Int = reference.takeWhile { it.isLetter() }.fold(0) { sum, c -> sum * 26 + (c.uppercaseChar() - 'A' + 1) } - 1

/** The first worksheet of an .xlsx file; a zip of XML, read with size caps because the file comes from outside. */
internal fun parseXlsx(bytes: ByteArray): List<List<String>> {
    val parts = mutableMapOf<String, ByteArray>()
    ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
        var total = 0
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.name == "xl/sharedStrings.xml" || entry.name.matches(Regex("xl/worksheets/sheet[0-9]+\\.xml"))) {
                val data = zip.readBytes()
                total += data.size
                require(total <= 40_000_000) { "파일이 너무 커요." }
                parts[entry.name] = data
            }
        }
    }
    val sheet = parts.keys.filter { it.startsWith("xl/worksheets/") }.minByOrNull { it.filter(Char::isDigit).toInt() }?.let(parts::get)
        ?: throw IllegalArgumentException("시트를 찾지 못했어요.")
    val shared = parts["xl/sharedStrings.xml"]?.let { strings ->
        val items = xml(strings).getElementsByTagName("si")
        (0 until items.length).map { items.item(it).textContent }
    }.orEmpty()
    val rows = xml(sheet).getElementsByTagName("row")
    return (0 until rows.length).map { r ->
        val cells = (rows.item(r) as Element).getElementsByTagName("c")
        val out = mutableListOf<String>()
        (0 until cells.length).forEach { c ->
            val cell = cells.item(c) as Element
            val index = cell.getAttribute("r").takeIf { it.isNotEmpty() }?.let(::columnIndex) ?: out.size
            while (out.size < index) out += ""
            val raw = cell.getElementsByTagName("v").item(0)?.textContent ?: cell.getElementsByTagName("t").item(0)?.textContent.orEmpty()
            out += if (cell.getAttribute("t") == "s") raw.toIntOrNull()?.let(shared::getOrNull).orEmpty() else raw
        }
        out
    }.filter { row -> row.any { it.isNotBlank() } }
}

/** Reads a statement download in whichever of the three formats it arrived. */
internal fun parseStatementFile(bytes: ByteArray, zone: ZoneId = ZoneId.systemDefault()): List<CardApproval> {
    require(bytes.size in 1..20_000_000) { "파일 크기를 확인해 주세요." }
    val isZip = bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
    val isOldExcel = bytes.size > 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()
    require(!isOldExcel) { "옛 .xls 형식은 읽을 수 없어요. 엑셀에서 .xlsx나 .csv로 저장해 주세요." }
    val rows = if (isZip) parseXlsx(bytes) else decodeStatementText(bytes).let { text ->
        if (text.trimStart().startsWith("<")) parseHtmlTable(text) else parseCsv(text)
    }
    return statementApprovals(rows, zone)
}

/** A statement lists everything on the card. Named vehicle costs go to the car; anything else leaves the phone only for a registered company card on a day the car stopped to refuel. */
internal fun vehicleRelevant(approvals: List<CardApproval>, data: TripLogData, zone: ZoneId): List<CardApproval> {
    val stopDays = data.stops.map { Instant.ofEpochMilli(it.endAt).atZone(zone).toLocalDate() }.toSet()
    return approvals.filter { categorize(it.merchant) != null || (it.last4 != null && it.last4 in data.profile.cards && Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() in stopDays) }
}
