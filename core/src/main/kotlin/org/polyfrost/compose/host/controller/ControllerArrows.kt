package org.polyfrost.compose.host.controller

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import org.jetbrains.annotations.ApiStatus

public enum class ControllerAxes {
    Horizontal,
    Vertical,
    Both,
    ;

    public fun covers(direction: FocusDirection): Boolean = when (this) {
        Horizontal -> direction == FocusDirection.Left || direction == FocusDirection.Right
        Vertical -> direction == FocusDirection.Up || direction == FocusDirection.Down
        Both -> direction == FocusDirection.Left || direction == FocusDirection.Right ||
            direction == FocusDirection.Up || direction == FocusDirection.Down
    }
}

@ApiStatus.Internal
public val ControllerArrowsKey: SemanticsPropertyKey<ControllerAxes> = SemanticsPropertyKey("ControllerArrows")

public fun Modifier.controllerArrows(axes: ControllerAxes = ControllerAxes.Both): Modifier =
    semantics { this[ControllerArrowsKey] = axes }
