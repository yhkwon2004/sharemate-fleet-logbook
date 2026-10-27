"""SHAREMATE development relay: HTTPS device sync and explicit photo review."""
from __future__ import annotations

import argparse
import hmac
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import secrets
import ssl
import tempfile
import threading
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlsplit
from uuid import UUID
from vision import analyze_photo, VisionError

MAX_BODY = 262_144
MAX_ANALYSIS_BODY = 4_200_000
SESSION_TTL_MS = 24 * 60 * 60 * 1_000
MAX_DATE = 253_402_300_799_999


class RequestError(Exception):
    def __init__(self, status: int, code: str):
        self.status, self.code = status, code


def timestamp() -> int:
    return time.time_ns() // 1_000_000


def strict_json(data: bytes):
    def reject_constant(_):
        raise ValueError("Non-finite number")

    def unique_keys(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("Duplicate key")
            result[key] = value
        return result

    return json.loads(data, parse_constant=reject_constant, object_pairs_hook=unique_keys)


def atomic_json(path: Path, value):
    encoded = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(dir=path.parent, prefix=".write-", delete=False) as stream:
            temporary = Path(stream.name)
            stream.write(encoded)
            stream.flush()
            os.fsync(stream.fileno())
        os.chmod(temporary, 0o600)
        os.replace(temporary, path)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def keys(value, required):
    if type(value) is not dict or set(value) != set(required.split()):
        raise ValueError("Invalid object")


def integer(value, minimum=0, maximum=MAX_DATE):
    if type(value) is not int or not minimum <= value <= maximum:
        raise ValueError("Invalid integer")


def number(value, maximum):
    if type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= maximum:
        raise ValueError("Invalid number")


def string(value, maximum):
    if type(value) is not str or len(value) > maximum or "\x00" in value:
        raise ValueError("Invalid string")


def identifier(value):
    if type(value) is not str or str(UUID(value)) != value:
        raise ValueError("Invalid identifier")


def boolean(value):
    if type(value) is not bool:
        raise ValueError("Invalid boolean")


def digest(value):
    if type(value) is not str or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        raise ValueError("Invalid evidence digest")


def ai_report(value):
    keys(value, "stage view sha256 referenceSha256 status summary findings model analyzedAt")
    if value["stage"] not in ("BEFORE", "AFTER") or value["view"] not in ("FRONT", "RIGHT", "REAR", "LEFT"):
        raise ValueError("Invalid AI view")
    digest(value["sha256"])
    if value["referenceSha256"] is not None:
        digest(value["referenceSha256"])
        if value["stage"] != "AFTER":
            raise ValueError("Invalid reference")
    if value["status"] not in ("REVIEW", "RETAKE", "NO_CANDIDATE"):
        raise ValueError("Invalid AI status")
    for key, maximum in (("summary", 1000), ("model", 100)):
        string(value[key], maximum)
        if not value[key].strip():
            raise ValueError("Empty AI text")
    integer(value["analyzedAt"], 1)
    findings = value["findings"]
    if type(findings) is not list or len(findings) > 12 or (value["status"] == "REVIEW") != bool(findings):
        raise ValueError("Invalid AI findings")
    for item in findings:
        keys(item, "kind description left top right bottom")
        if item["kind"] not in ("SCRATCH", "DENT", "OTHER"):
            raise ValueError("Invalid finding kind")
        string(item["description"], 250)
        if not item["description"].strip():
            raise ValueError("Empty finding")
        for key in ("left", "top", "right", "bottom"):
            number(item[key], 1)
        if item["left"] >= item["right"] or item["top"] >= item["bottom"]:
            raise ValueError("Invalid finding bounds")


def evidence_matches(value, record):
    def match(stage, sha):
        return any(item["stage"] == stage and item["view"] == value["view"] and item["sha256"] == sha for item in record["evidence"])
    return match(value["stage"], value["sha256"]) and (value["referenceSha256"] is None or match("BEFORE", value["referenceSha256"]))


def telemetry(value):
    keys(value, "sessionId source sentAt returnAt returnPlace soc odometerKm distanceKm charging startedAt rates costWon costAt")
    if value["sessionId"] is not None:
        identifier(value["sessionId"])
    if value["source"] not in ("PLEOS", "DEMO"):
        raise ValueError("Invalid source")
    integer(value["sentAt"], 1)
    string(value["returnPlace"], 200)
    for field in ("returnAt", "startedAt", "costAt"):
        if value[field] is not None:
            integer(value[field], 1)
    if value["soc"] is not None:
        integer(value["soc"], 0, 100)
    if value["odometerKm"] is not None:
        number(value["odometerKm"], 1_000_000_000)
    if value["distanceKm"] is not None:
        number(value["distanceKm"], 10_000_000)
    if value["charging"] is not None:
        boolean(value["charging"])
    if value["rates"] is not None:
        keys(value["rates"], "baseWon perMinuteWon perKmWon")
        for rate in value["rates"].values():
            integer(rate, 0, 1_000_000)
    if value["costWon"] is not None:
        integer(value["costWon"], 0, 1_000_000_000_000)
    if value["sessionId"] is None:
        if value["returnPlace"] or any(value[field] is not None for field in ("returnAt", "startedAt", "rates", "distanceKm", "costWon", "costAt")):
            raise ValueError("Ended session must omit rental data")


def inspection(value):
    keys(value, "recordId sessionId updatedAt beforeComplete afterComplete candidateCount retakeCount incidentType note evidence comparisons" + (" aiReports" if type(value) is dict and "aiReports" in value else ""))
    identifier(value["recordId"])
    identifier(value["sessionId"])
    integer(value["updatedAt"], 1)
    boolean(value["beforeComplete"])
    boolean(value["afterComplete"])
    integer(value["candidateCount"], 0, 48)
    integer(value["retakeCount"], 0, 4)
    if value["incidentType"] not in ("NONE", "SCRATCH", "ACCIDENT", "OTHER"):
        raise ValueError("Invalid incident type")
    string(value["note"], 1_000)
    if type(value["evidence"]) is not list or len(value["evidence"]) > 10:
        raise ValueError("Invalid evidence")
    slots = set()
    views = {"FRONT", "RIGHT", "REAR", "LEFT", "WALKAROUND"}
    for item in value["evidence"]:
        keys(item, "stage view kind capturedAt sha256 bytes")
        if item["stage"] not in ("BEFORE", "AFTER") or item["view"] not in views:
            raise ValueError("Invalid capture slot")
        if item["kind"] != ("VIDEO" if item["view"] == "WALKAROUND" else "PHOTO"):
            raise ValueError("Invalid evidence kind")
        slot = (item["stage"], item["view"])
        if slot in slots:
            raise ValueError("Duplicate evidence slot")
        slots.add(slot)
        integer(item["capturedAt"], 1)
        integer(item["bytes"], 1, 1_073_741_824)
        digest(item["sha256"])
    if value["beforeComplete"] and not all(("BEFORE", view) in slots for view in views):
        raise ValueError("Incomplete before evidence")
    if value["afterComplete"] and (not value["beforeComplete"] or not all(("AFTER", view) in slots for view in views)):
        raise ValueError("Incomplete after evidence")
    if type(value["comparisons"]) is not list or len(value["comparisons"]) > 4:
        raise ValueError("Invalid comparisons")
    compared = set()
    candidates = retakes = 0
    for item in value["comparisons"]:
        keys(item, "view status message boxes")
        if item["view"] not in views - {"WALKAROUND"} or item["view"] in compared:
            raise ValueError("Invalid comparison view")
        compared.add(item["view"])
        if any((stage, item["view"]) not in slots for stage in ("BEFORE", "AFTER")):
            raise ValueError("Comparison is missing evidence")
        if item["status"] not in ("RETAKE", "REVIEW", "NO_CANDIDATE"):
            raise ValueError("Invalid comparison status")
        string(item["message"], 1_000)
        if type(item["boxes"]) is not list or len(item["boxes"]) > 12:
            raise ValueError("Invalid candidate boxes")
        if (item["status"] == "REVIEW") != bool(item["boxes"]):
            raise ValueError("Candidate status mismatch")
        for box in item["boxes"]:
            keys(box, "left top right bottom score")
            for coordinate in box.values():
                number(coordinate, 1)
            if box["left"] >= box["right"] or box["top"] >= box["bottom"]:
                raise ValueError("Invalid candidate bounds")
        candidates += len(item["boxes"])
        retakes += item["status"] == "RETAKE"
    if candidates != value["candidateCount"] or retakes != value["retakeCount"]:
        raise ValueError("Comparison summary mismatch")
    reports = value.get("aiReports", [])
    if type(reports) is not list or len(reports) > 8:
        raise ValueError("Invalid AI reports")
    reported = set()
    for report in reports:
        ai_report(report)
        slot = (report["stage"], report["view"])
        if slot in reported or not evidence_matches(report, value):
            raise ValueError("AI evidence mismatch")
        reported.add(slot)


def analysis_request(value):
    keys(value, "recordId sessionId stage view sha256 referenceSha256 photoBase64 referenceBase64")
    identifier(value["recordId"])
    identifier(value["sessionId"])
    if value["stage"] not in ("BEFORE", "AFTER") or value["view"] not in ("FRONT", "RIGHT", "REAR", "LEFT"):
        raise ValueError("Invalid analysis view")
    digest(value["sha256"])
    if (value["referenceSha256"] is None) != (value["referenceBase64"] is None):
        raise ValueError("Missing reference")
    if value["referenceSha256"] is not None:
        digest(value["referenceSha256"])
        if value["stage"] != "AFTER":
            raise ValueError("Invalid reference stage")


def api_key_from_environment():
    configured = os.environ.get("OPENAI_API_KEY")
    if configured is not None:
        return configured.strip()
    path = Path(__file__).resolve().parent.parent / ".env.local"
    if not path.is_file():
        return ""
    if path.stat().st_size > 65_536:
        raise ValueError("Oversized local environment file")
    matches = [line.partition("=")[2].strip() for line in path.read_text(encoding="utf-8-sig").splitlines()
               if line.partition("=")[0].strip() == "OPENAI_API_KEY"]
    if len(matches) > 1:
        raise ValueError("Duplicate API key entry")
    if not matches:
        return ""
    value = matches[0]
    return value[1:-1] if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'" else value


class RelayStore:
    def __init__(self, directory: Path, now=timestamp):
        self.path = directory / "relay-state.json"
        self.lock = threading.Lock()
        self.ai_cache = {}
        self.now = now
        self.value = {"telemetry": None, "inspection": None, "telemetryReceivedAt": None, "closed": False}
        if self.path.exists():
            if self.path.stat().st_size > MAX_BODY * 2:
                raise ValueError("Oversized stored state")
            value = strict_json(self.path.read_bytes())
            value.setdefault("closed", False)
            keys(value, "telemetry inspection telemetryReceivedAt closed")
            if type(value["closed"]) is not bool:
                raise ValueError("Invalid closed state")
            if value["telemetry"] is not None:
                telemetry(value["telemetry"])
                integer(value["telemetryReceivedAt"], 1)
            elif value["telemetryReceivedAt"] is not None:
                raise ValueError("Invalid receipt timestamp")
            if value["inspection"] is not None:
                inspection(value["inspection"])
                if value["telemetry"] is None or value["telemetry"]["sessionId"] != value["inspection"]["sessionId"]:
                    raise ValueError("Stored session mismatch")
            self.value = value

    def _save(self, value):
        atomic_json(self.path, value)
        self.value = value
        if value["closed"]:
            self.ai_cache.clear()

    def _expire(self, now):
        received = self.value["telemetryReceivedAt"]
        if received is not None and (now < received or now - received >= SESSION_TTL_MS):
            self._save({"telemetry": None, "inspection": None, "telemetryReceivedAt": None, "closed": True})

    def read(self):
        with self.lock:
            now = self.now()
            self._expire(now)
            return {**self.value, "receivedAt": now}

    def check_analysis(self, value, result=None):
        with self.lock:
            self._expire(self.now())
            current, record = self.value["telemetry"], self.value["inspection"]
            if self.value["closed"]:
                raise RequestError(409, "pairing_closed")
            if current is None or current["sessionId"] != value["sessionId"]:
                raise RequestError(409, "session_mismatch")
            if record is None or record["recordId"] != value["recordId"] or not evidence_matches(value, record):
                raise RequestError(409, "evidence_mismatch")
            cache_key = tuple(value[key] for key in ("sessionId", "recordId", "stage", "view", "sha256", "referenceSha256"))
            # shortcut: cache lasts until relay restart; use durable request IDs before production billing.
            if result is not None:
                if len(self.ai_cache) >= 32:
                    self.ai_cache.pop(next(iter(self.ai_cache)))
                self.ai_cache[cache_key] = result
            return self.ai_cache.get(cache_key)

    def update(self, kind, value):
        with self.lock:
            now = self.now()
            self._expire(now)
            current = self.value["telemetry"]
            if kind == "telemetry":
                if self.value["closed"]:
                    if value["sessionId"] is None:
                        return {"ok": True, "receivedAt": now}
                    raise RequestError(409, "pairing_closed")
                if current is not None and current["sessionId"] is not None and value["sessionId"] not in (None, current["sessionId"]):
                    raise RequestError(409, "session_mismatch")
                if current is not None and (value["sentAt"] < current["sentAt"] or
                    (value["sentAt"] == current["sentAt"] and value != current)):
                    raise RequestError(409, "stale_update")
                record = self.value["inspection"]
                if value["sessionId"] is None or current is None or value["sessionId"] != current["sessionId"]:
                    record = None
                ended = current is not None and current["sessionId"] is not None and value["sessionId"] is None
                self._save({"telemetry": None if ended else value, "inspection": record, "telemetryReceivedAt": None if ended else now, "closed": ended})
            else:
                if current is None or current["sessionId"] != value["sessionId"]:
                    raise RequestError(409, "session_mismatch")
                previous = self.value["inspection"]
                if previous is not None and (value["updatedAt"] < previous["updatedAt"] or
                    (value["updatedAt"] == previous["updatedAt"] and value != previous)):
                    raise RequestError(409, "stale_update")
                self._save({**self.value, "inspection": value})
            return {"ok": True, "receivedAt": now}


class RelayHandler(BaseHTTPRequestHandler):
    server_version = "SHAREMATE"
    sys_version = ""

    def setup(self):
        self.request.settimeout(10)
        if isinstance(self.request, ssl.SSLSocket):
            self.request.do_handshake()
        super().setup()

    def log_message(self, *_):
        pass

    def _reply(self, status, body):
        data = json.dumps(body, ensure_ascii=False, allow_nan=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True
        self.wfile.write(data)

    def _role(self):
        values = self.headers.get_all("Authorization", [])
        if len(values) != 1 or not values[0].startswith("Bearer "):
            raise RequestError(401, "unauthorized")
        provided = values[0][7:]
        if re.fullmatch(r"[0-9a-f]{64}", provided) is None:
            raise RequestError(401, "unauthorized")
        for role in ("vehicle", "phone"):
            if hmac.compare_digest(provided, self.server.credentials[role + "_token"]):
                return role
        raise RequestError(401, "unauthorized")

    def _body(self, maximum=MAX_BODY):
        lengths = self.headers.get_all("Content-Length", [])
        if self.headers.get("Transfer-Encoding") or len(lengths) != 1 or re.fullmatch(r"[0-9]{1,8}", lengths[0]) is None:
            raise RequestError(400, "invalid_request")
        length = int(lengths[0])
        if length > maximum:
            raise RequestError(413, "too_large")
        if length == 0 or self.headers.get_content_type() != "application/json":
            raise RequestError(400, "invalid_request")
        data = self.rfile.read(length)
        if len(data) != length:
            raise RequestError(400, "invalid_request")
        return strict_json(data)

    def _dispatch(self):
        role = self._role()
        if self.command == "GET" and self.path == "/v1/state":
            return self.server.store.read()
        if self.command == "POST" and self.path == "/v1/analyze":
            if role != "phone":
                raise RequestError(403, "forbidden")
            value = self._body(MAX_ANALYSIS_BODY)
            analysis_request(value)
            if not self.server.ai_lock.acquire(blocking=False):
                raise RequestError(429, "ai_busy")
            try:
                result = self.server.store.check_analysis(value)
                if result is None:
                    if not self.server.api_key:
                        raise RequestError(503, "ai_not_configured")
                    result = analyze_photo(value["photoBase64"], value["referenceBase64"], value["stage"], value["view"], self.server.api_key)
                    result.update({key: value[key] for key in ("stage", "view", "sha256", "referenceSha256")})
                    ai_report(result)
                    self.server.store.check_analysis(value, result)
                return result
            finally:
                self.server.ai_lock.release()
        routes = {"/v1/telemetry": ("vehicle", telemetry), "/v1/inspection": ("phone", inspection)}
        if self.command != "POST" or self.path not in routes:
            raise RequestError(404, "not_found")
        required_role, validate = routes[self.path]
        if role != required_role:
            raise RequestError(403, "forbidden")
        value = self._body()
        validate(value)
        return self.server.store.update(self.path.rsplit("/", 1)[1], value)

    def _handle(self):
        try:
            self._reply(200, self._dispatch())
        except RequestError as error:
            self._reply(error.status, {"error": error.code})
        except VisionError as error:
            status = 400 if error.code in ("ai_invalid_request", "ai_invalid_image") else 429 if error.code == "ai_rate_limited" else 502
            self._reply(status, {"error": error.code})
        except (ValueError, TypeError, KeyError, OverflowError, RecursionError, UnicodeError):
            self._reply(400, {"error": "invalid_request"})
        except (TimeoutError, ConnectionError):
            self.close_connection = True
        except OSError:
            self._reply(503, {"error": "storage_unavailable"})

    do_GET = _handle
    do_POST = _handle


def credentials(directory: Path, hostname="localhost", rotate=False):
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    directory.mkdir(parents=True, exist_ok=True, mode=0o700)
    path = directory / "config.private.json"
    certificate_path, key_path = directory / "relay.crt", directory / "relay.key"
    if path.exists() and not rotate:
        value = strict_json(path.read_bytes())
        keys(value, "vehicle_token phone_token")
        if any(type(token) is not str or re.fullmatch(r"[0-9a-f]{64}", token) is None for token in value.values()) or value["vehicle_token"] == value["phone_token"]:
            raise ValueError("Invalid credentials")
        if not certificate_path.is_file() or not key_path.is_file():
            raise ValueError("Missing TLS identity")
    else:
        private_key = ec.generate_private_key(ec.SECP256R1())
        subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "SHAREMATE local relay")])
        names = []
        for host in {"localhost", "127.0.0.1", "10.0.2.2", hostname}:
            try:
                names.append(x509.IPAddress(ipaddress.ip_address(host)))
            except ValueError:
                names.append(x509.DNSName(host))
        now = datetime.now(timezone.utc)
        cert = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(private_key.public_key())
                .serial_number(x509.random_serial_number()).not_valid_before(now - timedelta(minutes=5))
                .not_valid_after(now + timedelta(days=365)).add_extension(x509.SubjectAlternativeName(names), critical=False)
                .sign(private_key, hashes.SHA256()))
        key_path.write_bytes(private_key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        os.chmod(key_path, 0o600)
        certificate_path.write_bytes(cert.public_bytes(serialization.Encoding.PEM))
        value = {"vehicle_token": secrets.token_hex(32), "phone_token": secrets.token_hex(32)}
        atomic_json(path, value)
    cert = x509.load_pem_x509_certificate(certificate_path.read_bytes())
    fingerprint = cert.fingerprint(hashes.SHA256()).hex()
    return value, fingerprint


def make_server(host, port, directory: Path, tokens, now=timestamp, api_key=""):
    server = ThreadingHTTPServer((host, port), RelayHandler)
    server.daemon_threads = True
    server.store = RelayStore(directory, now)
    server.credentials = tokens
    server.api_key = api_key
    server.ai_lock = threading.Lock()
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(directory / "relay.crt", directory / "relay.key")
    server.socket = context.wrap_socket(server.socket, server_side=True, do_handshake_on_connect=False)
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--state-dir", type=Path, default=Path(__file__).parent / ".runtime")
    parser.add_argument("--advertise-url")
    parser.add_argument("--rotate-credentials", action="store_true", help="Replace both device tokens and the TLS identity; existing links stop working")
    args = parser.parse_args()
    advertised = urlsplit(args.advertise_url or f"https://127.0.0.1:{args.port}")
    if advertised.scheme != "https" or not advertised.hostname or advertised.username or advertised.password or advertised.path not in ("", "/") or advertised.query or advertised.fragment or advertised.port is None:
        parser.error("advertise-url must be an HTTPS origin with an explicit port")
    tokens, fingerprint = credentials(args.state_dir, advertised.hostname, args.rotate_credentials)
    server = make_server(args.host, args.port, args.state_dir, tokens, api_key=api_key_from_environment())
    if args.rotate_credentials and server.store.value["closed"]:
        server.store._save({"telemetry": None, "inspection": None, "telemetryReceivedAt": None, "closed": False})
    url = args.advertise_url.rstrip("/") if args.advertise_url else f"https://127.0.0.1:{server.server_address[1]}"
    for role, filename in (("vehicle", "car-link.txt"), ("phone", "phone-link.txt")):
        path = args.state_dir / filename
        path.write_text(f"{url}|{tokens[role + '_token']}|{fingerprint}", encoding="utf-8")
        os.chmod(path, 0o600)
    print(f"SHAREMATE HTTPS relay listening on port {server.server_address[1]}. Pairing links saved in the private state directory.")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
