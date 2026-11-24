package org.polyfrost.compose.minecraft.compat.controlify

//? if > 1.8.9 {
import dev.isxander.controlify.api.bind.InputBinding
import dev.isxander.controlify.bindings.BindContext
import dev.isxander.controlify.bindings.ControlifyBindings
import dev.isxander.controlify.ingame.ControllerPlayerMovement
import dev.isxander.controlify.ingame.DualInput
import java.lang.reflect.Field
import net.minecraft.client.Minecraft
import org.polyfrost.compose.host.controller.Controllers
import org.polyfrost.compose.minecraft.platform.McScreens
//? if >= 1.21.2
import net.minecraft.world.entity.player.Input

internal object GameplayCapture {
    @JvmStatic
    fun blocksGameplay(): Boolean = Controllers.isGameplayCaptured && McScreens.current() == null

    private val dualInputs: List<Field> by lazy {
        runCatching { DualInput::class.java.declaredFields.onEach { it.isAccessible = true }.toList() }.getOrDefault(emptyList())
    }

    fun releaseSneak() {
        if (!blocksGameplay()) return
        for (input in controllerInputs(Minecraft.getInstance().player?.input)) {
            //? if >= 1.21.2 {
            val keys = input.keyPresses
            if (keys.shift()) input.keyPresses = Input(keys.forward(), keys.backward(), keys.left(), keys.right(), keys.jump(), false, keys.sprint())
            //?} else
            //input.shiftKeyDown = false
        }
    }

    private fun controllerInputs(input: Any?): List<ControllerPlayerMovement> = when (input) {
        is ControllerPlayerMovement -> listOf(input)
        is DualInput -> dualInputs.mapNotNull { runCatching { it.get(input) }.getOrNull() as? ControllerPlayerMovement }
        else -> emptyList()
    }

    @JvmStatic
    fun suppresses(binding: InputBinding): Boolean =
        blocksGameplay() &&
            BindContext.IN_GAME in binding.contexts() &&
            binding.id() != ControlifyBindings.PAUSE.bindId() &&
            !ControlifyActions.isAction(binding)
}
//?}
