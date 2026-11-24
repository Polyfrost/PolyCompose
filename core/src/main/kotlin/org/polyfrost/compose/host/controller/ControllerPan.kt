package org.polyfrost.compose.host.controller

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

public fun Modifier.controllerPan(enabled: Boolean = true, onPan: (Offset) -> Unit): Modifier =
    this then ControllerPanElement(enabled, onPan)

private class ControllerPanElement(
    private val enabled: Boolean,
    private val onPan: (Offset) -> Unit,
) : ModifierNodeElement<ControllerPanNode>() {
    override fun create(): ControllerPanNode = ControllerPanNode(enabled, onPan)

    override fun update(node: ControllerPanNode) {
        node.enabled = enabled
        node.onPan = onPan
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "controllerPan"
        properties["enabled"] = enabled
    }

    override fun equals(other: Any?): Boolean =
        other is ControllerPanElement && enabled == other.enabled && onPan === other.onPan

    override fun hashCode(): Int = 31 * enabled.hashCode() + onPan.hashCode()
}

private class ControllerPanNode(
    var enabled: Boolean,
    var onPan: (Offset) -> Unit,
) : Modifier.Node(), FocusEventModifierNode, PointerInputModifierNode {
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        focused = focusState.hasFocus
    }

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial || !enabled || !focused || !Controllers.isDispatching) return
        if (pointerEvent.type != PointerEventType.Scroll) return
        var delta = Offset.Zero
        for (change in pointerEvent.changes) {
            if (change.isConsumed) return
            delta += change.scrollDelta
        }
        for (change in pointerEvent.changes) change.consume()
        if (delta != Offset.Zero) onPan(delta)
    }

    override fun onCancelPointerInput() = Unit
}
