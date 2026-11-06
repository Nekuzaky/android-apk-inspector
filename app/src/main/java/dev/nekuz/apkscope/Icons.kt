package dev.nekuz.apkscope

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.util.concurrent.Executors

object Icons {
    private const val ICON_DP = 48
    private const val MAX_ICON_PX = 192
    private const val MAX_ICON_SOURCE_PX = 2048
    private const val MAX_CACHED_ICONS = 128
    private const val MAX_FAILED_ICONS = 2048
    private const val FALLBACK = android.R.drawable.sym_def_app_icon

    private val main = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private val icons = LruCache<String, Bitmap>(MAX_CACHED_ICONS)
    private val failed = HashSet<String>()

    fun bind(context: Context, view: ImageView, packageName: String) {
        view.tag = packageName
        val cached = synchronized(icons) {
            if (packageName in failed) {
                view.setImageResource(FALLBACK)
                return
            }
            icons[packageName]
        }
        if (cached != null) {
            view.setImageBitmap(cached)
            return
        }
        view.setImageResource(FALLBACK)
        val appContext = context.applicationContext
        executor.execute {
            val icon = try {
                render(appContext, packageName)
            } catch (e: Throwable) {
                null
            }
            synchronized(icons) {
                if (icon != null) {
                    icons.put(packageName, icon)
                } else {
                    if (failed.size >= MAX_FAILED_ICONS) failed.clear()
                    failed.add(packageName)
                }
            }
            main.post {
                if (view.tag == packageName && icon != null) view.setImageBitmap(icon)
            }
        }
    }

    private fun render(context: Context, packageName: String): Bitmap? {
        val manager = context.packageManager
        if (sourceTooLarge(manager, packageName)) return null
        val size = (ICON_DP * context.resources.displayMetrics.density).toInt().coerceIn(1, MAX_ICON_PX)
        val drawable = manager.getApplicationIcon(packageName)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    private fun sourceTooLarge(manager: PackageManager, packageName: String): Boolean = try {
        val application = manager.getApplicationInfo(packageName, 0)
        if (application.icon == 0) {
            false
        } else {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            manager.getResourcesForApplication(application).openRawResource(application.icon).use {
                BitmapFactory.decodeStream(it, null, options)
            }
            options.outWidth > MAX_ICON_SOURCE_PX || options.outHeight > MAX_ICON_SOURCE_PX
        }
    } catch (e: Throwable) {
        false
    }
}
