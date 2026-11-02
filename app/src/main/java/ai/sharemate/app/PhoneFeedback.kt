package ai.sharemate.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/*
 * Phone feedback, one system for every tab: a success is a bar at the bottom with its undo, an error sits next to the
 * field that caused it, and a modal is kept for the few things that cannot be taken back (replacing the copy, a
 * damaged file, an accident record). The bar reuses the car's FollowUp so the PhoneSyncUi cards speak through
 * LocalFollowUp without knowing which device they are on.
 */

/**
 * The snackbar host state and the FollowUp dispatcher the shell provides as LocalFollowUp. A follow-up with an action
 * stays long enough to be tapped (Long); a plain confirmation goes after a few seconds. The action runs through
 * [run], so a failed undo surfaces like any other failed command instead of vanishing.
 */
@Composable internal fun rememberPhoneFeedback(run: (suspend () -> Unit) -> Unit = { }): Pair<SnackbarHostState, (FollowUp) -> Unit> {
    val host = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val latestRun by rememberUpdatedState(run)
    val dispatch = remember(host) {
        { item: FollowUp ->
            scope.launch {
                host.currentSnackbarData?.dismiss()
                val result = host.showSnackbar(item.message, item.action, duration = if (item.action != null) SnackbarDuration.Long else SnackbarDuration.Short)
                if (result == SnackbarResult.ActionPerformed) item.perform?.let { latestRun(it) }
            }
            Unit
        }
    }
    return host to dispatch
}

/** Bottom bar host for the phone: the car's FollowUpBar, sliding up 120 ms (instant under reduced motion), kept above the tab bar by the caller. */
@Composable internal fun PhoneSnackbarHost(host: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(host, modifier) { data -> FollowUpBar(data) }
}

/** What the shell says when a command did not reach the car; the same command is offered again. */
internal fun sendFailureFollowUp(retry: suspend () -> Unit) = FollowUp("차량에 전달하지 못했어요", "다시 시도", retry)

/** Runs [block], turning thrown messages into [onError]; the phone never shows a raw exception. */
internal fun phoneRun(scope: kotlinx.coroutines.CoroutineScope, onError: (String) -> Unit, onBusy: (Boolean) -> Unit = {}, block: suspend () -> Unit) {
    scope.launch {
        onBusy(true)
        try { block() }
        catch (e: CancellationException) { throw e }
        catch (e: IllegalArgumentException) { onError(e.message ?: "입력 내용을 확인해 주세요.") }
        catch (_: Exception) { onError("처리하지 못했어요. 다시 시도해 주세요.") }
        finally { onBusy(false) }
    }
}

/**
 * A button that shows real work: its size never changes, the label swaps to [loadingLabel] with a small ring while
 * [loading]. Disabled while loading so a second tap cannot send the same command twice.
 */
@Composable internal fun PhoneLoadingButton(label: String, loading: Boolean, modifier: Modifier = Modifier, loadingLabel: String = label,
    enabled: Boolean = true, outlined: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.semantics { if (loading) stateDescription = loadingLabel }) {
            if (loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Text(if (loading) loadingLabel else label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
    if (outlined) OutlinedButton(onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) { content() }
    else Button(onClick = onClick, enabled = enabled && !loading, modifier = modifier.heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)) { content() }
}

/**
 * A quiet banner for a state the user should know about but cannot fix with one tap, such as the car being out of
 * reach. It can be closed; it returns by itself only when the state changes. Live region so a screen reader hears it.
 */
@Composable internal fun PhoneBanner(visible: Boolean, text: String, modifier: Modifier = Modifier, action: String? = null,
    onAction: (() -> Unit)? = null, onDismiss: (() -> Unit)? = null, tone: Color = MaterialTheme.colorScheme.surfaceVariant) {
    val reduce = LocalReduceMotion.current
    AnimatedVisibility(visible, modifier,
        enter = if (reduce) fadeIn(snap()) else fadeIn(tween(120, easing = FastOutSlowInEasing)) + slideInVertically(tween(120, easing = FastOutSlowInEasing)) { -it / 2 },
        exit = if (reduce) fadeOut(snap()) else fadeOut(tween(100)) + slideOutVertically(tween(100)) { -it / 2 }) {
        Surface(shape = RoundedCornerShape(16.dp), color = tone, modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 6.dp))
                if (action != null && onAction != null) TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
                if (onDismiss != null) IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "알림 닫기", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

/** Inline error under a field: the message sits where the mistake is, in the error colour, read as a live region. */
@Composable internal fun InlineError(text: String?, modifier: Modifier = Modifier) {
    if (text == null) return
    Text(text, modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

/** A section title on the phone: eyebrow plus optional trailing text, marked as a heading. */
@Composable internal fun PhoneSectionTitle(title: String, modifier: Modifier = Modifier, trailing: String? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { heading() })
        if (trailing != null) Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Empty-state copy block, centred, without a spinner or illustration that pretends something is coming. */
@Composable internal fun PhoneEmpty(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Box(modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (detail != null) Muted(detail)
        }
    }
}
