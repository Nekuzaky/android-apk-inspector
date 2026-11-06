package dev.nekuz.apkscope

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.time.ZoneId
import java.util.concurrent.Executors

object DocumentSaver {
    const val APK_MIME = "application/vnd.android.package-archive"
    const val TEXT_MIME = "text/plain"

    private const val BUFFER_SIZE = 65_536

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    fun saveApk(context: Context, packageName: String, apkName: String, target: Uri) {
        val appContext = context.applicationContext
        start(appContext, target) {
            val application = appContext.packageManager.getApplicationInfo(packageName, 0)
            val apk = AppDetailsReader.apkFiles(application).firstOrNull { it.name == apkName }
                ?: throw IOException()
            val source = File(apk.path)
            if (!source.isFile) throw IOException()
            FileInputStream(source)
        }
    }

    fun saveReport(context: Context, packageName: String, target: Uri) {
        val appContext = context.applicationContext
        start(appContext, target) {
            val result = AppDetailsReader.read(appContext, packageName)
            if (result !is DetailsResult.Ok) throw IOException()
            ReportBuilder.build(result.details, System.currentTimeMillis(), ZoneId.systemDefault())
                .byteInputStream(Charsets.UTF_8)
        }
    }

    private fun start(context: Context, target: Uri, open: () -> InputStream) {
        Actions.toast(context, R.string.save_started)
        executor.execute {
            val saved = try {
                copy(context, target, open)
                true
            } catch (e: Throwable) {
                discard(context, target)
                false
            }
            main.post { Actions.toast(context, if (saved) R.string.save_done else R.string.save_failed) }
        }
    }

    private fun copy(context: Context, target: Uri, open: () -> InputStream) {
        open().use { input ->
            val output = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException()
            output.use {
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    it.write(buffer, 0, count)
                }
            }
        }
    }

    private fun discard(context: Context, target: Uri) {
        try {
            DocumentsContract.deleteDocument(context.contentResolver, target)
        } catch (e: Throwable) {
            return
        }
    }
}
