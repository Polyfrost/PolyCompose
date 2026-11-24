package org.polyfrost.compose.minecraft.compat.controlify

import java.util.function.Consumer
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.minecraft.screen.ComposeScreen

internal interface ScreenControllerSupport {
    fun collectSnapPoints(screen: ComposeScreen, consumer: Consumer<Any?>)
}

internal object ControllerBridge {
    @Volatile
    var screens: ScreenControllerSupport? = null

    val tracksSemantics: Boolean
        get() = screens != null

    fun collectSnapPoints(screen: ComposeScreen, consumer: Consumer<Any?>) {
        val support = screens ?: return
        HostCallbacks.call(support, "ScreenControllerSupport.collectSnapPoints", Unit) {
            support.collectSnapPoints(screen, consumer)
        }
    }

    @JvmStatic
    fun consumeBack(screen: Any?): Boolean = (screen as? ComposeScreen)?.controllerBack() == true
}
