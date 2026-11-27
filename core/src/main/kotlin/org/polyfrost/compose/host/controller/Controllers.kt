package org.polyfrost.compose.host.controller

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.KeyEvent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.apache.logging.log4j.LogManager
import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.host.HostErrors

public object Controllers {
    private val LOGGER = LogManager.getLogger("PolyCompose/Controller")

    private val ID_PATTERN = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

    @Volatile
    private var backend: ControllerBackend? = null

    private val actions = CopyOnWriteArrayList<ControllerAction>()

    private val watched = CopyOnWriteArraySet<ControllerAction>()

    private val active = mutableStateOf(false)

    private val lookCaptures = AtomicInteger()

    private val gameplayCaptures = AtomicInteger()

    private var dispatchDepth = 0

    @JvmStatic
    public val isAvailable: Boolean
        get() = backend != null

    @JvmStatic
    public val isActive: Boolean
        get() = active.value

    @JvmStatic
    public val isDispatching: Boolean
        get() = dispatchDepth > 0

    @JvmStatic
    public val isLookCaptured: Boolean
        get() = lookCaptures.get() > 0 || gameplayCaptures.get() > 0

    @JvmStatic
    public val isGameplayCaptured: Boolean
        get() = gameplayCaptures.get() > 0

    @JvmStatic
    public val leftStick: Offset
        get() = stick(false)

    @JvmStatic
    public val rightStick: Offset
        get() = stick(true)

    @JvmStatic
    @JvmOverloads
    public fun register(
        id: String,
        name: String,
        defaultButton: ControllerButton = ControllerButton.NONE,
        category: String = id.substringBefore(':'),
        radial: Boolean = false,
        context: ControllerContext = ControllerContext.Gameplay,
    ): ControllerAction {
        require(ID_PATTERN.matches(id)) { "Controller action id must look like namespace:path, got '$id'" }
        val action = ControllerAction(ControllerActionSpec(id, name, defaultButton, category, radial, context))
        actions += action
        backend?.let { bind(it, action) }
        return action
    }

    @JvmStatic
    public fun captureLook(): AutoCloseable = capture(lookCaptures)

    @JvmStatic
    public fun captureGameplay(): AutoCloseable = capture(gameplayCaptures)

    @ApiStatus.Internal
    @JvmStatic
    public fun install(backend: ControllerBackend) {
        this.backend = backend
        for (action in actions) bind(backend, action)
    }

    @ApiStatus.Internal
    @JvmStatic
    public fun reportActive(active: Boolean) {
        if (this.active.value != active) this.active.value = active
    }

    @ApiStatus.Internal
    @JvmStatic
    public fun tick() {
        if (watched.isEmpty()) return
        for (action in watched) action.tick()
    }

    @ApiStatus.Internal
    @JvmStatic
    public inline fun <T> dispatching(block: () -> T): T {
        enterDispatch()
        try {
            return block()
        } finally {
            exitDispatch()
        }
    }

    @PublishedApi
    internal fun enterDispatch() {
        dispatchDepth++
    }

    @PublishedApi
    internal fun exitDispatch() {
        dispatchDepth--
    }

    internal fun watch(action: ControllerAction) {
        watched += action
    }

    internal fun unwatch(action: ControllerAction) {
        watched -= action
    }

    internal fun forget(action: ControllerAction) {
        actions -= action
        watched -= action
    }

    private fun bind(backend: ControllerBackend, action: ControllerAction) {
        if (action.state != null) return
        val spec = action.spec
        action.state = try {
            backend.register(spec)
        } catch (t: Throwable) {
            HostErrors.warn("Could not register controller action ${spec.id}", t, LOGGER)
            null
        }
    }

    private fun capture(counter: AtomicInteger): AutoCloseable {
        counter.incrementAndGet()
        val open = AtomicBoolean(true)
        return AutoCloseable { if (open.compareAndSet(true, false)) counter.decrementAndGet() }
    }

    private fun stick(right: Boolean): Offset {
        val current = backend ?: return Offset.Zero
        return HostCallbacks.call(current, "ControllerBackend.stick", Offset.Zero) { current.stick(right) }
    }
}

public val KeyEvent.isFromController: Boolean
    get() = Controllers.isDispatching

public class ControllerActionSpec @JvmOverloads constructor(
    public val id: String,
    public val name: String,
    public val defaultButton: ControllerButton,
    public val category: String,
    public val radial: Boolean,
    public val context: ControllerContext = ControllerContext.Gameplay,
)

@ApiStatus.Internal
public interface ControllerBackend {
    public fun register(spec: ControllerActionSpec): ControllerActionState

    public fun stick(right: Boolean): Offset
}

@ApiStatus.Internal
public interface ControllerActionState {
    public val isRebindable: Boolean

    public fun isDown(): Boolean

    public fun justPressed(): Boolean
}
