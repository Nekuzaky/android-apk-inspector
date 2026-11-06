package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComponentsTest {
    private val openActivity = ComponentEntry(ComponentType.ACTIVITY, "a.Open", exported = true, enabled = true)
    private val guardedService = ComponentEntry(
        ComponentType.SERVICE,
        "a.Guarded",
        exported = true,
        enabled = true,
        permission = "a.PERM",
    )
    private val privateReceiver = ComponentEntry(ComponentType.RECEIVER, "a.Private", exported = false, enabled = true)
    private val all = listOf(privateReceiver, guardedService, openActivity)

    @Test
    fun filterKeepsOnlyExportedWhenAsked() {
        assertEquals(all, Components.filter(all, exportedOnly = false))
        assertEquals(listOf(guardedService, openActivity), Components.filter(all, exportedOnly = true))
    }

    @Test
    fun marksExportedWithoutPermission() {
        assertTrue(openActivity.exportedWithoutPermission)
        assertFalse(guardedService.exportedWithoutPermission)
        assertFalse(privateReceiver.exportedWithoutPermission)
    }

    @Test
    fun providerWithAnyPermissionIsNotMarked() {
        val base = ComponentEntry(ComponentType.PROVIDER, "a.P", exported = true, enabled = true)
        assertTrue(base.exportedWithoutPermission)
        assertFalse(base.copy(readPermission = "r").exportedWithoutPermission)
        assertFalse(base.copy(writePermission = "w").exportedWithoutPermission)
        assertFalse(base.copy(pathPermissions = 1).exportedWithoutPermission)
    }

    @Test
    fun describesProtection() {
        assertEquals("No permission", openActivity.protectionText)
        assertEquals("Permission: a.PERM", guardedService.protectionText)
        assertEquals("No permission · Disabled", openActivity.copy(enabled = false).protectionText)
        val provider = ComponentEntry(
            ComponentType.PROVIDER,
            "a.P",
            exported = true,
            enabled = true,
            readPermission = "r",
            pathPermissions = 2,
            authority = "a.auth",
        )
        assertEquals(
            "Authority: a.auth · Read permission: r · Write permission: none · Path permissions: 2",
            provider.protectionText,
        )
    }

    @Test
    fun protectionTextStaysNeutral() {
        val text = all.joinToString(" ") { it.protectionText }.lowercase()
        listOf("danger", "risk", "unsafe", "vulnerab", "insecure", "warning").forEach {
            assertFalse(it, text.contains(it))
        }
    }

    @Test
    fun countsStats() {
        assertEquals(ComponentStats(3, 2, 1), Components.stats(all))
    }

    @Test
    fun sortsByTypeThenName() {
        val second = openActivity.copy(name = "a.Z")
        val sorted = Components.sort(listOf(privateReceiver, guardedService, second, openActivity))
        assertEquals(listOf("a.Open", "a.Z", "a.Guarded", "a.Private"), sorted.map { it.name })
    }

    @Test
    fun describesPermissionState() {
        assertEquals("granted", PermissionEntry("p", true).stateText)
        assertEquals("not granted", PermissionEntry("p", false).stateText)
        assertEquals(Format.UNKNOWN, PermissionEntry("p", null).stateText)
    }
}
