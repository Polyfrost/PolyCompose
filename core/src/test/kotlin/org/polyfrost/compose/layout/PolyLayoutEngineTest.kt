package org.polyfrost.compose.layout

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.polyfrost.compose.node.PolyNode

class PolyLayoutEngineTest {
    private fun node(w: PolySize, h: PolySize, vararg kids: PolyNode): PolyNode = PolyNode().apply {
        style.width = w
        style.height = h
        for (k in kids) {
            children.add(k)
            k.parent = this
        }
    }

    @Test
    fun defaultBoundsDoNotReportLayoutChange() {
        assertFalse(PolyLayoutStyle().layoutDiffers())
    }

    @Test
    fun fitContentScalesOverflowInFixedBox() {
        val box = node(PolySize.Px(100f), PolySize.Px(100f), node(PolySize.Px(200f), PolySize.Px(50f)))
        box.style.fitContent = true
        PolyLayoutEngine.layout(box, 500f, 500f)
        assertEquals(100f, box.width)
        assertEquals(0.5f, box.contentScale)
    }

    @Test
    fun fitContentScalesWrapClampedByMax() {
        val box = node(PolySize.Wrap, PolySize.Wrap, node(PolySize.Px(200f), PolySize.Px(50f)))
        box.style.maxWidth = 100f
        box.style.fitContent = true
        PolyLayoutEngine.layout(box, 500f, 500f)
        assertEquals(100f, box.width)
        assertEquals(50f, box.height)
        assertEquals(0.5f, box.contentScale)
    }

    @Test
    fun fitContentKeepsScaleWhenContentFits() {
        val box = node(PolySize.Px(100f), PolySize.Px(100f), node(PolySize.Px(40f), PolySize.Px(40f)))
        box.style.fitContent = true
        PolyLayoutEngine.layout(box, 500f, 500f)
        assertEquals(1f, box.contentScale)
    }
}
