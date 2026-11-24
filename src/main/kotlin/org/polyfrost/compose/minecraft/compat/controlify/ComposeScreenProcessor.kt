package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.geometry.Offset
import com.mojang.blaze3d.platform.InputConstants
import dev.isxander.controlify.api.bind.InputBindingSupplier
import dev.isxander.controlify.bindings.ControlifyBindings
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.controller.input.ControllerStateView
import dev.isxander.controlify.controller.input.GamepadInputs
import dev.isxander.controlify.screenop.ScreenProcessor
import dev.isxander.controlify.virtualmouse.VirtualMouseHandler
import java.util.Optional
import kotlin.math.abs
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier
import org.polyfrost.compose.host.controller.ControllerButton
import org.polyfrost.compose.minecraft.screen.ComposeScreen

//? if >= 26.3 {
import org.lwjgl.sdl.SDLScancode.SDL_SCANCODE_APPLICATION
//?} else
//import org.lwjgl.glfw.GLFW.GLFW_KEY_MENU

internal class ComposeScreenProcessor(screen: ComposeScreen) : ScreenProcessor<ComposeScreen>(screen) {
    private class Direction(val binding: InputBindingSupplier, val dpad: Identifier, val focus: FocusDirection, val arrow: Int)

    private val directions = listOf(
        Direction(ControlifyBindings.GUI_NAVI_UP, GamepadInputs.DPAD_UP_BUTTON, FocusDirection.Up, InputConstants.KEY_UP),
        Direction(ControlifyBindings.GUI_NAVI_DOWN, GamepadInputs.DPAD_DOWN_BUTTON, FocusDirection.Down, InputConstants.KEY_DOWN),
        Direction(ControlifyBindings.GUI_NAVI_LEFT, GamepadInputs.DPAD_LEFT_BUTTON, FocusDirection.Left, InputConstants.KEY_LEFT),
        Direction(ControlifyBindings.GUI_NAVI_RIGHT, GamepadInputs.DPAD_RIGHT_BUTTON, FocusDirection.Right, InputConstants.KEY_RIGHT),
    )

    override fun handleComponentNavigation(controller: ControllerEntity) {
        screen.controllerKeepFocus()
        val input = controller.input().orElse(null) ?: return
        val now = input.stateNow()
        val then = input.stateThen()
        val canRepeat = holdRepeatHelper.canNavigate()
        for (direction in directions) {
            val binding = direction.binding.onOrNull(controller)
            if (binding?.digitalNow() != true && !now.isButtonDown(direction.dpad)) continue
            val wasDown = binding?.digitalPrev() == true || then.isButtonDown(direction.dpad)
            if (wasDown && !canRepeat) return
            if (!wasDown) holdRepeatHelper.reset()
            if (screen.controllerNavigate(direction.focus, direction.arrow)) {
                holdRepeatHelper.onNavigate()
                input.notifyGuiPressOutputsOfNavigate()
            }
            return
        }
    }

    override fun handleButtons(controller: ControllerEntity) {
        val press = ControlifyBindings.GUI_PRESS.on(controller)
        val back = ControlifyBindings.GUI_BACK.on(controller)
        val pressed = press.guiPressed().get()
        val backPressed = back.guiPressed().get()
        val guarded = screen.swallowControllerPress(pressed || backPressed || press.digitalNow() || back.digitalNow())
        if (!guarded && pressed && !ComposeKeyboard.tryOpen(screen, controller)) screen.controllerPress()
        if (!guarded && backPressed) {
            if (!screen.controllerBack() && screen.shouldCloseOnEsc()) {
                playClackSound()
                screen.onClose()
            }
        }
        if (!guarded) {
            for (button in ComposeGuiBindings.ACTION_BUTTONS) {
                if (!ComposeGuiBindings.justPressed(controller, button)) continue
                if (!screen.controllerButton(button) && button == ControllerButton.X) screen.controllerTap(MENU_KEY)
            }
        }
        val scroll = stickScroll(controller)
        if (scroll != Offset.Zero) screen.controllerScroll(scroll * SCROLL_STEP)
    }

    override fun handleTabNavigation(controller: ControllerEntity) {
        val state = controller.input().orElse(null)
        for (button in ComposeGuiBindings.TAB_BUTTONS) {
            val pressed = ComposeGuiBindings.justPressed(controller, button) ||
                state != null && fallbackTrigger(button, state.stateNow(), state.stateThen())
            if (!pressed || screen.controllerButton(button)) continue
            val forward = button == ControllerButton.RIGHT_BUMPER || button == ControllerButton.RIGHT_TRIGGER
            screen.controllerMoveFocus(if (forward) FocusDirection.Next else FocusDirection.Previous)
            return
        }
    }

    override fun render(
        controller: ControllerEntity,
        graphics: GuiGraphicsExtractor,
        tickDelta: Float,
        vmouse: Optional<VirtualMouseHandler>,
    ) {
        if (vmouse.isPresent || !controller.settings().generic.guide.showScreenGuides) return
        val font = Minecraft.getInstance().font
        val line = Component.empty()
        line.hint(ControlifyBindings.GUI_PRESS.inputGlyph(), SELECT)
        line.hint(ControlifyBindings.GUI_BACK.inputGlyph(), BACK)
        for (hint in screen.controllerHints()) {
            val glyph = ComposeGuiBindings.glyph(hint.button) ?: continue
            line.hint(glyph, Component.literal(hint.label))
        }
        line.append(ControlifyBindings.GUI_SECONDARY_NAVI_DOWN.inputGlyph()).append(" ").append(SCROLL)
        val y = screen.height - font.lineHeight - GUIDE_MARGIN
        //? if >= 26.1 {
        graphics.text(font, line, GUIDE_MARGIN, y, -1, true)
        //?} else
        //graphics.drawString(font, line, GUIDE_MARGIN, y, -1, true)
    }

    private fun MutableComponent.hint(glyph: Component, label: Component) {
        append(glyph).append(" ").append(label).append("   ")
    }

    private fun fallbackTrigger(button: ControllerButton, now: ControllerStateView, then: ControllerStateView): Boolean {
        if (ComposeGuiBindings.hasBinding(button)) return false
        val axis = when (button) {
            ControllerButton.LEFT_TRIGGER -> GamepadInputs.LEFT_TRIGGER_AXIS
            ControllerButton.RIGHT_TRIGGER -> GamepadInputs.RIGHT_TRIGGER_AXIS
            else -> return false
        }
        return now.getAxisState(axis) > TRIGGER_THRESHOLD && then.getAxisState(axis) <= TRIGGER_THRESHOLD
    }

    private fun stickScroll(controller: ControllerEntity): Offset {
        val x = ControlifyBindings.GUI_SECONDARY_NAVI_RIGHT.on(controller).analogueNow() -
            ControlifyBindings.GUI_SECONDARY_NAVI_LEFT.on(controller).analogueNow()
        val y = ControlifyBindings.GUI_SECONDARY_NAVI_DOWN.on(controller).analogueNow() -
            ControlifyBindings.GUI_SECONDARY_NAVI_UP.on(controller).analogueNow()
        return Offset(x * abs(x), y * abs(y))
    }

    private companion object {
        const val SCROLL_STEP = 60f
        const val TRIGGER_THRESHOLD = 0.5f
        const val GUIDE_MARGIN = 4

        //? if >= 26.3 {
        const val MENU_KEY = SDL_SCANCODE_APPLICATION
        //?} else
        //const val MENU_KEY = GLFW_KEY_MENU

        val SELECT: Component = Component.translatableWithFallback("polycompose.controller.select", "Select")
        val BACK: Component = Component.translatable("gui.back")
        val SCROLL: Component = Component.translatableWithFallback("polycompose.controller.scroll", "Scroll")
    }
}
//?}
