package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyStore
import java.security.MessageDigest
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class CertificatesTest {
    @Test
    fun formatsHexWithColons() {
        assertEquals("", Certificates.hex(byteArrayOf()))
        assertEquals("0A", Certificates.hex(byteArrayOf(0x0A)))
        assertEquals("00:FF:7F:80", Certificates.hex(byteArrayOf(0, -1, 127, -128)))
    }

    @Test
    fun computesKnownDigests() {
        val input = "abc".toByteArray()
        assertEquals(
            "BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD",
            Certificates.fingerprint(input, "SHA-256"),
        )
        assertEquals(
            "A9:99:3E:36:47:06:81:6A:BA:3E:25:71:78:50:C2:6C:9C:D0:D8:9D",
            Certificates.fingerprint(input, "SHA-1"),
        )
    }

    @Test
    fun keepsFingerprintsWhenBytesAreNotACertificate() {
        val details = Certificates.details("not a certificate".toByteArray(), "Signer")
        assertEquals("Signer", details.role)
        assertEquals(95, details.sha256.length)
        assertEquals(59, details.sha1.length)
        assertNull(details.subject)
        assertNull(details.issuer)
        assertNull(details.notAfter)
    }

    @Test
    fun readsARealCertificate() {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        val manager = factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        val certificate = manager.acceptedIssuers.first()
        val encoded = certificate.encoded

        val details = Certificates.details(encoded, "Signer")

        assertEquals(Certificates.hex(MessageDigest.getInstance("SHA-256").digest(encoded)), details.sha256)
        assertEquals(Certificates.hex(MessageDigest.getInstance("SHA-1").digest(encoded)), details.sha1)
        assertFalse(details.subject.isNullOrEmpty())
        assertFalse(details.issuer.isNullOrEmpty())
        assertNotNull(details.serial)
        assertEquals(certificate.notBefore.time, details.notBefore)
        assertEquals(certificate.notAfter.time, details.notAfter)
        assertFalse(details.signatureAlgorithm.isNullOrEmpty())
        assertTrue(details.publicKey!!.isNotEmpty())
    }
}
