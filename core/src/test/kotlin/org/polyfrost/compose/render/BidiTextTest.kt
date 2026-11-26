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
        assertEquals("cba", visualOrder("\u202Eabc\u202C"))
        assertEquals("(abc", visualOrder("\u2067abc)\u2069"))
    }

    @Test
    fun marksStayWithTheirBaseAndArabicIsJoined() {
        assertEquals("\u05D1\u05D0\u05B8", visualOrder("\u05D0\u05B8\u05D1"))
        assertEquals("\uFE96\uFEF4\uFE91", visualOrder("بيت"))
        assertEquals("\uFEFB", visualOrder("لا"))
    }

    @Test
    fun laidOutTextIsDrawnAsGiven() {
        assertEquals("םולש (abc)", visualOrder("\u202Dםולש (abc)\u202C"))
        assertEquals(visualOrder("بيت"), visualOrder("\u202D" + visualOrder("بيت") + "\u202C"))
    }

    @Test
    fun defaultRightToLeftParagraph() {
        assertEquals("abc!", visualOrder("abc!", true))
        assertEquals("?!", visualOrder("!?", true))
    }

    @Test
    fun minecraftFormattingFollowsItsCharacters() {
        assertSame("§cabc", mcVisualOrder("§cabc", false))
        assertEquals("§r§c§lםול§r§cש§r§c§l", mcVisualOrder("§cש§lלום", false))
        assertEquals("§r§aםולש§r§a\n§bok", mcVisualOrder("§aשלום\n§bok", false))
        assertEquals("ab§c", mcVisualOrder("ab§c", true))
        assertEquals("םו§לש", mcVisualOrder("של§ום", true))
    }
}
