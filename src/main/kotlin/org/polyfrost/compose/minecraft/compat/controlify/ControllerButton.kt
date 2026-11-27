package org.polyfrost.compose.minecraft.compat.controlify

import org.polyfrost.compose.host.controller.ControllerButton as CoreControllerButton

@Deprecated("Use org.polyfrost.compose.host.controller.ControllerButton")
enum class ControllerButton {
    NONE,
    A,
    B,
    X,
    Y,
    LEFT_BUMPER,
    RIGHT_BUMPER,
    LEFT_TRIGGER,
    RIGHT_TRIGGER,
    LEFT_STICK,
    RIGHT_STICK,
    BACK,
    START,
    DPAD_UP,
    DPAD_DOWN,
    DPAD_LEFT,
    DPAD_RIGHT,
    ;

    internal fun toCore(): CoreControllerButton = CoreControllerButton.valueOf(name)
}
