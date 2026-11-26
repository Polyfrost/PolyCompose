package org.polyfrost.compose.node

import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.RenderContext

public class ImageNode(
    private var image: PolyBitmap
) : PolyNode() {
    override val tracksChanges: Boolean get() = true

    internal fun setImage(value: PolyBitmap) {
        image = value
        val w = intrinsicWidth
        val h = intrinsicHeight
        remeasure()
        if (intrinsicWidth != w || intrinsicHeight != h) layoutChanged()
    }

    public fun remeasure() {
        intrinsicWidth = image.width.toFloat()
        intrinsicHeight = image.height.toFloat()
    }

    override fun renderContent(ctx: RenderContext) {
        val pad = style.padding
        val natural = style.fitContent
        val w = if (natural) intrinsicWidth else (width - pad.horizontal).coerceAtLeast(0f)
        val h = if (natural) intrinsicHeight else (height - pad.vertical).coerceAtLeast(0f)
        ctx.image(image, x + pad.left, y + pad.top, w, h)
    }
}
