package dev.nekuz.apkscope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignTextTest {
    private val fallback = "com.example.app"

    @Test
    fun keepsOrdinaryLabels() {
        assertEquals("Maps", ForeignText.label("Maps", fallback))
        assertEquals("Café Noir", ForeignText.label("Café Noir", fallback))
        assertEquals("日本語アプリ", ForeignText.label("日本語アプリ", fallback))
    }

    @Test
    fun removesControlCharacters() {
        assertEquals("evilline", ForeignText.label("evil\nline", fallback))
        assertEquals("ab", ForeignText.label("a\u0000\u0007b\r", fallback))
    }

    @Test
    fun removesBidiAndFormatCharacters() {
        assertEquals("gpj.exe", ForeignText.label("‮gpj.exe‬", fallback))
        assertEquals("ab", ForeignText.label("a⁦⁩​‍­﻿b", fallback))
        assertEquals("ab", ForeignText.label("a  b", fallback))
    }

    @Test
    fun removesPrivateUseAndLoneSurrogates() {
        assertEquals("ab", ForeignText.label("a\uD800b", fallback))
    }

    @Test
    fun fallsBackWhenNothingIsVisible() {
        assertEquals(fallback, ForeignText.label(null, fallback))
        assertEquals(fallback, ForeignText.label("", fallback))
        assertEquals(fallback, ForeignText.label("    　", fallback))
        assertEquals(fallback, ForeignText.label("‮​", fallback))
        assertEquals(fallback, ForeignText.label("ㅤᅟ⠀", fallback))
        assertEquals(fallback, ForeignText.label("́́", fallback))
    }

    @Test
    fun collapsesAndTrimsSpaces() {
        assertEquals("a b", ForeignText.label("  a  　  b  ", fallback))
    }

    @Test
    fun capsLengthAndMarksIt() {
        val label = ForeignText.label("x".repeat(5000), fallback)
        assertEquals(ForeignText.MAX_LABEL + 1, label.length)
        assertTrue(label.endsWith("…"))
    }

    @Test
    fun doesNotMarkLabelAtExactCap() {
        val label = ForeignText.label("x".repeat(ForeignText.MAX_LABEL), fallback)
        assertEquals(ForeignText.MAX_LABEL, label.length)
        assertFalse(label.endsWith("…"))
    }

    @Test
    fun capNeverSplitsSurrogatePair() {
        val label = ForeignText.clean("😀".repeat(10), 7)
        assertEquals("😀😀😀…", label)
    }

    @Test
    fun limitsCombiningMarkStacks() {
        assertEquals("á́́́b", ForeignText.label("a" + "́".repeat(200) + "b", fallback))
    }

    @Test
    fun boundsVeryLongInput() {
        val hiddenThenText = "​".repeat(200_000) + "text"
        assertEquals(fallback, ForeignText.label(hiddenThenText, fallback))
    }

    @Test
    fun optionalKeepsAbsenceAndUnprintableApart() {
        assertNull(ForeignText.optional(null))
        assertNull(ForeignText.optional(""))
        assertEquals(ForeignText.UNPRINTABLE, ForeignText.optional("‮"))
        assertEquals("a.b", ForeignText.optional("a.‮b"))
    }

    @Test
    fun validatesPackageNames() {
        assertTrue(PackageNames.isValid("android"))
        assertTrue(PackageNames.isValid("com.example.app_2"))
        assertFalse(PackageNames.isValid(null))
        assertFalse(PackageNames.isValid(""))
        assertFalse(PackageNames.isValid("com..example"))
        assertFalse(PackageNames.isValid(".com.example"))
        assertFalse(PackageNames.isValid("com.example."))
        assertFalse(PackageNames.isValid("../../etc"))
        assertFalse(PackageNames.isValid("com.exa‮mple"))
        assertFalse(PackageNames.isValid("com.example/.Main"))
        assertFalse(PackageNames.isValid("a".repeat(256)))
        assertTrue(PackageNames.isValid("a".repeat(255)))
    }

    @Test
    fun buildsSafeFileNames() {
        assertEquals(
            "com.example.app-1.2.3-base.apk",
            FileNames.apk("com.example.app", "1.2.3", "base"),
        )
        assertEquals(
            "com.example.app-_._._etc_passwd-base.apk",
            FileNames.apk("com.example.app", "/../../etc/passwd", "base"),
        )
        assertEquals("com.example.app-report.txt", FileNames.report("com.example.app"))
    }

    @Test
    fun fileNameNeverCutsThePackage() {
        val longPackage = "a".repeat(230)
        val name = FileNames.apk(longPackage, "9".repeat(500), "split_config.arm64_v8a")
        assertTrue(name.startsWith(longPackage))
        assertTrue(name.endsWith(".apk"))
        assertTrue(name.length <= 240)
    }
}
