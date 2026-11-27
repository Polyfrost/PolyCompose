package org.polyfrost.compose.host.controller

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.TraversableNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.InspectorInfo
import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.host.HostCallbacks

public class ControllerHint(
    public val button: ControllerButton,
    public val label: String,
)

@ApiStatus.Internal
public class ControllerTargets {
    private val nodes = ArrayList<ControllerActionNode>()

    private var focusClock = 0L

    public var focusInside: ((Any) -> Boolean)? = null

    public var activeLayers: (() -> List<LayoutCoordinates>?)? = null

    internal fun add(node: ControllerActionNode) {
        nodes += node
    }

    internal fun remove(node: ControllerActionNode) {
        nodes -= node
    }

    internal fun nextFocusTick(): Long = ++focusClock

    public fun handles(button: ControllerButton): Boolean = pick(button, activeRoots()) != null

    public fun dispatch(button: ControllerButton): Boolean {
        val node = pick(button, activeRoots()) ?: return false
        val press = node.onPress
        Controllers.dispatching {
            HostCallbacks.call(press, "Modifier.controllerAction", Unit) { press() }
        }
        return true
    }

    public fun hints(): List<ControllerHint> {
        if (nodes.isEmpty()) return emptyList()
        val roots = activeRoots()
        val hints = ArrayList<ControllerHint>()
        for (button in ControllerButton.entries) {
            if (button == ControllerButton.NONE) continue
            val label = pick(button, roots)?.label ?: continue
            hints += ControllerHint(button, label)
        }
        return hints
    }

    private fun activeRoots(): List<LayoutCoordinates>? {
        val layers = activeLayers ?: return null
        return runCatching { layers()?.map { it.findRootCoordinates() } }.getOrNull()
    }

    private fun pick(button: ControllerButton, roots: List<LayoutCoordinates>?): ControllerActionNode? {
        if (nodes.isEmpty()) return null
        var focused: ArrayList<ControllerActionNode>? = null
        var fallback: ControllerActionNode? = null
        for (node in nodes) {
            if (node.button != button || !node.enabled || !node.isAttached) continue
            if (roots != null && !node.isInLayers(roots)) continue
            if (node.focused) {
                (focused ?: ArrayList<ControllerActionNode>().also { focused = it }) += node
            } else if (!node.requireFocus) {
                fallback = node
            }
        }
        val candidates = focused ?: return fallback
        if (candidates.size == 1) return candidates[0]
        val shadowed = HashSet<ControllerActionNode>()
        for (node in candidates) {
            node.traverseAncestors { ancestor ->
                if (ancestor.button == button) shadowed += ancestor
                true
            }
        }
        return candidates.filter { it !in shadowed }.maxByOrNull { it.focusedAt }
    }
}

public val LocalControllerTargets: ProvidableCompositionLocal<ControllerTargets?> = staticCompositionLocalOf { null }

public fun Modifier.controllerAction(
    button: ControllerButton,
    label: String? = null,
    enabled: Boolean = true,
    requireFocus: Boolean = true,
    onPress: () -> Unit,
): Modifier = this then ControllerActionElement(button, label, enabled, requireFocus, onPress)

private class ControllerActionElement(
    private val button: ControllerButton,
    private val label: String?,
    private val enabled: Boolean,
    private val requireFocus: Boolean,
    private val onPress: () -> Unit,
) : ModifierNodeElement<ControllerActionNode>() {
    override fun create(): ControllerActionNode = ControllerActionNode(button, label, enabled, requireFocus, onPress)

    override fun update(node: ControllerActionNode) {
        node.button = button
        node.label = label
        node.enabled = enabled
        node.requireFocus = requireFocus
        node.onPress = onPress
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "controllerAction"
        properties["button"] = button
        properties["label"] = label
        properties["enabled"] = enabled
        properties["requireFocus"] = requireFocus
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ControllerActionElement) return false
        return button == other.button && label == other.label && enabled == other.enabled &&
            requireFocus == other.requireFocus && onPress === other.onPress
    }

    override fun hashCode(): Int {
        var result = button.hashCode()
        result = 31 * result + (label?.hashCode() ?: 0)
        result = 31 * result + enabled.hashCode()
        result = 31 * result + requireFocus.hashCode()
        result = 31 * result + onPress.hashCode()
        return result
    }
}

internal class ControllerActionNode(
    var button: ControllerButton,
    var label: String?,
    var enabled: Boolean,
    var requireFocus: Boolean,
    var onPress: () -> Unit,
) : Modifier.Node(), FocusEventModifierNode, CompositionLocalConsumerModifierNode, TraversableNode {
    private var targets: ControllerTargets? = null

    var focused = false
        private set

    var focusedAt = 0L
        private set

    override val traverseKey: Any
        get() = TraverseKey

    override fun onAttach() {
        targets = currentValueOf(LocalControllerTargets)?.also { it.add(this) }
    }

    override fun onDetach() {
        targets?.remove(this)
        targets = null
        focused = false
    }

    override fun onFocusEvent(focusState: FocusState) {
        val now = focusState.hasFocus
        if (now && !focused) focusedAt = targets?.nextFocusTick() ?: 0L
        focused = now
    }

    fun isInLayers(roots: List<LayoutCoordinates>): Boolean = runCatching {
        val root = requireLayoutCoordinates().findRootCoordinates()
        roots.any { it === root }
    }.getOrDefault(false)

    private object TraverseKey
}
