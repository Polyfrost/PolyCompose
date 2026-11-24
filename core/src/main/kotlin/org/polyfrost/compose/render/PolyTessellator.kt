package org.polyfrost.compose.render

import org.polyfrost.compose.mc.McGlyph
import org.polyfrost.compose.mc.McGlyphs
import java.util.Arrays
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

public abstract class PolyTessellator : PolyRenderer {
    // explicit API mode demands visibility on members of these protected classes, yet the
    // redundancy check still flags it
    @Suppress("REDUNDANT_VISIBILITY_MODIFIER")
    protected class State(
        @JvmField public var sx: Float, @JvmField public var sy: Float, @JvmField public var tx: Float, @JvmField public var ty: Float,
        @JvmField public var alpha: Float, @JvmField public var clip: FloatArray?, @JvmField public var rclip: RClip?, @JvmField public var mask: Mask? = null,
        @JvmField public var glyphScale: Float = 1f, @JvmField public var rot: FloatArray? = null, @JvmField public var hole: RClip? = null,
    ) {
        @JvmField public var proj: FloatArray? = null

        @JvmField internal var region: Region? = null

        @JvmField internal var pooled: Boolean = false

        public fun copy(): State = State(sx, sy, tx, ty, alpha, clip, rclip, mask, glyphScale, rot, hole).also { it.proj = proj; it.region = region }

        internal fun setFrom(o: State): State {
            sx = o.sx; sy = o.sy; tx = o.tx; ty = o.ty; alpha = o.alpha
            clip = o.clip; rclip = o.rclip; mask = o.mask; glyphScale = o.glyphScale; rot = o.rot; hole = o.hole
            proj = o.proj; region = o.region
            return this
        }

        internal fun release() {
            clip = null; rclip = null; mask = null; rot = null; hole = null; proj = null; region = null
        }
    }

    internal class RegionPiece(
        val cover: Array<FloatArray>, val invLen: FloatArray, val inner: Array<FloatArray>, val outer: Array<FloatArray>,
        val l: Float, val t: Float, val r: Float, val b: Float,
    )

    internal class Region(
        val pieces: Array<RegionPiece>, val box: FloatArray?, val bounds: FloatArray, val bottoms: FloatArray,
        val src: FloatArray, val sizes: IntArray, val evenOdd: Boolean, val parent: Region?,
    )

    @Suppress("REDUNDANT_VISIBILITY_MODIFIER")
    protected class Mask(public val fade: PolyFade, public val parent: Mask?)

    @Suppress("REDUNDANT_VISIBILITY_MODIFIER")
    protected class RClip(
        public val edge: FloatArray, public val inner: FloatArray, public val outer: FloatArray, public val parent: RClip?, public val safe: FloatArray, public val box: FloatArray,
        public val rrect: Boolean = true,
    ) {
        public val invLen: FloatArray = FloatArray(edge.size / 2) { e ->
            val n = edge.size / 2
            1f / max(length(edge[(e + 1) % n * 2] - edge[e * 2], edge[(e + 1) % n * 2 + 1] - edge[e * 2 + 1]), 1e-4f)
        }
    }

    protected var state: State = State(1f, 1f, 0f, 0f, 1f, null, null)
    private val stack = ArrayDeque<State>()

    private class Layer(
        val depth: Int, val alpha: Float, var start: Int, val box: Int, val effect: EffectBox? = null, val isolated: Boolean = false,
        val composite: Int = PolyRenderer.BLEND_SRC_OVER, val resume: Int = -1,
    ) {
        var mark: Any? = null
        var blended = false
    }

    private class EffectBox(val chain: PolyEffectChain, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val radius: Float, val scale: Float)

    private val layers = ArrayList<Layer>()
    private var boxes = FloatArray(BOX * 16)
    private var boxCount = 0

    private var verts = FloatArray(4096)
    private var colors = IntArray(1024)
    private var count = 0
    private var batchTexture: Any = whiteTexture()
    private var batchPremul = false
    private var batchNearest = false
    private var whiteOnly = true
    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE

    protected abstract fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray)

    protected abstract fun bitmapTexture(bitmap: PolyBitmap): Any

    protected open val cpuOnly: Boolean get() = false

    protected open val sharedAtlas: Boolean get() = true

    private fun whiteTexture(): Any = if (sharedAtlas) PolyGlyphPage.pages[0] else PolyGlyphPage.cpuWhite()

    private fun atlasBitmap(b: PolyBitmap): PolyGlyphPage.Glyph? = if (sharedAtlas) PolyGlyphPage.bitmap(b) else null

    protected open val layered: Boolean get() = false

    protected open fun layerMark(): Any = Unit

    protected open fun layer(mark: Any, alpha: Float) {}

    protected open fun layer(mark: Any, alpha: Float, chain: PolyEffectChain, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, scale: Float): Unit =
        layer(mark, alpha)

    protected open fun fade(mark: Any, alpha: Float): Boolean = false

    private var drawBlend = PolyRenderer.BLEND_SRC_OVER
    private var wantBlend = PolyRenderer.BLEND_SRC_OVER
    private var discardFrom = Int.MAX_VALUE

    private val discarding: Boolean get() = stack.size >= discardFrom

    protected val blendMode: Int get() = drawBlend

    protected open fun blendSupported(mode: Int): Boolean = mode == PolyRenderer.BLEND_SRC_OVER

    protected open fun layerBlendSupported(mode: Int): Boolean = mode == PolyRenderer.BLEND_SRC_OVER

    private var antiAliased = true

    override fun antiAlias(enabled: Boolean) {
        antiAliased = enabled
    }

    override fun blend(mode: Int): Boolean {
        val supported = blendSupported(mode)
        wantBlend = if (supported) mode else PolyRenderer.BLEND_SRC_OVER
        return supported
    }

    private fun applyBlend() {
        val m = wantBlend
        if (m == drawBlend) return
        flush()
        clipImpure()
        drawBlend = m
        if (m != PolyRenderer.BLEND_SRC_OVER) layers.lastOrNull()?.blended = true
    }

    private fun invisible(color: Int): Boolean = color ushr 24 == 0 && (discarding || zeroSourceKeepsDst(wantBlend))

    private class ClipCtx(val clip: FloatArray, val inner: FloatArray?, val rclip: RClip?, val mask: Mask?, val hole: RClip?)

    private class BatchKey(val texture: Any, val premultiplied: Boolean, val nearest: Boolean)

    private class ClipLog(val entry: FloatArray) {
        val inner = IdentityHashMap<FloatArray, FloatArray>()
        val keys = ArrayList<BatchKey>()
        var quads = FloatArray(256)
        var colors = IntArray(64)
        var ctxs = arrayOfNulls<ClipCtx>(16)
        var quadCount = 0
        var events = IntArray(64)
        var size = 0
        var last: ClipCtx? = null

        fun event(e: Int) {
            if (size == events.size) events = events.copyOf(size * 2)
            events[size++] = e
        }

        fun begin(texture: Any, premultiplied: Boolean, nearest: Boolean) {
            val k = keys.lastOrNull()
            if (k == null || k.texture !== texture || k.premultiplied != premultiplied || k.nearest != nearest) keys += BatchKey(texture, premultiplied, nearest)
            event(CLIP_BEGIN - keys.lastIndex)
        }
    }

    private val clipLogs = ArrayList<ClipLog?>()

    internal fun clipLogPush(open: Boolean): Any? {
        val clip = state.clip
        val log = if (open && clip != null && state.rot == null) ClipLog(clip) else null
        clipLogs += log
        return log
    }

    internal fun clipLogPop() {
        if (clipLogs.isNotEmpty()) clipLogs.removeAt(clipLogs.lastIndex)
    }

    internal fun clipLogStart(): Int {
        val log = clipLogs.lastOrNull() ?: return -1
        log.begin(batchTexture, batchPremul, batchNearest)
        return log.size - 1
    }

    internal fun clipLogEnd(): Int = clipLogs.lastOrNull()?.size ?: -1

    internal fun clipLogReady(log: Any, captured: Any, from: Int, to: Int): Boolean {
        val l = log as ClipLog
        if (from < 0 || to < from || !recuttable(captured)) return false
        for (k in from until to) if (l.events[k] == CLIP_IMPURE) return false
        return true
    }

    private fun clipImpure() {
        clipLogs.lastOrNull()?.event(CLIP_IMPURE)
    }

    private fun logQuad(log: ClipLog, rotated: Boolean) {
        val s = state
        val clip = s.clip
        if (rotated || clip == null || s.region != null) return log.event(CLIP_IMPURE)
        var ctx = log.last
        if (ctx == null || ctx.clip !== clip || ctx.rclip !== s.rclip || ctx.mask !== s.mask || ctx.hole !== s.hole) {
            val inner = if (clip === log.entry) null else log.inner[clip] ?: return log.event(CLIP_IMPURE)
            ctx = ClipCtx(clip, inner, s.rclip, s.mask, s.hole)
            log.last = ctx
        }
        val n = log.quadCount
        if (n == log.ctxs.size) {
            log.ctxs = log.ctxs.copyOf(n * 2)
            log.colors = log.colors.copyOf(n * 8)
            log.quads = log.quads.copyOf(n * 32)
        }
        val q = log.quads
        for (i in 0 until 4) {
            val o = n * 16 + i * 4
            q[o] = qx[i]; q[o + 1] = qy[i]; q[o + 2] = qu[i]; q[o + 3] = qv[i]
            log.colors[n * 4 + i] = qc[i]
        }
        log.ctxs[n] = ctx
        log.quadCount = n + 1
        log.event(n)
    }

    private fun movedClip(log: ClipLog, clip: FloatArray, inner: FloatArray?, entry: FloatArray, dx: Float, dy: Float): FloatArray {
        if (clip === log.entry || inner == null) return entry
        return floatArrayOf(max(inner[0] + dx, entry[0]), max(inner[1] + dy, entry[1]), min(inner[2] + dx, entry[2]), min(inner[3] + dy, entry[3]))
    }

    internal fun clipShiftedState(log: Any, saved: Any, dx: Float, dy: Float): Any? {
        val l = log as ClipLog
        val s = saved as State
        val entry = state.clip
        val clip = s.clip
        if (s.rot != null || entry == null || clip == null) return null
        val inner = if (clip === l.entry) null else l.inner[clip] ?: return null
        return State(
            s.sx, s.sy, s.tx + dx, s.ty + dy, s.alpha, movedClip(l, clip, inner, entry, dx, dy), s.rclip?.let { shiftClip(it, dx, dy) },
            s.mask?.let { shiftMask(it, dx, dy) }, s.glyphScale, null, s.hole?.let { shiftClip(it, dx, dy) },
        ).also { it.region = s.region?.let { g -> shiftRegion(g, dx, dy) } }
    }

    internal fun clipRecut(log: Any, captured: Any, from: Int, to: Int, dx: Float, dy: Float) {
        val l = log as ClipLog
        val entry = state.clip ?: return
        beginRecut(captured)
        var ctx: ClipCtx? = null
        var box = entry
        var rclip: RClip? = null
        var mask: Mask? = null
        var hole: RClip? = null
        val q = l.quads
        for (k in from until to) {
            val e = l.events[k]
            if (e >= 0) {
                val cx = l.ctxs[e]!!
                if (cx !== ctx) {
                    ctx = cx
                    box = movedClip(l, cx.clip, cx.inner, entry, dx, dy)
                    rclip = cx.rclip?.let { shiftClip(it, dx, dy) }
                    mask = cx.mask?.let { shiftMask(it, dx, dy) }
                    hole = cx.hole?.let { shiftClip(it, dx, dy) }
                }
                for (i in 0 until 4) {
                    val o = e * 16 + i * 4
                    qx[i] = q[o] + dx; qy[i] = q[o + 1] + dy; qu[i] = q[o + 2]; qv[i] = q[o + 3]
                    qc[i] = l.colors[e * 4 + i]
                }
                cutQuad(box, rclip, mask, hole, null)
            } else if (e == CLIP_FLUSH) {
                flush()
            } else if (e == CLIP_DRAW) {
                beginDraw()
            } else if (e <= CLIP_BEGIN) {
                val key = l.keys[CLIP_BEGIN - e]
                begin(key.texture, key.premultiplied, key.nearest)
            }
        }
    }

    protected open fun recuttable(captured: Any): Boolean = false

    protected open fun beginRecut(captured: Any) {}

    protected fun beginDraw() {
        applyBlend()
        clipLogs.lastOrNull()?.event(CLIP_DRAW)
        if ((boxCount + 1) * BOX > boxes.size) boxes = boxes.copyOf(boxes.size * 2)
        val o = boxCount++ * BOX
        boxes[o] = Float.MAX_VALUE; boxes[o + 1] = Float.MAX_VALUE; boxes[o + 2] = -Float.MAX_VALUE; boxes[o + 3] = -Float.MAX_VALUE
        boxes[o + BOX_SELF] = 0f
        val blended = drawBlend != PolyRenderer.BLEND_SRC_OVER
        boxes[o + BOX_BLEND] = if (blended) 1f else 0f
        if (blended) layers.lastOrNull()?.blended = true
    }

    private fun selfOverlapping() {
        if (boxCount > 0) boxes[boxCount * BOX - BOX + BOX_SELF] = 1f
    }

    protected fun touch(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (boxCount == 0) return
        val o = boxCount * BOX - BOX
        if (x0 < boxes[o]) boxes[o] = x0
        if (y0 < boxes[o + 1]) boxes[o + 1] = y0
        if (x1 > boxes[o + 2]) boxes[o + 2] = x1
        if (y1 > boxes[o + 3]) boxes[o + 3] = y1
    }

    protected fun touchAll(): Unit = touch(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY)

    protected val drawBoxMark: Int get() = boxCount

    protected fun drawBoxesSince(mark: Int): FloatArray = boxes.copyOfRange(min(mark, boxCount) * BOX, boxCount * BOX)

    protected fun appendDrawBoxes(b: FloatArray) {
        while (boxes.size < boxCount * BOX + b.size) boxes = boxes.copyOf(boxes.size * 2)
        System.arraycopy(b, 0, boxes, boxCount * BOX, b.size)
        boxCount += b.size / BOX
        for (i in BOX_BLEND until b.size step BOX) {
            if (b[i] == 0f) continue
            layers.lastOrNull()?.blended = true
            return
        }
    }

    private fun overlapping(from: Int): Boolean {
        if (boxCount - from > MAX_FADE_DRAWS) return true
        for (i in from until boxCount) {
            val a = i * BOX
            if (boxes[a] > boxes[a + 2]) continue
            if (boxes[a + BOX_SELF] != 0f) return true
            for (j in i + 1 until boxCount) {
                val b = j * BOX
                if (boxes[b] > boxes[b + 2]) continue
                if (boxes[a] < boxes[b + 2] && boxes[b] < boxes[a + 2] && boxes[a + 1] < boxes[b + 3] && boxes[b + 1] < boxes[a + 3]) return true
            }
        }
        return false
    }

    private fun collapseBoxes(from: Int, blended: Boolean) {
        if (boxCount <= from) return
        val o = from * BOX
        for (i in from + 1 until boxCount) {
            val k = i * BOX
            boxes[o] = min(boxes[o], boxes[k]); boxes[o + 1] = min(boxes[o + 1], boxes[k + 1])
            boxes[o + 2] = max(boxes[o + 2], boxes[k + 2]); boxes[o + 3] = max(boxes[o + 3], boxes[k + 3])
        }
        boxes[o + BOX_SELF] = 0f
        boxes[o + BOX_BLEND] = if (blended) 1f else 0f
        boxCount = from + 1
    }

    private fun culled(x0: Float, y0: Float, x1: Float, y1: Float, padPx: Float): Boolean {
        val s = state
        if (s.clip == null) return false
        val ax = x0 * s.sx + s.tx; val bx = x1 * s.sx + s.tx
        val ay = y0 * s.sy + s.ty; val by = y1 * s.sy + s.ty
        return culledPx(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by), padPx)
    }

    private fun lineCulled(top: Float, bottom: Float, padPx: Float): Boolean {
        val s = state
        val c = s.clip ?: return false
        if (s.rot != null || s.proj != null || clipLogs.lastOrNull() != null) return false
        val a = top * s.sy + s.ty; val b = bottom * s.sy + s.ty
        val pad = padPx + CULL_PAD
        return max(a, b) + pad <= c[1] || min(a, b) - pad >= c[3]
    }

    private fun culledPx(l: Float, t: Float, r: Float, b: Float, padPx: Float): Boolean {
        val s = state
        val c = s.clip ?: return false
        if (s.rot != null || s.proj != null || clipLogs.lastOrNull() != null) return false
        return r + padPx <= c[0] || l - padPx >= c[2] || b + padPx <= c[1] || t - padPx >= c[3]
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float) {
        beginDraw()
        if (w <= 0f || h <= 0f || culled(x, y, x + w, y + h, CULL_PAD)) return
        clippedFill(x, y, w, h, radius) { fillPolygonPx(it, it.size / 2, color, convex = true) }
    }

    override fun rectWithHole(x: Float, y: Float, w: Float, h: Float, holeX: Float, holeY: Float, holeW: Float, holeH: Float, holeRadius: Float, color: Int) {
        beginDraw()
        if (w <= 0f || h <= 0f || culled(x, y, x + w, y + h, CULL_PAD)) return
        if (holeW <= 0f || holeH <= 0f || holeX >= x + w || holeY >= y + h || holeX + holeW <= x || holeY + holeH <= y) return rect(x, y, w, h, color, 0f)
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        val m = 2f / scaleAvg()
        val l = min(x, holeX - m); val t = min(y, holeY - m)
        val r = max(x + w, holeX + holeW + m); val b = max(y + h, holeY + holeH + m)
        val clipped = l < x || t < y || r > x + w || b > y + h
        if (clipped) { save(); clipRect(x, y, w, h) }
        val outer = quadTmp
        outer[0] = l; outer[1] = t; outer[2] = r; outer[3] = t; outer[4] = r; outer[5] = b; outer[6] = l; outer[7] = b
        transformInPlace(outer)
        val hole = rrectScratch(0, holeX, holeY, holeW, holeH, holeRadius)
        val n = hole.size / 2
        val per = n / 4
        val dir = if (signedArea(outer, 4) > 0f) 1f else -1f
        val oIn = floats(4, 8); val oOut = floats(5, 8)
        val hIn = floats(6, n * 2); val hOut = floats(7, n * 2)
        offsetPolygon(outer, 4, dir, oIn, oOut)
        offsetPolygon(hole, n, -dir, hIn, hOut)
        beginWhite()
        val wuv = PolyGlyphPage.WHITE_UV
        val clear = c and 0x00FFFFFF
        fun q(a: FloatArray, i: Int, ca: Int, b: FloatArray, j: Int, cb: Int, d: FloatArray, k: Int, cd: Int, e: FloatArray, o: Int, ce: Int) = quadVerts(
            a[i * 2], a[i * 2 + 1], wuv, wuv, ca, b[j * 2], b[j * 2 + 1], wuv, wuv, cb,
            d[k * 2], d[k * 2 + 1], wuv, wuv, cd, e[o * 2], e[o * 2 + 1], wuv, wuv, ce,
        )
        for (k in 0 until 4) {
            val base = k * per
            val next = (k + 1) % 4
            for (i in 0 until per - 1) q(oIn, k, c, hIn, base + i, c, hIn, base + i + 1, c, hIn, base + i + 1, c)
            q(oIn, k, c, hIn, base + per - 1, c, hIn, next * per, c, oIn, next, c)
            if (antiAliased) q(oIn, k, c, oOut, k, clear, oOut, next, clear, oIn, next, c)
        }
        if (antiAliased) for (i in 0 until n) {
            val j = (i + 1) % n
            q(hIn, i, c, hOut, i, clear, hOut, j, clear, hIn, j, c)
        }
        if (clipped) restore()
    }

    override fun rectGradient(x: Float, y: Float, w: Float, h: Float, gradient: PolyGradient, radius: Float) {
        beginDraw()
        if (culled(x, y, x + w, y + h, CULL_PAD)) return
        shaded(gradient, x, y, w, h) { clippedFill(x, y, w, h, radius) { fillPolygonPx(it, it.size / 2, -1, convex = true) } }
    }

    // Set while drawing with a gradient, mapping each vertex to where the gradient's texture holds its color
    private var shading: PolyGradient? = null
    private var shadeGlyph: PolyGlyphPage.Glyph? = null
    private var shadeSx = 1f
    private var shadeSy = 1f
    private var shadeTx = 0f
    private var shadeTy = 0f
    private var shadeX = 0f
    private var shadeY = 0f
    private var shadeW = 0f
    private var shadeH = 0f

    private inline fun shaded(gradient: PolyGradient, x: Float, y: Float, w: Float, h: Float, draw: () -> Unit) {
        if (w <= 0f || h <= 0f) return
        val fill = gradient as? PolyImageFill
        val k = deviceScale()
        val tex = fill?.bitmap ?: if (gradient.linear) ramp(gradient) else boxRaster(gradient, w / h, w * k, h * k)
        if (cpuOnly) return
        // The atlas packs other textures next to the ramp, so a ramp read past its ends needs a texture of its own to clamp
        val g = if (fill != null || gradient.linear && !inRamp(gradient, x, y, w, h)) null else atlasBitmap(tex)
        begin(g?.page ?: bitmapTexture(tex), false)
        val s = state
        shadeSx = s.sx; shadeSy = s.sy; shadeTx = s.tx; shadeTy = s.ty
        shadeX = x; shadeY = y; shadeW = w; shadeH = h
        shadeGlyph = g
        shading = gradient
        try {
            draw()
        } finally {
            shading = null
            shadeGlyph = null
        }
    }

    private fun shade(gradient: PolyGradient, fx: Float, fy: Float, out: FloatArray) {
        val px = (fx - shadeTx) / shadeSx
        val py = (fy - shadeTy) / shadeSy
        if (gradient is PolyImageFill) {
            out[0] = gradient.u(px, py)
            out[1] = gradient.v(px, py)
            return
        }
        if (gradient.linear) {
            out[0] = (0.5f + 255f * gradient.position(px, py, shadeX, shadeY, shadeW, shadeH)) / 256f
            out[1] = 0.5f
        } else {
            out[0] = (px - shadeX) / shadeW
            out[1] = (py - shadeY) / shadeH
        }
        val g = shadeGlyph ?: return
        out[0] = g.u(out[0]); out[1] = g.v(out[1])
    }

    // Linear positions are affine, so the box's corners bound them; the ramp's one-texel border covers that far past either end
    private fun inRamp(g: PolyGradient, x: Float, y: Float, w: Float, h: Float): Boolean {
        for (i in 0 until 4) {
            val t = g.position(if (i and 1 == 0) x else x + w, if (i < 2) y else y + h, x, y, w, h)
            if (t < -1f / 255f || t > 256f / 255f) return false
        }
        return true
    }

    override fun rectStroke(x: Float, y: Float, w: Float, h: Float, color: Int, strokeWidth: Float, radius: Float): Unit =
        stroke(x, y, w, h, color, strokeWidth, radius, shade = null)

    override fun rectStrokeGradient(x: Float, y: Float, w: Float, h: Float, gradient: PolyGradient, strokeWidth: Float, radius: Float) {
        val sx = state.sx; val sy = state.sy; val tx = state.tx; val ty = state.ty
        stroke(x, y, w, h, gradient.sample(0f), strokeWidth, radius, dense = !gradient.affine, shade = Shader { px, py -> gradient.colorAt((px - tx) / sx, (py - ty) / sy, x, y, w, h) })
    }

    private fun interface Shader {
        fun color(x: Float, y: Float): Int
    }

    private fun interface UvMap {
        fun map(x: Float, y: Float, out: FloatArray)
    }

    private fun stroke(x: Float, y: Float, w: Float, h: Float, color: Int, strokeWidth: Float, radius: Float, dense: Boolean = false, shade: Shader?) {
        beginDraw()
        if (w <= 0f || h <= 0f || strokeWidth <= 0f) return
        val reach = strokeWidth / 2f
        if (culled(x - reach, y - reach, x + w + reach, y + h + reach, CULL_PAD)) return
        val q = strokeWidth / 4f
        val rc = state.rclip
        if (rc != null && state.rot == null && radius >= q && strokeWidth * scaleAvg() >= 2f && state.sx > 0f && state.sy > 0f) {
            val b = boxPx(x, y, w, h, radius, boxTmp)
            val k = rc.box
            var same = true
            for (i in 0 until 6) if (abs(b[i] - k[i]) > 1e-3f) same = false
            if (same) return stroke(x + q, y + q, w - q * 2f, h - q * 2f, color, strokeWidth / 2f, radius - q, dense, shade)
        }
        val swPx = strokeWidth * scaleAvg()
        val alphaMul = if (swPx < 1f && antiAliased) swPx else 1f
        val half = (if (swPx < 1f) 1f / scaleAvg() else strokeWidth) / 2f
        if (half * 2f >= w || half * 2f >= h) selfOverlapping()
        val segs = cornerSegments(radius + half)
        var outer = rrectScratch(0, x - half, y - half, w + half * 2f, h + half * 2f, if (radius > 0f) radius + half else 0f, segs)
        var inner = rrectScratch(1, x + half, y + half, w - half * 2f, h - half * 2f, max(0f, radius - half), segs)
        if (dense) densify(outer, inner).let { outer = it.first; inner = it.second }
        unclipped(x - half, y - half, w + half * 2f, h + half * 2f, if (radius > 0f) radius + half else 0f) {
            strokeRing(outer, inner, outer.size / 2, mulAlpha(color, alphaMul), shade?.let { f -> Shader { px, py -> mulAlpha(f.color(px, py), alphaMul) } }, band = half * 2f * scaleAvg() > 1.001f || !antiAliased)
        }
    }

    override fun strokeColorRing(points: FloatArray, normals: FloatArray, colors: IntArray, strokeWidth: Float, shape: FloatArray?) {
        beginDraw()
        if (shape == null) return colorRing(points, normals, colors, strokeWidth)
        unclipped(shape[0], shape[1], shape[2], shape[3], shape[4]) { colorRing(points, normals, colors, strokeWidth) }
    }

    private var ringColors = IntArray(0)
    private var ringPoints = FloatArray(0)

    private fun colorRing(points: FloatArray, normals: FloatArray, colors: IntArray, strokeWidth: Float) {
        val n = colors.size
        if (n < 2) return
        val scale = scaleAvg()
        if (scale <= 0f) return
        val swPx = strokeWidth * scale
        val alphaMul = if (swPx < 1f && antiAliased) swPx else 1f
        val halfPx = max(swPx, 1f) / 2f
        val band = if (antiAliased) max(halfPx - 0.5f, 0f) / scale else halfPx / scale
        val edge = (halfPx + 0.5f) / scale
        if (ringColors.size < n) {
            ringColors = IntArray(n)
            ringPoints = FloatArray(n * 8)
        }
        val c = ringColors
        val p = ringPoints
        val alpha = alphaMul * state.alpha
        for (i in 0 until n) c[i] = mulAlpha(colors[i], alpha)
        val s = state
        for (i in 0 until n) {
            if (invisible(c[(i + n - 1) % n]) && invisible(c[i]) && invisible(c[(i + 1) % n])) continue
            val x = points[i * 2]; val y = points[i * 2 + 1]
            val nx = normals[i * 2]; val ny = normals[i * 2 + 1]
            val o = i * 8
            p[o] = (x + nx * band) * s.sx + s.tx; p[o + 1] = (y + ny * band) * s.sy + s.ty
            p[o + 2] = (x + nx * edge) * s.sx + s.tx; p[o + 3] = (y + ny * edge) * s.sy + s.ty
            p[o + 4] = (x - nx * band) * s.sx + s.tx; p[o + 5] = (y - ny * band) * s.sy + s.ty
            p[o + 6] = (x - nx * edge) * s.sx + s.tx; p[o + 7] = (y - ny * edge) * s.sy + s.ty
        }
        beginWhite()
        val w = PolyGlyphPage.WHITE_UV
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ci = c[i]; val cj = c[j]
            if (invisible(ci) && invisible(cj)) continue
            val ti = ci and 0x00FFFFFF; val tj = cj and 0x00FFFFFF
            val a = i * 8; val b = j * 8
            if (band > 0f) quadVerts(p[a], p[a + 1], w, w, ci, p[a + 4], p[a + 5], w, w, ci, p[b + 4], p[b + 5], w, w, cj, p[b], p[b + 1], w, w, cj)
            if (!antiAliased) continue
            quadVerts(p[a], p[a + 1], w, w, ci, p[a + 2], p[a + 3], w, w, ti, p[b + 2], p[b + 3], w, w, tj, p[b], p[b + 1], w, w, cj)
            quadVerts(p[a + 4], p[a + 5], w, w, ci, p[a + 6], p[a + 7], w, w, ti, p[b + 6], p[b + 7], w, w, tj, p[b + 4], p[b + 5], w, w, cj)
        }
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, strokeWidth: Float) {
        beginDraw()
        val dx = x2 - x1
        val dy = y2 - y1
        val len = hypot(dx, dy)
        if (len <= 0f) return
        val reach = max(strokeWidth, 0f) / 2f
        if (culled(min(x1, x2) - reach, min(y1, y2) - reach, max(x1, x2) + reach, max(y1, y2) + reach, CULL_PAD)) return
        val across = normalScale(dx, dy)
        if (across <= 0f) return
        val swPx = strokeWidth * across
        val alphaMul = if (swPx < 1f && antiAliased) swPx else 1f
        val half = (if (swPx < 1f) 1f / across else strokeWidth) / 2f
        val nx = -dy / len * half
        val ny = dx / len * half
        val pts = quadTmp
        pts[0] = x1 + nx; pts[1] = y1 + ny; pts[2] = x2 + nx; pts[3] = y2 + ny
        pts[4] = x2 - nx; pts[5] = y2 - ny; pts[6] = x1 - nx; pts[7] = y1 - ny
        transformInPlace(pts)
        fillPolygonPx(pts, 4, mulAlpha(color, alphaMul), convex = true)
    }

    override fun fillPathGradient(path: PolyPath, x: Float, y: Float, w: Float, h: Float, gradient: PolyGradient): Unit =
        shaded(gradient, x, y, w, h) { fillPath(path, -1) }

    override fun hairlineWidth(): Float = scaleAvg().let { if (it > 0f) 1f / it else 1f }

    override fun hairlineWidth(dx: Float, dy: Float): Float = normalScale(dx, dy).let { if (it > 0f) 1f / it else hairlineWidth() }

    private fun normalScale(dx: Float, dy: Float): Float {
        val len = hypot(dx, dy)
        if (len <= 0f) return 0f
        val s = state
        var vx = -dy / len * s.sx
        var vy = dx / len * s.sy
        val m = s.rot
        if (m != null) {
            val rx = m[0] * vx + m[1] * vy
            vy = m[2] * vx + m[3] * vy
            vx = rx
        }
        return hypot(vx, vy)
    }

    override fun triangles(positions: FloatArray, colors: IntArray, indices: IntArray, bitmap: PolyBitmap?, uvs: FloatArray?) {
        beginDraw()
        if (indices.size < 3) return
        val textured = bitmap != null && uvs != null
        if (textured && cpuOnly) return
        selfOverlapping()
        if (textured) begin(bitmapTexture(bitmap), false) else beginWhite()
        val white = PolyGlyphPage.WHITE_UV
        val p = FloatArray(6)
        val u = FloatArray(6)
        val c = IntArray(3)
        for (t in 0 until indices.size / 3) {
            var visible = false
            for (k in 0 until 3) {
                val i = indices[t * 3 + k]
                p[k * 2] = positions[i * 2]; p[k * 2 + 1] = positions[i * 2 + 1]
                u[k * 2] = if (textured) uvs[i * 2] else white
                u[k * 2 + 1] = if (textured) uvs[i * 2 + 1] else white
                c[k] = mulAlpha(colors[i], state.alpha)
                if (!invisible(c[k])) visible = true
            }
            if (!visible) continue
            transformInPlace(p)
            quadVerts(
                p[0], p[1], u[0], u[1], c[0], p[2], p[3], u[2], u[3], c[1],
                p[4], p[5], u[4], u[5], c[2], p[4], p[5], u[4], u[5], c[2],
            )
        }
    }

    override fun fillPath(path: PolyPath, color: Int) {
        beginDraw()
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        val mesh = pathMesh(path)
        val xy = mesh.xy
        val solid = mesh.solid
        if (solid.isEmpty()) return
        if (culledPx(mesh.l + state.tx, mesh.t + state.ty, mesh.r + state.tx, mesh.b + state.ty, CULL_PAD)) return
        if (!mesh.convex) selfOverlapping()
        beginWhite()
        val clear = c and 0x00FFFFFF
        val tx = state.tx
        val ty = state.ty
        val w = PolyGlyphPage.WHITE_UV
        for (i in solid.indices) vertex(xy[i * 2] + tx, xy[i * 2 + 1] + ty, w, w, if (solid[i]) c else clear)
    }

    private class PathMesh(val xy: FloatArray, val solid: BooleanArray, val convex: Boolean) {
        var l = Float.MAX_VALUE
        var t = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE

        init {
            for (i in 0 until xy.size / 2) {
                l = min(l, xy[i * 2]); r = max(r, xy[i * 2])
                t = min(t, xy[i * 2 + 1]); b = max(b, xy[i * 2 + 1])
            }
        }
    }

    private class PathKey {
        var path: PolyPath? = null
        var sx = 0f
        var sy = 0f
        var aa = true
        var hash = 0

        fun set(p: PolyPath?, sx: Float, sy: Float, aa: Boolean = true) {
            path = p
            this.sx = sx
            this.sy = sy
            this.aa = aa
            hash = if (p == null) 0 else ((p.contentHash() * 31 + sx.toRawBits()) * 31 + sy.toRawBits()) * 2 + (if (aa) 1 else 0)
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean {
            if (other !is PathKey || other.hash != hash || other.aa != aa || other.sx.toRawBits() != sx.toRawBits() || other.sy.toRawBits() != sy.toRawBits()) return false
            val a = path ?: return false
            val b = other.path ?: return false
            return a.contentEquals(b)
        }
    }

    private val pathProbe = PathKey()
    private val pathMeshes = object : LinkedHashMap<PathKey, PathMesh>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<PathKey, PathMesh>?) = size > MAX_PATH_MESHES
    }

    private var recording = false
    private var recXy = FloatArray(256)
    private var recSolid = BooleanArray(128)
    private var recCount = 0

    private fun record(x: Float, y: Float, color: Int) {
        if (recCount == recSolid.size) {
            recSolid = recSolid.copyOf(recCount * 2)
            recXy = recXy.copyOf(recCount * 4)
        }
        recXy[recCount * 2] = x; recXy[recCount * 2 + 1] = y
        recSolid[recCount++] = color ushr 24 != 0
    }

    private fun pathMesh(path: PolyPath): PathMesh {
        val s = state
        val probe = pathProbe
        probe.set(path, s.sx, s.sy, antiAliased)
        val cached = pathMeshes[probe]
        probe.set(null, 0f, 0f)
        if (cached != null) return cached
        val tx = s.tx; val ty = s.ty; val alpha = s.alpha
        s.tx = 0f; s.ty = 0f; s.alpha = 1f
        recording = true
        recCount = 0
        val convex = try {
            tessellatePath(path)
        } finally {
            recording = false
            s.tx = tx; s.ty = ty; s.alpha = alpha
        }
        val mesh = PathMesh(recXy.copyOf(recCount * 2), recSolid.copyOf(recCount), convex)
        if (recCount <= MAX_PATH_MESH_VERTICES) pathMeshes[PathKey().also { it.set(path.snapshot(), s.sx, s.sy, antiAliased) }] = mesh
        return mesh
    }

    private fun tessellatePath(path: PolyPath): Boolean {
        val color = -1
        val loops = flatten(path)
        for (loop in loops) transformInPlace(loop)
        if (loops.size == 1) {
            fillPolygonPx(loops[0], loops[0].size / 2, color, convex = false)
            return isConvexLoop(loops[0], loops[0].size / 2)
        }
        val n = loops.size
        val area = FloatArray(n) { signedArea(loops[it], loops[it].size / 2) }
        val parent = IntArray(n) { -1 }
        val outside = IntArray(n)
        val eo = path.evenOdd
        fun own(i: Int) = if (eo || area[i] > 0f) 1 else -1
        for (i in 0 until n) for (j in 0 until n) {
            if (i == j || abs(area[j]) <= abs(area[i]) || !inLoop(loops[j], loops[i])) continue
            outside[i] += own(j)
            if (parent[i] < 0 || abs(area[j]) < abs(area[parent[i]])) parent[i] = j
        }
        fun filled(w: Int) = if (eo) w % 2 != 0 else w != 0
        val outer = BooleanArray(n) { !filled(outside[it]) && filled(outside[it] + own(it)) }
        val holes = arrayOfNulls<ArrayList<Int>>(n)
        for (i in 0 until n) {
            if (outer[i] || !filled(outside[i]) || filled(outside[i] + own(i))) continue
            var o = parent[i]
            while (o >= 0 && !outer[o]) o = parent[o]
            if (o >= 0) (holes[o] ?: ArrayList<Int>().also { holes[o] = it }).add(i)
        }
        for (i in 0 until n) {
            if (!outer[i]) continue
            val h = holes[i]
            if (h == null) fillPolygonPx(loops[i], loops[i].size / 2, color, convex = false)
            else fillWithHoles(loops, area, i, h, color)
        }
        return false
    }

    private fun fillWithHoles(loops: List<FloatArray>, area: FloatArray, o: Int, holes: List<Int>, color: Int) {
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        val rings = ArrayList<FloatArray>(holes.size * 2 + 2)
        fun ring(p: FloatArray, dir: Float): FloatArray {
            val inner = FloatArray(p.size); val outer = FloatArray(p.size)
            offsetPolygon(p, p.size / 2, dir, inner, outer)
            rings += inner; rings += outer
            return inner
        }
        val cw = area[o] > 0f
        var merged = ring(loops[o], if (cw) 1f else -1f).let { if (cw) it else reversed(it) }
        for (h in holes.sortedBy { minX(loops[it]) }) {
            val inner = ring(loops[h], if (area[h] > 0f) -1f else 1f)
            merged = bridge(merged, if (area[h] > 0f) reversed(inner) else inner)
        }
        if (!recording) beginWhite()
        val w = PolyGlyphPage.WHITE_UV
        val m = merged
        val tris = earClip(m, m.size / 2)
        for (k in tris.indices step 3) {
            val a = tris[k] * 2; val b = tris[k + 1] * 2; val d = tris[k + 2] * 2
            quadVerts(m[a], m[a + 1], w, w, c, m[b], m[b + 1], w, w, c, m[d], m[d + 1], w, w, c, m[d], m[d + 1], w, w, c)
        }
        if (!antiAliased) return
        val clear = c and 0x00FFFFFF
        for (k in 0 until rings.size step 2) {
            val inner = rings[k]; val outer = rings[k + 1]
            val n = inner.size / 2
            for (i in 0 until n) {
                val a = i * 2; val b = (i + 1) % n * 2
                quadVerts(inner[a], inner[a + 1], w, w, c, outer[a], outer[a + 1], w, w, clear, outer[b], outer[b + 1], w, w, clear, inner[b], inner[b + 1], w, w, c)
            }
        }
    }

    private fun minX(p: FloatArray): Float {
        var m = Float.MAX_VALUE
        for (i in p.indices step 2) m = min(m, p[i])
        return m
    }

    private fun reversed(p: FloatArray): FloatArray {
        val n = p.size / 2
        return FloatArray(p.size) { if (it % 2 == 0) p[(n - 1 - it / 2) * 2] else p[(n - 1 - it / 2) * 2 + 1] }
    }

    private fun inLoop(outer: FloatArray, p: FloatArray): Boolean {
        for (i in p.indices step 2) if (!pointInLoop(outer, p[i], p[i + 1])) return false
        return true
    }

    private fun pointInLoop(p: FloatArray, x: Float, y: Float): Boolean {
        var inside = false
        val n = p.size / 2
        var j = n - 1
        for (i in 0 until n) {
            val yi = p[i * 2 + 1]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (p[j * 2] - p[i * 2]) * (y - yi) / (yj - yi) + p[i * 2]) inside = !inside
            j = i
        }
        return inside
    }

    private fun bridge(outer: FloatArray, hole: FloatArray): FloatArray {
        val no = outer.size / 2
        val nh = hole.size / 2
        var hi = 0
        for (i in 1 until nh) if (hole[i * 2] < hole[hi * 2] || (hole[i * 2] == hole[hi * 2] && hole[i * 2 + 1] < hole[hi * 2 + 1])) hi = i
        val hx = hole[hi * 2]; val hy = hole[hi * 2 + 1]
        fun x(i: Int) = outer[i * 2]
        fun y(i: Int) = outer[i * 2 + 1]
        fun prev(i: Int) = (i + no - 1) % no
        fun next(i: Int) = (i + 1) % no
        fun ar(ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float) = (by - ay) * (cx - bx) - (bx - ax) * (cy - by)
        fun ar(a: Int, b: Int, c: Int) = ar(x(a), y(a), x(b), y(b), x(c), y(c))
        var qx = -Float.MAX_VALUE
        var m = -1
        var touching = false
        for (i in 0 until no) {
            val j = next(i)
            if (hy <= y(i) && hy >= y(j) && y(j) != y(i)) {
                val ix = x(i) + (hy - y(i)) * (x(j) - x(i)) / (y(j) - y(i))
                if (ix <= hx && ix > qx) {
                    qx = ix
                    m = if (x(i) < x(j)) i else j
                    if (ix == hx) { touching = true; break }
                }
            }
        }
        if (m < 0) {
            var d = Float.MAX_VALUE
            for (i in 0 until no) {
                val dd = hypot(x(i) - hx, y(i) - hy)
                if (dd < d) { d = dd; m = i }
            }
        } else if (!touching) {
            val stop = m
            val mx = x(m); val my = y(m)
            var tanMin = Float.MAX_VALUE
            var p = m
            do {
                val px = x(p); val py = y(p)
                val ax = if (hy < my) hx else qx
                val cx = if (hy < my) qx else hx
                val inTri = (cx - px) * (hy - py) >= (ax - px) * (hy - py) && (ax - px) * (my - py) >= (mx - px) * (hy - py) && (mx - px) * (hy - py) >= (cx - px) * (my - py)
                if (hx >= px && px >= mx && hx != px && inTri) {
                    val tan = abs(hy - py) / (hx - px)
                    val a = prev(p); val b = next(p)
                    val local = if (ar(a, p, b) < 0f) ar(px, py, hx, hy, x(b), y(b)) >= 0f && ar(px, py, x(a), y(a), hx, hy) >= 0f
                    else ar(px, py, hx, hy, x(a), y(a)) < 0f || ar(px, py, x(b), y(b), hx, hy) < 0f
                    val sector = ar(prev(m), m, prev(p)) < 0f && ar(next(p), m, next(m)) < 0f
                    if (local && (tan < tanMin || (tan == tanMin && (px > x(m) || (px == x(m) && sector))))) { m = p; tanMin = tan }
                }
                p = next(p)
            } while (p != stop)
        }
        val out = FloatArray((no + nh + 2) * 2)
        var k = 0
        fun put(a: FloatArray, i: Int) { out[k++] = a[i * 2]; out[k++] = a[i * 2 + 1] }
        for (i in 0..m) put(outer, i)
        for (s in 0..nh) put(hole, (hi + s) % nh)
        for (i in m until no) put(outer, i)
        return out
    }

    override fun strokePolylineGradient(
        points: FloatArray, closed: Boolean, x: Float, y: Float, w: Float, h: Float, gradient: PolyGradient, strokeWidth: Float, cap: Int, join: Int,
    ): Unit = strokePolylineGradient(points, closed, x, y, w, h, gradient, strokeWidth, cap, join, DEFAULT_MITER_LIMIT)

    override fun strokePolylineGradient(
        points: FloatArray, closed: Boolean, x: Float, y: Float, w: Float, h: Float, gradient: PolyGradient, strokeWidth: Float, cap: Int, join: Int, miterLimit: Float,
    ): Unit = shaded(gradient, x, y, w, h) { strokePolyline(points, closed, -1, strokeWidth, cap, join, miterLimit) }

    override fun strokePolyline(points: FloatArray, closed: Boolean, color: Int, strokeWidth: Float, cap: Int, join: Int): Unit =
        strokePolyline(points, closed, color, strokeWidth, cap, join, DEFAULT_MITER_LIMIT)

    override fun strokePolyline(points: FloatArray, closed: Boolean, color: Int, strokeWidth: Float, cap: Int, join: Int, miterLimit: Float) {
        beginDraw()
        if (points.size < 4 || strokeWidth <= 0f) return
        if (state.clip != null) {
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (i in 0 until points.size / 2) {
                l = min(l, points[i * 2]); r = max(r, points[i * 2])
                t = min(t, points[i * 2 + 1]); b = max(b, points[i * 2 + 1])
            }
            val reach = strokeWidth / 2f * max(miterLimit, 2f)
            if (culled(l - reach, t - reach, r + reach, b + reach, CULL_PAD * max(miterLimit, 2f))) return
        }
        val p = dedupe(points)
        if (p.size == 2) {
            if (cap != PolyRenderer.CAP_BUTT) rect(p[0] - strokeWidth / 2f, p[1] - strokeWidth / 2f, strokeWidth, strokeWidth, color, if (cap == PolyRenderer.CAP_ROUND) strokeWidth / 2f else 0f)
            return
        }
        val loop = (closed || length(points[0] - points[points.size - 2], points[1] - points[points.size - 1]) <= 1e-3f) && p.size >= 6
        transformInPlace(p)
        val n = p.size / 2
        if (n < 2) return
        val swPx = strokeWidth * scaleAvg()
        val c = mulAlpha(color, if (swPx < 1f && antiAliased) swPx else 1f)
        val half = max(swPx, 1f) / 2f
        if (!loop && cap == PolyRenderer.CAP_SQUARE) {
            fun extend(i: Int, from: Int) {
                val dx = p[i * 2] - p[from * 2]; val dy = p[i * 2 + 1] - p[from * 2 + 1]
                val l = length(dx, dy)
                p[i * 2] += dx / l * half; p[i * 2 + 1] += dy / l * half
            }
            extend(0, 1)
            extend(n - 1, n - 2)
        }
        val arcSegs = max(4, min(32, ceil(sqrt(half) * 3.2f).toInt()))
        val round = join == PolyRenderer.JOIN_ROUND
        val per = if (round) arcSegs + 3 else 5
        val left = FloatArray(n * per * 2)
        val right = FloatArray(n * per * 2)
        var lo = 0
        var ro = 0
        fun put(rightOuter: Boolean, ox: Float, oy: Float, ix: Float, iy: Float) {
            if (rightOuter) {
                left[lo++] = ix; left[lo++] = iy; right[ro++] = ox; right[ro++] = oy
            } else {
                left[lo++] = ox; left[lo++] = oy; right[ro++] = ix; right[ro++] = iy
            }
        }
        val segCount = if (loop) n else n - 1
        val dir = FloatArray(n * 2)
        val len = FloatArray(n)
        for (s in 0 until segCount) {
            val j = (s + 1) % n
            val dx = p[j * 2] - p[s * 2]; val dy = p[j * 2 + 1] - p[s * 2 + 1]
            val l = length(dx, dy)
            dir[s * 2] = dx / l; dir[s * 2 + 1] = dy / l; len[s] = l
        }
        val turns = FloatArray(n)
        for (i in 0 until n) {
            if (!loop && (i == 0 || i == n - 1)) continue
            val a = (i + n - 1) % n
            val t = atan2(dir[a * 2] * dir[i * 2 + 1] - dir[a * 2 + 1] * dir[i * 2], dir[a * 2] * dir[i * 2] + dir[a * 2 + 1] * dir[i * 2 + 1])
            if (abs(t) >= 1e-3f) turns[i] = t
        }
        fun room(seg: Int, other: Int, turn: Float) = if (turns[other] * turn > 0f) len[seg] / 2f else len[seg]
        for (i in 0 until n) {
            val a = if (loop || i > 0) (i + n - 1) % n else 0
            val b = if (loop || i < n - 1) i else n - 2
            val n0x = -dir[a * 2 + 1]; val n0y = dir[a * 2]
            val n1x = -dir[b * 2 + 1]; val n1y = dir[b * 2]
            val mx = (n0x + n1x) / 2f; val my = (n0y + n1y) / 2f
            val d2 = mx * mx + my * my
            val px = p[i * 2]; val py = p[i * 2 + 1]
            val turn = turns[i]
            if (turn == 0f) {
                val k = half / d2
                put(false, px + mx * k, py + my * k, px - mx * k, py - my * k)
                continue
            }
            val rightOuter = turn > 0f
            val side = if (rightOuter) -1f else 1f
            val hx = half * side
            val ox0 = px + n0x * hx; val oy0 = py + n0y * hx
            val ox1 = px + n1x * hx; val oy1 = py + n1y * hx
            val meet = d2 > 1e-6f && half * tan(abs(turn) / 2f) <= min(room(a, a, turn), room(b, (i + 1) % n, turn))
            val cx = if (meet) px - mx * hx / d2 else px
            val cy = if (meet) py - my * hx / d2 else py
            if (!meet) put(rightOuter, ox0, oy0, px - n0x * hx, py - n0y * hx)
            if (round) {
                val steps = max(1, ceil(arcSegs * abs(turn) / Math.PI.toFloat()).toInt())
                val a0 = atan2(n0y * side, n0x * side)
                for (s in 0..steps) {
                    val ang = a0 + turn * s / steps
                    put(rightOuter, px + cos(ang) * half, py + sin(ang) * half, cx, cy)
                }
            } else if (join == PolyRenderer.JOIN_MITER && d2 > 1e-6f && d2 * miterLimit * miterLimit >= 1f) {
                if (!meet) put(rightOuter, ox0, oy0, cx, cy)
                put(rightOuter, px + mx * hx / d2, py + my * hx / d2, cx, cy)
                if (!meet) put(rightOuter, ox1, oy1, cx, cy)
            } else {
                put(rightOuter, ox0, oy0, cx, cy)
                put(rightOuter, ox1, oy1, cx, cy)
            }
            if (!meet) put(rightOuter, ox1, oy1, px - n1x * hx, py - n1y * hx)
        }
        val m = lo / 2
        val l = if (lo == left.size) left else left.copyOf(lo)
        val r = if (ro == right.size) right else right.copyOf(ro)
        if (loop) {
            val leftOuter = abs(signedArea(l, m)) >= abs(signedArea(r, m))
            val outerRing = if (leftOuter) l else r
            val innerRing = if (leftOuter) r else l
            if (!isConvexLoop(p, n) || !isConvexLoop(innerRing, m) || (signedArea(innerRing, m) > 0f) != (signedArea(outerRing, m) > 0f)) selfOverlapping()
            strokeRing(outerRing, innerRing, m, c, band = half * 2f > 1.001f || !antiAliased)
            return
        }
        if (n > 2) selfOverlapping()
        val segs = if (cap == PolyRenderer.CAP_ROUND) arcSegs else 0
        val ce = (segs - 1).coerceAtLeast(0)
        val out = FloatArray((m * 2 + ce * 2) * 2)
        var o = 0
        fun cap(i: Int, from: FloatArray, at: Int) {
            val cx = p[i * 2]; val cy = p[i * 2 + 1]
            val a0 = atan2(from[at * 2 + 1] - cy, from[at * 2] - cx)
            for (s in 1 until segs) {
                val a = a0 - Math.PI.toFloat() * s / segs
                out[o++] = cx + cos(a) * half; out[o++] = cy + sin(a) * half
            }
        }
        for (i in 0 until m) { out[o++] = l[i * 2]; out[o++] = l[i * 2 + 1] }
        cap(n - 1, l, m - 1)
        for (i in m - 1 downTo 0) { out[o++] = r[i * 2]; out[o++] = r[i * 2 + 1] }
        cap(0, r, 0)
        val total = o / 2
        fun ri(k: Int) = m + ce + (m - 1 - k)
        val quads = IntArray((m - 1 + ce * 2) * 4)
        var q = 0
        fun quad(a: Int, b: Int, d: Int, e: Int) { quads[q++] = a; quads[q++] = b; quads[q++] = d; quads[q++] = e }
        for (k in 0 until m - 1) quad(k, k + 1, ri(k + 1), ri(k))
        for (j in 0 until ce) quad(m - 1, m + j, m + j + 1, m + j + 1)
        for (j in 0 until ce) quad(ri(0), ri(0) + 1 + j, (ri(0) + 2 + j) % total, (ri(0) + 2 + j) % total)
        fillPolygonPx(out, total, c, convex = false, quads = quads)
    }

    override fun text(text: String, x: Float, y: Float, color: Int, font: PolyFont): Unit = glyphs(text, x, y, color, font, 0f, false, true)

    override fun text(text: String, x: Float, y: Float, color: Int, font: PolyFont, letterSpacing: Float): Unit = glyphs(text, x, y, color, font, 0f, false, true, letterSpacing)

    override fun text(text: String, x: Float, y: Float, color: Int, font: PolyFont, letterSpacing: Float, skewX: Float) {
        if (skewX == 0f || state.sy == 0f) return text(text, x, y, color, font, letterSpacing)
        shearX = skewX * state.sx / state.sy
        shearBase = y * state.sy + state.ty
        glyphs(text, x, y, color, font, 0f, false, true, letterSpacing)
        shearX = 0f
    }

    override fun textOutline(text: String, x: Float, y: Float, color: Int, font: PolyFont, width: Float): Unit =
        glyphs(text, x, y, color, font, width * scaleAvg(), true, true)

    internal fun textStroke(text: String, x: Float, y: Float, color: Int, font: PolyFont, letterSpacing: Float, skewX: Float, width: Float) {
        val radius = (if (width > 0f) width * scaleAvg() else 1f) / 2f
        if (skewX != 0f && state.sy != 0f) {
            shearX = skewX * state.sx / state.sy
            shearBase = y * state.sy + state.ty
        }
        glyphs(text, x, y, color, font, radius, true, true, letterSpacing, true)
        shearX = 0f
    }

    private fun glyphs(
        text: String, x: Float, y: Float, color: Int, font: PolyFont, outlinePx: Float, round: Boolean, subpixel: Boolean, letterSpacing: Float = 0f, ring: Boolean = false,
    ) {
        beginDraw()
        if (outlinePx > 0f) selfOverlapping()
        if (lineCulled(y - font.size * TEXT_ASCENT_REACH, y + font.size * TEXT_DESCENT_REACH, outlinePx)) return
        val r = PolyGlyphPage.rasterizer ?: return
        val face = face(r, font.name) ?: return
        glyphRun(r, text, 0, text.length, x, y, color, face, font.size, outlinePx, round, subpixel, letterSpacing, ring)
    }

    private var faceSource: PolyGlyphPage.Rasterizer? = null
    private var faceRevision = -1
    private val faceNames = arrayOfNulls<String>(FACE_CACHE)
    private val faceValues = arrayOfNulls<PolyGlyphPage.Face>(FACE_CACHE)
    private var faceNext = 0

    private fun face(r: PolyGlyphPage.Rasterizer, name: String): PolyGlyphPage.Face? {
        val revision = FontManager.revision
        if (r !== faceSource || revision != faceRevision) {
            faceSource = r
            faceRevision = revision
            faceNames.fill(null)
            faceValues.fill(null)
        }
        for (i in 0 until FACE_CACHE) if (faceNames[i] == name) return faceValues[i]
        val f = r.face(name)
        faceNames[faceNext] = name
        faceValues[faceNext] = f
        faceNext = (faceNext + 1) % FACE_CACHE
        return f
    }

    private fun fallbackFace(r: PolyGlyphPage.Rasterizer, cp: Int, next: Int): PolyGlyphPage.Face? {
        if (Character.isEmojiPresentation(cp) || (next == PolyGlyphPage.EMOJI_VARIATION && Character.isEmoji(cp))) {
            r.emoji()?.let { if (it.glyph(cp) != 0) return it }
        }
        return face(r, FALLBACK_FONT)
    }

    private var runFace: PolyGlyphPage.Face? = null
    private var runAtlas: PolyGlyphPage.Atlas? = null
    private var runFallback: PolyGlyphPage.Face? = null
    private var runFallbackAtlas: PolyGlyphPage.Atlas? = null
    private var runBucket = 0
    private var runOutline = 0f
    private var runRound = false
    private var runLum = -1
    private var runRing = false

    private fun runAtlasFor(f: PolyGlyphPage.Face): PolyGlyphPage.Atlas =
        if (runRing) PolyGlyphPage.ringAtlas(f, runBucket, runOutline) else PolyGlyphPage.atlas(f, runBucket, runOutline, runRound, runLum)

    private fun runGlyph(f: PolyGlyphPage.Face, id: Int, phase: Int): PolyGlyphPage.Glyph? {
        if (!sharedAtlas) return if (cpuOnly) null else PolyGlyphPage.cpuGlyph(f, runBucket, id, runOutline, runRound, phase, runLum, runRing)
        if (f !== runFace && f !== runFallback) {
            runFallback = f
            if (!cpuOnly) runFallbackAtlas = runAtlasFor(f)
        }
        val a = if (f === runFace) runAtlas else runFallbackAtlas
        if (a != null) return PolyGlyphPage.glyph(a, f, runBucket, id, runOutline, runRound, phase, runLum)
        if (runOutline <= 0f) PolyGlyphPage.prerasterize(f, runBucket, id, phase)
        return null
    }

    private fun glyphRun(
        r: PolyGlyphPage.Rasterizer, text: String, start: Int, end: Int, x: Float, y: Float, color: Int, face: PolyGlyphPage.Face, size: Float,
        outlinePx: Float, round: Boolean, subpixel: Boolean, letterSpacing: Float, ring: Boolean = false,
    ) {
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        var k = state.glyphScale
        val ax = abs(state.sx)
        val ay = abs(state.sy)
        val big = max(ax, ay)
        val raw = size * big / k * 4f
        if (!(raw > 0f) || raw.isInfinite()) return
        val outline = outlinePx / k
        var bucket = raw.roundToInt()
        val lum = if (outline > 0f) -1 else PolyMaskGamma.bucket(color)
        if (outline <= 0f && !cpuOnly && sharedAtlas) {
            val snapped = PolyGlyphPage.animatedBucket(face, bucket, lum)
            if (snapped != bucket) {
                k *= bucket / snapped.toFloat()
                bucket = snapped
            }
        }
        val kx = k * ax / big
        val ky = k * ay / big
        runFace = face
        runBucket = bucket
        runOutline = outline
        runRound = round
        runLum = lum
        runRing = ring && outline > 0f
        runAtlas = if (cpuOnly || !sharedAtlas) null else runAtlasFor(face)
        runFallback = null
        runFallbackAtlas = null
        val s = state
        val deviceY = y * s.sy + s.ty
        val emoji = r.emoji()
        var penX = x
        var kernGlyph = 0
        var i = start
        while (i < end) {
            val at = i
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (PolyGlyphPage.isIgnorable(cp)) continue
            var f = face
            var id: Int
            val sequence = if (emoji != null && i < end) PolyGlyphPage.sequence(emoji, text, at, end) else 0L
            if (sequence != 0L && emoji != null) {
                f = emoji
                id = (sequence ushr 32).toInt()
                i = sequence.toInt()
            } else {
                id = face.glyph(cp)
                if (id == 0 && !Character.isWhitespace(cp)) {
                    val other = fallbackFace(r, cp, if (i < end) text.codePointAt(i) else 0)
                    val fid = other?.glyph(cp) ?: 0
                    if (fid != 0) { f = other!!; id = fid }
                }
            }
            val kernable = f === face && id != 0 && sequence == 0L && PolyGlyphPage.kernable(cp)
            if (kernable && kernGlyph != 0) penX += face.kern(kernGlyph, id) * size
            kernGlyph = if (kernable) id else 0
            val sx = penX * s.sx + s.tx
            if (kx != 1f || ky != 1f) {
                val phase = if (subpixel) ((sx - floor(sx)) * PolyGlyphPage.SUBPIXELS).toInt() else 0
                val g = runGlyph(f, id, phase)
                if (g != null) {
                    val px = sx + (g.offX - phase.toFloat() / PolyGlyphPage.SUBPIXELS) * kx
                    val py = deviceY + g.offY * ky
                    begin(g.page, false)
                    glyphQuad(px, py, px + g.w * kx, py + g.h * ky, g.u0, g.v0, g.u1, g.v1, if (g.color) c or 0xFFFFFF else c)
                }
                penX += f.advance(id) * size + letterSpacing
                continue
            }
            var base = if (subpixel) floor(sx).toInt() else sx.roundToInt()
            var phase = if (subpixel) ((sx - base) * PolyGlyphPage.SUBPIXELS).roundToInt() else 0
            if (phase == PolyGlyphPage.SUBPIXELS) { base++; phase = 0 }
            val g = runGlyph(f, id, phase)
            if (g != null) {
                val px = base + g.offX
                val py = deviceY.roundToInt() + g.offY
                begin(g.page, false)
                glyphQuad(px.toFloat(), py.toFloat(), (px + g.w).toFloat(), (py + g.h).toFloat(), g.u0, g.v0, g.u1, g.v1, if (g.color) c or 0xFFFFFF else c)
            }
            penX += f.advance(id) * size + letterSpacing
        }
        runFace = null
        runAtlas = null
        runFallback = null
        runFallbackAtlas = null
        runRing = false
    }

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) {
        mcText(text, x, y, color, shadow, scale, false)
    }

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, literal: Boolean) {
        if (cpuOnly) return
        val x = if (snapMcX) mcLineLeft(x) else x
        if (shadow) {
            beginDraw()
            mcLines(text, x + scale, y, scale, color, scale, true, literal)
        }
        beginDraw()
        mcLines(text, x, y, 0f, color, scale, false, literal)
    }

    private fun mcLines(text: String, x: Float, y: Float, dy: Float, color: Int, scale: Float, shadow: Boolean, literal: Boolean) {
        var start = 0
        var index = 0
        while (true) {
            val end = mcLineEnd(text, start)
            val top = mcLineTop(y, index, scale) + dy
            if (!lineCulled(top - MC_LINE_REACH * scale, top + (MC_LINE_HEIGHT + MC_LINE_REACH) * scale, 0f)) mcLine(text, start, end, x, top, color, scale, shadow, literal)
            if (end >= text.length) return
            start = mcNextLine(text, end)
            index++
        }
    }

    private fun mcLineEnd(text: String, start: Int): Int {
        var i = start
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
        return i
    }

    private fun mcNextLine(text: String, end: Int): Int = if (text[end] == '\r' && end + 1 < text.length && text[end + 1] == '\n') end + 2 else end + 1

    private var mcSource: ((Int) -> McGlyph?)? = null
    private var mcSourceRevision = -1
    private var mcCached: Array<McGlyph?>? = null
    private var mcKnown: BooleanArray? = null

    private fun mcGlyphFor(cp: Int): McGlyph? {
        val source = McGlyphs.source ?: return null
        val revision = FontManager.revision
        if (cp !in 0 until MC_GLYPH_CACHE) return source(cp)
        var cached = mcCached
        var known = mcKnown
        if (cached == null || known == null) {
            cached = arrayOfNulls(MC_GLYPH_CACHE)
            known = BooleanArray(MC_GLYPH_CACHE)
            mcCached = cached
            mcKnown = known
            mcSource = source
            mcSourceRevision = revision
        } else if (source !== mcSource || revision != mcSourceRevision) {
            mcSource = source
            mcSourceRevision = revision
            cached.fill(null)
            known.fill(false)
        }
        if (known[cp]) return cached[cp]
        val g = source(cp)
        cached[cp] = g
        known[cp] = true
        return g
    }

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, literal: Boolean, snapX: Boolean) {
        withMcX(snapX) { if (literal) mcText(text, x, y, color, shadow, scale, true) else mcText(text, x, y, color, shadow, scale) }
    }

    override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float) {
        mcTextOutline(text, x, y, color, outlineColor, scale, false)
    }

    override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float, literal: Boolean) {
        beginDraw()
        if (cpuOnly) return
        val x = if (snapMcX) mcLineLeft(x) else x
        mcOutlineQuads(text, x, y, mulAlpha(outlineColor, state.alpha), scale, literal)
        mcText(text, x, y, color, false, scale, literal, false)
    }

    internal fun mcTextStroke(text: String, x: Float, y: Float, color: Int, scale: Float, literal: Boolean, snapX: Boolean) {
        if (!layered || !blendSupported(PolyRenderer.BLEND_DST_OUT) || !McGlyphs.available()) return mcText(text, x, y, color, false, scale, literal, snapX)
        if (cpuOnly) return
        withMcX(snapX) {
            val left = if (snapMcX) mcLineLeft(x) else x
            val outer = wantBlend
            saveLayerBlend(1f, outer)
            blend(PolyRenderer.BLEND_SRC_OVER)
            try {
                beginDraw()
                mcOutlineQuads(text, left, y, mulAlpha(color, state.alpha), scale, literal)
                blend(PolyRenderer.BLEND_DST_OUT)
                beginDraw()
                mcLines(text, left, y, 0f, MC_PUNCH, scale, false, literal)
                blend(PolyRenderer.BLEND_SRC_OVER)
            } finally {
                restore()
                blend(outer)
            }
        }
    }

    private var outlineBoxes = FloatArray(64)

    private fun mcOutlineQuads(text: String, x: Float, y: Float, c: Int, scale: Float, literal: Boolean) {
        val r = PolyGlyphPage.rasterizer
        if (c ushr 24 != 0 && r != null) {
            val s = state
            var boxes = outlineBoxes
            var n = 0
            var lineCount = 0
            var start = 0
            while (true) {
                val end = mcLineEnd(text, start)
                val line = if (start == 0 && end == text.length) text else text.substring(start, end)
                val index = lineCount++
                val rects = mcOutlineRects(r, line, literal, sharedAtlas)
                val top = mcLineTop(y, index, scale)
                var i = 0
                while (i < rects.size) {
                    val ax = coveredFrom((x + rects[i] * scale) * s.sx + s.tx)
                    val ay = coveredFrom((top + rects[i + 1] * scale) * s.sy + s.ty)
                    val bx = coveredFrom((x + rects[i + 2] * scale) * s.sx + s.tx)
                    val by = coveredFrom((top + rects[i + 3] * scale) * s.sy + s.ty)
                    if (ax != bx && ay != by) {
                        if (n + 4 > boxes.size) {
                            boxes = boxes.copyOf(boxes.size * 2)
                            outlineBoxes = boxes
                        }
                        boxes[n++] = min(ax, bx); boxes[n++] = min(ay, by); boxes[n++] = max(ax, bx); boxes[n++] = max(ay, by)
                    }
                    i += 4
                }
                if (end >= text.length) break
                start = mcNextLine(text, end)
            }
            if (n > 0) {
                beginWhite()
                val wuv = PolyGlyphPage.WHITE_UV
                if (lineCount == 1) {
                    for (i in 0 until n step 4) quadPx(boxes[i], boxes[i + 1], boxes[i + 2], boxes[i + 3], wuv, wuv, wuv, wuv, c)
                } else {
                    val ys = FloatArray(n / 2) { boxes[it / 2 * 4 + 1 + it % 2 * 2] }.apply { sort() }
                    val byTop = (0 until n step 4).sortedBy { boxes[it + 1] }
                    val active = ArrayList<Int>()
                    var next = 0
                    for (j in 0 until ys.size - 1) {
                        val y0 = ys[j]
                        val y1 = ys[j + 1]
                        if (y1 == y0) continue
                        active.removeAll { boxes[it + 3] <= y0 }
                        while (next < byTop.size && boxes[byTop[next] + 1] <= y0) active += byTop[next++]
                        active.sortBy { boxes[it] }
                        var start = 0f
                        var end = Float.NEGATIVE_INFINITY
                        for (k in active) {
                            if (boxes[k] > end) {
                                if (end > start) quadPx(start, y0, end, y1, wuv, wuv, wuv, wuv, c)
                                start = boxes[k]
                                end = boxes[k + 2]
                            } else end = max(end, boxes[k + 2])
                        }
                        if (end > start) quadPx(start, y0, end, y1, wuv, wuv, wuv, wuv, c)
                    }
                }
            }
        }
    }

    override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float, literal: Boolean, snapX: Boolean) {
        withMcX(snapX) { if (literal) mcTextOutline(text, x, y, color, outlineColor, scale, true) else mcTextOutline(text, x, y, color, outlineColor, scale) }
    }

    private var snapMcX = true

    private inline fun withMcX(snapX: Boolean, draw: () -> Unit) {
        val previous = snapMcX
        snapMcX = snapX
        try {
            draw()
        } finally {
            snapMcX = previous
        }
    }

    private fun mcLineLeft(x: Float): Float {
        val s = state
        if (s.rot != null || s.sx == 0f) return x
        val device = x * s.sx + s.tx
        return x + (device.roundToInt() - device) / s.sx
    }

    private fun mcLineTop(y: Float, index: Int, scale: Float): Float {
        val s = state
        val step = index * 9f * scale
        if (s.rot != null || s.sy == 0f) return y + step
        val device = y * s.sy + s.ty
        val deviceStep = step * s.sy
        return y + step + (device.roundToInt() - device + deviceStep.roundToInt() - deviceStep) / s.sy
    }

    private fun coveredFrom(edge: Float): Float = ceil(edge - 0.5f - EDGE_EPSILON)

    // device-space horizontal shear: x += shearX * (shearBase - y)
    private var shearX = 0f
    private var shearBase = 0f

    private fun glyphQuad(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, c: Int) {
        if (shearX == 0f) return quadPx(x0, y0, x1, y1, u0, v0, u1, v1, c)
        val s0 = shearX * (shearBase - y0)
        val s1 = shearX * (shearBase - y1)
        vertex(x0 + s0, y0, u0, v0, c)
        vertex(x0 + s1, y1, u0, v1, c)
        vertex(x1 + s1, y1, u1, v1, c)
        vertex(x1 + s0, y0, u1, v0, c)
    }

    private fun mcEffect(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        beginWhite()
        val s = state
        val w = PolyGlyphPage.WHITE_UV
        quadPx(x0 * s.sx + s.tx, y0 * s.sy + s.ty, x1 * s.sx + s.tx, y1 * s.sy + s.ty, w, w, w, w, c)
    }

    private fun mcLine(line: String, from: Int, to: Int, x: Float, top: Float, color: Int, scale: Float, shadow: Boolean, literal: Boolean) {
        val baseline = top + 7f * scale
        val r = PolyGlyphPage.rasterizer
        var c = color
        var bold = false
        var italic = false
        var under = false
        var strike = false
        var pen = x
        var i = from
        val s = state
        val italicShear = 0.25f * s.sx / s.sy
        while (i < to) {
            val cpStart = i
            val cp = line.codePointAt(i)
            i += Character.charCount(cp)
            if (!literal && cp == 0xA7 && i < to) {
                val code = MC_CODES.indexOf(line[i].lowercaseChar())
                i++
                if (code in 0..15 || code == 17) { bold = false; italic = false; under = false; strike = false }
                when {
                    code in 0..15 -> c = (color and -0x1000000) or MC_COLORS[code]
                    code == 16 -> bold = true
                    code == 17 -> c = color
                    code == 18 -> strike = true
                    code == 19 -> under = true
                    code == 20 -> italic = true
                }
                continue
            }
            val drawn = if (shadow) (c and -0x1000000) or ((c and 0xFCFCFC) shr 2) else c
            val g = mcGlyphFor(cp)
            // the shadow line is already one unit off, but vanilla offsets each glyph's shadow by its own offset
            val o = if (shadow && g != null) (g.offset - 1f) * scale else 0f
            shearX = if (italic) italicShear else 0f
            shearBase = (top + o + 4f * scale) * s.sy + s.ty
            val start = pen
            if (g != null) {
                mcGlyph(g, pen + o, top + o, scale, drawn)
                if (bold) {
                    selfOverlapping()
                    mcGlyph(g, pen + o + g.offset * scale, top + o, scale, drawn)
                }
                pen += (g.advance + if (bold) g.offset else 0f) * scale
            } else {
                val main = r?.let { face(it, if (bold) MC_BOLD_FACE else MC_FACE) }
                pen += if (r != null && main != null && main.glyph(cp) != 0) {
                    val size = 10f * scale
                    beginDraw()
                    glyphRun(r, line, cpStart, i, pen, baseline, drawn, main, size, 0f, false, false, 0f)
                    main.advance(main.glyph(cp)) * size
                } else {
                    val size = 8f * scale
                    val uni = r?.let { face(it, FALLBACK_FONT) } ?: continue
                    beginDraw()
                    glyphRun(r, line, cpStart, i, pen, baseline, drawn, uni, size, 0f, false, false, 0f)
                    uni.advance(uni.glyph(cp)) * size
                }
            }
            if (under || strike) selfOverlapping()
            if (under) mcEffect(start + o - scale, top + o + 8f * scale, pen + o, top + o + 9f * scale, drawn)
            if (strike) mcEffect(start + o - scale, top + o + 3.5f * scale, pen + o, top + o + 4.5f * scale, drawn)
        }
        shearX = 0f
    }

    private var mcSheet: PolyBitmap? = null
    private var mcSheetGlyph: PolyGlyphPage.Glyph? = null

    private fun mcPlaced(b: PolyBitmap): PolyGlyphPage.Glyph? {
        val last = mcSheetGlyph
        if (b === mcSheet && last != null && !last.page.retired) return last
        val p = atlasBitmap(b)
        mcSheet = b
        mcSheetGlyph = p
        return p
    }

    private fun mcGlyph(g: McGlyph, x: Float, top: Float, scale: Float, color: Int) {
        val sheet = g.bitmap ?: return
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        val s = state
        val nearest = abs(g.width * scale * s.sx) >= g.pixelWidth && abs(g.height * scale * s.sy) >= g.pixelHeight
        val b = if (nearest) sheet else g.linearBitmap ?: return
        val p = mcPlaced(b)
        begin(p?.page ?: bitmapTexture(b), !nearest, nearest)
        val tint = if (nearest) c else scaleArgb(c or -0x1000000, (c ushr 24) / 255f)
        val x0 = x * s.sx + s.tx
        val y0 = (top + g.top * scale) * s.sy + s.ty
        val tu = if (nearest) (p?.let { it.u1 - it.u0 } ?: 1f) / b.width * NEAREST_TIE_BIAS else 0f
        val tv = if (nearest) (p?.let { it.v1 - it.v0 } ?: 1f) / b.height * NEAREST_TIE_BIAS else 0f
        glyphQuad(
            x0, y0, x0 + g.width * scale * s.sx, y0 + g.height * scale * s.sy,
            (if (nearest) p?.u(g.u0) ?: g.u0 else p?.u0 ?: 0f) + tu,
            (if (nearest) p?.v(g.v0) ?: g.v0 else p?.v0 ?: 0f) + tv,
            (if (nearest) p?.u(g.u1) ?: g.u1 else p?.u1 ?: 1f) + tu,
            (if (nearest) p?.v(g.v1) ?: g.v1 else p?.v1 ?: 1f) + tv, tint,
        )
    }

    override fun image(bitmap: PolyBitmap, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, tint: Int, filterQuality: FilterQuality): Unit =
        imageQuad(bitmap, x, y, w, h, u0, v0, u1, v1, tint, filterQuality, strict = true)

    private fun imageQuad(
        bitmap: PolyBitmap, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, tint: Int, filterQuality: FilterQuality, strict: Boolean,
    ) {
        beginDraw()
        val c = mulAlpha(tint, state.alpha)
        if (invisible(c) || cpuOnly || culled(x, y, x + w, y + h, CULL_PAD)) return
        val g = atlasBitmap(bitmap)
        begin(g?.page ?: bitmapTexture(bitmap), false, filterQuality == FilterQuality.None)
        val x0 = x * state.sx + state.tx
        val y0 = y * state.sy + state.ty
        val x1 = x0 + w * state.sx
        val y1 = y0 + h * state.sy
        val tu = if (filterQuality == FilterQuality.None) ((g?.let { abs(it.u(1f) - it.u(0f)) } ?: 1f) / bitmap.width * NEAREST_TIE_BIAS) else 0f
        val tv = if (filterQuality == FilterQuality.None) ((g?.let { abs(it.v(1f) - it.v(0f)) } ?: 1f) / bitmap.height * NEAREST_TIE_BIAS) else 0f
        val ua = (g?.u(u0) ?: u0) + tu; val va = (g?.v(v0) ?: v0) + tv
        val ub = (g?.u(u1) ?: u1) + tu; val vb = (g?.v(v1) ?: v1) + tv
        var uLo = min(ua, ub); var uHi = max(ua, ub)
        var vLo = min(va, vb); var vHi = max(va, vb)
        if (strict && filterQuality != FilterQuality.None) {
            val hu = (g?.let { abs(it.u(1f) - it.u(0f)) } ?: 1f) / bitmap.width * 0.5f
            val hv = (g?.let { abs(it.v(1f) - it.v(0f)) } ?: 1f) / bitmap.height * 0.5f
            if (min(u0, u1) > STRICT_EDGE_EPSILON) uLo += hu
            if (max(u0, u1) < 1f - STRICT_EDGE_EPSILON) uHi -= hu
            if (min(v0, v1) > STRICT_EDGE_EPSILON) vLo += hv
            if (max(v0, v1) < 1f - STRICT_EDGE_EPSILON) vHi -= hv
            if (uLo > uHi) { val m = (uLo + uHi) / 2f; uLo = m; uHi = m }
            if (vLo > vHi) { val m = (vLo + vHi) / 2f; vLo = m; vHi = m }
        }
        texturedQuad(x, y, w, h, x0, y0, x1, y1, ua, va, ub, vb, tint, uLo, uHi, vLo, vHi)
    }

    private val sliceXd = FloatArray(4)
    private val sliceXt = FloatArray(4)
    private val sliceYd = FloatArray(4)
    private val sliceYt = FloatArray(4)

    private fun slices(d0: Float, d1: Float, t0: Float, t1: Float, lo: Float, hi: Float, outD: FloatArray, outT: FloatArray): Int {
        outD[0] = d0; outT[0] = t0.coerceIn(lo, hi)
        var n = 1
        var last = t1.coerceIn(lo, hi)
        if (t1 != t0) {
            val near = if (t0 < t1) lo else hi
            val far = if (t0 < t1) hi else lo
            if ((near - t0) * (near - t1) < 0f) {
                val d = d0 + (near - t0) / (t1 - t0) * (d1 - d0)
                if (sampledPast(d0, d)) { outD[n] = d; outT[n] = near; n++ } else outT[0] = t0
            }
            if ((far - t0) * (far - t1) < 0f) {
                val d = d0 + (far - t0) / (t1 - t0) * (d1 - d0)
                if (sampledPast(d1, d)) { outD[n] = d; outT[n] = far; n++ } else last = t1
            }
        }
        outD[n] = d1; outT[n] = last
        return n + 1
    }

    private fun sampledPast(end: Float, cut: Float): Boolean {
        val eps = abs(cut - end) * STRICT_CENTER_EPSILON
        if (end <= cut) return ceil(end - 0.5f) + 0.5f < cut - eps
        return floor(end - 0.5f) + 0.5f > cut + eps
    }

    /**
     * A blurred copy of the screen for [backdrop] to sample, covering [width] by [height] device pixels with its
     * rows stored bottom up as framebuffer textures are, or top down when [topDown] is set
     */
    @Suppress("REDUNDANT_VISIBILITY_MODIFIER")
    protected class BackdropSource(
        public val texture: Any,
        public val width: Float,
        public val height: Float,
        public val topDown: Boolean,
    )

    protected open fun backdropSource(): BackdropSource? = null

    override fun backdrop(x: Float, y: Float, w: Float, h: Float, alpha: Float) {
        beginDraw()
        val src = backdropSource() ?: return
        val tint = (alpha.coerceIn(0f, 1f) * 255f).roundToInt() shl 24 or 0xFFFFFF
        if (mulAlpha(tint, state.alpha) ushr 24 == 0 || cpuOnly || src.width <= 0f || src.height <= 0f) return
        begin(src.texture, false)
        val x0 = x * state.sx + state.tx
        val y0 = y * state.sy + state.ty
        val x1 = x0 + w * state.sx
        val y1 = y0 + h * state.sy
        val v0 = if (src.topDown) y0 / src.height else 1f - y0 / src.height
        val v1 = if (src.topDown) y1 / src.height else 1f - y1 / src.height
        texturedQuad(x, y, w, h, x0, y0, x1, y1, x0 / src.width, v0, x1 / src.width, v1, tint)
    }

    /**
     * Draws the texture bound by [begin] over the device rectangle [x0], [y0] to [x1], [y1] which is [x], [y] sized
     * [w] by [h] before transforming, cut to the rounded clip when one is set
     */
    private fun texturedQuad(
        x: Float, y: Float, w: Float, h: Float, x0: Float, y0: Float, x1: Float, y1: Float,
        ua: Float, va: Float, ub: Float, vb: Float, tint: Int,
        uLo: Float = min(ua, ub), uHi: Float = max(ua, ub), vLo: Float = min(va, vb), vHi: Float = max(va, vb),
    ) {
        val rc = state.rclip
        if (rc != null && state.rot == null && state.sx > 0f && state.sy > 0f && x1 > x0 && y1 > y0) {
            val k = rc.box
            val e = 1e-3f
            val covers = x0 <= k[0] + e && y0 <= k[1] + e && x1 >= k[2] - e && y1 >= k[3] - e
            val sf = rc.safe
            val safe = (x0 >= sf[0] && y0 >= sf[1] && x1 <= sf[2] && y1 <= sf[3]) || (x0 >= sf[4] && y0 >= sf[5] && x1 <= sf[6] && y1 <= sf[7])
            if (covers || !safe) {
                if (uLo > min(ua, ub) || uHi < max(ua, ub) || vLo > min(va, vb) || vHi < max(va, vb)) {
                    val nx = slices(x0, x1, ua, ub, uLo, uHi, sliceXd, sliceXt)
                    val ny = slices(y0, y1, va, vb, vLo, vHi, sliceYd, sliceYt)
                    val xd = sliceXd.copyOf(nx); val xt = sliceXt.copyOf(nx)
                    val yd = sliceYd.copyOf(ny); val yt = sliceYt.copyOf(ny)
                    val strictUv = UvMap { fx, fy, out ->
                        out[0] = pieceAt(fx, xd, xt)
                        out[1] = pieceAt(fy, yd, yt)
                    }
                    val cutsX = xd.copyOfRange(1, nx - 1)
                    val cutsY = yd.copyOfRange(1, ny - 1)
                    clippedFill(x, y, w, h, 0f) { fillPolygonCut(it, it.size / 2, tint, strictUv, cutsX, cutsY) }
                    return
                }
                val uv = UvMap { fx, fy, out ->
                    out[0] = (ua + (fx - x0) / (x1 - x0) * (ub - ua)).coerceIn(uLo, uHi)
                    out[1] = (va + (fy - y0) / (y1 - y0) * (vb - va)).coerceIn(vLo, vHi)
                }
                clippedFill(x, y, w, h, 0f) { fillPolygonPx(it, it.size / 2, tint, convex = true, uv = uv, keepBatch = true) }
                return
            }
            state.rclip = rc.parent
            try {
                texturedPieces(x0, y0, x1, y1, ua, va, ub, vb, mulAlpha(tint, state.alpha), uLo, uHi, vLo, vHi)
            } finally {
                state.rclip = rc
            }
            return
        }
        texturedPieces(x0, y0, x1, y1, ua, va, ub, vb, mulAlpha(tint, state.alpha), uLo, uHi, vLo, vHi)
    }

    private fun pieceAt(d: Float, ds: FloatArray, ts: FloatArray): Float {
        if (d <= ds[0]) return ts[0]
        for (i in 0 until ds.size - 1) {
            if (d > ds[i + 1]) continue
            val span = ds[i + 1] - ds[i]
            return if (span > 0f) ts[i] + (d - ds[i]) / span * (ts[i + 1] - ts[i]) else ts[i + 1]
        }
        return ts[ds.size - 1]
    }

    private fun fillPolygonCut(p: FloatArray, n: Int, color: Int, uv: UvMap, cutsX: FloatArray, cutsY: FloatArray) {
        if (n < 3) return
        val c = mulAlpha(color, state.alpha)
        if (c ushr 24 == 0) return
        val clockwise = signedArea(p, n) > 0f
        val inner = floats(0, n * 2)
        val outer = floats(1, n * 2)
        offsetPolygon(p, n, if (clockwise) 1f else -1f, inner, outer)
        val solid = (c ushr 24).toFloat()
        fun corner(src: FloatArray, i: Int, alpha: Float, out: FloatArray, at: Int) {
            out[at] = src[i * 2]; out[at + 1] = src[i * 2 + 1]; out[at + 2] = alpha
        }
        val tri = FloatArray(9)
        for (i in 1 until n - 1) {
            corner(inner, 0, solid, tri, 0); corner(inner, i, solid, tri, 3); corner(inner, i + 1, solid, tri, 6)
            emitCut(tri, c, uv, cutsX, cutsY)
        }
        if (!antiAliased) return
        for (i in 0 until n) {
            val j = (i + 1) % n
            corner(inner, i, solid, tri, 0); corner(outer, i, 0f, tri, 3); corner(outer, j, 0f, tri, 6)
            emitCut(tri, c, uv, cutsX, cutsY)
            corner(inner, i, solid, tri, 0); corner(outer, j, 0f, tri, 3); corner(inner, j, solid, tri, 6)
            emitCut(tri, c, uv, cutsX, cutsY)
        }
    }

    private fun emitCut(tri: FloatArray, color: Int, uv: UvMap, cutsX: FloatArray, cutsY: FloatArray) {
        var parts = listOf(tri)
        for (axis in 0..1) for (at in if (axis == 0) cutsX else cutsY) {
            val next = ArrayList<FloatArray>(parts.size * 2)
            for (part in parts) {
                for (high in booleanArrayOf(false, true)) {
                    val half = halfPlane(part, axis, at, high)
                    if (half.size >= 9) next += half
                }
            }
            parts = next
        }
        val rgb = color and 0xFFFFFF
        val st = FloatArray(2)
        for (part in parts) {
            val m = part.size / 3
            fun put(i: Int) {
                uv.map(part[i * 3], part[i * 3 + 1], st)
                val a = (part[i * 3 + 2] + 0.5f).toInt().coerceIn(0, 255)
                vertex(part[i * 3], part[i * 3 + 1], st[0], st[1], (a shl 24) or rgb)
            }
            for (i in 1 until m - 1) {
                put(0); put(i); put(i + 1); put(i + 1)
            }
        }
    }

    private fun halfPlane(poly: FloatArray, axis: Int, at: Float, high: Boolean): FloatArray {
        val n = poly.size / 3
        val sign = if (high) 1f else -1f
        val out = FloatArray((n + 1) * 3)
        var k = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ci = (poly[i * 3 + axis] - at) * sign
            val cj = (poly[j * 3 + axis] - at) * sign
            if (ci >= 0f) {
                out[k++] = poly[i * 3]; out[k++] = poly[i * 3 + 1]; out[k++] = poly[i * 3 + 2]
            }
            if ((ci > 0f && cj < 0f) || (ci < 0f && cj > 0f)) {
                val t = ci / (ci - cj)
                for (f in 0..2) out[k++] = poly[i * 3 + f] + (poly[j * 3 + f] - poly[i * 3 + f]) * t
            }
        }
        return out.copyOf(k)
    }

    private fun texturedPieces(
        x0: Float, y0: Float, x1: Float, y1: Float, ua: Float, va: Float, ub: Float, vb: Float, c: Int, uLo: Float, uHi: Float, vLo: Float, vHi: Float,
    ) {
        if (uLo <= min(ua, ub) && uHi >= max(ua, ub) && vLo <= min(va, vb) && vHi >= max(va, vb)) return quadPx(x0, y0, x1, y1, ua, va, ub, vb, c)
        val xd = sliceXd; val xt = sliceXt; val yd = sliceYd; val yt = sliceYt
        val nx = slices(x0, x1, ua, ub, uLo, uHi, xd, xt)
        val ny = slices(y0, y1, va, vb, vLo, vHi, yd, yt)
        for (pass in 0..1) for (j in 0 until ny - 1) for (i in 0 until nx - 1) {
            if (xd[i] == xd[i + 1] || yd[j] == yd[j + 1]) continue
            if ((xt[i] != xt[i + 1] && yt[j] != yt[j + 1]) != (pass == 0)) continue
            quadPx(xd[i], yd[j], xd[i + 1], yd[j + 1], xt[i], yt[j], xt[i + 1], yt[j + 1], c)
        }
    }

    private val shadowXs = FloatArray(4)
    private val shadowYs = FloatArray(4)
    private val shadowUs = FloatArray(4)
    private val shadowVs = FloatArray(4)

    override fun shadow(x: Float, y: Float, w: Float, h: Float, radius: Float, shadow: PolyShadow): Unit =
        shadowWithHole(x, y, w, h, radius, shadow, 0f, 0f, 0f, 0f, 0f)

    override fun shadowWithHole(x: Float, y: Float, w: Float, h: Float, radius: Float, shadow: PolyShadow, holeX: Float, holeY: Float, holeW: Float, holeH: Float, holeRadius: Float) {
        beginDraw()
        val s = scaleAvg()
        val color = shadow.color.argb
        val spread = shadow.spread
        val blur = shadow.blur
        if (color ushr 24 == 0 || s <= 0f) return
        val bx = x - spread + shadow.offsetX
        val by = y - spread + shadow.offsetY
        val bw = w + spread * 2f
        val bh = h + spread * 2f
        val rad = if (radius > 0f) max(0f, radius + spread) else 0f
        val sigmaQ = if (blur > 0f) ((blur * s * 0.57735f + 0.5f) * 2f).roundToInt() else 0
        if (sigmaQ == 0 || bw <= 0f || bh <= 0f) return rect(bx, by, bw, bh, color, rad)
        val rPx = min(rad * s, min(bw, bh) * s / 2f).roundToInt()
        val m = lastMask?.takeIf { rPx == lastMaskR && sigmaQ == lastMaskQ } ?: shadowMask(rPx, sigmaQ).also { lastMask = it; lastMaskR = rPx; lastMaskQ = sigmaQ }
        val e = m.extent / s
        val x0 = bx - e
        val y0 = by - e
        val x3 = bx + bw + e
        val y3 = by + bh + e
        if (culled(x0, y0, x3, y3, CULL_PAD)) return
        val kx =min(m.slice, (x3 - x0) * s / 2f)
        val ky = min(m.slice, (y3 - y0) * s / 2f)
        val mw = m.width
        val xs = shadowXs
        val ys = shadowYs
        val us = shadowUs
        val vs = shadowVs
        xs[0] = x0; xs[1] = x0 + kx / s; xs[2] = x3 - kx / s; xs[3] = x3
        ys[0] = y0; ys[1] = y0 + ky / s; ys[2] = y3 - ky / s; ys[3] = y3
        us[0] = 0f; us[1] = kx / mw; us[2] = 1f - kx / mw; us[3] = 1f
        vs[0] = 0f; vs[1] = ky / mw; vs[2] = 1f - ky / mw; vs[3] = 1f
        val holes = if (holeW > 0f && holeH > 0f) {
            val p = 0.45f / s
            val hr = min(holeRadius, min(holeW, holeH) / 2f)
            FloatArray(4 * HOLE_STEPS) {
                val a = (it / 4) * (Math.PI.toFloat() / 2f) / (HOLE_STEPS - 1)
                val ix = hr * (1f - cos(a)) + p
                val iy = hr * (1f - sin(a)) + p
                when (it % 4) { 0 -> holeX + ix; 1 -> holeY + iy; 2 -> holeX + holeW - ix; else -> holeY + holeH - iy }
            }
        } else null
        for (j in 0 until 3) for (i in 0 until 3) {
            if (xs[i + 1] <= xs[i] || ys[j + 1] <= ys[j]) continue
            if (holes == null) imageQuad(m.bitmap, xs[i], ys[j], xs[i + 1] - xs[i], ys[j + 1] - ys[j], us[i], vs[j], us[i + 1], vs[j + 1], color, FilterQuality.Low, strict = false)
            else shadowPiece(m.bitmap, xs[i], ys[j], xs[i + 1], ys[j + 1], xs[i], ys[j], xs[i + 1], ys[j + 1], us[i], vs[j], us[i + 1], vs[j + 1], color, holes, 0)
        }
    }

    private fun shadowPiece(
        bmp: PolyBitmap, l: Float, t: Float, r: Float, b: Float,
        cx0: Float, cy0: Float, cx1: Float, cy1: Float, u0: Float, v0: Float, u1: Float, v1: Float,
        color: Int, holes: FloatArray, k: Int,
    ) {
        if (r <= l || b <= t) return
        if (k == holes.size) {
            fun u(x: Float) = u0 + (x - cx0) / (cx1 - cx0) * (u1 - u0)
            fun v(y: Float) = v0 + (y - cy0) / (cy1 - cy0) * (v1 - v0)
            imageQuad(bmp, l, t, r - l, b - t, u(l), v(t), u(r), v(b), color, FilterQuality.Low, strict = false)
            return
        }
        val hl = holes[k]; val ht = holes[k + 1]; val hr = holes[k + 2]; val hb = holes[k + 3]
        val n = k + 4
        if (hr <= l || hl >= r || hb <= t || ht >= b) return shadowPiece(bmp, l, t, r, b, cx0, cy0, cx1, cy1, u0, v0, u1, v1, color, holes, n)
        val mt = max(t, ht); val mb = min(b, hb)
        shadowPiece(bmp, l, t, r, mt, cx0, cy0, cx1, cy1, u0, v0, u1, v1, color, holes, n)
        shadowPiece(bmp, l, mb, r, b, cx0, cy0, cx1, cy1, u0, v0, u1, v1, color, holes, n)
        shadowPiece(bmp, l, mt, max(l, hl), mb, cx0, cy0, cx1, cy1, u0, v0, u1, v1, color, holes, n)
        shadowPiece(bmp, min(r, hr), mt, r, mb, cx0, cy0, cx1, cy1, u0, v0, u1, v1, color, holes, n)
    }

    private var keyScratch = FloatArray(32)
    private var keyTags = ByteArray(32)
    private var keySize = 0

    protected fun keyAdd(v: Float): Unit = keyTagged(v, KEY_EXACT)

    private fun keyTagged(v: Float, tag: Byte) {
        if (keySize == keyScratch.size) {
            keyScratch = keyScratch.copyOf(keySize * 2)
            keyTags = keyTags.copyOf(keySize * 2)
        }
        keyTags[keySize] = tag
        keyScratch[keySize++] = v
    }

    private fun keyPairs(values: FloatArray, count: Int = values.size) {
        for (i in 0 until count) keyTagged(values[i], if (i and 1 == 0) KEY_X else KEY_Y)
    }

    protected open fun writeStateKey() {
        val s = state
        keyAdd(s.sx); keyAdd(s.sy); keyTagged(s.tx, KEY_X); keyTagged(s.ty, KEY_Y); keyAdd(s.alpha); keyAdd(s.glyphScale)
        s.rot?.let { keyAdd(-2f); for (v in it) keyAdd(v) }
        s.proj?.let { keyAdd(-4f); for (v in it) keyAdd(v) }
        val clip = s.clip
        if (clip != null) for (i in clip.indices) keyTagged(clip[i], if (i and 1 == 0) KEY_CX else KEY_CY) else keyAdd(-1f)
        var c = s.rclip
        while (c != null) {
            keyPairs(c.box, 4)
            for (i in 4 until c.box.size) keyAdd(c.box[i])
            if (!c.rrect) keyPairs(c.edge)
            c = c.parent
        }
        var h = s.hole
        while (h != null) { keyAdd(-3f); keyPairs(h.edge); h = h.parent }
        var g = s.region
        while (g != null) {
            keyAdd(if (g.box != null) -6f else -5f)
            keyAdd(if (g.evenOdd) 1f else 0f)
            for (n in g.sizes) keyAdd(n.toFloat())
            keyPairs(g.src)
            g = g.parent
        }
        var m = s.mask
        while (m != null) {
            keyAdd(if (m.fade.vertical) 1f else 0f)
            val axis = if (m.fade.vertical) KEY_Y else KEY_X
            for (v in m.fade.stops) keyTagged(v, axis)
            for (v in m.fade.alphas) keyAdd(v)
            m.fade.colors?.let { for (v in it) keyAdd(v.toFloat()) }
            m = m.parent
        }
    }

    private class MovableKey(val values: FloatArray, val tags: ByteArray, val tx: Float, val ty: Float)

    public fun movableKey(): Any? {
        val s = state
        if (s.rot != null) return null
        keySize = 0
        writeStateKey()
        return MovableKey(keyScratch.copyOf(keySize), keyTags.copyOf(keySize), s.tx, s.ty)
    }

    public fun offsetFrom(key: Any, lx: Float, ly: Float, out: FloatArray): Boolean = offsetFrom(key, lx, ly, out, false)

    internal fun offsetFrom(key: Any, lx: Float, ly: Float, out: FloatArray, freeClip: Boolean): Boolean {
        val s = state
        if (key !is MovableKey || s.rot != null) return false
        val tx = s.tx + lx * s.sx
        val ty = s.ty + ly * s.sy
        val dx = round(tx - key.tx)
        val dy = round(ty - key.ty)
        if (!near(key.tx + dx, tx) || !near(key.ty + dy, ty)) return false
        keySize = 0
        writeStateKey()
        val v = key.values
        val tags = key.tags
        if (keySize != v.size) return false
        val cur = keyScratch
        val curTags = keyTags
        var seenX = false
        var seenY = false
        for (i in 0 until keySize) {
            if (tags[i] != curTags[i]) return false
            val tag = when (tags[i]) {
                KEY_CX -> if (freeClip) continue else KEY_X
                KEY_CY -> if (freeClip) continue else KEY_Y
                else -> tags[i]
            }
            when (tag) {
                KEY_X -> if (seenX) { if (!near(v[i] + dx, cur[i])) return false } else seenX = true
                KEY_Y -> if (seenY) { if (!near(v[i] + dy, cur[i])) return false } else seenY = true
                else -> if (v[i].toRawBits() != cur[i].toRawBits()) return false
            }
        }
        out[0] = dx
        out[1] = dy
        return true
    }

    private fun near(a: Float, b: Float) = abs(a - b) <= 1e-3f + abs(b) * 1e-6f

    public fun shiftedState(saved: Any, dx: Float, dy: Float): Any? {
        val s = saved as State
        if (s.rot != null) return null
        return State(
            s.sx, s.sy, s.tx + dx, s.ty + dy, s.alpha, s.clip?.let { shiftPairs(it, dx, dy, it.size) }, s.rclip?.let { shiftClip(it, dx, dy) },
            s.mask?.let { shiftMask(it, dx, dy) }, s.glyphScale, null, s.hole?.let { shiftClip(it, dx, dy) },
        ).also { it.region = s.region?.let { g -> shiftRegion(g, dx, dy) } }
    }

    private fun shiftRegion(g: Region, dx: Float, dy: Float): Region = Region(
        Array(g.pieces.size) {
            val p = g.pieces[it]
            RegionPiece(
                Array(p.cover.size) { e -> shiftPairs(p.cover[e], dx, dy, 4) }, p.invLen, Array(p.inner.size) { e -> shiftPairs(p.inner[e], dx, dy, 4) },
                Array(p.outer.size) { e -> shiftPairs(p.outer[e], dx, dy, 4) }, p.l + dx, p.t + dy, p.r + dx, p.b + dy,
            )
        },
        g.box?.let { shiftPairs(it, dx, dy, 4) }, shiftPairs(g.bounds, dx, dy, 4), FloatArray(g.bottoms.size) { g.bottoms[it] + dy }, shiftPairs(g.src, dx, dy, g.src.size), g.sizes, g.evenOdd,
        g.parent?.let { shiftRegion(it, dx, dy) },
    )

    private fun shiftPairs(a: FloatArray, dx: Float, dy: Float, count: Int): FloatArray {
        if (a === NONE || a === NO_SAFE) return a
        val out = a.copyOf()
        for (i in 0 until count) out[i] += if (i and 1 == 0) dx else dy
        return out
    }

    private fun shiftClip(c: RClip, dx: Float, dy: Float): RClip = RClip(
        shiftPairs(c.edge, dx, dy, c.edge.size), shiftPairs(c.inner, dx, dy, c.inner.size), shiftPairs(c.outer, dx, dy, c.outer.size),
        c.parent?.let { shiftClip(it, dx, dy) }, shiftPairs(c.safe, dx, dy, c.safe.size), shiftPairs(c.box, dx, dy, min(4, c.box.size)), c.rrect,
    )

    private fun shiftMask(m: Mask, dx: Float, dy: Float): Mask {
        val f = m.fade
        val d = if (f.vertical) dy else dx
        return Mask(PolyFade(f.vertical, FloatArray(f.stops.size) { f.stops[it] + d }, f.alphas, f.colors), m.parent?.let { shiftMask(it, dx, dy) })
    }

    public open fun shiftedCapture(captured: Any, dx: Float, dy: Float): Any? = null

    protected fun shiftedBoxes(b: FloatArray, dx: Float, dy: Float): FloatArray {
        if (b.isEmpty()) return b
        val out = b.copyOf()
        for (i in out.indices) {
            val k = i % BOX
            if (k < BOX_SELF) out[i] += if (k and 1 == 0) dx else dy
        }
        return out
    }

    public fun swapState(saved: Any): Any {
        val prev = state
        prev.pooled = false
        state = (saved as State).copy()
        return prev
    }

    public fun putState(saved: Any) {
        state = saved as State
    }

    public fun stateKey(): Any {
        keySize = 0
        writeStateKey()
        return StateKey(keyScratch.copyOf(keySize))
    }

    public fun sameState(key: Any): Boolean {
        if (key !is StateKey) return false
        keySize = 0
        writeStateKey()
        return Arrays.equals(key.values, 0, key.values.size, keyScratch, 0, keySize)
    }

    private class StateKey(val values: FloatArray) {
        override fun equals(other: Any?): Boolean = other is StateKey && values.contentEquals(other.values)
        override fun hashCode(): Int = values.contentHashCode()
    }

    public fun saveState(): Any = state.copy()

    public fun restoreState(saved: Any) { state = (saved as State).copy() }

    private val statePool = arrayOfNulls<State>(STATE_POOL)
    private var statePoolSize = 0

    override fun save() {
        val s = state
        stack.addLast(s)
        val n = statePoolSize
        if (n == 0) {
            state = s.copy().also { it.pooled = true }
            return
        }
        statePoolSize = n - 1
        val p = statePool[n - 1]!!
        statePool[n - 1] = null
        state = p.setFrom(s)
    }

    override fun restore() {
        val l = layers.lastOrNull()
        if (l != null && l.depth == stack.size) {
            endLayer(l)
            if (l.resume >= 0) wantBlend = l.resume
        }
        val done = state
        state = stack.removeLast()
        if (stack.size < discardFrom) discardFrom = Int.MAX_VALUE
        if (done.pooled && done !== state && statePoolSize < STATE_POOL) {
            done.release()
            statePool[statePoolSize++] = done
        }
    }

    override fun saveLayerAlpha(alpha: Float) {
        save()
        if (alpha >= 1f || discarding) return
        val carried = wantBlend
        if (alpha <= 0f && zeroSourceKeepsDst(carried)) {
            state.alpha = 0f
            discardFrom = stack.size
            return
        }
        val a = max(alpha, 0f)
        if (!layered || carried != PolyRenderer.BLEND_SRC_OVER && !layerBlendSupported(carried)) {
            state.alpha *= a
            return
        }
        clipImpure()
        val blended = carried != PolyRenderer.BLEND_SRC_OVER
        layers.add(Layer(stack.size, state.alpha * a, count, boxCount, composite = carried, resume = if (blended) carried else -1))
        wantBlend = PolyRenderer.BLEND_SRC_OVER
        state.alpha = 1f
    }

    override fun saveLayerBlend(alpha: Float, mode: Int) {
        if (discarding) return save()
        val composite = if (layerBlendSupported(mode)) mode else PolyRenderer.BLEND_SRC_OVER
        if (!layered || alpha <= 0f && zeroSourceKeepsDst(composite)) return saveLayerAlpha(alpha)
        save()
        layers.add(Layer(stack.size, state.alpha * max(alpha, 0f), count, boxCount, isolated = true, composite = composite))
        state.alpha = 1f
    }

    override fun saveAlpha(alpha: Float) {
        save()
        state.alpha *= alpha.coerceIn(0f, 1f)
    }

    override fun saveLayerEffect(x: Float, y: Float, w: Float, h: Float, radius: Float, chain: PolyEffectChain, alpha: Float): Unit =
        saveLayerEffect(x, y, w, h, radius, chain, alpha, PolyRenderer.BLEND_SRC_OVER)

    override fun saveLayerEffect(x: Float, y: Float, w: Float, h: Float, radius: Float, chain: PolyEffectChain, alpha: Float, mode: Int) {
        if (discarding) return save()
        val s = state
        if (chain.isEmpty || !layered || alpha <= 0f) return if (mode == PolyRenderer.BLEND_SRC_OVER) saveLayerAlpha(alpha) else saveLayerBlend(alpha, mode)
        save()
        val m = s.rot
        val box = if (m == null) {
            val ax = x * s.sx + s.tx
            val bx = (x + w) * s.sx + s.tx
            val ay = y * s.sy + s.ty
            val by = (y + h) * s.sy + s.ty
            val scale = (abs(s.sx) + abs(s.sy)) / 2f
            EffectBox(chain, min(ax, bx), min(ay, by), max(ax, bx), max(ay, by), radius * scale, scale)
        } else {
            val p = floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
            transformInPlace(p)
            toDevice(p)
            var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
            for (i in p.indices step 2) {
                l = min(l, p[i]); r = max(r, p[i])
                t = min(t, p[i + 1]); b = max(b, p[i + 1])
            }
            val scale = (abs(s.sx) * hypot(m[0], m[2]) + abs(s.sy) * hypot(m[1], m[3])) / 2f
            EffectBox(chain, l, t, r, b, if (axisAligned(p)) radius * scale else 0f, scale)
        }
        clipImpure()
        val composite = if (layerBlendSupported(mode)) mode else PolyRenderer.BLEND_SRC_OVER
        layers.add(Layer(stack.size, s.alpha * alpha, count, boxCount, box, composite = composite))
        state.alpha = 1f
    }

    private fun endLayer(l: Layer) {
        val mark = l.mark
        val plain = !l.blended && l.composite == PolyRenderer.BLEND_SRC_OVER
        if (l.isolated && plain && l.alpha >= 1f && (mark == null || plainFade(mark, 1f))) {
            layers.removeAt(layers.lastIndex)
            return
        }
        clipImpure()
        val e = l.effect
        if (e != null || l.composite != PolyRenderer.BLEND_SRC_OVER) layers.getOrNull(layers.lastIndex - 1)?.blended = true
        if (e != null) {
            flush()
            layers.removeAt(layers.lastIndex)
            val outer = drawBlend
            drawBlend = l.composite
            try {
                layer(l.mark!!, l.alpha, e.chain, e.x0, e.y0, e.x1, e.y1, e.radius, e.scale)
            } finally {
                drawBlend = outer
            }
            if (boxCount <= l.box) return
            collapseBoxes(l.box, true)
            val pad = e.chain.outset * e.scale
            val o = l.box * BOX
            boxes[o] -= pad; boxes[o + 1] -= pad; boxes[o + 2] += pad; boxes[o + 3] += pad
            return
        }
        if (plain && !overlapping(l.box) && (mark == null || plainFade(mark, l.alpha))) {
            layers.removeAt(layers.lastIndex)
            for (i in (if (mark == null) l.start else 0) until count) colors[i] = if (batchPremul) scaleArgb(colors[i], l.alpha) else mulAlpha(colors[i], l.alpha)
            return
        }
        flush()
        layers.removeAt(layers.lastIndex)
        val outer = drawBlend
        drawBlend = l.composite
        try {
            layer(l.mark!!, l.alpha)
        } finally {
            drawBlend = outer
        }
        collapseBoxes(l.box, l.composite != PolyRenderer.BLEND_SRC_OVER)
    }

    private fun plainFade(mark: Any, alpha: Float): Boolean {
        val outer = drawBlend
        drawBlend = PolyRenderer.BLEND_SRC_OVER
        try {
            return fade(mark, alpha)
        } finally {
            drawBlend = outer
        }
    }

    override fun deviceTransform(): FloatArray? = if (state.rot != null) null else floatArrayOf(state.sx, state.sy, state.tx, state.ty)

    override fun deviceScale(): Float {
        val s = state
        val m = s.rot ?: return max(abs(s.sx), abs(s.sy))
        return max(abs(s.sx) * hypot(m[0], m[2]), abs(s.sy) * hypot(m[1], m[3]))
    }

    override fun saveLayerFade(fade: PolyFade) {
        save()
        var vertical = fade.vertical
        var s = if (vertical) state.sy else state.sx
        var t = if (vertical) state.ty else state.tx
        val m = state.rot
        if (m != null) {
            val ax = if (vertical) m[1] else m[0]
            val ay = if (vertical) m[3] else m[2]
            val tx = state.tx; val ty = state.ty
            vertical = abs(ay) >= abs(ax)
            s *= if (vertical) ay else ax
            t = if (vertical) m[2] * tx + m[3] * ty + m[5] else m[0] * tx + m[1] * ty + m[4]
        }
        val stops = FloatArray(fade.stops.size) { fade.stops[it] * s + t }
        val px = if (s >= 0f) PolyFade(vertical, stops, fade.alphas, fade.colors)
        else PolyFade(vertical, stops.reversedArray(), fade.alphas.reversedArray(), fade.colors?.reversedArray())
        state.mask = Mask(px, state.mask)
    }

    override fun translate(dx: Float, dy: Float) {
        state.tx += dx * state.sx
        state.ty += dy * state.sy
    }

    override fun scale(sx: Float, sy: Float) {
        state.sx *= sx
        state.sy *= sy
    }

    override fun rotate(degrees: Float) {
        val s = state
        if (degrees % 360f == 0f || s.sx == 0f || s.sy == 0f) return
        val rad = Math.toRadians(degrees.toDouble())
        val cs = cos(rad).toFloat()
        val sn = sin(rad).toFloat()
        val lb = -sn * s.sx / s.sy
        val lc = sn * s.sy / s.sx
        val le = s.tx - cs * s.tx - lb * s.ty
        val lf = s.ty - lc * s.tx - cs * s.ty
        val m = s.rot
        s.rot = if (m == null) floatArrayOf(cs, lb, lc, cs, le, lf)
        else floatArrayOf(
            m[0] * cs + m[1] * lc, m[0] * lb + m[1] * cs, m[2] * cs + m[3] * lc, m[2] * lb + m[3] * cs,
            m[0] * le + m[1] * lf + m[4], m[2] * le + m[3] * lf + m[5],
        )
    }

    override fun skew(sx: Float, sy: Float) {
        val s = state
        if (sx == 0f && sy == 0f || s.sx == 0f || s.sy == 0f) return
        val lb = sx * s.sx / s.sy
        val lc = sy * s.sy / s.sx
        val le = -lb * s.ty
        val lf = -lc * s.tx
        val m = s.rot
        s.rot = if (m == null) floatArrayOf(1f, lb, lc, 1f, le, lf)
        else floatArrayOf(
            m[0] + m[1] * lc, m[0] * lb + m[1], m[2] + m[3] * lc, m[2] * lb + m[3],
            m[0] * le + m[1] * lf + m[4], m[2] * le + m[3] * lf + m[5],
        )
    }

    internal fun rotate3d(rotationX: Float, rotationY: Float, cameraDistance: Float) {
        val s = state
        if (rotationX % 360f == 0f && rotationY % 360f == 0f || s.sx == 0f || s.sy == 0f) return
        val rx = Math.toRadians(rotationX.toDouble())
        val ry = Math.toRadians(rotationY.toDouble())
        val cx = cos(rx).toFloat(); val snx = sin(rx).toFloat()
        val cy = cos(ry).toFloat(); val sny = sin(ry).toFloat()
        val depth = max(abs(cameraDistance) * CAMERA_UNIT, 1f)
        val m = s.rot ?: floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f).also { s.rot = it }
        val a0 = m[0] * s.sx; val a1 = m[1] * s.sy; val a2 = m[0] * s.tx + m[1] * s.ty + m[4]
        val a3 = m[2] * s.sx; val a4 = m[3] * s.sy; val a5 = m[2] * s.tx + m[3] * s.ty + m[5]
        val det = a0 * a4 - a1 * a3
        if (det == 0f || det.isNaN()) return
        val i0 = a4 / det; val i1 = -a1 / det; val i3 = -a3 / det; val i4 = a0 / det
        val i2 = -(i0 * a2 + i1 * a5); val i5 = -(i3 * a2 + i4 * a5)
        val a = floatArrayOf(a0, a1, a2, a3, a4, a5, 0f, 0f, 1f)
        val h = floatArrayOf(cy, 0f, 0f, snx * sny, cx, 0f, sny * cx / depth, -snx / depth, 1f)
        val inv = floatArrayOf(i0, i1, i2, i3, i4, i5, 0f, 0f, 1f)
        val local = mul3(mul3(a, h), inv)
        s.proj = s.proj?.let { mul3(it, local) } ?: local
    }

    private fun mul3(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) {
        val r = it / 3 * 3
        val c = it % 3
        a[r] * b[c] + a[r + 1] * b[c + 3] + a[r + 2] * b[c + 6]
    }

    private fun toDevice(p: FloatArray) {
        val m = state.rot ?: return
        val q = state.proj
        for (i in p.indices step 2) {
            val x = m[0] * p[i] + m[1] * p[i + 1] + m[4]
            val y = m[2] * p[i] + m[3] * p[i + 1] + m[5]
            if (q == null) { p[i] = x; p[i + 1] = y; continue }
            val w = max(q[6] * x + q[7] * y + q[8], MIN_W)
            p[i] = (q[0] * x + q[1] * y + q[2]) / w
            p[i + 1] = (q[3] * x + q[4] * y + q[5]) / w
        }
    }

    private fun axisAligned(p: FloatArray): Boolean {
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            if (abs(p[i * 2] - p[j * 2]) > ALIGN_EPSILON && abs(p[i * 2 + 1] - p[j * 2 + 1]) > ALIGN_EPSILON) return false
        }
        return true
    }

    private fun polygonClip(p: FloatArray) {
        orient(p)
        val n = p.size / 2
        for (i in 0 until n) {
            val a = i * 2; val c = (i + 1) % n * 2; val d = (i + 2) % n * 2
            if ((p[c] - p[a]) * (p[d + 1] - p[c + 1]) - (p[c + 1] - p[a + 1]) * (p[d] - p[c]) < -0.01f) return
        }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in p.indices step 2) {
            l = min(l, p[i]); r = max(r, p[i])
            t = min(t, p[i + 1]); b = max(b, p[i + 1])
        }
        val inner = FloatArray(p.size); val outer = FloatArray(p.size)
        offsetPolygon(p, n, 1f, inner, outer)
        state.rclip = RClip(p, inner, outer, state.rclip, NO_SAFE, floatArrayOf(l, t, r, b, 0f, 0f), rrect = false)
    }

    override fun animatedScale(sx: Float, sy: Float) {
        scale(sx, sy)
        state.glyphScale *= (abs(sx) + abs(sy)) / 2f
    }

    override fun clipRect(x: Float, y: Float, w: Float, h: Float) {
        var x0 = x * state.sx + state.tx
        var y0 = y * state.sy + state.ty
        var x1 = x0 + w * state.sx
        var y1 = y0 + h * state.sy
        var turned: FloatArray? = null
        if (state.rot != null) {
            val p = floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
            toDevice(p)
            x0 = Float.MAX_VALUE; y0 = Float.MAX_VALUE; x1 = -Float.MAX_VALUE; y1 = -Float.MAX_VALUE
            for (i in 0 until 4) {
                x0 = min(x0, p[i * 2]); y0 = min(y0, p[i * 2 + 1]); x1 = max(x1, p[i * 2]); y1 = max(y1, p[i * 2 + 1])
            }
            if (!axisAligned(p)) turned = p
        }
        if (x0 > x1) { val t = x0; x0 = x1; x1 = t }
        if (y0 > y1) { val t = y0; y0 = y1; y1 = t }
        val prev = state.clip
        val next = if (prev == null) floatArrayOf(floor(x0), floor(y0), ceil(x1), ceil(y1))
        else floatArrayOf(max(floor(x0), prev[0]), max(floor(y0), prev[1]), min(ceil(x1), prev[2]), min(ceil(y1), prev[3]))
        state.clip = next
        if (turned != null) polygonClip(turned)
        val log = clipLogs.lastOrNull() ?: return
        if (prev == null) return
        val pi = if (prev === log.entry) null else log.inner[prev] ?: return
        log.inner[next] = if (pi == null) floatArrayOf(floor(x0), floor(y0), ceil(x1), ceil(y1))
        else floatArrayOf(max(floor(x0), pi[0]), max(floor(y0), pi[1]), min(ceil(x1), pi[2]), min(ceil(y1), pi[3]))
    }

    override fun clipRRect(x: Float, y: Float, w: Float, h: Float, radius: Float) {
        clipRect(x, y, w, h)
        if (radius <= 0f) return
        if (state.rot != null) {
            val p = rrectPoints(x, y, w, h, radius)
            toDevice(p)
            return polygonClip(p)
        }
        val s = state
        val ox = x * s.sx + s.tx
        val oy = y * s.sy + s.ty
        val parent = s.rclip
        val probe = rrectProbe
        probe.set(s.sx, s.sy, w, h, radius, antiAliased)
        val shape = rrectShapes[probe]
        if (shape == null) {
            val made = rrectClip(x, y, w, h, radius, parent)
            rrectShapes[RRectKey().also { it.set(s.sx, s.sy, w, h, radius, antiAliased) }] = RRectShape(made, ox, oy)
            s.rclip = made
            return
        }
        val last = shape.last
        if (shape.lastX.toRawBits() == ox.toRawBits() && shape.lastY.toRawBits() == oy.toRawBits() && last.parent === parent) {
            s.rclip = last
            return
        }
        val base = shape.base
        val dx = ox - shape.baseX
        val dy = oy - shape.baseY
        val moved = RClip(
            shiftPairs(base.edge, dx, dy, base.edge.size), shiftPairs(base.inner, dx, dy, base.inner.size), shiftPairs(base.outer, dx, dy, base.outer.size),
            parent, shiftPairs(base.safe, dx, dy, base.safe.size), shiftPairs(base.box, dx, dy, 4),
        )
        shape.last = moved
        shape.lastX = ox
        shape.lastY = oy
        s.rclip = moved
    }

    private fun rrectClip(x: Float, y: Float, w: Float, h: Float, radius: Float, parent: RClip?): RClip {
        val p = rrectPoints(x, y, w, h, radius)
        orient(p)
        val n = p.size / 2
        val inner = FloatArray(p.size); val outer = FloatArray(p.size)
        offsetPolygon(p, n, 1f, inner, outer)
        val box = boxPx(x, y, w, h, radius)
        val l = box[0]; val t = box[1]; val rt = box[2]; val b = box[3]
        val rx = box[4] + 1f; val ry = box[5] + 1f
        val safe = floatArrayOf(l + rx, t + 1f, rt - rx, b - 1f, l + 1f, t + ry, rt - 1f, b - ry)
        return RClip(p, inner, outer, parent, safe, box)
    }

    private class RRectShape(val base: RClip, val baseX: Float, val baseY: Float) {
        var last: RClip = base
        var lastX: Float = baseX
        var lastY: Float = baseY
    }

    private class RRectKey {
        var sx = 0f
        var sy = 0f
        var w = 0f
        var h = 0f
        var radius = 0f
        var aa = true
        var hash = 0

        fun set(sx: Float, sy: Float, w: Float, h: Float, radius: Float, aa: Boolean) {
            this.sx = sx; this.sy = sy; this.w = w; this.h = h; this.radius = radius; this.aa = aa
            hash = ((((sx.toRawBits() * 31 + sy.toRawBits()) * 31 + w.toRawBits()) * 31 + h.toRawBits()) * 31 + radius.toRawBits()) * 2 + (if (aa) 1 else 0)
        }

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is RRectKey && other.hash == hash && other.aa == aa &&
            other.sx.toRawBits() == sx.toRawBits() && other.sy.toRawBits() == sy.toRawBits() && other.w.toRawBits() == w.toRawBits() &&
            other.h.toRawBits() == h.toRawBits() && other.radius.toRawBits() == radius.toRawBits()
    }

    private val rrectProbe = RRectKey()
    private val rrectShapes = object : LinkedHashMap<RRectKey, RRectShape>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RRectKey, RRectShape>?) = size > MAX_RRECT_SHAPES
    }

    override fun clipPolygon(points: FloatArray) {
        super.clipPolygon(points)
        val p = FloatArray(points.size)
        var k = 0
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        val pt = floatArrayOf(0f, 0f)
        for (i in points.indices step 2) {
            pt[0] = points[i] * state.sx + state.tx
            pt[1] = points[i + 1] * state.sy + state.ty
            toDevice(pt)
            val x = pt[0]
            val y = pt[1]
            // Coverage is measured from each edge, which repeated points don't give a direction
            if (k > 0 && abs(x - p[k - 2]) < 0.01f && abs(y - p[k - 1]) < 0.01f) continue
            p[k++] = x; p[k++] = y
            l = min(l, x); t = min(t, y); r = max(r, x); b = max(b, y)
        }
        if (k > 4 && abs(p[0] - p[k - 2]) < 0.01f && abs(p[1] - p[k - 1]) < 0.01f) k -= 2
        val n = k / 2
        if (n < 3) return
        val poly = p.copyOf(k)
        orient(poly)
        for (i in 0 until n) {
            val a = i * 2; val c = (i + 1) % n * 2; val d = (i + 2) % n * 2
            // Concave outlines stay clipped to their bounds
            if ((poly[c] - poly[a]) * (poly[d + 1] - poly[c + 1]) - (poly[c + 1] - poly[a + 1]) * (poly[d] - poly[c]) < -0.01f) return
        }
        val inner = FloatArray(k); val outer = FloatArray(k)
        offsetPolygon(poly, n, 1f, inner, outer)
        state.rclip = RClip(poly, inner, outer, state.rclip, NO_SAFE, floatArrayOf(l, t, r, b, 0f, 0f), rrect = false)
    }

    override fun clipOutRect(x: Float, y: Float, w: Float, h: Float) {
        // Narrower holes would need their soft edges to overlap; drawing over them misses less than a pixel
        if (w * abs(state.sx) < 1f || h * abs(state.sy) < 1f) return
        val p = floatArrayOf(x, y, x + w, y, x + w, y + h, x, y + h)
        transformInPlace(p)
        toDevice(p)
        orient(p)
        val inner = FloatArray(8); val outer = FloatArray(8)
        offsetPolygon(p, 4, 1f, inner, outer)
        state.hole = RClip(p, inner, outer, state.hole, NONE, NONE)
    }

    private class RegionKey(val path: PolyPath, val transform: FloatArray, val difference: Boolean) {
        private val hash = (path.contentHash() * 31 + transform.contentHashCode()) * 31 + difference.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean =
            other is RegionKey && other.hash == hash && other.difference == difference && other.transform.contentEquals(transform) && other.path.contentEquals(path)
    }

    private class RegionEntry(val region: Region?, val outline: FloatArray?, val bounds: FloatArray)

    private val regions = object : LinkedHashMap<RegionKey, RegionEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RegionKey, RegionEntry>?) = size > MAX_REGIONS
    }

    override fun clipPath(path: PolyPath, difference: Boolean) {
        val s = state
        val key = if (s.rot == null && s.proj == null) RegionKey(path, floatArrayOf(s.sx, s.sy, s.tx, s.ty), difference) else null
        val hit = key?.let { regions[it] }
        if (hit != null) {
            hit.outline?.let { return clipPolygon(it) }
            val b = hit.bounds
            if (!difference) clipRect(b[0], b[1], b[2] - b[0], b[3] - b[1])
            val g = hit.region ?: return
            state.region = Region(g.pieces, g.box, g.bounds, g.bottoms, g.src, g.sizes, g.evenOdd, state.region)
            return
        }
        val loops = flatten(path)
        if (loops.isEmpty()) {
            if (!difference) clipRect(0f, 0f, 0f, 0f)
            return
        }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        val device = ArrayList<FloatArray>(loops.size)
        for (loop in loops) {
            for (i in loop.indices step 2) {
                l = min(l, loop[i]); r = max(r, loop[i])
                t = min(t, loop[i + 1]); b = max(b, loop[i + 1])
            }
            val d = loop.copyOf()
            transformInPlace(d)
            toDevice(d)
            device += d
        }
        val convex = !difference && device.size == 1 && convexOutline(device[0])
        val g = if (convex) null else region(device, path.evenOdd, difference)
        if (key != null) regions[RegionKey(path.snapshot(), key.transform, difference)] = RegionEntry(g, if (convex) loops[0] else null, floatArrayOf(l, t, r, b))
        if (convex) return clipPolygon(loops[0])
        if (!difference) clipRect(l, t, r - l, b - t)
        if (g != null) state.region = Region(g.pieces, g.box, g.bounds, g.bottoms, g.src, g.sizes, g.evenOdd, state.region)
    }

    private fun convexOutline(points: FloatArray): Boolean {
        val p = FloatArray(points.size)
        var k = 0
        for (i in points.indices step 2) {
            if (k > 0 && abs(points[i] - p[k - 2]) < 0.01f && abs(points[i + 1] - p[k - 1]) < 0.01f) continue
            p[k++] = points[i]; p[k++] = points[i + 1]
        }
        if (k > 4 && abs(p[0] - p[k - 2]) < 0.01f && abs(p[1] - p[k - 1]) < 0.01f) k -= 2
        val n = k / 2
        if (n < 3) return true
        val sign = if (signedArea(p, n) >= 0f) 1f else -1f
        var turn = 0.0
        for (i in 0 until n) {
            val a = i * 2; val c = (i + 1) % n * 2; val d = (i + 2) % n * 2
            val ux = p[c] - p[a]; val uy = p[c + 1] - p[a + 1]
            val vx = p[d] - p[c]; val vy = p[d + 1] - p[c + 1]
            val cross = (ux * vy - uy * vx) * sign
            if (cross < -0.01f) return false
            turn += atan2(cross.toDouble(), (ux * vx + uy * vy).toDouble())
        }
        return turn < Math.PI * 3
    }

    private class RegionEdges(size: Int) {
        val ax = FloatArray(size); val ay = FloatArray(size); val bx = FloatArray(size); val by = FloatArray(size)
        val dir = IntArray(size)
        val path = BooleanArray(size)
        var count = 0

        fun add(x0: Float, y0: Float, x1: Float, y1: Float, onPath: Boolean) {
            if (y0 == y1) return
            val e = count++
            if (y0 < y1) { ax[e] = x0; ay[e] = y0; bx[e] = x1; by[e] = y1; dir[e] = 1 } else { ax[e] = x1; ay[e] = y1; bx[e] = x0; by[e] = y0; dir[e] = -1 }
            path[e] = onPath
        }

        fun xAt(e: Int, y: Float): Float = when {
            y <= ay[e] -> ax[e]
            y >= by[e] -> bx[e]
            else -> ax[e] + (bx[e] - ax[e]) * ((y - ay[e]) / (by[e] - ay[e]))
        }

        fun crossing(i: Int, j: Int): Float {
            if (!path[i] || !path[j] || by[i] <= ay[j] || by[j] <= ay[i]) return Float.NaN
            if (max(ax[i], bx[i]) < min(ax[j], bx[j]) || max(ax[j], bx[j]) < min(ax[i], bx[i])) return Float.NaN
            val rx = (bx[i] - ax[i]).toDouble(); val ry = (by[i] - ay[i]).toDouble()
            val sx = (bx[j] - ax[j]).toDouble(); val sy = (by[j] - ay[j]).toDouble()
            val den = rx * sy - ry * sx
            if (abs(den) < 1e-12) return Float.NaN
            val qx = (ax[j] - ax[i]).toDouble(); val qy = (ay[j] - ay[i]).toDouble()
            val t = (qx * sy - qy * sx) / den
            val u = (qx * ry - qy * rx) / den
            if (t <= 0.0 || t >= 1.0 || u <= 0.0 || u >= 1.0) return Float.NaN
            return (ay[i] + t * ry).toFloat()
        }
    }

    private class Spans {
        var xs = FloatArray(64)
        var sides = IntArray(32)
        var count = 0

        fun add(slabStart: Int, left: Int, right: Int, tl: Float, tr: Float, bl: Float, br: Float) {
            val last = count - 1
            if (last >= slabStart && abs(xs[last * 4 + 1] - tl) < 1e-4f && abs(xs[last * 4 + 3] - bl) < 1e-4f) {
                xs[last * 4 + 1] = tr; xs[last * 4 + 3] = br; sides[last * 2 + 1] = right
                return
            }
            if (count * 4 == xs.size) { xs = xs.copyOf(xs.size * 2); sides = sides.copyOf(sides.size * 2) }
            xs[count * 4] = tl; xs[count * 4 + 1] = tr; xs[count * 4 + 2] = bl; xs[count * 4 + 3] = br
            sides[count * 2] = left; sides[count * 2 + 1] = right
            count++
        }
    }

    private fun region(loops: List<FloatArray>, evenOdd: Boolean, difference: Boolean): Region {
        var pl = Float.MAX_VALUE; var pt = Float.MAX_VALUE; var pr = -Float.MAX_VALUE; var pb = -Float.MAX_VALUE
        var points = 0
        for (p in loops) {
            points += p.size / 2
            for (i in p.indices step 2) {
                pl = min(pl, p[i]); pr = max(pr, p[i])
                pt = min(pt, p[i + 1]); pb = max(pb, p[i + 1])
            }
        }
        val box = if (difference) floatArrayOf(floor(pl) - 2f, floor(pt) - 2f, ceil(pr) + 2f, ceil(pb) + 2f) else null
        val edges = RegionEdges(points + 2)
        for (p in loops) {
            val n = p.size / 2
            for (i in 0 until n) {
                val j = (i + 1) % n
                edges.add(p[i * 2], p[i * 2 + 1], p[j * 2], p[j * 2 + 1], true)
            }
        }
        if (box != null) {
            edges.add(box[0], box[1], box[0], box[3], false)
            edges.add(box[2], box[3], box[2], box[1], false)
        }
        val m = edges.count
        var ys = FloatArray(m * 2 + 2)
        var yn = 0
        fun addY(y: Float) {
            if (yn == ys.size) ys = ys.copyOf(yn * 2)
            ys[yn++] = y
        }
        for (e in 0 until m) { addY(edges.ay[e]); addY(edges.by[e]) }
        val order = (0 until m).sortedBy { edges.ay[it] }
        for (a in 0 until m) {
            val i = order[a]
            for (c in a + 1 until m) {
                val j = order[c]
                if (edges.ay[j] >= edges.by[i]) break
                val y = edges.crossing(i, j)
                if (!y.isNaN()) addY(y)
            }
        }
        ys.sort(0, yn)
        var slabYs = 0
        for (i in 0 until yn) if (slabYs == 0 || ys[i] - ys[slabYs - 1] > 1e-3f) ys[slabYs++] = ys[i]
        val slabs = max(slabYs - 1, 0)
        val spans = Spans()
        val slabStart = IntArray(slabs + 1)
        val active = IntArray(m)
        val xm = FloatArray(m)
        var an = 0
        var next = 0
        fun filled(w: Int) = if (evenOdd) w and 1 != 0 else w != 0
        fun inside(wp: Int, wb: Int) = if (difference) wb != 0 && !filled(wp) else filled(wp)
        for (k in 0 until slabs) {
            val y0 = ys[k]
            val y1 = ys[k + 1]
            val mid = (y0 + y1) / 2f
            var w = 0
            for (i in 0 until an) if (edges.by[active[i]] > mid) active[w++] = active[i]
            an = w
            while (next < m && edges.ay[order[next]] < mid) {
                val e = order[next++]
                if (edges.by[e] > mid) active[an++] = e
            }
            for (i in 0 until an) xm[active[i]] = edges.xAt(active[i], mid)
            for (i in 1 until an) {
                val e = active[i]
                var j = i - 1
                while (j >= 0 && xm[active[j]] > xm[e]) { active[j + 1] = active[j]; j-- }
                active[j + 1] = e
            }
            slabStart[k] = spans.count
            var wp = 0
            var wb = 0
            var open = -1
            for (i in 0 until an) {
                val e = active[i]
                val was = inside(wp, wb)
                if (edges.path[e]) wp += edges.dir[e] else wb += edges.dir[e]
                val now = inside(wp, wb)
                if (!was && now) open = e
                if (!was || now || open < 0) continue
                var tl = edges.xAt(open, y0); var tr = edges.xAt(e, y0)
                var bl = edges.xAt(open, y1); var br = edges.xAt(e, y1)
                if (tr < tl) { tl = (tl + tr) / 2f; tr = tl }
                if (br < bl) { bl = (bl + br) / 2f; br = bl }
                if (tr - tl > 1e-4f || br - bl > 1e-4f) spans.add(slabStart[k], open, e, tl, tr, bl, br)
            }
        }
        slabStart[slabs] = spans.count
        val pieces = ArrayList<RegionPiece>()
        val sx = spans.xs
        val top = FloatArray(spans.count * 2 + 4)
        val bottom = FloatArray(spans.count * 2 + 4)
        for (k in 0 until slabs) for (s in slabStart[k] until slabStart[k + 1]) {
            val tl = sx[s * 4]; val tr = sx[s * 4 + 1]; val bl = sx[s * 4 + 2]; val br = sx[s * 4 + 3]
            val tn = when {
                k > 0 -> uncovered(tl, tr, sx, slabStart[k - 1], slabStart[k], 2, top)
                box == null -> { top[0] = tl; top[1] = tr; 2 }
                else -> 0
            }
            val bn = when {
                k < slabs - 1 -> uncovered(bl, br, sx, slabStart[k + 1], slabStart[k + 2], 0, bottom)
                box == null -> { bottom[0] = bl; bottom[1] = br; 2 }
                else -> 0
            }
            val y0 = ys[k]
            val y1 = ys[k + 1]
            val limits = ArrayList<FloatArray>(6)
            val lx = min(tl, bl) - 1f
            val rx = max(tr, br) + 1f
            limits += floatArrayOf(lx, y1, lx, y0)
            limits += floatArrayOf(rx, y0, rx, y1)
            limits += floatArrayOf(lx, y0 - 1f, rx, y0 - 1f)
            limits += floatArrayOf(rx, y1 + 1f, lx, y1 + 1f)
            if (s > slabStart[k]) limits += floatArrayOf((sx[s * 4 - 1] + bl) / 2f, y1, (sx[s * 4 - 3] + tl) / 2f, y0)
            if (s + 1 < slabStart[k + 1]) limits += floatArrayOf((tr + sx[s * 4 + 4]) / 2f, y0, (br + sx[s * 4 + 6]) / 2f, y1)
            val caps = ArrayList<Cap>()
            if (k > 0) capsAt(edges, spans, slabStart[k - 1], slabStart[k], tl, tr, y0, true, caps)
            if (k < slabs - 1) capsAt(edges, spans, slabStart[k + 1], slabStart[k + 2], bl, br, y1, false, caps)
            val leftSoft = edges.path[spans.sides[s * 2]]
            val rightSoft = edges.path[spans.sides[s * 2 + 1]]
            spanPieces(y0, y1, tl, tr, bl, br, leftSoft, rightSoft, top, tn, bottom, bn, limits, caps, pieces)
        }
        pieces.sortBy { it.t }
        val bounds = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        val bottoms = FloatArray(pieces.size)
        for ((i, p) in pieces.withIndex()) {
            bounds[0] = min(bounds[0], p.l); bounds[1] = min(bounds[1], p.t); bounds[2] = max(bounds[2], p.r); bounds[3] = max(bounds[3], p.b)
            bottoms[i] = if (i == 0) p.b else max(bottoms[i - 1], p.b)
        }
        val src = FloatArray(points * 2)
        var o = 0
        for (p in loops) { p.copyInto(src, o); o += p.size }
        return Region(pieces.toTypedArray(), box, bounds, bottoms, src, IntArray(loops.size) { loops[it].size / 2 }, evenOdd, null)
    }

    private class Cap(val x: Float, val right: Boolean, val line: FloatArray)

    private fun capsAt(edges: RegionEdges, spans: Spans, from: Int, to: Int, a: Float, b: Float, y: Float, top: Boolean, out: MutableList<Cap>) {
        val xs = spans.xs
        val at = if (top) 2 else 0
        val far = if (top) 0 else 2
        for (s in from until to) {
            val c = xs[s * 4 + at]; val d = xs[s * 4 + at + 1]
            if (c > a + 1e-3f && c < b - 1e-3f && xs[s * 4 + far] > c + 1e-3f) cap(edges, spans.sides[s * 2], c, true, y, top, out)
            if (d > a + 1e-3f && d < b - 1e-3f && xs[s * 4 + far + 1] < d - 1e-3f) cap(edges, spans.sides[s * 2 + 1], d, false, y, top, out)
        }
    }

    private fun cap(edges: RegionEdges, e: Int, x: Float, right: Boolean, y: Float, top: Boolean, out: MutableList<Cap>) {
        if (!edges.path[e]) return
        var line = floatArrayOf(edges.ax[e], edges.ay[e], edges.bx[e], edges.by[e])
        if (edgeSide(line, 0, x + if (right) 0.5f else -0.5f, if (top) y + 1f else y - 1f) < 0f) line = floatArrayOf(line[2], line[3], line[0], line[1])
        out += Cap(x, right, line)
    }

    private fun uncovered(a: Float, b: Float, xs: FloatArray, from: Int, to: Int, at: Int, out: FloatArray): Int {
        var n = 0
        var x = a
        for (s in from until to) {
            val c = xs[s * 4 + at]
            val d = xs[s * 4 + at + 1]
            if (d <= x) continue
            if (c >= b) break
            if (c - x > 1e-3f) { out[n++] = x; out[n++] = c }
            x = max(x, d)
            if (x >= b) break
        }
        if (b - x > 1e-3f) { out[n++] = x; out[n++] = b }
        return n
    }

    private fun spanPieces(
        y0: Float, y1: Float, tl: Float, tr: Float, bl: Float, br: Float, leftSoft: Boolean, rightSoft: Boolean,
        top: FloatArray, tn: Int, bottom: FloatArray, bn: Int, limits: List<FloatArray>, caps: List<Cap>, out: MutableList<RegionPiece>,
    ) {
        val px = floatArrayOf(tl, tr, br, bl)
        val py = floatArrayOf(y0, y0, y1, y1)
        val pl = intArrayOf(SIDE_TOP, SIDE_RIGHT, SIDE_BOTTOM, SIDE_LEFT)
        val cuts = FloatArray(tn + bn + caps.size)
        var cn = 0
        for (i in 0 until tn) if (top[i] > tl + 1e-3f && top[i] < tr - 1e-3f) cuts[cn++] = top[i]
        for (i in 0 until bn) if (bottom[i] > bl + 1e-3f && bottom[i] < br - 1e-3f) cuts[cn++] = bottom[i]
        for (c in caps) cuts[cn++] = c.x
        cuts.sort(0, cn)
        var unique = 0
        for (i in 0 until cn) if (unique == 0 || cuts[i] - cuts[unique - 1] > 1e-3f) cuts[unique++] = cuts[i]
        cn = unique
        val ax = FloatArray(12); val ay = FloatArray(12); val al = IntArray(12)
        val bx = FloatArray(12); val by = FloatArray(12); val bl2 = IntArray(12)
        for (c in 0..cn) {
            var n = 4
            px.copyInto(ax); py.copyInto(ay); pl.copyInto(al)
            if (c > 0) {
                n = cutX(ax, ay, al, n, cuts[c - 1], true, bx, by, bl2)
                bx.copyInto(ax, 0, 0, n); by.copyInto(ay, 0, 0, n); bl2.copyInto(al, 0, 0, n)
            }
            if (c < cn && n >= 3) {
                n = cutX(ax, ay, al, n, cuts[c], false, bx, by, bl2)
                bx.copyInto(ax, 0, 0, n); by.copyInto(ay, 0, 0, n); bl2.copyInto(al, 0, 0, n)
            }
            if (n < 3) continue
            val soft = BooleanArray(n)
            for (i in 0 until n) {
                val mid = (ax[i] + ax[(i + 1) % n]) / 2f
                soft[i] = when (al[i]) {
                    SIDE_TOP -> within(top, tn, mid)
                    SIDE_BOTTOM -> within(bottom, bn, mid)
                    SIDE_LEFT -> leftSoft
                    SIDE_RIGHT -> rightSoft
                    else -> false
                }
            }
            val lo = if (c > 0) cuts[c - 1] else Float.NaN
            val hi = if (c < cn) cuts[c] else Float.NaN
            val stripCaps = if (caps.isEmpty()) emptyList() else caps.filter { if (it.right) abs(it.x - lo) <= 1e-3f else abs(it.x - hi) <= 1e-3f }.map { it.line }
            regionPiece(ax, ay, soft, n, limits, stripCaps)?.let { out += it }
        }
    }

    private fun within(intervals: FloatArray, n: Int, x: Float): Boolean {
        for (i in 0 until n step 2) if (x >= intervals[i] && x <= intervals[i + 1]) return true
        return false
    }

    private fun cutX(xs: FloatArray, ys: FloatArray, ls: IntArray, n: Int, at: Float, keepRight: Boolean, ox: FloatArray, oy: FloatArray, ol: IntArray): Int {
        var k = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ii = if (keepRight) xs[i] >= at else xs[i] <= at
            val ij = if (keepRight) xs[j] >= at else xs[j] <= at
            if (ii) { ox[k] = xs[i]; oy[k] = ys[i]; ol[k] = ls[i]; k++ }
            if (ii != ij) {
                val f = (at - xs[i]) / (xs[j] - xs[i])
                ox[k] = at; oy[k] = ys[i] + (ys[j] - ys[i]) * f; ol[k] = if (ii) SIDE_CUT else ls[i]; k++
            }
        }
        return k
    }

    private fun regionPiece(xs: FloatArray, ys: FloatArray, soft: BooleanArray, count: Int, limits: List<FloatArray>, caps: List<FloatArray>): RegionPiece? {
        val ex = FloatArray(count); val ey = FloatArray(count); val es = BooleanArray(count)
        var n = 0
        for (i in 0 until count) {
            if (n > 0 && abs(xs[i] - ex[n - 1]) < 1e-4f && abs(ys[i] - ey[n - 1]) < 1e-4f) { es[n - 1] = soft[i]; continue }
            ex[n] = xs[i]; ey[n] = ys[i]; es[n] = soft[i]; n++
        }
        while (n > 1 && abs(ex[0] - ex[n - 1]) < 1e-4f && abs(ey[0] - ey[n - 1]) < 1e-4f) n--
        if (n < 3) return null
        val edge = FloatArray(n * 2)
        for (i in 0 until n) { edge[i * 2] = ex[i]; edge[i * 2 + 1] = ey[i] }
        if (signedArea(edge, n) <= 1e-6f) return null
        val cover = ArrayList<FloatArray>(n + caps.size)
        val outer = ArrayList<FloatArray>(n + limits.size)
        for (e in 0 until n) {
            val j = (e + 1) % n
            val line = floatArrayOf(ex[e], ey[e], ex[j], ey[j])
            if (es[e]) cover += line
            outer += if (es[e]) offsetLine(line, -0.5f) else line
        }
        cover += caps
        outer += limits
        val invLen = FloatArray(cover.size) { 1f / max(length(cover[it][2] - cover[it][0], cover[it][3] - cover[it][1]), 1e-6f) }
        val inner = Array(cover.size) { offsetLine(cover[it], 0.5f) }
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            l = min(l, ex[i]); r = max(r, ex[i])
            t = min(t, ey[i]); b = max(b, ey[i])
        }
        return RegionPiece(cover.toTypedArray(), invLen, inner, outer.toTypedArray(), l - 1f, t - 1f, r + 1f, b + 1f)
    }

    private fun offsetLine(line: FloatArray, by: Float): FloatArray {
        val dx = line[2] - line[0]; val dy = line[3] - line[1]
        val o = by / max(length(dx, dy), 1e-6f)
        val ox = -dy * o; val oy = dx * o
        return floatArrayOf(line[0] + ox, line[1] + oy, line[2] + ox, line[3] + oy)
    }

    // Winds a clip polygon so edgeSide is positive inside it
    private fun orient(p: FloatArray) {
        if (signedArea(p, p.size / 2) >= 0f) return
        for (i in 0 until p.size / 4) {
            val j = p.size / 2 - 1 - i
            val tx = p[i * 2]; val ty = p[i * 2 + 1]
            p[i * 2] = p[j * 2]; p[i * 2 + 1] = p[j * 2 + 1]
            p[j * 2] = tx; p[j * 2 + 1] = ty
        }
    }

    private val boxTmp = FloatArray(6)
    private val quadTmp = FloatArray(8)

    private fun boxPx(x: Float, y: Float, w: Float, h: Float, radius: Float, out: FloatArray = FloatArray(6)): FloatArray {
        val r = max(0f, min(radius, min(w, h) / 2f))
        val ax = x * state.sx + state.tx; val bx = (x + w) * state.sx + state.tx
        val ay = y * state.sy + state.ty; val by = (y + h) * state.sy + state.ty
        out[0] = min(ax, bx); out[1] = min(ay, by); out[2] = max(ax, bx); out[3] = max(ay, by); out[4] = r * abs(state.sx); out[5] = r * abs(state.sy)
        return out
    }

    private inline fun clippedFill(x: Float, y: Float, w: Float, h: Float, radius: Float, fill: (FloatArray) -> Unit) {
        val saved = state.rclip
        if (saved == null || state.rot != null || state.sx <= 0f || state.sy <= 0f) {
            val r = rrectScratch(0, x, y, w, h, radius)
            return unclipped(x, y, w, h, radius) { fill(r) }
        }
        val b = boxPx(x, y, w, h, radius, boxTmp)
        val k = saved.box
        val e = 1e-3f
        val covers = b[0] <= k[0] + e && b[1] <= k[1] + e && b[2] >= k[2] - e && b[3] >= k[3] - e && b[4] <= k[4] + e && b[5] <= k[5] + e
        val sf = saved.safe
        val safe = (b[0] >= sf[0] && b[1] >= sf[1] && b[2] <= sf[2] && b[3] <= sf[3]) || (b[0] >= sf[4] && b[1] >= sf[5] && b[2] <= sf[6] && b[3] <= sf[7])
        if (!covers && (safe || b[4] > 0.5f || b[5] > 0.5f)) {
            val r = rrectScratch(0, x, y, w, h, radius)
            if (!safe) return unclipped(x, y, w, h, radius) { fill(r) }
            state.rclip = saved.parent
            try {
                unclipped(x, y, w, h, radius) { fill(r) }
            } finally {
                state.rclip = saved
            }
            return
        }
        val poly = if (covers) saved.edge else clipToBox(saved.edge, b[0], b[1], b[2], b[3])
        if (poly.size < 6) return
        state.rclip = saved.parent
        try {
            unclipped((k[0] - state.tx) / state.sx, (k[1] - state.ty) / state.sy, (k[2] - k[0]) / state.sx, (k[3] - k[1]) / state.sy, k[4] / state.sx) { fill(poly) }
        } finally {
            state.rclip = saved
        }
    }

    private fun clipToBox(p: FloatArray, l: Float, t: Float, r: Float, b: Float): FloatArray {
        var cur = p
        for (side in 0 until 4) {
            val n = cur.size / 2
            if (n == 0) break
            val bound = when (side) { 0 -> l; 1 -> t; 2 -> r; else -> b }
            val axis = side % 2
            val keepAbove = side < 2
            fun inside(i: Int) = if (keepAbove) cur[i * 2 + axis] >= bound else cur[i * 2 + axis] <= bound
            val out = FloatArray((n + 1) * 2)
            var k = 0
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ii = inside(i); val ij = inside(j)
                if (ii) { out[k++] = cur[i * 2]; out[k++] = cur[i * 2 + 1] }
                if (ii != ij) {
                    val a0 = cur[i * 2 + axis]; val a1 = cur[j * 2 + axis]
                    val f = (bound - a0) / (a1 - a0)
                    val x = cur[i * 2] + (cur[j * 2] - cur[i * 2]) * f
                    val y = cur[i * 2 + 1] + (cur[j * 2 + 1] - cur[i * 2 + 1]) * f
                    if (axis == 0) { out[k++] = bound; out[k++] = y } else { out[k++] = x; out[k++] = bound }
                }
            }
            cur = out.copyOf(k)
        }
        return cur
    }

    private inline fun unclipped(x: Float, y: Float, w: Float, h: Float, radius: Float, draw: () -> Unit) {
        val saved = state.rclip ?: return draw()
        if (state.rot != null) return draw()
        val b = boxPx(x, y, w, h, radius, boxTmp)
        var c: RClip? = saved
        while (c != null) {
            val k = c.box
            val e = 1e-3f
            // A polygon's box doesn't say whether the drawing fits in it
            if (!c.rrect || b[0] < k[0] - e || b[1] < k[1] - e || b[2] > k[2] + e || b[3] > k[3] + e || b[4] < k[4] - e || b[5] < k[5] - e) break
            c = c.parent
        }
        state.rclip = c
        try {
            draw()
        } finally {
            state.rclip = saved
        }
    }

    // Geometry drawn with a gradient keeps sampling the gradient's texture
    private fun whitePage(): Any = if (shading != null) batchTexture else batchTexture as? PolyGlyphPage.Page ?: whiteTexture()

    protected fun begin(texture: Any, premultiplied: Boolean, nearest: Boolean = false) {
        applyBlend()
        if (whiteOnly && premultiplied == batchPremul && (texture === batchTexture || texture is PolyGlyphPage.Page && batchTexture is PolyGlyphPage.Page)) {
            if (texture !== batchTexture || nearest != batchNearest) clipLogs.lastOrNull()?.begin(texture, premultiplied, nearest)
            batchTexture = texture
            batchNearest = nearest
        } else {
            switchBatch(texture, premultiplied, nearest)
        }
        whiteOnly = false
    }

    private fun beginWhite() {
        applyBlend()
        if (shading != null) return begin(whitePage(), false, batchNearest)
        val white = whiteOnly || count == 0
        switchBatch(whitePage(), false, batchNearest)
        whiteOnly = white || count == 0
    }

    private fun switchBatch(texture: Any, premultiplied: Boolean, nearest: Boolean) {
        if (texture !== batchTexture || premultiplied != batchPremul || nearest != batchNearest) clipLogs.lastOrNull()?.begin(texture, premultiplied, nearest)
        if (count > 0 && (texture !== batchTexture || premultiplied != batchPremul || nearest != batchNearest)) flush()
        batchTexture = texture
        batchPremul = premultiplied
        batchNearest = nearest
    }

    protected fun quadPx(x0: Float, y0: Float, x1: Float, y1: Float, u0: Float, v0: Float, u1: Float, v1: Float, color: Int) {
        vertex(x0, y0, u0, v0, color)
        vertex(x0, y1, u0, v1, color)
        vertex(x1, y1, u1, v1, color)
        vertex(x1, y0, u1, v0, color)
    }

    protected fun vertexPx(x: Float, y: Float, u: Float, v: Float, color: Int): Unit = vertex(x, y, u, v, color)

    protected fun resetTessellator() {
        state = State(1f, 1f, 0f, 0f, 1f, null, null)
        stack.clear()
        layers.clear()
        boxCount = 0
        count = 0
        qn = 0
        batchTexture = whiteTexture()
        batchPremul = false
        batchNearest = false
        whiteOnly = true
        drawBlend = PolyRenderer.BLEND_SRC_OVER
        wantBlend = PolyRenderer.BLEND_SRC_OVER
        discardFrom = Int.MAX_VALUE
        antiAliased = true
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
    }

    protected fun flush() {
        clipLogs.lastOrNull()?.event(CLIP_FLUSH)
        for (i in layers.indices) {
            val l = layers[i]
            if (l.mark != null) continue
            if (l.start > 0) submitPrefix(l.start)
            l.mark = layerMark()
        }
        if (count == 0) return
        val b = boundsTmp
        b[0] = minX; b[1] = minY; b[2] = maxX; b[3] = maxY
        submitBuffer(batchTexture, batchPremul, batchNearest, verts, colors, count, b)
        count = 0
        whiteOnly = true
        minX = Float.MAX_VALUE; minY = Float.MAX_VALUE; maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
    }

    private val boundsTmp = FloatArray(4)

    protected open fun submitBuffer(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, count: Int, bounds: FloatArray) {
        submit(texture, premultiplied, nearest, verts.copyOf(count * 4), colors.copyOf(count), bounds)
    }

    private fun submitPrefix(n: Int) {
        submitBuffer(batchTexture, batchPremul, batchNearest, verts, colors, n, boundsOf(verts, n))
        System.arraycopy(verts, n * 4, verts, 0, (count - n) * 4)
        System.arraycopy(colors, n, colors, 0, count - n)
        count -= n
        for (i in layers.indices) layers[i].start -= n
        val b = boundsOf(verts, count)
        minX = b[0]; minY = b[1]; maxX = b[2]; maxY = b[3]
    }

    private fun boundsOf(v: FloatArray, vertices: Int): FloatArray {
        val b = boundsTmp
        b[0] = Float.MAX_VALUE; b[1] = Float.MAX_VALUE; b[2] = -Float.MAX_VALUE; b[3] = -Float.MAX_VALUE
        for (i in 0 until vertices) {
            b[0] = min(b[0], v[i * 4]); b[1] = min(b[1], v[i * 4 + 1])
            b[2] = max(b[2], v[i * 4]); b[3] = max(b[3], v[i * 4 + 1])
        }
        return b
    }

    private fun premultiplied(color: Int): Int = (color and -0x1000000) or (scaleArgb(color, (color ushr 24) / 255f) and 0x00FFFFFF)

    private fun scaleArgb(color: Int, alpha: Float): Int {
        var out = 0
        var shift = 0
        while (shift < 32) {
            out = out or ((((color ushr shift) and 0xFF) * alpha).roundToInt().coerceIn(0, 255) shl shift)
            shift += 8
        }
        return out
    }

    protected fun mulAlpha(color: Int, alpha: Float): Int {
        if (alpha >= 1f) return color
        val a = ((color ushr 24) * alpha).roundToInt().coerceIn(0, 255)
        return (a shl 24) or (color and 0x00FFFFFF)
    }

    private fun scaleAvg() = (abs(state.sx) + abs(state.sy)) / 2f

    private fun cornerSegments(radius: Float): Int {
        val r = radius * scaleAvg()
        return if (r <= 0.5f) 0 else min(MAX_CORNER_SEGMENTS, max(2, ceil(sqrt(r) * 1.6f).toInt()))
    }

    private val rrectBuffers = arrayOfNulls<FloatArray>(RRECT_SLOTS * (MAX_CORNER_SEGMENTS + 1))

    private fun rrectScratch(slot: Int, x: Float, y: Float, w: Float, h: Float, radius: Float, segs: Int = cornerSegments(radius)): FloatArray {
        val i = slot * (MAX_CORNER_SEGMENTS + 1) + segs
        val out = rrectBuffers[i] ?: FloatArray(rrectSize(segs)).also { rrectBuffers[i] = it }
        return rrectPoints(x, y, w, h, radius, segs, out)
    }

    private fun rrectSize(segs: Int): Int = 4 * (segs + 1) * 2

    private fun rrectPoints(x: Float, y: Float, w: Float, h: Float, radius: Float, segs: Int = cornerSegments(radius), out: FloatArray = FloatArray(rrectSize(segs))): FloatArray {
        val r = max(0f, min(radius, min(w, h) / 2f))
        if (segs == 0) {
            out[0] = x; out[1] = y; out[2] = x + w; out[3] = y; out[4] = x + w; out[5] = y + h; out[6] = x; out[7] = y + h
        } else {
            val trig = cornerTrig(segs)
            val per = (segs + 1) * 2
            for (corner in 0 until 4) {
                val cx = if (corner == 0 || corner == 3) x + r else x + w - r
                val cy = if (corner < 2) y + r else y + h - r
                var k = corner * per
                while (k < (corner + 1) * per) {
                    out[k] = cx + trig[k] * r
                    out[k + 1] = cy + trig[k + 1] * r
                    k += 2
                }
            }
        }
        transformInPlace(out)
        return out
    }

    private fun transformInPlace(p: FloatArray) {
        val s = state
        var i = 0
        while (i < p.size) {
            p[i] = p[i] * s.sx + s.tx
            p[i + 1] = p[i + 1] * s.sy + s.ty
            i += 2
        }
    }

    private val uvTmp = FloatArray(2)
    private var whiteUvs = FloatArray(64).also { it.fill(PolyGlyphPage.WHITE_UV) }
    private val floatScratch = arrayOfNulls<FloatArray>(8)
    private val intScratch = arrayOfNulls<IntArray>(7)

    private fun floats(slot: Int, size: Int): FloatArray {
        val a = floatScratch[slot]
        if (a != null && a.size >= size) return a
        return FloatArray(max(size, (a?.size ?: 32) * 2)).also { floatScratch[slot] = it }
    }

    private fun ints(slot: Int, size: Int): IntArray {
        val a = intScratch[slot]
        if (a != null && a.size >= size) return a
        return IntArray(max(size, (a?.size ?: 16) * 2)).also { intScratch[slot] = it }
    }

    private fun fillPolygonPx(p: FloatArray, n: Int, color: Int, convex: Boolean, uv: UvMap? = null, keepBatch: Boolean = false, quads: IntArray? = null) {
        if (n < 3) return
        val c = mulAlpha(color, state.alpha)
        if (invisible(c)) return
        if (convex && n == 4 && uv == null && quads == null && !recording && alignedRect(p, c, keepBatch)) return
        val clockwise = signedArea(p, n) > 0f
        val inner = floats(0, n * 2)
        val outer = floats(1, n * 2)
        offsetPolygon(p, n, if (clockwise) 1f else -1f, inner, outer)
        if (!keepBatch && !recording) beginWhite()
        val iuv = uvs(inner, n, uv, 2)
        val ouv = uvs(outer, n, uv, 3)
        fun tri(a: Int, b: Int, d: Int) = quadVerts(
            inner[a * 2], inner[a * 2 + 1], iuv[a * 2], iuv[a * 2 + 1], c, inner[b * 2], inner[b * 2 + 1], iuv[b * 2], iuv[b * 2 + 1], c,
            inner[d * 2], inner[d * 2 + 1], iuv[d * 2], iuv[d * 2 + 1], c, inner[d * 2], inner[d * 2 + 1], iuv[d * 2], iuv[d * 2 + 1], c,
        )
        if (convex || (quads == null && isConvexLoop(inner, n))) {
            var i = 1
            while (i + 2 < n) {
                quadVerts(
                    inner[0], inner[1], iuv[0], iuv[1], c, inner[i * 2], inner[i * 2 + 1], iuv[i * 2], iuv[i * 2 + 1], c,
                    inner[i * 2 + 2], inner[i * 2 + 3], iuv[i * 2 + 2], iuv[i * 2 + 3], c, inner[i * 2 + 4], inner[i * 2 + 5], iuv[i * 2 + 4], iuv[i * 2 + 5], c,
                )
                i += 2
            }
            if (i + 1 < n) tri(0, i, i + 1)
        } else if (quads != null) {
            var q = 0
            while (q < quads.size) {
                val a = quads[q] * 2; val b = quads[q + 1] * 2; val d = quads[q + 2] * 2; val e = quads[q + 3] * 2
                quadVerts(
                    inner[a], inner[a + 1], iuv[a], iuv[a + 1], c, inner[b], inner[b + 1], iuv[b], iuv[b + 1], c,
                    inner[d], inner[d + 1], iuv[d], iuv[d + 1], c, inner[e], inner[e + 1], iuv[e], iuv[e + 1], c,
                )
                q += 4
            }
        } else {
            val t = earClip(inner, n)
            for (k in t.indices step 3) tri(t[k], t[k + 1], t[k + 2])
        }
        if (!antiAliased) return
        val clear = c and 0x00FFFFFF
        for (i in 0 until n) {
            val j = (i + 1) % n
            quadVerts(
                inner[i * 2], inner[i * 2 + 1], iuv[i * 2], iuv[i * 2 + 1], c, outer[i * 2], outer[i * 2 + 1], ouv[i * 2], ouv[i * 2 + 1], clear,
                outer[j * 2], outer[j * 2 + 1], ouv[j * 2], ouv[j * 2 + 1], clear, inner[j * 2], inner[j * 2 + 1], iuv[j * 2], iuv[j * 2 + 1], c,
            )
        }
    }

    private fun alignedRect(p: FloatArray, c: Int, keepBatch: Boolean): Boolean {
        val s = state
        if (s.rot != null || s.hole != null) return false
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until 4) {
            val x = p[i * 2]; val y = p[i * 2 + 1]
            val rx = round(x); val ry = round(y)
            if (abs(x - rx) > ALIGN_EPSILON || abs(y - ry) > ALIGN_EPSILON) return false
            x0 = min(x0, rx); y0 = min(y0, ry); x1 = max(x1, rx); y1 = max(y1, ry)
        }
        if (x1 - x0 < 1f || y1 - y0 < 1f) return false
        var lastVertical = false
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            val vertical = abs(p[i * 2] - p[j * 2]) <= ALIGN_EPSILON * 2f
            val horizontal = abs(p[i * 2 + 1] - p[j * 2 + 1]) <= ALIGN_EPSILON * 2f
            if (vertical == horizontal || (i > 0 && vertical == lastVertical)) return false
            lastVertical = vertical
        }
        var rc = s.rclip
        while (rc != null) {
            if (!inside(rc, x0, y0) || !inside(rc, x1, y0) || !inside(rc, x1, y1) || !inside(rc, x0, y1)) return false
            rc = rc.parent
        }
        if (!keepBatch) beginWhite()
        val w = PolyGlyphPage.WHITE_UV
        quadPx(x0, y0, x1, y1, w, w, w, w, c)
        return true
    }

    private fun uvs(p: FloatArray, n: Int, uv: UvMap?, slot: Int): FloatArray {
        if (uv == null) {
            if (whiteUvs.size < n * 2) whiteUvs = FloatArray(max(n * 2, whiteUvs.size * 2)).also { it.fill(PolyGlyphPage.WHITE_UV) }
            return whiteUvs
        }
        val out = floats(slot, n * 2)
        for (i in 0 until n) {
            uv.map(p[i * 2], p[i * 2 + 1], uvTmp)
            out[i * 2] = uvTmp[0]; out[i * 2 + 1] = uvTmp[1]
        }
        return out
    }

    private fun strokeRing(outerPts: FloatArray, innerPts: FloatArray, n: Int, color: Int, shade: Shader? = null, band: Boolean = true) {
        val c = mulAlpha(color, state.alpha)
        if ((shade == null && invisible(c)) || n < 3) return
        val dir = if (signedArea(outerPts, n) > 0f) 1f else -1f
        val oIn = floats(4, n * 2); val oOut = floats(5, n * 2)
        val iIn = floats(6, n * 2); val iOut = floats(7, n * 2)
        offsetPolygon(outerPts, n, dir, oIn, oOut)
        offsetPolygon(innerPts, n, -dir, iIn, iOut)
        if (shade != null) begin(whitePage(), true) else beginWhite()
        val w = PolyGlyphPage.WHITE_UV
        fun at(p: FloatArray, i: Int) = if (shade == null) c else premultiplied(mulAlpha(shade.color(p[i * 2], p[i * 2 + 1]), state.alpha))
        fun clear(p: FloatArray, i: Int) = if (shade == null) c and 0x00FFFFFF else 0
        val oc = ints(0, n); val ic = ints(1, n); val ocl = ints(2, n); val icl = ints(3, n)
        for (i in 0 until n) {
            oc[i] = at(oIn, i); ic[i] = at(iIn, i)
            ocl[i] = clear(oOut, i); icl[i] = clear(iOut, i)
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            val a = i * 2; val b = j * 2
            if (band) quadVerts(oIn[a], oIn[a + 1], w, w, oc[i], iIn[a], iIn[a + 1], w, w, ic[i], iIn[b], iIn[b + 1], w, w, ic[j], oIn[b], oIn[b + 1], w, w, oc[j])
            if (!antiAliased) continue
            quadVerts(oIn[a], oIn[a + 1], w, w, oc[i], oOut[a], oOut[a + 1], w, w, ocl[i], oOut[b], oOut[b + 1], w, w, ocl[j], oIn[b], oIn[b + 1], w, w, oc[j])
            quadVerts(iIn[a], iIn[a + 1], w, w, ic[i], iOut[a], iOut[a + 1], w, w, icl[i], iOut[b], iOut[b + 1], w, w, icl[j], iIn[b], iIn[b + 1], w, w, ic[j])
        }
    }

    private fun densify(outer: FloatArray, inner: FloatArray, step: Float = 4f): Pair<FloatArray, FloatArray> {
        val n = outer.size / 2
        val ks = IntArray(n) { i ->
            val j = (i + 1) % n
            max(1, ceil(hypot(outer[j * 2] - outer[i * 2], outer[j * 2 + 1] - outer[i * 2 + 1]) / step).toInt())
        }
        val o = FloatArray(ks.sum() * 2)
        val i2 = FloatArray(o.size)
        var p = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val k = ks[i]
            for (t in 0 until k) {
                val f = t.toFloat() / k
                o[p] = outer[i * 2] + (outer[j * 2] - outer[i * 2]) * f; o[p + 1] = outer[i * 2 + 1] + (outer[j * 2 + 1] - outer[i * 2 + 1]) * f
                i2[p] = inner[i * 2] + (inner[j * 2] - inner[i * 2]) * f; i2[p + 1] = inner[i * 2 + 1] + (inner[j * 2 + 1] - inner[i * 2 + 1]) * f
                p += 2
            }
        }
        return o to i2
    }

    private fun signedArea(p: FloatArray, n: Int): Float {
        var a = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[i * 2] * p[j * 2 + 1] - p[j * 2] * p[i * 2 + 1]
        }
        return a / 2f
    }

    private fun offsetPolygon(p: FloatArray, n: Int, dir: Float, inner: FloatArray, outer: FloatArray) {
        fun same(a: Int, b: Int) = abs(p[a * 2] - p[b * 2]) < 1e-4f && abs(p[a * 2 + 1] - p[b * 2 + 1]) < 1e-4f
        for (i in 0 until n) {
            var prev = (i + n - 1) % n
            var next = (i + 1) % n
            for (s in 2 until n) { if (!same(prev, i)) break; prev = (i + n - s) % n }
            for (s in 2 until n) { if (!same(next, i)) break; next = (i + s) % n }
            var e0x = p[i * 2] - p[prev * 2]; var e0y = p[i * 2 + 1] - p[prev * 2 + 1]
            var e1x = p[next * 2] - p[i * 2]; var e1y = p[next * 2 + 1] - p[i * 2 + 1]
            val l0 = length(e0x, e0y).takeIf { it > 1e-4f } ?: 1f
            val l1 = length(e1x, e1y).takeIf { it > 1e-4f } ?: 1f
            e0x /= l0; e0y /= l0; e1x /= l1; e1y /= l1
            val n0x = -e0y * dir; val n0y = e0x * dir
            val n1x = -e1y * dir; val n1y = e1x * dir
            var mx = (n0x + n1x) / 2f
            var my = (n0y + n1y) / 2f
            val d2 = mx * mx + my * my
            if (d2 > 1e-6f) {
                val scale = min(1f / d2, 4f)
                mx *= scale; my *= scale
            }
            val x = p[i * 2]; val y = p[i * 2 + 1]
            val reach = if (antiAliased) 0.5f else 0f
            inner[i * 2] = x + mx * reach; inner[i * 2 + 1] = y + my * reach
            outer[i * 2] = x - mx * reach; outer[i * 2 + 1] = y - my * reach
        }
    }

    private fun earClip(p: FloatArray, n: Int): IntArray {
        val out = IntArray(max(n - 2, 0) * 3)
        if (n < 3) return out
        val ccw = signedArea(p, n) < 0f
        val prev = ints(4, n)
        val next = ints(5, n)
        val reflex = ints(6, n)
        for (i in 0 until n) {
            prev[i] = (i + n - 1) % n
            next[i] = (i + 1) % n
        }
        fun convex(a: Int, b: Int, c: Int): Boolean {
            val cross = (p[b * 2] - p[a * 2]) * (p[c * 2 + 1] - p[a * 2 + 1]) - (p[b * 2 + 1] - p[a * 2 + 1]) * (p[c * 2] - p[a * 2])
            return if (ccw) cross < 0f else cross > 0f
        }
        for (i in 0 until n) reflex[i] = if (convex(prev[i], i, next[i])) 0 else 1
        fun blocked(a: Int, b: Int, c: Int): Boolean {
            var o = next[c]
            while (o != a) {
                if (reflex[o] != 0) {
                    val ox = p[o * 2]; val oy = p[o * 2 + 1]
                    val shared = (ox == p[a * 2] && oy == p[a * 2 + 1]) || (ox == p[b * 2] && oy == p[b * 2 + 1]) || (ox == p[c * 2] && oy == p[c * 2 + 1])
                    if (!shared && pointInTri(ox, oy, p, a, b, c)) return true
                }
                o = next[o]
            }
            return false
        }
        var t = 0
        var size = n
        var b = 0
        var stall = 0
        while (size > 3 && stall < size) {
            val a = prev[b]
            val c = next[b]
            if (reflex[b] != 0 || blocked(a, b, c)) {
                b = c
                stall++
                continue
            }
            out[t++] = a; out[t++] = b; out[t++] = c
            next[a] = c
            prev[c] = a
            size--
            reflex[a] = if (convex(prev[a], a, c)) 0 else 1
            reflex[c] = if (convex(a, c, next[c])) 0 else 1
            b = a
            stall = 0
        }
        if (size == 3) { out[t++] = prev[b]; out[t++] = b; out[t++] = next[b] }
        return if (t == out.size) out else out.copyOf(t)
    }

    private fun pointInTri(px: Float, py: Float, p: FloatArray, a: Int, b: Int, c: Int): Boolean {
        fun side(x1: Float, y1: Float, x2: Float, y2: Float) = (px - x2) * (y1 - y2) - (x1 - x2) * (py - y2)
        val d1 = side(p[a * 2], p[a * 2 + 1], p[b * 2], p[b * 2 + 1])
        val d2 = side(p[b * 2], p[b * 2 + 1], p[c * 2], p[c * 2 + 1])
        val d3 = side(p[c * 2], p[c * 2 + 1], p[a * 2], p[a * 2 + 1])
        val neg = d1 < 0f || d2 < 0f || d3 < 0f
        val pos = d1 > 0f || d2 > 0f || d3 > 0f
        return !(neg && pos)
    }

    private fun flatten(path: PolyPath): List<FloatArray> {
        val loops = ArrayList<FloatArray>()
        var cur = FloatArray(32)
        var size = 0
        var lastX = 0f
        var lastY = 0f
        fun add(x: Float, y: Float) {
            if (size + 2 > cur.size) cur = cur.copyOf(cur.size * 2)
            cur[size++] = x; cur[size++] = y; lastX = x; lastY = y
        }
        fun endLoop() {
            if (size >= 6) loops.add(dedupe(cur, size))
            size = 0
        }
        path.visit(object : PolyPath.Visitor {
            override fun moveTo(x: Float, y: Float) {
                endLoop()
                add(x, y)
            }

            override fun lineTo(x: Float, y: Float) = add(x, y)

            override fun tangentArcTo(x1: Float, y1: Float, x2: Float, y2: Float, radius: Float) {
                val ax = lastX - x1; val ay = lastY - y1
                val bx = x2 - x1; val by = y2 - y1
                val la = hypot(ax, ay); val lb = hypot(bx, by)
                if (la < 1e-4f || lb < 1e-4f || radius <= 0f) { add(x1, y1); return }
                val ux = ax / la; val uy = ay / la
                val vx = bx / lb; val vy = by / lb
                val cosT = (ux * vx + uy * vy).coerceIn(-1f, 1f)
                val theta = acos(cosT)
                if (theta < 1e-4f || theta > Math.PI.toFloat() - 1e-4f) { add(x1, y1); return }
                val dist = radius / tan(theta / 2f)
                val t0x = x1 + ux * dist; val t0y = y1 + uy * dist
                val t1x = x1 + vx * dist; val t1y = y1 + vy * dist
                val bisx = ux + vx; val bisy = uy + vy
                val bl = hypot(bisx, bisy)
                val cd = radius / sin(theta / 2f)
                val ccx = x1 + bisx / bl * cd; val ccy = y1 + bisy / bl * cd
                val a0 = atan2(t0y - ccy, t0x - ccx)
                var a1 = atan2(t1y - ccy, t1x - ccx)
                var sweep = a1 - a0
                while (sweep > Math.PI) sweep -= (2 * Math.PI).toFloat()
                while (sweep < -Math.PI) sweep += (2 * Math.PI).toFloat()
                a1 = a0 + sweep
                add(t0x, t0y)
                val segs = max(2, cornerSegments(radius))
                for (i in 1..segs) {
                    val a = a0 + (a1 - a0) * i / segs
                    add(ccx + cos(a) * radius, ccy + sin(a) * radius)
                }
            }

            override fun close() = endLoop()
        })
        endLoop()
        return loops
    }

    private fun dedupe(p: FloatArray, size: Int = p.size): FloatArray {
        val out = FloatArray(size)
        var n = 0
        var i = 0
        while (i < size) {
            val x = p[i]; val y = p[i + 1]
            if (n < 2 || length(out[n - 2] - x, out[n - 1] - y) > 1e-3f) { out[n++] = x; out[n++] = y }
            i += 2
        }
        if (n >= 4 && length(out[0] - out[n - 2], out[1] - out[n - 1]) <= 1e-3f) n -= 2
        return if (n == out.size) out else out.copyOf(n)
    }

    private fun quadVerts(
        x0: Float, y0: Float, u0: Float, v0: Float, c0: Int, x1: Float, y1: Float, u1: Float, v1: Float, c1: Int,
        x2: Float, y2: Float, u2: Float, v2: Float, c2: Int, x3: Float, y3: Float, u3: Float, v3: Float, c3: Int,
    ) {
        val area = (x0 * y1 - x1 * y0) + (x1 * y2 - x2 * y1) + (x2 * y3 - x3 * y2) + (x3 * y0 - x0 * y3)
        if (recording) {
            record(x0, y0, c0)
            if (area > 0f) {
                record(x3, y3, c3); record(x2, y2, c2); record(x1, y1, c1)
            } else {
                record(x1, y1, c1); record(x2, y2, c2); record(x3, y3, c3)
            }
            return
        }
        if (area > 0f) {
            vertex(x0, y0, u0, v0, c0)
            vertex(x3, y3, u3, v3, c3)
            vertex(x2, y2, u2, v2, c2)
            vertex(x1, y1, u1, v1, c1)
            return
        }
        vertex(x0, y0, u0, v0, c0)
        vertex(x1, y1, u1, v1, c1)
        vertex(x2, y2, u2, v2, c2)
        vertex(x3, y3, u3, v3, c3)
    }

    private val qx = FloatArray(4); private val qy = FloatArray(4); private val qu = FloatArray(4); private val qv = FloatArray(4)
    private val qc = IntArray(4)
    private var qn = 0

    private val wx = FloatArray(4); private val wy = FloatArray(4); private val wu = FloatArray(4); private val wv = FloatArray(4)
    private val wc = IntArray(4)
    private val warpPt = FloatArray(2)

    private var textFill: PolyImageFill? = null
    private var fillSx = 1f
    private var fillSy = 1f
    private var fillTx = 0f
    private var fillTy = 0f

    internal fun textFill(fill: PolyImageFill?) {
        textFill = fill
        val s = state
        fillSx = s.sx; fillSy = s.sy; fillTx = s.tx; fillTy = s.ty
    }

    private fun warpedVertex(x: Float, y: Float, u: Float, v: Float, color: Int) {
        val sh = shading
        if (sh == null) { wu[qn] = u; wv[qn] = v } else { shade(sh, x, y, uvTmp); wu[qn] = uvTmp[0]; wv[qn] = uvTmp[1] }
        wx[qn] = x; wy[qn] = y; wc[qn] = color
        if (++qn < 4) return
        qn = 0
        val s = state
        val q = s.proj
        val fill = textFill
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        var w0 = Float.MAX_VALUE; var w1 = -Float.MAX_VALUE
        val m = s.rot
        for (i in 0 until 4) {
            val rx = if (m == null) wx[i] else m[0] * wx[i] + m[1] * wy[i] + m[4]
            val ry = if (m == null) wy[i] else m[2] * wx[i] + m[3] * wy[i] + m[5]
            var px = rx; var py = ry
            if (q != null) {
                val w = max(q[6] * rx + q[7] * ry + q[8], MIN_W)
                w0 = min(w0, w); w1 = max(w1, w)
                px = (q[0] * rx + q[1] * ry + q[2]) / w
                py = (q[3] * rx + q[4] * ry + q[5]) / w
            }
            x0 = min(x0, px); y0 = min(y0, py); x1 = max(x1, px); y1 = max(y1, py)
        }
        val extent = max(x1 - x0, y1 - y0)
        var n = 1
        if (q != null && w1 > w0 * WARP_FLAT) n = ceil(extent / WARP_STEP).toInt()
        if (fill != null) n = max(n, ceil(extent / FILL_STEP).toInt())
        n = n.coerceIn(1, MAX_WARP)
        val step = 1f / n
        val log = clipLogs.lastOrNull()
        for (j in 0 until n) for (i in 0 until n) {
            warpCorner(0, i * step, j * step, fill)
            warpCorner(1, (i + 1) * step, j * step, fill)
            warpCorner(2, (i + 1) * step, (j + 1) * step, fill)
            warpCorner(3, i * step, (j + 1) * step, fill)
            log?.let { logQuad(it, m != null) }
            cutQuad(s.clip, s.rclip, s.mask, s.hole, s.region)
        }
    }

    private fun warpCorner(k: Int, a: Float, b: Float, fill: PolyImageFill?) {
        val x = bilinear(wx, a, b); val y = bilinear(wy, a, b)
        var c = PolyGradient.lerpArgb(PolyGradient.lerpArgb(wc[0], wc[1], a), PolyGradient.lerpArgb(wc[3], wc[2], a), b)
        if (fill != null) c = tinted(c, fillSample(fill, (x - fillTx) / fillSx, (y - fillTy) / fillSy))
        val pt = warpPt
        pt[0] = x; pt[1] = y
        toDevice(pt)
        qx[k] = pt[0]; qy[k] = pt[1]; qu[k] = bilinear(wu, a, b); qv[k] = bilinear(wv, a, b); qc[k] = c
    }

    private fun bilinear(p: FloatArray, a: Float, b: Float): Float {
        val top = p[0] + (p[1] - p[0]) * a
        val bottom = p[3] + (p[2] - p[3]) * a
        return top + (bottom - top) * b
    }

    private fun fillSample(f: PolyImageFill, x: Float, y: Float): Int {
        val b = f.bitmap
        val bw = b.width; val bh = b.height
        if (bw <= 0 || bh <= 0) return 0
        val fx = f.u(x, y) * bw - 0.5f
        val fy = f.v(x, y) * bh - 0.5f
        val ix = floor(fx).toInt(); val iy = floor(fy).toInt()
        val ax = fx - ix; val ay = fy - iy
        val xa = ix.coerceIn(0, bw - 1); val xb = (ix + 1).coerceIn(0, bw - 1)
        val ya = iy.coerceIn(0, bh - 1); val yb = (iy + 1).coerceIn(0, bh - 1)
        val px = b.pixels
        var out = 0
        val o00 = (ya * bw + xa) * 4; val o10 = (ya * bw + xb) * 4
        val o01 = (yb * bw + xa) * 4; val o11 = (yb * bw + xb) * 4
        for (ch in 0 until 4) {
            val c00 = (px[o00 + ch].toInt() and 0xFF).toFloat(); val c10 = (px[o10 + ch].toInt() and 0xFF).toFloat()
            val c01 = (px[o01 + ch].toInt() and 0xFF).toFloat(); val c11 = (px[o11 + ch].toInt() and 0xFF).toFloat()
            val top = c00 + (c10 - c00) * ax
            val bottom = c01 + (c11 - c01) * ax
            val shift = if (ch == 3) 24 else 16 - ch * 8
            out = out or ((top + (bottom - top) * ay + 0.5f).toInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    private fun tinted(color: Int, fill: Int): Int {
        val fa = fill ushr 24
        val a = ((color ushr 24) * fa + 127) / 255
        var out = a shl 24
        var shift = 0
        while (shift < 24) {
            var ch = ((color ushr shift) and 0xFF) * ((fill ushr shift) and 0xFF) / 255
            if (batchPremul) ch = ch * fa / 255
            out = out or (ch shl shift)
            shift += 8
        }
        return out
    }

    private fun vertex(x: Float, y: Float, u: Float, v: Float, color: Int) {
        if (discarding) return
        val s = state
        if (textFill != null || s.proj != null) return warpedVertex(x, y, u, v, color)
        val m = s.rot
        val n = qn
        if (m == null) { qx[n] = x; qy[n] = y } else { qx[n] = m[0] * x + m[1] * y + m[4]; qy[n] = m[2] * x + m[3] * y + m[5] }
        val sh = shading
        if (sh == null) { qu[n] = u; qv[n] = v } else { shade(sh, x, y, uvTmp); qu[n] = uvTmp[0]; qv[n] = uvTmp[1] }
        qc[n] = color
        if (n < 3) {
            qn = n + 1
            return
        }
        qn = 0
        clipLogs.lastOrNull()?.let { logQuad(it, m != null) }
        cutQuad(s.clip, s.rclip, s.mask, s.hole, s.region)
    }

    private fun cutQuad(box: FloatArray?, clipped: RClip?, masked: Mask?, hole: RClip?, region: Region?) {
        var crosses = 0
        if (box != null) {
            val bl = box[0]; val bt = box[1]; val br = box[2]; val bb = box[3]
            if (allAtMost(qx, bl) || allAtLeast(qx, br) || allAtMost(qy, bt) || allAtLeast(qy, bb)) return
            if (!allWithin(qx, bl, br) || !allWithin(qy, bt, bb)) {
                val x0 = min(min(qx[0], qx[1]), min(qx[2], qx[3])); val x1 = max(max(qx[0], qx[1]), max(qx[2], qx[3]))
                val y0 = min(min(qy[0], qy[1]), min(qy[2], qy[3])); val y1 = max(max(qy[0], qy[1]), max(qy[2], qy[3]))
                if (x0 < bl) crosses = 1
                if (x1 > br) crosses = crosses or 2
                if (y0 < bt) crosses = crosses or 4
                if (y1 > bb) crosses = crosses or 8
                if (crosses != 0 && clearOutside(box)) crosses = 0
            }
        }
        var rclip = clipped
        while (rclip != null && quadInside(rclip)) rclip = rclip.parent
        var mask = masked
        while (mask != null && maskOpaque(mask.fade)) mask = mask.parent
        if (crosses == 0 && rclip == null && mask == null && hole == null && region == null) {
            emitQuad()
        } else {
            polyUsed = 0
            val quad = poly()
            for (i in 0 until 4) quad.add(qx[i], qy[i], qu[i], qv[i], qc[i])
            var polys = clipA
            polys.clear()
            if (crosses == 0) polys.add(quad) else boxCut(quad, box!!, crosses, polys)
            if (rclip != null && polys.isNotEmpty()) polys = clipQuad(polys, rclip, ::cut)
            if (hole != null && polys.isNotEmpty()) polys = clipQuad(polys, hole, ::cutHole)
            var rg = region
            while (rg != null && polys.isNotEmpty()) {
                val next = other(polys)
                for (i in polys.indices) cutRegion(polys[i], rg, next)
                polys = next
                rg = rg.parent
            }
            while (mask != null && polys.isNotEmpty()) {
                val m = mask.fade
                val next = other(polys)
                for (i in polys.indices) faded(polys[i], m, next)
                polys = next
                mask = mask.parent
            }
            for (i in polys.indices) {
                val p = polys[i]
                if (!clear(p)) emitFan(p)
            }
        }
    }

    private val polyPool = ArrayList<Poly>()
    private var polyUsed = 0
    private val clipA = ArrayList<Poly>()
    private val clipB = ArrayList<Poly>()
    private val cornerCols = ArrayList<Poly>(3)

    private fun poly(): Poly {
        if (polyUsed == polyPool.size) polyPool.add(Poly())
        val p = polyPool[polyUsed++]
        p.size = 0
        return p
    }

    private fun other(list: ArrayList<Poly>): ArrayList<Poly> {
        val next = if (list === clipA) clipB else clipA
        next.clear()
        return next
    }

    private fun clear(p: Poly): Boolean {
        for (i in 0 until p.size) if (if (batchPremul) p.c[i] != 0 else p.c[i] ushr 24 != 0) return false
        return true
    }

    private fun maskOpaque(fade: PolyFade): Boolean {
        val pos = if (fade.vertical) qy else qx
        val span = fade.span(pos[0])
        if (!fade.opaque(span)) return false
        for (i in 1 until 4) if (fade.span(pos[i]) != span) return false
        return true
    }

    private fun faded(p: Poly, fade: PolyFade, out: ArrayList<Poly>) {
        val from = out.size
        var rest = p
        for (stop in fade.stops) {
            if (rest.size < 3) break
            val before = poly()
            rest = splitAxis(rest, fade.vertical, stop, before)
            if (before.size >= 3) out.add(before)
        }
        if (rest.size >= 3) out.add(rest)
        val tint = fade.colors != null
        for (k in from until out.size) {
            val piece = out[k]
            for (i in 0 until piece.size) {
            val pos = if (fade.vertical) piece.y[i] else piece.x[i]
                val c = coverColor(piece.c[i], fade.alphaAt(pos), batchPremul)
                piece.c[i] = if (tint) modulate(c, fade.colorAt(pos)) else c
            }
        }
    }

    private fun modulate(color: Int, rgb: Int): Int {
        var out = color and -0x1000000
        var shift = 0
        while (shift < 24) {
            out = out or ((((color ushr shift) and 0xFF) * ((rgb ushr shift) and 0xFF) + 127) / 255 shl shift)
            shift += 8
        }
        return out
    }

    private fun clearOutside(box: FloatArray): Boolean {
        val e = 0.5f + 1e-3f
        for (i in 0 until 4) {
            val x = qx[i]; val y = qy[i]
            if (x >= box[0] && x <= box[2] && y >= box[1] && y <= box[3]) continue
            if (x < box[0] - e || x > box[2] + e || y < box[1] - e || y > box[3] + e) return false
            if (if (batchPremul) qc[i] != 0 else qc[i] ushr 24 != 0) return false
        }
        return true
    }

    private fun boxCut(p: Poly, box: FloatArray, edges: Int, out: ArrayList<Poly>) {
        var rest = p
        if (edges and 1 != 0) rest = splitAxis(rest, false, box[0], null)
        if (edges and 2 != 0 && rest.size >= 3) rest = splitAxis(rest, false, box[2], null, -1f)
        if (edges and 4 != 0 && rest.size >= 3) rest = splitAxis(rest, true, box[1], null)
        if (edges and 8 != 0 && rest.size >= 3) rest = splitAxis(rest, true, box[3], null, -1f)
        if (rest.size >= 3) out.add(rest)
    }

    private fun splitAxis(p: Poly, vertical: Boolean, at: Float, before: Poly?, sign: Float = 1f): Poly {
        val after = poly()
        for (i in 0 until p.size) {
            val j = (i + 1) % p.size
            val di = ((if (vertical) p.y[i] else p.x[i]) - at) * sign
            val dj = ((if (vertical) p.y[j] else p.x[j]) - at) * sign
            if (di >= 0f) after.addFrom(p, i) else before?.addFrom(p, i)
            if ((di >= 0f) != (dj >= 0f)) {
                val t = di / (di - dj)
                val x = p.x[i] + (p.x[j] - p.x[i]) * t; val y = p.y[i] + (p.y[j] - p.y[i]) * t
                val u = p.u[i] + (p.u[j] - p.u[i]) * t; val v = p.v[i] + (p.v[j] - p.v[i]) * t
                val c = PolyGradient.lerpArgb(p.c[i], p.c[j], t)
                after.add(x, y, u, v, c)
                before?.add(x, y, u, v, c)
            }
        }
        return after
    }

    private fun allAtMost(p: FloatArray, at: Float): Boolean = p[0] <= at && p[1] <= at && p[2] <= at && p[3] <= at

    private fun allAtLeast(p: FloatArray, at: Float): Boolean = p[0] >= at && p[1] >= at && p[2] >= at && p[3] >= at

    private fun allWithin(p: FloatArray, lo: Float, hi: Float): Boolean {
        for (i in 0 until 4) {
            val v = p[i]
            if (!(v >= lo && v <= hi)) return false
        }
        return true
    }

    private fun emitQuad() {
        var n = count
        if (n + 4 > colors.size) {
            val cap = colors.size * 2
            colors = colors.copyOf(cap)
            verts = verts.copyOf(cap * 4)
        }
        val vs = verts
        val cs = colors
        val tracked = boxCount > 0
        val o = boxCount * BOX - BOX
        val bx = boxes
        var tl = if (tracked) bx[o] else 0f
        var tt = if (tracked) bx[o + 1] else 0f
        var tr = if (tracked) bx[o + 2] else 0f
        var tb = if (tracked) bx[o + 3] else 0f
        var l = minX; var t = minY; var r = maxX; var b = maxY
        for (i in 0 until 4) {
            val x = qx[i]; val y = qy[i]
            val k = n * 4
            vs[k] = x; vs[k + 1] = y; vs[k + 2] = qu[i]; vs[k + 3] = qv[i]
            cs[n++] = qc[i]
            if (tracked) {
                if (x < tl) tl = x
                if (y < tt) tt = y
                if (x > tr) tr = x
                if (y > tb) tb = y
            }
            if (x < l) l = x
            if (y < t) t = y
            if (x > r) r = x
            if (y > b) b = y
        }
        count = n
        if (tracked) {
            bx[o] = tl; bx[o + 1] = tt; bx[o + 2] = tr; bx[o + 3] = tb
        }
        minX = l; minY = t; maxX = r; maxY = b
    }

    private fun emit(x: Float, y: Float, u: Float, v: Float, color: Int) {
        if (count == colors.size) {
            colors = colors.copyOf(count * 2)
            verts = verts.copyOf(count * 8)
        }
        val o = count * 4
        verts[o] = x; verts[o + 1] = y; verts[o + 2] = u; verts[o + 3] = v
        colors[count++] = color
        if (boxCount > 0) touch(x, y, x, y)
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    private fun edgeSide(clip: FloatArray, e: Int, x: Float, y: Float): Float {
        val n = clip.size / 2
        val ax = clip[e * 2]; val ay = clip[e * 2 + 1]
        val bx = clip[(e + 1) % n * 2]; val by = clip[(e + 1) % n * 2 + 1]
        return (bx - ax) * (y - ay) - (by - ay) * (x - ax)
    }

    private fun quadInside(clip: RClip): Boolean {
        for (i in 0 until 4) if (!inside(clip, qx[i], qy[i])) return false
        return true
    }

    private fun inside(clip: RClip, x: Float, y: Float) = inSafe(clip, x, y) || insideConvex(clip.inner, x, y)

    private fun inSafe(clip: RClip, x: Float, y: Float): Boolean {
        val s = clip.safe
        return (x >= s[0] && x <= s[2] && y >= s[1] && y <= s[3]) || (x >= s[4] && x <= s[6] && y >= s[5] && y <= s[7])
    }

    private fun insideConvex(clip: FloatArray, x: Float, y: Float): Boolean {
        for (e in 0 until clip.size / 2) if (edgeSide(clip, e, x, y) < 0f) return false
        return true
    }

    private class Poly {
        var x = FloatArray(8); var y = FloatArray(8); var u = FloatArray(8); var v = FloatArray(8)
        var c = IntArray(8)
        var size = 0

        fun add(px: Float, py: Float, pu: Float, pv: Float, pc: Int) {
            if (size == c.size) {
                val n = size * 2
                x = x.copyOf(n); y = y.copyOf(n); u = u.copyOf(n); v = v.copyOf(n); c = c.copyOf(n)
            }
            x[size] = px; y[size] = py; u[size] = pu; v[size] = pv; c[size] = pc
            size++
        }

        fun addFrom(p: Poly, i: Int) = add(p.x[i], p.y[i], p.u[i], p.v[i], p.c[i])
    }

    private fun split(p: Poly, clip: FloatArray, e: Int, outside: Poly?): Poly {
        var allIn = true
        var allOut = true
        for (i in 0 until p.size) {
            if (edgeSide(clip, e, p.x[i], p.y[i]) >= 0f) allOut = false else allIn = false
        }
        if (allIn) return p
        if (allOut) {
            if (outside != null) for (i in 0 until p.size) outside.addFrom(p, i)
            return poly()
        }
        val inside = poly()
        for (i in 0 until p.size) {
            val j = (i + 1) % p.size
            val di = edgeSide(clip, e, p.x[i], p.y[i])
            val dj = edgeSide(clip, e, p.x[j], p.y[j])
            if (di >= 0f) inside.addFrom(p, i) else outside?.addFrom(p, i)
            if ((di >= 0f) != (dj >= 0f)) {
                val t = di / (di - dj)
                val x = p.x[i] + (p.x[j] - p.x[i]) * t; val y = p.y[i] + (p.y[j] - p.y[i]) * t
                val u = p.u[i] + (p.u[j] - p.u[i]) * t; val v = p.v[i] + (p.v[j] - p.v[i]) * t
                val c = PolyGradient.lerpArgb(p.c[i], p.c[j], t)
                inside.add(x, y, u, v, c)
                outside?.add(x, y, u, v, c)
            }
        }
        return inside
    }

    private inline fun clipQuad(quad: ArrayList<Poly>, clip: RClip, cut: (Poly, RClip, MutableList<Poly>) -> Unit): ArrayList<Poly> {
        var polys = quad
        var c: RClip? = clip
        while (c != null && polys.isNotEmpty()) {
            val next = other(polys)
            for (i in polys.indices) cut(polys[i], c, next)
            polys = next
            c = c.parent
        }
        return polys
    }

    // Keeps what lies outside the hole, fading out across the pixel straddling its edge
    private fun cutHole(p: Poly, hole: RClip, out: MutableList<Poly>) {
        var rest = p
        for (e in 0 until 4) {
            val outside = poly()
            rest = split(rest, hole.outer, e, outside)
            if (outside.size >= 3) out.add(outside)
            if (rest.size < 3) return
        }
        for (e in 0 until 4) {
            rest = band(rest, hole.inner, e, corners = true) { b ->
                for (i in 0 until b.size) b.c[i] = coverColor(b.c[i], 1f - coverage(hole, b.x[i], b.y[i]), batchPremul)
                out.add(b)
            }
            if (rest.size < 3) return
        }
    }

    // Splits off what lies past inner edge e; with corners, also where later edges' fades start, so each piece's fade stays linear
    private inline fun band(rest: Poly, inner: FloatArray, e: Int, corners: Boolean, add: (Poly) -> Unit): Poly {
        var past = poly()
        val inside = split(rest, inner, e, past)
        if (corners && past.size >= 3) {
            var corner = poly()
            for (f in e + 1 until inner.size / 2) {
                past = split(past, inner, f, corner)
                if (corner.size > 0) {
                    if (corner.size >= 3) add(corner)
                    corner = poly()
                }
                if (past.size < 3) break
            }
        }
        if (past.size >= 3) add(past)
        return inside
    }

    private fun cut(p: Poly, clip: RClip, out: MutableList<Poly>) {
        for (i in 0 until p.size) if (!inside(clip, p.x[i], p.y[i])) return cutCorners(p, clip, out)
        out.add(p)
    }

    private fun cutCorners(p: Poly, clip: RClip, out: MutableList<Poly>) {
        val k = clip.box
        val cols = cornerCols
        cols.clear()
        var rest = p
        for (side in 0 until 2) {
            val x = if (side == 0) k[0] + k[4] else k[2] - k[4]
            if (!crosses(rest.x, rest.size, x)) continue
            val before = poly()
            rest = splitAxis(rest, false, x, before)
            if (before.size >= 3) cols.add(before)
        }
        if (rest.size >= 3) cols.add(rest)
        for (ci in cols.indices) {
            var r = cols[ci]
            for (side in 0 until 2) {
                val y = if (side == 0) k[1] + k[5] else k[3] - k[5]
                if (!crosses(r.y, r.size, y)) continue
                val before = poly()
                r = splitAxis(r, true, y, before)
                if (before.size >= 3) cutInside(before, clip, out)
            }
            if (r.size >= 3) cutInside(r, clip, out)
        }
    }

    private fun crosses(v: FloatArray, n: Int, at: Float): Boolean {
        var lo = false
        var hi = false
        for (i in 0 until n) {
            if (v[i] < at - 1f) lo = true
            if (v[i] > at + 1f) hi = true
        }
        return lo && hi
    }

    private fun cutInside(p: Poly, clip: RClip, out: MutableList<Poly>) {
        for (i in 0 until p.size) if (!inside(clip, p.x[i], p.y[i])) return cutPiece(p, clip, out)
        out.add(p)
    }

    private fun cutPiece(p: Poly, clip: RClip, out: MutableList<Poly>) {
        var rest = p
        for (e in 0 until clip.outer.size / 2) {
            if (rest.size < 3) return
            rest = split(rest, clip.outer, e, null)
        }
        for (e in 0 until clip.inner.size / 2) {
            if (rest.size < 3) return
            // cutCorners already keeps a rounded rect's straight sides apart from its corners
            rest = band(rest, clip.inner, e, corners = !clip.rrect) { out.add(covered(it, clip)) }
        }
        if (rest.size >= 3) out.add(covered(rest, clip))
    }

    private fun covered(p: Poly, clip: RClip): Poly {
        for (i in 0 until p.size) if (!inSafe(clip, p.x[i], p.y[i])) p.c[i] = coverColor(p.c[i], coverage(clip, p.x[i], p.y[i]), batchPremul)
        return p
    }

    private fun cutRegion(p: Poly, g: Region, out: MutableList<Poly>) {
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
        for (i in 0 until p.size) {
            x0 = min(x0, p.x[i]); x1 = max(x1, p.x[i])
            y0 = min(y0, p.y[i]); y1 = max(y1, p.y[i])
        }
        var rest = p
        val box = g.box
        if (box != null) {
            if (x1 <= box[0] || x0 >= box[2] || y1 <= box[1] || y0 >= box[3]) { out.add(p); return }
            if (x0 < box[0]) rest = keepPast(rest, false, box[0], 1f, out)
            if (rest.size >= 3 && x1 > box[2]) rest = keepPast(rest, false, box[2], -1f, out)
            if (rest.size >= 3 && y0 < box[1]) rest = keepPast(rest, true, box[1], 1f, out)
            if (rest.size >= 3 && y1 > box[3]) rest = keepPast(rest, true, box[3], -1f, out)
            if (rest.size < 3) return
            x0 = max(x0, box[0]); y0 = max(y0, box[1]); x1 = min(x1, box[2]); y1 = min(y1, box[3])
        } else {
            val b = g.bounds
            if (x1 <= b[0] || x0 >= b[2] || y1 <= b[1] || y0 >= b[3]) return
        }
        val pieces = g.pieces
        var lo = 0
        var hi = pieces.size
        val bottoms = g.bottoms
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (bottoms[mid] <= y0) lo = mid + 1 else hi = mid
        }
        for (k in lo until pieces.size) {
            val pc = pieces[k]
            if (pc.t >= y1) break
            if (pc.b <= y0 || pc.r <= x0 || pc.l >= x1) continue
            var q = poly()
            for (i in 0 until rest.size) q.addFrom(rest, i)
            for (line in pc.outer) {
                q = split(q, line, 0, null)
                if (q.size < 3) break
            }
            if (q.size < 3) continue
            for (e in pc.inner.indices) {
                val past = poly()
                q = split(q, pc.inner[e], 0, past)
                var rest2 = past
                for (f in e + 1 until pc.inner.size) {
                    if (rest2.size < 3) break
                    val corner = poly()
                    rest2 = split(rest2, pc.inner[f], 0, corner)
                    if (corner.size >= 3) out.add(regionCovered(corner, pc))
                }
                if (rest2.size >= 3) out.add(regionCovered(rest2, pc))
                if (q.size < 3) break
            }
            if (q.size >= 3) out.add(q)
        }
    }

    private fun keepPast(p: Poly, vertical: Boolean, at: Float, sign: Float, out: MutableList<Poly>): Poly {
        val outside = poly()
        val rest = splitAxis(p, vertical, at, outside, sign)
        if (outside.size >= 3) out.add(outside)
        return rest
    }

    private fun regionCovered(p: Poly, pc: RegionPiece): Poly {
        for (i in 0 until p.size) {
            var d = Float.MAX_VALUE
            for (e in pc.cover.indices) d = min(d, edgeSide(pc.cover[e], 0, p.x[i], p.y[i]) * pc.invLen[e])
            p.c[i] = coverColor(p.c[i], (d + 0.5f).coerceIn(0f, 1f), batchPremul)
        }
        return p
    }

    private fun emitFan(p: Poly) {
        if (p.size < 3) return
        var i = 1
        while (i + 1 < p.size) {
            val k = if (i + 2 < p.size) i + 2 else i + 1
            emit(p.x[0], p.y[0], p.u[0], p.v[0], p.c[0])
            emit(p.x[i], p.y[i], p.u[i], p.v[i], p.c[i])
            emit(p.x[i + 1], p.y[i + 1], p.u[i + 1], p.v[i + 1], p.c[i + 1])
            emit(p.x[k], p.y[k], p.u[k], p.v[k], p.c[k])
            i += 2
        }
    }

    private fun coverage(clip: RClip, x: Float, y: Float): Float {
        var d = Float.MAX_VALUE
        for (e in 0 until clip.invLen.size) d = min(d, edgeSide(clip.edge, e, x, y) * clip.invLen[e])
        return (d + 0.5f).coerceIn(0f, 1f)
    }

    private fun coverColor(color: Int, cov: Float, premul: Boolean): Int {
        if (cov >= 1f) return color
        if (!premul) return mulAlpha(color, cov)
        var out = 0
        var shift = 0
        while (shift <= 24) {
            out = out or ((((color ushr shift) and 0xFF) * cov).roundToInt().coerceIn(0, 255) shl shift)
            shift += 8
        }
        return out
    }

    private class ShadowMask(val bitmap: PolyBitmap, val slice: Float, val extent: Float, val width: Float)

    private var lastMask: ShadowMask? = null
    private var lastMaskR = -1
    private var lastMaskQ = -1

    public companion object {
        private const val MAX_FADE_DRAWS = 16
        private const val STATE_POOL = 32
        private const val BOX = 6
        private const val BOX_SELF = 4
        private const val BOX_BLEND = 5
        private const val DEFAULT_MITER_LIMIT = 4f
        private const val KEY_EXACT: Byte = 0
        private const val KEY_X: Byte = 1
        private const val KEY_Y: Byte = 2
        private const val KEY_CX: Byte = 3
        private const val KEY_CY: Byte = 4
        private const val CLIP_FLUSH = -1
        private const val CLIP_DRAW = -2
        private const val CLIP_IMPURE = -3
        private const val CLIP_BEGIN = -4
        private const val MAX_PATH_MESHES = 64
        private const val MAX_PATH_MESH_VERTICES = 16384
        private const val ALIGN_EPSILON = 1e-3f
        private const val MAX_REGIONS = 16
        private const val SIDE_TOP = 0
        private const val SIDE_RIGHT = 1
        private const val SIDE_BOTTOM = 2
        private const val SIDE_LEFT = 3
        private const val SIDE_CUT = 4
        private const val CAMERA_UNIT = 72f
        private const val MIN_W = 0.05f
        private const val WARP_FLAT = 1.002f
        private const val WARP_STEP = 24f
        private const val FILL_STEP = 12f
        private const val MAX_WARP = 8

        private const val CULL_PAD = 2f
        private const val MAX_RRECT_SHAPES = 32
        private const val TEXT_ASCENT_REACH = 3f
        private const val TEXT_DESCENT_REACH = 2f
        private const val MC_LINE_REACH = 16f
        private const val MC_LINE_HEIGHT = 9f

        private const val NEAREST_TIE_BIAS = 1f / 256f
        private const val STRICT_EDGE_EPSILON = 1e-5f
        private const val STRICT_CENTER_EPSILON = 2e-3f
        private const val EDGE_EPSILON = 1f / 512f

        // Holes are only cut along their edges, so they need no safe areas or box
        private val NONE = FloatArray(0)

        // Inverted, so no point is known to be inside a polygon without testing its edges
        private val NO_SAFE = floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, -1f, -1f)

        public fun layerTargetSize(current: Int, needed: Int): Int = if (current >= needed) current else (needed + 63) / 64 * 64

        private fun length(x: Float, y: Float): Float = sqrt(x.toDouble() * x + y.toDouble() * y).toFloat()

        internal fun isConvexLoop(p: FloatArray, n: Int): Boolean {
            var start = 0
            while (start < n && p[start * 2] == p[(start + 1) % n * 2] && p[start * 2 + 1] == p[(start + 1) % n * 2 + 1]) start++
            if (start == n) return false
            var px = p[(start + 1) % n * 2] - p[start * 2]
            var py = p[(start + 1) % n * 2 + 1] - p[start * 2 + 1]
            var dirX = px
            var flips = 0
            var turn = 0f
            for (k in 1..n) {
                val a = (start + k) % n
                val b = (a + 1) % n
                val ex = p[b * 2] - p[a * 2]
                val ey = p[b * 2 + 1] - p[a * 2 + 1]
                if (ex == 0f && ey == 0f) continue
                val cross = px * ey - py * ex
                if (cross == 0f) {
                    if (px * ex + py * ey < 0f) return false
                } else if (turn == 0f) {
                    turn = cross
                } else if ((cross > 0f) != (turn > 0f)) {
                    return false
                }
                if (ex != 0f) {
                    if (dirX != 0f && (ex > 0f) != (dirX > 0f)) flips++
                    dirX = ex
                }
                px = ex
                py = ey
            }
            return turn != 0f && flips <= 2
        }
        public const val FALLBACK_FONT: String = "unifont"

        private val cornerTrigs = arrayOfNulls<FloatArray>(17)

        private fun cornerTrig(segs: Int): FloatArray = cornerTrigs[segs] ?: FloatArray(4 * (segs + 1) * 2).also { t ->
            var k = 0
            for (corner in 0 until 4) {
                val start = Math.PI.toFloat() * (1f + corner * 0.5f)
                for (i in 0..segs) {
                    val a = start + (Math.PI.toFloat() / 2f) * i / segs
                    t[k++] = cos(a)
                    t[k++] = sin(a)
                }
            }
            cornerTrigs[segs] = t
        }

        private fun <K, V> lru(max: Int): MutableMap<K, V> = Collections.synchronizedMap(object : LinkedHashMap<K, V>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?) = size > max
        })

        private val ramps = lru<Pair<List<PolyColor>, List<Float>?>, PolyBitmap>(64)
        private data class BoxKey(val gradient: PolyGradient, val aspect: Int, val w: Int, val h: Int)

        private val boxRasters = LinkedHashMap<BoxKey, PolyBitmap>(16, 0.75f, true)
        private var boxBytes = 0L
        private val masks = lru<Long, ShadowMask>(32)
        private val mcOutlines = arrayOf(lru<String, FloatArray>(256), lru<String, FloatArray>(256))

        @Volatile
        private var mcOutlinesRevision = -1

        private fun mcOutlineRects(r: PolyGlyphPage.Rasterizer, line: String, literal: Boolean, shared: Boolean): FloatArray {
            if (!shared) return mcCpuOutlineRects(r, line, literal)
            val revision = FontManager.revision
            if (revision != mcOutlinesRevision) {
                for (m in mcOutlines) m.clear()
                mcOutlinesRevision = revision
            }
            return mcOutlines[if (literal) 1 else 0].getOrPut(line) { mcOutline(r, line, literal, mcRasters, mcInks) }
        }

        private class McCpuCache(val revision: Int) {
            val outlines = arrayOf(lru<String, FloatArray>(256), lru<String, FloatArray>(256))
            val rasters: MutableMap<Pair<PolyGlyphPage.Face, Int>, PolyGlyphPage.Raster?> = Collections.synchronizedMap(HashMap())
            val inks: Array<MutableMap<Any, McInk?>> = Array(2) { Collections.synchronizedMap(WeakHashMap()) }
        }

        @Volatile
        private var mcCpu = McCpuCache(-1)
        private val mcCpuLock = Any()

        private fun mcCpuCache(): McCpuCache {
            val revision = FontManager.revision
            mcCpu.let { if (it.revision >= revision) return it }
            synchronized(mcCpuLock) {
                val c = mcCpu
                if (c.revision >= revision) return c
                return McCpuCache(revision).also { mcCpu = it }
            }
        }

        private fun mcCpuOutlineRects(r: PolyGlyphPage.Rasterizer, line: String, literal: Boolean): FloatArray {
            val c = mcCpuCache()
            val outlines = c.outlines[if (literal) 1 else 0]
            outlines[line]?.let { return it }
            val rects = mcOutline(r, line, literal, c.rasters, c.inks)
            return outlines.putIfAbsent(line, rects) ?: rects
        }

        private const val FACE_CACHE = 4
        private const val MC_PUNCH = -0x1000000
        private const val MC_GLYPH_CACHE = 0x800
        private const val MAX_CORNER_SEGMENTS = 16
        private const val RRECT_SLOTS = 2
        private const val MC_FACE = "minecraft"
        private const val MC_BOLD_FACE = "minecraft-bold"
        private val mcRasters = HashMap<Pair<PolyGlyphPage.Face, Int>, PolyGlyphPage.Raster?>()
        // upright and italic, keyed by the McGlyph or face raster
        private val mcInks = Array(2) { WeakHashMap<Any, McInk?>() }

        private const val MC_GRID = 2
        private const val MC_RUN_MASK = 0x1FFFFF

        // a glyph's ink grown by the outline width, as [start, end) runs per row, relative to the pen on the baseline
        private class McInk(val left: Int, val top: Int, val rows: Array<IntArray>)

        // nearest-samples the glyph's alpha at outline grid resolution, positioned like a face raster on the baseline
        private fun mcRaster(g: McGlyph): PolyGlyphPage.Raster? {
            val b = g.bitmap ?: return null
            val w = (g.width * MC_GRID).roundToInt()
            val h = (g.height * MC_GRID).roundToInt()
            if (w <= 0 || h <= 0) return null
            val coverage = ByteArray(w * h) { k ->
                val sx = ((g.u0 + (g.u1 - g.u0) * (k % w + 0.5f) / w) * b.width).toInt().coerceIn(0, b.width - 1)
                val sy = ((g.v0 + (g.v1 - g.v0) * (k / w + 0.5f) / h) * b.height).toInt().coerceIn(0, b.height - 1)
                b.pixels[(sy * b.width + sx) * 4 + 3]
            }
            return PolyGlyphPage.Raster(0, ((g.top - 7f) * MC_GRID).roundToInt(), w, h, coverage)
        }

        private fun mcInk(g: PolyGlyphPage.Raster, italic: Boolean): McInk? {
            if (g.w <= 0 || g.h <= 0) return null
            val grow = MC_GRID
            // italic rows lean by their height above the line top, which sits 7 units over the baseline
            val lean = IntArray(g.h) { if (italic) ((1f - 0.25f * ((7 * MC_GRID + g.offY + it + 0.5f) / MC_GRID)) * MC_GRID).roundToInt() else 0 }
            val lo = lean.min() - grow
            val w = g.w + lean.max() + grow - lo
            val h = g.h + 2 * grow
            val grown = BooleanArray(w * h)
            for (py in 0 until g.h) for (px in 0 until g.w) {
                if ((g.coverage[py * g.w + px].toInt() and 0xFF) < 128) continue
                val x = px + lean[py] - lo - grow
                for (y in py..py + 2 * grow) grown.fill(true, y * w + x, y * w + x + 2 * grow + 1)
            }
            val rows = Array(h) { y ->
                val runs = ArrayList<Int>()
                var x = 0
                while (x < w) {
                    if (!grown[y * w + x]) { x++; continue }
                    val start = x
                    while (x < w && grown[y * w + x]) x++
                    runs += start + lo + g.offX; runs += x + lo + g.offX
                }
                runs.toIntArray()
            }
            return McInk(lo + g.offX, g.offY - grow, rows)
        }

        // unions the cached grown glyphs row by row, so a changed text costs its runs rather than its whole bounding block
        private fun mcOutline(
            r: PolyGlyphPage.Rasterizer, text: String, literal: Boolean,
            rasters: MutableMap<Pair<PolyGlyphPage.Face, Int>, PolyGlyphPage.Raster?>, inkCaches: Array<out MutableMap<Any, McInk?>>,
        ): FloatArray {
            val inks = ArrayList<McInk>()
            val at = ArrayList<Int>()
            val effects = ArrayList<Int>()
            text.lines().forEachIndexed { index, line ->
                val lineTop = index * 9
                val baseline = (lineTop + 7) * MC_GRID
                var pen = 0f
                var bold = false
                var italic = false
                var under = false
                var strike = false
                var i = 0
                while (i < line.length) {
                    val cp = line.codePointAt(i)
                    i += Character.charCount(cp)
                    if (!literal && cp == 0xA7 && i < line.length) {
                        val code = MC_CODES.indexOf(line[i++].lowercaseChar())
                        when (code) {
                            in 0..15, 17 -> { bold = false; italic = false; under = false; strike = false }
                            16 -> bold = true
                            18 -> strike = true
                            19 -> under = true
                            20 -> italic = true
                        }
                        continue
                    }
                    fun place(g: McInk?, x: Float) {
                        if (g == null) return
                        inks += g
                        at += (x * MC_GRID).roundToInt()
                        at += baseline
                    }
                    val start = pen
                    val cache = inkCaches[if (italic) 1 else 0]
                    val rg = McGlyphs.source?.invoke(cp)
                    if (rg != null) {
                        val g = cache.getOrPut(rg) { mcRaster(rg)?.let { mcInk(it, italic) } }
                        place(g, pen)
                        if (bold) place(g, pen + rg.offset)
                        pen += rg.advance + if (bold) rg.offset else 0f
                    } else {
                        val main = r.face(if (bold) "minecraft-bold" else "minecraft")
                        val face: PolyGlyphPage.Face
                        val em: Float
                        if (main != null && main.glyph(cp) != 0) { face = main; em = 10f } else { face = r.face("unifont") ?: continue; em = 8f }
                        val id = face.glyph(cp)
                        val raster = rasters.getOrPut(face to id) { face.raster(id, em * MC_GRID) }
                        place(raster?.let { cache.getOrPut(it) { mcInk(it, italic) } }, pen)
                        pen += face.advance(id) * em
                    }
                    fun effect(top: Float) {
                        effects += ((start - 1f) * MC_GRID).roundToInt(); effects += ((lineTop + top) * MC_GRID).roundToInt()
                        effects += (pen * MC_GRID).roundToInt(); effects += ((lineTop + top + 1f) * MC_GRID).roundToInt()
                    }
                    if (under) effect(8f)
                    if (strike) effect(3.5f)
                }
            }
            if (inks.isEmpty() && effects.isEmpty()) return FloatArray(0)
            val grow = MC_GRID
            var x0 = Int.MAX_VALUE; var y0 = Int.MAX_VALUE; var y1 = Int.MIN_VALUE; var n = 0
            for (k in inks.indices) {
                val g = inks[k]
                x0 = min(x0, at[k * 2] + g.left); y0 = min(y0, at[k * 2 + 1] + g.top); y1 = max(y1, at[k * 2 + 1] + g.top + g.rows.size)
                for (row in g.rows) n += row.size / 2
            }
            for (k in effects.indices step 4) {
                x0 = min(x0, effects[k] - grow); y0 = min(y0, effects[k + 1] - grow); y1 = max(y1, effects[k + 3] + grow)
                n += effects[k + 3] - effects[k + 1] + 2 * grow
            }
            // packed as (row, start, end) so that one sort orders them by row, then start
            val runs = LongArray(n)
            var m = 0
            fun run(y: Int, s: Int, e: Int) { runs[m++] = ((y - y0).toLong() shl 42) or ((s - x0).toLong() shl 21) or (e - x0).toLong() }
            for (k in inks.indices) {
                val g = inks[k]
                val x = at[k * 2]
                val y = at[k * 2 + 1] + g.top
                for (ry in g.rows.indices) { val row = g.rows[ry]; for (i in row.indices step 2) run(y + ry, x + row[i], x + row[i + 1]) }
            }
            for (k in effects.indices step 4) {
                if (effects[k + 2] <= effects[k] || effects[k + 3] <= effects[k + 1]) continue
                for (y in effects[k + 1] - grow until effects[k + 3] + grow) run(y, effects[k] - grow, effects[k + 2] + grow)
            }
            runs.sort(0, m)
            var out = FloatArray(64)
            var o = 0
            fun emit(s: Int, e: Int, top: Int, bottom: Int) {
                if (o + 4 > out.size) out = out.copyOf(out.size * 2)
                out[o++] = (s + x0).toFloat() / MC_GRID; out[o++] = (top + y0).toFloat() / MC_GRID
                out[o++] = (e + x0).toFloat() / MC_GRID; out[o++] = (bottom + y0).toFloat() / MC_GRID
            }
            // (start, end, top) of the rects still growing down, sorted by start
            var open = IntArray(48)
            var next = IntArray(48)
            var opened = 0
            var i = 0
            for (y in 0..y1 - y0) {
                var j = 0
                var count = 0
                while (i < m && (runs[i] ushr 42).toInt() == y) {
                    val s = (runs[i] ushr 21).toInt() and MC_RUN_MASK
                    var e = runs[i++].toInt() and MC_RUN_MASK
                    while (i < m && (runs[i] ushr 42).toInt() == y && ((runs[i] ushr 21).toInt() and MC_RUN_MASK) <= e) e = max(e, runs[i++].toInt() and MC_RUN_MASK)
                    while (j < opened && open[j * 3] < s) { emit(open[j * 3], open[j * 3 + 1], open[j * 3 + 2], y); j++ }
                    var top = y
                    if (j < opened && open[j * 3] == s && open[j * 3 + 1] == e) top = open[j++ * 3 + 2]
                    if (count * 3 + 3 > next.size) next = next.copyOf(next.size * 2)
                    next[count * 3] = s; next[count * 3 + 1] = e; next[count * 3 + 2] = top
                    count++
                }
                while (j < opened) { emit(open[j * 3], open[j * 3 + 1], open[j * 3 + 2], y); j++ }
                val t = open; open = next; next = t; opened = count
            }
            return out.copyOf(o)
        }

        private fun bitmapOf(w: Int, h: Int, argb: (Int, Int) -> Int): PolyBitmap {
            val px = ByteArray(w * h * 4)
            for (y in 0 until h) for (x in 0 until w) {
                val c = argb(x, y)
                val o = (y * w + x) * 4
                px[o] = (c shr 16).toByte(); px[o + 1] = (c shr 8).toByte(); px[o + 2] = c.toByte(); px[o + 3] = (c ushr 24).toByte()
            }
            return PolyBitmap(w, h, px)
        }

        private fun ramp(g: PolyGradient): PolyBitmap = g.rampBitmap
            ?: ramps.getOrPut(g.colors to g.stops) { bitmapOf(256, 1) { x, _ -> g.sample(x / 255f) } }.also { g.rampBitmap = it }

        private const val MIN_BOX_SIDE = 16
        private const val MAX_BOX_SIDE = 512
        private const val BOX_BYTES = 8L shl 20

        private fun boxSide(px: Float): Int {
            val n = if (px > 0f) ceil(min(px, MAX_BOX_SIDE.toFloat())).toInt().coerceIn(MIN_BOX_SIDE, MAX_BOX_SIDE) else MIN_BOX_SIDE
            return Integer.highestOneBit(n - 1) shl 1
        }

        private fun boxRaster(g: PolyGradient, aspect: Float, pw: Float, ph: Float): PolyBitmap {
            val q = (aspect * 16f).roundToInt().coerceIn(1, 1024)
            val w = boxSide(pw)
            val h = boxSide(ph)
            g.boxBitmap?.let { if (g.boxAspect == q && it.width == w && it.height == h) return it }
            return boxRaster(BoxKey(g, q, w, h)).also { g.boxBitmap = it; g.boxAspect = q }
        }

        private fun boxRaster(key: BoxKey): PolyBitmap {
            synchronized(boxRasters) { boxRasters[key]?.let { return it } }
            val g = key.gradient
            val a = key.aspect / 16f
            val w = key.w
            val h = key.h
            val made = bitmapOf(w, h) { x, y -> g.colorAt((x + 0.5f) / w * a, (y + 0.5f) / h, 0f, 0f, a, 1f) }
            synchronized(boxRasters) {
                boxRasters.put(key, made)?.let { boxBytes -= it.pixels.size }
                boxBytes += made.pixels.size
                val eldest = boxRasters.values.iterator()
                while (boxBytes > BOX_BYTES && boxRasters.size > 1) {
                    boxBytes -= eldest.next().pixels.size
                    eldest.remove()
                }
            }
            return made
        }

        private const val MAX_MASK_SIGMA = 6f
        private const val HOLE_STEPS = 5

        private fun shadowMask(rPx: Int, sigmaQ: Int): ShadowMask {
            val d = max(1, ceil(sigmaQ / 2f / MAX_MASK_SIGMA).toInt())
            val rT = (rPx.toFloat() / d).roundToInt()
            val qT = max(1, (sigmaQ.toFloat() / d).roundToInt())
            return masks.getOrPut((rT.toLong() shl 32) or (qT.toLong() shl 16) or d.toLong()) { shadowMask(rT, qT, d) }
        }

        private fun shadowMask(rT: Int, sigmaQ: Int, d: Int): ShadowMask {
            val sigma = sigmaQ / 2f
            val e = ceil(sigma * 3f).toInt()
            val core = 2 * (rT + e) + 1
            val m = core + 2 * e
            val cov = FloatArray(m * m)
            val lo = e.toFloat(); val hi = (e + core).toFloat(); val r = rT.toFloat()
            for (y in 0 until m) for (x in 0 until m) {
                val px = x + 0.5f; val py = y + 0.5f
                val qx = max(max(lo + r - px, px - (hi - r)), 0f)
                val qy = max(max(lo + r - py, py - (hi - r)), 0f)
                val inX = min(px - lo, hi - px); val inY = min(py - lo, hi - py)
                val dist = if (qx > 0f && qy > 0f) hypot(qx, qy) - r else -min(inX, inY)
                cov[y * m + x] = (0.5f - dist).coerceIn(0f, 1f)
            }
            val k = FloatArray(e * 2 + 1) { exp(-((it - e) * (it - e)) / (2f * sigma * sigma)) }
            val sum = k.sum()
            for (i in k.indices) k[i] /= sum
            val tmp = FloatArray(m * m)
            for (y in 0 until m) for (x in 0 until m) {
                var a = 0f
                for (i in k.indices) { val sx = x + i - e; if (sx in 0 until m) a += cov[y * m + sx] * k[i] }
                tmp[y * m + x] = a
            }
            val bmp = bitmapOf(m, m) { x, y ->
                var a = 0f
                for (i in k.indices) { val sy = y + i - e; if (sy in 0 until m) a += tmp[sy * m + x] * k[i] }
                ((a * 255f).roundToInt().coerceIn(0, 255) shl 24) or 0xFFFFFF
            }
            return ShadowMask(bmp, (rT + 2 * e + 0.5f) * d, (e * d).toFloat(), (m * d).toFloat())
        }
    }
}

private const val MC_CODES = "0123456789abcdeflrmno"

private val MC_COLORS = intArrayOf(
    0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
    0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF,
)

internal fun zeroSourceKeepsDst(mode: Int): Boolean = when (mode) {
    PolyRenderer.BLEND_SRC, PolyRenderer.BLEND_DST_IN, PolyRenderer.BLEND_SRC_IN, PolyRenderer.BLEND_SRC_OUT,
    PolyRenderer.BLEND_DST_ATOP, PolyRenderer.BLEND_MODULATE -> false
    else -> true
}
