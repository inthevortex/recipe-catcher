package org.eu.angsumanray.recipecatcher.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IsoDurationTest {

    @Test
    fun parseToMinutes_parsesStandardIsoFormats() {
        assertEquals(75, IsoDuration.parseToMinutes("PT1H15M"))
        assertEquals(45, IsoDuration.parseToMinutes("PT45M"))
        assertEquals(60, IsoDuration.parseToMinutes("PT1H"))
        assertEquals(150, IsoDuration.parseToMinutes("PT2H30M"))
        assertEquals(0, IsoDuration.parseToMinutes("PT30S"))
        assertEquals(1440, IsoDuration.parseToMinutes("P1D"))
        assertEquals(1560, IsoDuration.parseToMinutes("P1DT2H"))
    }

    @Test
    fun parseToSeconds_parsesAccurateSeconds() {
        assertEquals(4500L, IsoDuration.parseToSeconds("PT1H15M"))
        assertEquals(2700L, IsoDuration.parseToSeconds("PT45M"))
        assertEquals(30L, IsoDuration.parseToSeconds("PT30S"))
    }

    @Test
    fun parseToMinutes_parsesLenientFormats() {
        assertEquals(75, IsoDuration.parseToMinutes("1H15M"))
        assertEquals(45, IsoDuration.parseToMinutes("45M"))
        assertEquals(75, IsoDuration.parseToMinutes("pt1h15m"))
    }

    @Test
    fun parseToMinutes_handlesInvalidInputs() {
        assertNull(IsoDuration.parseToMinutes(null))
        assertNull(IsoDuration.parseToMinutes(""))
        assertNull(IsoDuration.parseToMinutes("not-a-duration"))
    }

    @Test
    fun toIsoDuration_formatsMinutesToIso() {
        assertEquals("PT1H15M", IsoDuration.toIsoDuration(75))
        assertEquals("PT1H", IsoDuration.toIsoDuration(60))
        assertEquals("PT45M", IsoDuration.toIsoDuration(45))
        assertEquals("PT2H30M", IsoDuration.toIsoDuration(150))
        assertEquals("PT0M", IsoDuration.toIsoDuration(0))
    }

    @Test
    fun formatDisplay_formatsHumanReadable() {
        assertEquals("1 hr 15 min", IsoDuration.formatDisplay(75))
        assertEquals("1 hr", IsoDuration.formatDisplay(60))
        assertEquals("2 hrs", IsoDuration.formatDisplay(120))
        assertEquals("45 min", IsoDuration.formatDisplay(45))
        assertEquals("", IsoDuration.formatDisplay(null))
        assertEquals("", IsoDuration.formatDisplay(0))
    }
}
