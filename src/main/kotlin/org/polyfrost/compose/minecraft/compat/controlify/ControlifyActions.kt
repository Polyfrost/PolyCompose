package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import androidx.compose.ui.geometry.Offset
import dev.isxander.controlify.api.ControlifyApi
import dev.isxander.controlify.api.bind.ControlifyBindApi
import dev.isxander.controlify.api.bind.InputBinding
import dev.isxander.controlify.api.bind.InputBindingSupplier
import dev.isxander.controlify.bindings.BindContext
//? if < 26.1
//import dev.isxander.controlify.bindings.RadialIcons
import dev.isxander.controlify.bindings.input.AxisInput
import dev.isxander.controlify.bindings.input.ButtonInput
import dev.isxander.controlify.bindings.input.EmptyInput
import dev.isxander.controlify.bindings.input.Input
import dev.isxander.controlify.controller.ControllerEntity
import dev.isxander.controlify.controller.input.GamepadInputs
import dev.isxander.controlify.controller.input.InputComponent
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.host.controller.ControllerActionSpec
import org.polyfrost.compose.host.controller.ControllerActionState
import org.polyfrost.compose.host.controller.ControllerBackend
import org.polyfrost.compose.host.controller.ControllerButton
import org.polyfrost.compose.host.controller.ControllerContext
import org.polyfrost.compose.minecraft.platform.LoadedMods

internal object ControlifyActions : ControllerBackend {
    private val LOGGER = LogManager.getLogger("PolyCompose/Controller")

    private const val DIGITAL_THRESHOLD = 0.5f

    private val bindings = HashMap<String, InputBindingSupplier>()

    private val actionIds: MutableSet<Any> = Collections.newSetFromMap(ConcurrentHashMap())

    @Volatile
    private var locked = false

    override fun register(spec: ControllerActionSpec): ControllerActionState {
        val supplier = synchronized(bindings) {
            bindings[spec.id] ?: tryRegister(spec)?.also {
                bindings[spec.id] = it
                actionIds += it.bindId()
            }
        }
        return if (supplier != null) BindingState(supplier) else RawState(inputFor(spec.defaultButton), bindContext(spec.context))
    }

    fun isAction(binding: InputBinding): Boolean = binding.id() in actionIds

    override fun stick(right: Boolean): Offset {
        val state = currentController()?.input()?.orElse(null)?.stateNow() ?: return Offset.Zero
        return if (right) {
            Offset(
                state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_RIGHT) - state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_LEFT),
                state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_DOWN) - state.getAxisState(GamepadInputs.RIGHT_STICK_AXIS_UP),
            )
        } else {
            Offset(
                state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_RIGHT) - state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_LEFT),
                state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_DOWN) - state.getAxisState(GamepadInputs.LEFT_STICK_AXIS_UP),
            )
        }
    }

    fun registerGuiBinding(id: String, name: String, button: ControllerButton): InputBindingSupplier? =
        tryRegister(ControllerActionSpec(id, name, button, "polycompose.controller.category", false, ControllerContext.Screens))

    private fun bindContext(context: ControllerContext): BindContext? = when (context) {
        ControllerContext.Gameplay -> BindContext.IN_GAME
        ControllerContext.Screens -> BindContext.ANY_SCREEN
        ControllerContext.Anywhere -> null
    }

    @Suppress("DEPRECATION")
    private fun tryRegister(spec: ControllerActionSpec): InputBindingSupplier? {
        if (locked) return null
        val namespace = spec.id.substringBefore(':')
        val path = spec.id.substringAfter(':')
        return try {
            ControlifyBindApi.get().registerBinding { builder ->
                builder.id(namespace, path)
                    .name(Component.translatable(spec.name))
                    .category(Component.translatableWithFallback(spec.category, LoadedMods.name(spec.category) ?: spec.category))
                    .defaultInput(inputFor(spec.defaultButton))
                bindContext(spec.context)?.let { builder.allowedContexts(it) }
                //? if >= 26.1 {
                if (spec.radial) builder.radialCandidate(true)
                //?} else
                //if (spec.radial) builder.radialCandidate(RadialIcons.EMPTY)
                builder
            }
        } catch (e: IllegalStateException) {
            LOGGER.debug("Controlify bindings are locked, {} reads its default button directly", spec.id, e)
            locked = true
            null
        }
    }

    fun currentController(): ControllerEntity? = ControlifyApi.get().currentController.orElse(null)

    private fun inputFor(button: ControllerButton): Input = when (button) {
        ControllerButton.NONE -> EmptyInput()
        ControllerButton.A -> ButtonInput(GamepadInputs.SOUTH_BUTTON)
        ControllerButton.B -> ButtonInput(GamepadInputs.EAST_BUTTON)
        ControllerButton.X -> ButtonInput(GamepadInputs.WEST_BUTTON)
        ControllerButton.Y -> ButtonInput(GamepadInputs.NORTH_BUTTON)
        ControllerButton.LEFT_BUMPER -> ButtonInput(GamepadInputs.LEFT_SHOULDER_BUTTON)
        ControllerButton.RIGHT_BUMPER -> ButtonInput(GamepadInputs.RIGHT_SHOULDER_BUTTON)
        ControllerButton.LEFT_TRIGGER -> AxisInput(GamepadInputs.LEFT_TRIGGER_AXIS)
        ControllerButton.RIGHT_TRIGGER -> AxisInput(GamepadInputs.RIGHT_TRIGGER_AXIS)
        ControllerButton.LEFT_STICK -> ButtonInput(GamepadInputs.LEFT_STICK_BUTTON)
        ControllerButton.RIGHT_STICK -> ButtonInput(GamepadInputs.RIGHT_STICK_BUTTON)
        ControllerButton.BACK -> ButtonInput(GamepadInputs.BACK_BUTTON)
        ControllerButton.START -> ButtonInput(GamepadInputs.START_BUTTON)
        ControllerButton.DPAD_UP -> ButtonInput(GamepadInputs.DPAD_UP_BUTTON)
        ControllerButton.DPAD_DOWN -> ButtonInput(GamepadInputs.DPAD_DOWN_BUTTON)
        ControllerButton.DPAD_LEFT -> ButtonInput(GamepadInputs.DPAD_LEFT_BUTTON)
        ControllerButton.DPAD_RIGHT -> ButtonInput(GamepadInputs.DPAD_RIGHT_BUTTON)
    }

    private class BindingState(private val supplier: InputBindingSupplier) : ControllerActionState {
        override val isRebindable: Boolean
            get() = true

        private fun binding(): InputBinding? = currentController()?.let { supplier.onOrNull(it) }

        override fun isDown(): Boolean = binding()?.digitalNow() == true

        override fun justPressed(): Boolean = binding()?.justPressed() == true
    }

    private class RawState(private val input: Input, private val context: BindContext?) : ControllerActionState {
        override val isRebindable: Boolean
            get() = false

        private fun inputs(): InputComponent? {
            if (context != null && context.isApplicable().apply(Minecraft.getInstance()) != true) return null
            return currentController()?.input()?.orElse(null)
        }

        override fun isDown(): Boolean {
            if (input is EmptyInput) return false
            val component = inputs() ?: return false
            return input.state(component.stateNow()) > DIGITAL_THRESHOLD
        }

        override fun justPressed(): Boolean {
            if (input is EmptyInput) return false
            val component = inputs() ?: return false
            return input.state(component.stateNow()) > DIGITAL_THRESHOLD &&
                input.state(component.stateThen()) <= DIGITAL_THRESHOLD
        }
    }
}
//?}
