package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ReportTest {
    private val utc = ZoneOffset.UTC
    private val generated = 1704164640000L

    private val certificate = CertDetails(
        role = "Signer",
        sha256 = "AA:BB",
        sha1 = "CC:DD",
        subject = "CN=Example",
        issuer = "CN=Issuer",
        serial = "01",
        notBefore = 1000000000000,
        notAfter = 2000000000000,
        signatureAlgorithm = "SHA256withRSA",
        publicKey = "RSA 2048 bit",
    )

    private val details = AppDetails(
        packageName = "com.example.app",
        label = "Example",
        versionName = "1.2.3",
        versionCode = 42,
        isSystem = false,
        minSdk = 29,
        targetSdk = 35,
        compileSdk = 35,
        firstInstall = 1704164640000,
        lastUpdate = 1704164640000,
        installer = "com.android.vending",
        uid = 10123,
        sharedUid = null,
        dataDir = "/data/user/0/com.example.app",
        flags = AppFlags(debuggable = true, allowBackup = false, cleartextTraffic = true, testOnly = false),
        apks = listOf(
            ApkFile("base", "/data/app/x/base.apk", 2048),
            ApkFile("config.arm64_v8a", "/data/app/x/split_config.arm64_v8a.apk", null),
        ),
        native = NativeScan(
            listOf(NativeLib("arm64-v8a", "libfoo.so"), NativeLib("x86_64", "libfoo.so"), NativeLib("x86_64", "libbar.so")),
            12,
            ZipReadStatus.COMPLETE,
        ),
        certificates = listOf(certificate),
        multipleSigners = false,
        rotated = false,
        permissions = listOf(PermissionEntry("android.permission.CAMERA", false), PermissionEntry("android.permission.VIBRATE", true)),
        components = listOf(
            ComponentEntry(ComponentType.ACTIVITY, "com.example.Main", exported = true, enabled = true),
            ComponentEntry(ComponentType.SERVICE, "com.example.Sync", exported = false, enabled = true, permission = "com.example.PERM"),
        ),
        manifestComplete = true,
    )

    @Test
    fun reportContainsEveryPart() {
        val report = ReportBuilder.build(details, generated, utc)
        listOf(
            "ApkScope report",
            "Generated: 2024-01-02 03:04",
            "Package: com.example.app",
            "Version code: 42",
            "Min SDK: 29 (Android 10)",
            "Target SDK: 35 (Android 15)",
            "Compile SDK: 35 (Android 15)",
            "Installer package: com.android.vending",
            "UID: 10123",
            "Shared UID: none",
            "Data directory: /data/user/0/com.example.app",
            "debuggable: yes",
            "allowBackup: no",
            "usesCleartextTraffic: yes",
            "testOnly: no",
            "base path: /data/app/x/base.apk",
            "base size: 2.0 KiB (2048 bytes)",
            "config.arm64_v8a size: unknown",
            "Total size: 2.0 KiB (2048 bytes)",
            "ABIs: arm64-v8a, x86_64",
            "arm64-v8a, x86_64: libfoo.so",
            "x86_64: libbar.so",
            "Signer SHA-256: AA:BB",
            "Signer SHA-1: CC:DD",
            "Signer subject: CN=Example",
            "android.permission.CAMERA: not granted",
            "android.permission.VIBRATE: granted",
            "Exported with no permission: 1",
            "com.example.Main\n  exported · No permission",
            "com.example.Sync\n  not exported · Permission: com.example.PERM",
        ).forEach { assertTrue(it, report.contains(it)) }
    }

    @Test
    fun incompleteManifestIsStated() {
        assertFalse(ReportBuilder.build(details, generated, utc).contains("Note:"))
        assertTrue(ReportBuilder.build(details.copy(manifestComplete = false), generated, utc).contains("Note:"))
    }

    @Test
    fun missingCompileSdkIsExplained() {
        val report = ReportBuilder.build(details.copy(compileSdk = null), generated, utc)
        assertTrue(report.contains("Compile SDK: not available before Android 12"))
    }

    @Test
    fun unparsedCertificateStillShowsFingerprints() {
        val unparsed = certificate.copy(subject = null, issuer = null, serial = null)
        val report = ReportBuilder.build(details.copy(certificates = listOf(unparsed)), generated, utc)
        assertTrue(report.contains("Signer SHA-256: AA:BB"))
        assertTrue(report.contains("Signer details: could not be parsed"))
    }

    @Test
    fun noCertificatesIsStated() {
        val report = ReportBuilder.build(details.copy(certificates = emptyList()), generated, utc)
        assertTrue(report.contains("Certificates: not available"))
    }

    @Test
    fun cleanedLabelCannotForgeReportLines() {
        val label = ForeignText.label("Example\nPackage: com.bank.real", "com.example.app")
        val report = ReportBuilder.build(details.copy(label = label), generated, utc)
        assertFalse(report.lines().any { it.startsWith("Package: com.bank.real") })
        assertEquals(1, report.lines().count { it.startsWith("Package: ") })
    }

    @Test
    fun nativeStatusSaysWhenPartial() {
        val partial = details.native.copy(status = ZipReadStatus.TIME_LIMIT)
        assertTrue(DetailSections.nativeStatus(partial).startsWith("partial"))
        assertEquals("complete, 12 entries", DetailSections.nativeStatus(details.native))
    }

    @Test
    fun fitLeavesShortTextAlone() {
        assertEquals(FittedText("short", false), ReportBuilder.fit("short", 100))
    }

    @Test
    fun fitCutsAtLineAndSaysSo() {
        val text = "first line\nsecond line\nthird line\n"
        val fitted = ReportBuilder.fit(text, 15)
        assertTrue(fitted.truncated)
        assertTrue(fitted.text.startsWith("first line\n\n[Report shortened"))
        assertFalse(fitted.text.contains("second"))
    }

    @Test
    fun fitNeverSplitsSurrogatePair() {
        val fitted = ReportBuilder.fit("a😀b", 2)
        assertTrue(fitted.text.startsWith("a\n"))
    }

    @Test
    fun largeReportFitsShareLimit() {
        val many = (1..5000).map {
            ComponentEntry(ComponentType.RECEIVER, "com.example.receiver.Receiver$it", exported = false, enabled = true)
        }
        val fitted = ReportBuilder.fit(ReportBuilder.build(details.copy(components = many), generated, utc))
        assertTrue(fitted.truncated)
        assertTrue(fitted.text.length < ReportBuilder.MAX_SHARE_CHARS + 200)
    }
}
