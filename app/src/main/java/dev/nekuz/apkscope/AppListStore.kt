package dev.nekuz.apkscope

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

object AppListStore {
    data class Snapshot(val apps: List<AppSummary>, val loaded: Boolean, val failed: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val listeners = LinkedHashSet<() -> Unit>()
    private var running = false

    var snapshot = Snapshot(emptyList(), loaded = false, failed = false)
        private set

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun refresh(context: Context) {
        if (running) return
        running = true
        val manager = context.applicationContext.packageManager
        executor.execute {
            val apps = try {
                readAll(manager)
            } catch (e: Throwable) {
                null
            }
            main.post {
                running = false
                snapshot = when {
                    apps != null -> Snapshot(apps, loaded = true, failed = false)
                    snapshot.loaded -> snapshot
                    else -> Snapshot(emptyList(), loaded = true, failed = true)
                }
                listeners.toList().forEach { it() }
            }
        }
    }

    private fun readAll(manager: PackageManager): List<AppSummary> =
        manager.getInstalledPackages(0).mapNotNull { info -> readOne(manager, info) }

    private fun readOne(manager: PackageManager, info: PackageInfo): AppSummary? {
        val packageName = try {
            info.packageName
        } catch (e: Throwable) {
            null
        }
        if (!PackageNames.isValid(packageName)) return null
        val name = packageName ?: return null
        return try {
            val application = info.applicationInfo
            val label = try {
                application?.loadLabel(manager)
            } catch (e: Throwable) {
                null
            }
            AppSummary(
                packageName = name,
                label = ForeignText.label(label, name),
                versionName = ForeignText.clean(info.versionName, ForeignText.MAX_LABEL),
                versionCode = info.longVersionCode,
                targetSdk = application?.targetSdkVersion ?: 0,
                lastUpdate = info.lastUpdateTime,
                isSystem = application?.let { isSystem(it) } ?: false,
            )
        } catch (e: Throwable) {
            AppSummary(name, name, "", 0, 0, 0, false)
        }
    }

    fun isSystem(application: ApplicationInfo): Boolean {
        val flags = ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP
        return application.flags and flags != 0
    }
}
