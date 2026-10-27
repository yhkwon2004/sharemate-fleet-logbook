package ai.sharemate.app

import org.json.JSONArray
import org.json.JSONObject

data class AiDamageFinding(val kind: String, val description: String, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class AiDamageReport(val stage: InspectionStage, val view: EvidenceView, val sha256: String, val referenceSha256: String?,
    val status: ComparisonStatus, val summary: String, val findings: List<AiDamageFinding>, val model: String, val analyzedAt: Long)

internal fun validateAiReport(report: AiDamageReport) {
    require(report.view != EvidenceView.WALKAROUND)
    require(report.sha256.matches(Regex("[0-9a-f]{64}")))
    require(report.referenceSha256 == null || report.referenceSha256.matches(Regex("[0-9a-f]{64}")))
    require(report.stage == InspectionStage.AFTER || report.referenceSha256 == null)
    require(report.summary.isNotBlank() && report.summary.length <= 1_000 && report.model.isNotBlank() && report.model.length <= 100 && report.analyzedAt > 0)
    require(report.findings.size <= 12 && (report.status == ComparisonStatus.REVIEW) == report.findings.isNotEmpty())
    report.findings.forEach {
        require(it.kind in setOf("SCRATCH", "DENT", "OTHER") && it.description.isNotBlank() && it.description.length <= 250)
        require(it.left in 0f..1f && it.top in 0f..1f && it.right in 0f..1f && it.bottom in 0f..1f && it.left < it.right && it.top < it.bottom)
    }
}

internal fun aiReportJson(report: AiDamageReport): JSONObject = JSONObject().apply {
    validateAiReport(report)
    put("stage", report.stage.name); put("view", report.view.name); put("sha256", report.sha256)
    put("referenceSha256", report.referenceSha256 ?: JSONObject.NULL); put("status", report.status.name)
    put("summary", report.summary); put("model", report.model); put("analyzedAt", report.analyzedAt)
    put("findings", JSONArray(report.findings.map { JSONObject().put("kind", it.kind).put("description", it.description)
        .put("left", it.left).put("top", it.top).put("right", it.right).put("bottom", it.bottom) }))
}

internal fun parseAiReport(json: JSONObject): AiDamageReport {
    val findings = json.getJSONArray("findings")
    require(findings.length() <= 12)
    return AiDamageReport(InspectionStage.valueOf(json.getString("stage")), EvidenceView.valueOf(json.getString("view")),
        json.getString("sha256"), if (json.isNull("referenceSha256")) null else json.getString("referenceSha256"),
        ComparisonStatus.valueOf(json.getString("status")), json.getString("summary"),
        (0 until findings.length()).map { findings.getJSONObject(it).let { item -> AiDamageFinding(item.getString("kind"), item.getString("description"),
            item.getDouble("left").toFloat(), item.getDouble("top").toFloat(), item.getDouble("right").toFloat(), item.getDouble("bottom").toFloat()) } },
        json.getString("model"), json.getLong("analyzedAt")).also(::validateAiReport)
}
