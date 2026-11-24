package org.polyfrost.compose.host

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset

public open class ComposeOptions {
    public open val animationScale: Float get() = 1f

    public open val reducedMotion: Boolean get() = false

    public open val messages: HostMessages get() = HostMessages.Default

    @Composable
    public open fun Decorate(owner: Any, content: @Composable () -> Unit) {
        content()
    }

    public open fun onSceneCreated(owner: Any, kind: SceneKind) {}

    public open fun onFrame(owner: Any): Boolean = false

    public open fun onPointer(owner: Any, position: Offset?) {}

    public open fun onInputKind(owner: Any, keyboard: Boolean) {}

    public open fun onInputKind(owner: Any, kind: InputKind) {
        onInputKind(owner, kind != InputKind.Pointer)
    }

    public open fun onRemoved(owner: Any) {}

    public open val isCapturingInput: Boolean get() = false

    public open fun consumeEscape(owner: Any): Boolean = false

    public open fun consumeMouse(owner: Any, button: Int, pressed: Boolean): Boolean = false

    public open fun createWheelHandler(kind: SceneKind): WheelHandler? = null

    public companion object {
        @JvmField
        public val Default: ComposeOptions = ComposeOptions()
    }
}

public enum class SceneKind { Screen, HudLayer }

public val LocalComposeOptions: ProvidableCompositionLocal<ComposeOptions> = staticCompositionLocalOf { ComposeOptions.Default }
