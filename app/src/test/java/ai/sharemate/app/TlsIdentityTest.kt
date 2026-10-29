package ai.sharemate.app

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class TlsIdentityTest {
    @Test fun selfSignedCertificateParsesAndVerifiesWithItsOwnKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val now = 1_800_000_000_000L
        val cert = TlsIdentity.selfSigned(pair, now)
        cert.verify(pair.public)
        assertEquals("CN=SHAREMATE vehicle", cert.subjectX500Principal.name)
        assertEquals(cert.subjectX500Principal, cert.issuerX500Principal)
        assertEquals(3, cert.version)
        assertEquals(now / 1000, cert.notBefore.time / 1000)
        assertTrue(cert.notAfter.time > now + 9L * 365 * 24 * 3_600_000)
        assertEquals("SHA256withECDSA", cert.sigAlgName)
        assertTrue(cert.serialNumber.signum() > 0)
    }

    @Test fun storedIdentityIsReusedSoThePinnedFingerprintSurvivesRestarts() {
        val dir = File(System.getProperty("java.io.tmpdir"), "sharemate-tls-${System.nanoTime()}").apply { mkdirs() }
        try {
            val first = TlsIdentity.load(File(dir, "k.der"), File(dir, "c.der"))
            val second = TlsIdentity.load(File(dir, "k.der"), File(dir, "c.der"))
            assertEquals(first.fingerprint, second.fingerprint)
            assertEquals(64, first.fingerprint.length)
            assertArrayEquals(first.privateKey.encoded, second.privateKey.encoded)
        } finally { dir.deleteRecursively() }
    }
}
