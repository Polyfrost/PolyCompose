package org.polyfrost.compose.composables

import org.polyfrost.compose.layout.*
import org.polyfrost.compose.render.PolyColor

private val NO_INSETS = PolyInsets()

public open class PolyModifier internal constructor(
    internal val key: List<Any?>?,
    internal val block: PolyLayoutStyle.() -> Unit,
) {
    internal constructor(block: PolyLayoutStyle.() -> Unit) : this(null, block)

    public fun then(other: PolyModifier): PolyModifier {
        val a = key
        val b = other.key
        return PolyModifier(if (a != null && b != null) a + b else null) { block(); other.block(this) }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        val k = key ?: return false
        return other is PolyModifier && k == other.key
    }

    override fun hashCode(): Int = key?.hashCode() ?: System.identityHashCode(this)

    public fun applyTo(style: PolyLayoutStyle) {
        val lt = style.layoutType
        val g = style.gap
        style.width = PolySize.Wrap
        style.height = PolySize.Wrap
        style.minWidth = Float.NaN
        style.maxWidth = Float.NaN
        style.minHeight = Float.NaN
        style.maxHeight = Float.NaN
        style.fitContent = false
        style.align = PolyAlign.TopLeft
        style.padding = NO_INSETS
        style.margin = NO_INSETS
        style.positionMode = PolyPositionMode.Relative
        style.x = 0f
        style.y = 0f
        style.background = PolyColor.TRANSPARENT
        style.radius = 0f
        style.borderColor = PolyColor.TRANSPARENT
        style.borderWidth = 0f
        style.clipToBounds = false
        style.alpha = 1f
        style.fade = null
        style.tag = null
        style.layoutType = lt
        style.gap = g
        style.block()
    }

    public companion object : PolyModifier(emptyList(), {})
}

public fun PolyModifier.size(w: Float, h: Float): PolyModifier = op("size", w, h) { width = PolySize.Px(w); height = PolySize.Px(h) }
public fun PolyModifier.size(w: PolySize, h: PolySize): PolyModifier = op("size", w, h) { width = w; height = h }
public fun PolyModifier.width(w: Float): PolyModifier = op("width", w) { width = PolySize.Px(w) }
public fun PolyModifier.width(w: PolySize): PolyModifier = op("width", w) { width = w }
public fun PolyModifier.height(h: Float): PolyModifier = op("height", h) { height = PolySize.Px(h) }
public fun PolyModifier.height(h: PolySize): PolyModifier = op("height", h) { height = h }
public fun PolyModifier.widthIn(min: Float = Float.NaN, max: Float = Float.NaN): PolyModifier = op("widthIn", min, max) { minWidth = min; maxWidth = max }
public fun PolyModifier.heightIn(min: Float = Float.NaN, max: Float = Float.NaN): PolyModifier = op("heightIn", min, max) { minHeight = min; maxHeight = max }
public fun PolyModifier.fitContent(): PolyModifier = op("fitContent") { fitContent = true }
public fun PolyModifier.fillWidth(): PolyModifier = op("fillWidth") { width = PolySize.Fill }
public fun PolyModifier.fillHeight(): PolyModifier = op("fillHeight") { height = PolySize.Fill }
public fun PolyModifier.fill(): PolyModifier = op("fill") { width = PolySize.Fill; height = PolySize.Fill }
public fun PolyModifier.wrapWidth(): PolyModifier = op("wrapWidth") { width = PolySize.Wrap }
public fun PolyModifier.wrapHeight(): PolyModifier = op("wrapHeight") { height = PolySize.Wrap }

public fun PolyModifier.padding(all: Float): PolyModifier = op("padding", all) { padding = PolyInsets(all) }
public fun PolyModifier.padding(horizontal: Float = 0f, vertical: Float = 0f): PolyModifier = op("padding", horizontal, vertical) { padding = PolyInsets(horizontal, vertical) }
public fun PolyModifier.padding(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f): PolyModifier = op("padding", left, top, right, bottom) { padding = PolyInsets(left, top, right, bottom) }
public fun PolyModifier.padding(insets: PolyInsets): PolyModifier = op("padding", insets) { padding = insets }

public fun PolyModifier.margin(all: Float): PolyModifier = op("margin", all) { margin = PolyInsets(all) }
public fun PolyModifier.margin(horizontal: Float = 0f, vertical: Float = 0f): PolyModifier = op("margin", horizontal, vertical) { margin = PolyInsets(horizontal, vertical) }
public fun PolyModifier.margin(left: Float = 0f, top: Float = 0f, right: Float = 0f, bottom: Float = 0f): PolyModifier = op("margin", left, top, right, bottom) { margin = PolyInsets(left, top, right, bottom) }
public fun PolyModifier.margin(insets: PolyInsets): PolyModifier = op("margin", insets) { margin = insets }

public fun PolyModifier.background(color: PolyColor, radius: Float = 0f): PolyModifier = op("background", color, radius) { background = color; this.radius = radius }
public fun PolyModifier.border(color: PolyColor, width: Float = 1f, radius: Float = 0f): PolyModifier = op("border", color, width, radius) { borderColor = color; borderWidth = width; if (radius > 0f) this.radius = radius }
public fun PolyModifier.radius(r: Float): PolyModifier = op("radius", r) { radius = r }
public fun PolyModifier.clip(radius: Float = 0f): PolyModifier = op("clip", radius) { clipToBounds = true; if (radius > 0f) this.radius = radius }
public fun PolyModifier.alpha(a: Float): PolyModifier = op("alpha", a) { alpha = a }
public fun PolyModifier.fade(alpha: (() -> Float)?): PolyModifier = op("fade", alpha) { fade = alpha }
public fun PolyModifier.align(a: PolyAlign): PolyModifier = op("align", a) { align = a }

public fun PolyModifier.tag(value: Any?): PolyModifier = op("tag", value) { tag = value }

public fun PolyModifier.absoluteAt(x: Float, y: Float): PolyModifier = op("absoluteAt", x, y) {
    positionMode = PolyPositionMode.Absolute
    this.x = x
    this.y = y
}

private fun PolyModifier.op(vararg key: Any?, block: PolyLayoutStyle.() -> Unit): PolyModifier =
    then(PolyModifier(listOf(key.asList()), block))
