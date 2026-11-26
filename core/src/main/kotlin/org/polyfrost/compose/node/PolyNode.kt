package org.polyfrost.compose.node

import org.polyfrost.compose.layout.PolyLayoutStyle
import org.polyfrost.compose.render.CapturingRenderer
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.RenderContext

public open class PolyNode {
    public val children: MutableList<PolyNode> = mutableListOf<PolyNode>()
    public val style: PolyLayoutStyle = PolyLayoutStyle()
    public var parent: PolyNode? = null
    public var x: Float = 0f
    public var y: Float = 0f
    public var width: Float = 0f
    public var height: Float = 0f
    public var contentScale: Float = 1f

    internal var intrinsicWidth: Float = -1f
    internal var intrinsicHeight: Float = -1f
    internal var contentRevision: Int = 0
    internal open val hasLiveContent: Boolean get() = liveMeasure

    internal var tracker: ChangeTracker? = null
    internal open val tracksChanges: Boolean get() = javaClass === PolyNode::class.java || reportsLayoutChanges
    private var countedUntracked = false

    protected open val reportsLayoutChanges: Boolean get() = false

    protected open val liveMeasure: Boolean get() = false

    init {
        style.owner = this
    }

    protected fun notifyLayoutChanged(): Unit = layoutChanged()

    protected fun notifyLiveMeasureChanged(): Unit = liveChanged()

    protected fun notifyContentChanged() {
        contentRevision++
        val t = tracker ?: return
        t.paint++
    }

    internal fun layoutChanged() {
        val t = tracker ?: return
        t.layout++
    }

    internal fun liveChanged() {
        val t = tracker ?: return
        t.layout++
        t.live.remove(this)
        if (hasLiveContent) t.live.add(this)
    }

    internal fun attach(t: ChangeTracker) {
        if (tracker === t) return
        if (tracker != null) detach()
        tracker = t
        countedUntracked = !tracksChanges
        if (countedUntracked) t.untracked++
        if (hasLiveContent) t.live.add(this)
        for (i in children.indices) children[i].attach(t)
    }

    internal fun detach() {
        val t = tracker ?: return
        tracker = null
        if (countedUntracked) t.untracked--
        countedUntracked = false
        t.live.remove(this)
        if (style.pending) {
            style.pending = false
            t.pendingStyles.remove(style)
        }
        for (i in children.indices) children[i].detach()
    }

    private var decoration: Decoration? = null
    private var liveScratch: RenderContext? = null

    internal fun liveContext(renderer: PolyRenderer): RenderContext {
        val cached = liveScratch
        if (cached != null && cached.renderer === renderer) return cached
        return RenderContext(renderer).also { liveScratch = it }
    }

    public open fun measureContent() {}

    protected open fun renderContent(ctx: RenderContext) {}

    public open fun render(ctx: RenderContext) {
        val s = style
        decorate(ctx)

        val clip = s.clipToBounds
        if (clip) {
            ctx.save()
            val r = s.radius
            if (r > 0f) ctx.clipRRect(x, y, width, height, r) else ctx.clipRect(x, y, width, height)
        }
        val scaled = contentScale != 1f
        if (scaled) {
            val px = x + s.padding.left
            val py = y + s.padding.top
            ctx.save()
            ctx.translate(px, py)
            ctx.scale(contentScale, contentScale)
            ctx.translate(-px, -py)
        }
        renderContent(ctx)
        val kids = children
        for (i in kids.indices) {
            val child = kids[i]
            val cs = child.style
            val fade = cs.fade
            val staticAlpha = cs.alpha
            if (fade == null) {
                if (staticAlpha >= 1f) {
                    child.render(ctx)
                } else if (staticAlpha > 0f) {
                    ctx.saveLayerAlpha(staticAlpha)
                    child.render(ctx)
                    ctx.restore()
                }
            } else {
                ctx.renderer.live { r ->
                    val a = fade() * staticAlpha
                    if (a > 0f) {
                        val scratch = child.liveContext(r)
                        scratch.saveLayerAlpha(a)
                        child.render(scratch)
                        scratch.restore()
                    }
                }
            }
        }
        if (scaled) ctx.restore()
        if (clip) ctx.restore()
    }

    private fun decorate(ctx: RenderContext) {
        val s = style
        val bg = s.background
        val border = s.borderColor
        val stroke = s.borderWidth
        if (bg.chroma || stroke > 0f && border.chroma) {
            val px = x; val py = y; val w = width; val h = height; val r = s.radius
            ctx.renderer.live { drawDecoration(it, px, py, w, h, r, bg.argb, if (stroke > 0f) border.argb else 0, stroke) }
            return
        }
        val bgArgb = bg.argb
        val borderArgb = if (stroke > 0f) border.argb else 0
        if (bgArgb ushr 24 == 0 && borderArgb ushr 24 == 0) return
        val cr = if (ctx.cacheNodes) ctx.renderer as? CapturingRenderer else null
        if (cr == null) return drawDecoration(ctx.renderer, x, y, width, height, s.radius, bgArgb, borderArgb, stroke)
        val d = decoration
        if (d != null && d.x == x && d.y == y && d.w == width && d.h == height && d.radius == s.radius && d.bg == bgArgb &&
            d.border == borderArgb && d.stroke == stroke && cr.sameState(d.key) && cr.valid(d.captured)
        ) {
            cr.append(d.captured)
            return
        }
        val key = cr.stateKey()
        val mark = cr.mark()
        drawDecoration(ctx.renderer, x, y, width, height, s.radius, bgArgb, borderArgb, stroke)
        decoration = Decoration(x, y, width, height, s.radius, bgArgb, borderArgb, stroke, key, cr.since(mark))
    }

    private class Decoration(
        val x: Float, val y: Float, val w: Float, val h: Float, val radius: Float,
        val bg: Int, val border: Int, val stroke: Float, val key: Any, val captured: Any,
    )
}

internal class ChangeTracker {
    var layout: Long = 0L
    var paint: Long = 0L
    var untracked: Int = 0
    val live: ArrayList<PolyNode> = ArrayList()
    val pendingStyles: ArrayList<PolyLayoutStyle> = ArrayList()

    fun settle(): Long {
        val styles = pendingStyles
        if (styles.isEmpty()) return layout
        var differs = false
        for (i in styles.indices) {
            val s = styles[i]
            if (!differs && s.layoutDiffers()) differs = true
            s.pending = false
        }
        styles.clear()
        if (differs) layout++
        return layout
    }

    fun clearStyles() {
        val styles = pendingStyles
        for (i in styles.indices) styles[i].pending = false
        styles.clear()
    }
}

private fun drawDecoration(renderer: PolyRenderer, x: Float, y: Float, w: Float, h: Float, radius: Float, bg: Int, border: Int, stroke: Float) {
    if (bg ushr 24 != 0) renderer.rect(x, y, w, h, bg, radius)
    if (stroke > 0f && border ushr 24 != 0) renderer.rectStroke(x, y, w, h, border, stroke, radius)
}
