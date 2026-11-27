package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import dev.isxander.controlify.api.bind.InputBindingSupplier
import dev.isxander.controlify.bindings.ControlifyBindings
import dev.isxander.controlify.bindings.input.EmptyInput
import dev.isxander.controlify.controller.ControllerEntity
import java.util.EnumMap
import net.minecraft.network.chat.Component
import org.polyfrost.compose.host.controller.ControllerButton

internal object ComposeGuiBindings {
    val ACTION_BUTTONS = listOf(
        ControllerButton.X,
        ControllerButton.Y,
        ControllerButton.LEFT_STICK,
        ControllerButton.RIGHT_STICK,
        ControllerButton.BACK,
        ControllerButton.START,
    )

    val TAB_BUTTONS = listOf(
        ControllerButton.LEFT_BUMPER,
        ControllerButton.RIGHT_BUMPER,
        ControllerButton.LEFT_TRIGGER,
        ControllerButton.RIGHT_TRIGGER,
    )

    private val registered = EnumMap<ControllerButton, InputBindingSupplier>(ControllerButton::class.java)

    fun registerAll() {
        register(ControllerButton.LEFT_TRIGGER, "gui_left_trigger")
        register(ControllerButton.RIGHT_TRIGGER, "gui_right_trigger")
        register(ControllerButton.RIGHT_STICK, "gui_right_stick")
        register(ControllerButton.START, "gui_start")
        register(ControllerButton.BACK, "gui_back", ControllerButton.NONE)
    }

    private fun register(button: ControllerButton, path: String, defaultButton: ControllerButton = button) {
        ControlifyActions.registerGuiBinding("polycompose:$path", "polycompose.binding.$path", defaultButton)?.let { registered[button] = it }
    }

    fun hasBinding(button: ControllerButton): Boolean = supplier(button) != null

    fun justPressed(controller: ControllerEntity, button: ControllerButton): Boolean =
        supplier(button)?.onOrNull(controller)?.justPressed() == true

    fun glyph(controller: ControllerEntity, button: ControllerButton): Component? {
        val supplier = supplier(button) ?: return null
        if (supplier.onOrNull(controller)?.boundInput() is EmptyInput) return null
        return supplier.inputGlyph()
    }

    private fun supplier(button: ControllerButton): InputBindingSupplier? = when (button) {
        ControllerButton.A -> ControlifyBindings.GUI_PRESS
        ControllerButton.B -> ControlifyBindings.GUI_BACK
        ControllerButton.X -> ControlifyBindings.GUI_ABSTRACT_ACTION_1
        ControllerButton.Y -> ControlifyBindings.GUI_ABSTRACT_ACTION_2
        ControllerButton.LEFT_STICK -> ControlifyBindings.GUI_ABSTRACT_ACTION_3
        ControllerButton.LEFT_BUMPER -> ControlifyBindings.GUI_PREV_TAB
        ControllerButton.RIGHT_BUMPER -> ControlifyBindings.GUI_NEXT_TAB
        ControllerButton.DPAD_UP -> ControlifyBindings.GUI_NAVI_UP
        ControllerButton.DPAD_DOWN -> ControlifyBindings.GUI_NAVI_DOWN
        ControllerButton.DPAD_LEFT -> ControlifyBindings.GUI_NAVI_LEFT
        ControllerButton.DPAD_RIGHT -> ControlifyBindings.GUI_NAVI_RIGHT
        else -> registered[button]
    }
}
//?}
