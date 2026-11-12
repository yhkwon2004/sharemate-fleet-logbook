package ai.sharemate.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Instant

/*
 * The screen assistant: a chat-shaped shell over the rule engine in Assistant.kt, for the parked car and the phone.
 * It is honest about what it is (rules on the device, no microphone, no network), shows its interpretation of a
 * write before running it, lists the steps it took, cites the trips it read or changed, and undoes in one tap.
 * Nothing here is shown while driving: the host only composes the sheet when the car is parked.
 */

internal const val ASSISTANT_TITLE = "운행 비서"
internal const val ASSISTANT_LABEL = "화면 비서 · Gleo 연동 전 규칙 기반"
internal const val ASSISTANT_NOTE = "기기 안에서 규칙으로 처리해요 · 마이크 없음 · 네트워크 없음"
internal const val ASSISTANT_SETTINGS_NOTE = "Gleo 음성 연동은 승인 후 제공돼요 · 지금은 운행 비서에서 같은 세 동작(목적 기록 · 비용 추가 · 요약)을 화면으로 써요"
private const val THINKING_DELAY_MS = 300L

/**
 * The sheet's scrim settles at .45 over the page (spec C); the sheet itself keeps Material's slide. Shared since 2.13 with
 * 운행 상세's side sheet, whose scrim was the page colour at .55, a white veil by day.
 */
internal const val SHEET_SCRIM_ALPHA = .45f

/**
 * The assistant's touch target, chosen by the device and not by the window: 48 dp on the phone, 56 on the car even in a
 * split window narrower than the roomy sheet ([compact]), 60 for the buttons of the roomy car sheet. Chips and bubbles stop
 * at 56 on the car.
 */
internal fun assistantTapTarget(car: Boolean, compact: Boolean): Dp = when {
    !car -> 48.dp
    compact -> 56.dp
    else -> 60.dp
}

/** Phrases the preview card shows; each is one the parser understands today. */
internal val ASSISTANT_EXAMPLES = listOf("방금 운행 업무로 기록해줘", "통행료 3,600원 추가해줘", "이번 달 운행 요약해줘")

/** Evergreen chips in the order they fill in after the log's own suggestions; writes come first so the three actions stay visible. */
private val EVERGREEN_CHIPS = listOf("이번 달 운행 요약해줘", "주차비 4,500원 추가해줘", "미분류 운행 뭐가 남았어?", "지난달 마감 상태 알려줘",
    "오늘 운행 요약해줘", "차량 관리 알려줘", "이번 주 운행 요약해줘", "지난달 운행 요약해줘")

/**
 * Six chips for this moment: the log's own suggestions first, then evergreen questions, never a duplicate. A write chip
 * whose target does not exist yet (no recent trip) is left out rather than shown as a dead end.
 */
internal fun assistantChips(data: TripLogData, now: Long, zone: java.time.ZoneId): List<String> {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return (suggestedPrompts(data, now, zone) + EVERGREEN_CHIPS).distinct().filter { prompt ->
        val intent = parseIntent(prompt, today)
        !writesLog(intent) || answerReadOnly(data, intent, now, zone).citations.isNotEmpty()
    }.take(6)
}

private sealed interface TurnState {
    data object Thinking : TurnState
    data class Confirm(val preview: AssistantAnswer) : TurnState
    data class Done(val answer: AssistantAnswer, val undone: Boolean = false) : TurnState
    data class Failed(val message: String) : TurnState
}

private data class AssistantTurn(val id: Long, val text: String, val state: TurnState)

/**
 * Bottom sheet for the parked car and the phone. [compact] is the window (the phone, or a car window narrower than the
 * roomy sheet): it sets the padding, the sheet's width and the type. The touch targets and the press come from the device
 * ([LocalLayout]'s startAnchored, true on the car), so a split car window keeps 56-dp targets and the car's press. The host
 * decides when it may be open; passing `open = false` composes nothing, so flipping it when the car starts moving removes
 * the sheet at once. [initialText] pre-fills the field when the sheet is opened from an example phrase.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AssistantSheet(
    open: Boolean,
    onDismiss: () -> Unit,
    data: TripLogData,
    now: Long,
    writer: AssistantWriter,
    act: Act,
    followUp: (FollowUp) -> Unit,
    onOpenTrip: (String) -> Unit,
    compact: Boolean = false,
    initialText: String? = null,
) {
    if (!open) return
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface, sheetMaxWidth = if (compact) BottomSheetDefaults.SheetMaxWidth else 960.dp,
        scrimColor = MaterialTheme.colorScheme.scrim.copy(alpha = SHEET_SCRIM_ALPHA),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Scene.outline) }) {
        AssistantBody(data, now, writer, act, followUp, onOpenTrip, onDismiss, compact,
            Modifier.fillMaxHeight(if (compact) 0.92f else 0.88f), initialText)
    }
}

/** The sheet's content without the sheet, for hosts that already have a panel to put it in (wide car window, phone page). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AssistantBody(
    data: TripLogData,
    now: Long,
    writer: AssistantWriter,
    act: Act,
    followUp: (FollowUp) -> Unit,
    onOpenTrip: (String) -> Unit,
    onClose: (() -> Unit)?,
    compact: Boolean,
    modifier: Modifier = Modifier,
    initialText: String? = null,
) {
    val colors = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val reduce = LocalReduceMotion.current
    val pad = if (compact) 16.dp else 28.dp
    // The device, not the window, sets the targets: a car window split narrower than the roomy sheet is still the car.
    val tap = assistantTapTarget(car = LocalLayout.current.startAnchored, compact = compact)
    var input by rememberSaveable { mutableStateOf(initialText.orEmpty()) }
    val turns = remember { mutableStateListOf<AssistantTurn>() }
    var busy by remember { mutableStateOf(false) }
    val latestData by rememberUpdatedState(data)
    val latestNow by rememberUpdatedState(now)
    val scroll = rememberScrollState()
    val today = remember(now) { Instant.ofEpochMilli(now).atZone(zone).toLocalDate() }
    val chips = remember(data, now) { assistantChips(data, now, zone) }
    val tooLong = input.length > ASSISTANT_MAX_INPUT
    // The most recent change still standing, wherever it sits in the conversation.
    val lastChange = turns.asReversed().firstNotNullOfOrNull { (it.state as? TurnState.Done)?.takeIf { s -> s.answer.changed && !s.undone }?.answer }

    fun replace(id: Long, state: TurnState) {
        val index = turns.indexOfFirst { it.id == id }
        if (index >= 0) turns[index] = turns[index].copy(state = state)
    }

    // Runs the writer for one turn. Errors stay inside the bubble: nothing here is worth a modal.
    fun execute(id: Long, text: String) {
        replace(id, TurnState.Thinking)
        busy = true
        act {
            try {
                val answer = writer.run(text, latestNow)
                if (answer.changed) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                replace(id, TurnState.Done(answer))
            } catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { replace(id, TurnState.Failed(e.message ?: "입력 내용을 확인해 주세요.")) }
            catch (_: Exception) { replace(id, TurnState.Failed("처리하지 못했어요. 잠시 후 다시 시도해 주세요.")) }
            finally { busy = false }
        }
    }

    // A write shows what it understood first; a question runs right away.
    fun submit(raw: String) {
        val text = raw.trim().replace(Regex("\\s+"), " ")
        if (text.isEmpty() || text.length > ASSISTANT_MAX_INPUT || busy) return
        input = ""
        val id = System.nanoTime()
        val intent = parseIntent(text, today)
        if (writesLog(intent)) {
            val preview = answerReadOnly(latestData, intent, latestNow, zone)
            if (preview.citations.isNotEmpty() && writer.canWrite) { turns += AssistantTurn(id, text, TurnState.Confirm(preview)); return }
        }
        turns += AssistantTurn(id, text, TurnState.Thinking)
        execute(id, text)
    }

    fun undo(id: Long, answer: AssistantAnswer) {
        busy = true
        act {
            try { writer.undo(answer); replace(id, TurnState.Done(answer, undone = true)) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { replace(id, TurnState.Failed("되돌리지 못했어요. 운행 상세에서 직접 고칠 수 있어요.")) }
            finally { busy = false }
        }
    }

    // The sheet sits above the host's snackbar, so undo lives in the bubble while it is open. However the sheet goes
    // away (닫기, swipe, back, the car starting to move), the latest change not taken back goes to the follow-up bar
    // once, with the same undo, so the person never loses the way back.
    val latestChange by rememberUpdatedState(lastChange)
    val latestFollowUp by rememberUpdatedState(followUp)
    DisposableEffect(Unit) {
        onDispose {
            val change = latestChange ?: return@onDispose
            val first = Regex("^.*?요\\.").find(change.text)?.value ?: change.text
            latestFollowUp(FollowUp("운행 비서: ${first.take(60)}", "실행 취소") { writer.undo(change) })
        }
    }

    LaunchedEffect(turns.size, turns.lastOrNull()?.state) {
        if (turns.isEmpty()) return@LaunchedEffect
        if (reduce) scroll.scrollTo(scroll.maxValue) else scroll.animateScrollTo(scroll.maxValue)
    }

    Column(modifier.fillMaxWidth().imePadding().padding(horizontal = pad).padding(bottom = pad), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
        AssistantHeader(compact, tap, writer.sourceNote, onClose)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
            if (turns.isEmpty()) AssistantIntro(compact, writer.canWrite)
            turns.forEachIndexed { index, turn ->
                UserBubble(turn.text, compact, tap, enabled = !busy) { input = turn.text }
                when (val state = turn.state) {
                    TurnState.Thinking -> ThinkingBubble(compact)
                    is TurnState.Confirm -> ConfirmBubble(state.preview, compact, tap, busy, onOpenTrip,
                        confirm = { execute(turn.id, turn.text) }, revise = { input = turn.text; turns.removeAt(index) })
                    is TurnState.Done -> AnswerBubble(state.answer, state.undone, compact, tap, busy, writer.sourceNote, onOpenTrip,
                        again = { execute(turn.id, turn.text) }, revise = { input = turn.text }, undo = { undo(turn.id, state.answer) },
                        send = { submit(it) })
                    is TurnState.Failed -> FailedBubble(state.message, compact, tap, busy) { execute(turn.id, turn.text) }
                }
            }
        }
        if (!writer.canWrite) Muted("차량과 연결되면 기록할 수 있어요. 지금은 요약과 확인만 돼요.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            chips.forEach { prompt ->
                val writes = writesLog(parseIntent(prompt, today))
                PromptChip(prompt, tap, enabled = !busy && (writer.canWrite || !writes)) { submit(prompt) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(input, { input = it.take(ASSISTANT_MAX_INPUT + 20) }, Modifier.weight(1f).heightIn(min = tap), singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge, shape = RoundedCornerShape(18.dp), isError = tooLong,
                placeholder = { Text("예: 주차비 4,500원 추가해줘", color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingText = if (tooLong) { { Text("${ASSISTANT_MAX_INPUT}자까지 알아들을 수 있어요 · 지금 ${input.length}자", color = colors.error) } } else null,
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = colors.surfaceContainerLow, focusedContainerColor = colors.surface,
                    unfocusedBorderColor = Scene.outline, focusedBorderColor = colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit(input) }))
            Button(onClick = { submit(input) }, enabled = input.isNotBlank() && !tooLong && !busy, shape = RoundedCornerShape(18.dp),
                modifier = Modifier.heightIn(min = tap).semantics { contentDescription = "보내기" }) {
                Text("보내기", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clearAndSetSemantics {})
            }
        }
    }
}

/**
 * A tappable surface the car and the phone share (spec C). The device decides the press, not the window: on the car (the
 * start-anchored layout, a split window included) it settles to .98 with no ripple ([carTap]); on the phone to .97 with
 * Material's bounded ripple. One interaction source per surface, shared with its clickable.
 */
@Composable private fun TapSurface(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, shape: Shape,
    color: Color, contentColor: Color, border: BorderStroke? = null, content: @Composable () -> Unit) {
    val source = remember { MutableInteractionSource() }
    if (LocalLayout.current.startAnchored) Surface(modifier.carTap(source, enabled = enabled, onClick = onClick), shape = shape, color = color,
        contentColor = contentColor, border = border, content = content)
    else Surface(onClick = onClick, modifier = modifier.pressable(source, .97f), enabled = enabled, shape = shape, color = color, contentColor = contentColor,
        border = border, interactionSource = source, content = content)
}

@Composable private fun AssistantHeader(compact: Boolean, tap: Dp, sourceNote: String?, onClose: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(ASSISTANT_TITLE, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.semantics { heading() })
                Surface(shape = RoundedCornerShape(10.dp), color = Scene.fill, contentColor = Scene.textSecondary, border = BorderStroke(1.dp, Scene.divider)) {
                    Text(ASSISTANT_LABEL, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Muted(listOfNotNull(ASSISTANT_NOTE, sourceNote).joinToString(" · "))
        }
        if (onClose != null) {
            if (compact) TextButton(onClick = onClose, Modifier.heightIn(min = tap)) { Text("닫기") }
            else QuietAction("닫기", onClick = onClose)
        }
    }
}

@Composable private fun AssistantIntro(compact: Boolean, canWrite: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("무엇을 도와드릴까요? 아래 제안을 누르거나 짧게 적어 주세요.", style = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium)
        Muted(if (canWrite) "할 수 있는 일 · 운행 목적 기록 · 비용 추가 · 요약, 차량 기한, 월 마감 확인. 바꾸는 일은 먼저 어떻게 이해했는지 보여 드리고, 누르신 뒤에만 기록해요."
            else "할 수 있는 일 · 요약, 차량 기한, 월 마감 확인. 목적 기록과 비용 추가는 차량과 연결된 뒤에 돼요.")
    }
}

/** The sentence the person sent; tapping it puts the words back in the field to adjust and resend. [tap] is the device's target; the bubble stops at 56 dp. */
@Composable private fun UserBubble(text: String, compact: Boolean, tap: Dp, enabled: Boolean, onRevise: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        TapSurface(onClick = onRevise, enabled = enabled, shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomStart = 22.dp, bottomEnd = 6.dp),
            color = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
            modifier = Modifier.widthIn(max = if (compact) 320.dp else 560.dp).heightIn(min = tap.coerceAtMost(56.dp))
                .semantics { contentDescription = "보낸 말: $text. 눌러서 고쳐 묻기" }) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
        }
    }
}

@Composable private fun AssistantSurface(modifier: Modifier = Modifier, compact: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = 6.dp),
            color = LocalPleosColors.current.surfaceHigh, contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = modifier.widthIn(max = if (compact) 360.dp else 720.dp)) {
            Box(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) { content() }
        }
    }
}

/** Shown only once real work has taken longer than a blink; a rule engine usually answers before this appears. */
@Composable private fun ThinkingBubble(compact: Boolean) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { delay(THINKING_DELAY_MS); visible = true }
    AssistantSurface(Modifier.heightIn(min = 48.dp).alpha(if (visible) 1f else 0f), compact) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ThinkingDots()
            Text("기록을 읽고 있어요", style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary)
        }
    }
}

/** A write waits here: the exact interpretation, the trips it would touch, and one button to let it happen. */
@Composable private fun ConfirmBubble(preview: AssistantAnswer, compact: Boolean, tap: Dp, busy: Boolean, onOpenTrip: (String) -> Unit, confirm: () -> Unit, revise: () -> Unit) {
    val verb = if (preview.intent is AssistantIntent.AddExpense) "추가" else "기록"
    AssistantSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(preview.text, style = MaterialTheme.typography.bodyLarge)
            if (preview.citations.isNotEmpty()) CitationRow(preview.citations, compact, tap, onOpenTrip)
            Muted("이렇게 이해했어요. 맞으면 ${verb}을 눌러 주세요. 기록 뒤에도 바로 되돌릴 수 있어요.")
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = confirm, enabled = !busy, shape = RoundedCornerShape(18.dp), modifier = Modifier.heightIn(min = tap)) { Text(verb, style = MaterialTheme.typography.labelLarge) }
                FilledTonalButton(onClick = revise, enabled = !busy, shape = RoundedCornerShape(18.dp), modifier = Modifier.heightIn(min = tap),
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.surface)) { Text("고치기", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun AnswerBubble(answer: AssistantAnswer, undone: Boolean, compact: Boolean, tap: Dp, busy: Boolean, sourceNote: String?,
    onOpenTrip: (String) -> Unit, again: () -> Unit, revise: () -> Unit, undo: () -> Unit, send: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    var streamed by remember(answer.text) { mutableStateOf(false) }
    val askAmount = answer.intent as? AssistantIntent.AskAmount
    AssistantSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, compact) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StreamingText(answer.text, onDone = { streamed = true })
            if (answer.steps.isNotEmpty() && streamed) StepList(answer.steps, undone)
            if (answer.citations.isNotEmpty() && streamed) CitationRow(answer.citations, compact, tap, onOpenTrip)
            // A cost named without a sum: the usual amounts are one tap away, the field stays open for any other.
            if (askAmount != null && streamed) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // A caption over the chips inside the bubble, not a section label: grey rather than the brand eyebrow.
                Eyebrow("금액 선택 · ${askAmount.type.label}", Scene.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    quickAmounts(askAmount.type).forEach { amount ->
                        PromptChip(formatWon(amount), tap, enabled = !busy, description = "${askAmount.type.label} ${formatWon(amount)} 추가") { send(expensePhrase(askAmount.type, amount)) }
                    }
                }
            }
            if (sourceNote != null && !answer.changed && answer.intent !is AssistantIntent.Unknown) Muted(sourceNote)
            if (undone) Text("되돌렸어요. 기록은 이전 상태예요.", style = MaterialTheme.typography.bodyMedium, color = Scene.textSecondary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (answer.changed && !undone) TextButton(onClick = undo, enabled = !busy, modifier = Modifier.heightIn(min = tap)) { Text("실행 취소", style = MaterialTheme.typography.labelLarge) }
                if (!answer.changed || undone) TextButton(onClick = again, enabled = !busy, modifier = Modifier.heightIn(min = tap),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) { Text("다시 생성", style = MaterialTheme.typography.labelLarge) }
                TextButton(onClick = revise, enabled = !busy, modifier = Modifier.heightIn(min = tap),
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) { Text("고쳐 묻기", style = MaterialTheme.typography.labelLarge) }
            }
        }
    }
}

@Composable private fun FailedBubble(message: String, compact: Boolean, tap: Dp, busy: Boolean, retry: () -> Unit) {
    AssistantSurface(Modifier.semantics { liveRegion = LiveRegionMode.Polite }, compact) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Scene.negative)
            TextButton(onClick = retry, enabled = !busy, modifier = Modifier.heightIn(min = tap)) { Text("다시 시도", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

/** The steps the assistant took, arriving one after another like a checklist being ticked; instant with reduced motion. */
@Composable private fun StepList(steps: List<AssistantStep>, undone: Boolean) {
    val positive = Scene.positive
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEachIndexed { index, step ->
            Appear(index = index) {
                Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { stateDescription = if (undone) "되돌림" else "완료" },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.size(22.dp).background(if (undone) Scene.fill else positive.copy(alpha = .18f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                        Text(if (undone) "–" else "✓", style = MaterialTheme.typography.labelSmall, color = if (undone) Scene.textSecondary else positive,
                            modifier = Modifier.clearAndSetSemantics {})
                    }
                    Text(step.label, style = MaterialTheme.typography.bodyMedium)
                    if (step.detail.isNotBlank()) Text(step.detail, style = MaterialTheme.typography.bodySmall, color = Scene.textSecondary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

/** Trips the answer read or changed, as chips that open the trip itself: the source is always one tap away. [tap] is the device's target; the chips stop at 56 dp. */
@OptIn(ExperimentalLayoutApi::class)
@Composable private fun CitationRow(citations: List<AssistantCitation>, compact: Boolean, tap: Dp, onOpenTrip: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow("출처 · 운행 ${citations.size}건", Scene.textSecondary)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            citations.forEach { citation ->
                TapSurface(onClick = { onOpenTrip(citation.tripId) }, shape = RoundedCornerShape(14.dp), color = colors.surface, contentColor = Scene.text,
                    border = BorderStroke(1.dp, Scene.outline),
                    modifier = Modifier.heightIn(min = tap.coerceAtMost(56.dp)).semantics { contentDescription = "운행 상세 보기: ${citation.label}" }) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(citation.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (compact) 260.dp else 420.dp))
                        Text("›", style = MaterialTheme.typography.labelMedium, color = Scene.textSecondary, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
            }
        }
    }
}

/**
 * One suggested sentence; disabled chips keep their words visible so the person still learns what can be asked. [tap] is
 * the height ([assistantTapTarget]); the press follows the device ([TapSurface]).
 */
@Composable private fun PromptChip(text: String, tap: Dp, enabled: Boolean, modifier: Modifier = Modifier, description: String? = null, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    TapSurface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(18.dp), color = if (enabled) colors.surfaceContainerHigh else colors.surfaceContainerLow,
        border = BorderStroke(1.dp, Scene.outline), contentColor = if (enabled) Scene.text else Scene.textSecondary,
        modifier = modifier.heightIn(min = tap.coerceAtLeast(48.dp)).semantics {
            if (description != null) contentDescription = description
            if (!enabled) stateDescription = "차량 연결 후 사용"
        }) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ───────────────────────── 홈·설정 진입점 ─────────────────────────

/**
 * 운행 비서 in 설정 › 기록 (2.12; the car is its only caller): three of the phrases the sheet understands, each a chip that
 * opens it with the words already in the field ([onOpenWith]; TalkBack names that action), and 운행 비서 열기, the card's one
 * solid action, which opens it empty. The title carries what the assistant is ([ASSISTANT_LABEL], a tag that wraps under the
 * title at a large font). The last row is the ⓘ of how it works today ([ASSISTANT_NOTE]); unfolded, where Gleo's voice
 * stands. [compact] (below EXPANDED) shows two phrases; chips and the button are 56-dp targets in every window.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssistantPreviewCard(compact: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier, onOpenWith: ((String) -> Unit)? = null) {
    CarCard(modifier, title = {
        CarSectionTitle(ASSISTANT_TITLE, icon = painterResource(R.drawable.ic_assistant)) { CarTag(ASSISTANT_LABEL) }
    }) {
        CarLine(SettingsCopy.ASSISTANT_LINE)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            (if (compact) ASSISTANT_EXAMPLES.take(2) else ASSISTANT_EXAMPLES).forEach { phrase ->
                CarExampleChip("“$phrase”", onClick = { onOpenWith?.invoke(phrase) ?: onOpen() }, actionLabel = SettingsCopy.ASSISTANT_CHIP_ACTION)
            }
        }
        PrimaryAction("운행 비서 열기", Modifier.fillMaxWidth(), onClick = onOpen)
        CarInfoRow(ASSISTANT_NOTE, more = SettingsCopy.ASSISTANT_MORE)
    }
}

/** The one line that replaces the Gleo card in settings. */
@Composable
fun AssistantSettingsNote(modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = RoundedCornerShape(10.dp), color = LocalPleosColors.current.gleoBubble.copy(alpha = .35f)) {
            Text("Gleo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
        }
        Muted(ASSISTANT_SETTINGS_NOTE, Modifier.weight(1f))
    }
}
