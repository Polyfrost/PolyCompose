package org.polyfrost.compose.host.controller

import java.util.concurrent.CopyOnWriteArrayList
import org.polyfrost.compose.host.HostCallbacks

public class ControllerAction internal constructor(
    internal val spec: ControllerActionSpec,
) : AutoCloseable {
    private val listeners = CopyOnWriteArrayList<Runnable>()

    @Volatile
    internal var state: ControllerActionState? = null

    @Volatile
    private var closed = false

    public val id: String
        get() = spec.id

    public val name: String
        get() = spec.name

    public val defaultButton: ControllerButton
        get() = spec.defaultButton

    public val isActive: Boolean
        get() = state != null && !closed

    public val isRebindable: Boolean
        get() = isActive && state?.isRebindable == true

    public val isPressed: Boolean
        get() = isActive && state?.isDown() == true

    public val justPressed: Boolean
        get() = isActive && state?.justPressed() == true

    public fun onPress(listener: Runnable): AutoCloseable {
        if (closed) return AutoCloseable {}
        listeners += listener
        Controllers.watch(this)
        return AutoCloseable {
            listeners -= listener
            if (listeners.isEmpty()) Controllers.unwatch(this)
        }
    }

    internal fun tick() {
        if (listeners.isEmpty() || !justPressed) return
        HostCallbacks.each(listeners, "ControllerAction.onPress") { it.run() }
    }

    override fun close() {
        closed = true
        listeners.clear()
        Controllers.forget(this)
    }
}
