package app.familygem.geni

import androidx.appcompat.app.AppCompatDelegate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceModeTest {

    @Test
    fun storedValuesAreStable() {
        assertEquals("original", AppearanceMode.ORIGINAL.stored)
        assertEquals("geni_light", AppearanceMode.GENI_LIGHT.stored)
        assertEquals("geni_dark", AppearanceMode.GENI_DARK.stored)
    }

    @Test
    fun parsesEveryStoredValue() {
        for (mode in AppearanceMode.entries) assertEquals(mode, AppearanceMode.fromStored(mode.stored))
    }

    @Test
    fun missingOrUnknownIsOriginal() {
        assertEquals(AppearanceMode.ORIGINAL, AppearanceMode.fromStored(null))
        assertEquals(AppearanceMode.ORIGINAL, AppearanceMode.fromStored(""))
        assertEquals(AppearanceMode.ORIGINAL, AppearanceMode.fromStored("GENI_DARK"))
        assertEquals(AppearanceMode.ORIGINAL, AppearanceMode.fromStored("sepia"))
    }

    @Test
    fun mapsToNightModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppearanceMode.ORIGINAL.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppearanceMode.GENI_LIGHT.nightMode)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppearanceMode.GENI_DARK.nightMode)
    }

    @Test
    fun onlyGeniThemesAreGeni() {
        assertFalse(AppearanceMode.ORIGINAL.isGeni)
        assertTrue(AppearanceMode.GENI_LIGHT.isGeni)
        assertTrue(AppearanceMode.GENI_DARK.isGeni)
    }
}
