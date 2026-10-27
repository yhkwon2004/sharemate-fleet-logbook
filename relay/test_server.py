import base64
import copy
import http.client
import json
from pathlib import Path
import ssl
import tempfile
import threading
import unittest
from unittest.mock import patch

import server

SESSION = "00000000-0000-0000-0000-000000000001"
RECORD = "00000000-0000-0000-0000-000000000002"
NOW = 1_800_000_000_000


def telemetry(session=SESSION, at=NOW):
    return {"sessionId": session, "source": "PLEOS", "sentAt": at,
            "returnAt": at + 3_600_000 if session else None, "returnPlace": "반납 허브" if session else "",
            "soc": 80, "odometerKm": 12000.5, "distanceKm": 3.2 if session else None,
            "charging": False, "startedAt": at - 60000 if session else None,
            "rates": {"baseWon": 1000, "perMinuteWon": 100, "perKmWon": 50} if session else None,
            "costWon": 1260 if session else None, "costAt": at if session else None}


def inspection():
    return {"recordId": RECORD, "sessionId": SESSION, "updatedAt": NOW,
            "beforeComplete": False, "afterComplete": False, "candidateCount": 0, "retakeCount": 0,
            "incidentType": "NONE", "note": "", "evidence": [], "comparisons": []}


def captured(stage, view):
    return {"stage": stage, "view": view, "kind": "VIDEO" if view == "WALKAROUND" else "PHOTO",
            "capturedAt": NOW, "sha256": "a" * 64, "bytes": 2048}


def analysis(stage="AFTER"):
    photo = base64.b64encode(b"\xff\xd8\xff\xe0\x00\x02\xff\xd9").decode()
    return {"recordId": RECORD, "sessionId": SESSION, "stage": stage, "view": "FRONT",
            "sha256": ("b" if stage == "AFTER" else "a") * 64,
            "referenceSha256": "a" * 64 if stage == "AFTER" else None,
            "photoBase64": photo, "referenceBase64": photo if stage == "AFTER" else None}


def ai_result():
    return {"status": "REVIEW", "summary": "문 표면의 긁힘 의심 흔적을 확인해 주세요.", "model": "gpt-4.1-mini",
            "analyzedAt": NOW, "findings": [{"kind": "SCRATCH", "description": "선 모양 흔적을 직접 확인해 주세요.",
                                           "left": .1, "top": .2, "right": .6, "bottom": .7}]}


class RelayTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.directory = Path(self.temporary.name)
        self.tokens, self.pin = server.credentials(self.directory, "127.0.0.1")
        self.clock = NOW
        self.relay = server.make_server("127.0.0.1", 0, self.directory, self.tokens, lambda: self.clock)
        self.thread = threading.Thread(target=self.relay.serve_forever, daemon=True)
        self.thread.start()
        self.context = ssl.create_default_context(cafile=self.directory / "relay.crt")

    def tearDown(self):
        self.relay.shutdown()
        self.relay.server_close()
        self.thread.join(timeout=2)
        self.temporary.cleanup()

    def request(self, method, path, role="phone", body=None, raw=None, token=None, declared_length=None):
        connection = http.client.HTTPSConnection("127.0.0.1", self.relay.server_address[1], context=self.context, timeout=3)
        headers = {"Content-Type": "application/json"}
        if declared_length is not None:
            headers["Content-Length"] = str(declared_length)
        if role is not None:
            headers["Authorization"] = "Bearer " + (token or self.tokens[role + "_token"])
        encoded = raw if raw is not None else json.dumps(body).encode() if body is not None else None
        try:
            connection.request(method, path, body=encoded, headers=headers)
            response = connection.getresponse()
            value = json.loads(response.read())
            self.assertEqual("no-store", response.getheader("Cache-Control"))
            return response.status, value
        finally:
            connection.close()

    def post_telemetry(self, value=None):
        return self.request("POST", "/v1/telemetry", "vehicle", value or telemetry())

    def prepare_analysis(self):
        self.relay.api_key = "test-only"
        self.assertEqual(200, self.post_telemetry()[0])
        record = inspection()
        record["evidence"] = [captured("BEFORE", "FRONT"), {**captured("AFTER", "FRONT"), "sha256": "b" * 64}]
        self.assertEqual(200, self.request("POST", "/v1/inspection", body=record)[0])
        return record

    def test_authentication_and_device_roles(self):
        self.assertEqual(401, self.request("GET", "/v1/state", None)[0])
        self.assertEqual(401, self.request("GET", "/v1/state", token="0" * 64)[0])
        self.assertEqual(403, self.request("POST", "/v1/telemetry", "phone", telemetry())[0])
        self.assertEqual(403, self.request("POST", "/v1/inspection", "vehicle", inspection())[0])
        self.assertEqual(200, self.request("GET", "/v1/state", "vehicle")[0])
        self.assertEqual(200, self.request("GET", "/v1/state", "phone")[0])

    def test_matching_session_metadata_sync_and_original_timestamp(self):
        self.assertEqual(409, self.request("POST", "/v1/inspection", body=inspection())[0])
        self.assertEqual(200, self.post_telemetry()[0])
        report = inspection()
        report["beforeComplete"] = report["afterComplete"] = True
        report["evidence"] = [captured(stage, view) for stage in ("BEFORE", "AFTER") for view in ("FRONT", "RIGHT", "REAR", "LEFT", "WALKAROUND")]
        report["comparisons"] = [{"view": "FRONT", "status": "REVIEW", "message": "직접 확인해 주세요", "boxes": [{"left": .1, "top": .2, "right": .3, "bottom": .4, "score": .5}]}]
        report["candidateCount"] = 1
        self.assertEqual(200, self.request("POST", "/v1/inspection", body=report)[0])
        self.clock += 30_000
        status, result = self.request("GET", "/v1/state")
        self.assertEqual(200, status)
        self.assertEqual(report, result["inspection"])
        self.assertEqual(NOW, result["telemetry"]["sentAt"])
        self.assertEqual(NOW, result["telemetryReceivedAt"])
        self.assertEqual(self.clock, result["receivedAt"])

    def test_session_change_end_and_stale_updates_cannot_restore_old_inspection(self):
        self.post_telemetry()
        self.assertEqual(200, self.request("POST", "/v1/inspection", body=inspection())[0])
        self.assertEqual(200, self.post_telemetry(telemetry(session=None, at=NOW + 1))[0])
        result = self.request("GET", "/v1/state")[1]
        self.assertIsNone(result["inspection"])
        self.assertIsNone(result["telemetry"])
        self.assertTrue(result["closed"])
        self.assertEqual(409, self.request("POST", "/v1/inspection", body=inspection())[0])
        self.assertEqual(409, self.post_telemetry(telemetry())[0])
        self.assertEqual(409, self.post_telemetry(telemetry(RECORD, NOW + 2))[0])
        self.assertEqual(409, self.request("POST", "/v1/inspection", body=inspection())[0])
        self.assertTrue(server.RelayStore(self.directory).read()["closed"])

    def test_live_pairing_cannot_silently_switch_to_another_renter(self):
        self.post_telemetry()
        self.assertEqual(409, self.post_telemetry(telemetry(RECORD, NOW + 1))[0])
        self.assertEqual(SESSION, self.request("GET", "/v1/state")[1]["telemetry"]["sessionId"])

    def test_validation_rejects_nonfinite_duplicate_unknown_and_oversized_bodies(self):
        invalid = telemetry()
        invalid["odometerKm"] = float("nan")
        self.assertEqual(400, self.post_telemetry(invalid)[0])
        invalid = telemetry()
        invalid["soc"] = True
        self.assertEqual(400, self.post_telemetry(invalid)[0])
        invalid = telemetry()
        invalid["photo"] = "should never be stored"
        self.assertEqual(400, self.post_telemetry(invalid)[0])
        raw = json.dumps(telemetry()).replace('"soc": 80', '"soc": 80, "soc": 1').encode()
        self.assertEqual(400, self.request("POST", "/v1/telemetry", "vehicle", raw=raw)[0])
        self.assertEqual(413, self.request("POST", "/v1/telemetry", "vehicle", declared_length=server.MAX_BODY + 1)[0])
        self.assertIsNone(self.request("GET", "/v1/state")[1]["telemetry"])

    def test_inspection_cannot_claim_completion_without_evidence_or_inconsistent_candidates(self):
        self.post_telemetry()
        bad = inspection()
        bad["beforeComplete"] = True
        self.assertEqual(400, self.request("POST", "/v1/inspection", body=bad)[0])
        bad = inspection()
        bad["candidateCount"] = 1
        self.assertEqual(400, self.request("POST", "/v1/inspection", body=bad)[0])
        bad = inspection()
        bad["evidence"] = [captured("BEFORE", "FRONT"), captured("BEFORE", "FRONT")]
        self.assertEqual(400, self.request("POST", "/v1/inspection", body=bad)[0])
        bad = inspection()
        bad["evidence"] = [captured(stage, "FRONT") for stage in ("BEFORE", "AFTER")]
        bad["comparisons"] = [{"view": "FRONT", "status": "REVIEW", "message": "확인", "boxes": [{"left": .8, "top": .2, "right": .3, "bottom": .4, "score": .5}]}]
        bad["candidateCount"] = 1
        self.assertEqual(400, self.request("POST", "/v1/inspection", body=bad)[0])

    def test_restart_preserves_state_and_credentials(self):
        self.post_telemetry()
        self.request("POST", "/v1/inspection", body=inspection())
        restored = server.RelayStore(self.directory, lambda: NOW)
        self.assertEqual(inspection(), restored.read()["inspection"])
        tokens, pin = server.credentials(self.directory)
        self.assertEqual(self.tokens, tokens)
        self.assertEqual(self.pin, pin)
        rotated, new_pin = server.credentials(self.directory, rotate=True)
        self.assertNotEqual(tokens, rotated)
        self.assertNotEqual(pin, new_pin)

    def test_ttl_purges_rental_metadata_without_refreshing_old_receipts(self):
        self.post_telemetry()
        self.request("POST", "/v1/inspection", body=inspection())
        self.clock += server.SESSION_TTL_MS
        result = self.request("GET", "/v1/state")[1]
        self.assertIsNone(result["telemetry"])
        self.assertIsNone(result["inspection"])
        self.assertIsNone(result["telemetryReceivedAt"])
        self.assertIsNone(json.loads((self.directory / "relay-state.json").read_bytes())["inspection"])

    def test_older_phone_update_cannot_overwrite_newer_record(self):
        self.post_telemetry()
        newer = inspection()
        newer["updatedAt"] += 1000
        newer["note"] = "새 점검 기록"
        self.assertEqual(200, self.request("POST", "/v1/inspection", body=newer)[0])
        self.assertEqual(409, self.request("POST", "/v1/inspection", body=inspection())[0])
        self.assertEqual(newer, self.request("GET", "/v1/state")[1]["inspection"])

    def test_analysis_requires_phone_and_configured_key(self):
        self.prepare_analysis()
        with patch.object(server, "analyze_photo") as provider:
            self.assertEqual((401, {"error": "unauthorized"}), self.request("POST", "/v1/analyze", None, analysis()))
            self.assertEqual((403, {"error": "forbidden"}), self.request("POST", "/v1/analyze", "vehicle", analysis()))
            self.relay.api_key = ""
            self.assertEqual((503, {"error": "ai_not_configured"}), self.request("POST", "/v1/analyze", body=analysis()))
            provider.assert_not_called()
        self.assertFalse(self.relay.ai_lock.locked())

    def test_analysis_rejects_malformed_context_before_provider(self):
        self.prepare_analysis()
        invalid = [{**analysis(), "unknown": True}, {**analysis(), "stage": "DURING"},
                   {**analysis(), "view": "WALKAROUND"}, {**analysis(), "sha256": "not-a-digest"},
                   {**analysis(), "referenceSha256": "bad"}, {**analysis(), "referenceBase64": None},
                   {**analysis(), "referenceSha256": None}, {**analysis(), "stage": "BEFORE"}]
        with patch.object(server, "analyze_photo") as provider:
            for body in invalid:
                with self.subTest(body_keys=list(body)):
                    self.assertEqual((400, {"error": "invalid_request"}), self.request("POST", "/v1/analyze", body=body))
            self.assertEqual(400, self.request("POST", "/v1/analyze", raw=b"not json")[0])
            self.assertEqual(413, self.request("POST", "/v1/analyze", declared_length=server.MAX_ANALYSIS_BODY + 1)[0])
            provider.assert_not_called()
        self.assertFalse(self.relay.ai_lock.locked())

    def test_analysis_rejects_wrong_session_record_photo_or_reference(self):
        self.prepare_analysis()
        cases = [({"sessionId": RECORD}, "session_mismatch"), ({"recordId": SESSION}, "evidence_mismatch"),
                 ({"sha256": "c" * 64}, "evidence_mismatch"), ({"referenceSha256": "c" * 64}, "evidence_mismatch"),
                 ({"view": "REAR"}, "evidence_mismatch"),
                 ({"stage": "BEFORE", "referenceSha256": None, "referenceBase64": None}, "evidence_mismatch")]
        with patch.object(server, "analyze_photo") as provider:
            for change, error in cases:
                with self.subTest(change=change):
                    self.assertEqual((409, {"error": error}), self.request("POST", "/v1/analyze", body={**analysis(), **change}))
            provider.assert_not_called()
        self.assertFalse(self.relay.store.ai_cache)

    def test_analysis_success_cache_and_metadata_sync_do_not_persist_photos(self):
        record = self.prepare_analysis()
        body = analysis()
        with patch.object(server, "analyze_photo", return_value=ai_result()) as provider:
            first = self.request("POST", "/v1/analyze", body=body)
            self.assertEqual(200, first[0])
            self.assertEqual(first, self.request("POST", "/v1/analyze", body=body))
            provider.assert_called_once_with(body["photoBase64"], body["referenceBase64"], "AFTER", "FRONT", "test-only")
        report = first[1]
        for key in ("stage", "view", "sha256", "referenceSha256"):
            self.assertEqual(body[key], report[key])
        self.assertNotIn("photoBase64", report)
        record.update(updatedAt=NOW + 1, aiReports=[report])
        self.assertEqual(200, self.request("POST", "/v1/inspection", body=record)[0])
        self.assertEqual([report], self.request("GET", "/v1/state", "vehicle")[1]["inspection"]["aiReports"])
        persisted = (self.directory / "relay-state.json").read_text(encoding="utf-8")
        self.assertNotIn(body["photoBase64"], persisted)
        self.assertNotIn("test-only", persisted)
        self.assertEqual([report], server.RelayStore(self.directory, lambda: NOW).read()["inspection"]["aiReports"])
        self.assertFalse(self.relay.ai_lock.locked())

    def test_session_end_during_analysis_discards_result_and_clears_existing_cache(self):
        self.prepare_analysis()
        with patch.object(server, "analyze_photo", return_value=ai_result()):
            self.assertEqual(200, self.request("POST", "/v1/analyze", body=analysis("BEFORE"))[0])
        self.assertEqual(1, len(self.relay.store.ai_cache))

        def end_during_provider(*_):
            self.assertEqual(200, self.post_telemetry(telemetry(None, NOW + 1))[0])
            return ai_result()

        with patch.object(server, "analyze_photo", side_effect=end_during_provider) as provider:
            self.assertEqual((409, {"error": "pairing_closed"}), self.request("POST", "/v1/analyze", body=analysis()))
            provider.assert_called_once()
        self.assertFalse(self.relay.store.ai_cache)
        state = self.request("GET", "/v1/state")[1]
        self.assertTrue(state["closed"])
        self.assertIsNone(state["inspection"])
        self.assertFalse(self.relay.ai_lock.locked())

    def test_analysis_busy_rejects_without_provider_and_recovers(self):
        self.prepare_analysis()
        self.relay.ai_lock.acquire()
        try:
            with patch.object(server, "analyze_photo") as provider:
                self.assertEqual((429, {"error": "ai_busy"}), self.request("POST", "/v1/analyze", body=analysis()))
                provider.assert_not_called()
        finally:
            self.relay.ai_lock.release()
        with patch.object(server, "analyze_photo", return_value=ai_result()):
            self.assertEqual(200, self.request("POST", "/v1/analyze", body=analysis())[0])

    def test_analysis_safe_provider_error_mapping_and_unlock(self):
        self.prepare_analysis()
        for code, expected in (("ai_invalid_image", 400), ("ai_invalid_request", 400), ("ai_rate_limited", 429),
                               ("ai_auth_failed", 502), ("ai_unavailable", 502), ("ai_refused", 502),
                               ("ai_invalid_response", 502), ("ai_incomplete", 502)):
            with self.subTest(code=code), patch.object(server, "analyze_photo", side_effect=server.VisionError(code)) as provider:
                self.assertEqual((expected, {"error": code}), self.request("POST", "/v1/analyze", body=analysis()))
                provider.assert_called_once()
                self.assertFalse(self.relay.ai_lock.locked())
                self.assertFalse(self.relay.store.ai_cache)

    def test_ai_report_upload_requires_valid_metadata_and_matching_originals(self):
        record = self.prepare_analysis()
        report = {**ai_result(), **{key: analysis()[key] for key in ("stage", "view", "sha256", "referenceSha256")}}
        changes = [{"sha256": "c" * 64}, {"referenceSha256": "c" * 64}, {"stage": "BEFORE"}, {"view": "REAR"},
                   {"status": "NO_CANDIDATE"}, {"findings": []}, {"summary": " "}, {"model": ""}, {"analyzedAt": 0},
                   {"unexpected": "value"}, {"findings": [{**report["findings"][0], "left": .9}]},
                   {"findings": [{**report["findings"][0], "right": float("nan")}]},
                   {"findings": [{**report["findings"][0], "left": True}]}]
        with patch.object(server, "analyze_photo") as provider:
            for change in changes:
                with self.subTest(change_keys=list(change)):
                    bad = {**record, "updatedAt": NOW + 1, "aiReports": [{**report, **change}]}
                    self.assertEqual(400, self.request("POST", "/v1/inspection", body=bad)[0])
            self.assertEqual(400, self.request("POST", "/v1/inspection", body={**record, "updatedAt": NOW + 1, "aiReports": [report, report]})[0])
            provider.assert_not_called()
        self.assertEqual(record, self.request("GET", "/v1/state")[1]["inspection"])
        valid = {**record, "updatedAt": NOW + 1, "aiReports": [report]}
        self.assertEqual(200, self.request("POST", "/v1/inspection", body=valid)[0])


if __name__ == "__main__":
    unittest.main()
