@file:Suppress("DEPRECATION")

package org.polyfrost.compose.minecraft.compat.controlify

import org.polyfrost.compose.host.controller.Controllers
import org.polyfrost.compose.host.controller.ControllerAction as CoreControllerAction

@Deprecated("Use org.polyfrost.compose.host.controller.Controllers")
object ControllerActions {
    @JvmStatic
    val isAvailable: Boolean
        get() = Controllers.isAvailable

    @JvmStatic
    @JvmOverloads
    fun register(
        id: String,
        name: String,
        defaultButton: ControllerButton = ControllerButton.NONE,
        category: String = id.substringBefore(':'),
    ): ControllerAction = ControllerAction(Controllers.register(id, name, defaultButton.toCore(), category), defaultButton)
}

@Deprecated("Use org.polyfrost.compose.host.controller.ControllerAction")
class ControllerAction internal constructor(
    private val delegate: CoreControllerAction,
    val defaultButton: ControllerButton,
) : AutoCloseable {
    val id: String
        get() = delegate.id

    val name: String
        get() = delegate.name

    val isActive: Boolean
        get() = delegate.isActive

    val isRebindable: Boolean
        get() = delegate.isRebindable

    val isPressed: Boolean
        get() = delegate.isPressed

    val justPressed: Boolean
        get() = delegate.justPressed

    fun onPress(listener: Runnable): AutoCloseable = delegate.onPress(listener)

    override fun close() = delegate.close()
}
