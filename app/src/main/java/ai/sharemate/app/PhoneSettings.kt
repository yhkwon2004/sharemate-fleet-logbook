package ai.sharemate.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.ZoneId

/*
 * Settings and the first-run screen. Both put the car link first and leave "차량 연결 해제" one tap from the top, so leaving
 * is as easy as joining. A wrong pairing code is an inline sentence under the card, not a dialog; success is a bar.
 */

/** What the phone says when pairing fails for a reason other than the car's own sentence. */
internal const val PAIR_UNREACHABLE = "차량에 닿지 않아요. 주소와 포트, 같은 Wi-Fi·핫스팟인지 확인해 주세요."

/**
 * The pairing card with its inline error and its own loading state. [pair] throws IllegalArgumentException with the
 * car's sentence ("연결 번호가 달라요…"), which lands under the code field instead of in a modal.
 */
@Composable internal fun PhonePairingBlock(busy: Boolean, pair: suspend (String, String) -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val followUp = LocalFollowUp.current
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PhoneConnectCard(busy || working) { endpoint, code ->
            error = null
            scope.launch {
                working = true
                try { pair(endpoint, code); followUp(FollowUp("차량과 연결됐어요 · 기록을 받고 있어요")) }
                catch (e: CancellationException) { throw e }
                catch (e: IllegalArgumentException) { error = e.message ?: PAIR_UNREACHABLE }
                catch (_: Exception) { error = PAIR_UNREACHABLE }
                finally { working = false }
            }
        }
        if (working) Muted("차량에 연결하는 중…", Modifier.padding(horizontal = 6.dp).semantics { liveRegion = LiveRegionMode.Polite })
        InlineError(error, Modifier.padding(horizontal = 6.dp))
    }
}

/** Full-screen settings: car link (connect or disconnect), card alerts and statements, the logbook file, and what the app does. */
@Composable internal fun PhoneSettingsPage(data: TripLogData, link: PhoneLink, paired: Boolean, busy: Boolean, pair: suspend (String, String) -> Unit,
    unpair: suspend () -> Unit, cardAlerts: Boolean, queued: Int, openAlertSettings: () -> Unit, importStatement: () -> Unit, pickFile: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val followUp = LocalFollowUp.current
    val zone = remember { ZoneId.systemDefault() }
    var unpairing by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        PhoneRouteHeader("설정", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (paired) Panel(padding = 20.dp) {
                Eyebrow("차량 연결")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PhoneLinkBadge(link)
                    Text(link.message, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                }
                lastSeenText(link.lastSeenAt, System.currentTimeMillis(), zone)?.let { Muted("마지막 수신 $it" + (link.live?.odometerKm?.let { km -> " · 계기판 ${kmText(km)}" } ?: "")) }
                Muted("차량이 원본이에요. 여기서 바꾼 목적·경비·특이사항은 차량에 ‘휴대폰’ 출처로 기록되고 다시 내려와요.")
                PhoneLoadingButton("차량 연결 해제", loading = unpairing, loadingLabel = "해제하는 중…", outlined = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    scope.launch {
                        unpairing = true
                        try { unpair(); followUp(FollowUp("차량 연결을 해제했어요 · 보던 사본은 남아 있어요")) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { followUp(FollowUp("연결을 해제하지 못했어요 · 다시 시도해 주세요")) }
                        finally { unpairing = false }
                    }
                }
                Muted("다시 연결하려면 차량 화면의 새 6자리 번호가 필요해요.")
            } else PhonePairingBlock(busy, pair)

            PhoneCardLinkCard(cardAlerts, queued, busy, openAlertSettings, importStatement)

            Panel(padding = 20.dp) {
                Eyebrow("운행파일")
                Text(if (paired) "연결이 닿지 않는 곳에서는 파일로 받을 수 있어요" else "차량 앱 › 월간·Excel › ‘휴대폰용 운행파일 저장’ 후 이 휴대폰으로 가져오세요",
                    style = MaterialTheme.typography.titleSmall)
                Muted("휴대폰의 사본을 파일 내용으로 교체해요. 차량 원본에는 영향을 주지 않고, 교체 전에 한 번 확인해요.")
                OutlinedButton(onClick = pickFile, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text("운행파일 가져오기") }
            }

            Panel(padding = 20.dp) {
                Eyebrow("이 앱에 대해")
                Text(listOf(data.profile.companyName, data.profile.plateNumber).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "법인차 운행일지 · 휴대폰 사본" },
                    style = MaterialTheme.typography.titleSmall)
                Muted("운행은 차량이 기록하고 휴대폰은 확인과 입력을 맡아요. 목적·경비·사고 기록은 차량에 ‘휴대폰’ 출처로 남고, 바꾼 것은 바로 실행 취소할 수 있어요.")
                Muted("카드 알림은 금액·가맹점·시각·끝 4자리만 읽고 원문은 저장하지 않아요. 외부 서버는 없고 차량과 같은 네트워크 안에서만 통신해요.")
                Muted("Excel과 정산 참고 금액은 신고·지급 확정 자료가 아니에요.")
            }
        }
    }
}

/** The screen before the first trip: what the phone is for, the pairing card (or the link status), and the file fallback. No tab bar yet. */
@Composable internal fun PhoneConnectScreen(state: TripLogState, link: PhoneLink, paired: Boolean, busy: Boolean, pair: suspend (String, String) -> Unit,
    pickFile: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SharemateTheme(compact = true, dark = true) {
            Panel(padding = 22.dp) {
                Eyebrow("운행은 차량에서, 확인과 입력은 휴대폰에서")
                Text(if (paired) "차량과 연결됐어요\n첫 운행을 기다리고 있어요" else "차량과 연결하면\n운행이 실시간으로 와요", style = MaterialTheme.typography.headlineMedium)
                VehicleDrawing(Modifier.fillMaxWidth().height(140.dp))
            }
        }
        if (!paired) PhonePairingBlock(busy, pair)
        else Panel(padding = 22.dp) {
            Eyebrow("연결 상태")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PhoneLinkBadge(link)
                Text(link.message, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            }
            Muted("차량이 출발하면 운행이 이 화면에 바로 나타나요. 운행 목적·경비·특이사항은 여기서 입력할 수 있어요. 연결 해제는 오른쪽 위 설정에 있어요.")
        }
        Muted("연결할 수 없는 환경이면 차량 앱의 월간·Excel에서 ‘휴대폰용 운행파일 저장’ 후 이 휴대폰으로 파일을 가져올 수 있어요.")
        OutlinedButton(onClick = pickFile, enabled = !busy && !state.loadFailed, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), shape = RoundedCornerShape(18.dp)) { Text("운행파일 가져오기") }
        if (state.loadFailed) InlineError("저장된 사본을 읽지 못했어요. 기존 파일을 보존하고 있어요.")
    }
}
