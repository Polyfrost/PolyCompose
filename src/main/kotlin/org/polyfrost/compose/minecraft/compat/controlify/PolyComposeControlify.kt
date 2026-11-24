package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import dev.isxander.controlify.api.ControlifyApi
import dev.isxander.controlify.api.entrypoint.ControlifyEntrypoint
import dev.isxander.controlify.api.entrypoint.InitContext
import dev.isxander.controlify.api.entrypoint.PreInitContext
import dev.isxander.controlify.api.event.ControlifyEvents
import dev.isxander.controlify.api.vmousesnapping.SnapPoint
import dev.isxander.controlify.bindings.ControlifyBindings
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.screenop.ScreenProcessorFactory
import dev.isxander.controlify.screenop.ScreenProcessorProvider
import java.util.function.Consumer
import kotlin.math.min
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.host.controller.Controllers
import org.polyfrost.compose.minecraft.hud.HudInput
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.screen.ComposeScreen

class PolyComposeControlify : ControlifyEntrypoint {
    override fun onControlifyPreInit(context: PreInitContext) {
        try {
            ScreenProcessorProvider.registerProvider(
                ComposeScreen::class.java,
                ScreenProcessorFactory.Factory<ComposeScreen> { ComposeScreenProcessor(it) },
            )
            ControllerBridge.screens = ComposeSnapPoints
        } catch (t: Throwable) {
            HostErrors.warn("Could not register the Compose screen controller support", t, LOGGER)
        }
        try {
            ComposeGuiBindings.registerAll()
            Controllers.install(ControlifyActions)
        } catch (t: Throwable) {
            HostErrors.warn("Controller actions are unavailable", t, LOGGER)
        }
    }

    override fun onControlifyInit(context: InitContext) {
        try {
            ControlifyEvents.ACTIVE_CONTROLLER_TICKED.register { event -> onControllerTick(event.controller()) }
            ControlifyEvents.CONTROLLER_STATE_UPDATE.register { event -> onControllerUpdate(event.controller()) }
            ControlifyEvents.INPUT_MODE_CHANGED.register { event -> MixedInput.onModeChanged(event.mode()) }
            ControlifyEvents.LOOK_INPUT_MODIFIER.register { event ->
                if (Controllers.isLookCaptured) event.lookInput().zero()
            }
        } catch (t: Throwable) {
            HostErrors.warn("Could not register the controller listeners", t, LOGGER)
        }
    }

    override fun onControllersDiscovered(controlify: ControlifyApi) {
        try {
            MixedInput.onModeChanged(controlify.currentInputMode())
        } catch (t: Throwable) {
            HostErrors.warn("Could not read the controller input mode", t, LOGGER)
        }
    }

    private fun onControllerTick(controller: ControllerEntity) {
        try {
            Controllers.tick()
            GameplayCapture.releaseSneak()
            routeHudScroll(controller)
        } catch (t: Throwable) {
            HostErrors.warn("Controller tick failed", t, LOGGER)
        }
    }

    private fun onControllerUpdate(controller: ControllerEntity) {
        try {
            MixedInput.onControllerUpdate(controller)
        } catch (t: Throwable) {
            HostErrors.warn("Could not track mixed controller input", t, LOGGER)
        }
    }

    private fun routeHudScroll(controller: ControllerEntity) {
        val screen = McScreens.current() ?: return
        if (screen is ComposeScreen) return
        val x = ControlifyBindings.GUI_SECONDARY_NAVI_RIGHT.on(controller).analogueNow() -
            ControlifyBindings.GUI_SECONDARY_NAVI_LEFT.on(controller).analogueNow()
        val y = ControlifyBindings.GUI_SECONDARY_NAVI_DOWN.on(controller).analogueNow() -
            ControlifyBindings.GUI_SECONDARY_NAVI_UP.on(controller).analogueNow()
        if (x == 0f && y == 0f) return
        HudInput.consumeScroll((-x * HUD_SCROLL_NOTCHES).toDouble(), (-y * HUD_SCROLL_NOTCHES).toDouble())
    }

    private companion object {
        val LOGGER = LogManager.getLogger("PolyCompose/Controlify")

        const val HUD_SCROLL_NOTCHES = 0.5f
    }
}

private object ComposeSnapPoints : ScreenControllerSupport {
    override fun collectSnapPoints(screen: ComposeScreen, consumer: Consumer<Any?>) {
        val scale = screen.guiUnitsPerScenePixel
        screen.controllerTargets { bounds ->
            val center = bounds.center
            val range = min(bounds.width, bounds.height) * scale / 2f
            consumer.accept(SnapPoint((center.x * scale).toInt(), (center.y * scale).toInt(), range.toInt() + SNAP_PADDING))
        }
    }

    private const val SNAP_PADDING = 10
}
//?}
