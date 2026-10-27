package ai.sharemate.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PhoneActivity : ComponentActivity() {
    private val model: PhoneViewModel by viewModels()
    private val photo = registerForActivityResult(ActivityResultContracts.TakePicture()) { model.finishCapture(it) }
    private val video = registerForActivityResult(object : ActivityResultContracts.CaptureVideo() {
        override fun createIntent(context: Context, input: Uri): Intent = super.createIntent(context, input)
            .putExtra(MediaStore.EXTRA_DURATION_LIMIT, 120).putExtra(MediaStore.EXTRA_SIZE_LIMIT, 100_000_000L)
    }) { model.finishCapture(it) }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        Toast.makeText(this, if (granted) "촬영 확인 안내를 이 휴대폰으로 알려드려요." else "시스템 알림이 꺼져 있어 앱 안에서 확인할 수 있어요.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)) {
            Toast.makeText(this, "외관 촬영은 휴대폰 앱에서 이용해 주세요.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        setContent {
            val state by model.state.collectAsState()
            SharemateTheme(compact = true) {
                PhoneApp(model, state, capture = { stage, view ->
                    model.prepareCapture(stage, view) { uri ->
                        try { if (view == EvidenceView.WALKAROUND) video.launch(uri) else photo.launch(uri) }
                        catch (_: Exception) { model.finishCapture(false); model.reportError("촬영 앱을 열지 못했습니다. 카메라 앱이 설치되어 있는지 확인해 주세요.") }
                    }
                }, previewVideo = { record, item ->
                    try { startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(model.mediaUri(record, item), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                    catch (_: Exception) { model.reportError("영상 재생 앱을 열지 못했습니다. 원본은 휴대폰에 보관되어 있습니다.") }
                }, enableNotifications = {
                    if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else Toast.makeText(this, "기기의 SHAREMATE 알림 설정을 따릅니다.", Toast.LENGTH_SHORT).show()
                }, connectionContent = { visible ->
                    MobileConnectionPanel(onSessionChanged = model::setLinkedSession, inspectionSummary = model.exportSummary(), visible = visible)
                })
            }
        }
    }
}

@Composable
fun PhoneApp(model: PhoneViewModel, state: PhoneState, capture: (InspectionStage, EvidenceView) -> Unit,
    previewVideo: (InspectionRecord, EvidenceItem) -> Unit, enableNotifications: () -> Unit,
    connectionContent: @Composable (Boolean) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var history by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<Pair<InspectionRecord, EvidenceItem>?>(null) }
    val record = state.data.selected
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SHAREMATE /", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                    Text("YOUR SPACE. YOUR PACE.", fontSize = 10.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = enableNotifications) { Text("알림 설정") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("내 차량" to 0, "촬영 기록" to 1).forEach { (label, value) ->
                    if (tab == value) Button(onClick = { tab = value }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
                    else OutlinedButton(onClick = { tab = value }, modifier = Modifier.weight(1f).heightIn(min = 52.dp), shape = RoundedCornerShape(14.dp)) { Text(label) }
                }
            }
            if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 680.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    connectionContent(tab == 0)
                    if (state.loading) CircularProgressIndicator()
                    else if (state.loadFailed) PhoneCard {
                        Text("촬영 기록을 열지 못했어요", style = MaterialTheme.typography.titleLarge)
                        Text("저장된 원본을 보호하고 있습니다. 앱을 다시 실행해 확인해 주세요.")
                    }
                    else if (tab == 0) {
                        PhoneCard(dark = true) {
                            Text("01 BEFORE  /  02 DRIVE  /  03 AFTER", fontSize = 12.sp, letterSpacing = 1.4.sp, color = MaterialTheme.colorScheme.primary)
                            VehicleDrawing(Modifier.fillMaxWidth().height(132.dp))
                            Text("타기 전부터,\n돌려줄 때까지.", fontSize = 30.sp, lineHeight = 39.sp, fontWeight = FontWeight.Bold)
                            Text("차량을 한 바퀴 돌며 사진과 영상을 남겨 보세요. 기록과 확인 결과를 한곳에서 볼 수 있어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { if (record == null) model.newRecord(); tab = 1 }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp), shape = RoundedCornerShape(14.dp)) {
                                Text(if (record == null) "새 촬영 기록 시작" else "촬영 기록 열기")
                            }
                        }
                        PhoneCard {
                            Text("기기 내 사진 비교 · 실험 기능", fontWeight = FontWeight.SemiBold)
                            Text("같은 면의 탑승 전·후 사진에서 달라진 부위를 표시해요. 학습형 AI가 아니며, 긁힘 여부는 원본과 함께 직접 확인해야 해요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("사진·확인 결과 보기에서 OpenAI 사진 분석을 별도로 요청할 수 있습니다. 영상은 원본으로 보관하며 자동 분석하지 않습니다.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("차량 상태 기록", style = MaterialTheme.typography.titleLarge)
                            TextButton(onClick = { history = true }, enabled = state.data.pending == null && !state.busy) { Text("보관함 ${state.data.records.size}") }
                        }
                        if (record == null) PhoneCard {
                            Text("내 차를 둘러보는 5단계", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                            Text("앞면 · 오른쪽 · 뒷면 · 왼쪽 사진과 둘레 영상을 남깁니다. 정차된 차량 주변에서 안전을 확인한 뒤 촬영해 주세요.")
                            Button(onClick = model::newRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp)) { Text("새 촬영 기록 시작") }
                        } else {
                            if (state.data.pending != null) PhoneCard {
                                Text("미완료 촬영이 있어요", fontWeight = FontWeight.Bold)
                                Text("카메라에서 돌아온 결과를 확인하지 못했습니다. 저장된 촬영을 확인하거나 취소해 주세요.")
                                Button(onClick = { model.finishCapture(true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("저장된 촬영 확인") }
                                TextButton(onClick = { model.finishCapture(false) }, enabled = !state.busy) { Text("미완료 촬영 취소") }
                            }
                            RecordBody(record, state, model, capture, onPreview = { item -> if (item.video) previewVideo(record, item) else viewing = record to item })
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(onClick = model::newRecord, enabled = !state.busy && state.data.pending == null, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("다른 이용 새 기록") }
                                TextButton(onClick = { deleting = true }, enabled = !state.busy && state.data.pending == null, modifier = Modifier.heightIn(min = 52.dp)) { Text("이 기록 삭제", color = MaterialTheme.colorScheme.error) }
                            }
                            Text("원본은 이 휴대폰에 보관됩니다. 연결한 차량에는 촬영 완료 여부·확인 결과·메모와 원본 해시를 공유합니다. 실제 반납 승인은 운영사에서 확인해 주세요.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
    if (state.error != null) AlertDialog(onDismissRequest = model::clearError, title = { Text("확인이 필요해요") }, text = { Text(state.error) }, confirmButton = { TextButton(onClick = model::clearError) { Text("확인") } })
    if (history) AlertDialog(onDismissRequest = { history = false }, title = { Text("휴대폰 보관함") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.data.records.isEmpty()) Text("아직 촬영 기록이 없습니다.")
            state.data.records.forEach { item -> OutlinedButton(onClick = { model.selectRecord(item.id); history = false }, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Column { Text(phoneDate(item.createdAt)); Text(if (item.afterComplete) "이용 후 기록 완료" else if (item.beforeComplete) "탑승 전 기록 완료" else "촬영 중", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }, confirmButton = { TextButton(onClick = { history = false }) { Text("닫기") } })
    if (deleting && record != null) AlertDialog(onDismissRequest = { deleting = false }, title = { Text("촬영 원본까지 삭제할까요?") }, text = { Text("이 휴대폰의 사진·영상·판독 결과·메모를 삭제합니다. 복구할 수 없으며, 이미 공유한 차량의 요약은 별도로 남아 있을 수 있습니다.") }, confirmButton = {
        TextButton(onClick = { model.deleteSelected(); deleting = false }) { Text("원본과 기록 삭제", color = MaterialTheme.colorScheme.error) }
    }, dismissButton = { TextButton(onClick = { deleting = false }) { Text("보관하기") } })
    viewing?.let { (selectedRecord, item) -> PhotoReviewDialog(model, selectedRecord, item) { viewing = null } }
}

@Composable private fun RecordBody(record: InspectionRecord, state: PhoneState, model: PhoneViewModel,
    capture: (InspectionStage, EvidenceView) -> Unit, onPreview: (EvidenceItem) -> Unit) {
    var stage by rememberSaveable(record.id) { mutableStateOf(if (record.beforeComplete) InspectionStage.AFTER else InspectionStage.BEFORE) }
    var reviewed by rememberSaveable(record.id, stage, record.evidence.size) { mutableStateOf(false) }
    var incident by rememberSaveable(record.id, record.incidentType) { mutableStateOf(record.incidentType) }
    var note by rememberSaveable(record.id, record.note) { mutableStateOf(record.note) }
    val dirty = incident != record.incidentType || note != record.note
    val locked = record.afterComplete || (stage == InspectionStage.BEFORE && record.beforeComplete)
    val enabled = !state.busy && state.data.pending == null
    PhoneCard(dark = true) {
        Text("${phoneDate(record.createdAt)}  ·  ${record.id.take(8)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (record.afterComplete) "이용 기록을 남겼어요" else if (record.beforeComplete) "이용 후에도 같은 구도로" else "출발 전, 한 바퀴 확인", fontSize = 27.sp, lineHeight = 35.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stage.label, fontSize = 18.sp)
            Text("${record.capturedCount(stage).toString().padStart(2, '0')} / 05", fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(if (record.sessionId != null) "차량 대여 연결 · ${record.sessionId.take(8)}" else "휴대폰 단독 기록 · 차량 미연결", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        if (record.sessionId == null && state.linkedSessionId != null) OutlinedButton(onClick = model::linkCurrentRecord, enabled = enabled, colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("이 기록을 연결한 차량과 공유") }
        if (record.sessionId != null && state.linkedSessionId != null && record.sessionId != state.linkedSessionId) Text("현재 연결 차량과 다른 대여의 기록입니다.", color = Color(0xFFFFC992))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        InspectionStage.entries.forEach { value -> FilterChip(selected = stage == value, onClick = { stage = value }, label = { Text(value.label) }, enabled = value == InspectionStage.BEFORE || record.beforeComplete, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) }
    }
    PhoneCard {
        Text("네 방향 사진 + 둘레 영상", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text("사진에는 해당 면 전체가 들어오게 촬영해 주세요. 이용 후에는 탑승 전 사진과 거리·각도·방향을 맞추면 비교가 쉬워져요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        EvidenceView.entries.forEachIndexed { index, view ->
            val item = record.latest(stage, view)
            if (index != 0) HorizontalDivider()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("0${index + 1}  ${view.label}", fontWeight = FontWeight.SemiBold)
                        Text(if (item == null) "아직 촬영하지 않았어요" else "원본 저장 · ${phoneDate(item.capturedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!locked) OutlinedButton(onClick = { capture(stage, view) }, enabled = enabled, modifier = Modifier.heightIn(min = 52.dp).semantics { contentDescription = "${stage.label} ${view.label} ${if (item == null) "촬영" else "재촬영"}" }) { Text(if (item == null) "촬영" else "재촬영") }
                }
                if (item != null) TextButton(onClick = { onPreview(item) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (item.video) "영상 재생" else "사진·확인 결과 보기") }
                if (stage == InspectionStage.AFTER && !item?.video.orFalse()) record.comparisons[view]?.let { result ->
                    Text(when (result.status) { ComparisonStatus.REVIEW -> "직접 확인 필요 · ${result.boxes.size}곳"; ComparisonStatus.RETAKE -> "재촬영 권장 · 비교 제한"; ComparisonStatus.NO_CANDIDATE -> "뚜렷한 변화 후보 없음 · 직접 확인 필요" }, style = MaterialTheme.typography.bodySmall,
                        color = if (result.status == ComparisonStatus.REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Text("둘레 영상은 최대 2분·100 MB로 요청합니다. 카메라 앱에 따라 제한 적용이 다를 수 있으며 150 MB가 넘는 파일은 저장 완료로 처리하지 않습니다.", style = MaterialTheme.typography.bodySmall)
    }
    PhoneCard {
        Text("발견한 문제 남기기", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
        Text("기존 손상·새로 발견한 긁힘·사고 위치와 상황을 직접 기록해 주세요. 촬영 결과만으로 책임이나 비용을 확정하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        IncidentType.entries.forEach { type ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { RadioButton(selected = incident == type, onClick = { incident = type }, enabled = enabled); TextButton(onClick = { incident = type }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(type.label, modifier = Modifier.fillMaxWidth()) } }
        }
        OutlinedTextField(value = note, onValueChange = { if (it.length <= 1_000) note = it }, label = { Text("위치·발견 시점·상황 메모") }, supportingText = { Text("${note.length} / 1,000") }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = enabled)
        Button(onClick = { model.saveIncident(incident, note) }, enabled = enabled && dirty, modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp)) { Text(if (dirty) "문제·메모 저장" else "저장된 상태") }
    }
    PhoneCard {
        if (locked) {
            Text("${stage.label} 기록 완료", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("촬영 원본은 보관함에서 다시 확인할 수 있어요.")
            if (stage == InspectionStage.BEFORE && !record.afterComplete) Button(onClick = { stage = InspectionStage.AFTER }, modifier = Modifier.fillMaxWidth().heightIn(min = 58.dp)) { Text("이용 후 촬영으로 이동") }
        } else {
            Text("직접 확인하고 마무리", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("사진 비교가 놓치는 손상이나 반사광에 의한 표시가 있을 수 있습니다. '변화 후보 없음'도 무손상을 뜻하지 않습니다.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = reviewed, onCheckedChange = { reviewed = it }, enabled = enabled)
                TextButton(onClick = { reviewed = !reviewed }, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 60.dp)) { Text("원본·판독 제한·차량 상태를 직접 확인했습니다") }
            }
            if (dirty) Text("작성한 문제·메모를 먼저 저장해 주세요.", color = MaterialTheme.colorScheme.error)
            Button(onClick = { model.complete(stage, reviewed) }, enabled = enabled && !dirty && record.canComplete(stage, reviewed), modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp), shape = RoundedCornerShape(16.dp)) { Text("${stage.label} 기록 마무리") }
        }
    }
}

@Composable private fun PhotoReviewDialog(model: PhoneViewModel, record: InspectionRecord, item: EvidenceItem, dismiss: () -> Unit) {
    val state by model.state.collectAsState()
    val current = state.data.records.firstOrNull { it.id == record.id } ?: record
    val ai = current.aiReports.firstOrNull { it.stage == item.stage && it.view == item.view && it.sha256 == item.sha256 }
    var confirmingAi by remember { mutableStateOf(false) }
    var bitmap by remember(record.id, item.name) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(record.id, item.name) { mutableStateOf(false) }
    LaunchedEffect(record.id, item.name) {
        bitmap = withContext(Dispatchers.IO) { runCatching { inspectionBitmap(model.mediaFile(record, item)) }.getOrNull() }
        failed = bitmap == null
    }
    val result = if (item.stage == InspectionStage.AFTER) record.comparisons[item.view] else null
    AlertDialog(onDismissRequest = dismiss, title = { Text("${item.stage.label} · ${item.view.label}") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            bitmap?.let { image ->
                Box(Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height)) {
                    Image(image.asImageBitmap(), contentDescription = "${item.view.label} 촬영 원본", modifier = Modifier.fillMaxSize())
                    if (result != null) Canvas(Modifier.fillMaxSize()) { result.boxes.forEach { box ->
                        drawRect(Color(0xFFFF6536), Offset(box.left * size.width, box.top * size.height), Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height), style = Stroke(3.dp.toPx()))
                    } }
                    if (ai != null) Canvas(Modifier.fillMaxSize()) { ai.findings.forEach { box ->
                        drawRect(Color(0xFF245BEB), Offset(box.left * size.width, box.top * size.height), Size((box.right - box.left) * size.width, (box.bottom - box.top) * size.height), style = Stroke(3.dp.toPx()))
                    } }
                }
            } ?: if (failed) Text("사진을 읽지 못했습니다. 원본 상태를 확인해 주세요.") else CircularProgressIndicator()
            if (result != null) { Text("기기 내 사진 비교 · 실험 기능", fontWeight = FontWeight.SemiBold); Text(result.message); Text("표시는 손상 확률이나 책임 판정이 아닙니다.", style = MaterialTheme.typography.bodySmall) }
            else Text("같은 방향의 이용 후 사진이 저장되면 전·후 사진을 비교합니다.")
            HorizontalDivider()
            Text("AI 손상 의심 확인", fontWeight = FontWeight.Bold)
            if (ai != null) {
                Text(when (ai.status) { ComparisonStatus.REVIEW -> "손상 의심 · 직접 확인 필요"; ComparisonStatus.RETAKE -> "사진 판독 제한 · 재촬영 권장"; ComparisonStatus.NO_CANDIDATE -> "뚜렷한 의심 부위 없음 · 무손상 확인 아님" }, color = MaterialTheme.colorScheme.primary)
                Text(ai.summary)
                ai.findings.forEachIndexed { index, finding -> Text("${index + 1}. ${finding.description}") }
                Text("OpenAI · ${ai.model} · ${phoneDate(ai.analyzedAt)}\n파란 표시 위치는 근사치이며 손상 책임·비용·안전 판정이 아닙니다.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("선택한 사진을 서버를 통해 OpenAI로 보내 확인합니다. 이용 후 사진은 같은 면의 탑승 전 사진도 함께 비교합니다.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { confirmingAi = true }, enabled = !state.busy && current.sessionId != null && current.sessionId == state.linkedSessionId, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(if (state.busy) "AI 응답 확인 중…" else "사진 전송 후 AI 확인") }
                if (current.sessionId == null || current.sessionId != state.linkedSessionId) Text("현재 이용 중인 차량과 기록을 먼저 연결해 주세요.", style = MaterialTheme.typography.bodySmall)
            }
            Text("원본 SHA-256\n${item.sha256}", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = dismiss) { Text("확인") } })
    if (confirmingAi) AlertDialog(onDismissRequest = { confirmingAi = false }, title = { Text("사진을 AI로 확인") },
        text = { Text("선택한 차량 사진${if (item.stage == InspectionStage.AFTER) "과 같은 면의 탑승 전 사진" else ""}을 연결 서버를 거쳐 OpenAI에 전송합니다. 위치 메타데이터는 제외하며 원본 영상은 전송하지 않습니다. API 이용료가 발생할 수 있고 AI가 손상을 놓치거나 반사광을 오인할 수 있습니다.") },
        confirmButton = { TextButton(onClick = { confirmingAi = false; model.analyzePhoto(record.id, item) }) { Text("사진 전송·분석") } },
        dismissButton = { TextButton(onClick = { confirmingAi = false }) { Text("취소") } })
}

@Composable private fun PhoneCard(dark: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surface,
        contentColor = colors.onSurface, tonalElevation = 0.dp, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.background(Brush.linearGradient(if (dark) listOf(colors.primaryContainer, colors.surface) else listOf(colors.surface, colors.surface))).padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

private fun phoneDate(time: Long) = SimpleDateFormat("M.d HH:mm", Locale.KOREAN).format(Date(time))
private fun Boolean?.orFalse() = this == true
