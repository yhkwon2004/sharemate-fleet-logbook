package ai.sharemate.app

import android.content.Context
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

enum class InspectionStage(val label: String) { BEFORE("탑승 전"), AFTER("이용 후") }
enum class EvidenceView(val label: String) { FRONT("앞면"), RIGHT("오른쪽"), REAR("뒷면"), LEFT("왼쪽"), WALKAROUND("차량 둘레 영상") }
enum class IncidentType(val label: String) { NONE("직접 발견한 문제 없음"), SCRATCH("긁힘·손상 의심"), ACCIDENT("사고 기록"), OTHER("기타 확인 필요") }

data class EvidenceItem(val name: String, val stage: InspectionStage, val view: EvidenceView,
    val capturedAt: Long, val bytes: Long, val sha256: String) {
    val video: Boolean get() = view == EvidenceView.WALKAROUND
}
data class PendingCapture(val recordId: String, val name: String, val stage: InspectionStage, val view: EvidenceView)
data class InspectionRecord(
    val id: String = UUID.randomUUID().toString(), val sessionId: String? = null,
    val createdAt: Long = System.currentTimeMillis(), val updatedAt: Long = createdAt,
    val beforeComplete: Boolean = false, val afterComplete: Boolean = false,
    val incidentType: IncidentType = IncidentType.NONE, val note: String = "",
    val evidence: List<EvidenceItem> = emptyList(), val comparisons: Map<EvidenceView, ComparisonResult> = emptyMap(),
    val aiReports: List<AiDamageReport> = emptyList(),
) {
    fun latest(stage: InspectionStage, view: EvidenceView) = evidence.lastOrNull { it.stage == stage && it.view == view }
    fun capturedCount(stage: InspectionStage) = EvidenceView.entries.count { latest(stage, it) != null }
    fun canComplete(stage: InspectionStage, reviewed: Boolean) = reviewed && capturedCount(stage) == 5 &&
        (if (stage == InspectionStage.BEFORE) !beforeComplete else beforeComplete) && !afterComplete
}
data class InspectionData(val records: List<InspectionRecord> = emptyList(), val selectedId: String? = null,
    val pending: PendingCapture? = null) {
    val selected: InspectionRecord? get() = records.firstOrNull { it.id == selectedId }
}

class InspectionStore(context: Context) {
    private val directory = File(context.filesDir, "evidence").apply { mkdirs() }
    private val index = AtomicFile(File(context.noBackupFilesDir, "inspections.json"))

    fun media(recordId: String, name: String): File {
        require(validUuid(recordId) && name.matches(Regex("[0-9a-f-]{36}\\.(jpg|mp4)"))) { "촬영 파일 정보가 올바르지 않습니다." }
        val folder = File(directory, recordId)
        val file = File(folder, name)
        require(file.canonicalFile.parentFile == folder.canonicalFile && folder.canonicalFile.parentFile == directory.canonicalFile)
        return file
    }

    fun prepare(data: InspectionData, stage: InspectionStage, view: EvidenceView): Pair<InspectionData, File> {
        require(data.pending == null) { "진행 중인 촬영을 먼저 확인해 주세요." }
        val record = requireNotNull(data.selected)
        require(!record.afterComplete && !(stage == InspectionStage.BEFORE && record.beforeComplete)) { "완료한 단계의 원본은 변경하지 않습니다." }
        require(stage == InspectionStage.BEFORE || record.beforeComplete) { "탑승 전 촬영을 먼저 마무리해 주세요." }
        require(record.evidence.size < 80) { "한 이용당 촬영은 80개까지 보관할 수 있습니다." }
        val name = UUID.randomUUID().toString() + if (view == EvidenceView.WALKAROUND) ".mp4" else ".jpg"
        val file = media(record.id, name)
        require(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "촬영 폴더를 만들지 못했습니다." }
        require(file.createNewFile()) { "촬영 파일을 만들지 못했습니다." }
        val next = data.copy(pending = PendingCapture(record.id, name, stage, view))
        try { save(next) } catch (error: Exception) { file.delete(); throw error }
        return next to file
    }

    fun finishCapture(data: InspectionData, success: Boolean): InspectionData {
        val pending = data.pending ?: return data
        val file = media(pending.recordId, pending.name)
        if (!success) {
            require(!file.exists() || file.delete()) { "취소한 촬영 파일을 삭제하지 못했습니다." }
            return data.copy(pending = null).also(::save)
        }
        require(file.length() in 1..150_000_000L) { "촬영 파일이 비어 있거나 150 MB를 넘습니다. 취소 후 다시 촬영해 주세요." }
        if (pending.view == EvidenceView.WALKAROUND) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.path)
                require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) >= 1_000) { "1초 이상의 영상을 촬영해 주세요." }
                require(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes") { "재생 가능한 영상을 확인하지 못했습니다." }
            } finally { retriever.release() }
        } else {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                require(info.size.width >= 128 && info.size.height >= 128) { "사진 해상도가 너무 작습니다." }
                decoder.setTargetSize(64, 64)
            }.recycle()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(64 * 1024); while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) } }
        val item = EvidenceItem(pending.name, pending.stage, pending.view, System.currentTimeMillis(), file.length(), digest.digest().joinToString("") { "%02x".format(it) })
        val next = data.copy(pending = null, records = data.records.map { record ->
            if (record.id == pending.recordId) record.copy(evidence = record.evidence + item, comparisons = record.comparisons - pending.view,
                updatedAt = System.currentTimeMillis()) else record
        })
        save(next)
        return next
    }

    fun assertMediaPresent(record: InspectionRecord, stage: InspectionStage) {
        EvidenceView.entries.forEach { view ->
            val item = requireNotNull(record.latest(stage, view)) { "모든 방향과 영상을 촬영해 주세요." }
            require(media(record.id, item.name).length() == item.bytes && item.bytes > 0) { "촬영 원본이 누락되었습니다. 다시 확인해 주세요." }
        }
    }

    fun deleteRecord(data: InspectionData, recordId: String): InspectionData {
        require(data.pending == null) { "촬영 중에는 기록을 삭제할 수 없습니다." }
        require(validUuid(recordId) && data.records.any { it.id == recordId })
        val folder = File(directory, recordId)
        require(folder.canonicalFile.parentFile == directory.canonicalFile)
        require(!folder.exists() || folder.deleteRecursively()) { "촬영 원본을 모두 삭제하지 못했습니다. 다시 시도해 주세요." }
        val remaining = data.records.filterNot { it.id == recordId }
        return data.copy(records = remaining, selectedId = if (data.selectedId == recordId) remaining.firstOrNull()?.id else data.selectedId).also(::save)
    }

    fun load(): InspectionData {
        if (!index.baseFile.exists() && !File(index.baseFile.path + ".bak").exists()) return InspectionData()
        val bytes = index.openRead().use { input -> require(input.channel.size() <= 2_000_000); input.readBytes() }
        val json = JSONObject(bytes.toString(Charsets.UTF_8))
        require(json.getInt("version") == 1)
        val rows = json.getJSONArray("records")
        require(rows.length() <= 50)
        val records = (0 until rows.length()).map { pos ->
            val row = rows.getJSONObject(pos)
            val items = row.getJSONArray("evidence")
            require(items.length() <= 80)
            val results = row.getJSONArray("comparisons")
            require(results.length() <= 4)
            InspectionRecord(id = row.getString("id"), sessionId = row.optString("sessionId").takeIf { it.isNotBlank() && it != "null" },
                createdAt = row.getLong("createdAt"), updatedAt = row.getLong("updatedAt"), beforeComplete = row.getBoolean("beforeComplete"),
                afterComplete = row.getBoolean("afterComplete"), incidentType = IncidentType.valueOf(row.getString("incidentType")), note = row.getString("note"),
                evidence = (0 until items.length()).map { itemIndex -> items.getJSONObject(itemIndex).let {
                    EvidenceItem(it.getString("name"), InspectionStage.valueOf(it.getString("stage")), EvidenceView.valueOf(it.getString("view")),
                        it.getLong("capturedAt"), it.getLong("bytes"), it.getString("sha256"))
                } }, comparisons = (0 until results.length()).associate { resultIndex -> results.getJSONObject(resultIndex).let { result ->
                    val boxes = result.getJSONArray("boxes")
                    require(boxes.length() <= 100)
                    EvidenceView.valueOf(result.getString("view")) to ComparisonResult(ComparisonStatus.valueOf(result.getString("status")), result.getString("message"),
                        (0 until boxes.length()).map { boxIndex -> boxes.getJSONObject(boxIndex).let { box ->
                            ComparisonBox(box.getDouble("left").toFloat(), box.getDouble("top").toFloat(), box.getDouble("right").toFloat(), box.getDouble("bottom").toFloat(), box.getDouble("score").toFloat())
                        } })
                } }, aiReports = row.optJSONArray("aiReports")?.let { list ->
                    require(list.length() <= 8)
                    (0 until list.length()).map { parseAiReport(list.getJSONObject(it)) }
                } ?: emptyList())
        }
        val pending = json.optJSONObject("pending")?.let { PendingCapture(it.getString("recordId"), it.getString("name"), InspectionStage.valueOf(it.getString("stage")), EvidenceView.valueOf(it.getString("view"))) }
        return InspectionData(records, json.optString("selectedId").takeIf { it.isNotBlank() && it != "null" }, pending).also(::validate)
    }

    fun save(data: InspectionData) {
        validate(data)
        val bytes = JSONObject().put("version", 1).put("selectedId", data.selectedId ?: JSONObject.NULL)
            .put("records", JSONArray(data.records.map { recordJson(it, true) }))
            .put("pending", data.pending?.let { JSONObject().put("recordId", it.recordId).put("name", it.name).put("stage", it.stage.name).put("view", it.view.name) } ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 2_000_000) { "보관 기록이 너무 많습니다. 불필요한 기록을 삭제해 주세요." }
        val output = index.startWrite()
        try { output.write(bytes); index.finishWrite(output) } catch (error: Exception) { index.failWrite(output); throw error }
    }

    private fun validate(data: InspectionData) {
        require(data.records.size <= 50 && data.records.map { it.id }.distinct().size == data.records.size)
        require(data.selectedId == null || data.records.any { it.id == data.selectedId })
        data.records.forEach { record ->
            require(validUuid(record.id) && (record.sessionId == null || validUuid(record.sessionId)))
            require(record.note.length <= 1_000 && record.evidence.size <= 80 && record.createdAt > 0 && record.updatedAt > 0)
            require(!record.afterComplete || record.beforeComplete)
            record.evidence.forEach { media(record.id, it.name); require(it.bytes in 1..150_000_000L && it.capturedAt > 0 && it.sha256.matches(Regex("[0-9a-f]{64}"))) }
            require(record.evidence.map { it.name }.distinct().size == record.evidence.size)
            require(record.aiReports.size <= 8 && record.aiReports.map { it.stage to it.view }.distinct().size == record.aiReports.size)
            record.aiReports.forEach { report ->
                validateAiReport(report)
                require(record.evidence.any { it.sha256 == report.sha256 && it.stage == report.stage && it.view == report.view })
                require(report.referenceSha256 == null || record.evidence.any { it.stage == InspectionStage.BEFORE && it.view == report.view && it.sha256 == report.referenceSha256 })
            }
            record.comparisons.forEach { (view, result) ->
                require(view != EvidenceView.WALKAROUND && result.message.length <= 1_000 && result.boxes.size <= 100)
                result.boxes.forEach { box -> require(box.left in 0f..1f && box.top in 0f..1f && box.right in box.left..1f && box.bottom in box.top..1f && box.score.isFinite()) }
            }
        }
        data.pending?.let { pending -> require(data.records.any { it.id == pending.recordId }); media(pending.recordId, pending.name) }
    }
}

internal fun validUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal fun recordJson(record: InspectionRecord, local: Boolean = false): JSONObject = JSONObject().apply {
    put(if (local) "id" else "recordId", record.id)
    put("sessionId", record.sessionId ?: JSONObject.NULL)
    if (local) put("createdAt", record.createdAt)
    put("updatedAt", record.updatedAt)
    put("beforeComplete", record.beforeComplete); put("afterComplete", record.afterComplete)
    put("incidentType", record.incidentType.name); put("note", record.note)
    put("candidateCount", record.comparisons.values.sumOf { it.boxes.size })
    put("retakeCount", record.comparisons.values.count { it.status == ComparisonStatus.RETAKE })
    put("aiReports", JSONArray(record.aiReports.filter { report -> local || (record.latest(report.stage, report.view)?.sha256 == report.sha256 &&
        (report.referenceSha256 == null || record.latest(InspectionStage.BEFORE, report.view)?.sha256 == report.referenceSha256)) }.map(::aiReportJson)))
    val exportedEvidence = if (local) record.evidence else InspectionStage.entries.flatMap { stage -> EvidenceView.entries.mapNotNull { view -> record.latest(stage, view) } }
    put("evidence", JSONArray(exportedEvidence.map { item -> JSONObject().apply {
        if (local) put("name", item.name)
        put("stage", item.stage.name); put("view", item.view.name); put("kind", if (item.video) "VIDEO" else "PHOTO")
        put("capturedAt", item.capturedAt); put("bytes", item.bytes); put("sha256", item.sha256)
    } }))
    put("comparisons", JSONArray(record.comparisons.map { (view, result) -> JSONObject().apply {
        put("view", view.name); put("status", result.status.name); put("message", result.message)
        put("boxes", JSONArray(result.boxes.map { box -> JSONObject().put("left", box.left).put("top", box.top).put("right", box.right).put("bottom", box.bottom).put("score", box.score) }))
    } }))
}
