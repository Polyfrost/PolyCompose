package org.polyfrost.compose.minecraft.render.gl

import androidx.compose.runtime.snapshots.SnapshotStateObserver
import com.mojang.blaze3d.pipeline.RenderTarget
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.AbstractTexture
import org.lwjgl.opengl.GL11
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.host.HostMessages
import org.polyfrost.compose.host.OverlayRenderer
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.profile.ProfilerHooks
import org.polyfrost.compose.minecraft.render.GpuBackend
import org.polyfrost.compose.minecraft.render.RenderTargetFbo
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measure
import org.polyfrost.compose.profile.within
import org.polyfrost.compose.render.RenderContext
import org.slf4j.LoggerFactory
//? if < 1.21.11
//import org.polyfrost.compose.minecraft.screen.ComposeScreen

//? if >= 26.2 {
import com.mojang.renderpearl.api.GpuFormat
//?}

//? if >= 26.1 {
import com.mojang.renderpearl.api.textures.FilterMode
//?}

//? if >= 1.21.8 {
import com.mojang.renderpearl.api.textures.GpuTextureView
//?}

//? if >= 1.21.5 {
import com.mojang.renderpearl.api.textures.GpuTexture
import net.minecraft.client.renderer.RenderPipelines
//?}

//? if >= 1.21.4 {
import net.minecraft.resources.Identifier
//?}

//? if > 1.8.9 {
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.client.gui.GuiGraphicsExtractor
//?}

//? if = 1.21.5 {
/*import com.mojang.blaze3d.pipeline.BlendFunction
import com.mojang.blaze3d.pipeline.RenderPipeline
import com.mojang.blaze3d.platform.DestFactor
import com.mojang.blaze3d.platform.SourceFactor
*///?}

//? if >= 1.21.4 && < 1.21.8 {
/*import net.minecraft.client.renderer.RenderStateShard
import net.minecraft.client.renderer.RenderType
import net.minecraft.util.TriState
*///?}

//? if = 1.21.4 {
/*import com.mojang.blaze3d.vertex.DefaultVertexFormat
import com.mojang.blaze3d.vertex.VertexFormat
*///?}

//? if < 1.21.5 {
/*import com.mojang.blaze3d.platform.GlStateManager
*///?}

//? if < 1.21.4 {
/*import net.minecraft.server.packs.resources.ResourceManager
*///?}

//? if < 1.21.5 && > 1.8.9 {
/*import net.minecraft.resources.ResourceLocation
*///?}

//? if < 1.21.11
//import net.minecraft.client.gui.components.DebugScreenOverlay

//? if > 1.8.9 && < 1.21.11
//import net.minecraft.client.gui.screens.Screen

//? if >= 1.21.8 && < 1.21.11 {
/*import net.minecraft.client.gui.render.state.GuiRenderState
import net.minecraft.client.renderer.fog.FogRenderer
import org.polyfrost.compose.minecraft.mixin.render.GameRendererAccessor
import org.polyfrost.compose.minecraft.mixin.render.GuiRendererAccessor
*///?}

//? if < 1.21.8
//import org.polyfrost.compose.minecraft.render.withGuiProjection

//? if = 1.8.9 {
/*import net.minecraft.client.render.Window
import net.minecraft.client.render.platform.GLX
*///?}

//? if > 1.8.9 {
typealias GuiDraw = (GuiGraphicsExtractor) -> Unit
//?} else
//typealias GuiDraw = () -> Unit

object GlHost {
    private val LOG = LoggerFactory.getLogger(GlHost::class.java)

    private val client get() = Minecraft.getInstance()

    @JvmStatic
    val isVulkanModInstalled: Boolean
        get() = GpuBackend.isVulkanMod

    @JvmField
    //? if = 1.21.1 {
    /*val vanillaPipeline = isVulkanModInstalled
    *///?} else
    val vanillaPipeline = false

    @JvmField
    //? if >= 1.21.11 {
    val legacyGl = false
    //?} else
    //val legacyGl = vanillaPipeline || !isVulkanModInstalled

    @JvmOverloads
    fun unavailableReason(messages: HostMessages = HostMessages.Default): String? {
        //? if >= 1.21.11 {
        return null
        //?} else {
        /*if (legacyGl) return null
        return messages.vulkanModUnsupported
        *///?}
    }

    private val gl = StoredGLState()

    private var hudTarget: RenderTarget? = null
    private var hudFrame: GlRenderer? = null

    //? if >= 1.21.5 {
    private val HUD_TEXTURE_LOC = Identifier.fromNamespaceAndPath("polycompose", "hud_skia")
    private var hudTextureWrapper: HudGpuTexture? = null

    private class HudGpuTexture : AbstractTexture() {
        fun setGpuTexture(t: GpuTexture?) {
            this.texture = t
        }

        //? if >= 1.21.8 {
        fun setGpuTextureView(v: GpuTextureView?) {
            this.textureView = v
            //? if >= 26.1 {
            this.sampler = RenderSystem.getSamplerCache()
                .getClampToEdge(FilterMode.LINEAR)
            //? }
        }
        //? }

        override fun close() {
            this.texture = null
            //? if >= 1.21.8
            this.textureView = null
        }
    }
    //?} elif > 1.8.9 {
    /*private val HUD_TEXTURE_LOC = ResourceLocation.fromNamespaceAndPath("polycompose", "hud_skia")
    private var hudTextureWrapper: HudGlTexture? = null
    //? if >= 1.21.4 {
    private class HudGlTexture : AbstractTexture() {
        fun setGlTexId(id: Int) { this.id = id }
        override fun close() { this.id = -1 }
    }
    //? } else {
    /*private class HudGlTexture : AbstractTexture() {
        fun setGlTexId(id: Int) { this.id = id }
        override fun load(manager: ResourceManager) {}
        override fun close() { this.id = -1 }
    }
    *///? }
    *///? }

    //? if >= 1.21.4 && < 1.21.8 {
    /*private val premulRenderTypes = HashMap<Identifier, RenderType>()

    private fun premulGuiTextured(loc: Identifier): RenderType = premulRenderTypes.getOrPut(loc) {
        //? if >= 1.21.5 {
        /*RenderType.create(
            "polycompose_gui_textured_premultiplied",
            786432,
            premulGuiPipeline,
            RenderType.CompositeState.builder()
                .setTextureState(RenderStateShard.TextureStateShard(loc, TriState.FALSE, false))
                .createCompositeState(false),
        )
        *///? } else {
        RenderType.create(
            "polycompose_gui_textured_premultiplied",
            DefaultVertexFormat.POSITION_TEX_COLOR,
            VertexFormat.Mode.QUADS,
            786432,
            RenderType.CompositeState.builder()
                .setTextureState(RenderStateShard.TextureStateShard(loc, TriState.FALSE, false))
                .setShaderState(RenderStateShard.POSITION_TEXTURE_COLOR_SHADER)
                .setTransparencyState(premulTransparency)
                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                .createCompositeState(false),
        )
        //? }
    }

    //? if >= 1.21.5 {
    /*private val premulGuiPipeline by lazy {
        RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.GUI_TEXTURED_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("polycompose", "pipeline/gui_textured_premultiplied"))
                .withBlend(
                    BlendFunction(
                        SourceFactor.ONE,
                        DestFactor.ONE_MINUS_SRC_ALPHA,
                        SourceFactor.ONE,
                        DestFactor.ONE_MINUS_SRC_ALPHA,
                    )
                )
                .build()
        )
    }
    *///? } else {
    private val premulTransparency = RenderStateShard.TransparencyStateShard(
        "polycompose_premultiplied_transparency",
        {
            RenderSystem.enableBlend()
            RenderSystem.blendFuncSeparate(
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                GlStateManager.SourceFactor.ONE,
                GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            )
        },
        {
            RenderSystem.disableBlend()
            RenderSystem.defaultBlendFunc()
        },
    )
    //? }
    *///? }

    //? if > 1.8.9 {
    fun blitHud(guiGraphics: GuiGraphicsExtractor) {
        //? if = 1.21.1 {
        /*if (vanillaPipeline) {
            val frame = hudFrame ?: return
            guiGraphics.flush()
            runCatching { frame.drawVanilla(McWindow.viewportWidth(), McWindow.viewportHeight(), topDown = false) }
                .onFailure { HostErrors.warn("GlHost.blitHud() error", it) }
            return
        }
        *///?}
        val rt = hudTarget ?: return
        if (liveStale) repaintHud(rt)
        val w = rt.width
        val h = rt.height
        val guiScale = client.window.guiScale.toFloat()

        //? if >= 1.21.5 {
        val colorTex = rt.getColorTexture() ?: return
        var wrapper = hudTextureWrapper
        if (wrapper == null) {
            wrapper = HudGpuTexture()
            hudTextureWrapper = wrapper
            client.textureManager.register(HUD_TEXTURE_LOC, wrapper)
        }
        wrapper.setGpuTexture(colorTex)
        //? if >= 1.21.8 {
        wrapper.setGpuTextureView(rt.getColorTextureView())
        guiGraphics.pose().pushMatrix()
        try {
            guiGraphics.pose().scale(1f / guiScale, 1f / guiScale)
            guiGraphics.blit(RenderPipelines.GUI_TEXTURED_PREMULTIPLIED_ALPHA, HUD_TEXTURE_LOC, 0, 0, 0f, 0f, w, h, w, h)
        } finally {
            guiGraphics.pose().popMatrix()
        }
        //? } else {
        /*guiGraphics.pose().pushPose()
        try {
            guiGraphics.pose().scale(1f / guiScale, 1f / guiScale, 1f)
            guiGraphics.blit(::premulGuiTextured, HUD_TEXTURE_LOC, 0, 0, 0f, 0f, w, h, w, h)
        } finally {
            guiGraphics.pose().popPose()
        }
        *///? }
        //? } else {
        /*var wrapper = hudTextureWrapper
        if (wrapper == null) {
            wrapper = HudGlTexture()
            hudTextureWrapper = wrapper
            client.textureManager.register(HUD_TEXTURE_LOC, wrapper)
        }
        wrapper.setGlTexId(rt.colorTextureId)
        guiGraphics.pose().pushPose()
        try {
        guiGraphics.pose().scale(1f / guiScale, 1f / guiScale, 1f)
        //? if >= 1.21.4 {
        guiGraphics.blit(::premulGuiTextured, HUD_TEXTURE_LOC, 0, 0, 0f, 0f, w, h, w, h)
        //?} else {
        /*RenderSystem.enableBlend()
        RenderSystem.blendFuncSeparate(
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
            GlStateManager.SourceFactor.ONE,
            GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
        )
        guiGraphics.blit(HUD_TEXTURE_LOC, 0, 0, 0f, 0f, w, h, w, h)
        RenderSystem.disableBlend()
        RenderSystem.defaultBlendFunc()
        *///?}
        } finally {
            guiGraphics.pose().popPose()
        }
        *///? }
    }
    //?} else {
    /*fun blitHud() {
        if (!hudLayersReady) return
        gl.capture()
        try {
            val fbo = gl.drawFramebuffer
            val w = McWindow.viewportWidth()
            val h = McWindow.viewportHeight()
            if (w > 0 && h > 0) GlRenderer.Layer.draw(fbo, w, h, topDown = false, clear = false, stableLayer, volatileLayer)
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.blitHud() error", e)
        } finally {
            gl.restore()
        }
    }
    *///?}

    private var glScreen: ((RenderContext) -> Unit)? = null
    private var glScreenDirty = false
    private var glBlurAlpha = 0f
    private var glBackdrop = false
    private var glBeneath: ((RenderContext) -> Unit)? = null
    private var glUnderlay: GuiDraw? = null
    private var screenFrame: GlRenderer? = null
    private var beneathFrame: GlRenderer? = null
    private var glScreenProfile: ProfileHost? = null

    fun screen(
        dirty: Boolean,
        blurAlpha: Float,
        underlay: GuiDraw?,
        backdrop: Boolean = false,
        beneath: ((RenderContext) -> Unit)? = null,
        paint: (RenderContext) -> Unit,
    ) {
        glScreen = paint
        glScreenProfile = FrameProfiler.active
        glBlurAlpha = blurAlpha
        glBackdrop = backdrop
        glBeneath = beneath
        glUnderlay = underlay
        if (dirty) glScreenDirty = true
        //? if > 1.8.9 && < 1.21.11
        //if (drawingCover) drawCoveredScreen()
    }

    private var drawnBeforeBlit = false

    /** Whether this frame's screen was blurred, so a blurred copy from an earlier frame is never shown */
    var backdropFresh = false
        private set

    /**
     * Whether vanilla GUI can be drawn between the blur and the OneConfig screen, which the fallback below
     * can't do since it draws after the blit
     */
    var drawsGuiOverScreen = true
        private set

    //? if < 1.21.11 {
    /*private var drawingGui = false

    //~ if = 1.8.9 'DebugScreenOverlay' -> 'DebugOverlay'
    private var deferredDebugScreen: DebugScreenOverlay? = null

    /** Holds back the F3 overlay while a OneConfig screen will be drawn over it, to draw it on top instead */
    //~ if = 1.8.9 'DebugScreenOverlay' -> 'DebugOverlay'
    fun deferDebugScreen(overlay: DebugScreenOverlay): Boolean {
        if (drawingGui || !drawsGuiOverScreen || McScreens.current() !is ComposeScreen) return false
        deferredDebugScreen = overlay
        return true
    }

    var drawingCover = false
        private set

    var coverPainted = false
        private set

    //? if > 1.8.9 {
    var coverScreen: Screen? = null

    private var deferredCover: Screen? = null
    private var coverMouseX = 0
    private var coverMouseY = 0
    private var coverDelta = 0f
    private var coverFbo = 0
    private var guiFlush: (() -> Unit)? = null

    fun deferCover(screen: Screen, mouseX: Int, mouseY: Int, delta: Float): Boolean {
        if (drawingGui || !drawsGuiOverScreen || screen !== coverScreen || McScreens.current() !== screen) return false
        deferredCover = screen
        coverMouseX = mouseX
        coverMouseY = mouseY
        coverDelta = delta
        return true
    }

    private fun drawCover(fbo: Int) {
        val screen = deferredCover ?: return
        deferredCover = null
        if (!drawsGuiOverScreen) return
        coverFbo = fbo
        drawingCover = true
        try {
            //~ if >= 1.21.10 'renderWithTooltip' -> 'renderWithTooltipAndSubtitles'
            drawGui { screen.renderWithTooltipAndSubtitles(it, coverMouseX, coverMouseY, coverDelta) }
        } finally {
            drawingCover = false
        }
    }

    private fun drawCoveredScreen() {
        val paint = glScreen ?: return
        val profile = glScreenProfile
        glScreen = null
        glScreenProfile = null
        guiFlush?.invoke()
        coverPainted = true
        isolated { profile.within { drawScreen(coverFbo, paint) } }
    }
    //?}
    *///?}

    // VulkanMod also draws here: before 0.5.3 it submits the frame right before Window.updateDisplay
    fun drawBeforeBlit() {
        if (!legacyGl) return
        drawnBeforeBlit = true
        presentHookSeen = true
        //? if = 1.8.9 {
        /*draw(mainDrawFbo())
        *///?} elif < 1.21.11 {
        /*draw(RenderTargetFbo.getFboId(client.mainRenderTarget))
        *///?}
    }

    //? if = 1.8.9 {
    /*fun mainDrawFbo(): Int = if (GLX.useFbo()) client.mainRenderTarget.frameBufferId else 0

    fun flushLegacyBatches() {
        GlStateManager.pushMatrix()
        GlStateManager.popMatrix()
    }
    *///?}

    fun drawAtPresent() {
        if (drawnBeforeBlit) {
            drawnBeforeBlit = false
            return
        }
        if (legacyGl && !presentHookSeen && !presentFallbackReported && ++presentFallbacks >= PRESENT_FALLBACK_FRAMES) {
            presentFallbackReported = true
            LOG.warn("GlHost: the pre-blit hook never ran; drawing PolyCompose UI at buffer swap, so it will be missing from screenshots and recordings")
        }
        draw(0)
    }

    private var presentHookSeen = false
    private var presentFallbacks = 0
    private var presentFallbackReported = false

    private fun draw(fbo: Int) {
        if (!legacyGl) return
        drawsGuiOverScreen = drawnBeforeBlit
        //? if > 1.8.9 && < 1.21.11 {
        /*drawCover(fbo)
        val paint = glScreen
        val covered = coverPainted
        coverPainted = false
        if (paint == null && !covered) {
        *///?} else {
        val paint = glScreen
        if (paint == null) {
        //?}
            screenFrame = null
            beneathFrame = null
            backdropFresh = false
            GlEffects.takeGameFrameDemand()
            if (++idleFrames == EFFECT_IDLE_RELEASE_FRAMES) {
                GlBlur.release()
                GlGlass.release()
            }
        } else {
            idleFrames = 0
        }
        overlayFrame++
        collectOverlays()
        val sweep = overlayFrame % OVERLAY_SWEEP_FRAMES == 0L && overlayPasses.isNotEmpty()
        if (paint != null || shownOverlays.isNotEmpty() || sweep) isolated {
            if (paint != null) {
                val profile = glScreenProfile
                glScreen = null
                glScreenProfile = null
                profile.within { drawScreen(fbo, paint) }
            }
            for (i in shownOverlays.indices) drawOverlay(fbo, shownOverlays[i], shownPasses[i])
            if (sweep) sweepOverlays()
        }
        shownOverlays.clear()
        shownPasses.clear()
        //? if < 1.21.11 {
        /*deferredDebugScreen?.let { overlay ->
            deferredDebugScreen = null
            //~ if = 1.8.9 'overlay.render(it)' -> 'overlay.render(Window(client))'
            drawGui { overlay.render(it) }
        }
        *///?}
    }

    //? if < 1.21.11 {
    /*private fun drawGui(draw: GuiDraw) {
        val wasDrawingGui = drawingGui
        drawingGui = true
        //? if > 1.8.9
        val previousFlush = guiFlush
        try {
            //? if >= 1.21.8 {
            val gameRenderer = client.gameRenderer as GameRendererAccessor
            val renderer = gameRenderer.`polycompose$getGuiRenderer`()
            val state = GuiRenderState()
            val render = {
                val accessor = renderer as GuiRendererAccessor
                val previous = accessor.`polycompose$getRenderState`()
                accessor.`polycompose$setRenderState`(state)
                try {
                    renderer.render(gameRenderer.`polycompose$getFogRenderer`().getBuffer(FogRenderer.FogMode.NONE))
                } finally {
                    accessor.`polycompose$setRenderState`(previous)
                }
            }
            guiFlush = {
                render()
                state.reset()
            }
            draw(GuiGraphicsExtractor(client, state))
            render()
            //?} else {
            /*withGuiProjection(McWindow.guiWidth(), McWindow.guiHeight()) {
                // depth left by the GUI pass would hide this behind HUD elements
                //? if = 1.21.5 {
                client.mainRenderTarget.depthTexture?.let { RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(it, 1.0) }
                //?} else {
                /*client.mainRenderTarget.bindWrite(true)
                //? if >= 1.21.4 {
                GlStateManager._clear(GL11.GL_DEPTH_BUFFER_BIT)
                //?} elif > 1.8.9 {
                /*GlStateManager._clear(GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX)
                *///?} else
                //GlStateManager.clear(GL11.GL_DEPTH_BUFFER_BIT)
                *///?}
                //? if > 1.8.9 {
                val graphics = GuiGraphicsExtractor(client, client.renderBuffers().bufferSource())
                guiFlush = graphics::flush
                draw(graphics)
                graphics.flush()
                //?} else
                //draw()
                //? if < 1.21.5
                //client.mainRenderTarget.unbindWrite()
            }
            *///?}
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.drawGui() error", e)
        } finally {
            drawingGui = wasDrawingGui
            //? if > 1.8.9
            guiFlush = previousFlush
        }
    }
    *///?}

    private class OverlayPass {
        var renderer: GlRenderer? = null
        var vbo: GlProgram.Vbo? = null
        var shown = false
        var seenAt = 0L
        var drawnAt = 0L

        fun release() {
            renderer = null
            vbo?.let(GlProgram::release)
            vbo = null
        }
    }

    private val overlayPasses = HashMap<OverlayRenderer, OverlayPass>()
    private val shownOverlays = ArrayList<OverlayRenderer>()
    private val shownPasses = ArrayList<OverlayPass>()
    private var overlayFrame = 0L
    private var idleFrames = 0

    private fun collectOverlays() {
        val overlays = ComposeHost.overlays
        if (overlays.isEmpty()) return
        val hidden = guiHidden()
        var i = 0
        while (i < overlays.size) {
            val overlay = overlays[i++]
            val pass = overlayPasses.getOrPut(overlay, ::OverlayPass)
            pass.seenAt = overlayFrame
            if (HostCallbacks.isDisabled(overlay)) continue
            val show = HostCallbacks.call(overlay, "OverlayRenderer.visible", false) {
                overlay.visible && (!hidden || overlay.showWhenGuiHidden)
            }
            if (!show) {
                pass.shown = false
                continue
            }
            shownOverlays.add(overlay)
            shownPasses.add(pass)
        }
    }

    private fun drawOverlay(fbo: Int, overlay: OverlayRenderer, pass: OverlayPass) {
        val w = McWindow.viewportWidth()
        val h = McWindow.viewportHeight()
        val gw = McWindow.guiWidth()
        if (w <= 0 || h <= 0 || gw <= 0) return
        val first = !pass.shown
        pass.shown = false
        val changed = HostCallbacks.call<Boolean?>(overlay, "OverlayRenderer.prepare", null) {
            overlay.prepare(gw.toFloat(), McWindow.guiHeight().toFloat())
        } ?: return
        val profile = if (FrameProfiler.enabled) ProfilerHooks.overlayHost(overlay) else null
        val previous = pass.renderer
        pass.renderer = null
        val drawn = profile.within {
            HostCallbacks.call<Unit?>(overlay, "OverlayRenderer.draw", null) {
                val cached = previous?.takeUnless { changed || first || !it.matches() || it.stale() }
                val vbo = pass.vbo ?: GlProgram.retain().also { pass.vbo = it }
                pass.renderer = paintGl(fbo, cached, previous, vbo = vbo, draw = overlay::draw)
            }
        }
        if (drawn == null) return
        pass.shown = true
        pass.drawnAt = overlayFrame
    }

    private fun sweepOverlays() {
        val it = overlayPasses.entries.iterator()
        while (it.hasNext()) {
            val pass = it.next().value
            val removed = pass.seenAt != overlayFrame
            if (removed || overlayFrame - pass.drawnAt > OVERLAY_RELEASE_FRAMES) pass.release()
            if (removed) it.remove()
        }
    }

    private fun drawScreen(fbo: Int, paint: (RenderContext) -> Unit) {
        glBeneath?.let { beneath -> beneathFrame = drawGl(fbo, null, beneathFrame, draw = beneath) }
        val demand = GlEffects.takeGameFrameDemand()
        val wanted = glBlurAlpha > 0f || glBackdrop || demand || screenFrame?.usesGameFrame == true
        val fresh = wanted && !vanillaPipeline && drawBlur(fbo, glBlurAlpha)
        if (fresh != backdropFresh) glScreenDirty = true
        backdropFresh = fresh
        //? if < 1.21.11
        //if (drawsGuiOverScreen) glUnderlay?.let { gl.restored { drawGui(it) } }
        val reuse = screenFrame?.takeUnless { glScreenDirty }
        glScreenDirty = false
        screenFrame = drawGl(fbo, reuse, screenFrame, true, paint)
    }

    private inline fun isolated(block: () -> Unit) {
        if (vanillaPipeline) return block()
        gl.capture()
        try {
            block()
        } finally {
            gl.restore()
        }
    }

    private fun drawBlur(fbo: Int, alpha: Float): Boolean {
        val w = McWindow.viewportWidth()
        val h = McWindow.viewportHeight()
        if (w <= 0 || h <= 0) return false
        gl.capture()
        try {
            GlBlur.draw(fbo, w, h, alpha, client.isPaused)
            return true
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.drawBlur() error", e)
            return false
        } finally {
            gl.restore()
        }
    }

    private fun drawGl(fbo: Int, cached: GlRenderer?, spare: GlRenderer? = null, kept: Boolean = false, draw: (RenderContext) -> Unit): GlRenderer? =
        try {
            paintGl(fbo, cached, spare, kept, null, draw)
        } catch (e: VirtualMachineError) {
            throw e
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.drawGl() error", e)
            null
        }

    private fun paintGl(
        fbo: Int, cached: GlRenderer?, spare: GlRenderer?, kept: Boolean = false, vbo: GlProgram.Vbo? = null, draw: (RenderContext) -> Unit,
    ): GlRenderer? {
        val w = McWindow.viewportWidth()
        val h = McWindow.viewportHeight()
        if (w <= 0 || h <= 0 || McWindow.guiWidth() <= 0) return null
        //? if = 1.21.1 {
        /*if (vanillaPipeline) {
            val renderer = cached ?: reuse(spare).also { record(it, draw) }
            client.mainRenderTarget.bindWrite(true)
            renderer.drawVanilla(w, h, topDown = false)
            return renderer
        }
        *///?}
        gl.capture()
        try {
            val renderer = cached ?: reuse(spare).also { record(it, draw) }
            renderer.draw(fbo, w, h, topDown = false, clear = false, kept = kept, vbo = vbo)
            return renderer
        } finally {
            gl.restore()
        }
    }

    private fun record(renderer: GlRenderer, draw: (RenderContext) -> Unit) {
        FrameProfiler.active.measure(ProfilePhase.Record) { draw(RenderContext(renderer)) }
    }

    private var oversizeReported = false

    private fun maxTextureSize(): Int =
        //? if >= 26.2 {
        RenderSystem.getDevice().deviceInfo.limits().maxTextureSize()
        //? } else if >= 1.21.5 {
        /*RenderSystem.getDevice().maxTextureSize
        *///? } else if > 1.8.9 {
        /*RenderSystem.maxSupportedTextureSize()
        *///? } else
        //Minecraft.getMaxTextureSize()

    private var maxTextureSizeCache = 0

    private fun cachedMaxTextureSize(): Int {
        if (maxTextureSizeCache == 0) {
            maxTextureSizeCache = runCatching { maxTextureSize() }.getOrNull()
                ?.takeIf { it > 0 }
                ?: Int.MAX_VALUE
        }
        return maxTextureSizeCache
    }

    private fun viewportFitsTexture(w: Int, h: Int): Boolean {
        val max = cachedMaxTextureSize()
        if (w <= max && h <= max) {
            oversizeReported = false
            return true
        }
        if (!oversizeReported) {
            oversizeReported = true
            LOG.warn("GlHost: viewport {}x{} is past the max texture size ({}); skipping the HUD target", w, h, max)
            destroyHudTarget()
        }
        return false
    }

    private var hudStable: GlRenderer? = null
    private var hudVolatile: GlRenderer? = null
    private val stableLayer = GlRenderer.Layer(coalesce = true)
    private val volatileLayer = GlRenderer.Layer()
    //? if = 1.8.9
    //private var hudLayersReady = false

    private fun reuse(renderer: GlRenderer?): GlRenderer {
        if (renderer == null || !renderer.matches()) return GlRenderer()
        renderer.reset()
        return renderer
    }

    // live canvases only run while the HUD target is painted, so repaint it when state they read changes
    private val liveReads = SnapshotStateObserver { it() }.also { it.start() }
    @Volatile private var liveStale = false
    private val onLiveStateChanged: (Any) -> Unit = { liveStale = true }

    private fun paintHud(fbo: Int, w: Int, h: Int) {
        liveStale = false
        liveReads.observeReads(this, onLiveStateChanged) {
            GlRenderer.Layer.draw(fbo, w, h, topDown = true, clear = true, stableLayer, volatileLayer)
        }
    }

    fun drawHudGl(draw: (RenderContext, RenderContext?) -> Unit): Boolean {
        //? if = 1.21.1 {
        /*if (vanillaPipeline) {
            hudFrame = runCatching { GlRenderer().also { draw(RenderContext(it), null) } }
                .onFailure { HostErrors.warn("GlHost.drawHudGl() error", it) }
                .getOrNull()
            return hudFrame != null
        }
        *///?}
        //? if = 1.8.9 {
        /*gl.capture()
        try {
            val stable = reuse(hudStable).also { hudStable = it }
            val volatile = reuse(hudVolatile).also { hudVolatile = it }
            draw(RenderContext(stable), RenderContext(volatile))
            stableLayer.take(stable)
            volatileLayer.take(volatile)
            hudLayersReady = true
            return true
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.drawHudGl() error", e)
            return false
        } finally {
            gl.restore()
        }
        *///?} else {
        val rt = resolveHudTarget() ?: return true
        val fbo = RenderTargetFbo.getFboId(rt)
        if (fbo <= 0) return true
        gl.capture()
        try {
            val stable = reuse(hudStable).also { hudStable = it }
            val volatile = reuse(hudVolatile).also { hudVolatile = it }
            draw(RenderContext(stable), RenderContext(volatile))
            stableLayer.take(stable)
            volatileLayer.take(volatile)
            paintHud(fbo, rt.width, rt.height)
            return true
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.drawHudGl() error", e)
            return false
        } finally {
            gl.restore()
        }
        //?}
    }

    private fun repaintHud(rt: RenderTarget) {
        val fbo = RenderTargetFbo.getFboId(rt)
        if (fbo <= 0) return
        gl.capture()
        try {
            paintHud(fbo, rt.width, rt.height)
        } catch (e: Throwable) {
            HostErrors.warn("GlHost.repaintHud() error", e)
        } finally {
            gl.restore()
        }
    }

    private fun resolveHudTarget(): RenderTarget? {
        val w = McWindow.viewportWidth()
        val h = McWindow.viewportHeight()
        if (w <= 0 || h <= 0) return null
        if (!viewportFitsTexture(w, h)) return null

        var rt = hudTarget
        val needNewTarget = rt == null || rt.width != w || rt.height != h
        if (needNewTarget) {
            if (System.currentTimeMillis() - allocFailedAt < ALLOC_RETRY_COOLDOWN_MS) return null
            destroyHudTarget()
            //? if < 1.21.5
            //val bound = RenderTargetFbo.saveBoundTarget()
            rt = try {
                //? if >= 26.3 {
                TextureTarget(
                    null, w, h,
                    GpuFormat.RGBA8_UNORM,
                    GpuFormat.D32_FLOAT,
                )
                //? } else if >= 26.2 {
                /*TextureTarget(null, w, h, true, GpuFormat.RGBA8_UNORM)
                *///? } else if >= 1.21.5 {
                /*TextureTarget(null, w, h, true)
                *///? } else if >= 1.21.4 {
                /*TextureTarget(w, h, true)
                *///? } elif > 1.8.9 {
                /*TextureTarget(w, h, true, Minecraft.ON_OSX)
                *///? } else
                //RenderTarget(w, h, false)
            } catch (e: Throwable) {
                onAllocFailure(HUD_TARGET, w, h, e)
                return null
            }
            hudTarget = rt
            //? if < 1.21.5
            //RenderTargetFbo.restoreBoundTarget(bound)

            //? if >= 1.21.5 {
            val fboId = RenderTargetFbo.getFboId(rt)
            if (fboId <= 0) {
                LOG.warn("GlHost: hud TextureTarget FBO not ready (id={}), retry next frame", fboId)
                hudTarget = null
                rt.destroyBuffers()
                return null
            }
            //? }
        }
        return rt
    }

    private fun destroyHudTarget() {
        hudTarget?.let { target ->
            //? if < 1.21.5
            //val bound = RenderTargetFbo.saveBoundTarget()
            target.destroyBuffers()
            //? if < 1.21.5
            //RenderTargetFbo.restoreBoundTarget(bound)
        }
        hudTarget = null
    }

    private var allocFailedAt = 0L
    private var allocReported = false

    private const val ALLOC_RETRY_COOLDOWN_MS = 2000L
    private const val PRESENT_FALLBACK_FRAMES = 120
    private const val OVERLAY_SWEEP_FRAMES = 64L
    private const val OVERLAY_RELEASE_FRAMES = 600L
    private const val EFFECT_IDLE_RELEASE_FRAMES = 60
    private const val HUD_TARGET = "hud"


    private fun onAllocFailure(what: String, w: Int, h: Int, error: Throwable) {
        allocFailedAt = System.currentTimeMillis()
        destroyHudTarget()
        LOG.error("GlHost: failed to allocate the {}x{} {} target; skipping offscreen frames", w, h, what, error)
        if (!allocReported) {
            allocReported = true
            runCatching {
                McScreens.showMessage(HostMessages.Default.gpuAllocFailed(w, h))
            }
        }
    }

    fun recreateSurface(width: Int, height: Int) {
        if (width > 0 && height > 0) {
            destroyHudTarget()
            hudFrame = null
        }
    }
}

internal fun guiHidden(): Boolean =
    //? if >= 26.2 {
    Minecraft.getInstance().gui.hud.isHidden
    //?} else
    //Minecraft.getInstance().options.hideGui
