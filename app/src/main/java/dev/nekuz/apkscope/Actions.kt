package dev.nekuz.apkscope

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

object Actions {
    private const val CLIP_LABEL = "ApkScope"

    fun copy(context: Context, value: String) {
        val done = try {
            val clipboard = context.getSystemService(ClipboardManager::class.java)
            clipboard.setPrimaryClip(ClipData.newPlainText(CLIP_LABEL, value))
            true
        } catch (e: RuntimeException) {
            false
        }
        toast(context, if (done) R.string.copied else R.string.copy_failed)
    }

    fun openApp(activity: Activity, packageName: String) {
        val intent = try {
            activity.packageManager.getLaunchIntentForPackage(packageName)
        } catch (e: RuntimeException) {
            null
        }
        if (intent == null || intent.component?.packageName != packageName) {
            toast(activity, R.string.no_launcher)
            return
        }
        try {
            activity.startActivity(intent)
        } catch (e: RuntimeException) {
            toast(activity, R.string.open_failed)
        }
    }

    fun openSettings(activity: Activity, packageName: String) {
        try {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null),
            )
            val handler = activity.packageManager
                .queryIntentActivities(intent, PackageManager.MATCH_SYSTEM_ONLY or PackageManager.MATCH_DEFAULT_ONLY)
                .firstOrNull()
                ?.activityInfo
            if (handler == null) {
                toast(activity, R.string.settings_failed)
                return
            }
            intent.component = ComponentName(handler.packageName, handler.name)
            activity.startActivity(intent)
        } catch (e: RuntimeException) {
            toast(activity, R.string.settings_failed)
        }
    }

    fun shareText(activity: Activity, subject: String, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            activity.startActivity(Intent.createChooser(send, null))
        } catch (e: RuntimeException) {
            toast(activity, R.string.report_share_failed)
        }
    }

    fun toast(context: Context, text: Int) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }
}
