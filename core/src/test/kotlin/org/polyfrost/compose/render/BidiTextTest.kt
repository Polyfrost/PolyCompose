package org.polyfrost.compose.render

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class BidiTextTest {
    @Test
    fun rightToLeftTextIsReorderedPerLine() {
        val latin = "Midnight City"
        assertSame(latin, visualOrder(latin))
        assertEquals("םולש", visualOrder("שלום"))
        assertEquals("Astronengg - ריש", visualOrder("שיר - Astronengg"))
        assertEquals("(סקימר) ריש", visualOrder("שיר (רמיקס)"))
        assertEquals("ריש 2\nSong", visualOrder("2 שיר\nSong"))
    }
}
