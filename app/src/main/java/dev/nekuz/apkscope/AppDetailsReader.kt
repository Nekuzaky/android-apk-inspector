package dev.nekuz.apkscope

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.ComponentInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File

sealed class DetailsResult {
    data class Ok(val details: AppDetails) : DetailsResult()
    data object Missing : DetailsResult()
    data object Failed : DetailsResult()
}

object AppDetailsReader {
    private const val MAX_SPLITS = 256
    private const val MAX_CERTIFICATES = 32
    private const val UNNAMED = "(unnamed)"

    private val MANIFEST_FLAGS = listOf(
        PackageManager.GET_ACTIVITIES,
        PackageManager.GET_RECEIVERS,
        PackageManager.GET_SERVICES,
        PackageManager.GET_PROVIDERS,
        PackageManager.GET_PERMISSIONS,
        PackageManager.GET_SIGNING_CERTIFICATES,
    )

    private class ManifestRead(val info: PackageInfo, val complete: Boolean)

    fun read(context: Context, packageName: String): DetailsResult = try {
        val manager = context.packageManager
        val manifest = readManifest(manager, packageName)
        val application = manifest?.info?.applicationInfo
        when {
            manifest == null -> DetailsResult.Missing
            application == null -> DetailsResult.Failed
            else -> DetailsResult.Ok(build(manager, packageName, manifest, application))
        }
    } catch (e: Throwable) {
        DetailsResult.Failed
    }

    fun apkFiles(application: ApplicationInfo): List<ApkFile> {
        val files = ArrayList<ApkFile>()
        application.sourceDir?.let { files.add(ApkFile("base", it, sizeOf(it))) }
        val names = application.splitNames
        application.splitSourceDirs.orEmpty().take(MAX_SPLITS).forEachIndexed { index, path ->
            val name = ForeignText.clean(names?.getOrNull(index), ForeignText.MAX_LABEL).ifEmpty { "split_$index" }
            files.add(ApkFile(name, path, sizeOf(path)))
        }
        return files
    }

    private fun build(
        manager: PackageManager,
        packageName: String,
        manifest: ManifestRead,
        application: ApplicationInfo,
    ): AppDetails {
        val info = manifest.info
        val apks = safely(emptyList()) { apkFiles(application) }
        val signing = safely(SigningRead(emptyList(), false, false)) { readSigning(info) }
        val label = safely(null) { application.loadLabel(manager) }
        return AppDetails(
            packageName = packageName,
            label = ForeignText.label(label, packageName),
            versionName = ForeignText.clean(info.versionName, ForeignText.MAX_LABEL),
            versionCode = info.longVersionCode,
            isSystem = AppListStore.isSystem(application),
            minSdk = application.minSdkVersion,
            targetSdk = application.targetSdkVersion,
            compileSdk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) application.compileSdkVersion else null,
            firstInstall = info.firstInstallTime,
            lastUpdate = info.lastUpdateTime,
            installer = safely(null) { installerOf(manager, packageName) },
            uid = application.uid,
            sharedUid = ForeignText.optional(info.sharedUserId, ForeignText.MAX_LABEL * 2),
            dataDir = ForeignText.optional(application.dataDir, ForeignText.MAX_VALUE),
            flags = AppFlags(
                debuggable = application.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
                allowBackup = application.flags and ApplicationInfo.FLAG_ALLOW_BACKUP != 0,
                cleartextTraffic = application.flags and ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC != 0,
                testOnly = application.flags and ApplicationInfo.FLAG_TEST_ONLY != 0,
            ),
            apks = apks,
            native = safely(NativeScan(emptyList(), 0, ZipReadStatus.UNREADABLE)) {
                ApkZipReader.readAll(apks.map { File(it.path) }, ZipBudget())
            },
            certificates = signing.certificates,
            multipleSigners = signing.multipleSigners,
            rotated = signing.rotated,
            permissions = safely(emptyList()) { readPermissions(info) },
            components = safely(emptyList()) { readComponents(info) },
            manifestComplete = manifest.complete,
        )
    }

    private fun readManifest(manager: PackageManager, packageName: String): ManifestRead? {
        val all = MANIFEST_FLAGS.reduce { flags, flag -> flags or flag } or PackageManager.MATCH_DISABLED_COMPONENTS
        try {
            return ManifestRead(manager.getPackageInfo(packageName, all), true)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        } catch (e: RuntimeException) {
            var merged: PackageInfo? = null
            var complete = true
            for (flag in MANIFEST_FLAGS) {
                val part = try {
                    manager.getPackageInfo(packageName, PackageManager.MATCH_DISABLED_COMPONENTS or flag)
                } catch (error: Throwable) {
                    null
                }
                if (part == null) {
                    complete = false
                    continue
                }
                val info = merged ?: part.also { merged = it }
                part.requestedPermissions?.let { info.requestedPermissions = it }
                part.requestedPermissionsFlags?.let { info.requestedPermissionsFlags = it }
                part.activities?.let { info.activities = it }
                part.receivers?.let { info.receivers = it }
                part.services?.let { info.services = it }
                part.providers?.let { info.providers = it }
                part.signingInfo?.let { info.signingInfo = it }
            }
            return merged?.let { ManifestRead(it, complete) }
        }
    }

    private fun installerOf(manager: PackageManager, packageName: String): String? {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            manager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            legacyInstaller(manager, packageName)
        }
        return ForeignText.optional(raw, ForeignText.MAX_VALUE)
    }

    @Suppress("DEPRECATION")
    private fun legacyInstaller(manager: PackageManager, packageName: String): String? =
        manager.getInstallerPackageName(packageName)

    private fun sizeOf(path: String): Long? = try {
        File(path).takeIf { it.isFile }?.length()
    } catch (e: Throwable) {
        null
    }

    private class SigningRead(
        val certificates: List<CertDetails>,
        val multipleSigners: Boolean,
        val rotated: Boolean,
    )

    private fun readSigning(info: PackageInfo): SigningRead {
        val signing = info.signingInfo ?: return SigningRead(emptyList(), false, false)
        if (signing.hasMultipleSigners()) {
            val signers = signing.apkContentsSigners.orEmpty().take(MAX_CERTIFICATES)
            val certificates = signers.mapIndexed { index, signer -> certificate(signer, "Signer ${index + 1}") }
            return SigningRead(certificates, true, false)
        }
        val history = signing.signingCertificateHistory.orEmpty().toList().asReversed().take(MAX_CERTIFICATES)
        val certificates = history.mapIndexed { index, signer ->
            certificate(signer, if (index == 0) "Signer" else "Previous signer $index")
        }
        return SigningRead(certificates, false, history.size > 1)
    }

    private fun certificate(signature: Signature, role: String): CertDetails =
        Certificates.details(signature.toByteArray(), role)

    private fun readPermissions(info: PackageInfo): List<PermissionEntry> {
        val flags = info.requestedPermissionsFlags
        return info.requestedPermissions.orEmpty()
            .mapIndexed { index, name ->
                val granted = flags?.getOrNull(index)?.let { it and PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }
                PermissionEntry(ForeignText.optional(name) ?: UNNAMED, granted)
            }
            .distinctBy { it.name }
            .sortedBy { it.name }
    }

    private fun readComponents(info: PackageInfo): List<ComponentEntry> {
        val entries = ArrayList<ComponentEntry>()
        info.activities.orEmpty().forEach { activity ->
            entries.add(
                ComponentEntry(
                    ComponentType.ACTIVITY,
                    nameOf(activity),
                    activity.exported,
                    activity.isEnabled,
                    permission = ForeignText.optional(activity.permission),
                ),
            )
        }
        info.services.orEmpty().forEach { service ->
            entries.add(
                ComponentEntry(
                    ComponentType.SERVICE,
                    nameOf(service),
                    service.exported,
                    service.isEnabled,
                    permission = ForeignText.optional(service.permission),
                ),
            )
        }
        info.receivers.orEmpty().forEach { receiver ->
            entries.add(
                ComponentEntry(
                    ComponentType.RECEIVER,
                    nameOf(receiver),
                    receiver.exported,
                    receiver.isEnabled,
                    permission = ForeignText.optional(receiver.permission),
                ),
            )
        }
        info.providers.orEmpty().forEach { provider ->
            entries.add(
                ComponentEntry(
                    ComponentType.PROVIDER,
                    nameOf(provider),
                    provider.exported,
                    provider.isEnabled,
                    readPermission = ForeignText.optional(provider.readPermission),
                    writePermission = ForeignText.optional(provider.writePermission),
                    pathPermissions = provider.pathPermissions?.size ?: 0,
                    authority = ForeignText.optional(provider.authority),
                ),
            )
        }
        return Components.sort(entries)
    }

    private fun nameOf(component: ComponentInfo): String = ForeignText.optional(component.name) ?: UNNAMED

    private inline fun <T> safely(fallback: T, read: () -> T): T = try {
        read()
    } catch (e: Throwable) {
        fallback
    }
}
