package org.polyfrost.compose.minecraft.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.scene.ComposeScene
import com.mojang.blaze3d.platform.InputConstants
//? if >= 26.3
import org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode
import java.awt.Component
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.util.Collections
import java.util.function.Consumer
import kotlin.math.abs
import kotlin.math.round
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.backend.PolyCanvas
import org.polyfrost.compose.host.BackStack
import org.polyfrost.compose.effect.PolyGlassLight
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.ComposeOptions
import org.polyfrost.compose.host.FocusRingStyle
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.host.KeyState
import org.polyfrost.compose.host.LocalUiOversample
import org.polyfrost.compose.host.LocalComposeOptions
import org.polyfrost.compose.host.SceneKind
import org.polyfrost.compose.host.InputKind
import org.polyfrost.compose.host.controller.ControllerButton
import org.polyfrost.compose.host.controller.ControllerGuide
import org.polyfrost.compose.host.controller.ControllerHint
import org.polyfrost.compose.host.controller.ControllerTargets
import org.polyfrost.compose.host.controller.Controllers
import org.polyfrost.compose.host.controller.LocalControllerTargets
import org.polyfrost.compose.minecraft.compat.controlify.ControllerBridge
import org.polyfrost.compose.minecraft.compat.controlify.FieldPreview
import org.polyfrost.compose.minecraft.compat.controlify.FocusMark
import org.polyfrost.compose.minecraft.compat.controlify.FocusedNode
import org.polyfrost.compose.minecraft.compat.controlify.KeyboardPreview
import org.polyfrost.compose.minecraft.compat.controlify.SceneFocus
import org.polyfrost.compose.minecraft.compat.controlify.SceneSemantics
import org.polyfrost.compose.minecraft.compat.controlify.SpatialNavigation
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.HostUriHandler
import org.polyfrost.compose.minecraft.platform.SystemClipboard
import org.polyfrost.compose.minecraft.platform.SystemClipboardManager
import org.polyfrost.compose.minecraft.platform.McKeys
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.platform.OperatingSystem
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.minecraft.profile.ProfilerControl
import org.polyfrost.compose.minecraft.profile.ProfilerHooks
import org.polyfrost.compose.minecraft.profile.ProfilerKeybind
import org.polyfrost.compose.profile.FrameProfiler
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.render.RenderContext
import org.polyfrost.compose.state.LocalTickActivity
import org.polyfrost.compose.state.TickActivity

//? if >= 1.21.10 {
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent as McKeyEvent
import net.minecraft.client.input.MouseButtonEvent
//?}

//? if sdl {
import org.lwjgl.sdl.SDLVideo.*
//?} else {
/*import org.lwjgl.glfw.GLFW
*///?}

//? if > 1.8.9 {
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.CommonComponents
//?}

//? if >= 1.21.11 {
import org.polyfrost.compose.minecraft.render.blaze.Recording
import org.polyfrost.compose.minecraft.render.blaze.UiBlur
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measure
//?} else {
/*import org.polyfrost.compose.minecraft.render.gl.GlHost
import org.polyfrost.compose.profile.within
*///?}

//? if = 1.8.9 {
/*import com.mojang.blaze3d.platform.GlStateManager
import net.minecraft.client.gui.screens.TitleScreen
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.sdl.SDLKeyboard.SDL_GetKeyFromScancode
import org.polyfrost.compose.minecraft.legacy.KeyCodes
import org.polyfrost.compose.minecraft.legacy.LegacyInputDrain
import org.polyfrost.compose.minecraft.platform.GameKeyRouter
import org.polyfrost.compose.minecraft.legacy.LegacyPanoramaTracker
import org.polyfrost.compose.minecraft.render.gl.StoredGLState
*///?}

private val LOGGER = LogManager.getLogger("PolyCompose/Compose")

@OptIn(InternalComposeUiApi::class)
abstract class ComposeScreen(
    protected val renderMode: RenderMode = RenderMode.ON_DEMAND,
    protected val options: ComposeOptions = ComposeOptions.Default,
//~ if = 1.8.9 '(CommonComponents.EMPTY)' -> '()'
) : Screen(CommonComponents.EMPTY) {
    //? if = 1.8.9 {
    /*private var panorama: TitleScreen? = null
    private val panoramaGlState = StoredGLState()
    *///?}

    enum class RenderMode {
        CONTINUOUS,

        ON_DEMAND,
    }

    @Composable
    abstract fun compose()

    @Composable
    protected open fun decorate(content: @Composable () -> Unit) {
        options.Decorate(this, content)
    }

    private var sceneHandle: SceneHandle? = null

    private val frames = SceneFrames { sceneDirty = true }

    private val uiOversample = mutableFloatStateOf(1f)

    private val tickActivity = TickActivity { showing }

    private var sceneContextOrNull: SceneContext? = null

    private var showing = false

    private var scenePoisoned = false

    private var sceneRebuilds = 0

    private var sceneBusy = false

    private var contentSet = false

    private var controllerFocus: FocusManager? = null

    private var controllerSemantics: SceneSemantics? = null

    private fun liveScene(): ComposeScene? {
        if (scenePoisoned || sceneBusy) return null
        return sceneHandle?.scene
    }

    private inline fun <T> withScene(block: (ComposeScene) -> T): T? {
        val scene = liveScene() ?: return null
        sceneBusy = true
        return try {
            val result = block(scene)
            sceneHandle?.rethrow()
            result
        } catch (t: Throwable) {
            poisonScene(t)
            null
        } finally {
            sceneBusy = false
        }
    }

    private inline fun sendKeyEventSafely(build: () -> ComposeKeyEvent): Boolean {
        val event = try {
            build()
        } catch (t: Throwable) {
            ComposeSupport.recordSceneFailure(t)
            reportUnavailableAndClose()
            return false
        }
        return withScene { it.sendKeyEvent(event) } ?: false
    }

    private fun poisonScene(cause: Throwable) {
        if (scenePoisoned) return
        scenePoisoned = true
        sceneDirty = true
        HostErrors.error(
            "Compose scene for ${this::class.java.simpleName} failed and has been discarded; " +
                "it will be rebuilt on the next frame.",
            cause,
            LOGGER,
        )
    }

    protected open fun onSceneRebuilding() {}

    private fun ensureScene(): ComposeScene? {
        if (scenePoisoned) closeSceneQuietly()
        sceneHandle?.let { return it.scene }
        if (!ComposeSupport.isAvailable) return null
        val created = try {
            createScene()
        } catch (t: Throwable) {
            ComposeSupport.recordSceneFailure(t)
            return null
        }
        sceneHandle = created
        scenePoisoned = false
        contentSet = false
        return created.scene
    }

    private fun closeSceneQuietly() {
        val handle = sceneHandle ?: return
        val sceneContext = sceneContextOrNull
        sceneHandle = null
        sceneContextOrNull = null
        controllerFocus = null
        controllerSemantics = null
        contentSet = false
        scenePoisoned = false
        sceneContext?.close()
        handle.close()
    }

    private fun createScene(): SceneHandle {
        val sceneContext = SceneContext({ showing }, ::scaledDensity)
        val semantics = if (ControllerBridge.tracksSemantics) SceneSemantics() else null
        val handle = SceneHandle.create(semantics?.wrap(sceneContext) ?: sceneContext, options, frames.onInvalidation) { sceneDirty = true }
        sceneContextOrNull = sceneContext
        controllerSemantics = semantics
        HostCallbacks.call(options, "ComposeOptions.onSceneCreated", Unit) { options.onSceneCreated(this, SceneKind.Screen) }
        return handle
    }

    @Volatile
    private var sceneDirty = true

    private var controllerFocusVisible = false

    protected open val drawsControllerFocusRing: Boolean get() = true

    open val controllerGuide: ControllerGuide get() = ControllerGuide.BottomLeft

    private var awaitingFirstFrame = false

    private var lastPointer = Offset.Zero
    private var hasLastPointer = false
    private var pointerInside = false
    private var lastSceneW = -1
    private var lastSceneH = -1
    private var lastFbWidth = -1
    private var lastFbHeight = -1
    private var settleFrames = 0
    private var cachedSurfaceScale = -1f

    protected val client get() = Minecraft.getInstance()
    override fun init() {
        val hadScene = sceneHandle != null
        val scene = ensureScene()
        if (scene == null) {
            reportUnavailableAndClose()
            return
        }
        //? if sdl
        if (OperatingSystem.isMac) MacScrollEvent.watchWheel()
        //? if = 1.8.9
        //Keyboard.enableRepeatEvents(true)

        //? if = 1.8.9 {
        /*panorama = McScreens.current() as? TitleScreen ?: LegacyPanoramaTracker.current()
            ?: if (drawsPanorama() && client.level == null) createPanorama() else null
        panorama?.width = width
        panorama?.height = height
        *///?}

        if (!hadScene) sceneDirty = true
        awaitingFirstFrame = true
        hasLastPointer = false
        pointerInside = false

        syncSceneMetrics()
        lastSceneW = -1
        lastSceneH = -1
        lastFbWidth = -1
        lastFbHeight = -1
        cachedSurfaceScale = -1f
        uiOversample.floatValue = surfaceScale()

        if (!bindContent()) {
            closeWithMessage(options.messages.failedToStart)
            return
        }

        if (!showing) {
            tapClean = false
            heldAtOpen.clear()
            heldAtOpen.addAll(heldKeysNow())
            heldButtons.clear()
            mouseHeldAtOpen = mouseButtonsDownNow()
        }
        showing = true
        sceneContextOrNull?.sync()
    }

    private fun bindContent(): Boolean {
        if (contentSet) return true
        withScene { setContentOn(it) } ?: return false
        contentSet = true
        return true
    }

    private fun setContentOn(scene: ComposeScene) {
        scene.setContent {
            @Suppress("DEPRECATION")
            CompositionLocalProvider(
                LocalClipboardManager provides SystemClipboardManager,
                LocalClipboard provides SystemClipboard,
                LocalUriHandler provides HostUriHandler,
                LocalUiOversample provides uiOversample.floatValue,
                *wheel.provided(),
                LocalComposeOptions provides options,
                LocalTickActivity provides tickActivity,
                LocalControllerTargets provides actionTargets,
            ) {
                val focusManager = LocalFocusManager.current
                SideEffect { controllerFocus = focusManager }
                decorate { compose() }
            }
        }
    }

    private fun reportUnavailableAndClose() {
        val reason = ComposeSupport.unavailableReason(options.messages) ?: options.messages.failedToStart
        LOGGER.error("Refusing to open ${this::class.java.simpleName}: {}", reason)
        closeWithMessage(reason)
    }

    private fun closeWithMessage(reason: String) {
        RenderThread.execute {
            if (McScreens.current() === this) McScreens.close()
            McScreens.showMessage(reason)
        }
    }

    override fun resize(
        //? if < 1.21.11
        //minecraft: Minecraft,
        width: Int, height: Int
    ) {
        this.width = width
        this.height = height
        //? if = 1.8.9 {
        /*panorama?.width = width
        panorama?.height = height
        *///?}
        syncSceneMetrics()
    }

    //? if = 1.8.9 {
    /*private fun createPanorama(): TitleScreen? = runCatching {
        TitleScreen().also {
            it.init(client, width, height)
            LegacyPanoramaTracker.capture(it)
        }
    }.getOrNull()

    override fun tick() {
        panorama?.tick()
    }
    *///?}

    override fun isPauseScreen(): Boolean = false

    //? if > 1.8.9 {
    //~ if >= 26.1 'renderBackground' -> 'extractBackground'
    override fun extractBackground(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
        //? if < 1.21.11
        //if (GlHost.coverPainted) return
        if (client.level == null && drawsPanorama()) {
            //~ if >= 26.1 'renderPanorama' -> 'extractPanorama'
            extractPanorama(ctx, tickDelta)
        }
        //? if >= 1.21.11 {
        UiBlur.beginFrame()
        if (extractBeneathBlur(ctx)) ctx.nextStratum()
        val blur = blurAlpha()
        UiBlur.extract(ctx, blur)
        if (blur <= 0f && (wantsBackdrop() || recording.usesGameFrame)) UiBlur.request(ctx)
        underlay?.invoke(ctx)
        //?}
    }
    //?}

    protected open fun blurAlpha(): Float = 0f

    protected open fun drawsPanorama(): Boolean = true

    protected open fun densityScale(): Float = 1f

    private fun scaledDensity(): Float {
        val scale = densityScale()
        return sceneDensity() * if (scale > 0f && scale.isFinite()) scale else 1f
    }

    protected open fun wantsBackdrop(): Boolean = false

    //? if > 1.8.9 {
    protected open val underlay: ((GuiGraphicsExtractor) -> Unit)? get() = null
    //?} else
    //protected open val underlay: (() -> Unit)? get() = null

    //? if >= 1.21.11 {
    protected open fun extractBeneathBlur(ctx: GuiGraphicsExtractor): Boolean = false

    private var paintedWithBackdrop = false
    //?} else {
    /*protected open val beneathBlur: ((RenderContext) -> Unit)? get() = null
    *///?}

    protected open val retainsScene: Boolean get() = false

    private fun disposeScene() {
        sceneDirty = true
        closeSceneQuietly()
    }

    private fun releaseScene() {
        if (!retainsScene || sceneHandle == null || scenePoisoned) {
            disposeScene()
            return
        }
        sceneDirty = true
    }

    fun disposeRetainedScene() {
        RenderThread.execute { if (!showing) disposeScene() }
    }

    //? if > 1.8.9 {
    override fun onClose() {
        ComposeSceneContextImpl.resetPointerIcon()
        if (McScreens.current() === this) super.onClose() else releaseScene()
    }
    //?}

    private var pendingCover: Screen? = null

    private var coveredBy: Screen? = null

    private var coverWatch: AutoCloseable? = null

    private var controllerPressGuard = false

    internal fun showBehind(overlay: Screen) {
        if (!showing || McScreens.current() !== this) return
        pendingCover = overlay
        controllerPressGuard = true
        try {
            McScreens.display(overlay)
        } finally {
            pendingCover = null
        }
        if (McScreens.current() === overlay) coverWith(overlay) else controllerPressGuard = false
    }

    internal fun swallowControllerPress(held: Boolean): Boolean {
        if (!controllerPressGuard) return false
        if (!held) controllerPressGuard = false
        return held
    }

    private var openingLink = false

    internal fun openCovered(open: () -> Unit) {
        if (!showing || McScreens.current() !== this) {
            open()
            return
        }
        openingLink = true
        try {
            open()
        } finally {
            openingLink = false
        }
        val overlay = McScreens.current()
        if (overlay != null && overlay !== this && showing) coverWith(overlay)
    }

    override fun removed() {
        val cover = pendingCover
        if (cover != null && showing) {
            coverWith(cover)
        } else if (!(openingLink && showing)) {
            uncover()
            hide()
        }
        super.removed()
    }

    private fun hide() {
        //? if = 1.8.9 {
        /*Keyboard.enableRepeatEvents(false)
        panorama = null
        *///?}
        showing = false
        sceneContextOrNull?.sync()
        ComposeSceneContextImpl.resetPointerIcon()
        wheel.stop()
        releaseHeldInput()
        consumedButtons.clear()
        pointerInside = false
        PolyGlassLight.reset()
        HostCallbacks.call(options, "ComposeOptions.onRemoved", Unit) { options.onRemoved(this) }
        releaseScene()
    }

    private fun coverWith(overlay: Screen) {
        coveredBy = overlay
        //? if > 1.8.9 && < 1.21.11
        //GlHost.coverScreen = overlay
        ComposeSceneContextImpl.resetPointerIcon()
        wheel.stop()
        releaseHeldInput()
        if (coverWatch == null) coverWatch = HostEvents.onClientTick { checkCover() }
    }

    private var keyboardArea: Rect? = null

    private var keyboardField: FieldPreview? = null

    private var keyboardPreview: KeyboardPreview? = null

    internal fun keyboardDocked(area: Rect) {
        keyboardArea = area
        sceneDirty = true
    }

    private fun syncKeyboardField() {
        if (coveredBy == null || keyboardArea == null) return
        val field = controllerSemantics?.focusedField()
        if (field == keyboardField) return
        keyboardField = field
        sceneDirty = true
    }

    private fun uncover() {
        //? if > 1.8.9 && < 1.21.11
        //if (GlHost.coverScreen === coveredBy) GlHost.coverScreen = null
        coveredBy = null
        if (keyboardArea != null) sceneDirty = true
        keyboardArea = null
        keyboardField = null
        coverWatch?.close()
        coverWatch = null
    }

    private fun checkCover() {
        val overlay = coveredBy
        val current = McScreens.current()
        if (overlay != null && current === overlay) return
        uncover()
        if (current !== this && showing) hide()
    }

    private fun isPresented(): Boolean {
        val current = McScreens.current() ?: return false
        //? if >= 1.21.11 {
        return current === this || current === coveredBy
        //?} else
        //return current === this || (current === coveredBy && GlHost.drawingCover)
    }

    //? if > 1.8.9 {
    //~ if >= 26.1 'render' -> 'extractRenderState'
    override fun extractRenderState(ctx: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, tickDelta: Float) {
    //?} else
    //override fun render(mouseX: Int, mouseY: Int, tickDelta: Float) {
        if (!isPresented()) return
        //? if = 1.8.9 {
        /*handleInputs()
        if (McScreens.current() !== this) return
        var backdropProvided = false
        if (drawsPanorama() && ScreenBackdrops.active) {
            panoramaGlState.capture()
            try {
                backdropProvided = ScreenBackdrops.draw(this, mouseX, mouseY, tickDelta)
            } finally {
                panoramaGlState.restore()
            }
        }
        if (!backdropProvided && client.level == null && drawsPanorama()) {
            panorama?.let {
                panoramaGlState.capture()
                try {
                    GlStateManager.disableAlphaTest()
                    it.drawBackground(mouseX, mouseY, tickDelta)
                    GlStateManager.enableAlphaTest()
                    fillGradient(0, 0, width, height, -2130706433, 16777215)
                    fillGradient(0, 0, width, height, 0, Int.MIN_VALUE)
                } finally {
                    panoramaGlState.restore()
                }
            }
        }
        *///?}

        //? if < 1.21.8 && > 1.8.9
        //renderBackground(ctx, mouseX, mouseY, tickDelta)

        if (scenePoisoned) {
            if (sceneRebuilds >= MAX_SCENE_REBUILDS) {
                LOGGER.error(
                    "Compose scene for {} failed {} times without a good frame in between; closing it.",
                    this::class.java.simpleName, sceneRebuilds,
                )
                closeSceneQuietly()
                closeWithMessage(options.messages.renderError)
                return
            }
            sceneRebuilds++
            onSceneRebuilding()
            val rebuilt = ensureScene()
            if (rebuilt == null || !bindContent()) {
                closeSceneQuietly()
                closeWithMessage(ComposeSupport.unavailableReason(options.messages) ?: options.messages.renderError)
                return
            }
            sceneDirty = true
            hasLastPointer = false
        }
        val metricsChanged = syncSceneMetrics()

        val focused = (McWindow.isActive() || testPointer != null) && coveredBy == null
        if (wasFocused && !focused) releaseHeldInput()
        wasFocused = focused
        if (focused) {
            val pointerPosition = pointerPosition()
            val moved = !hasLastPointer || pointerPosition != lastPointer
            if (moved) {
                lastPointer = pointerPosition
                hasLastPointer = true
                withScene { it.sendPointerEvent(PointerEventType.Move, pointerPosition) }
            }
            if (moved || !pointerInside) trackPointer(pointerPosition)
        } else if (pointerInside) {
            pointerInside = false
            notifyPointer(null)
        }

        if (metricsChanged) {
            withScene { it.invalidatePositionInWindow() }
        }

        withScene { wheel.frame(it, scaledDensity(), wheelPointer) }

        tickActivity.resume()
        val profile = if (FrameProfiler.enabled) profileHost() else null
        val recomposer = sceneHandle?.recomposer
        val forced = metricsChanged || sceneDirty || awaitingFirstFrame || renderMode == RenderMode.CONTINUOUS
        withScene { scene ->
            if (recomposer != null && (forced || frames.hasWork(recomposer, scene))) {
                frames.advance(recomposer, scene, System.nanoTime(), profile)
            }
        }
        if (notifyFrame()) sceneDirty = true

        if (liveScene() == null) return
        syncKeyboardField()

        //? if >= 1.21.11 {
        val backdrop = UiBlur.backdrop != null
        if (backdrop != paintedWithBackdrop) {
            paintedWithBackdrop = backdrop
            sceneDirty = true
        }
        //?}
        val wasDirty = sceneDirty || awaitingFirstFrame || renderMode == RenderMode.CONTINUOUS
        sceneDirty = false
        awaitingFirstFrame = false
        //? if >= 1.21.11 {
        profile.measure(ProfilePhase.Record) { recording.render(ctx, wasDirty, paintFrame) }
        //?} else
        //profile.within { GlHost.screen(wasDirty, blurAlpha(), underlay, wantsBackdrop(), beneathBlur, paintFrame) }
    }

    private var screenProfile: ProfileHost? = null

    private fun profileHost(): ProfileHost =
        screenProfile ?: FrameProfiler.host("screen/" + ProfilerHooks.simpleName(javaClass)).also { screenProfile = it }

    private fun trackPointer(pointer: Offset) {
        val inside = pointer.x >= 0f && pointer.y >= 0f && pointer.x < lastSceneW && pointer.y < lastSceneH
        if (inside) {
            notifyPointer(pointer)
        } else if (pointerInside) {
            notifyPointer(null)
            withScene { it.sendPointerEvent(PointerEventType.Exit, pointer) }
        }
        pointerInside = inside
    }

    private fun notifyPointer(position: Offset?) {
        if (position == null) PolyGlassLight.leave() else PolyGlassLight.moveTo(position)
        HostCallbacks.call(options, "ComposeOptions.onPointer", Unit) { options.onPointer(this, position) }
    }

    private fun notifyFrame(): Boolean {
        return HostCallbacks.call(options, "ComposeOptions.onFrame", false) { options.onFrame(this) }
    }

    //? if >= 1.21.11
    private val recording = Recording()

    private val paintFrame: (RenderContext) -> Unit = { paint(it) }

    private fun paint(ctx: RenderContext) {
        try {
            val pixelRatio = surfaceScale()
            if (pixelRatio != 1f) ctx.renderer.scale(pixelRatio, pixelRatio)
            val canvas = PolyCanvas(ctx.renderer)
            val rendered = withScene { frames.draw(it, canvas) }
            if (rendered != null) sceneRebuilds = 0
            if (controllerFocusVisible && drawsControllerFocusRing) drawFocusRing(canvas)
            drawKeyboardPreview(canvas)
        } catch (t: Throwable) {
            poisonScene(t)
        }
    }

    private fun drawFocusRing(canvas: PolyCanvas) {
        val rect = withScene { it.focusManager.getFocusRect(false) } ?: return
        if (controllerSemantics?.focusedHidesRing() == true) return
        val style = HostCallbacks.call(options, "ComposeOptions.focusRing", FocusRingStyle.Default) { options.focusRing }
        val density = scaledDensity()
        val stroke = (style.width.value * density).coerceAtLeast(1f)
        val gap = style.gap.value * density
        val radius = style.cornerRadius.value * density
        val paint = Paint().apply { this.style = PaintingStyle.Stroke }
        style.halo?.let { halo ->
            val haloWidth = (stroke / 2f).coerceAtLeast(1f)
            val inset = gap - haloWidth / 2f
            paint.strokeWidth = haloWidth
            paint.color = halo
            canvas.drawRoundRect(rect.left - inset, rect.top - inset, rect.right + inset, rect.bottom + inset, radius, radius, paint)
        }
        val outset = gap + stroke / 2f
        paint.strokeWidth = stroke
        paint.color = style.color
        canvas.drawRoundRect(rect.left - outset, rect.top - outset, rect.right + outset, rect.bottom + outset, radius + outset, radius + outset, paint)
    }

    private fun drawKeyboardPreview(canvas: PolyCanvas) {
        if (coveredBy == null) return
        val area = keyboardArea ?: return
        val field = keyboardField ?: return
        val preview = keyboardPreview ?: KeyboardPreview().also { keyboardPreview = it }
        preview.draw(canvas, area, 1f / guiUnitsPerScenePixel, field)
    }

    private fun hideControllerFocus() {
        if (!controllerFocusVisible) return
        controllerFocusVisible = false
        sceneDirty = true
    }

    //? if = 1.8.9 {
    /*override fun handleInputs() {
        LegacyInputDrain.active = true
        try {
            var mouse = Mouse.isCreated() && Mouse.next()
            var keyboard = Keyboard.isCreated() && Keyboard.next()
            while (mouse || keyboard) {
                if (mouse && (!keyboard || Mouse.getEventNanoseconds() <= Keyboard.getEventNanoseconds())) {
                    if (!routeMouseToGameLayers() && !HostCallbacks.any(ComposeHost.legacyInput, "LegacyInputObserver.onDrainedMouse") { it.onDrainedMouse() }) handleMouse()
                    mouse = Mouse.next()
                } else {
                    HostCallbacks.each(ComposeHost.legacyInput, "LegacyInputObserver.onDrainedKeyboard") { it.onDrainedKeyboard() }
                    if (!routeToGameLayers()) handleKeyboard()
                    keyboard = Keyboard.next()
                }
            }
        } finally {
            LegacyInputDrain.active = false
        }
    }

    private fun routeMouseToGameLayers(): Boolean {
        val button = Mouse.getEventButton()
        if (button < 0) return false
        return GameKeyRouter.consumeButton(KeyCodes.mouseFromLegacy(button), Mouse.getEventButtonState())
    }

    private fun routeToGameLayers(): Boolean {
        val legacyKey = Keyboard.getEventKey()
        if (legacyKey <= 0) return false
        val key = KeyCodes.fromLegacy(legacyKey).value
        val state = GameKeyRouter.legacyKeyState
        if (!GameKeyRouter.consume(key, state)) return false
        RawKeys.onKey(key, state)
        return true
    }

    override fun handleMouse() {
        val wheel = Mouse.getEventDWheelPrecise()
        if (wheel != 0.0) {
            mouseScrolled(wheel)
        }

        super.handleMouse()
    }

    override fun handleKeyboard() {
        fun getModifiers(): Int {
            var m = 0
            if (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) m = m or InputConstants.MOD_CONTROL
            if (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) m = m or InputConstants.MOD_SHIFT
            if (Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU)) m = m or InputConstants.MOD_ALT
            if (Keyboard.isKeyDown(Keyboard.KEY_LMETA) || Keyboard.isKeyDown(Keyboard.KEY_RMETA)) m = m or InputConstants.MOD_SUPER
            return m
        }

        val char = Keyboard.getEventCharacter()
        val legacyKey = Keyboard.getEventKey()
        val key = if (legacyKey > 0) KeyCodes.fromLegacy(legacyKey).value else -1
        val modifiers = getModifiers()
        if (key > 0) RawKeys.onKey(key, GameKeyRouter.legacyKeyState)

        if (Keyboard.getEventKeyState()) {
            val keyHandled = if (key >= 0) keyPressed(key, modifiers) else false
            val typedHandled = if (char != Keyboard.CHAR_NONE.toChar()) {
                charTyped(char, modifiers)
            } else false

            if (key >= 0 && !keyHandled && !typedHandled) {
                super.keyPressed(char, legacyKey)
            }
        } else if (key >= 0) {
            keyReleased(key, modifiers)
        }
        client.handleGuiKeyBindings()
    }
    *///?}

    //? if >= 1.21.10 {
    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val button = event.button()
    //?} elif > 1.8.9 {
    /*override fun mouseClicked(x: Double, y: Double, button: Int): Boolean {
    *///?} else
    //override fun mouseClicked(x: Int, y: Int, button: Int) {
        reportInputKind(InputKind.Pointer)
        hideControllerFocus()
        //~ if = 1.8.9 'button' -> 'KeyCodes.mouseFromLegacy(button)' {
        tapClean = false
        heldButtons += button
        if (interceptMouse(button, true) || handleMouseClicked(button)) {
            consumedButtons += button
            //$ if > 1.8.9 'return true' else 'return'
            return true
        }
        sendMouseButtonEvent(PointerEventType.Press, button)
        //~}

        //? if >= 1.21.10 {
        return super.mouseClicked(event, doubleClick)
        //?} elif > 1.8.9 {
        /*return super.mouseClicked(x, y, button)
        *///?} else
        //super.mouseClicked(x, y, button)
    }

    //? if >= 1.21.10 {
    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        val button = event.button()
    //?} elif > 1.8.9 {
    /*override fun mouseReleased(x: Double, y: Double, button: Int): Boolean {
    *///?} else
    //override fun mouseReleased(x: Int, y: Int, button: Int) {
        //~ if = 1.8.9 'button' -> 'KeyCodes.mouseFromLegacy(button)' {
        if (!heldButtons.remove(button) && mouseHeldAtOpen > 0) mouseHeldAtOpen--
        val interceptConsumed = interceptMouse(button, false)
        val pressConsumed = consumedButtons.remove(button)
        if (!interceptConsumed && !pressConsumed) {
            sendMouseButtonEvent(PointerEventType.Release, button)
        }
        //~}

        //? if >= 1.21.10 {
        return super.mouseReleased(event)
        //?} elif > 1.8.9 {
        /*return super.mouseReleased(x, y, button)
        *///?} else
        //super.mouseReleased(x, y, button)
    }

    //? if > 1.8.9 {
    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        tapClean = false
        sendScrollEvent(scrollX, scrollY)
        return super.mouseScrolled(x, y, scrollX, scrollY)
    }
    //?} else {
    /*private fun mouseScrolled(scrollY: Double) {
        tapClean = false
        sendScrollEvent(0.0, scrollY)
    }
    *///?}

    protected open fun handleMouseClicked(button: Int): Boolean = false

    private var wasFocused = true

    private fun releaseHeldInput() {
        releaseHeldButtons()
        if (hasLastPointer) {
            val pointer = lastPointer
            withScene { it.sendPointerEvent(PointerEventType.Exit, pointer) }
            hasLastPointer = false
        }
        releaseHeldKeys()
    }

    private fun releaseHeldKeys() {
        if (sentKeys.isEmpty()) return
        val held = sentKeys.toList()
        sentKeys.clear()
        for ((bindingKey, shortcutKey) in held) sendKeyReleasedEvent(bindingKey, shortcutKey, 0)
    }

    private fun releaseHeldButtons() {
        withScene { it.cancelPointerInput() }
        tapClean = false
        heldButtons.clear()
        mouseHeldAtOpen = 0
        sendMouseButtonEvent(PointerEventType.Release, InputConstants.MOUSE_BUTTON_LEFT)
        sendMouseButtonEvent(PointerEventType.Release, InputConstants.MOUSE_BUTTON_RIGHT)
        sendMouseButtonEvent(PointerEventType.Release, InputConstants.MOUSE_BUTTON_MIDDLE)
        sendMouseButtonEvent(PointerEventType.Release, McKeys.MOUSE_BACK)
        sendMouseButtonEvent(PointerEventType.Release, McKeys.MOUSE_FORWARD)
    }

    private fun sendMouseButtonEvent(type: PointerEventType, button: Int) {
        val pointerButton = when (button) {
            InputConstants.MOUSE_BUTTON_LEFT -> PointerButton.Primary
            InputConstants.MOUSE_BUTTON_RIGHT -> PointerButton.Secondary
            InputConstants.MOUSE_BUTTON_MIDDLE -> PointerButton.Tertiary
            McKeys.MOUSE_BACK -> PointerButton.Back
            McKeys.MOUSE_FORWARD -> PointerButton.Forward
            else -> return
        }
        if (type == PointerEventType.Press) wheel.stop()
        withScene { it.sendPointerEvent(type, button = pointerButton, position = pointerPosition()) }
    }

    private val wheel = SceneWheel(options, SceneKind.Screen)
    private val wheelPointer: () -> Offset = ::pointerPosition

    private var lastPreciseScrollNanos = 0L

    private fun sendScrollEvent(scrollX: Double, scrollY: Double) {
        val now = System.nanoTime()
        //? if > 1.8.9 {
        val rawX = RawScroll.x
        val rawY = RawScroll.y
        val discrete = client.options.discreteMouseScroll().get()
        //?} else {
        /*val rawX = scrollX
        val rawY = scrollY
        val discrete = false
        *///?}
        //~ if !sdl 'takeScroll(rawX, rawY)' -> 'readScroll()'
        val macPrecise = if (OperatingSystem.isMac) MacScrollEvent.takeScroll(rawX, rawY) else null
        val precise = !discrete && isPreciseScroll(rawX, rawY, now, macPrecise)
        val shift = isKeyDown(InputConstants.KEY_LSHIFT) || isKeyDown(InputConstants.KEY_RSHIFT)

        //~ if = 1.8.9 'ScrollMath.notches(scrollX)' -> '0f'
        val notchX = ScrollMath.notches(scrollX)
        val notchY = ScrollMath.notches(scrollY)
        val sceneDelta = Offset(ScrollMath.horizontal(shift, notchX, notchY), ScrollMath.vertical(shift, notchY))
        val position = pointerPosition()
        wheel.dispatch(position, sceneDelta, precise) {
            withScene {
                it.sendPointerEvent(PointerEventType.Move, position)
                SceneWheel.send(it, position, sceneDelta, precise)
            }
        }
    }

    private fun isPreciseScroll(scrollX: Double, scrollY: Double, now: Long, macPrecise: Boolean?): Boolean {
        if (macPrecise != null) {
            if (macPrecise) lastPreciseScrollNanos = now
            return macPrecise
        }

        val wheelSteps = OperatingSystem.isLinux && isWheelStep(scrollX) && isWheelStep(scrollY)
        if ((scrollX % 1.0 != 0.0 || scrollY % 1.0 != 0.0) && !wheelSteps) lastPreciseScrollNanos = now
        return lastPreciseScrollNanos != 0L && now - lastPreciseScrollNanos < PRECISE_SCROLL_HOLD_NANOS
    }

    //? if >= 1.21.10 {
    override fun charTyped(event: CharacterEvent): Boolean {
        val char = if (Character.isBmpCodePoint(event.codepoint)) Char(event.codepoint) else Char(0)
        val codepoint = event.codepoint
        //? if >= 26.1 {
        val modifiers = 0
        //? } else
        //val modifiers = event.modifiers
    //?} elif > 1.8.9 {
    /*override fun charTyped(char: Char, modifiers: Int): Boolean {
       val codepoint = char.code
    *///?} else {
    /*private fun charTyped(char: Char, modifiers: Int): Boolean {
        val codepoint = char.code
    *///?}
        val handled = sendCharacterEvent(char, codepoint, modifiers)
        //? if >= 1.21.10 {
        return handled || super.charTyped(event)
        //?} elif > 1.8.9 {
        /*return handled || super.charTyped(char, modifiers)
        *///?} else
        //return handled
    }

    fun Int.ctrlDown() = this and InputConstants.MOD_CONTROL != 0
    //? if >= 1.21.9 || = 1.8.9 {
    fun Int.shiftDown() = this and InputConstants.MOD_SHIFT != 0
    fun Int.altDown() = this and InputConstants.MOD_ALT != 0
    fun Int.superDown() = this and InputConstants.MOD_SUPER != 0
    //?} else {
    /*fun Int.shiftDown() = this and GLFW.GLFW_MOD_SHIFT != 0
    fun Int.altDown() = this and GLFW.GLFW_MOD_ALT != 0
    fun Int.superDown() = this and GLFW.GLFW_MOD_SUPER != 0
    *///?}

    protected open fun handleKeyPressed(key: Int, modifiers: Int): Boolean = false

    protected open val modifierTapKeys: Set<Int>? get() = null

    protected open fun handleModifierTapped() {}

    protected val isModifierTapBind: Boolean get() {
        val keys = modifierTapKeys ?: return false
        return keys.isNotEmpty() && keys.all(MinecraftKeyboardAdapter::isModifier)
    }

    private var tapReady = false
    private var tapClean = false

    private val heldAtOpen = HashSet<Int>()
    private val heldButtons = HashSet<Int>()
    private var mouseHeldAtOpen = 0

    internal fun onRawKey(key: Int, pressed: Boolean) {
        if (!pressed) {
            heldAtOpen.remove(key)
            return
        }
        val keys = modifierTapKeys ?: return
        if (key !in keys) {
            tapClean = false
            return
        }
        val held = RawKeys.held + heldAtOpen
        val noButtons = heldButtons.isEmpty() && mouseHeldAtOpen == 0
        if (keys.none { it != key && it in held }) {
            tapClean = held.none { it != key } && noButtons
        }
        tapReady = isModifierTapBind && noButtons && !capturingInput && held + key == keys
    }

    private val consumedKeys = HashSet<Int>()
    private val sentKeys = HashMap<Int, Int>()

    private val navigationKeys = intArrayOf(
        InputConstants.KEY_TAB, InputConstants.KEY_UP, InputConstants.KEY_DOWN, InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT,
        InputConstants.KEY_SPACE, InputConstants.KEY_RETURN, InputConstants.KEY_NUMPADENTER,
    )
    private val consumedButtons = HashSet<Int>()

    //? if >= 1.21.10 {
    override fun keyPressed(event: McKeyEvent): Boolean {
        val bindingKey = event.key
        //~ if !sdl 'event.shortcutKey()' -> 'bindingKey'
        val shortcutKey = event.shortcutKey()
        val modifiers = event.modifiers
    //?} else {
    /*//? if > 1.8.9 {
    override fun keyPressed(key: Int, scanCode: Int, modifiers: Int): Boolean {
    //?} else
    //private fun keyPressed(key: Int, modifiers: Int): Boolean {
        val bindingKey = key
        //~ if = 1.8.9 '= key' -> '= SDL_GetKeyFromScancode(key, 0, true)'
        val shortcutKey = key
    *///?}
        val handled = dispatchKeyPressed(bindingKey, shortcutKey, modifiers)
        //? if >= 1.21.10 {
        return handled || super.keyPressed(event)
        //?} elif > 1.8.9 {
        /*return handled || super.keyPressed(key, scanCode, modifiers)
        *///?} else
        //return handled
    }

    //? if >= 1.21.10 {
    override fun keyReleased(event: McKeyEvent): Boolean {
        val bindingKey = event.key
        //~ if !sdl 'event.shortcutKey()' -> 'bindingKey'
        val shortcutKey = event.shortcutKey()
        val modifiers = event.modifiers
    //?} else {
    /*//? if > 1.8.9 {
    override fun keyReleased(key: Int, scanCode: Int, modifiers: Int): Boolean {
    //?} else
    //private fun keyReleased(key: Int, modifiers: Int): Boolean {
        val bindingKey = key
        //~ if = 1.8.9 '= key' -> '= SDL_GetKeyFromScancode(key, 0, true)'
        val shortcutKey = key
    *///?}
        val tapKeys = modifierTapKeys
        if (tapReady && tapKeys?.contains(bindingKey) == true) {
            if (tapKeys.none { it != bindingKey && it in RawKeys.held }) {
                tapReady = false
                if (tapClean && isWindowFocused()) handleModifierTapped()
            }
        } else {
            tapReady = false
        }
        val handled = !consumedKeys.remove(bindingKey) && sendKeyReleasedEvent(bindingKey, shortcutKey, modifiers)
        //? if >= 1.21.10 {
        return handled || super.keyReleased(event)
        //?} elif > 1.8.9 {
        /*return handled || super.keyReleased(key, scanCode, modifiers)
        *///?} else
        //return handled
    }

    private val capturingInput: Boolean
        get() = HostCallbacks.call(options, "ComposeOptions.isCapturingInput", false) { options.isCapturingInput }

    private fun interceptMouse(code: Int, pressed: Boolean): Boolean =
        HostCallbacks.call(options, "ComposeOptions.consumeMouse", false) { options.consumeMouse(this, code, pressed) }

    private fun reportInputKind(kind: InputKind) {
        if (kind != InputKind.Controller) pendingSpatial = null
        HostCallbacks.call(options, "ComposeOptions.onInputKind", Unit) { options.onInputKind(this, kind) }
    }

    private fun dispatchKeyPressed(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean {
        if (bindingKey in navigationKeys) reportInputKind(if (Controllers.isDispatching) InputKind.Controller else InputKind.Keyboard)
        if (bindingKey == InputConstants.KEY_ESCAPE) {
            if (HostCallbacks.call(options, "ComposeOptions.consumeEscape", false) { options.consumeEscape(this) }) {
                consumedKeys += bindingKey
                return true
            }
            if (capturingInput) {
                sendKeyPressedEvent(bindingKey, shortcutKey, modifiers)
                return true
            }
            if (sendKeyPressedEvent(bindingKey, shortcutKey, modifiers)) return true
            if (BackStack.closeTop() || handleKeyPressed(bindingKey, modifiers)) {
                consumedKeys += bindingKey
                return true
            }
            return false
        }
        if (handleKeyPressed(bindingKey, modifiers)) {
            consumedKeys += bindingKey
            return true
        }
        if (sendKeyPressedEvent(bindingKey, shortcutKey, modifiers)) return true
        if (!capturingInput && ProfilerKeybind.matches(bindingKey)) {
            consumedKeys += bindingKey
            ProfilerControl.toggleOverlay()
            return true
        }
        return false
    }

    private fun sendKeyPressedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean =
        sendKeyEvent(KeyEventType.KeyDown, KeyEvent.KEY_PRESSED, Char(0), bindingKey, shortcutKey, modifiers)

    private fun sendKeyReleasedEvent(bindingKey: Int, shortcutKey: Int, modifiers: Int): Boolean =
        sendKeyEvent(KeyEventType.KeyUp, KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, bindingKey, shortcutKey, modifiers)

    private fun sendKeyEvent(
        type: KeyEventType,
        awtId: Int,
        awtChar: Char,
        bindingKey: Int,
        shortcutKey: Int,
        modifiers: Int,
    ): Boolean {
        val awtCode = MinecraftKeyboardAdapter.toAwtKeyCode(shortcutKey)
        val eventLocation = MinecraftKeyboardAdapter.keyLocation(bindingKey)
        if (type == KeyEventType.KeyDown) sentKeys[bindingKey] = shortcutKey else sentKeys.remove(bindingKey)
        return sendKeyEventSafely {
            ComposeKeyEvent(
                key = Key(awtCode, eventLocation),
                type = type,
                codePoint = bindingKey,
                isCtrlPressed = modifiers.ctrlDown(),
                isShiftPressed = modifiers.shiftDown(),
                isAltPressed = modifiers.altDown(),
                isMetaPressed = modifiers.superDown(),
                nativeEvent = KeyEvent(
                    DummyComponent,
                    awtId,
                    System.currentTimeMillis(),
                    modifiersToAwt(modifiers),
                    awtCode,
                    awtChar,
                    eventLocation
                )
            )
        }
    }

    private fun sendCharacterEvent(char: Char, codePoint: Int, modifiers: Int): Boolean {
        if (!Character.isBmpCodePoint(codePoint)) {
            return sendCharKeyEvent(Character.highSurrogate(codePoint), codePoint, modifiers)
        }
        if (char == KeyEvent.CHAR_UNDEFINED) return false
        return sendCharKeyEvent(char, codePoint, modifiers)
    }

    private fun sendCharKeyEvent(char: Char, codePoint: Int, modifiers: Int): Boolean {
        return sendKeyEventSafely {
            ComposeKeyEvent(
                key = Key(KeyEvent.VK_UNDEFINED),
                type = KeyEventType.KeyDown,
                codePoint = codePoint,
                isCtrlPressed = modifiers.ctrlDown(),
                isShiftPressed = modifiers.shiftDown(),
                isAltPressed = modifiers.altDown(),
                isMetaPressed = modifiers.superDown(),
                nativeEvent = KeyEvent(
                    DummyComponent,
                    KeyEvent.KEY_TYPED,
                    System.currentTimeMillis(),
                    modifiersToAwt(modifiers),
                    0,
                    char,
                    KeyEvent.KEY_LOCATION_UNKNOWN
                )
            )
        }
    }

    private fun modifiersToAwt(modifiers: Int): Int {
        var m = 0
        if (modifiers.ctrlDown()) m = m or InputEvent.CTRL_DOWN_MASK
        if (modifiers.altDown()) m = m or InputEvent.ALT_DOWN_MASK
        if (modifiers.shiftDown()) m = m or InputEvent.SHIFT_DOWN_MASK
        if (modifiers.superDown()) m = m or InputEvent.META_DOWN_MASK
        return m
    }

    private fun syncSceneMetrics(): Boolean {
        val w = McWindow.windowWidth()
        val h = McWindow.windowHeight()
        if (w <= 0 || h <= 0) return false
        val applied = withScene { sceneHandle?.resize(w, h, scaledDensity()) }
        if (applied == null) return false
        sceneContextOrNull?.updateContainerSize(w, h, scaledDensity())
        val changed = w != lastSceneW || h != lastSceneH
        val fbW = McWindow.viewportWidth()
        val fbH = McWindow.viewportHeight()
        if (changed || fbW != lastFbWidth || fbH != lastFbHeight) {
            cachedSurfaceScale = -1f
            uiOversample.floatValue = surfaceScale()
            settleFrames = SETTLE_FRAMES
            sceneDirty = true
        }
        if (settleFrames > 0) {
            settleFrames--
            sceneDirty = true
        }
        lastSceneW = w
        lastSceneH = h
        lastFbWidth = fbW
        lastFbHeight = fbH
        return changed
    }

    private fun surfaceScale(): Float {
        if (cachedSurfaceScale > 0f) return cachedSurfaceScale
        val screenW = McWindow.windowWidth()
        val scale = if (screenW <= 0) positiveOrOne(McWindow.pixelRatio())
        else (McWindow.viewportWidth().toFloat() / screenW).coerceAtLeast(0.01f)
        cachedSurfaceScale = scale
        return scale
    }

    internal fun controllerKey(key: Int, pressed: Boolean, modifiers: Int = 0): Boolean {
        if (!showing) return false
        val shortcut = controllerShortcut(key)
        return Controllers.dispatching {
            if (pressed) dispatchKeyPressed(key, shortcut, modifiers)
            else !consumedKeys.remove(key) && sendKeyReleasedEvent(key, shortcut, modifiers)
        }
    }

    private fun controllerShortcut(key: Int): Int {
        //? if >= 26.3 {
        return SDL_GetKeyFromScancode(key, 0, true)
        //?} else
        //return key
    }

    internal fun controllerChar(char: Char, modifiers: Int = 0): Boolean {
        if (!showing) return false
        return Controllers.dispatching { sendCharacterEvent(char, char.code, modifiers) }
    }

    internal fun controllerBack(): Boolean {
        reportInputKind(InputKind.Controller)
        return Controllers.dispatching {
            val handled = controllerKey(InputConstants.KEY_ESCAPE, true)
            controllerKey(InputConstants.KEY_ESCAPE, false)
            handled
        }
    }

    internal fun controllerPress() {
        if (!showing) return
        reportInputKind(InputKind.Controller)
        val key = InputConstants.KEY_RETURN
        val shortcut = controllerShortcut(key)
        Controllers.dispatching {
            if (!sendKeyPressedEvent(key, shortcut, 0)) handleKeyPressed(key, 0)
            sendKeyReleasedEvent(key, shortcut, 0)
        }
    }

    internal fun controllerTap(key: Int): Boolean {
        if (!showing) return false
        val shortcut = controllerShortcut(key)
        return Controllers.dispatching {
            val consumed = sendKeyPressedEvent(key, shortcut, 0)
            sendKeyReleasedEvent(key, shortcut, 0) || consumed
        }
    }

    internal val actionTargets = ControllerTargets().also { targets ->
        targets.focusInside = { token ->
            val moved = controllerSemantics?.focusFirstIn(token) == true
            if (moved) {
                controllerFocusVisible = true
                sceneDirty = true
            }
            moved
        }
        targets.activeLayers = { controllerSemantics?.activeLayers() }
    }

    internal fun controllerHints(): List<ControllerHint> = if (liveScene() == null) emptyList() else actionTargets.hints()

    internal fun controllerButton(button: ControllerButton): Boolean {
        if (!showing) return false
        reportInputKind(InputKind.Controller)
        if (liveScene() == null) return false
        val handled = actionTargets.dispatch(button)
        if (handled) sceneDirty = true
        return handled
    }

    internal fun controllerNavigate(direction: FocusDirection, arrowKey: Int): Boolean {
        if (!showing) return false
        reportInputKind(InputKind.Controller)
        pendingSpatial = null
        val focused = withScene { it.focusManager.getFocusRect(false) } != null
        if (!focused) {
            controllerKeepFocus()
            if (withScene { it.focusManager.getFocusRect(false) } != null) return true
        }
        val arrows = focused && controllerTextField() == null
        val claimed = arrows && controllerSemantics?.focusedArrows()?.covers(direction) == true
        if (claimed && tapArrow(arrowKey)) return true
        if (controllerMoveFocus(direction)) return true
        return arrows && !claimed && tapArrow(arrowKey)
    }

    private fun tapArrow(arrowKey: Int): Boolean {
        if (!controllerTap(arrowKey)) return false
        controllerFocusVisible = true
        sceneDirty = true
        return true
    }

    private val focusTrail = ArrayList<FocusMark>()

    private var pendingSpatial: FocusDirection? = null

    private var pendingTicks = 0

    private var pendingFrom: FocusedNode? = null

    internal fun controllerRetryPending() {
        val direction = pendingSpatial ?: return
        if (!showing || !Controllers.isActive || liveScene() == null || --pendingTicks < 0 || controllerSemantics?.focusedNode() != pendingFrom) {
            pendingSpatial = null
            return
        }
        val result = withScene { Controllers.dispatching { controllerSemantics?.moveSpatially(direction, scroll = false) } }
        if (result == SpatialNavigation.MOVED) {
            pendingSpatial = null
            controllerFocusVisible = true
            sceneDirty = true
        }
    }

    internal fun controllerKeepFocus(restore: Boolean = true) {
        if (!showing || liveScene() == null) return
        val semantics = controllerSemantics ?: return
        val mark = semantics.focusedMark()
        if (mark != null) {
            val last = focusTrail.lastOrNull()
            if (last == null || last.owner !== mark.owner || last.bounds != mark.bounds) {
                focusTrail += mark
                if (focusTrail.size > MAX_FOCUS_TRAIL) focusTrail.removeAt(0)
            }
            return
        }
        if (!restore) return
        focusTrail.removeAll { !semantics.isAlive(it.owner) }
        val newest = focusTrail.lastOrNull() ?: return
        val restored = withScene {
            focusTrail.asReversed().any { semantics.focusAt(it.owner, it.bounds) } ||
                semantics.focusNearest(newest.owner, newest.bounds)
        } == true
        if (restored) {
            controllerFocusVisible = true
            sceneDirty = true
        }
    }

    internal fun controllerTextField(): Rect? {
        if (liveScene() == null) return null
        return controllerSemantics?.focusedTextField()
    }

    internal fun controllerMoveFocus(direction: FocusDirection): Boolean = withScene { scene ->
        reportInputKind(InputKind.Controller)
        pendingSpatial = null
        val manager = scene.focusManager
        val focus = SceneFocus.of(manager) ?: controllerFocus
        val moved = Controllers.dispatching {
            if (manager.getFocusRect(false) == null) {
                manager.takeFocus(FocusDirection.Next) || focus?.moveFocus(FocusDirection.Next) == true
            } else {
                when (controllerSemantics?.moveSpatially(direction)) {
                    SpatialNavigation.MOVED -> true
                    SpatialNavigation.SCROLLED -> {
                        pendingSpatial = direction
                        pendingTicks = PENDING_SPATIAL_TICKS
                        pendingFrom = controllerSemantics?.focusedNode()
                        true
                    }
                    SpatialNavigation.BLOCKED -> false
                    else -> focus?.moveFocus(direction) == true
                }
            }
        }
        if (moved || !controllerFocusVisible) {
            controllerFocusVisible = true
            sceneDirty = true
        }
        moved
    } ?: false

    internal fun controllerScroll(delta: Offset) {
        if (delta == Offset.Zero || !showing) return
        reportInputKind(InputKind.Controller)
        wheel.stop()
        val pan = withScene { controllerSemantics?.focusedPan() }
        if (pan != null) {
            withScene { Controllers.dispatching { pan(delta) } }
            sceneDirty = true
            return
        }
        val pointer = pointerPosition()
        val anchor = withScene { it.focusManager.getFocusRect(false)?.let(::visibleCenter) } ?: pointer
        withScene {
            Controllers.dispatching {
                it.sendPointerEvent(PointerEventType.Move, anchor)
                SceneWheel.scrollDirect(it, anchor, delta)
                if (anchor != pointer) it.sendPointerEvent(PointerEventType.Move, pointer)
            }
        }
    }

    private fun visibleCenter(focus: Rect): Offset {
        val window = Rect(0f, 0f, McWindow.windowWidth().toFloat(), McWindow.windowHeight().toFloat())
        val visible = focus.intersect(window)
        if (visible.width > 0f && visible.height > 0f) return visible.center
        val center = focus.center
        if (window.width < 1f || window.height < 1f) return center
        return Offset(center.x.coerceIn(window.left, window.right - 1f), center.y.coerceIn(window.top, window.bottom - 1f))
    }

    internal fun controllerTargets(action: (Rect) -> Unit) {
        if (liveScene() == null) return
        controllerSemantics?.forEachTarget(action)
    }

    internal val guiUnitsPerScenePixel: Float
        get() = width.toFloat() / McWindow.windowWidth().coerceAtLeast(1)

    fun `controlify$collectSnapPoints`(consumer: Consumer<Any?>) {
        ControllerBridge.collectSnapPoints(this, consumer)
    }

    var testPointer: Offset? = null

    protected fun pointerPosition(): Offset {
        testPointer?.let { return it }
        //? if > 1.8.9 {
        val mouse = client.mouseHandler
        return Offset(mouse.xpos().toFloat(), mouse.ypos().toFloat())
        //?} else {
        /*val pixelRatio = McWindow.pixelRatio()
        return Offset(Mouse.getX() / pixelRatio, (McWindow.viewportHeight() - Mouse.getY() - 1) / pixelRatio)
        *///?}
    }

    private companion object {
        const val SETTLE_FRAMES = 4

        const val PRECISE_SCROLL_HOLD_NANOS = 250_000_000L

        fun isWheelStep(value: Double) = abs(value * 120 - round(value * 120)) < 1e-3

        const val MAX_SCENE_REBUILDS = 3

        const val MAX_FOCUS_TRAIL = 32

        const val PENDING_SPATIAL_TICKS = 8
    }
}

private object DummyComponent : Component()

internal fun positiveOrOne(value: Float): Float = if (value > 0f) value else 1f

private object SceneDensityCache {
    const val RECHECK_NANOS = 500_000_000L

    var windowW = -1
    var windowH = -1
    var viewportW = -1
    var viewportH = -1
    var checkedAt = 0L
    var density = 1f
}

fun sceneDensity(): Float {
    val cache = SceneDensityCache
    val windowW = McWindow.windowWidth()
    val windowH = McWindow.windowHeight()
    val viewportW = McWindow.viewportWidth()
    val viewportH = McWindow.viewportHeight()
    val now = System.nanoTime()
    val sameWindow = windowW == cache.windowW && windowH == cache.windowH
    val sameViewport = viewportW == cache.viewportW && viewportH == cache.viewportH
    if (sameWindow && sameViewport && now - cache.checkedAt < SceneDensityCache.RECHECK_NANOS) return cache.density
    val density = querySceneDensity()
    cache.windowW = windowW
    cache.windowH = windowH
    cache.viewportW = viewportW
    cache.viewportH = viewportH
    cache.checkedAt = now
    cache.density = density
    return density
}

private fun querySceneDensity(): Float {
    val pixelRatio = positiveOrOne(McWindow.pixelRatio())
    //? if sdl {
    val contentScale = SDL_GetWindowDisplayScale(McWindow.handle()).coerceAtLeast(1f)
    //?} else {
    /*val contentScaleX = FloatArray(1)
    val contentScaleY = FloatArray(1)
    GLFW.glfwGetWindowContentScale(McWindow.handle(), contentScaleX, contentScaleY)
    val contentScale = maxOf(contentScaleX[0], contentScaleY[0]).coerceAtLeast(1f)
    *///?}
    return (contentScale / pixelRatio).coerceAtLeast(1f)
}

internal object RawScroll {
    @JvmField
    var x = 0.0

    @JvmField
    var y = 0.0
}

object RawKeys {
    private val down = HashSet<Int>()

    @JvmStatic
    val held: Set<Int> = Collections.unmodifiableSet(down)

    @JvmStatic
    fun onKey(key: Int, state: KeyState) {
        if (key == 0 || state == KeyState.Repeated) return
        val pressed = state == KeyState.Pressed
        if (pressed) down += key else down -= key
        (McScreens.current() as? ComposeScreen)?.onRawKey(key, pressed)
    }
}
