package ai.sharemate.app

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The car's TLS identity: a P-256 key and a self-signed certificate the phone pins at pairing.
 * Android has no public API for issuing certificates outside the hardware keystore, so the few DER
 * structures a self-signed X.509 v3 certificate needs are written here by hand.
 */
internal class TlsIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    val fingerprint: String = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }

    companion object {
        private const val SUBJECT = "SHAREMATE vehicle"
        private const val VALID_YEARS = 10

        /** Reuses the stored identity so the phone's pinned fingerprint survives restarts; creates one the first time. */
        fun load(keyFile: File, certFile: File): TlsIdentity {
            if (keyFile.exists() && certFile.exists()) {
                runCatching {
                    val key = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
                    cert.checkValidity()
                    return TlsIdentity(key, cert)
                }
            }
            val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val cert = selfSigned(pair, System.currentTimeMillis())
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(pair.private.encoded)
            certFile.writeBytes(cert.encoded)
            return TlsIdentity(pair.private, cert)
        }

        internal fun selfSigned(pair: KeyPair, now: Long): X509Certificate {
            val ecdsaSha256 = sequence(oid(byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02)))
            val name = sequence(set(sequence(oid(byteArrayOf(0x55, 0x04, 0x03)) + tlv(0x0C, SUBJECT.toByteArray(Charsets.UTF_8)))))
            // DER integers must be minimal: clear the sign bit instead of prefixing 0x00, and never start with a zero byte.
            val serial = ByteArray(8).also { SecureRandom().nextBytes(it); it[0] = ((it[0].toInt() and 0x7F) or 0x40).toByte() }
            val tbs = sequence(
                tlv(0xA0, tlv(0x02, byteArrayOf(2))) +                       // version v3
                    tlv(0x02, serial) + ecdsaSha256 + name +
                    sequence(utcTime(now) + utcTime(now + VALID_YEARS * 365L * 24 * 3_600_000)) +
                    name + pair.public.encoded,
            )
            val signature = Signature.getInstance("SHA256withECDSA").apply { initSign(pair.private); update(tbs) }.sign()
            val der = sequence(tbs + ecdsaSha256 + tlv(0x03, byteArrayOf(0) + signature))
            return CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
        }

        private fun utcTime(at: Long) = tlv(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(at)).toByteArray())
        private fun oid(bytes: ByteArray) = tlv(0x06, bytes)
        private fun sequence(content: ByteArray) = tlv(0x30, content)
        private fun set(content: ByteArray) = tlv(0x31, content)

        private fun tlv(tag: Int, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag)
            val length = content.size
            when {
                length < 0x80 -> out.write(length)
                length < 0x100 -> { out.write(0x81); out.write(length) }
                else -> { out.write(0x82); out.write(length shr 8); out.write(length and 0xFF) }
            }
            out.write(content)
            return out.toByteArray()
        }
    }
}
