package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListsTest {
    private fun app(
        packageName: String,
        label: String,
        target: Int = 34,
        update: Long = 1000,
        system: Boolean = false,
    ) = AppSummary(packageName, label, "1.0", 1, target, update, system)

    private val alpha = app("com.z.alpha", "Alpha", target = 30, update = 5000)
    private val beta = app("com.a.beta", "beta", target = 35, update = 9000)
    private val camera = app("com.android.camera", "Camera", target = 33, update = 1000, system = true)
    private val apps = listOf(camera, beta, alpha)

    private fun packages(list: List<AppSummary>) = list.map { it.packageName }

    @Test
    fun sortsByNameIgnoringCase() {
        assertEquals(
            listOf("com.z.alpha", "com.a.beta", "com.android.camera"),
            packages(AppLists.sort(apps, SortOrder.NAME)),
        )
    }

    @Test
    fun sortsByLastUpdateNewestFirst() {
        assertEquals(
            listOf("com.a.beta", "com.z.alpha", "com.android.camera"),
            packages(AppLists.sort(apps, SortOrder.LAST_UPDATE)),
        )
    }

    @Test
    fun sortsByTargetSdkLowestFirst() {
        assertEquals(
            listOf("com.z.alpha", "com.android.camera", "com.a.beta"),
            packages(AppLists.sort(apps, SortOrder.TARGET_SDK)),
        )
    }

    @Test
    fun breaksTiesByLabelThenPackage() {
        val second = app("com.b.same", "Same", target = 34)
        val first = app("com.a.same", "Same", target = 34)
        val other = app("com.c.other", "Other", target = 34)
        val expected = listOf("com.c.other", "com.a.same", "com.b.same")
        assertEquals(expected, packages(AppLists.sort(listOf(second, first, other), SortOrder.NAME)))
        assertEquals(expected, packages(AppLists.sort(listOf(second, first, other), SortOrder.TARGET_SDK)))
        assertEquals(expected, packages(AppLists.sort(listOf(second, first, other), SortOrder.LAST_UPDATE)))
    }

    @Test
    fun hidesSystemAppsUnlessAsked() {
        assertFalse(AppLists.filter(apps, "", includeSystem = false).contains(camera))
        assertTrue(AppLists.filter(apps, "", includeSystem = true).contains(camera))
    }

    @Test
    fun searchesLabelAndPackageIgnoringCase() {
        assertEquals(listOf(alpha), AppLists.filter(apps, "ALPH", includeSystem = true))
        assertEquals(listOf(beta), AppLists.filter(apps, "com.a.", includeSystem = true))
        assertEquals(listOf(alpha), AppLists.filter(apps, "  alpha  ", includeSystem = true))
        assertEquals(emptyList<AppSummary>(), AppLists.filter(apps, "nothing", includeSystem = true))
    }

    @Test
    fun searchCannotRevealHiddenSystemApps() {
        assertEquals(emptyList<AppSummary>(), AppLists.filter(apps, "camera", includeSystem = false))
    }
}
