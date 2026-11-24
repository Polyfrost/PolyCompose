package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import dev.isxander.controlify.Controlify
import dev.isxander.controlify.InputMode
import dev.isxander.controlify.api.ControlifyApi
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.controller.input.ControllerStateView
import kotlin.math.abs
import kotlin.math.sign
import net.minecraft.client.Minecraft
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.GameInputLayer
import org.polyfrost.compose.host.KeyState
import org.polyfrost.compose.host.controller.Controllers
import org.polyfrost.compose.minecraft.platform.McScreens
import org.polyfrost.compose.minecraft.platform.McWindow

internal object MixedInput : GameInputLayer {
    private const val GRACE_TICKS = 10
    private const val AXIS_THRESHOLD = 0.1f

    private var mode: InputMode = InputMode.KEYBOARD_MOUSE
    private var observer: AutoCloseable? = null
    private var quietTicks = 0
    private var cursorX = Double.NaN
    private var cursorY = Double.NaN
    private var screen: Any? = null
    private var tracked: ControllerEntity? = null
    private var held = HashSet<Any>()

    override val order: Int
        get() = Int.MAX_VALUE

    override fun consumeKey(key: Int, state: KeyState): Boolean {
        if (state == KeyState.Pressed) usedKeyboardMouse()
        return false
    }

    override fun consumeButton(button: Int, pressed: Boolean): Boolean {
        if (pressed && pointerIsReal()) usedKeyboardMouse()
        return false
    }

    override fun consumeScroll(x: Double, y: Double): Boolean {
        if (pointerIsReal()) usedKeyboardMouse()
        return false
    }

    fun onModeChanged(mode: InputMode) {
        this.mode = mode
        Controllers.reportActive(mode.isController)
        if (mode == InputMode.MIXED) {
            quietTicks = 0
            tracked = null
            rebaseCursor()
            if (observer == null) observer = ComposeHost.addGameInput(this)
        } else {
            observer?.close()
            observer = null
        }
    }

    fun onControllerUpdate(controller: ControllerEntity) {
        if (mode != InputMode.MIXED) return
        if (ControlifyApi.get().currentController.orElse(null) !== controller) return
        val state = controller.input().map { it.stateNow() }.orElse(null)
        val active = if (state != null && McWindow.isActive()) activeInputs(state) else HashSet()
        val pressed = tracked === controller && active.any { it !in held }
        tracked = controller
        held = active
        if (active.isNotEmpty()) {
            quietTicks = 0
        } else if (quietTicks < GRACE_TICKS) {
            quietTicks++
        }
        val moved = cursorMoved()
        if (pressed) {
            Controllers.reportActive(true)
        } else if (moved && pointerIsReal()) {
            usedKeyboardMouse()
        }
    }

    fun usedController() {
        if (mode == InputMode.MIXED) Controllers.reportActive(true)
    }

    private fun activeInputs(state: ControllerStateView): HashSet<Any> {
        val active = HashSet<Any>()
        for (button in state.buttons) if (state.isButtonDown(button)) active += button
        for (axis in state.axes) {
            val offset = state.getAxisState(axis) - state.getAxisResting(axis)
            if (abs(offset) > AXIS_THRESHOLD) active += axis to sign(offset)
        }
        return active
    }

    private fun usedKeyboardMouse() {
        if (mode == InputMode.MIXED) Controllers.reportActive(false)
    }

    private fun pointerIsReal(): Boolean =
        quietTicks >= GRACE_TICKS || !Controlify.instance().virtualMouseHandler().isVirtualMouseEnabled

    private fun cursorMoved(): Boolean {
        val current = McScreens.current()
        val mouse = Minecraft.getInstance().mouseHandler
        val x = mouse.xpos()
        val y = mouse.ypos()
        val moved = current === screen && !cursorX.isNaN() && (x != cursorX || y != cursorY)
        screen = current
        cursorX = x
        cursorY = y
        return moved
    }

    private fun rebaseCursor() {
        screen = McScreens.current()
        cursorX = Double.NaN
        cursorY = Double.NaN
    }
}
//?}
