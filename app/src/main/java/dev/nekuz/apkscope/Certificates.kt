package dev.nekuz.apkscope

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey

data class CertDetails(
    val role: String,
    val sha256: String,
    val sha1: String,
    val subject: String?,
    val issuer: String?,
    val serial: String?,
    val notBefore: Long?,
    val notAfter: Long?,
    val signatureAlgorithm: String?,
    val publicKey: String?,
)

object Certificates {
    private const val HEX = "0123456789ABCDEF"
    private const val MAX_FIELD = 512

    fun hex(bytes: ByteArray): String {
        val text = StringBuilder(bytes.size * 3)
        bytes.forEachIndexed { index, byte ->
            if (index > 0) text.append(':')
            val value = byte.toInt() and 0xFF
            text.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return text.toString()
    }

    fun fingerprint(encoded: ByteArray, algorithm: String): String =
        hex(MessageDigest.getInstance(algorithm).digest(encoded))

    fun details(encoded: ByteArray, role: String): CertDetails {
        val certificate = parse(encoded)
        return CertDetails(
            role = role,
            sha256 = fingerprint(encoded, "SHA-256"),
            sha1 = fingerprint(encoded, "SHA-1"),
            subject = certificate?.let { field { it.subjectX500Principal.name } },
            issuer = certificate?.let { field { it.issuerX500Principal.name } },
            serial = certificate?.let { field { hex(it.serialNumber.toByteArray()) } },
            notBefore = certificate?.let { cert -> runCatching { cert.notBefore.time }.getOrNull() },
            notAfter = certificate?.let { cert -> runCatching { cert.notAfter.time }.getOrNull() },
            signatureAlgorithm = certificate?.let { field { it.sigAlgName } },
            publicKey = certificate?.let { field { describeKey(it) } },
        )
    }

    private fun parse(encoded: ByteArray): X509Certificate? = try {
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(encoded)) as? X509Certificate
    } catch (e: Throwable) {
        null
    }

    private fun field(read: () -> String?): String? = try {
        ForeignText.optional(read(), MAX_FIELD)
    } catch (e: Throwable) {
        null
    }

    private fun describeKey(certificate: X509Certificate): String {
        val key = certificate.publicKey
        return when (key) {
            is RSAPublicKey -> "RSA ${key.modulus.bitLength()} bit"
            is ECPublicKey -> "EC ${key.params.order.bitLength()} bit"
            else -> key.algorithm
        }
    }
}
