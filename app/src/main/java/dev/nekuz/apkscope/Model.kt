package dev.nekuz.apkscope

import java.util.Locale

data class AppSummary(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val targetSdk: Int,
    val lastUpdate: Long,
    val isSystem: Boolean,
) {
    val sortKey: String = label.lowercase(Locale.ROOT)
}

enum class SortOrder { NAME, LAST_UPDATE, TARGET_SDK }

object AppLists {
    private val byName = compareBy<AppSummary> { it.sortKey }.thenBy { it.packageName }

    fun filter(apps: List<AppSummary>, query: String, includeSystem: Boolean): List<AppSummary> {
        val needle = query.trim()
        return apps.filter { app ->
            (includeSystem || !app.isSystem) && (
                needle.isEmpty() ||
                    app.label.contains(needle, ignoreCase = true) ||
                    app.packageName.contains(needle, ignoreCase = true)
                )
        }
    }

    fun sort(apps: List<AppSummary>, order: SortOrder): List<AppSummary> = when (order) {
        SortOrder.NAME -> apps.sortedWith(byName)
        SortOrder.LAST_UPDATE -> apps.sortedWith(compareByDescending<AppSummary> { it.lastUpdate }.then(byName))
        SortOrder.TARGET_SDK -> apps.sortedWith(compareBy<AppSummary> { it.targetSdk }.then(byName))
    }
}

data class AppFlags(
    val debuggable: Boolean,
    val allowBackup: Boolean,
    val cleartextTraffic: Boolean,
    val testOnly: Boolean,
)

data class ApkFile(val name: String, val path: String, val sizeBytes: Long?)

data class PermissionEntry(val name: String, val granted: Boolean?) {
    val stateText: String
        get() = when (granted) {
            true -> "granted"
            false -> "not granted"
            null -> Format.UNKNOWN
        }
}

enum class ComponentType(val title: String) {
    ACTIVITY("Activities"),
    SERVICE("Services"),
    RECEIVER("Receivers"),
    PROVIDER("Providers"),
}

data class ComponentEntry(
    val type: ComponentType,
    val name: String,
    val exported: Boolean,
    val enabled: Boolean,
    val permission: String? = null,
    val readPermission: String? = null,
    val writePermission: String? = null,
    val pathPermissions: Int = 0,
    val authority: String? = null,
) {
    val hasPermission: Boolean
        get() = permission != null || readPermission != null || writePermission != null || pathPermissions > 0

    val exportedWithoutPermission: Boolean get() = exported && !hasPermission

    val protectionText: String
        get() {
            val parts = ArrayList<String>()
            if (type == ComponentType.PROVIDER) {
                authority?.let { parts.add("Authority: $it") }
                parts.add("Read permission: ${readPermission ?: "none"}")
                parts.add("Write permission: ${writePermission ?: "none"}")
                if (pathPermissions > 0) parts.add("Path permissions: $pathPermissions")
            } else {
                parts.add(permission?.let { "Permission: $it" } ?: "No permission")
            }
            if (!enabled) parts.add("Disabled")
            return parts.joinToString(" · ")
        }
}

data class ComponentStats(val total: Int, val exported: Int, val exportedWithoutPermission: Int)

object Components {
    fun filter(components: List<ComponentEntry>, exportedOnly: Boolean): List<ComponentEntry> =
        if (exportedOnly) components.filter { it.exported } else components

    fun sort(components: List<ComponentEntry>): List<ComponentEntry> =
        components.sortedWith(compareBy<ComponentEntry> { it.type.ordinal }.thenBy { it.name })

    fun stats(components: List<ComponentEntry>) = ComponentStats(
        components.size,
        components.count { it.exported },
        components.count { it.exportedWithoutPermission },
    )
}

data class AppDetails(
    val packageName: String,
    val label: String,
    val versionName: String,
    val versionCode: Long,
    val isSystem: Boolean,
    val minSdk: Int,
    val targetSdk: Int,
    val compileSdk: Int?,
    val firstInstall: Long,
    val lastUpdate: Long,
    val installer: String?,
    val uid: Int,
    val sharedUid: String?,
    val dataDir: String?,
    val flags: AppFlags,
    val apks: List<ApkFile>,
    val native: NativeScan,
    val certificates: List<CertDetails>,
    val multipleSigners: Boolean,
    val rotated: Boolean,
    val permissions: List<PermissionEntry>,
    val components: List<ComponentEntry>,
    val manifestComplete: Boolean,
)
