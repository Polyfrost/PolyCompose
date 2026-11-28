package org.polyfrost.compose.node

import org.polyfrost.compose.render.RenderContext

public class CanvasNode : PolyNode() {
    public var onDraw: (RenderContext.(x: Float, y: Float, width: Float, height: Float) -> Unit)? = null

    public var live: Boolean = false

    override val tracksChanges: Boolean get() = true

    override fun renderContent(ctx: RenderContext) {
        val draw = onDraw ?: return
        val pad = style.padding
        val x = x + pad.left
        val y = y + pad.top
        val w = (width - pad.horizontal).coerceAtLeast(0f) / contentScale
        val h = (height - pad.vertical).coerceAtLeast(0f) / contentScale
        if (!live) return draw(ctx, x, y, w, h)
        ctx.renderer.live { draw(liveContext(it), x, y, w, h) }
    }
}
