package ai.sharemate.app

import java.io.IOException
import java.security.cert.CertificateException
import java.util.concurrent.CancellationException

/*
 * The pairing link (2.15, docs/design/DESIGN_2_15.md §6.1): the three things a phone used to type (the car's addresses, the
 * six-digit code and the 64-hex key) in one line that the car draws as a QR code (QrCode.kt) and the phone reads back
 * (QrScan.kt). Shared by both APKs and pure, so every rule below is a JUnit test (PairingLinkTest).
 *
 *   sharemate://pair?v=1&e=<host:port>[&e=<host:port>…]&c=<6 digits>&k=<64 hex>
 *
 * The parser is strict on purpose: a QR is read from a camera picture, so anything that is not exactly this link (another
 * app's code, a link with a parameter we do not know, a key one digit short) is refused instead of half-used. Nothing is
 * percent-decoded: no legal value needs it, so a '%' anywhere simply fails its value's pattern.
 */

/** Everything before the parameters. Lower case only: the car writes it so, and the parser takes nothing else. */
internal const val PAIRING_LINK_PREFIX = "sharemate://pair?"

/** The longest link the parser accepts (§6.1); four long host names could pass it, and then the car shows no QR. */
internal const val PAIRING_LINK_MAX_LENGTH = 512

/** At most this many addresses ride in one link, in the car's order. */
internal const val PAIRING_LINK_MAX_ENDPOINTS = 4

/** The only version of the link so far; a later car that changes the format bumps it and older phones refuse it cleanly. */
internal const val PAIRING_LINK_VERSION = "1"

/**
 * A parsed link: [endpoints] as "host:port" in the car's order (one to four, host lower-cased, no repeats), the six-digit
 * [code] and the lower-cased 64-hex [fingerprint] of the car's certificate.
 */
internal data class PairingLink(val endpoints: List<String>, val code: String, val fingerprint: String) {
    /** The addresses as [PhoneSyncClient.pair] takes them: one comma-separated string, tried in this order. */
    val endpointText: String get() = endpoints.joinToString(",")
}

private val CODE = Regex("[0-9]{6}")
private val KEY = Regex("[0-9a-fA-F]{64}")
private val HOST_LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
private val PORT = Regex("[1-9][0-9]{0,4}")
private val OCTET = Regex("0|[1-9][0-9]{0,2}")

/**
 * One address as the link carries it (pure): "host:port", lower-cased, or null. The host is letters, digits, dots and
 * hyphens in DNS labels, or an IPv4 literal; a host of digits and dots only must be a real IPv4 literal (no "999.1.1.1", no
 * leading zeros). The port is 1–65535 without leading zeros. IPv6 is not taken: the car publishes IPv4 addresses only.
 */
internal fun pairingEndpoint(text: String): String? {
    val colon = text.lastIndexOf(':')
    if (colon <= 0 || text.indexOf(':') != colon) return null
    val host = text.substring(0, colon).lowercase()
    val port = text.substring(colon + 1)
    if (!PORT.matches(port) || port.toInt() !in 1..65535) return null
    if (host.length > 253) return null
    val labels = host.split('.')
    val numeric = host.all { it.isDigit() || it == '.' }
    val valid = if (numeric) labels.size == 4 && labels.all { OCTET.matches(it) && it.toInt() <= 255 }
        else labels.all { HOST_LABEL.matches(it) }
    return if (valid) "$host:$port" else null
}

/**
 * The link for [endpoints] (each "host:port", in the car's order, the first four kept), [code] and [fingerprint] (pure), or
 * null when those could not make a link the phone accepts: no address yet, no live code, a key that is not 64 hex digits, or
 * a link past [PAIRING_LINK_MAX_LENGTH]. The car then shows why instead of a QR, so it never draws a code the phone refuses.
 */
internal fun pairingLinkText(endpoints: List<String>, code: String, fingerprint: String): String? {
    val kept = endpoints.mapNotNull(::pairingEndpoint).distinct().take(PAIRING_LINK_MAX_ENDPOINTS)
    if (kept.isEmpty() || !CODE.matches(code) || !KEY.matches(fingerprint)) return null
    val text = buildString {
        append(PAIRING_LINK_PREFIX).append("v=").append(PAIRING_LINK_VERSION)
        kept.forEach { append("&e=").append(it) }
        append("&c=").append(code).append("&k=").append(fingerprint.lowercase())
    }
    return text.takeIf { parsePairingLink(it) != null }
}

/**
 * The car's pairing code while it is live (pure; 2.15 fix, docs/design/DESIGN_2_15.md §9.6): [SyncInfo.pairingCode] only
 * while [now] is before [SyncInfo.pairingExpiresAt]. The server drops an expired code only when it publishes, and on a first
 * pairing nothing else makes it publish, so every reader of the code on screen (the QR, its headline, the countdown, the
 * pill, 홈's pairing card) asks this instead of the field: an expired code is no code.
 */
internal fun livePairingCode(info: SyncInfo, now: Long): String? = info.pairingCode?.takeIf { now < info.pairingExpiresAt }

/**
 * The link the car shows for its sync server at [now] (pure): its addresses on its port, the live code ([livePairingCode])
 * and the key, or null while there is no live code (expired, used, or closed after too many wrong tries), no address or no
 * key yet. 2.15 fix (§9.6): a code past its time gives no link, so the car never draws a QR the phone would scan in vain.
 */
internal fun syncPairingLinkText(info: SyncInfo, now: Long): String? {
    val code = livePairingCode(info, now) ?: return null
    return pairingLinkText(info.addresses.map { "$it:${info.port}" }, code, info.fingerprint)
}

// ───────────────────────── how far a scanned link is trusted (§9.6) ─────────────────────────

/** One strict IPv4 octet in ASCII digits: 0–255, no leading zero, no sign, no hex. */
private val LOCAL_OCTET = Regex("0|[1-9][0-9]{0,2}")

/**
 * Whether [host] is a four-octet IPv4 literal on a network a car's own interface can have (pure): 10/8, 172.16/12,
 * 192.168/16 or the link-local 169.254/16. Loopback (127/8) is not a car, a public address is not one either, and a host
 * name never qualifies whatever it would resolve to ("localhost", "0x7f.0.0.1").
 */
private fun hostIsLocalIpv4(host: String): Boolean {
    val octets = host.split('.')
    if (octets.size != 4 || !octets.all { LOCAL_OCTET.matches(it) }) return false
    val values = octets.map { it.toInt() }
    if (values.any { it > 255 }) return false
    val (a, b) = values
    return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

/**
 * Whether every address of [link] is a local IPv4 literal (pure; 2.15 fix, docs/design/DESIGN_2_15.md §9.6): true only when
 * each endpoint's host is on 10/8, 172.16/12, 192.168/16 or 169.254/16. Every link a real car writes passes, since the car
 * publishes its own interface addresses ([SyncServer]). A link that names a public address or any host name (a picture sent
 * from elsewhere, a sticker beside the screen) is trusted no further than typing was: the phone fills the fields and waits.
 */
internal fun pairingLinkIsLocal(link: PairingLink): Boolean =
    link.endpoints.isNotEmpty() && link.endpoints.all { hostIsLocalIpv4(it.substringBeforeLast(':')) }

/**
 * The address a waiting link shows the person (pure; §9.6): the first one that is not local, since that is where the code
 * could go that typing would never have sent it; an all-local link shows its first address. A link that led with a local
 * decoy and hid a foreign host behind it would otherwise wait under an address that looks like the car's own.
 */
internal fun pairingLinkShownEndpoint(link: PairingLink): String =
    link.endpoints.firstOrNull { !hostIsLocalIpv4(it.substringBeforeLast(':')) } ?: link.endpoints.first()

/**
 * Whether a scanned link pairs at once (pure; §9.6): only a picture the phone's camera just took ([fromCamera]) of a link
 * whose addresses are all local ([local], [pairingLinkIsLocal]): the person stands at the car that shows it. A picture from
 * the photo picker, or a link that names another host, fills the three fields and waits for 차량과 연결, as typing does, with
 * the first address shown so the person sees where the code would go.
 */
internal fun scannedLinkPairsAtOnce(fromCamera: Boolean, local: Boolean): Boolean = fromCamera && local

/**
 * Reads a pairing link (pure, strict; §6.1): that scheme and host, `v=1` once, one to four `e` addresses ([pairingEndpoint]),
 * `c` once with exactly six digits, `k` once with exactly 64 hex digits (lower-cased), [PAIRING_LINK_MAX_LENGTH] characters at
 * most, and no other parameter, empty segment or fragment. Anything else is null.
 */
internal fun parsePairingLink(text: String): PairingLink? {
    if (text.length > PAIRING_LINK_MAX_LENGTH || !text.startsWith(PAIRING_LINK_PREFIX)) return null
    val query = text.substring(PAIRING_LINK_PREFIX.length)
    if (query.isEmpty()) return null
    var version: String? = null
    var code: String? = null
    var key: String? = null
    val endpoints = mutableListOf<String>()
    for (segment in query.split('&')) {
        val equals = segment.indexOf('=')
        if (equals <= 0) return null
        val name = segment.substring(0, equals)
        val value = segment.substring(equals + 1)
        when (name) {
            "v" -> { if (version != null) return null; version = value }
            "c" -> { if (code != null) return null; code = value }
            "k" -> { if (key != null) return null; key = value }
            "e" -> endpoints += pairingEndpoint(value) ?: return null
            else -> return null
        }
    }
    if (version != PAIRING_LINK_VERSION) return null
    if (endpoints.isEmpty() || endpoints.size > PAIRING_LINK_MAX_ENDPOINTS) return null
    if (code == null || !CODE.matches(code)) return null
    if (key == null || !KEY.matches(key)) return null
    return PairingLink(endpoints.distinct(), code, key.lowercase())
}

// ───────────────────────── trying the addresses (§6.3) ─────────────────────────

/**
 * The addresses [PhoneSyncClient.pair] tries, in order (pure): [text] split at commas, trimmed, blanks and repeats dropped.
 * A typed address is one entry; a scanned link gives up to four.
 */
internal fun pairingEndpointList(text: String): List<String> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** What one failed pairing attempt means for the addresses after it (§6.3). */
internal enum class PairAttemptOutcome {
    /** Nothing answered there (no route, refused, timed out, not TLS): the next address may be the car. */
    NEXT,
    /** The car answered (a wrong or expired code, another key) or the attempt was cancelled: no other address is tried. */
    STOP,
}

/**
 * Whether a pairing attempt that threw [error] lets the next address be tried (pure). Only a place that could not be
 * reached moves on. A certificate that is not the pinned one (a wrong key, or a clock outside the certificate's dates) stops
 * at once, as does every answer from the car: the code is never sent to a second address after one that spoke as the car,
 * because the car counts wrong codes and closes pairing after [SYNC_MAX_PAIR_FAILURES]. A connection that drops or times out
 * after the handshake also moves on (an I/O error cannot say when it happened), but only a place that proves the pinned
 * certificate ever receives the code, so the next address that takes it is the same car.
 */
internal fun pairAttemptOutcome(error: Throwable): PairAttemptOutcome = when {
    error is CancellationException -> PairAttemptOutcome.STOP
    generateSequence(error) { it.cause }.take(8).any { it is CertificateException } -> PairAttemptOutcome.STOP
    error is IOException -> PairAttemptOutcome.NEXT
    else -> PairAttemptOutcome.STOP
}

/**
 * Runs [attempt] on each of [endpoints] in order and returns the first success (pure apart from what [attempt] does). An
 * attempt whose failure is [PairAttemptOutcome.STOP] ends the run with that failure. When every address failed to answer,
 * the failure thrown is a TLS one if any address gave one (the key's message is the better hint then), otherwise the last.
 * Inline, so [attempt] may suspend in a suspending caller.
 */
internal inline fun <E, T> pairInOrder(endpoints: List<E>, attempt: (E) -> T): T {
    require(endpoints.isNotEmpty()) { "차량 주소를 확인해 주세요." }
    var tls: Throwable? = null
    var last: Throwable? = null
    for (endpoint in endpoints) {
        try {
            return attempt(endpoint)
        } catch (e: Throwable) {
            if (pairAttemptOutcome(e) == PairAttemptOutcome.STOP) throw e
            if (e is javax.net.ssl.SSLException && tls == null) tls = e
            last = e
        }
    }
    throw tls ?: last!!
}
