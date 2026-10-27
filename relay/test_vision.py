import base64
import copy
import io
import json
import unittest
from unittest.mock import patch
from urllib.error import HTTPError, URLError

import vision

# Only the JPEG envelope is checked locally; the provider decodes image content.
JPEG = base64.b64encode(b"\xff\xd8\xff\xe0\x00\x02\xff\xd9").decode("ascii")
REFERENCE = base64.b64encode(b"\xff\xd8\xff\xe1\x00\x02\xff\xd9").decode("ascii")
KEY = "mock-secret-never-log"
FINDING = {"kind": "SCRATCH", "description": "문 표면에 선 모양 흔적이 보여 확인이 필요합니다.",
           "left": 0.1, "top": 0.2, "right": 0.6, "bottom": 0.7}
REVIEW = {"status": "REVIEW", "summary": "긁힘 의심 흔적을 직접 확인해 주세요.", "findings": [FINDING]}


def envelope(value):
    return {"status": "completed", "output": [{"type": "message", "content": [
        {"type": "output_text", "text": json.dumps(value, ensure_ascii=False)}]}]}


class VisionTests(unittest.TestCase):
    def invoke(self, provider=None, **kwargs):
        raw = json.dumps(provider if provider is not None else envelope(REVIEW)).encode()
        with patch.object(vision, "urlopen", return_value=io.BytesIO(raw)) as opener:
            result = vision.analyze_photo(kwargs.get("photo", JPEG), kwargs.get("reference"),
                                          kwargs.get("stage", "BEFORE"), kwargs.get("view", "FRONT"), KEY)
        return result, opener

    def invalid(self, value):
        with self.assertRaises(vision.VisionError) as error:
            self.invoke(envelope(value))
        self.assertEqual(error.exception.code, "ai_invalid_response")

    def test_request_and_two_images(self):
        with patch("sys.stdout", new_callable=io.StringIO) as stdout, patch("sys.stderr", new_callable=io.StringIO) as stderr:
            result, opener = self.invoke(reference=REFERENCE, stage="AFTER")
        self.assertEqual(stdout.getvalue() + stderr.getvalue(), "")
        opener.assert_called_once()
        request = opener.call_args.args[0]
        self.assertEqual(request.full_url, vision.ENDPOINT)
        self.assertEqual(request.get_method(), "POST")
        self.assertEqual(request.get_header("Authorization"), "Bearer " + KEY)
        self.assertEqual(opener.call_args.kwargs["timeout"], 45)
        body = json.loads(request.data)
        self.assertNotIn(KEY, request.data.decode())
        self.assertIs(body["store"], False)
        self.assertEqual(body["model"], "gpt-4.1-mini")
        self.assertEqual(body["max_output_tokens"], 2000)
        fmt = body["text"]["format"]
        self.assertEqual(fmt["type"], "json_schema")
        self.assertIs(fmt["strict"], True)
        self.assertIs(fmt["schema"]["additionalProperties"], False)
        images = [item["image_url"] for item in body["input"][1]["content"] if item["type"] == "input_image"]
        self.assertEqual(images, ["data:image/jpeg;base64," + JPEG, "data:image/jpeg;base64," + REFERENCE])
        self.assertEqual(result["findings"], REVIEW["findings"])
        self.assertEqual(result["model"], vision.MODEL)
        self.assertGreater(result["analyzedAt"], 0)
        self.assertEqual(set(result), {"status", "summary", "findings", "model", "analyzedAt"})

    def test_one_image_and_empty_results(self):
        for status in ("RETAKE", "NO_CANDIDATE"):
            with self.subTest(status=status):
                result, opener = self.invoke(envelope({"status": status, "summary": "사진을 다시 확인해 주세요. 무손상을 보증하지 않습니다.", "findings": []}))
                self.assertEqual(result["status"], status)
                content = json.loads(opener.call_args.args[0].data)["input"][1]["content"]
                self.assertEqual(sum(item["type"] == "input_image" for item in content), 1)

    def test_invalid_images_never_send(self):
        invalid = [None, "", "!!!", JPEG + "\n", "한글", base64.b64encode(b"not jpeg").decode(),
                   base64.b64encode(b"\xff\xd8\xffmissing-end").decode(),
                   base64.b64encode(b"\xff\xd8\xff" + b"x" * vision.MAX_IMAGE + b"\xff\xd9").decode()]
        with patch.object(vision, "urlopen") as opener:
            for photo in invalid:
                with self.subTest(photo_type=type(photo).__name__), self.assertRaises(vision.VisionError) as error:
                    vision.analyze_photo(photo, None, "BEFORE", "FRONT", KEY)
                self.assertEqual(error.exception.code, "ai_invalid_image")
            with self.assertRaises(vision.VisionError):
                vision.analyze_photo(JPEG, "!!!", "AFTER", "FRONT", KEY)
            opener.assert_not_called()

    def test_bad_context_and_keys_never_send(self):
        cases = [("DURING", "FRONT", None, KEY, "ai_invalid_request"),
                 ("BEFORE", "BOTTOM", None, KEY, "ai_invalid_request"),
                 ("BEFORE", "FRONT", REFERENCE, KEY, "ai_invalid_request"),
                 ("BEFORE", "FRONT", None, "", "ai_not_configured"),
                 ("BEFORE", "FRONT", None, "bad\r\nheader", "ai_auth_failed"),
                 ("BEFORE", "FRONT", None, "bad\x7fkey", "ai_auth_failed")]
        with patch.object(vision, "urlopen") as opener:
            for stage, view, reference, key, code in cases:
                with self.subTest(code=code), self.assertRaises(vision.VisionError) as error:
                    vision.analyze_photo(JPEG, reference, stage, view, key)
                self.assertEqual(error.exception.code, code)
            opener.assert_not_called()

    def test_exact_schema_and_status_relationship(self):
        cases = [None, {}, {**REVIEW, "extra": True}, {**REVIEW, "status": "SAFE"},
                 {**REVIEW, "findings": []}, {**REVIEW, "status": "NO_CANDIDATE"},
                 {**REVIEW, "status": "RETAKE"}, {**REVIEW, "findings": [FINDING] * 13},
                 {**REVIEW, "findings": None}]
        for value in cases:
            with self.subTest(value=value):
                self.invalid(value)

    def test_text_validation(self):
        for text in ("", "   ", "English only", "가" * 1001, "확인\x00"):
            with self.subTest(text_length=len(text)):
                self.invalid({**REVIEW, "summary": text})
        self.invalid({**REVIEW, "findings": [{**FINDING, "description": "가" * 251}]})

    def test_box_and_finding_validation(self):
        for change in ({"left": True}, {"left": -0.1}, {"right": 1.1}, {"left": float("nan")},
                       {"bottom": float("inf")}, {"left": 0.6}, {"top": 0.8}, {"right": "0.6"},
                       {"confidence": 0.9}, {"kind": "CONFIRMED_DAMAGE"}):
            with self.subTest(change=change):
                self.invalid({**REVIEW, "findings": [{**FINDING, **change}]})
        value = copy.deepcopy(REVIEW)
        del value["findings"][0]["left"]
        self.invalid(value)

    def test_refusal_incomplete_and_provider_error(self):
        cases = [({"status": "incomplete", "output": []}, "ai_incomplete"),
                 ({"status": "failed", "error": {"message": KEY}}, "ai_unavailable"),
                 ({"status": "completed", "output": [{"type": "message", "content": [
                     {"type": "refusal", "refusal": KEY}]}]}, "ai_refused")]
        for response, code in cases:
            with self.subTest(code=code), self.assertRaises(vision.VisionError) as error:
                self.invoke(response)
            self.assertEqual(error.exception.code, code)
            self.assertEqual(str(error.exception), code)

    def test_http_and_network_errors_are_sanitized_without_retry(self):
        cases = [(HTTPError(vision.ENDPOINT, status, KEY, {}, io.BytesIO(KEY.encode())), code)
                 for status, code in [(401, "ai_auth_failed"), (403, "ai_auth_failed"),
                                      (429, "ai_rate_limited"), (500, "ai_unavailable"), (302, "ai_unavailable")]]
        cases += [(TimeoutError(KEY), "ai_unavailable"), (URLError(KEY), "ai_unavailable")]
        for failure, code in cases:
            with self.subTest(code=code), patch.object(vision, "urlopen", side_effect=failure) as opener:
                with self.assertRaises(vision.VisionError) as error:
                    vision.analyze_photo(JPEG, None, "BEFORE", "FRONT", KEY)
                self.assertEqual(str(error.exception), code)
                opener.assert_called_once()

    def test_malformed_and_bounded_responses(self):
        for raw in (b"", b"not json", b"\xff", b"[]", b'{"status":"completed","status":"completed"}',
                    b"x" * (vision.MAX_RESPONSE + 1)):
            with self.subTest(size=len(raw)), patch.object(vision, "urlopen", return_value=io.BytesIO(raw)):
                with self.assertRaises(vision.VisionError) as error:
                    vision.analyze_photo(JPEG, None, "BEFORE", "FRONT", KEY)
                self.assertEqual(error.exception.code, "ai_invalid_response")
        for provider in ({"status": "completed", "output": []}, {"status": "completed", "output": [None]},
                         {"status": "completed", "output": [{"type": "message", "content": [None]}]},
                         {"status": "completed", "output": [{"type": "message", "content": [{"type": "output_text", "text": "{}"}]}]}):
            with self.subTest(provider=provider), self.assertRaises(vision.VisionError) as error:
                self.invoke(provider)
            self.assertEqual(error.exception.code, "ai_invalid_response")

    def test_redirect_does_not_forward_authorization(self):
        handler = vision._NoRedirect()
        self.assertIsNone(handler.redirect_request(None, None, 302, "Found", {}, "https://example.invalid"))


if __name__ == "__main__":
    unittest.main()
