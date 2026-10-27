package ai.sharemate.app

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

data class PhoneState(val data: InspectionData = InspectionData(), val loading: Boolean = true, val busy: Boolean = false,
    val error: String? = null, val loadFailed: Boolean = false, val linkedSessionId: String? = null)

class PhoneViewModel(application: Application) : AndroidViewModel(application) {
    private val store = InspectionStore(application)
    private val lock = Mutex()
    private val mutableState = MutableStateFlow(PhoneState())
    val state = mutableState.asStateFlow()
    private val ready = viewModelScope.launch {
        try {
            val data = withContext(Dispatchers.IO) { store.load() }
            mutableState.update { it.copy(data = data, loading = false) }
        } catch (_: Exception) {
            mutableState.update { it.copy(loading = false, loadFailed = true, error = "보관 기록을 읽지 못했습니다. 원본을 보호하기 위해 새 저장을 중단했습니다.") }
        }
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }
    fun reportError(message: String) { mutableState.update { it.copy(error = message) } }
    fun setLinkedSession(id: String?) {
        if (id == null || validUuid(id)) mutableState.update { it.copy(linkedSessionId = id) }
    }

    fun newRecord() = action {
        require(state.value.data.pending == null) { "미완료 촬영을 먼저 확인해 주세요." }
        require(state.value.data.records.size < 50) { "최대 50개의 이용 기록을 보관할 수 있습니다." }
        val record = InspectionRecord(sessionId = state.value.linkedSessionId)
        persist(state.value.data.copy(records = listOf(record) + state.value.data.records, selectedId = record.id))
    }

    fun selectRecord(id: String) = action {
        require(state.value.data.pending == null) { "미완료 촬영을 먼저 확인해 주세요." }
        require(state.value.data.records.any { it.id == id })
        persist(state.value.data.copy(selectedId = id))
    }

    fun linkCurrentRecord() = change { record ->
        require(record.sessionId == null) { "다른 대여와 연결된 기록은 변경할 수 없습니다. 새 촬영 기록을 시작해 주세요." }
        record.copy(sessionId = requireNotNull(state.value.linkedSessionId) { "차량과 먼저 연결해 주세요." })
    }

    fun prepareCapture(stage: InspectionStage, view: EvidenceView, ready: (Uri) -> Unit) = action {
        val (data, file) = withContext(Dispatchers.IO) { store.prepare(state.value.data, stage, view) }
        mutableState.update { it.copy(data = data) }
        ready(uri(file))
    }

    fun finishCapture(success: Boolean) = action {
        val pending = state.value.data.pending
        val data = withContext(Dispatchers.IO) { store.finishCapture(state.value.data, success) }
        mutableState.update { it.copy(data = data) }
        if (success && pending?.stage == InspectionStage.AFTER && pending.view != EvidenceView.WALKAROUND) {
            compare(pending.view)
        }
    }

    fun compareAgain(view: EvidenceView) = action { compare(view) }

    fun analyzePhoto(recordId: String, item: EvidenceItem) = action {
        val record = requireNotNull(state.value.data.selected)
        require(record.id == recordId && !item.video && record.latest(item.stage, item.view)?.sha256 == item.sha256) { "현재 촬영 원본을 다시 선택해 주세요." }
        require(record.sessionId != null && record.sessionId == state.value.linkedSessionId) { "현재 이용 중인 차량과 기록을 먼저 연결해 주세요." }
        val link = DeviceLink(getApplication(), "phone")
        val config = withContext(Dispatchers.IO) { link.load() } ?: error("휴대폰 연결 정보가 없습니다.")
        val reference = if (item.stage == InspectionStage.AFTER) record.latest(InspectionStage.BEFORE, item.view) else null
        require(record.aiReports.none { it.stage == item.stage && it.view == item.view && it.sha256 == item.sha256 && it.referenceSha256 == reference?.sha256 }) { "이 사진의 AI 결과가 이미 저장되어 있습니다." }
        // Re-encoding the bitmap omits EXIF/location metadata; the original stays on the phone.
        fun encoded(file: File): String {
            val bitmap = inspectionBitmap(file, 1280)
            try {
                val output = java.io.ByteArrayOutputStream()
                require(bitmap.compress(Bitmap.CompressFormat.JPEG, 85, output))
                require(output.size() <= 1_500_000) { "사진이 너무 큽니다. 다시 촬영해 주세요." }
                return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            } finally { bitmap.recycle() }
        }
        val payload = withContext(Dispatchers.IO) { JSONObject().put("recordId", record.id).put("sessionId", record.sessionId)
            .put("stage", item.stage.name).put("view", item.view.name).put("sha256", item.sha256)
            .put("referenceSha256", reference?.sha256 ?: JSONObject.NULL)
            .put("photoBase64", encoded(store.media(record.id, item.name)))
            .put("referenceBase64", reference?.let { encoded(store.media(record.id, it.name)) } ?: JSONObject.NULL) }
        link.request(config, "/v1/inspection", recordJson(record))
        val response = link.request(config, "/v1/analyze", payload)
        val report = parseAiReport(response)
        require(report.sha256 == item.sha256 && report.referenceSha256 == reference?.sha256 && report.stage == item.stage && report.view == item.view) { "다른 촬영의 응답입니다. 저장하지 않았습니다." }
        val next = record.copy(aiReports = record.aiReports.filterNot { it.stage == item.stage && it.view == item.view } + report, updatedAt = System.currentTimeMillis())
        persist(state.value.data.copy(records = state.value.data.records.map { if (it.id == record.id) next else it }))
        if (report.status != ComparisonStatus.NO_CANDIDATE) notifyIssue("${item.view.label} AI 확인 결과", if (report.status == ComparisonStatus.RETAKE) "판독이 어려워요. 안내와 원본을 확인해 주세요." else "손상 의심 부위를 원본과 함께 직접 확인해 주세요.")
    }

    private suspend fun compare(view: EvidenceView) {
        val record = requireNotNull(state.value.data.selected)
        val before = requireNotNull(record.latest(InspectionStage.BEFORE, view)) { "탑승 전 사진이 필요합니다." }
        val after = requireNotNull(record.latest(InspectionStage.AFTER, view)) { "이용 후 사진이 필요합니다." }
        val result = withContext(Dispatchers.Default) {
            val first = inspectionBitmap(store.media(record.id, before.name), 384)
            val second = inspectionBitmap(store.media(record.id, after.name), 384)
            try {
                if (abs(first.width.toFloat() / first.height - second.width.toFloat() / second.height) > 0.08f) {
                    ComparisonResult(ComparisonStatus.RETAKE, "사진 방향과 구도가 다릅니다. 탑승 전 사진과 같은 방향으로 다시 촬영해 주세요.", emptyList())
                } else {
                    val scaled = Bitmap.createScaledBitmap(second, first.width, first.height, true)
                    try { DamageComparison.compare(grayscale(first), grayscale(scaled), first.width, first.height) }
                    finally { if (scaled !== second) scaled.recycle() }
                }
            } finally { first.recycle(); second.recycle() }
        }
        persist(state.value.data.copy(records = state.value.data.records.map { if (it.id == record.id) it.copy(
            comparisons = it.comparisons + (view to result), updatedAt = System.currentTimeMillis()) else it }))
        if (result.status == ComparisonStatus.REVIEW) notifyIssue("${view.label} 사진에서 변화가 보여요", "표시된 부위를 직접 확인하고 손상 여부를 기록해 주세요.")
    }

    fun saveIncident(type: IncidentType, note: String) = change { record ->
        require(note.length <= 1_000) { "메모는 1,000자 이하로 작성해 주세요." }
        require(type == IncidentType.NONE || note.isNotBlank()) { "발견 위치와 상황을 간단히 남겨 주세요." }
        record.copy(incidentType = type, note = note.trim())
    }

    fun complete(stage: InspectionStage, reviewed: Boolean) = change { record ->
        require(record.canComplete(stage, reviewed)) { "네 방향 사진과 둘레 영상을 촬영하고 직접 확인을 완료해 주세요." }
        store.assertMediaPresent(record, stage)
        if (stage == InspectionStage.BEFORE) record.copy(beforeComplete = true) else record.copy(afterComplete = true)
    }

    fun deleteSelected() = action {
        val record = requireNotNull(state.value.data.selected)
        val data = withContext(Dispatchers.IO) { store.deleteRecord(state.value.data, record.id) }
        mutableState.update { it.copy(data = data) }
    }

    fun mediaUri(record: InspectionRecord, item: EvidenceItem): Uri = uri(store.media(record.id, item.name))
    fun mediaFile(record: InspectionRecord, item: EvidenceItem): File = store.media(record.id, item.name)
    fun exportSummary(): JSONObject? = state.value.data.selected?.let { recordJson(it) }
    private fun uri(file: File): Uri = FileProvider.getUriForFile(getApplication(), "${getApplication<Application>().packageName}.evidence", file)

    private fun change(edit: suspend (InspectionRecord) -> InspectionRecord) = action {
        val current = requireNotNull(state.value.data.selected) { "새 촬영 기록을 먼저 시작해 주세요." }
        require(state.value.data.pending == null) { "미완료 촬영을 먼저 확인해 주세요." }
        val next = withContext(Dispatchers.IO) { edit(current).copy(updatedAt = System.currentTimeMillis()) }
        persist(state.value.data.copy(records = state.value.data.records.map { if (it.id == current.id) next else it }))
        if (next.incidentType != IncidentType.NONE && (next.incidentType != current.incidentType || next.note != current.note)) {
            notifyIssue("${next.incidentType.label}을 기록했어요", "촬영 원본과 위치 메모를 확인해 주세요.")
        }
    }

    private suspend fun persist(data: InspectionData) {
        withContext(Dispatchers.IO) { store.save(data) }
        mutableState.update { it.copy(data = data, error = null) }
    }

    private fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            ready.join()
            lock.withLock {
                if (state.value.loadFailed) return@withLock
                mutableState.update { it.copy(busy = true, error = null) }
                try { block() }
                catch (error: CancellationException) { throw error }
                catch (error: Exception) { reportError(error.message?.take(250) ?: "저장 결과를 확인하지 못했습니다. 다시 확인해 주세요.") }
                finally { mutableState.update { it.copy(busy = false) } }
            }
        }
    }

    private fun notifyIssue(title: String, body: String) {
        val app = getApplication<Application>()
        if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val manager = app.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel("inspection", "촬영 확인 안내", NotificationManager.IMPORTANCE_DEFAULT))
            val intent = PendingIntent.getActivity(app, 0, Intent(app, PhoneActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(2001, Notification.Builder(app, "inspection").setSmallIcon(R.drawable.ic_launcher).setContentTitle(title)
                .setContentText(body).setContentIntent(intent).setAutoCancel(true).setVisibility(Notification.VISIBILITY_PRIVATE).build())
        }
    }
}

internal fun inspectionBitmap(file: File, longest: Int = 640): Bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
    val scale = minOf(1.0, longest.toDouble() / maxOf(info.size.width, info.size.height))
    decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1), (info.size.height * scale).roundToInt().coerceAtLeast(1))
    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
}

private fun grayscale(bitmap: Bitmap): IntArray {
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return IntArray(pixels.size) { index -> val color = pixels[index]; ((color shr 16 and 255) * 299 + (color shr 8 and 255) * 587 + (color and 255) * 114) / 1_000 }
}
