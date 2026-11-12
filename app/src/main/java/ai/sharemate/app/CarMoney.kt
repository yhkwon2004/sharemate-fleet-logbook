package ai.sharemate.app

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/*
 * Money on the car, one style (2.13, docs/design/DESIGN_2_13_SPEC.md §5). The words were already one function ([formatWon]);
 * the setting was not: seven sizes, places without tabular digits, two conventions for zero, raw digits in every field. Here
 * an amount is one line of tabular digits with 원 straight after them in Medium and a quieter ink, at one of three sizes; it
 * never ellipsises (it steps down to 70 %) and never animates. Zero and a missing amount are derived, never passed: both are
 * textMuted. A tone (POSITIVE, PENDING) never stands alone and a card holds one at most. Sentences, chips, follow-ups and
 * TalkBack keep [formatWon] with the particle attached (4,500원을); a tag never holds a sum; a rate is "km당 150원". The
 * ₩ mark (ic_won) is once per card, on a money card's title or a money mini stat's label, never beside an amount. The
 * phone never draws any of this.
 */

/**
 * The three sizes of an amount: HERO, a 홈 card's figure (displaySmall 44 sp, 원 at 21); TOTAL, a total row (headlineMedium
 * 30, 원 at 19); ROW, a row's amount (titleMedium 21, 원 at 16). 원 is never under 16 sp. A mini stat's money is
 * [CarMiniStat]'s `won` overload, titleSmall with 원 at the same size.
 */
internal enum class MoneySize(val unitSp: Int) { HERO(21), TOTAL(19), ROW(16) }

/**
 * What an amount's digits say besides the sum: NEUTRAL in text (17.38 / 16.00 on the card); POSITIVE in Scene.positive
 * (6.21 / 9.08), only for 비용 참고's result while it exceeds 기록부 없을 때; PENDING in Scene.warn (6.80 / 9.09), an amount
 * that waits for the driver's answer (카드 확인). 원 stays textMuted in every tone.
 */
internal enum class MoneyTone { NEUTRAL, POSITIVE, PENDING }

/**
 * An amount ready to draw ([moneyText]): the grouped [digits] (or the missing mark), the [unit] after them (empty when
 * missing), whether it is [zero] or [missing], and what TalkBack says ([spoken]).
 */
@Immutable internal data class MoneyText(val digits: String, val unit: String, val zero: Boolean, val missing: Boolean, val spoken: String) {
    /** Zero and missing are drawn quietly: textMuted, Medium. */
    val quiet: Boolean get() = zero || missing
}

/**
 * How [won] is drawn (pure, unit-tested): "45,000" and "원", spoken "45,000원" as [formatWon] writes it; zero is "0" and "원",
 * flagged [MoneyText.zero]. No amount (null) shows [missing] in the amount's place ("—", or a word such as 금액 없음) and is
 * heard as that word, or as 금액 없음 where [missing] is a mark.
 */
internal fun moneyText(won: Long?, missing: String): MoneyText {
    if (won == null) return MoneyText(missing, "", zero = false, missing = true, spoken = if (missing.any { it in '가'..'힣' }) missing else "금액 없음")
    val digits = groupDigits(abs(won).toString())
    return MoneyText(if (won < 0) "-$digits" else digits, "원", zero = won == 0L, missing = false, spoken = formatWon(won))
}

/** A string of digits in groups of three from the end (pure): "32000000" → "32,000,000"; "" stays "". */
internal fun groupDigits(digits: String): String = digits.reversed().chunked(3).joinToString(",").reversed()

/**
 * An amount in words a driver reads at a glance (pure): under 1만 as [formatWon] writes it (4,500원); from 1만 in 억 and 만
 * with a space after 만 or 억 only (1만 5,000원 · 10만 원 · 1,000만 원 · 1억 2,000만 원 · 100억 원). The money field reads
 * a large figure back with it, and a limit is written with it ([wonLimit]).
 */
internal fun wonReading(won: Long): String {
    if (won < 0) return "-" + wonReading(-won)
    if (won < 10_000) return formatWon(won)
    val eok = won / 100_000_000
    val man = won % 100_000_000 / 10_000
    val rest = won % 10_000
    val parts = buildList {
        if (eok > 0) add("${groupDigits(eok.toString())}억")
        if (man > 0) add("${groupDigits(man.toString())}만")
        if (rest > 0) add(formatWon(rest))
    }
    return if (rest > 0) parts.joinToString(" ") else parts.joinToString(" ") + " 원"
}

/** The error of a money field past its range (pure): "1,000만 원까지 입력할 수 있어요" for 10,000,000. */
internal fun wonLimit(max: Long): String = "${wonReading(max)}까지 입력할 수 있어요"

/**
 * What the money field keeps of an edit (pure): its digits only (a pasted "32,000,000원" is taken as 32000000), without
 * leading zeros ("007" → "7", "000" → "0"); null when that is longer than [maxDigits], and the edit is then ignored.
 */
internal fun moneyDigits(input: String, maxDigits: Int): String? {
    val digits = input.filter { it in '0'..'9' }
    val kept = if (digits.isEmpty()) "" else digits.trimStart('0').ifEmpty { "0" }
    return kept.takeIf { it.length <= maxDigits }
}

/**
 * A money field's state ([moneyInput]): the amount ([won], null while blank), whether an action may use it ([ok]), the
 * [error] past the range, and the [helper] line under the field.
 */
@Immutable internal data class MoneyInput(val won: Long?, val ok: Boolean, val error: String?, val helper: String)

/**
 * A money field's raw [digits] against its range (pure, unit-tested). Blank is fine where [min] is 0 (the amount is optional)
 * and simply not ready otherwise, with no error. Past [max] the error is [wonLimit]; under [min] the field is not ready and
 * its [hint] says the range. The helper line is the error; else, with [reading] and from 1만 up, the amount read back
 * ([wonReading], "3,200만 원"); else the [hint]. Each action needs [MoneyInput.ok]; the repository's own checks stay as the
 * backstop.
 */
internal fun moneyInput(digits: String, min: Long, max: Long, hint: String, reading: Boolean = false): MoneyInput {
    if (digits.isBlank()) return MoneyInput(null, ok = min <= 0, error = null, helper = hint)
    val won = digits.toLongOrNull() ?: Long.MAX_VALUE
    return when {
        won > max -> wonLimit(max).let { MoneyInput(won, ok = false, error = it, helper = it) }
        won < min -> MoneyInput(won, ok = false, error = null, helper = hint)
        else -> MoneyInput(won, ok = true, error = null, helper = if (reading && won >= 10_000) wonReading(won) else hint)
    }
}

/**
 * Where the cursor shows in the grouped text ([groupDigits]) for a cursor after [raw] of [n] digits (pure): right after the
 * same digit, never after a comma, so a backspace there deletes the digit before it.
 */
internal fun wonShownOffset(n: Int, raw: Int) = if (raw <= 0) 0 else raw + (n - 1) / 3 - (n - raw) / 3

/** The raw offset for a cursor at [shown] in the grouped text of [n] digits (pure): a comma counts for the digit before it. */
internal fun wonRawOffset(n: Int, shown: Int): Int {
    val head = n - 3 * ((n - 1) / 3)
    return if (shown <= head) shown else shown - (shown - head + 3) / 4
}

/** Shows a money field's raw digits grouped ("32000000" as "32,000,000") with the cursor mapped by [wonShownOffset] and [wonRawOffset]. */
internal object WonTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val n = text.length
        return TransformedText(AnnotatedString(groupDigits(text.text)), object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = wonShownOffset(n, offset)
            override fun transformedToOriginal(offset: Int): Int = wonRawOffset(n, offset)
        })
    }
}

/** The digits' style of a [MoneySize]. */
@Composable private fun moneyStyle(size: MoneySize): TextStyle = when (size) {
    MoneySize.HERO -> MaterialTheme.typography.displaySmall
    MoneySize.TOTAL -> MaterialTheme.typography.headlineMedium
    MoneySize.ROW -> MaterialTheme.typography.titleMedium
}

/**
 * An amount in the one money style: one line of [won]'s digits with tabular figures in [size] and [tone], 원 straight after
 * them in Medium textMuted on the same baseline, end-aligned. It never ellipsises: where it would not fit it steps down to
 * 70 % (원 keeps its size, never under 16 sp). It never animates. Zero and [missing] are muted; TalkBack hears the amount
 * as [formatWon] writes it, or 금액 없음.
 */
@Composable internal fun CarAmount(won: Long?, modifier: Modifier = Modifier, size: MoneySize = MoneySize.ROW, tone: MoneyTone = MoneyTone.NEUTRAL,
    missing: String = "—") {
    val style = moneyStyle(size)
    AmountText(moneyText(won, missing), style, unitSize = size.unitSp.sp, tone = tone, modifier = modifier,
        autoSize = TextAutoSize.StepBased(minFontSize = style.fontSize * .7f, maxFontSize = style.fontSize, stepSize = .5.sp))
}

/**
 * The text of an amount, shared by [CarAmount] and the money mini stat: one AnnotatedString, the digits in [style] with
 * `tnum` in the [tone]'s ink, 원 a Medium span at [unitSize] (unspecified: the digits' size) in textMuted; a quiet amount
 * (zero, missing) is textMuted Medium throughout. One line, never ellipsised ([autoSize]).
 */
@Composable internal fun AmountText(money: MoneyText, style: TextStyle, unitSize: TextUnit, tone: MoneyTone, modifier: Modifier = Modifier,
    autoSize: TextAutoSize? = null, alignEnd: Boolean = true) {
    val muted = Scene.textMuted
    val ink = when {
        money.quiet -> muted
        tone == MoneyTone.POSITIVE -> Scene.positive
        tone == MoneyTone.PENDING -> Scene.warn
        else -> Scene.text
    }
    val text = buildAnnotatedString {
        withStyle(SpanStyle(color = ink, fontFeatureSettings = "tnum", fontWeight = if (money.quiet) FontWeight.Medium else null)) { append(money.digits) }
        if (money.unit.isNotEmpty()) withStyle(SpanStyle(color = muted, fontSize = unitSize, fontWeight = FontWeight.Medium)) { append(money.unit) }
    }
    BasicText(text, modifier.semantics { contentDescription = money.spoken }, style = style.copy(textAlign = if (alignEnd) TextAlign.End else TextAlign.Start),
        color = { ink }, maxLines = 1, softWrap = false, autoSize = autoSize)
}

/**
 * A row of money (2.13): a [CarRow] with the [label] and an optional [line], then the amount ([CarAmount], ROW, or TOTAL for
 * the [total] row) and an optional pill ([action], 12 dp after it). Both are unweighted, so the label gives way. A list of
 * money is its non-zero rows, a [CarDivider], then the total row on the same right edge; a list without rows shows its one
 * empty line and no total. One TalkBack stop for the words ("주유·충전, 45,000원", then the line); the pill keeps its own.
 */
@Composable internal fun CarMoneyRow(label: String, won: Long?, modifier: Modifier = Modifier, line: String? = null, total: Boolean = false,
    tone: MoneyTone = MoneyTone.NEUTRAL, missing: String = "—", lead: (@Composable () -> Unit)? = null, action: (@Composable RowScope.() -> Unit)? = null) {
    val spoken = listOfNotNull(label, moneyText(won, missing).spoken, line).joinToString(", ")
    CarRow(label, modifier, line = line, lead = lead, spoken = spoken, trailing = {
        CarAmount(won, Modifier.clearAndSetSemantics {}, size = if (total) MoneySize.TOTAL else MoneySize.ROW, tone = tone, missing = missing)
        if (action != null) {
            Spacer(Modifier.width(12.dp))
            action()
        }
    })
}

/**
 * A money field (2.13), its own text field in [Field]'s look because [Field] is the phone's too: the state stays the raw
 * [digits] ([moneyDigits] filters every edit, so a paste of "32,000,000원" is taken and a digit past [maxDigits] is not),
 * shown grouped ([WonTransformation]) with tabular figures, end-aligned, 원 straight after them and 0 as the focused
 * placeholder. The helper line is [input]'s ([moneyInput]): the error, the amount read back, or the hint. The focus ring is
 * the accent ink (4.93 / 7.66), as every car field's. [done] ends the form (the keyboard's 완료), otherwise 다음.
 */
@Composable internal fun CarMoneyField(label: String, digits: String, onDigits: (String) -> Unit, input: MoneyInput, modifier: Modifier = Modifier,
    maxDigits: Int = 8, done: Boolean = false) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val style = MaterialTheme.typography.bodyLarge.copy(fontFeatureSettings = "tnum", textAlign = TextAlign.End)
    OutlinedTextField(digits, { typed -> moneyDigits(typed, maxDigits)?.let(onDigits) }, modifier.fillMaxWidth().heightIn(min = 68.dp),
        label = { Text(label) }, singleLine = true, textStyle = style, visualTransformation = WonTransformation,
        placeholder = { Text("0", Modifier.fillMaxWidth(), style = style, color = Scene.textMuted) }, suffix = { Text("원") },
        shape = RoundedCornerShape(18.dp), isError = input.error != null,
        supportingText = input.helper.takeIf { it.isNotBlank() }?.let { helper -> { Text(helper, style = MaterialTheme.typography.bodySmall) } },
        colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Scene.fill, focusedContainerColor = Scene.glass,
            unfocusedBorderColor = Scene.outline, focusedBorderColor = Car.skin.accentInk),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = if (done) ImeAction.Done else ImeAction.Next),
        keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Next) }, onDone = { keyboard?.hide(); focus.clearFocus() }))
}
