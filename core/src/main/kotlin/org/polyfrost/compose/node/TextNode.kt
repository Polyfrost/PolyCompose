package org.polyfrost.compose.node

import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyFont
import org.polyfrost.compose.render.RenderContext

public class TextNode : PolyNode() {
    public var text: String = ""
        set(value) {
            if (field != value) {
                field = value
                measureDirty = true
                layoutChanged()
            }
        }
    public var color: PolyColor = PolyColor.WHITE
    public var fontSize: Float = 12f
        set(value) {
            if (field != value) {
                field = value
                measureDirty = true
                layoutChanged()
            }
        }
    public var shadow: Boolean = false
    public var shadowColor: PolyColor = PolyColor(0x80000000.toInt())
    public var shadowOffset: Float = 1f

    public var fontName: String? = null
        set(value) {
            if (field != value) {
                field = value
                measureDirty = true
                layoutChanged()
            }
        }

    private var measureDirty = true
    private var measuredFonts = -1

    override val tracksChanges: Boolean get() = true

    private var cachedFont: PolyFont? = null

    private fun font(): PolyFont {
        val name = fontName ?: FontManager.DEFAULT_KEY
        val f = cachedFont
        if (f != null && f.name == name && f.size == fontSize) return f
        return PolyFont(name, fontSize).also { cachedFont = it }
    }

    override fun measureContent() {
        if (measureDirty || measuredFonts != FontManager.revision) remeasure()
    }

    public fun remeasure() {
        measureDirty = false
        measuredFonts = FontManager.revision
        val font = font()
        intrinsicWidth = font.measureTextWidth(text)
        intrinsicHeight = font.metrics.let { it.descent - it.ascent }
    }

    override fun renderContent(ctx: RenderContext) {
        val font = font()
        val pad = style.padding
        val px = x + pad.left
        val baseline = y + pad.top - font.metrics.ascent
        val c = color
        val sc = shadowColor
        val sh = shadow
        if (c.chroma || sh && sc.chroma) {
            val t = text
            val off = shadowOffset
            ctx.renderer.live { r ->
                if (sh && sc.alpha != 0 && t.isNotEmpty()) r.text(t, px + off, baseline + off, sc.argb, font)
                if (c.alpha != 0 && t.isNotEmpty()) r.text(t, px, baseline, c.argb, font)
            }
            return
        }
        if (sh) ctx.text(text, px + shadowOffset, baseline + shadowOffset, sc, font)
        ctx.text(text, px, baseline, c, font)
    }
}
