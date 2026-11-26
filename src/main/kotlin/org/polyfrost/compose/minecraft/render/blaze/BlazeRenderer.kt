package org.polyfrost.compose.minecraft.render.blaze

//? if >= 1.21.11 {
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.BlendFunction
import com.mojang.renderpearl.api.pipeline.ColorTargetState
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology
import com.mojang.renderpearl.api.pipeline.RenderPipeline
//?} else {
/*import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
*///?}
//? if >= 26.3 {
import com.mojang.renderpearl.api.pipeline.BlendFactor as BlendSrc
import com.mojang.renderpearl.api.pipeline.BlendFactor as BlendDst
//?} elif 26.2 {
/*import com.mojang.blaze3d.platform.BlendFactor as BlendSrc
import com.mojang.blaze3d.platform.BlendFactor as BlendDst
*///?} else {
/*import com.mojang.blaze3d.platform.SourceFactor as BlendSrc
import com.mojang.blaze3d.platform.DestFactor as BlendDst
*///?}
//? if 26.2 {
/*import com.mojang.blaze3d.PrimitiveTopology
*///?} elif < 26.2
//import com.mojang.blaze3d.vertex.VertexFormat.Mode as PrimitiveTopology
//? if >= 26.1 && < 26.3
//import com.mojang.blaze3d.pipeline.ColorTargetState
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.renderpearl.api.textures.GpuTextureView
import com.mojang.renderpearl.api.textures.FilterMode
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.renderpearl.api.buffers.GpuBuffer
import com.mojang.renderpearl.api.commands.RenderPass
import java.nio.ByteBuffer
import java.util.Collections
import java.util.IdentityHashMap
import java.util.WeakHashMap
import java.util.function.IntSupplier
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.world.item.ItemStack
import net.minecraft.client.gui.render.TextureSetup
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiElementRenderState
//?} else {
/*import net.minecraft.client.gui.render.state.GuiElementRenderState
*///?}
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.network.chat.TextColor
import net.minecraft.resources.Identifier
import org.polyfrost.compose.minecraft.compat.GnetumCompat
import org.polyfrost.compose.minecraft.compat.GnetumOffscreenState
import org.polyfrost.compose.minecraft.render.ColorMath
import org.polyfrost.compose.minecraft.render.EffectParams
import org.polyfrost.compose.minecraft.render.OffscreenTarget
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect
//? if >= 26.1 {
import net.minecraft.client.renderer.state.gui.GuiRenderState
//?} else
//import net.minecraft.client.gui.render.state.GuiRenderState
//? if < 26.2 {
/*import net.minecraft.client.renderer.fog.FogRenderer
import org.polyfrost.compose.minecraft.mixin.render.GameRendererAccessor
*///?}
import net.minecraft.locale.Language
import net.minecraft.util.ARGB
import org.joml.Matrix3x2f
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.font.TextRenderable
import net.minecraft.util.FormattedCharSequence
import net.minecraft.network.chat.Style
import net.minecraft.util.StringDecomposer
import org.joml.Matrix4f
import org.joml.Matrix4fc
import org.joml.Vector3f
import org.lwjgl.system.MemoryUtil
import org.polyfrost.compose.render.BitmapTextureCache
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.mcVisualOrder
import org.polyfrost.compose.render.PolyTessellator
import org.polyfrost.compose.render.CapturingRenderer
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.OverlayRenderer
import org.polyfrost.compose.minecraft.mc.McItemAtlas
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.mc.GuiItemRenderer
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.render.gl.GlHost
import org.polyfrost.compose.minecraft.render.gl.guiHidden
import org.polyfrost.compose.render.PolyBackdrop
import org.polyfrost.compose.render.PolyEffect
import org.polyfrost.compose.render.PolyEffectChain
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.minecraft.render.TextureRenderer
import org.polyfrost.compose.minecraft.mixin.render.BufferBuilderAccessor
import org.polyfrost.compose.minecraft.mixin.render.RenderPipelinesAccessor
import org.polyfrost.compose.minecraft.profile.EffectGpuTimer
import org.polyfrost.compose.minecraft.profile.ProfilerHooks
import org.polyfrost.compose.minecraft.profile.RenderCounters
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measure
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class BlazeRenderer(private val guiScale: Float, private val keepVertices: Boolean = false) : PolyTessellator(), TextureRenderer, CapturingRenderer, GuiItemRenderer {
    private val ops = ArrayList<(GuiGraphicsExtractor) -> Unit>()
    private val bitmapLog = ArrayList<PolyBitmap>()

    val bitmaps = HashSet<PolyBitmap>()
    private val elementPose = Matrix3x2f().scale(1f / guiScale)
    private val screenW = Minecraft.getInstance().window.guiScaledWidth
    private val screenH = Minecraft.getInstance().window.guiScaledHeight

    override fun bitmapTexture(bitmap: PolyBitmap): Any {
        bitmaps.add(bitmap)
        val ref = BitmapTextures.get(bitmap)
        bitmapLog.add(bitmap)
        return ref
    }

    private class Captured(
        val ops: List<(GuiGraphicsExtractor) -> Unit>, val bitmaps: List<PolyBitmap>, val refs: List<AbstractTextureRef>, val boxes: FloatArray,
        val movable: Boolean,
    ) {
        var glyphGeneration = GlyphAtlas.generation
        var textures: List<GpuTextureView>? = null
    }

    private var screenCuts = 0
    private var screenReads = 0

    override fun mark(): Any {
        flush()
        return intArrayOf(ops.size, bitmapLog.size, drawBoxMark, screenCuts, screenReads)
    }

    override fun since(mark: Any): Any {
        flush()
        val m = mark as IntArray
        val bmps = ArrayList(bitmapLog.subList(m[1], bitmapLog.size))
        return Captured(
            ArrayList(ops.subList(m[0], ops.size)), bmps, bmps.map(BitmapTextures::get), drawBoxesSince(m[2]),
            m[3] == screenCuts && m[4] == screenReads,
        )
    }

    override fun valid(captured: Any): Boolean {
        val c = captured as Captured
        val bitmaps = c.bitmaps
        for (i in bitmaps.indices) if (BitmapTextures.get(bitmaps[i]) !== c.refs[i]) return false
        val textures = c.textures ?: LayerOp.texturesOf(c.ops).also { c.textures = it }
        if (anyIndexed(textures) { it.isClosed }) return false
        if (c.glyphGeneration != GlyphAtlas.generation) {
            if (anyIndexed(c.ops) { LayerOp.closed(it) }) return false
            c.glyphGeneration = GlyphAtlas.generation
        }
        return true
    }

    override fun shiftedCapture(captured: Any, dx: Float, dy: Float): Any? {
        val c = captured as Captured
        if (!c.movable) return null
        val boxes = shiftedBoxes(c.boxes, dx, dy)
        if (c.ops.isEmpty()) return Captured(c.ops, c.bitmaps, c.refs, boxes, true)
        val gx = dx / guiScale
        val gy = dy / guiScale
        val ix = gx.roundToInt()
        val iy = gy.roundToInt()
        val whole = abs(gx - ix) < GUI_EPSILON && abs(gy - iy) < GUI_EPSILON
        val ops = c.ops
        val out = ArrayList<(GuiGraphicsExtractor) -> Unit>(ops.size)
        for (i in ops.indices) {
            val op = ops[i]
            out += (if (whole) shiftWhole(op, ix, iy) else shiftFraction(op, gx, gy)) ?: return null
        }
        return Captured(out, c.bitmaps, c.refs, boxes, true)
    }

    override fun recuttable(captured: Any): Boolean {
        val c = captured as Captured
        return c.movable && !anyIndexed(c.ops) { it !is MeshOp || it.mesh.glass }
    }

    override fun beginRecut(captured: Any) {
        val c = captured as Captured
        bitmaps.addAll(c.bitmaps)
        bitmapLog.addAll(c.bitmaps)
    }

    private fun onScreen(r: ScreenRectangle) =r.left() >= 0 && r.top() >= 0 && r.right() <= screenW && r.bottom() <= screenH

    private fun onScreen(meshes: List<Mesh>?): Boolean {
        if (meshes.isNullOrEmpty()) return false
        for (i in meshes.indices) if (!onScreen(meshes[i].bounds())) return false
        return true
    }

    private fun shiftWhole(op: (GuiGraphicsExtractor) -> Unit, ix: Int, iy: Int): ((GuiGraphicsExtractor) -> Unit)? {
        val moved = when (op) {
            is MeshOp -> if (op.mesh.glass) return null else MeshOp(op.mesh.shifted(-ix, -iy))
            is BakedTextOp -> if (op.meshes.isNullOrEmpty()) return null else op.shifted(-ix, -iy)
            is LiveTextOp -> if (op.meshes.isNullOrEmpty()) return null else op.shifted(-ix, -iy)
            is OutlineOp -> op.shifted(-ix, -iy)
            is ItemGlint.Op -> op.shifted(-ix, -iy)
            is LayerOp -> {
                val b = op.bounds
                if (b.left() <= 0 || b.top() <= 0 || b.right() >= screenW || b.bottom() >= screenH) return null
                op.moved(-ix, -iy)
            }
            else -> return null
        }
        val inside = when (moved) {
            is MeshOp -> onScreen(moved.mesh.bounds())
            is BakedTextOp -> onScreen(moved.meshes)
            is LiveTextOp -> onScreen(moved.meshes)
            is OutlineOp -> onScreen(moved.bounds)
            is ItemGlint.Op -> onScreen(moved.bounds)
            is LayerOp -> onScreen(moved.bounds)
            else -> false
        }
        return if (inside) moved else null
    }

    private fun shiftFraction(op: (GuiGraphicsExtractor) -> Unit, gx: Float, gy: Float): ((GuiGraphicsExtractor) -> Unit)? = when (op) {
        is MeshOp -> op.mesh.moved(gx, gy)?.takeIf { onScreen(it.bounds()) }?.let(::MeshOp)
        is BakedTextOp -> op.moved(gx, gy)?.takeIf { onScreen(it.meshes) }
        is LiveTextOp -> op.moved(gx, gy)?.takeIf { onScreen(it.meshes) }
        is ItemGlint.Op -> op.moved(gx, gy).takeIf { onScreen(it.bounds) }
        else -> null
    }

    override fun append(captured: Any) {
        flush()
        val c = captured as Captured
        if (!c.movable) screenCuts++
        ops.addAll(c.ops)
        appendDrawBoxes(c.boxes)
        bitmaps.addAll(c.bitmaps)
        bitmapLog.addAll(c.bitmaps)
    }

    override fun writeStateKey() {
        super.writeStateKey()
        keyAdd(guiScale); keyAdd(screenW.toFloat()); keyAdd(screenH.toFloat())
    }

    fun matches(guiScale: Float): Boolean {
        val window = Minecraft.getInstance().window
        return guiScale == this.guiScale && screenW == window.guiScaledWidth && screenH == window.guiScaledHeight
    }

    var usesGameFrame = false
        private set

    fun reset() {
        ops.clear()
        bitmapLog.clear()
        bitmaps.clear()
        usesGameFrame = false
        resetTessellator()
    }

    override val layered: Boolean get() = true

    override fun layerMark(): Any = ops.size

    override fun layer(mark: Any, alpha: Float) = hoistedLayer(mark, alpha, null)

    override fun layer(mark: Any, alpha: Float, chain: PolyEffectChain, x0: Float, y0: Float, x1: Float, y1: Float, radius: Float, scale: Float) =
        hoistedLayer(mark, alpha, LayerEffect(chain, x0, y0, x1, y1, radius, scale))

    private fun hoistedLayer(mark: Any, alpha: Float, effect: LayerEffect?) {
        val sub = ops.subList(mark as Int, ops.size)
        if (sub.isEmpty()) return
        val all = ArrayList(sub)
        sub.clear()
        var from = 0
        for (i in all.indices) {
            val op = all[i]
            if (op !is MeshOp || !op.mesh.glass) continue
            if (i > from) addLayer(all.subList(from, i), alpha, effect)
            ops.add(MeshOp(op.mesh.faded(alpha)))
            from = i + 1
        }
        if (from == 0) addLayer(all, alpha, effect) else if (from < all.size) addLayer(all.subList(from, all.size), alpha, effect)
    }

    private fun addLayer(list: List<(GuiGraphicsExtractor) -> Unit>, alpha: Float, effect: LayerEffect?) {
        val op = LayerOp.of(list, alpha, guiScale, screenW, screenH, effect, blendMode)
        if (op != null) ops.add(op)
    }

    override fun fade(mark: Any, alpha: Float): Boolean {
        if (alpha >= 1f) return true
        val from = mark as Int
        val faded = ArrayList<(GuiGraphicsExtractor) -> Unit>(ops.size - from)
        for (i in from until ops.size) faded += when (val op = ops[i]) {
            is MeshOp -> MeshOp(op.mesh.faded(alpha))
            is BakedTextOp -> op.faded(alpha)
            is LiveTextOp -> op.faded(alpha)
            is LayerOp -> op.faded(alpha)
            is LiveOp -> op.faded(alpha)
            is ItemGlint.Op -> op.faded(alpha)
            else -> return false
        }
        for (i in faded.indices) ops[from + i] = faded[i]
        return true
    }

    private fun touchText(lines: List<String>, x: Float, y: Float, scale: Float, shadow: Boolean, literal: Boolean) {
        val font = Minecraft.getInstance().font
        touchText(lines.maxOf { lineWidth(font, it, literal) }, lines.size, x, y, scale, shadow)
    }

    private fun lineWidth(font: Font, line: String, literal: Boolean): Int = TextWidths.of(font, line, literal)

    private fun touchText(maxWidth: Int, count: Int, x: Float, y: Float, scale: Float, shadow: Boolean) {
        val font = Minecraft.getInstance().font
        val w = (maxWidth + 1f) * scale
        val h = (count * font.lineHeight + 1f) * scale
        val b = deviceBox(x * state.sx + state.tx, y * state.sy + state.ty, (x + w) * state.sx + state.tx, (y + h) * state.sy + state.ty)
        repeat(if (shadow) 2 else 1) {
            beginDraw()
            touch(b[0], b[1], b[2], b[3])
        }
    }

    override fun submit(texture: Any, premultiplied: Boolean, nearest: Boolean, verts: FloatArray, colors: IntArray, bounds: FloatArray) {
        RenderCounters.op()
        if (texture is BackdropSpec) {
            val p = texture.pad
            val box = rectGui(texture.x0 - p, texture.y0 - p, texture.x1 + p, texture.y1 + p) ?: return
            val setup = GlyphAtlas.page(PolyGlyphPage.pages[0]).setup()
            ops.add(MeshOp(Mesh(LiquidGlass.pipeline, setup, elementPose, verts, IntArray(colors.size), box, false, backdrop = texture)))
            return
        }
        val box = rectGui(bounds[0], bounds[1], bounds[2], bounds[3]) ?: return
        if (texture is ItemGlint.Key) {
            ops.add(ItemGlint.Op(texture, elementPose, verts, colors, box))
            return
        }
        val setup = when (texture) {
            is AbstractTextureRef -> texture.setup(nearest)
            is GpuTextureView -> TextureSetup.singleTexture(texture, sampler(nearest))
            is PolyGlyphPage.Page -> GlyphAtlas.page(texture).setup(nearest)
            else -> GlyphAtlas.page(PolyGlyphPage.pages[0]).setup()
        }
        val pipeline = when {
            blendMode != PolyRenderer.BLEND_SRC_OVER -> BlendPipelines.of(blendMode, premultiplied)
            premultiplied -> RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA
            else -> RenderPipelines.GUI_TEXTURED
        }
        ops.add(MeshOp(Mesh(pipeline, setup, elementPose, verts, colors, box, keepVertices)))
    }

    override fun blendSupported(mode: Int): Boolean = BlendPipelines.supports(mode)

    override fun layerBlendSupported(mode: Int): Boolean = BlendPipelines.supports(mode)

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float) {
        mcText(text, x, y, color, shadow, scale, false)
    }

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, literal: Boolean) {
        if (state.proj != null) return projectedText(text, x, y, mulAlpha(color, state.alpha), shadow, scale, literal, false)
        mcText(text, x, y, color, shadow, scale, literal, true)
    }

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, literal: Boolean, snapX: Boolean) {
        if (state.proj != null) return projectedText(text, x, y, mulAlpha(color, state.alpha), shadow, scale, literal, false)
        if (!finiteText(x, y, scale)) return
        flush()
        val c = mulAlpha(color, state.alpha)
        if (c ushr 24 == 0) return
        val scissor = state.clip?.let { rectGui(it[0], it[1], it[2], it[3]) ?: return }
        val font = Minecraft.getInstance().font
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            touchText(lineWidth(font, text, literal), 1, x, y, scale, shadow)
            textLine(font, text, 0, x, y, c, shadow, scale, literal, snapX, scissor)
            return
        }
        val lines = text.lines()
        touchText(lines, x, y, scale, shadow, literal)
        for (index in lines.indices) textLine(font, lines[index], index, x, y, c, shadow, scale, literal, snapX, scissor)
    }

    private fun textLine(
        font: Font, line: String, index: Int, x: Float, y: Float, c: Int, shadow: Boolean, scale: Float, literal: Boolean, snapX: Boolean,
        scissor: ScreenRectangle?,
    ) {
        if (line.isEmpty()) return
        val m = lineMatrix(x, y, index, font.lineHeight, scale, snapX)
        val seq = visualOrder(line, literal)
        val formatted = '§' in line
        val baked = if (!keepVertices || (!literal && formatted && line.contains("§k", ignoreCase = true))) null
            else if (formatted || Language.getInstance().isDefaultRightToLeft) bakeText(font, seq, m, c, shadow, scissor)
            else GlyphQuads.bake(font, line, m, c, shadow, scissor) ?: bakeText(font, seq, m, c, shadow, scissor)
        ops.add(BakedTextOp(baked, c) { g, color -> vanillaText(g, font, seq, m, color, shadow, scissor) })
    }

    override fun guiItem(stack: ItemStack, x: Float, y: Float, w: Float, h: Float): Boolean {
        val s = state
        if (stack.hasFoil() && ItemGlint.usable) return false
        if (s.proj != null || s.rot != null || s.alpha < 1f || !finiteText(x, y, 1f) || w <= 0f || h <= 0f) return false
        flush()
        val scissor = s.clip?.let { rectGui(it[0], it[1], it[2], it[3]) ?: return true }
        val b = deviceBox(x * s.sx + s.tx, y * s.sy + s.ty, (x + w) * s.sx + s.tx, (y + h) * s.sy + s.ty)
        beginDraw()
        touch(b[0], b[1], b[2], b[3])
        val m = guiPose().translate(x * s.sx + s.tx, y * s.sy + s.ty).scale(s.sx * w / 16f, s.sy * h / 16f)
        ops.add { g -> vanillaItem(g, stack, m, scissor) }
        return true
    }

    override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float) {
        mcTextLive(text, x, y, IntSupplier { color() }, shadow, scale, false)
    }

    override fun mcTextLive(text: String, x: Float, y: Float, color: () -> Int, shadow: Boolean, scale: Float, literal: Boolean) {
        mcTextLive(text, x, y, IntSupplier { color() }, shadow, scale, literal)
    }

    override fun mcTextLive(text: String, x: Float, y: Float, color: IntSupplier, shadow: Boolean, scale: Float, literal: Boolean) {
        val formatted = '§' in text
        if (state.proj != null || (!literal && formatted && text.contains("§k", ignoreCase = true))) {
            return live { it.mcText(text, x, y, color.asInt, shadow, scale, literal) }
        }
        if (!keepVertices || formatted || Language.getInstance().isDefaultRightToLeft) return keyedTextLive(text, x, y, color, shadow, scale, literal)
        if (!finiteText(x, y, scale)) return
        flush()
        val alpha = state.alpha
        val scissor = state.clip?.let { rectGui(it[0], it[1], it[2], it[3]) ?: return }
        val lines = textLines(text)
        touchText(lines, x, y, scale, shadow, false)
        val font = Minecraft.getInstance().font
        lines.forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val m = lineMatrix(x, y, index, font.lineHeight, scale, true)
            val seq = visualOrder(line)
            val meshes = GlyphQuads.bake(font, line, m, -1, shadow, scissor, keep = false)
            val op = LiveTextOp(meshes, color, alpha) { g, c -> vanillaText(g, font, seq, m, c, shadow, scissor) }
            ops.add(op)
        }
    }

    private fun keyedTextLive(text: String, x: Float, y: Float, color: IntSupplier, shadow: Boolean, scale: Float, literal: Boolean) {
        if (!finiteText(x, y, scale)) return
        flush()
        beginDraw()
        val font = Minecraft.getInstance().font
        var bounds: ScreenRectangle? = null
        textLines(text).forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val m = lineMatrix(x, y, index, font.lineHeight, scale, true)
            val r = font.prepareText(visualOrder(line, literal), 0f, 0f, -1, shadow, false, 0).bounds() ?: return@forEachIndexed
            val b = ScreenRectangle(r.left() - TEXT_PAD, r.top() - TEXT_PAD, r.width() + TEXT_PAD * 2, r.height() + TEXT_PAD * 2).transformMaxBounds(m)
            bounds = bounds?.let { rectUnion(it, b) } ?: b
        }
        val b = bounds ?: return
        val x0 = b.left() * guiScale
        val y0 = b.top() * guiScale
        val x1 = b.right() * guiScale
        val y1 = b.bottom() * guiScale
        touch(x0, y0, x1, y1)
        val box = rectGui(x0, y0, x1, y1) ?: return
        ops.add(LiveOp(state.copy(), { it.mcText(text, x, y, color.asInt, shadow, scale, literal) }, color, box))
    }

    private fun guiPose(): Matrix3x2f {
        val pose = Matrix3x2f().scale(1f / guiScale)
        val r = state.rot ?: return pose
        return pose.mul(Matrix3x2f(r[0], r[2], r[1], r[3], r[4], r[5]))
    }

    private val boxScratch = FloatArray(4)

    private fun deviceBox(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val b = boxScratch
        val m = state.rot
        if (m == null) {
            b[0] = min(x0, x1); b[1] = min(y0, y1); b[2] = max(x0, x1); b[3] = max(y0, y1)
        } else {
            b[0] = min(m[0] * x0, m[0] * x1) + min(m[1] * y0, m[1] * y1) + m[4]
            b[2] = max(m[0] * x0, m[0] * x1) + max(m[1] * y0, m[1] * y1) + m[4]
            b[1] = min(m[2] * x0, m[2] * x1) + min(m[3] * y0, m[3] * y1) + m[5]
            b[3] = max(m[2] * x0, m[2] * x1) + max(m[3] * y0, m[3] * y1) + m[5]
        }
        return b
    }

    private fun finiteText(x: Float, y: Float, scale: Float): Boolean {
        val s = state
        return (x * s.sx + s.tx).isFinite() && (y * s.sy + s.ty).isFinite() && (s.sx * scale).isFinite() && (s.sy * scale).isFinite()
    }

    private fun lineMatrix(x: Float, y: Float, index: Int, lineHeight: Int, scale: Float, snapX: Boolean): Matrix3x2f =
        guiPose()
            .translate((x * state.sx + state.tx).let { if (snapX) snap(it) else it }, snap(y * state.sy + state.ty) + snap(index * lineHeight * scale * state.sy))
            .scale(state.sx * scale, state.sy * scale)

    override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float) {
        mcTextOutline(text, x, y, color, outlineColor, scale, false)
    }

    override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float, literal: Boolean) {
        if (state.proj != null) return projectedOutline(text, x, y, color, outlineColor, scale, literal)
        mcTextOutline(text, x, y, color, outlineColor, scale, literal, true)
    }

    override fun mcTextOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float, literal: Boolean, snapX: Boolean) {
        if (state.proj != null) return projectedOutline(text, x, y, color, outlineColor, scale, literal)
        if (!finiteText(x, y, scale)) return
        val a = mulAlpha(outlineColor, state.alpha) ushr 24
        val clip = state.clip
        val scissor = clip?.let { rectGui(it[0], it[1], it[2], it[3]) }
        if (a != 0 && (clip == null || scissor != null)) {
            flush()
            val s = state
            val ox = x * s.sx + s.tx
            val px = if (snapX) snap(ox) else floor(ox)
            val py = snap(y * s.sy + s.ty)
            val outline = McOutlines.get(text, scale * s.sx, scale * s.sy, if (snapX) 0f else ox - px, 0f, literal)
            if (outline != null) {
                val x0 = px - outline.pad
                val y0 = py - outline.pad
                val box = deviceBox(x0, y0, x0 + outline.w, y0 + outline.h)
                val bounds = rectGui(box[0], box[1], box[2], box[3])
                if (bounds != null) {
                    beginDraw()
                    touch(box[0], box[1], box[2], box[3])
                    val color4 = (a shl 24) or ((outlineColor ushr 16 and 0xFF) * a / 255 shl 16) or
                        ((outlineColor ushr 8 and 0xFF) * a / 255 shl 8) or ((outlineColor and 0xFF) * a / 255)
                    val pose = if (s.rot == null) elementPose else guiPose()
                    ops.add(OutlineOp(outline, pose, x0, y0, color4, bounds, scissor))
                }
            }
        }
        mcText(text, x, y, color, false, scale, literal, snapX)
    }

    private fun projectedOutline(text: String, x: Float, y: Float, color: Int, outlineColor: Int, scale: Float, literal: Boolean) {
        val o = mulAlpha(outlineColor, state.alpha)
        if (o ushr 24 != 0) {
            for (dy in -1..1) for (dx in -1..1) {
                if (dx != 0 || dy != 0) projectedText(text, x + dx * scale, y + dy * scale, o, false, scale, literal, true)
            }
        }
        projectedText(text, x, y, mulAlpha(color, state.alpha), false, scale, literal, false)
    }

    private fun projectedText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, literal: Boolean, flat: Boolean) {
        if (color ushr 24 == 0 || !finiteText(x, y, scale)) return
        val font = Minecraft.getInstance().font
        val s = state
        val cap = GlyphCapture()
        textLines(text).forEachIndexed { index, line ->
            if (line.isEmpty()) return@forEachIndexed
            val pose = Matrix4f()
                .translation(x * s.sx + s.tx, (y + index * font.lineHeight * scale) * s.sy + s.ty, 0f)
                .scale(s.sx * scale, s.sy * scale, 1f)
            val prepared = font.prepareText(visualOrder(line, literal), 0f, 0f, color, shadow, false, 0)
            beginDraw()
            visitRenderables(prepared) { renderable ->
                if (renderable.guiPipeline() !== RenderPipelines.GUI_TEXT) return@visitRenderables
                cap.n = 0
                renderable.render(pose, cap, FULL_BRIGHT, true)
                if (cap.n == 0) return@visitRenderables
                begin(renderable.textureView(), false, true)
                val v = cap.verts
                for (i in 0 until cap.n - cap.n % 4) {
                    vertexPx(v[i * 4], v[i * 4 + 1], v[i * 4 + 2], v[i * 4 + 3], if (flat) color else cap.colors[i])
                }
            }
        }
    }

    private fun bakeText(font: Font, seq: FormattedCharSequence, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?): List<Mesh>? {
        val prepared = font.prepareText(seq, 0f, 0f, color, shadow, false, 0)
        var bounds = prepared.bounds()?.transformMaxBounds(m) ?: return emptyList()
        if (scissor != null) bounds = scissor.intersection(bounds) ?: return emptyList()
        val pose = Matrix4f().mul(m)
        val meshes = ArrayList<Mesh>(1)
        var pipeline: RenderPipeline? = null
        var view: GpuTextureView? = null
        var cap = GlyphCapture()
        var unknown = false
        fun close() {
            val p = pipeline ?: return
            val v = view ?: return
            if (cap.n == 0) return
            meshes.add(cap.mesh(p, v, bounds, true, scissor))
            cap = GlyphCapture()
        }
        visitRenderables(prepared) { renderable ->
            val p = renderable.guiPipeline()
            if (!isTextPipeline(p)) { unknown = true; return@visitRenderables }
            if (p !== pipeline || renderable.textureView() !== view) { close(); pipeline = p; view = renderable.textureView() }
            renderable.render(pose, cap, FULL_BRIGHT, true)
        }
        if (unknown) return null
        close()
        return meshes
    }

    internal class GlyphCapture : VertexConsumer {
        var verts = FloatArray(64)
        var colors = IntArray(16)
        var n = 0
        private val transformed = Vector3f()

        fun mesh(pipeline: RenderPipeline, view: GpuTextureView, bounds: ScreenRectangle, keep: Boolean, scissor: ScreenRectangle?): Mesh {
            val sampler = sampler(true)
            val setup = if (TEXT_LIGHTMAP) TextureSetup.singleTextureWithLightmap(view, sampler) else TextureSetup.singleTexture(view, sampler)
            return Mesh(pipeline, setup, Matrix3x2f(), verts.copyOf(n * 4), colors.copyOf(n), bounds, keep, scissor, TEXT_LIGHTMAP)
        }

        override fun addVertex(x: Float, y: Float, z: Float) = apply {
            if (n == colors.size) { colors = colors.copyOf(n * 2); verts = verts.copyOf(n * 8) }
            verts[n * 4] = x; verts[n * 4 + 1] = y; n++
        }
        override fun addVertex(pose: Matrix4fc, x: Float, y: Float, z: Float): VertexConsumer {
            val v = pose.transformPosition(x, y, z, transformed)
            return addVertex(v.x(), v.y(), v.z())
        }
        override fun setUv(u: Float, v: Float) = apply { verts[n * 4 - 2] = u; verts[n * 4 - 1] = v }
        override fun setColor(color: Int) = apply { colors[n - 1] = color }
        override fun setColor(r: Int, g: Int, b: Int, a: Int) = setColor((a shl 24) or (r shl 16) or (g shl 8) or b)
        override fun setUv1(u: Int, v: Int) = this
        override fun setUv2(u: Int, v: Int) = this
        //? if >= 26.3
        override fun setUv3(u: Float, v: Float) = this
        override fun setNormal(x: Float, y: Float, z: Float) = this
        override fun setLineWidth(width: Float) = this
    }

    override fun texture(target: RenderTarget, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) {
        texture(target.colorTextureView ?: return, x, y, w, h, u0, targetV(v0), u1, targetV(v1), alpha)
    }

    override fun backdropSource(): BackdropSource? {
        val view = UiBlur.backdrop ?: return null
        screenReads++
        return BackdropSource(view, UiBlur.backdropWidth.toFloat(), UiBlur.backdropHeight.toFloat(), topDownTargets)
    }

    internal fun itemGlint(target: RenderTarget, cells: Int, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) {
        val view = target.colorTextureView ?: return
        beginDraw()
        val a = ColorMath.alphaByte(alpha * state.alpha)
        if (a == 0) return
        begin(ItemGlint.key(view, cells), true)
        val x0 = x * state.sx + state.tx
        val y0 = y * state.sy + state.ty
        quadPx(x0, y0, x0 + w * state.sx, y0 + h * state.sy, u0, targetV(v0), u1, targetV(v1), (a shl 24) or (a shl 16) or (a shl 8) or a)
    }

    fun texture(view: GpuTextureView, x: Float, y: Float, w: Float, h: Float, u0: Float, v0: Float, u1: Float, v1: Float, alpha: Float) {
        beginDraw()
        val a = ColorMath.alphaByte(alpha * state.alpha)
        if (a == 0) return
        begin(view, true)
        val x0 = x * state.sx + state.tx
        val y0 = y * state.sy + state.ty
        quadPx(x0, y0, x0 + w * state.sx, y0 + h * state.sy, u0, v0, u1, v1, (a shl 24) or (a shl 16) or (a shl 8) or a)
    }

    override fun backdropGlass(x: Float, y: Float, w: Float, h: Float, radius: Float, band: Float, refraction: Float, alpha: Float) {
        flush()
        beginDraw()
        val s = state
        if (s.rot != null) return
        val a = ColorMath.alphaByte(alpha * s.alpha)
        if (a == 0) return
        val x0 = EffectParams.lowEdge(x, w, s.sx, s.tx)
        val x1 = EffectParams.highEdge(x, w, s.sx, s.tx)
        val y0 = EffectParams.lowEdge(y, h, s.sy, s.ty)
        val y1 = EffectParams.highEdge(y, h, s.sy, s.ty)
        val scale = EffectParams.scaleOf(s.sx, s.sy)
        val bandPx = EffectParams.legacyBand(band, scale).toFloat()
        val r = EffectParams.legacyRadius(radius, scale).toFloat()
        val k = EffectParams.legacyStrength(refraction) / 255f
        val chain = PolyEffectChain(listOf(PolyEffect.Refract(bandPx, k, EffectParams.LEGACY_DISPERSION)))
        val backdrop = PolyBackdrop(PolyBackdrop.SOURCE_BEHIND, chain, 1, 0f, false, 0)
        val pad = bandPx * k * EffectParams.LEGACY_REACH + EffectParams.LEGACY_FROST + 2f
        emitBackdrop(BackdropSpec(x0, y0, x1, y1, r, pad, a / 255f, backdrop, 1f, EffectParams.LEGACY_FROST, null, null, guiScale))
    }

    override fun effectSupport(): Int = PolyRenderer.EFFECT_BEHIND or PolyRenderer.EFFECT_GAME_FRAME or PolyRenderer.EFFECT_LAYER or
        if (EffectShaders.hookActive()) PolyRenderer.EFFECT_CUSTOM else 0

    override fun backdropEffect(x: Float, y: Float, w: Float, h: Float, radius: Float, backdrop: PolyBackdrop, alpha: Float) {
        flush()
        val s = state
        if (s.rot != null) {
            val f = backdrop.fallbackColor
            if (f ushr 24 != 0) rect(x, y, w, h, mulAlpha(f, alpha), radius)
            return
        }
        beginDraw()
        val a = alpha * s.alpha
        if (a <= 0f) return
        val x0 = EffectParams.lowEdge(x, w, s.sx, s.tx)
        val x1 = EffectParams.highEdge(x, w, s.sx, s.tx)
        val y0 = EffectParams.lowEdge(y, h, s.sy, s.ty)
        val y1 = EffectParams.highEdge(y, h, s.sy, s.ty)
        val scale = EffectParams.scaleOf(s.sx, s.sy)
        val liquid = backdrop.chain.liquid?.let { BackdropSpec.packLiquid(it, s.sx, s.sy, s.tx, s.ty, scale) }
        val pad = max(backdrop.padding, 0f) * scale
        emitBackdrop(BackdropSpec(x0, y0, x1, y1, radius * scale, pad, min(a, 1f), backdrop, scale, 0f, s.clip?.copyOf(), liquid, guiScale))
    }

    private fun emitBackdrop(spec: BackdropSpec) {
        if (spec.backdrop.source == PolyBackdrop.SOURCE_GAME_FRAME) usesGameFrame = true
        val reach = if (spec.liquid != null) spec.pad else 0f
        touch(spec.x0 - reach, spec.y0 - reach, spec.x1 + reach, spec.y1 + reach)
        begin(spec, false)
        quadPx(spec.x0, spec.y0, spec.x1, spec.y1, 0f, 0f, 1f, 1f, 0)
        flush()
    }

    private var liveRenderer: BlazeRenderer? = null

    override fun live(draw: (PolyRenderer) -> Unit) {
        flush()
        beginDraw()
        touchAll()
        ops.add(LiveOp(state.copy(), draw))
    }

    override fun live(x: Float, y: Float, w: Float, h: Float, key: () -> Int, draw: (PolyRenderer) -> Unit) {
        live(x, y, w, h, IntSupplier { key() }, draw)
    }

    override fun live(x: Float, y: Float, w: Float, h: Float, key: IntSupplier, draw: (PolyRenderer) -> Unit) {
        flush()
        beginDraw()
        val s = state
        val ax = x * s.sx + s.tx
        val bx = (x + w) * s.sx + s.tx
        val ay = y * s.sy + s.ty
        val by = (y + h) * s.sy + s.ty
        var x0 = min(ax, bx)
        var x1 = max(ax, bx)
        var y0 = min(ay, by)
        var y1 = max(ay, by)
        val m = s.rot
        if (m != null) {
            val l = min(m[0] * x0, m[0] * x1) + min(m[1] * y0, m[1] * y1) + m[4]
            val r = max(m[0] * x0, m[0] * x1) + max(m[1] * y0, m[1] * y1) + m[4]
            val t = min(m[2] * x0, m[2] * x1) + min(m[3] * y0, m[3] * y1) + m[5]
            val b = max(m[2] * x0, m[2] * x1) + max(m[3] * y0, m[3] * y1) + m[5]
            x0 = l
            x1 = r
            y0 = t
            y1 = b
        }
        touch(x0, y0, x1, y1)
        val box = rectGui(x0, y0, x1, y1) ?: return
        ops.add(LiveOp(s.copy(), draw, key, box))
    }

    private inner class LiveOp(
        private val s: State, private val draw: (PolyRenderer) -> Unit, private val key: IntSupplier? = null,
        override val box: ScreenRectangle? = null, private val dx: Int = 0, private val dy: Int = 0,
    ) : (GuiGraphicsExtractor) -> Unit, LiveDraw {
        override var gameFrame = false
            private set
        private var held: List<LayerOp> = emptyList()
        private var drawn: List<(GuiGraphicsExtractor) -> Unit>? = null
        private var drawnRuns: List<(GuiGraphicsExtractor) -> Unit> = emptyList()
        private var drawnKey = 0
        private var drawnPin = BitmapTextures.unpinned
        private var fadedOp: LiveOp? = null
        private var fadedAlpha = Float.NaN

        fun faded(alpha: Float): LiveOp {
            fadedOp?.let { if (fadedAlpha == alpha) return it }
            return LiveOp(s.copy().also { it.alpha *= alpha }, draw, key, box, dx, dy).also { fadedOp = it; fadedAlpha = alpha }
        }

        override fun shifted(dx: Int, dy: Int): (GuiGraphicsExtractor) -> Unit = LiveOp(s, draw, key, box?.shifted(dx, dy), this.dx + dx, this.dy + dy)

        override fun changed(): Boolean {
            val k = key ?: return true
            val d = drawn ?: return true
            return k.asInt != drawnKey || !drawnPin.intact() || anyIndexed(d) { LayerOp.stale(it) }
        }

        override fun samplesAtlas(): Boolean {
            val d = drawn ?: return false
            return anyIndexed(d) { LayerOp.samplesAtlas(it) }
        }

        override fun invoke(g: GuiGraphicsExtractor) {
            val keyed = key
            val k = keyed?.asInt ?: 0
            val cached = drawn
            if (cached != null && keyed != null && k == drawnKey && drawnPin.intact() && !anyIndexed(cached) { LayerOp.closed(it) }) {
                drawnPin.touch()
                val merged = drawnRuns
                for (i in merged.indices) merged[i](g)
                return
            }
            val r = liveRenderer ?: BlazeRenderer(guiScale).also { liveRenderer = it }
            r.state = s.copy()
            draw(r)
            val finished = r.finish()
            val out = if (dx != 0 || dy != 0) finished.map { shiftOp(it, dx, dy) } else if (keyed != null) ArrayList(finished) else finished
            if (held.isNotEmpty()) {
                val layers = held
                for (i in layers.indices) layers[i].release()
                held = emptyList()
            }
            if (anyIndexed(out) { it is LayerOp }) held = out.filterIsInstance<LayerOp>()
            val merged = runs(out, false)
            for (i in merged.indices) merged[i](g)
            gameFrame = r.usesGameFrame
            if (keyed != null) {
                drawn = out
                drawnRuns = merged
                drawnKey = k
                drawnPin = BitmapTextures.pin(r.bitmaps, false)
            }
            r.reset()
            GlyphAtlas.uploadDirty()
        }
    }

    fun finish(): List<(GuiGraphicsExtractor) -> Unit> {
        flush()
        return ops
    }

    private fun rectGui(x0: Float, y0: Float, x1: Float, y1: Float): ScreenRectangle? {
        val fl = floor(x0 / guiScale).toInt()
        val ft = floor(y0 / guiScale).toInt()
        val fr = ceil(x1 / guiScale).toInt()
        val fb = ceil(y1 / guiScale).toInt()
        val l = max(fl, 0)
        val t = max(ft, 0)
        val r = min(fr, screenW)
        val b = min(fb, screenH)
        if (fl < 0 || ft < 0 || fr > screenW || fb > screenH || r <= l || b <= t) screenCuts++
        return if (r <= l || b <= t) null else ScreenRectangle(l, t, r - l, b - t)
    }

    internal class MeshOp(val mesh: Mesh) : (GuiGraphicsExtractor) -> Unit {
        override fun invoke(g: GuiGraphicsExtractor) {
            if (mesh.glass) LiquidGlass.submit()
            RenderCounters.batch(mesh.vertexCount)
            //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
            g.guiRenderState.addGuiElement(mesh)
        }
    }

    internal class LiveTextOp(
        val meshes: List<Mesh>?, private val color: IntSupplier, private val alpha: Float,
        private val base: List<IntArray>? = meshes?.map { it.colors.copyOf() },
        private val fallback: (GuiGraphicsExtractor, Int) -> Unit,
    ) : (GuiGraphicsExtractor) -> Unit {
        private var drawn = 0
        private var hasDrawn = false
        private var written = 0
        private var hasWritten = false
        private var fadedOp: LiveTextOp? = null
        private var fadedAlpha = Float.NaN

        fun shifted(dx: Int, dy: Int) = LiveTextOp(meshes?.map { it.shifted(dx, dy) }, color, alpha, base) { g, c -> shiftedDraw(g, dx, dy) { fallback(it, c) } }

        fun moved(gx: Float, gy: Float): LiveTextOp? {
            if (meshes.isNullOrEmpty()) return null
            val out = ArrayList<Mesh>(meshes.size)
            for (m in meshes) out += m.moved(gx, gy) ?: return null
            return LiveTextOp(out, color, alpha, base) { g, c -> movedDraw(g, gx, gy) { fallback(it, c) } }
        }

        fun faded(a: Float): LiveTextOp {
            fadedOp?.let { if (fadedAlpha == a) return it }
            return LiveTextOp(meshes?.map { it.detached() }, color, alpha * a, base, fallback).also { fadedOp = it; fadedAlpha = a }
        }

        fun changed(): Boolean {
            val c = ColorMath.alphaMul(color.asInt, alpha)
            return !hasDrawn || c != drawn
        }

        override fun invoke(g: GuiGraphicsExtractor) {
            val c = ColorMath.alphaMul(color.asInt, alpha)
            drawn = c
            hasDrawn = true
            if (c ushr 24 == 0) return
            if (meshes == null || anyIndexed(meshes) { it.closed }) return fallback(g, c)
            val rewrite = !hasWritten || c != written
            written = c
            hasWritten = true
            val sc = if (rewrite) ARGB.scaleRGB(c, 0.25f) else 0
            for (k in meshes.indices) {
                if (rewrite) {
                    val dst = meshes[k].colors
                    val src = base!![k]
                    for (i in dst.indices) dst[i] = if (src[i] and 0xFFFFFF == 0xFFFFFF) c else sc
                }
                RenderCounters.batch(meshes[k].vertexCount)
                //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
                g.guiRenderState.addGuiElement(meshes[k])
            }
        }
    }

    internal class BakedTextOp(
        val meshes: List<Mesh>?, private val color: Int, private val draw: (GuiGraphicsExtractor, Int) -> Unit,
    ) : (GuiGraphicsExtractor) -> Unit {
        fun shifted(dx: Int, dy: Int) = BakedTextOp(meshes?.map { it.shifted(dx, dy) }, color) { g, c -> shiftedDraw(g, dx, dy) { draw(it, c) } }

        fun moved(gx: Float, gy: Float): BakedTextOp? {
            if (meshes.isNullOrEmpty()) return null
            val out = ArrayList<Mesh>(meshes.size)
            for (m in meshes) out += m.moved(gx, gy) ?: return null
            return BakedTextOp(out, color) { g, c -> movedDraw(g, gx, gy) { draw(it, c) } }
        }

        private var fadedOp: BakedTextOp? = null
        private var fadedAlpha = Float.NaN

        fun faded(alpha: Float): BakedTextOp {
            fadedOp?.let { if (fadedAlpha == alpha) return it }
            return BakedTextOp(meshes?.map { it.faded(alpha) }, ColorMath.alphaMul(color, alpha), draw).also { fadedOp = it; fadedAlpha = alpha }
        }

        override fun invoke(g: GuiGraphicsExtractor) {
            if (meshes == null || anyIndexed(meshes) { it.closed }) return draw(g, color)
            for (i in meshes.indices) {
                RenderCounters.batch(meshes[i].vertexCount)
                //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
                g.guiRenderState.addGuiElement(meshes[i])
            }
        }
    }

    internal class Mesh(
        private val pipeline: RenderPipeline,
        private val textureSetup: TextureSetup,
        private val pose: Matrix3x2f,
        private val verts: FloatArray,
        internal val colors: IntArray,
        private val bounds: ScreenRectangle,
        private val keep: Boolean,
        private val scissor: ScreenRectangle? = null,
        private val lightmap: Boolean = false,
        internal val backdrop: BackdropSpec? = null,
    ) : GuiElementRenderState {
        @JvmField var gpu: Any? = null
        @JvmField var gpuVertex = 0
        @JvmField var lastDrawn = 0
        @JvmField var lastDrawnNanos = 0L
        @JvmField var lastPass = 0
        private var drawn = false
        private var fadedMesh: Mesh? = null
        private var fadedAlpha = Float.NaN
        private var keptPipeline: RenderPipeline? = null
        val vertexCount get() = colors.size
        val texture: GpuTextureView? get() = textureSetup.texure0()
        val closed: Boolean get() = textureSetup.texure0()?.isClosed == true
        val glass: Boolean get() = backdrop != null

        fun shifted(dx: Int, dy: Int) = Mesh(
            pipeline, textureSetup, Matrix3x2f().translation(-dx.toFloat(), -dy.toFloat()).mul(pose), verts, colors,
            bounds.shifted(dx, dy), keep, scissor?.shifted(dx, dy), lightmap, backdrop?.shifted(dx, dy),
        )

        fun moved(gx: Float, gy: Float): Mesh? {
            if (scissor != null || backdrop != null) return null
            val l = floor(bounds.left() + gx).toInt()
            val t = floor(bounds.top() + gy).toInt()
            val box = ScreenRectangle(l, t, ceil(bounds.right() + gx).toInt() - l, ceil(bounds.bottom() + gy).toInt() - t)
            return Mesh(pipeline, textureSetup, Matrix3x2f().translation(gx, gy).mul(pose), verts, colors, box, keep, null, lightmap)
        }

        fun faded(alpha: Float): Mesh {
            fadedMesh?.let { if (fadedAlpha == alpha) return it }
            val out = if (backdrop != null) {
                Mesh(pipeline, textureSetup, pose, verts, colors, bounds, keep, scissor, lightmap, backdrop.faded(alpha))
            } else {
                val premul = pipeline === RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA || BlendPipelines.premultiplied(pipeline)
                Mesh(pipeline, textureSetup, pose, verts, IntArray(colors.size) { ColorMath.fadeColor(colors[it], alpha, premul) }, bounds, keep, scissor, lightmap)
            }
            fadedMesh = out
            fadedAlpha = alpha
            return out
        }

        fun detached() = Mesh(pipeline, textureSetup, pose, verts, colors.copyOf(), bounds, keep, scissor, lightmap, backdrop)

        fun mergeKey(): Any? = if (keep) MergeKey(pipeline, textureSetup, pose, scissor, lightmap) else null

        fun runsWith(o: Mesh): Boolean =
            pipeline === o.pipeline && keep == o.keep && lightmap == o.lightmap && backdrop == null && o.backdrop == null &&
                textureSetup == o.textureSetup && scissor == o.scissor

        companion object {
            fun merge(list: List<Mesh>): Mesh {
                val first = list[0]
                var n = 0
                for (m in list) n += m.colors.size
                val verts = FloatArray(n * 4)
                val colors = IntArray(n)
                var o = 0
                var l = Int.MAX_VALUE; var t = Int.MAX_VALUE; var r = Int.MIN_VALUE; var b = Int.MIN_VALUE
                for (m in list) {
                    System.arraycopy(m.verts, 0, verts, o * 4, m.colors.size * 4)
                    System.arraycopy(m.colors, 0, colors, o, m.colors.size)
                    o += m.colors.size
                    l = min(l, m.bounds.left()); t = min(t, m.bounds.top()); r = max(r, m.bounds.right()); b = max(b, m.bounds.bottom())
                }
                return Mesh(first.pipeline, first.textureSetup, first.pose, verts, colors, ScreenRectangle(l, t, r - l, b - t), true, first.scissor, first.lightmap)
            }
        }

        override fun buildVertices(consumer: VertexConsumer) {
            KeptMeshes.submitted = true
            if (gpu != null) {
                //? if < 26.2 {
                /*                repeat(4) { vertex(consumer, 0f, 0f, 0f, 0f, 0) }
                *///?}
                return
            }
            val b = consumer as? BufferBuilderAccessor
            val before = b?.`polycompose$getVertices`() ?: 0
            for (i in colors.indices) {
                val o = i * 4
                vertex(consumer, verts[o], verts[o + 1], verts[o + 2], verts[o + 3], colors[i])
            }
            val again = drawn
            drawn = true
            if (keep && again && b != null && b.`polycompose$getVertices`() - before == colors.size && b.`polycompose$getElementsToFill`() == 0) {
                val size = b.`polycompose$getVertexSize`()
                val len = colors.size * size
                val ptr = b.`polycompose$getVertexPointer`() + size - len
                KeptMeshes.capture(this, ptr, len)
            }
        }

        private fun vertex(consumer: VertexConsumer, x: Float, y: Float, u: Float, v: Float, color: Int) {
            val c = consumer.addVertexWith2DPose(pose, x, y).setUv(u, v).setColor(color)
            if (lightmap) c.setLight(FULL_BRIGHT)
        }

        override fun pipeline() = if (gpu != null) keptPipeline ?: KeptMeshes.pipeline(pipeline).also { keptPipeline = it } else pipeline
        override fun textureSetup() = textureSetup
        override fun scissorArea(): ScreenRectangle? = scissor
        override fun bounds() = bounds
    }

    internal class MeshRun(@JvmField val meshes: Array<Mesh>, private val bounds: ScreenRectangle) : (GuiGraphicsExtractor) -> Unit, GuiElementRenderState {
        override fun invoke(g: GuiGraphicsExtractor) {
            val ms = meshes
            val captured = ms[0].gpu != null
            var uniform = true
            for (i in 1 until ms.size) if ((ms[i].gpu != null) != captured) {
                uniform = false
                break
            }
            if (uniform) {
                for (i in ms.indices) RenderCounters.batch(ms[i].vertexCount)
                //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
                g.guiRenderState.addGuiElement(this)
                return
            }
            for (i in ms.indices) {
                RenderCounters.batch(ms[i].vertexCount)
                //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
                g.guiRenderState.addGuiElement(ms[i])
            }
        }

        override fun buildVertices(consumer: VertexConsumer) {
            val ms = meshes
            for (i in ms.indices) ms[i].buildVertices(consumer)
        }

        override fun pipeline() = meshes[0].pipeline()
        override fun textureSetup() = meshes[0].textureSetup()
        override fun scissorArea(): ScreenRectangle? = meshes[0].scissorArea()
        override fun bounds() = bounds
    }

    private object Runs {
        const val WINDOW = 48
        val meshes = ArrayList<Mesh>()
        val sources = ArrayList<((GuiGraphicsExtractor) -> Unit)?>()
        var next = IntArray(64)
        val groupOps = ArrayList<((GuiGraphicsExtractor) -> Unit)?>()
        var first = IntArray(32)
        var last = IntArray(32)
        var count = IntArray(32)
        var box = IntArray(128)

        val groups: Int get() = groupOps.size

        fun clear() {
            meshes.clear()
            sources.clear()
            groupOps.clear()
        }

        private fun newGroup(op: ((GuiGraphicsExtractor) -> Unit)?): Int {
            val g = groupOps.size
            if (g == first.size) {
                first = first.copyOf(g * 2)
                last = last.copyOf(g * 2)
                count = count.copyOf(g * 2)
                box = box.copyOf(g * 8)
            }
            groupOps.add(op)
            first[g] = -1
            last[g] = -1
            count[g] = 0
            return g
        }

        private fun setBox(g: Int, r: ScreenRectangle) {
            val o = g * 4
            box[o] = r.left(); box[o + 1] = r.top(); box[o + 2] = r.right(); box[o + 3] = r.bottom()
        }

        private fun unionBox(g: Int, r: ScreenRectangle) {
            val o = g * 4
            box[o] = min(box[o], r.left()); box[o + 1] = min(box[o + 1], r.top())
            box[o + 2] = max(box[o + 2], r.right()); box[o + 3] = max(box[o + 3], r.bottom())
        }

        private fun overlaps(g: Int, r: ScreenRectangle): Boolean {
            val o = g * 4
            return box[o] < r.right() && r.left() < box[o + 2] && box[o + 1] < r.bottom() && r.top() < box[o + 3]
        }

        private fun append(g: Int, mesh: Mesh, source: ((GuiGraphicsExtractor) -> Unit)?) {
            val e = meshes.size
            if (e == next.size) next = next.copyOf(e * 2)
            meshes.add(mesh)
            sources.add(source)
            next[e] = -1
            if (last[g] < 0) first[g] = e else next[last[g]] = e
            last[g] = e
            count[g]++
        }

        fun barrier(op: (GuiGraphicsExtractor) -> Unit) {
            newGroup(op)
        }

        fun fixed(op: (GuiGraphicsExtractor) -> Unit, meshes: List<Mesh>) {
            val g = newGroup(op)
            setBox(g, meshes[0].bounds())
            for (k in 1 until meshes.size) unionBox(g, meshes[k].bounds())
        }

        fun place(mesh: Mesh, source: ((GuiGraphicsExtractor) -> Unit)?, floor: Int): Boolean {
            val b = mesh.bounds()
            val stop = max(floor, groupOps.size - WINDOW)
            var g = groupOps.size - 1
            while (g >= stop) {
                if (groupOps[g] == null && meshes[first[g]].runsWith(mesh)) {
                    append(g, mesh, source)
                    unionBox(g, b)
                    return true
                }
                if (overlaps(g, b)) break
                g--
            }
            val n = newGroup(null)
            append(n, mesh, source)
            setBox(n, b)
            return false
        }

        fun build(): List<(GuiGraphicsExtractor) -> Unit> {
            val n = groupOps.size
            val out = ArrayList<(GuiGraphicsExtractor) -> Unit>(n)
            for (g in 0 until n) {
                val op = groupOps[g]
                if (op != null) {
                    out.add(op)
                    continue
                }
                val c = count[g]
                if (c == 1) {
                    val e = first[g]
                    out.add(sources[e] ?: MeshOp(meshes[e]))
                    continue
                }
                var e = first[g]
                val run = Array(c) {
                    val m = meshes[e]
                    e = next[e]
                    m
                }
                val o = g * 4
                out.add(MeshRun(run, ScreenRectangle(box[o], box[o + 1], box[o + 2] - box[o], box[o + 3] - box[o + 1])))
            }
            return out
        }
    }

    companion object {
        internal fun runs(ops: List<(GuiGraphicsExtractor) -> Unit>, text: Boolean): List<(GuiGraphicsExtractor) -> Unit> {
            if (ops.size < 2) return ops
            val r = Runs
            r.clear()
            try {
                var merged = false
                var floor = 0
                for (i in ops.indices) {
                    val op = ops[i]
                    if (op is MeshOp && !op.mesh.glass) {
                        if (r.place(op.mesh, op, floor)) merged = true
                        continue
                    }
                    if (text && op is BakedTextOp) {
                        val meshes = op.meshes
                        if (meshes != null && !anyIndexed(meshes) { it.closed }) {
                            val source = if (meshes.size == 1) op else null
                            for (k in meshes.indices) if (r.place(meshes[k], source, floor)) merged = true
                            continue
                        }
                    }
                    if (text && op is LiveTextOp) {
                        val meshes = op.meshes
                        if (!meshes.isNullOrEmpty() && !anyIndexed(meshes) { it.closed }) {
                            r.fixed(op, meshes)
                            continue
                        }
                    }
                    r.barrier(op)
                    floor = r.groups
                }
                return if (merged) r.build() else ops
            } finally {
                r.clear()
            }
        }

        fun coalesce(ops: List<(GuiGraphicsExtractor) -> Unit>): List<(GuiGraphicsExtractor) -> Unit> {
            val out = ArrayList<(GuiGraphicsExtractor) -> Unit>()
            val buckets = ArrayList<ArrayList<Mesh>?>()
            val fixed = ArrayList<LiveTextOp?>()
            val latest = HashMap<Any, Int>()
            val grid = OverlapGrid()
            fun emit() {
                for (i in buckets.indices) out.add(fixed[i] ?: buckets[i]!!.let { MeshOp(if (it.size == 1) it[0] else Mesh.merge(it)) })
                buckets.clear()
                fixed.clear()
                latest.clear()
                grid.clear()
            }
            fun place(m: Mesh) {
                val bounds = m.bounds()
                val key = m.mergeKey()
                val target = if (key == null) -1 else latest[key] ?: -1
                if (target >= 0 && target >= grid.highest(bounds, target)) {
                    buckets[target]!!.add(m)
                    grid.add(bounds, target)
                    return
                }
                val index = buckets.size
                buckets.add(arrayListOf(m))
                fixed.add(null)
                if (key != null) latest[key] = index
                grid.add(bounds, index)
            }
            for (i in ops.indices) {
                val op = ops[i]
                if (op is LiveTextOp) {
                    val live = op.meshes
                    if (!live.isNullOrEmpty() && !anyIndexed(live) { it.closed }) {
                        val index = buckets.size
                        buckets.add(null)
                        fixed.add(op)
                        for (k in live.indices) grid.add(live[k].bounds(), index)
                        continue
                    }
                }
                if (op is MeshOp) {
                    place(op.mesh)
                    continue
                }
                val meshes = if (op is BakedTextOp) op.meshes?.takeUnless { m -> anyIndexed(m) { it.closed } } else null
                if (meshes == null) {
                    emit()
                    out.add(op)
                } else {
                    for (k in meshes.indices) place(meshes[k])
                }
            }
            emit()
            return out
        }
    }
}

internal object BlendPipelines {
    private val snippet = RenderPipelinesAccessor.`polycompose$getGuiTexturedSnippet`()
    private val built = arrayOfNulls<RenderPipeline>((PolyRenderer.BLEND_PLUS + 1) * 2)

    fun supports(mode: Int): Boolean = mode in PolyRenderer.BLEND_SRC_OVER..PolyRenderer.BLEND_PLUS

    fun of(mode: Int, premultiplied: Boolean): RenderPipeline {
        val i = mode * 2 + if (premultiplied) 1 else 0
        built[i]?.let { return it }
        val src = if (premultiplied) BlendSrc.ONE else BlendSrc.SRC_ALPHA
        val function = when (mode) {
            PolyRenderer.BLEND_SRC -> BlendFunction(src, BlendDst.ZERO, BlendSrc.ONE, BlendDst.ZERO)
            PolyRenderer.BLEND_DST_OUT -> BlendFunction(BlendSrc.ZERO, BlendDst.ONE_MINUS_SRC_ALPHA, BlendSrc.ZERO, BlendDst.ONE_MINUS_SRC_ALPHA)
            PolyRenderer.BLEND_DST_IN -> BlendFunction(BlendSrc.ZERO, BlendDst.SRC_ALPHA, BlendSrc.ZERO, BlendDst.SRC_ALPHA)
            else -> BlendFunction(src, BlendDst.ONE, BlendSrc.ONE, BlendDst.ONE)
        }
        val pipeline = RenderPipeline.builder(snippet).withLocation("pipeline/polycompose_gui_blend_$i")
            //? if >= 26.3 {
            .withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", "core/poly_blend"))
            //?} else
            //.withFragmentShader(Identifier.fromNamespaceAndPath("polycompose", "core/poly_blend_legacy"))
            //? if >= 26.1 {
            .withColorTargetState(ColorTargetState(function)).build()
            //?} else
            //.withBlend(function).build()
        built[i] = pipeline
        return pipeline
    }

    val pipelines: List<RenderPipeline>
        get() = (PolyRenderer.BLEND_SRC..PolyRenderer.BLEND_PLUS).flatMap { listOf(of(it, false), of(it, true)) }

    fun owns(pipeline: RenderPipeline): Boolean = built.any { it === pipeline }

    fun premultiplied(pipeline: RenderPipeline): Boolean {
        for (i in 1 until built.size step 2) if (built[i] === pipeline) return true
        return false
    }
}

private data class MergeKey(
    val pipeline: RenderPipeline, val textureSetup: TextureSetup, val pose: Matrix3x2f, val scissor: ScreenRectangle?, val lightmap: Boolean,
)

private class OverlapGrid {
    private class Entry(val rect: ScreenRectangle, val index: Int)

    private val cells = HashMap<Long, ArrayList<Entry>>()
    private val big = ArrayList<Entry>()

    fun clear() {
        cells.clear()
        big.clear()
    }

    fun add(rect: ScreenRectangle, index: Int) {
        val entry = Entry(rect, index)
        val x0 = rect.left() shr CELL_SHIFT
        val y0 = rect.top() shr CELL_SHIFT
        val x1 = max(x0, (rect.right() - 1) shr CELL_SHIFT)
        val y1 = max(y0, (rect.bottom() - 1) shr CELL_SHIFT)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_CELLS) {
            big.add(entry)
            return
        }
        for (cy in y0..y1) for (cx in x0..x1) cells.getOrPut(cellKey(cx, cy)) { ArrayList(4) }.add(entry)
    }

    fun highest(rect: ScreenRectangle, floor: Int): Int {
        var best = scan(big, rect, floor)
        val x0 = rect.left() shr CELL_SHIFT
        val y0 = rect.top() shr CELL_SHIFT
        val x1 = max(x0, (rect.right() - 1) shr CELL_SHIFT)
        val y1 = max(y0, (rect.bottom() - 1) shr CELL_SHIFT)
        if ((x1 - x0 + 1).toLong() * (y1 - y0 + 1) > MAX_CELLS) {
            for (list in cells.values) best = scan(list, rect, best)
            return best
        }
        for (cy in y0..y1) for (cx in x0..x1) cells[cellKey(cx, cy)]?.let { best = scan(it, rect, best) }
        return best
    }

    private fun scan(list: List<Entry>, rect: ScreenRectangle, floor: Int): Int {
        var best = floor
        for (i in list.indices) {
            val e = list[i]
            if (e.index > best && overlaps(e.rect, rect)) best = e.index
        }
        return best
    }

    private fun overlaps(a: ScreenRectangle, b: ScreenRectangle): Boolean =
        a.left() < b.right() && b.left() < a.right() && a.top() < b.bottom() && b.top() < a.bottom()

    private fun cellKey(cx: Int, cy: Int): Long = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    private companion object {
        const val CELL_SHIFT = 6
        const val MAX_CELLS = 256L
    }
}

private const val FULL_BRIGHT = 0xF000F0
private const val TEXT_PAD = 4

private fun vanillaText(g: GuiGraphicsExtractor, font: Font, seq: FormattedCharSequence, m: Matrix3x2f, c: Int, shadow: Boolean, scissor: ScreenRectangle?) {
    if (scissor != null) g.enableScissor(scissor.left(), scissor.top(), scissor.right(), scissor.bottom())
    g.pose().pushMatrix()
    g.pose().mul(m)
    //~ if >= 26.1 'drawString' -> 'text'
    g.text(font, seq, 0, 0, c, shadow)
    g.pose().popMatrix()
    if (scissor != null) g.disableScissor()
}

private fun vanillaItem(g: GuiGraphicsExtractor, stack: ItemStack, m: Matrix3x2f, scissor: ScreenRectangle?) {
    if (scissor != null) g.enableScissor(scissor.left(), scissor.top(), scissor.right(), scissor.bottom())
    g.pose().pushMatrix()
    g.pose().mul(m)
    //~ if >= 26.1 'renderItem' -> 'item'
    g.item(stack, 0, 0)
    g.pose().popMatrix()
    if (scissor != null) g.disableScissor()
}

//~ if >= 26.2 'GUI_TEXT_INTENSITY' -> 'GUI_TEXT_GRAYSCALE'
private fun isTextPipeline(p: RenderPipeline) = p === RenderPipelines.GUI_TEXT || p === RenderPipelines.GUI_TEXT_GRAYSCALE

private inline fun visitRenderables(prepared: Font.PreparedText, crossinline accept: (TextRenderable) -> Unit) {
    prepared.visit(object : Font.GlyphVisitor {
        //? if >= 26.2 {
        override fun acceptRenderable(renderable: TextRenderable) = accept(renderable)
        //?} else {
        /*override fun acceptGlyph(glyph: TextRenderable.Styled) = accept(glyph)
        override fun acceptEffect(effect: TextRenderable) = accept(effect)
        *///?}
    })
}

private object GlyphQuads {
    private class Glyph(
        val advance: Float, val pipeline: RenderPipeline?, val view: GpuTextureView?,
        val left: Float, val right: Float, val up: Float, val down: Float,
        val u0: Float, val u1: Float, val v0: Float, val v1: Float,
    )

    private val UNSUPPORTED = Glyph(0f, null, null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
    private val glyphs = arrayOfNulls<Glyph>(0x590)
    private var font: Font? = null

    init {
        HostEvents.onResourcesReloaded { glyphs.fill(null) }
    }

    private fun glyph(font: Font, c: Char): Glyph {
        glyphs[c.code]?.let { return it }
        return probe(font, c).also { glyphs[c.code] = it }
    }

    private fun probe(font: Font, c: Char): Glyph {
        val str = c.toString()
        val advance = font.splitter.stringWidth(str)
        val prepared = font.prepareText(FormattedCharSequence.forward(str, Style.EMPTY), 0f, 0f, -1, true, false, 0)
        var found: TextRenderable? = null
        var count = 0
        visitRenderables(prepared) { found = it; count++ }
        val r = found ?: return if (count == 0) Glyph(advance, null, null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f) else UNSUPPORTED
        if (count != 1 || !isTextPipeline(r.guiPipeline())) return UNSUPPORTED
        val cap = BlazeRenderer.GlyphCapture()
        r.render(Matrix4f(), cap, FULL_BRIGHT, true)
        if (cap.n != 8) return UNSUPPORTED
        val v = cap.verts
        val l = v[16]; val up = v[17]; val down = v[21]; val rt = v[24]
        val u0 = v[18]; val v0 = v[19]; val v1 = v[23]; val u1 = v[26]
        val main = floatArrayOf(l, up, u0, v0, l, down, u0, v1, rt, down, u1, v1, rt, up, u1, v0)
        val shadow = floatArrayOf(1f + l, 1f + up, u0, v0, 1f + l, 1f + down, u0, v1, 1f + rt, 1f + down, u1, v1, 1f + rt, 1f + up, u1, v0)
        for (i in 0 until 16) if (v[i] != shadow[i] || v[16 + i] != main[i]) return UNSUPPORTED
        return Glyph(advance, r.guiPipeline(), r.textureView(), l, rt, up, down, u0, u1, v0, v1)
    }

    fun bake(font: Font, line: String, m: Matrix3x2f, color: Int, shadow: Boolean, scissor: ScreenRectangle?, keep: Boolean = true): List<BlazeRenderer.Mesh>? {
        if (this.font !== font) { glyphs.fill(null); this.font = font }
        for (ch in line) {
            if (ch.code >= 0x590) return null
            val g = glyph(font, ch)
            if (g === UNSUPPORTED) return null
            if (g.view?.isClosed == true) { glyphs.fill(null); return null }
        }
        val shadowColor = ARGB.scaleRGB(color, 0.25f)
        val so = if (shadow) 1f else 0f
        var left = Float.MAX_VALUE; var top = Float.MAX_VALUE; var right = -Float.MAX_VALUE; var bottom = -Float.MAX_VALUE
        var pen = 0f
        for (ch in line) {
            val g = glyphs[ch.code]!!
            if (g.pipeline != null) {
                left = min(left, pen + g.left); top = min(top, 0f + g.up)
                right = max(right, pen + g.right + so); bottom = max(bottom, 0f + g.down + so)
            }
            pen += g.advance
        }
        if (left >= right || top >= bottom) return emptyList()
        val l = floor(left).toInt(); val t = floor(top).toInt()
        var bounds = ScreenRectangle(l, t, ceil(right).toInt() - l, ceil(bottom).toInt() - t).transformMaxBounds(m)
        if (scissor != null) bounds = scissor.intersection(bounds) ?: return emptyList()
        val pose = Matrix4f().mul(m)
        val meshes = ArrayList<BlazeRenderer.Mesh>(1)
        var pipeline: RenderPipeline? = null
        var view: GpuTextureView? = null
        var cap = BlazeRenderer.GlyphCapture()
        fun close() {
            val p = pipeline ?: return
            val v = view ?: return
            if (cap.n == 0) return
            meshes.add(cap.mesh(p, v, bounds, keep, scissor))
            cap = BlazeRenderer.GlyphCapture()
        }
        pen = 0f
        for (ch in line) {
            val g = glyphs[ch.code]!!
            val p = g.pipeline
            if (p != null) {
                if (p !== pipeline || g.view !== view) { close(); pipeline = p; view = g.view }
                if (shadow) quad(cap, pose, pen + 1f, 0f + 1f, g, shadowColor)
                quad(cap, pose, pen, 0f, g, color)
            }
            pen += g.advance
        }
        close()
        return meshes
    }

    private fun quad(cap: BlazeRenderer.GlyphCapture, pose: Matrix4f, x: Float, y: Float, g: Glyph, color: Int) {
        val x0 = x + g.left; val x1 = x + g.right; val y0 = y + g.up; val y1 = y + g.down
        cap.addVertex(pose, x0, y0, 0f).setColor(color).setUv(g.u0, g.v0)
        cap.addVertex(pose, x0, y1, 0f).setColor(color).setUv(g.u0, g.v1)
        cap.addVertex(pose, x1, y1, 0f).setColor(color).setUv(g.u1, g.v1)
        cap.addVertex(pose, x1, y0, 0f).setColor(color).setUv(g.u1, g.v0)
    }
}

private fun textLines(text: String): List<String> =
    if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) listOf(text) else text.lines()

private object TextWidths {
    private val plain = HashMap<String, Int>()
    private val literal = HashMap<String, Int>()

    init {
        HostEvents.onResourcesReloaded {
            plain.clear()
            literal.clear()
        }
    }

    fun of(font: Font, line: String, literal: Boolean): Int {
        val map = if (literal) this.literal else plain
        if (map.size > 4096) map.clear()
        return map.getOrPut(line) { font.width(visualOrder(line, literal)) }
    }
}

private fun visualOrder(line: String, literal: Boolean = false): FormattedCharSequence {
    val visual = mcVisualOrder(line, literal)
    if (literal || '§' !in visual) return FormattedCharSequence.forward(visual, Style.EMPTY)
    return FormattedCharSequence { sink -> StringDecomposer.iterateFormatted(visual, Style.EMPTY, sink) }
}

private fun snap(pixel: Float) = pixel.roundToInt().toFloat()

internal object McOutlines {
    private const val MAX_ENTRIES = 32
    private const val HARD_MAX_ENTRIES = 64
    private const val MAX_POOLED = 8
    private const val TARGET_STEP = 32
    private const val PAD_FONT_PX = 2f
    private val OFFSETS = floatArrayOf(-1f, -1f, 0f, -1f, 1f, -1f, -1f, 0f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f)

    internal data class Key(val text: String, val kx: Float, val ky: Float, val fx: Float, val fy: Float, val literal: Boolean = false)

    private val entries = LinkedHashMap<Key, Outline>(16, 0.75f, true)
    private val pool = ArrayList<OffscreenTarget>()
    private var generation = 0

    init {
        HostEvents.onResourcesReloaded {
            generation++
            entries.values.forEach { it.release() }
            entries.clear()
        }
    }

    fun get(text: String, kx: Float, ky: Float, fx: Float, fy: Float, literal: Boolean = false): Outline? {
        if (kx <= 0f || ky <= 0f) return null
        val key = Key(text, kx, ky, (fx * 8f).roundToInt() / 8f, (fy * 8f).roundToInt() / 8f, literal)
        entries[key]?.let { return it }
        val font = Minecraft.getInstance().font
        val lines = textLines(text)
        val width = lines.maxOf { font.width(visualOrder(it, literal)) }
        val pad = ceil(PAD_FONT_PX * max(kx, ky))
        val w = ceil(pad * 2f + width * kx + 1f)
        val h = ceil(pad * 2f + lines.size * font.lineHeight * ky + 1f)
        val outline = Outline(key, lines, pad, w, h, generation)
        entries[key] = outline
        evict()
        return outline
    }

    private fun evict() {
        if (entries.size <= MAX_ENTRIES) return
        val now = System.nanoTime()
        val it = entries.values.iterator()
        while (entries.size > MAX_ENTRIES && it.hasNext()) {
            val outline = it.next()
            if (now - outline.lastUsed < 1_000_000_000L) continue
            it.remove()
            outline.release()
        }
        while (entries.size > HARD_MAX_ENTRIES) {
            val oldest = entries.values.minByOrNull { it.lastUsed } ?: break
            entries.remove(oldest.key)
            oldest.release()
        }
    }

    private fun acquire(w: Int, h: Int): OffscreenTarget {
        var best: OffscreenTarget? = null
        var bestArea = Long.MAX_VALUE
        for (t in pool) {
            val rt = t.target ?: continue
            val area = rt.width.toLong() * rt.height
            if (rt.width >= w && rt.height >= h && area < bestArea) {
                best = t
                bestArea = area
            }
        }
        if (best != null) {
            pool.remove(best)
            return best
        }
        return pool.removeLastOrNull() ?: OffscreenTarget(depth = false)
    }

    private fun fit(current: Int, needed: Int): Int =
        if (current >= needed && current <= needed * 2 + TARGET_STEP) current else (needed + TARGET_STEP - 1) / TARGET_STEP * TARGET_STEP

    private fun uncolored(seq: FormattedCharSequence) = FormattedCharSequence { sink ->
        seq.accept { index, style, codePoint -> sink.accept(index, style.withColor(null as TextColor?), codePoint) }
    }

    class Outline internal constructor(internal val key: Key, private val lines: List<String>, val pad: Float, val w: Float, val h: Float, private val generation: Int) {
        internal val obfuscated = !key.literal && key.text.contains("§k", ignoreCase = true)
        private var target: OffscreenTarget? = null
        private var dirty = true
        internal var lastUsed = System.nanoTime()

        fun prepare(): RenderTarget? {
            lastUsed = System.nanoTime()
            if (target == null && generation == McOutlines.generation) entries.putIfAbsent(key, this)
            if (dirty || obfuscated || target?.target == null) render()
            return target?.target
        }

        internal fun release() {
            val t = target ?: return
            target = null
            if (pool.size < MAX_POOLED) pool.add(t) else t.dispose()
        }

        private fun render() {
            val needW = w.toInt()
            val needH = h.toInt()
            val offscreen = target ?: acquire(needW, needH).also { target = it }
            val current = offscreen.target
            if (!offscreen.resolve(fit(current?.width ?: 0, needW), fit(current?.height ?: 0, needH))) return
            val rt = offscreen.target ?: return
            offscreen.clear()
            val mc = Minecraft.getInstance()
            val slot = OffscreenGuiRenderers.acquire(mc)
            try {
                GnetumCompat.restoring { renderInto(mc, slot.state, slot.renderer, rt) }
            } finally {
                OffscreenGuiRenderers.release(slot)
            }
            dirty = false
        }

        private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget) {
            val graphics = GuiGraphicsExtractor(mc, state, rt.width, rt.height)
            val font = mc.font
            val ox = pad + key.fx
            val oy = pad + key.fy
            lines.forEachIndexed { index, line ->
                if (line.isEmpty()) return@forEachIndexed
                val seq = uncolored(visualOrder(line, key.literal))
                for (i in OFFSETS.indices step 2) {
                    val pose = graphics.pose()
                    pose.pushMatrix()
                    pose.translate(ox, oy + snap(index * font.lineHeight * key.ky)).scale(key.kx, key.ky).translate(OFFSETS[i], OFFSETS[i + 1])
                    //~ if >= 26.1 'drawString' -> 'text'
                    graphics.text(font, seq, 0, 0, -1, false)
                    pose.popMatrix()
                }
            }
            renderGui(renderer, rt, 1, GuiTargetRedirect.scissorTransform)
        }
    }
}

private fun renderGui(renderer: GuiRenderer, rt: RenderTarget, itemRenderSizePx: Int, scissor: GuiTargetRedirect.ScissorTransform?) {
    val previousTarget = GuiTargetRedirect.target
    val previousScissor = GuiTargetRedirect.scissorTransform
    val previousSize = GuiTargetRedirect.itemRenderSizePx
    GuiTargetRedirect.target = rt
    GuiTargetRedirect.scissorTransform = scissor
    GuiTargetRedirect.itemRenderSizePx = itemRenderSizePx
    try {
        //? if >= 26.2 {
        renderer.render()
        //?} else {
        /*renderer.render((Minecraft.getInstance().gameRenderer as GameRendererAccessor).`polycompose$getFogRenderer`().getBuffer(FogRenderer.FogMode.NONE))
        *///?}
    } finally {
        GuiTargetRedirect.itemRenderSizePx = previousSize
        GuiTargetRedirect.scissorTransform = previousScissor
        GuiTargetRedirect.target = previousTarget
    }
    //? if >= 26.1 {
    renderer.endFrame()
    //?} else
    //renderer.incrementFrameNumber()
}

private fun compositeMesh(
    view: GpuTextureView, rt: RenderTarget, pose: Matrix3x2f, x0: Float, y0: Float, x1: Float, y1: Float,
    w: Float, h: Float, color: Int, bounds: ScreenRectangle, scissor: ScreenRectangle?,
    pipeline: RenderPipeline = RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA,
): BlazeRenderer.Mesh {
    val u1 = w / rt.width
    val v0 = targetV(1f)
    val v1 = targetV(1f - h / rt.height)
    val verts = floatArrayOf(x0, y0, 0f, v0, x0, y1, 0f, v1, x1, y1, u1, v1, x1, y0, u1, v0)
    val setup = TextureSetup.singleTexture(view, sampler(true))
    return BlazeRenderer.Mesh(pipeline, setup, pose, verts, IntArray(4) { color }, bounds, false, scissor)
}

internal object OffscreenGuiRenderers {
    //? if >= 26.2 {
    private const val MAX_RENDERS_PER_FRAME = Int.MAX_VALUE
    private const val SPREAD_SLOTS = 0
    //?} else {
    /*private const val MAX_RENDERS_PER_FRAME = 3
    private const val SPREAD_SLOTS = 8
    *///?}
    private const val IDLE_FRAMES = 60
    private const val WARM_SLOTS = 1
    private val spreadSlots = if (GlHost.isVulkanModInstalled) Int.MAX_VALUE else SPREAD_SLOTS

    class Slot(val state: GuiRenderState, val renderer: GuiRenderer) {
        internal var renders = 0
        internal var held = false
        internal var lastFrame = 0L
    }

    private val slots = ArrayList<Slot>()
    private var frame = 0L

    init {
        HostEvents.onFrameEnd {
            frame++
            var warm = 0
            val it = slots.iterator()
            while (it.hasNext()) {
                val slot = it.next()
                if (frame - slot.lastFrame > IDLE_FRAMES && !slot.held && warm++ >= WARM_SLOTS) {
                    it.remove()
                    slot.renderer.close()
                } else {
                    slot.renders = 0
                }
            }
        }
    }

    fun acquire(mc: Minecraft): Slot {
        val least = slots.minByOrNull { if (it.held) Int.MAX_VALUE else it.renders }
        val slot = least?.takeUnless { it.held || it.renders >= MAX_RENDERS_PER_FRAME || (it.renders > 0 && slots.size < spreadSlots) }
            ?: GuiRenderState().let { (it as? GnetumOffscreenState)?.`polycompose$markOffscreen`(); Slot(it, create(mc, it)) }.also(slots::add)
        slot.held = true
        slot.renders++
        slot.lastFrame = frame
        return slot
    }

    fun release(slot: Slot) {
        slot.held = false
    }

    private fun create(mc: Minecraft, state: GuiRenderState): GuiRenderer {
        //? if >= 26.2 {
        return GuiRenderer(state, mc.gameRenderer.featureRenderDispatcher(), emptyList())
        //?} else {
        /*return GuiRenderer(state, mc.renderBuffers().bufferSource(), mc.gameRenderer.getSubmitNodeStorage(), mc.gameRenderer.getFeatureRenderDispatcher(), emptyList())
        *///?}
    }
}

internal val topDownTargets = GlHost.isVulkanModInstalled

private fun targetV(v: Float) = if (topDownTargets) 1f - v else v

//? if >= 26.2 {
private const val TEXT_LIGHTMAP = false
//?} else
//private const val TEXT_LIGHTMAP = true

interface KeptMeshScissor {
    fun `polycompose$enableScissor`(area: ScreenRectangle, pass: RenderPass)
}

internal object KeptMeshes {
    private const val RELEASE_FRAMES = 10
    private const val RELEASE_NANOS = 500_000_000L
    private var frame = 0
    private var frameNanos = System.nanoTime()
    private var pass = 0
    private var scanAt = 0
    private val alive = ArrayList<BlazeRenderer.Mesh>()
    private val byDraw = IdentityHashMap<Any, ArrayList<BlazeRenderer.Mesh>>()
    private val lists = ArrayList<ArrayList<BlazeRenderer.Mesh>>()
    private val usedLists = ArrayList<ArrayList<BlazeRenderer.Mesh>>()
    private val dirtyArenas = ArrayList<Arena>()

    @JvmField var submitted = false

    private fun list(): ArrayList<BlazeRenderer.Mesh> {
        val l = if (lists.isEmpty()) ArrayList(2) else lists.removeAt(lists.size - 1)
        usedLists.add(l)
        return l
    }

    private fun recycleLists() {
        //? if < 26.2 {
        /*if (byIndex.isNotEmpty()) { usedLists.clear(); return }
        *///?}
        for (i in usedLists.indices) {
            val l = usedLists[i]
            l.clear()
            lists.add(l)
        }
        usedLists.clear()
    }

    private val snippet = RenderPipelinesAccessor.`polycompose$getGuiTexturedSnippet`()
    private val straight = RenderPipeline.builder(snippet).withLocation("pipeline/polycompose_gui_kept").build()
    private val premultiplied = RenderPipeline.builder(snippet).withLocation("pipeline/polycompose_gui_kept_premultiplied")
        //? if >= 26.1 {
        .withColorTargetState(ColorTargetState(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA)).build()
        //?} else
        //.withBlend(BlendFunction.TRANSLUCENT_PREMULTIPLIED_ALPHA).build()

    private val textSnippet = RenderPipelinesAccessor.`polycompose$getGuiTextSnippet`()
    //? if >= 26.3 {
    private val text = RenderPipeline.builder(textSnippet).withLocation("pipeline/polycompose_gui_text_kept").build()
    private val textGrayscale = RenderPipeline.builder(textSnippet).withLocation("pipeline/polycompose_gui_text_grayscale_kept").withShaderDefine("IS_GRAYSCALE").build()
    //?} elif >= 26.2 {
        /*private val text = RenderPipeline.builder(textSnippet).withLocation("pipeline/polycompose_gui_text_kept")
        .withVertexShader("core/text").withFragmentShader("core/text").build()
    private val textGrayscale = RenderPipeline.builder(textSnippet).withLocation("pipeline/polycompose_gui_text_grayscale_kept")
        .withVertexShader("core/text").withFragmentShader("core/text").withShaderDefine("IS_GRAYSCALE").build()
    *///?} else {
    /*    private val fogSnippet = RenderPipelinesAccessor.`polycompose$getFogSnippet`()
    private val text = RenderPipeline.builder(textSnippet, fogSnippet).withLocation("pipeline/polycompose_gui_text_kept")
        .withVertexShader("core/rendertype_text").withFragmentShader("core/rendertype_text").withSampler("Sampler0").withSampler("Sampler2").build()
    private val textGrayscale = RenderPipeline.builder(textSnippet, fogSnippet).withLocation("pipeline/polycompose_gui_text_intensity_kept")
        .withVertexShader("core/rendertype_text_intensity").withFragmentShader("core/rendertype_text_intensity").withSampler("Sampler0").withSampler("Sampler2").build()
    *///?}

    internal val pipelines: List<RenderPipeline> get() = listOf(straight, premultiplied, text, textGrayscale)

    fun pipeline(vanilla: RenderPipeline) = when {
        vanilla === RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA -> premultiplied
        vanilla === RenderPipelines.GUI_TEXT -> text
        //~ if >= 26.2 'GUI_TEXT_INTENSITY' -> 'GUI_TEXT_GRAYSCALE'
        vanilla === RenderPipelines.GUI_TEXT_GRAYSCALE -> textGrayscale
        BlendPipelines.owns(vanilla) -> vanilla
        else -> straight
    }

    private const val ARENA_BYTES = 1 shl 18

    private class Arena(val stride: Int, vertices: Int) {
        val buffer: GpuBuffer = RenderSystem.getDevice()
            .createBuffer({ "PolyCompose kept GUI meshes" }, GpuBuffer.USAGE_VERTEX or GpuBuffer.USAGE_COPY_DST, vertices.toLong() * stride)
        val whole = buffer.slice()
        var live = 0
            private set
        private var starts = IntArray(8)
        private var counts = IntArray(8)
        private var blocks = 1
        private var mirror = MemoryUtil.nmemCalloc(1L, vertices.toLong() * stride)
        private var dirtyFrom = Long.MAX_VALUE
        private var dirtyTo = 0L

        init {
            counts[0] = vertices
        }

        fun stage(at: Int, ptr: Long, len: Int): Boolean {
            val from = at.toLong() * stride
            if (mirror == 0L) {
                RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(from, len.toLong()), MemoryUtil.memByteBuffer(ptr, len))
                return false
            }
            MemoryUtil.memCopy(ptr, mirror + from, len.toLong())
            val dirty = dirtyFrom != Long.MAX_VALUE
            dirtyFrom = min(dirtyFrom, from)
            dirtyTo = max(dirtyTo, from + len)
            return !dirty
        }

        fun upload() {
            if (dirtyFrom == Long.MAX_VALUE) return
            val len = dirtyTo - dirtyFrom
            RenderSystem.getDevice().createCommandEncoder().writeToBuffer(buffer.slice(dirtyFrom, len), MemoryUtil.memByteBuffer(mirror + dirtyFrom, len.toInt()))
            dirtyFrom = Long.MAX_VALUE
            dirtyTo = 0L
        }

        fun close() {
            buffer.close()
            if (mirror != 0L) MemoryUtil.nmemFree(mirror)
            mirror = 0L
            dirtyFrom = Long.MAX_VALUE
            dirtyTo = 0L
        }

        fun alloc(n: Int): Int {
            for (i in 0 until blocks) {
                if (counts[i] < n) continue
                val at = starts[i]
                if (counts[i] == n) remove(i) else { starts[i] += n; counts[i] -= n }
                live++
                return at
            }
            return -1
        }

        fun free(at: Int, n: Int) {
            live--
            var i = 0
            while (i < blocks && starts[i] < at) i++
            val joinPrev = i > 0 && starts[i - 1] + counts[i - 1] == at
            val joinNext = i < blocks && at + n == starts[i]
            when {
                joinPrev && joinNext -> { counts[i - 1] += n + counts[i]; remove(i) }
                joinPrev -> counts[i - 1] += n
                joinNext -> { starts[i] = at; counts[i] += n }
                else -> insert(i, at, n)
            }
        }

        private fun remove(i: Int) {
            System.arraycopy(starts, i + 1, starts, i, blocks - i - 1)
            System.arraycopy(counts, i + 1, counts, i, blocks - i - 1)
            blocks--
        }

        private fun insert(i: Int, at: Int, n: Int) {
            if (blocks == starts.size) {
                starts = starts.copyOf(blocks * 2)
                counts = counts.copyOf(blocks * 2)
            }
            System.arraycopy(starts, i, starts, i + 1, blocks - i)
            System.arraycopy(counts, i, counts, i + 1, blocks - i)
            starts[i] = at
            counts[i] = n
            blocks++
        }
    }

    private val arenas = HashMap<Int, ArrayList<Arena>>()

    fun capture(mesh: BlazeRenderer.Mesh, ptr: Long, len: Int) {
        val n = mesh.vertexCount
        if (n == 0 || len % n != 0) return
        val stride = len / n
        val list = arenas.getOrPut(stride) { ArrayList(2) }
        var arena: Arena? = null
        var at = -1
        for (a in list) {
            at = a.alloc(n)
            if (at >= 0) { arena = a; break }
        }
        if (arena == null) {
            arena = Arena(stride, max(ARENA_BYTES / stride, n))
            list.add(arena)
            at = arena.alloc(n)
        }
        if (arena.stage(at, ptr, len)) dirtyArenas.add(arena)
        mesh.gpu = arena
        mesh.gpuVertex = at
        mesh.lastDrawn = frame
        mesh.lastDrawnNanos = frameNanos
        mesh.lastPass = pass
        alive.add(mesh)
        scanAt = min(scanAt, frame + RELEASE_FRAMES + 1)
    }

    private fun kept(element: GuiElementRenderState): BlazeRenderer.Mesh? {
        if (element !is BlazeRenderer.Mesh || element.gpu == null || element.lastPass == pass) return null
        element.lastPass = pass
        element.lastDrawn = frame
        element.lastDrawnNanos = frameNanos
        RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(element.vertexCount / 4 * 6)
        return element
    }

    //? if >= 26.2 {
    @JvmStatic
    fun added(draw: Any, element: GuiElementRenderState) {
        if (element is BlazeRenderer.MeshRun) {
            val meshes = element.meshes
            for (i in meshes.indices) {
                val mesh = kept(meshes[i]) ?: continue
                (byDraw[draw] ?: list().also { byDraw[draw] = it }).add(mesh)
            }
            return
        }
        val mesh = kept(element) ?: return
        (byDraw[draw] ?: list().also { byDraw[draw] = it }).add(mesh)
    }
    //?} else {
    /*    private val byIndex = HashMap<Int, ArrayList<BlazeRenderer.Mesh>>()

    @JvmStatic
    fun added(index: Int, element: GuiElementRenderState) {
        if (element is BlazeRenderer.MeshRun) {
            val meshes = element.meshes
            for (i in meshes.indices) {
                val mesh = kept(meshes[i]) ?: continue
                (byIndex[index] ?: list().also { byIndex[index] = it }).add(mesh)
            }
            return
        }
        val mesh = kept(element) ?: return
        (byIndex[index] ?: list().also { byIndex[index] = it }).add(mesh)
    }

    @JvmStatic
    fun recorded(draws: List<*>) {
        if (byIndex.isEmpty()) return
        for ((i, meshes) in byIndex) byDraw[draws[i]!!] = meshes
        byIndex.clear()
    }
    *///?}

    @JvmStatic
    fun execute(draw: Any, pass: RenderPass, scissors: KeptMeshScissor): Boolean {
        if (byDraw.isEmpty()) return false
        val meshes = byDraw[draw] ?: return false
        val first = meshes[0]
        val scissor = first.scissorArea()
        if (scissor == null) pass.disableScissor() else scissors.`polycompose$enableScissor`(scissor, pass)
        val setup = first.textureSetup()
        //? if >= 26.3 {
        pass.setPipeline(RenderSystem.getCompiledPipeline(first.pipeline()))
        if (setup.texure0() != null) pass.setUniform("Sampler0", setup.texure0(), setup.sampler0())
        //?} else {
        /*pass.setPipeline(first.pipeline())
        if (setup.texure0() != null) pass.bindTexture("Sampler0", setup.texure0(), setup.sampler0())
        if (setup.texure2() != null) pass.bindTexture("Sampler2", setup.texure2(), setup.sampler2())
        *///?}
        val indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS)
        var maxVertices = 0
        for (i in meshes.indices) maxVertices = max(maxVertices, meshes[i].vertexCount)
        pass.setIndexBuffer(indices.getBuffer(maxVertices / 4 * 6), indices.type())
        var bound: Arena? = null
        for (i in meshes.indices) {
            val m = meshes[i]
            val arena = m.gpu as Arena
            //? if >= 26.2 {
            if (arena !== bound) pass.setVertexBuffer(0, arena.whole)
            pass.drawIndexed(m.vertexCount / 4 * 6, 1, 0, m.gpuVertex, 0)
            //?} else {
            /*if (arena !== bound) pass.setVertexBuffer(0, arena.buffer)
            pass.drawIndexed(m.gpuVertex, 0, m.vertexCount / 4 * 6, 1)
            *///?}
            bound = arena
        }
        return true
    }

    @JvmStatic
    fun endFrame(realFrame: Boolean) {
        submitted = false
        if (dirtyArenas.isNotEmpty()) upload()
        if (byDraw.isNotEmpty()) byDraw.clear()
        if (usedLists.isNotEmpty()) recycleLists()
        pass++
        if (!realFrame || GuiTargetRedirect.target != null) return
        frame++
        frameNanos = System.nanoTime()
        if (frame < scanAt) return
        var next = Int.MAX_VALUE
        var kept = 0
        for (i in alive.indices) {
            val m = alive[i]
            val staleFrames = frame - m.lastDrawn > RELEASE_FRAMES
            if (staleFrames && frameNanos - m.lastDrawnNanos > RELEASE_NANOS) {
                release(m.gpu as Arena, m.gpuVertex, m.vertexCount)
                m.gpu = null
            } else {
                next = min(next, if (staleFrames) frame + RELEASE_FRAMES else m.lastDrawn + RELEASE_FRAMES + 1)
                alive[kept++] = m
            }
        }
        while (alive.size > kept) alive.removeAt(alive.size - 1)
        scanAt = next
    }

    private fun upload() {
        for (i in dirtyArenas.indices) dirtyArenas[i].upload()
        dirtyArenas.clear()
    }

    private fun release(arena: Arena, at: Int, n: Int) {
        arena.free(at, n)
        if (arena.live > 0) return
        val list = arenas[arena.stride] ?: return
        if (list.size > 1 || arena.buffer.size() > ARENA_BYTES) {
            list.remove(arena)
            arena.close()
            if (list.isEmpty()) arenas.remove(arena.stride)
        }
    }
}

object BlazeHud {
    private var dirty = true
    private var scale = 0f
    private val stable = Recording(coalesceFrames = COALESCE_FRAMES)
    private val volatile = Recording(coalesce = false)
    private var stableRenderer: BlazeRenderer? = null
    private var volatileRenderer: BlazeRenderer? = null

    @JvmStatic
    fun markDirty() {
        dirty = true
    }

    @JvmStatic
    fun render(gfx: GuiGraphicsExtractor, draw: (stable: RenderContext, volatile: RenderContext) -> Unit) {
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        if (dirty || guiScale != scale || stable.stale() || volatile.stale()) {
            scale = guiScale
            val a = reuse(stableRenderer, guiScale).also { stableRenderer = it }
            val b = reuse(volatileRenderer, guiScale).also { volatileRenderer = it }
            draw(RenderContext(a), RenderContext(b))
            stable.take(a, copy = true)
            volatile.take(b, copy = true)
            GlyphAtlas.uploadDirty()
        } else {
            stable.idle()
            volatile.idle()
        }
        dirty = false
        stacking(gfx) {
            stable.replay(gfx)
            volatile.replay(gfx)
        }
    }

    private fun reuse(renderer: BlazeRenderer?, guiScale: Float): BlazeRenderer {
        if (renderer == null || !renderer.matches(guiScale)) return BlazeRenderer(guiScale, keepVertices = true)
        renderer.reset()
        return renderer
    }
}

object BlazeOverlays {
    private class Pass {
        val recording = Recording()
        var drewLastFrame = false
        var profile: ProfileHost? = null
        var overlay: OverlayRenderer? = null
        val draw: (RenderContext) -> Unit = { ctx -> overlay?.draw(ctx) }
    }

    private val passes = WeakHashMap<OverlayRenderer, Pass>()

    @JvmStatic
    fun render(gfx: GuiGraphicsExtractor) {
        val overlays = ComposeHost.overlays
        if (overlays.isEmpty()) return
        val sw = McWindow.guiWidth()
        val sh = McWindow.guiHeight()
        val hidden = guiHidden()
        var i = 0
        while (i < overlays.size) {
            val overlay = overlays[i++]
            val pass = passes.getOrPut(overlay, ::Pass)
            if (HostCallbacks.isDisabled(overlay)) continue
            val show = HostCallbacks.call(overlay, "OverlayRenderer.visible", false) {
                overlay.visible && (!hidden || overlay.showWhenGuiHidden)
            }
            val first = show && !pass.drewLastFrame
            pass.drewLastFrame = false
            if (!show || sw <= 0 || sh <= 0) continue
            val changed = HostCallbacks.call<Boolean?>(overlay, "OverlayRenderer.prepare", null) {
                overlay.prepare(sw.toFloat(), sh.toFloat())
            } ?: continue
            gfx.nextStratum()
            val profile = if (FrameProfiler.enabled) pass.profile ?: ProfilerHooks.overlayHost(overlay).also { pass.profile = it } else null
            pass.overlay = overlay
            val drawn = try {
                HostCallbacks.call<Unit?>(overlay, "OverlayRenderer.draw", null) {
                    profile.measure(ProfilePhase.Record) { pass.recording.render(gfx, changed || first, pass.draw) }
                }
            } finally {
                pass.overlay = null
            }
            drawn ?: continue
            pass.drewLastFrame = true
        }
    }
}

internal class Recording(private val coalesce: Boolean = true, private val coalesceFrames: Int = SCREEN_COALESCE_FRAMES) {
    private var ops: List<(GuiGraphicsExtractor) -> Unit> = emptyList()
    private var raw: List<(GuiGraphicsExtractor) -> Unit> = emptyList()
    private var stable = 0
    private var pin = BitmapTextures.unpinned
    private var scale = 0f
    private var gameFrame = false
    private var lives: List<LiveDraw> = emptyList()
    private var textures: List<GpuTextureView> = emptyList()
    private var textTextures: List<GpuTextureView> = emptyList()
    private var spare: BlazeRenderer? = null

    val usesGameFrame: Boolean get() = gameFrame || (lives.isNotEmpty() && anyIndexed(lives) { it.gameFrame })

    fun render(gfx: GuiGraphicsExtractor, dirty: Boolean, draw: (RenderContext) -> Unit) {
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat()
        if (dirty || guiScale != scale || stale()) {
            scale = guiScale
            val reused = spare?.takeIf { it.matches(guiScale) }
            spare = null
            val renderer = reused?.also { it.reset() } ?: BlazeRenderer(guiScale, keepVertices = true)
            draw(RenderContext(renderer))
            take(renderer, copy = true)
            spare = renderer
            GlyphAtlas.uploadDirty()
        } else {
            idle()
        }
        stacking(gfx) { replay(gfx) }
    }

    fun take(renderer: BlazeRenderer, copy: Boolean = false) {
        val next = renderer.finish()
        if (coalesce && sameOps(next, raw)) {
            idle()
            return
        }
        if (!coalesce && next !== raw && sameOps(next, raw)) {
            gameFrame = renderer.usesGameFrame
            stable = 0
            pin.touch()
            if (ops !== raw && (anyIndexed(textTextures) { it.isClosed } || stale())) ops = raw
            return
        }
        val old = raw
        raw = if (copy) ArrayList(next) else next
        LayerOp.retire(old, raw)
        ops = BlazeRenderer.runs(raw, true)
        stable = 0
        gameFrame = renderer.usesGameFrame
        lives = if (anyIndexed(raw) { it is LiveDraw }) raw.filterIsInstance<LiveDraw>() else emptyList()
        collectTextures(raw)
        pin.release()
        pin = BitmapTextures.pin(renderer.bitmaps, true)
    }

    private fun sameOps(a: List<(GuiGraphicsExtractor) -> Unit>, b: List<(GuiGraphicsExtractor) -> Unit>): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) if (a[i] !== b[i]) return false
        return true
    }

    private fun collectTextures(list: List<(GuiGraphicsExtractor) -> Unit>) {
        val hard = Collections.newSetFromMap(IdentityHashMap<GpuTextureView, Boolean>())
        val soft = Collections.newSetFromMap(IdentityHashMap<GpuTextureView, Boolean>())
        for (i in list.indices) {
            val op = list[i]
            if (op is BlazeRenderer.BakedTextOp) {
                val meshes = op.meshes ?: continue
                for (k in meshes.indices) meshes[k].texture?.let(soft::add)
            } else {
                LayerOp.textures(op, hard)
            }
        }
        soft.removeAll(hard)
        textures = if (hard.isEmpty()) emptyList() else ArrayList(hard)
        textTextures = if (soft.isEmpty()) emptyList() else ArrayList(soft)
    }

    fun stale(): Boolean = anyIndexed(textures) { it.isClosed }

    fun idle() {
        pin.touch()
        if (coalesce && ++stable == coalesceFrames) ops = BlazeRenderer.coalesce(raw)
        if (ops !== raw && (anyIndexed(textTextures) { it.isClosed } || stale())) ops = raw
    }

    fun replay(gfx: GuiGraphicsExtractor) {
        val ops = ops
        for (i in ops.indices) ops[i](gfx)
    }
}

internal class LayerOp private constructor(
    private val ops: List<(GuiGraphicsExtractor) -> Unit>, private val alpha: Float, private val guiScale: Float, val bounds: ScreenRectangle,
    private val effect: LayerEffect? = null,
    private val mode: Int = PolyRenderer.BLEND_SRC_OVER,
) : (GuiGraphicsExtractor) -> Unit {
    private var target: OffscreenTarget? = null
    private var effectTarget: OffscreenTarget? = null
    private var effectUniforms: OwnedUniforms? = null
    private var composite: BlazeRenderer.Mesh? = null
    private var usedFrame = 0L
    private var seenAt = 0L
    private val dynamic = anyIndexed(ops) { mayChange(it) }
    private var atlasGeneration = 0
    private var fadedOp: LayerOp? = null
    private var fadedAlpha = Float.NaN

    fun moved(dx: Int, dy: Int) = LayerOp(ops, alpha, guiScale, bounds.shifted(dx, dy), effect, mode)

    fun faded(a: Float): LayerOp {
        fadedOp?.let { if (fadedAlpha == a) return it }
        return LayerOp(ops, alpha * a, guiScale, bounds, effect, mode).also { fadedOp = it; fadedAlpha = a }
    }

    private fun stale(): Boolean = (dynamic && anyIndexed(ops) { stale(it) }) || atlasStale()

    private fun atlasStale(): Boolean {
        val generation = McItemAtlas.contentGeneration
        if (atlasGeneration == generation) return false
        if (anyIndexed(ops) { samplesAtlas(it) }) return true
        atlasGeneration = generation
        return false
    }

    override fun invoke(g: GuiGraphicsExtractor) {
        usedFrame = frame
        val c = composite?.takeUnless { it.closed || stale() } ?: render() ?: return
        //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
        g.guiRenderState.addGuiElement(c)
    }

    fun release() {
        active.remove(this)
        target?.let(::recycle)
        target = null
        effectTarget?.let(::recycle)
        effectTarget = null
        effectUniforms?.close()
        effectUniforms = null
        composite = null
        for (i in ops.indices) {
            val op = ops[i]
            if (op is LayerOp) op.release()
        }
    }

    private fun render(): BlazeRenderer.Mesh? {
        EffectGpuTimer.layerBegin()
        try {
            return renderOffscreen()
        } finally {
            EffectGpuTimer.layerEnd()
        }
    }

    private fun renderOffscreen(): BlazeRenderer.Mesh? {
        val gs = guiScale.roundToInt()
        val w = bounds.width() * gs
        val h = bounds.height() * gs
        val offscreen = target ?: acquire(w, h).also { target = it; active.add(this) }
        val generation = McItemAtlas.contentGeneration
        val current = offscreen.target
        if (!offscreen.resolve(PolyTessellator.layerTargetSize(current?.width ?: 0, w), PolyTessellator.layerTargetSize(current?.height ?: 0, h))) return null
        val rt = offscreen.target ?: return null
        offscreen.clear()
        val mc = Minecraft.getInstance()
        val slot = OffscreenGuiRenderers.acquire(mc)
        try {
            renderInto(mc, slot.state, slot.renderer, rt, gs)
        } finally {
            OffscreenGuiRenderers.release(slot)
        }
        var view = rt.colorTextureView ?: return null
        var shown = rt
        val e = effect
        var ox = 0f
        var oy = 0f
        if (e != null) {
            ox = e.chain.offsetX * e.scale / guiScale
            oy = e.chain.offsetY * e.scale / guiScale
            val applied = if (e.passthrough) null else applyEffect(e, rt, w, h)
            if (applied != null) {
                view = applied.colorTextureView ?: view
                shown = applied
            }
        }
        val a = ColorMath.alphaByte(alpha)
        val l = bounds.left() + ox; val t = bounds.top() + oy; val r = bounds.right() + ox; val b = bounds.bottom() + oy
        val box = if (ox == 0f && oy == 0f) bounds else bounds.shifted(-ox.roundToInt(), -oy.roundToInt())
        val pipeline = if (mode == PolyRenderer.BLEND_SRC_OVER) RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA else BlendPipelines.of(mode, true)
        return compositeMesh(view, shown, Matrix3x2f(), l, t, r, b, w.toFloat(), h.toFloat(), a * 0x01010101, box, null, pipeline)
            .also {
                composite = it
                atlasGeneration = generation
            }
    }

    private fun applyEffect(e: LayerEffect, rt: RenderTarget, w: Int, h: Int): RenderTarget? {
        val offscreen = effectTarget ?: acquire(rt.width, rt.height).also { effectTarget = it }
        if (!offscreen.resolve(rt.width, rt.height)) return null
        val out = offscreen.target ?: return null
        val uniforms = effectUniforms ?: OwnedUniforms().also { effectUniforms = it }
        uniforms.close()
        return out.takeIf { BlazeBackdrop.layer(e, rt, w, h, it, uniforms) }
    }

    private fun renderInto(mc: Minecraft, state: GuiRenderState, renderer: GuiRenderer, rt: RenderTarget, gs: Int) {
        val graphics = GuiGraphicsExtractor(mc, state, bounds.width(), bounds.height())
        GnetumCompat.restoring { stacking(graphics) { for (i in ops.indices) ops[i](graphics) } }
        renderGui(renderer, rt, 0, GuiTargetRedirect.ScissorTransform { l, t, r, b -> intArrayOf(l * gs, rt.height - b * gs, (r - l) * gs, (b - t) * gs) })
    }

    companion object {
        private const val MAX_POOLED = 4
        private val pool = ArrayList<OffscreenTarget>()
        private val active = ArrayList<LayerOp>()
        private var frame = 0L
        private var sweptAt = 0L
        private var sweptFrame = 0L

        init {
            HostEvents.onFrameEnd {
                frame++
                if (active.isNotEmpty()) {
                    val now = System.nanoTime()
                    if (now - sweptAt >= 250_000_000L) sweep(now)
                }
            }
        }

        fun closed(op: (GuiGraphicsExtractor) -> Unit): Boolean = when (op) {
            is BlazeRenderer.MeshOp -> op.mesh.closed
            is ItemGlint.Op -> op.closed
            is LayerOp -> anyIndexed(op.ops) { closed(it) }
            else -> false
        }

        fun textures(op: (GuiGraphicsExtractor) -> Unit, out: MutableSet<GpuTextureView>, text: Boolean = false) {
            when (op) {
                is BlazeRenderer.MeshOp -> op.mesh.texture?.let(out::add)
                is ItemGlint.Op -> out.add(op.atlas)
                is BlazeRenderer.BakedTextOp -> if (text) op.meshes?.forEach { m -> m.texture?.let(out::add) }
                is BlazeRenderer.LiveTextOp -> if (text) op.meshes?.forEach { m -> m.texture?.let(out::add) }
                is LayerOp -> for (i in op.ops.indices) textures(op.ops[i], out, true)
                else -> {}
            }
        }

        fun texturesOf(ops: List<(GuiGraphicsExtractor) -> Unit>): List<GpuTextureView> {
            val out = Collections.newSetFromMap(IdentityHashMap<GpuTextureView, Boolean>())
            for (i in ops.indices) textures(ops[i], out, true)
            return if (out.isEmpty()) emptyList() else ArrayList(out)
        }

        fun samplesAtlas(op: (GuiGraphicsExtractor) -> Unit): Boolean = when (op) {
            is BlazeRenderer.MeshOp -> op.mesh.texture?.let(::atlasView) == true
            is ItemGlint.Op -> true
            is LayerOp -> anyIndexed(op.ops) { samplesAtlas(it) }
            is LiveDraw -> op.samplesAtlas()
            else -> false
        }

        private fun atlasView(view: GpuTextureView): Boolean {
            val targets = McItemAtlas.atlasTargets()
            for (i in targets.indices) if (targets[i].colorTextureView === view) return true
            return false
        }

        fun stale(op: (GuiGraphicsExtractor) -> Unit): Boolean = when (op) {
            is BlazeRenderer.MeshOp, is BlazeRenderer.BakedTextOp -> false
            is BlazeRenderer.LiveTextOp -> op.changed()
            is LayerOp -> op.stale()
            is LiveDraw -> op.changed()
            is OutlineOp -> op.animated
            else -> true
        }

        private fun mayChange(op: (GuiGraphicsExtractor) -> Unit): Boolean = when (op) {
            is BlazeRenderer.MeshOp, is BlazeRenderer.BakedTextOp -> false
            is LayerOp -> op.dynamic
            is OutlineOp -> op.animated
            else -> true
        }

        fun of(
            ops: List<(GuiGraphicsExtractor) -> Unit>, alpha: Float, guiScale: Float, screenW: Int, screenH: Int, effect: LayerEffect? = null,
            mode: Int = PolyRenderer.BLEND_SRC_OVER,
        ): LayerOp? {
            val screen = ScreenRectangle(0, 0, screenW, screenH)
            fun full() = LayerOp(ops, alpha, guiScale, screen, effect, mode)
            var bounds: ScreenRectangle? = null
            fun add(b: ScreenRectangle) { bounds = bounds?.let { rectUnion(it, b) } ?: b }
            for (i in ops.indices) when (val op = ops[i]) {
                is BlazeRenderer.MeshOp -> add(op.mesh.bounds())
                is BlazeRenderer.BakedTextOp -> op.meshes?.forEach { add(it.bounds()) } ?: return full()
                is BlazeRenderer.LiveTextOp -> op.meshes?.forEach { add(it.bounds()) } ?: return full()
                is LayerOp -> add(op.bounds)
                is ItemGlint.Op -> add(op.bounds)
                is LiveDraw -> add(op.box ?: return full())
                is OutlineOp -> add(op.bounds)
                else -> return full()
            }
            if (effect != null) {
                val pad = effect.chain.outset * effect.scale
                val l = floor((effect.x0 - pad) / guiScale).toInt(); val t = floor((effect.y0 - pad) / guiScale).toInt()
                val r = ceil((effect.x1 + pad) / guiScale).toInt(); val b = ceil((effect.y1 + pad) / guiScale).toInt()
                if (r > l && b > t) add(ScreenRectangle(l, t, r - l, b - t))
                val grow = ceil(pad / guiScale).toInt() + 1
                bounds = bounds?.let { ScreenRectangle(it.left() - grow, it.top() - grow, it.width() + grow * 2, it.height() + grow * 2) }
            }
            val b = bounds?.intersection(screen) ?: return null
            if (b.width() <= 0 || b.height() <= 0) return null
            val dx = b.left(); val dy = b.top()
            return LayerOp(ops.map { shiftOp(it, dx, dy) }, alpha, guiScale, b, effect?.local(dx * guiScale, dy * guiScale), mode)
        }

        fun retire(old: List<(GuiGraphicsExtractor) -> Unit>, next: List<(GuiGraphicsExtractor) -> Unit>) {
            if (!anyIndexed(old) { it is LayerOp }) return
            val kept = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
            for (i in next.indices) {
                val op = next[i]
                if (op is LayerOp) kept.add(op)
            }
            for (i in old.indices) {
                val op = old[i]
                if (op is LayerOp && op !in kept) op.release()
            }
        }

        private fun recycle(target: OffscreenTarget) {
            if (pool.size < MAX_POOLED) pool.add(target) else target.dispose()
        }

        private fun acquire(w: Int, h: Int): OffscreenTarget {
            val fit = pool.filter { it.target?.let { t -> t.width >= w && t.height >= h } == true }.minByOrNull { it.target!!.let { t -> t.width * t.height } }
            if (fit != null) return fit.also { pool.remove(it) }
            return pool.removeLastOrNull() ?: OffscreenTarget(depth = false)
        }

        private fun sweep(now: Long) {
            sweptAt = now
            val since = sweptFrame
            sweptFrame = frame
            var idle: ArrayList<LayerOp>? = null
            for (op in active) {
                if (op.usedFrame >= since) op.seenAt = now
                else if (now - op.seenAt > 1_000_000_000L) (idle ?: ArrayList<LayerOp>().also { idle = it }).add(op)
            }
            idle?.forEach(LayerOp::release)
        }

    }
}

internal fun rectUnion(a: ScreenRectangle, b: ScreenRectangle): ScreenRectangle {
    val l = min(a.left(), b.left()); val t = min(a.top(), b.top())
    return ScreenRectangle(l, t, max(a.right(), b.right()) - l, max(a.bottom(), b.bottom()) - t)
}

private inline fun <T> anyIndexed(list: List<T>, predicate: (T) -> Boolean): Boolean {
    for (i in list.indices) if (predicate(list[i])) return true
    return false
}

private fun ScreenRectangle.shifted(dx: Int, dy: Int) = ScreenRectangle(left() - dx, top() - dy, width(), height())

internal interface LiveDraw {
    val box: ScreenRectangle?
    val gameFrame: Boolean
    fun changed(): Boolean
    fun samplesAtlas(): Boolean
    fun shifted(dx: Int, dy: Int): (GuiGraphicsExtractor) -> Unit
}

private class OutlineOp(
    private val outline: McOutlines.Outline, private val pose: Matrix3x2f, private val x0: Float, private val y0: Float,
    private val color: Int, val bounds: ScreenRectangle, private val scissor: ScreenRectangle?,
) : (GuiGraphicsExtractor) -> Unit {
    val animated: Boolean get() = outline.obfuscated
    private var mesh: BlazeRenderer.Mesh? = null
    private var meshView: GpuTextureView? = null
    private var meshW = 0
    private var meshH = 0

    fun shifted(dx: Int, dy: Int) = OutlineOp(
        outline, Matrix3x2f().translation(-dx.toFloat(), -dy.toFloat()).mul(pose), x0, y0, color, bounds.shifted(dx, dy), scissor?.shifted(dx, dy),
    )

    override fun invoke(g: GuiGraphicsExtractor) {
        val rt = outline.prepare() ?: return
        val view = rt.colorTextureView ?: return
        var m = mesh
        if (m == null || view !== meshView || rt.width != meshW || rt.height != meshH) {
            m = compositeMesh(view, rt, pose, x0, y0, x0 + outline.w, y0 + outline.h, outline.w, outline.h, color, bounds, scissor)
            mesh = m
            meshView = view
            meshW = rt.width
            meshH = rt.height
        }
        //~ if >= 26.1 'submitGuiElement' -> 'addGuiElement'
        g.guiRenderState.addGuiElement(m)
    }
}

private fun shiftOp(op: (GuiGraphicsExtractor) -> Unit, dx: Int, dy: Int): (GuiGraphicsExtractor) -> Unit = when (op) {
    is BlazeRenderer.MeshOp -> BlazeRenderer.MeshOp(op.mesh.shifted(dx, dy))
    is BlazeRenderer.BakedTextOp -> op.shifted(dx, dy)
    is BlazeRenderer.LiveTextOp -> op.shifted(dx, dy)
    is LayerOp -> op.moved(dx, dy)
    is LiveDraw -> op.shifted(dx, dy)
    is OutlineOp -> op.shifted(dx, dy)
    is ItemGlint.Op -> op.shifted(dx, dy)
    else -> { g -> shiftedDraw(g, dx, dy, op) }
}

private fun movedDraw(g: GuiGraphicsExtractor, gx: Float, gy: Float, draw: (GuiGraphicsExtractor) -> Unit) {
    g.pose().pushMatrix()
    g.pose().translate(gx, gy)
    try {
        draw(g)
    } finally {
        g.pose().popMatrix()
    }
}

private fun shiftedDraw(g: GuiGraphicsExtractor, dx: Int, dy: Int, draw: (GuiGraphicsExtractor) -> Unit) {
    g.pose().pushMatrix()
    g.pose().translate(-dx.toFloat(), -dy.toFloat())
    try {
        draw(g)
    } finally {
        g.pose().popMatrix()
    }
}

private inline fun stacking(gfx: GuiGraphicsExtractor, draw: () -> Unit) {
    val stacking = gfx.guiRenderState as StackingGuiRenderState
    stacking.`polycompose$setStacking`(true)
    try {
        draw()
    } finally {
        stacking.`polycompose$setStacking`(false)
    }
}

private const val COALESCE_FRAMES = 15
private const val GUI_EPSILON = 1e-4f
private const val SCREEN_COALESCE_FRAMES = 1

interface StackingGuiRenderState {
    fun `polycompose$setStacking`(stacking: Boolean)
}

class AbstractTextureRef(val texture: DynamicTexture) {
    private var linearSetup: TextureSetup? = null
    private var nearestSetup: TextureSetup? = null

    fun setup(nearest: Boolean = false): TextureSetup {
        val view = texture.textureView
        val sampler = sampler(nearest)
        val cached = if (nearest) nearestSetup else linearSetup
        if (cached != null && cached.texure0() === view && cached.sampler0() === sampler) return cached
        val setup = TextureSetup.singleTexture(view, sampler)
        if (nearest) nearestSetup = setup else linearSetup = setup
        return setup
    }
}

private fun sampler(nearest: Boolean) = RenderSystem.getSamplerCache().getClampToEdge(if (nearest) FilterMode.NEAREST else FilterMode.LINEAR)

internal object GlyphAtlas {
    private const val SIZE = PolyGlyphPage.SIZE

    private class Entry(val ref: AbstractTextureRef, var uploaded: Int, var lastUsed: Long)

    private val entries = IdentityHashMap<PolyGlyphPage.Page, Entry>()
    private var uploadedRevision = -1L
    private var frame = 0L
    private var scanAt = 0L

    var generation = 0
        private set

    init {
        HostEvents.onFrameEnd { endFrame() }
    }

    private fun entry(page: PolyGlyphPage.Page): Entry {
        entries[page]?.let { return it }
        uploadedRevision = -1L
        scanAt = min(scanAt, frame + RELEASE_FRAMES + 1)
        val texture = DynamicTexture({ "polycompose_glyphs_${entries.size}" }, NativeImage(SIZE, SIZE, true))
        texture.setPixels(NativeImage(1, 1, false))
        val e = Entry(AbstractTextureRef(texture), 0, frame)
        entries[page] = e
        if (page.retired) FrameProfiler.active.measure(ProfilePhase.Upload) { upload(page, e) }
        return e
    }

    fun page(page: PolyGlyphPage.Page): AbstractTextureRef = entry(page).also { it.lastUsed = frame }.ref

    fun uploadDirty() {
        if (PolyGlyphPage.revision == uploadedRevision) return
        FrameProfiler.active.measure(ProfilePhase.Upload) { uploadPages() }
    }

    private fun uploadPages() {
        uploadedRevision = PolyGlyphPage.revision
        for (page in PolyGlyphPage.pages) upload(page, entry(page))
    }

    private fun upload(page: PolyGlyphPage.Page, e: Entry) {
        if (e.uploaded == page.version) return
        val rows = page.dirtyRows(e.uploaded)
        e.uploaded = page.version
        if (rows.isEmpty()) return
        val h = rows.last - rows.first + 1
        val staging = MemoryUtil.memAlloc(h * SIZE * 4)
        try {
            val at = MemoryUtil.memAddress(staging)
            page.readRows(rows.first, h, MemoryUtil.memIntBuffer(at, h * SIZE))
            RenderCounters.upload(h * SIZE * 4L)
            writeTexture(e.ref.texture, MemoryUtil.memByteBuffer(at, h * SIZE * 4), rows.first, SIZE, h)
        } finally {
            MemoryUtil.memFree(staging)
        }
    }

    private fun endFrame() {
        frame++
        if (frame < scanAt) return
        var next = Long.MAX_VALUE
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val (page, e) = it.next()
            if (!page.retired) e.lastUsed = frame
            if (frame - e.lastUsed > RELEASE_FRAMES) {
                e.ref.texture.close()
                it.remove()
                generation++
            } else next = min(next, e.lastUsed + RELEASE_FRAMES + 1)
        }
        scanAt = next
    }

    private const val RELEASE_FRAMES = 600L
}

private fun writeTexture(texture: DynamicTexture, data: ByteBuffer, y: Int, w: Int, h: Int) {
    //~ if < 26.2 ', 0, 0, 0,' -> ', NativeImage.Format.RGBA, 0, 0, 0,'
    RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture.texture, data, 0, 0, 0, y, w, h)
}

internal object BitmapTextures {
    private val cache = BitmapTextureCache<AbstractTextureRef>(RELEASE_FRAMES)

    fun get(bitmap: PolyBitmap): AbstractTextureRef {
        cache.get(bitmap)?.let { return it.texture }
        return FrameProfiler.active.measure(ProfilePhase.Upload) { adopt(bitmap) ?: upload(bitmap) }
    }

    private fun adopt(bitmap: PolyBitmap): AbstractTextureRef? {
        val ref = cache.adopt(bitmap)?.texture ?: return null
        val px = bitmap.pixels
        val staging = MemoryUtil.memAlloc(px.size)
        try {
            staging.put(px).flip()
            RenderCounters.upload(px.size.toLong())
            writeTexture(ref.texture, staging, 0, bitmap.width, bitmap.height)
        } finally {
            MemoryUtil.memFree(staging)
        }
        return ref
    }

    private fun upload(bitmap: PolyBitmap): AbstractTextureRef {
        val px = bitmap.pixels
        RenderCounters.upload(px.size.toLong())
        val img = NativeImage(bitmap.width, bitmap.height, false)
        MemoryUtil.memByteBuffer(img.pointer, px.size).put(px)
        val ref = AbstractTextureRef(DynamicTexture({ "polycompose_hud_image" }, img))
        cache.add(bitmap, ref)
        return ref
    }

    val decoder = PolyBitmap.Decoder { bytes ->
        runCatching {
            NativeImage.read(bytes).use { img ->
                val w = img.width
                val px = ByteArray(w * img.height * 4)
                for (y in 0 until img.height) for (x in 0 until w) {
                    val c = img.getPixel(x, y)
                    val o = (y * w + x) * 4
                    px[o] = (c shr 16).toByte(); px[o + 1] = (c shr 8).toByte(); px[o + 2] = c.toByte(); px[o + 3] = (c ushr 24).toByte()
                }
                PolyBitmap(w, img.height, px)
            }
        }.getOrNull()
    }

    val unpinned: BitmapTextureCache<AbstractTextureRef>.Pin = cache.pin(emptyList(), false)

    fun pin(bitmaps: Collection<PolyBitmap>, hold: Boolean): BitmapTextureCache<AbstractTextureRef>.Pin = cache.pin(bitmaps, hold)

    @JvmStatic
    fun endFrame() {
        cache.endFrame { it.texture.close() }
    }

    private const val RELEASE_FRAMES = 600L
}
//?}
