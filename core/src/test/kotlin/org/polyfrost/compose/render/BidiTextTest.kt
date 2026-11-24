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

    @Test
    fun marksStayWithTheirBaseAndArabicIsJoined() {
        assertEquals("\u05D1\u05D0\u05B8", visualOrder("\u05D0\u05B8\u05D1"))
        assertEquals("\uFE96\uFEF4\uFE91", visualOrder("بيت"))
        assertEquals("\uFEFB", visualOrder("لا"))
    }

    @Test
    fun leadingFormattingCodesKeepBidi() {
        assertEquals("§l§oםולש", mcVisualOrder("§l§oשלום", false))
        assertEquals("§lשלום §rhi", mcVisualOrder("§lשלום §rhi", false))
    }
}
