package dev.nekuz.apkscope

import java.time.ZoneId

data class Field(val label: String, val value: String)

data class Section(val title: String, val fields: List<Field>)

object DetailSections {
    private const val MAX_PATH = 4096

    fun build(details: AppDetails, zone: ZoneId): List<Section> = listOf(
        identity(details),
        sdk(details),
        install(details, zone),
        flags(details),
        apkFiles(details),
        nativeCode(details),
        signing(details, zone),
    )

    private fun identity(details: AppDetails) = Section(
        "Identity",
        listOf(
            Field("Package", details.packageName),
            Field("Label", details.label),
            Field("Version name", details.versionName.ifEmpty { Format.UNKNOWN }),
            Field("Version code", details.versionCode.toString()),
            Field("System app", Format.yesNo(details.isSystem)),
        ),
    )

    private fun sdk(details: AppDetails) = Section(
        "SDK levels",
        listOf(
            Field("Min SDK", Format.sdk(details.minSdk)),
            Field("Target SDK", Format.sdk(details.targetSdk)),
            Field(
                "Compile SDK",
                details.compileSdk?.let { Format.sdk(it) } ?: "not available before Android 12",
            ),
        ),
    )

    private fun install(details: AppDetails, zone: ZoneId) = Section(
        "Install",
        listOf(
            Field("Installed", Format.dateTime(details.firstInstall, zone)),
            Field("Updated", Format.dateTime(details.lastUpdate, zone)),
            Field("Installer package", details.installer ?: Format.UNKNOWN),
            Field("UID", details.uid.toString()),
            Field("Shared UID", details.sharedUid ?: "none"),
            Field("Data directory", details.dataDir ?: Format.UNKNOWN),
        ),
    )

    private fun flags(details: AppDetails) = Section(
        "Manifest flags",
        listOf(
            Field("debuggable", Format.yesNo(details.flags.debuggable)),
            Field("allowBackup", Format.yesNo(details.flags.allowBackup)),
            Field("usesCleartextTraffic", Format.yesNo(details.flags.cleartextTraffic)),
            Field("testOnly", Format.yesNo(details.flags.testOnly)),
        ),
    )

    private fun apkFiles(details: AppDetails): Section {
        val total = details.apks.mapNotNull { it.sizeBytes }.sum()
        val fields = ArrayList<Field>()
        fields.add(Field("Files", details.apks.size.toString()))
        fields.add(Field("Total size", Format.sizeExact(total)))
        details.apks.forEach { apk ->
            fields.add(Field("${apk.name} path", ForeignText.clean(apk.path, MAX_PATH)))
            fields.add(Field("${apk.name} size", Format.sizeExact(apk.sizeBytes)))
        }
        return Section("APK files", fields)
    }

    private fun nativeCode(details: AppDetails): Section {
        val native = details.native
        val fields = ArrayList<Field>()
        fields.add(Field("ABIs", native.abis.joinToString(", ").ifEmpty { "none" }))
        fields.add(Field("APK read status", nativeStatus(native)))
        native.libraries
            .groupBy { it.name }
            .toSortedMap()
            .forEach { (name, libraries) ->
                fields.add(Field(libraries.map { it.abi }.sorted().joinToString(", "), name))
            }
        return Section("Native libraries", fields)
    }

    fun nativeStatus(native: NativeScan): String = when (native.status) {
        ZipReadStatus.COMPLETE -> "complete, ${native.entries} entries"
        ZipReadStatus.MALFORMED -> "partial, the zip structure was not fully readable"
        ZipReadStatus.UNREADABLE -> "partial, at least one APK could not be opened"
        ZipReadStatus.ENTRY_LIMIT -> "partial, stopped at the limit after ${native.entries} entries"
        ZipReadStatus.TIME_LIMIT -> "partial, stopped at the time limit after ${native.entries} entries"
    }

    private fun signing(details: AppDetails, zone: ZoneId): Section {
        val fields = ArrayList<Field>()
        if (details.certificates.isEmpty()) {
            fields.add(Field("Certificates", "not available"))
            return Section("Signing", fields)
        }
        fields.add(Field("Multiple signers", Format.yesNo(details.multipleSigners)))
        fields.add(Field("Key rotation in history", Format.yesNo(details.rotated)))
        details.certificates.forEach { certificate ->
            val role = certificate.role
            fields.add(Field("$role SHA-256", certificate.sha256))
            fields.add(Field("$role SHA-1", certificate.sha1))
            if (certificate.subject == null && certificate.issuer == null) {
                fields.add(Field("$role details", "could not be parsed"))
                return@forEach
            }
            fields.add(Field("$role subject", certificate.subject ?: Format.UNKNOWN))
            fields.add(Field("$role issuer", certificate.issuer ?: Format.UNKNOWN))
            fields.add(Field("$role serial", certificate.serial ?: Format.UNKNOWN))
            fields.add(Field("$role valid from", Format.dateTime(certificate.notBefore, zone)))
            fields.add(Field("$role valid until", Format.dateTime(certificate.notAfter, zone)))
            fields.add(Field("$role signature algorithm", certificate.signatureAlgorithm ?: Format.UNKNOWN))
            fields.add(Field("$role public key", certificate.publicKey ?: Format.UNKNOWN))
        }
        return Section("Signing", fields)
    }
}

data class FittedText(val text: String, val truncated: Boolean)

object ReportBuilder {
    const val MAX_SHARE_CHARS = 100_000

    fun build(details: AppDetails, generatedAt: Long, zone: ZoneId): String = buildString {
        append("ApkScope report\n")
        append("Generated: ").append(Format.dateTime(generatedAt, zone)).append('\n')
        if (!details.manifestComplete) {
            append("Note: Android could not return the whole manifest, so some lists may be incomplete.\n")
        }
        DetailSections.build(details, zone).forEach { section ->
            append("\n== ").append(section.title).append(" ==\n")
            section.fields.forEach { field ->
                append(field.label).append(": ").append(field.value).append('\n')
            }
        }
        append("\n== Permissions (").append(details.permissions.size).append(") ==\n")
        details.permissions.forEach { permission ->
            append(permission.name).append(": ").append(permission.stateText).append('\n')
        }
        val stats = Components.stats(details.components)
        append("\n== Components (").append(stats.total).append(") ==\n")
        append("Exported: ").append(stats.exported).append('\n')
        append("Exported with no permission: ").append(stats.exportedWithoutPermission).append('\n')
        ComponentType.entries.forEach { type ->
            val entries = details.components.filter { it.type == type }
            append("\n-- ").append(type.title).append(" (").append(entries.size).append(") --\n")
            entries.forEach { entry ->
                append(entry.name).append('\n')
                append("  ").append(if (entry.exported) "exported" else "not exported")
                append(" · ").append(entry.protectionText).append('\n')
            }
        }
    }

    fun fit(text: String, maxChars: Int = MAX_SHARE_CHARS): FittedText {
        if (text.length <= maxChars) return FittedText(text, false)
        var cut = text.lastIndexOf('\n', maxChars - 1)
        if (cut <= 0) cut = maxChars
        if (Character.isHighSurrogate(text[cut - 1])) cut--
        val omitted = text.length - cut
        val shortened = text.substring(0, cut) +
            "\n\n[Report shortened: $omitted characters omitted. Save as file for the full text.]\n"
        return FittedText(shortened, true)
    }
}
