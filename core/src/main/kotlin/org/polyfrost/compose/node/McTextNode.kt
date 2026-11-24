package org.polyfrost.compose.node

import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyColor
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.RenderContext
import java.util.function.IntSupplier

private const val LIVE_PAD = 4f

public class McTextNode : PolyNode() {
    private var currentText: String = ""
    public var text: String
        get() = currentText
        set(value) {
            if (currentText != value) {
                currentText = value
                markMeasureDirty()
            }
        }
    public var color: PolyColor = PolyColor.WHITE
    public var shadow: Boolean = true
    public var scale: Float = 1f
        set(value) {
            if (field != value) {
                field = value
                markMeasureDirty()
            }
        }
    public var outlineColor: PolyColor? = null
    public var textProvider: (() -> String)? = null
        set(value) {
            val wasLive = field != null
            field = value
            if (wasLive != (value != null)) liveChanged()
        }
    public var literal: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                markMeasureDirty()
            }
        }
    private var measuredFonts = -1
    private var measureDirty = true

    override val hasLiveContent: Boolean get() = textProvider != null
    override val tracksChanges: Boolean get() = true

    private fun markMeasureDirty() {
        measureDirty = true
        if (textProvider == null) layoutChanged()
    }

    override fun measureContent() {
        val provider = textProvider
        if (provider != null) {
            val next = provider()
            if (currentText != next) {
                currentText = next
                measureDirty = true
            }
        }
        if (!measureDirty && measuredFonts == FontManager.revision) return
        remeasure()
        contentRevision++
    }

    public fun remeasure() {
        measuredFonts = FontManager.revision
        measureDirty = false
        val t = currentText
        if (t.indexOf('\n') < 0 && t.indexOf('\r') < 0) {
            intrinsicWidth = McFontQueue.measureTextWidth(t, scale, literal)
            intrinsicHeight = McFontQueue.measureBlockHeight(1, scale)
            return
        }
        val lines = t.lines()
        intrinsicWidth = lines.maxOf { McFontQueue.measureTextWidth(it, scale, literal) }
        intrinsicHeight = McFontQueue.measureBlockHeight(lines.size, scale)
    }

    override fun renderContent(ctx: RenderContext) {
        val c = color
        val outline = outlineColor
        val lit = literal
        val insets = style.padding
        val x = x + insets.left
        val y = y + insets.top
        if (c.chroma && outline == null) {
            ctx.mcTextLive(text, x, y, IntSupplier { c.argb }, shadow, scale, lit)
        } else if (outline != null && (c.chroma || outline.chroma)) {
            val t = text
            if (t.isEmpty()) return
            val px = x; val py = y; val sh = shadow; val sc = scale
            val pad = LIVE_PAD * sc
            ctx.renderer.live(
                px - pad, py - pad, intrinsicWidth.coerceAtLeast(0f) + pad * 2f, intrinsicHeight.coerceAtLeast(0f) + pad * 2f,
                IntSupplier { c.argb * 31 + outline.argb },
            ) { draw(it, t, px, py, c, outline, sh, sc, lit) }
        } else {
            draw(ctx.renderer, text, x, y, c, outline, shadow, scale, lit)
        }
    }

    private fun draw(renderer: PolyRenderer, text: String, x: Float, y: Float, color: PolyColor, outline: PolyColor?, shadow: Boolean, scale: Float, literal: Boolean) {
        if (text.isEmpty()) return
        if (outline != null) renderer.mcTextOutline(text, x, y, color.argb, outline.argb, scale, literal)
        else renderer.mcText(text, x, y, color.argb, shadow, scale, literal)
    }
}
