@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.minecraft.compat.controlify

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import java.lang.reflect.Field
import org.polyfrost.compose.host.controller.ControllerArrowsKey
import org.polyfrost.compose.host.controller.ControllerAxes
import org.polyfrost.compose.host.controller.ControllerPanKey
import org.polyfrost.compose.host.controller.FocusEntryKey
import org.polyfrost.compose.host.controller.HidesFocusRingKey
import org.polyfrost.compose.host.controller.ShownGroupKey

internal object SceneFocus {
    private val ownerField: Field? by lazy {
        runCatching {
            ComposeSceneFocusManager::class.java.getDeclaredField("focusOwner").apply { isAccessible = true }
        }.getOrNull()
    }

    fun of(manager: ComposeSceneFocusManager): FocusManager? = runCatching {
        (ownerField?.get(manager) as? Function0<*>)?.invoke() as? FocusManager
    }.getOrNull()
}

internal class SceneSemantics {
    private val owners = ArrayList<SemanticsOwner>()

    private val listener = object : PlatformContext.SemanticsOwnerListener {
        override fun onSemanticsOwnerAppended(semanticsOwner: SemanticsOwner) {
            owners += semanticsOwner
        }

        override fun onSemanticsOwnerRemoved(semanticsOwner: SemanticsOwner) {
            owners -= semanticsOwner
        }

        override fun onSemanticsChange(semanticsOwner: SemanticsOwner) = Unit

        override fun onLayoutChange(semanticsOwner: SemanticsOwner, semanticsNodeId: Int) = Unit
    }

    fun wrap(context: PlatformContext): PlatformContext = object : PlatformContext by context {
        override val semanticsOwnerListener: PlatformContext.SemanticsOwnerListener get() = listener
    }

    fun focusedMark(): FocusMark? {
        for (owner in listOfNotNull(activeOwner())) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = false)) {
                if (!node.config.getOrElse(SemanticsProperties.Focused) { false }) continue
                val bounds = node.boundsInWindow
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                return FocusMark(owner, bounds)
            }
        }
        return null
    }

    fun isAlive(owner: SemanticsOwner): Boolean = owner in owners

    fun activeLayers(): List<LayoutCoordinates>? {
        val layers = owners.toList()
        val active = activeOwner() ?: layers.lastOrNull() ?: return null
        val from = layers.indexOf(active)
        if (from < 0) return null
        return layers.subList(from, layers.size).map { it.unmergedRootSemanticsNode.layoutInfo.coordinates }
    }

    fun focusedNode(): FocusedNode? {
        val owner = activeOwner() ?: return null
        val node = owner.getAllSemanticsNodes(mergingEnabled = false).firstOrNull {
            it.config.getOrElse(SemanticsProperties.Focused) { false } && it.boundsInWindow.width > 0f && it.boundsInWindow.height > 0f
        } ?: return null
        return FocusedNode(owner, node.id)
    }

    private fun activeOwner(): SemanticsOwner? = owners.toList().asReversed().firstOrNull { owner ->
        owner.getAllSemanticsNodes(mergingEnabled = false).any { it.config.getOrElse(SemanticsProperties.Focused) { false } }
    }

    fun moveSpatially(direction: FocusDirection, scroll: Boolean = true): Int {
        for (owner in listOfNotNull(activeOwner())) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = false)) {
                if (!node.config.getOrElse(SemanticsProperties.Focused) { false }) continue
                val bounds = node.boundsInWindow
                if (bounds.width <= 0f || bounds.height <= 0f) continue
                val claimed = focusedArrows()?.covers(direction) == true
                return SpatialNavigation.move(owner, bounds, node, direction, scroll, enter = !claimed)
            }
        }
        return SpatialNavigation.NONE
    }

    fun focusNearest(owner: SemanticsOwner, near: Rect): Boolean {
        val candidates = ArrayList<Pair<Float, SemanticsNode>>()
        for (node in owner.getAllSemanticsNodes(mergingEnabled = false)) {
            if (SemanticsActions.RequestFocus !in node.config) continue
            val bounds = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            candidates += focusDistance(bounds, near) to node
        }
        candidates.sortBy { it.first }
        for ((_, node) in candidates.take(MAX_FOCUS_ATTEMPTS)) {
            val action = node.config.getOrNull(SemanticsActions.RequestFocus)?.action ?: continue
            if (action()) return true
        }
        return false
    }

    fun focusFirstIn(token: Any): Boolean {
        for (owner in owners.toList().asReversed()) {
            val all = owner.getAllSemanticsNodes(mergingEnabled = false)
            val group = all.firstOrNull { it.config.getOrNull(ShownGroupKey) === token } ?: continue
            val inside = all
                .filter { SemanticsActions.RequestFocus in it.config && (it.id == group.id || isInside(group, it)) }
                .filter { val b = it.boundsInWindow; b.width > 0f && b.height > 0f }
                .sortedWith(compareBy({ it.boundsInWindow.top }, { it.boundsInWindow.left }))
            for (node in inside.take(MAX_FOCUS_ATTEMPTS)) {
                if (entryWithin(group, node)?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return true
                val action = node.config.getOrNull(SemanticsActions.RequestFocus)?.action ?: continue
                if (action()) return true
            }
        }
        return false
    }

    private fun entryWithin(group: SemanticsNode, node: SemanticsNode): FocusRequester? {
        if (node.id == group.id) return null
        var parent = node.parent
        while (parent != null) {
            parent.config.getOrNull(FocusEntryKey)?.let { return it }
            if (parent.id == group.id) return null
            parent = parent.parent
        }
        return null
    }

    private fun isInside(group: SemanticsNode, node: SemanticsNode): Boolean {
        var parent = node.parent
        while (parent != null) {
            if (parent.id == group.id) return true
            parent = parent.parent
        }
        return false
    }

    fun focusAt(owner: SemanticsOwner, at: Rect): Boolean {
        for (node in owner.getAllSemanticsNodes(mergingEnabled = false)) {
            if (SemanticsActions.RequestFocus !in node.config) continue
            if (!sameSpot(node.boundsInWindow, at)) continue
            val action = node.config.getOrNull(SemanticsActions.RequestFocus)?.action ?: continue
            if (action()) return true
        }
        return false
    }

    private fun sameSpot(a: Rect, b: Rect): Boolean {
        if (a.width <= 0f || a.height <= 0f) return false
        val overlap = a.intersect(b)
        if (overlap.width <= 0f || overlap.height <= 0f) return false
        val area = overlap.width * overlap.height
        return area >= SAME_SPOT * a.width * a.height && area >= SAME_SPOT * b.width * b.height
    }

    private fun focusDistance(bounds: Rect, near: Rect): Float {
        val dx = maxOf(0f, maxOf(bounds.left, near.left) - minOf(bounds.right, near.right))
        val dy = maxOf(0f, maxOf(bounds.top, near.top) - minOf(bounds.bottom, near.bottom))
        val centre = (bounds.center - near.center).getDistance()
        return (dx * dx + dy * dy) * 4f + centre
    }

    fun forEachTarget(action: (Rect) -> Unit) {
        for (owner in owners.toList()) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                val interactive = SemanticsActions.OnClick in config ||
                    SemanticsActions.SetText in config ||
                    SemanticsProperties.Focused in config
                if (!interactive) continue
                val bounds = node.boundsInWindow
                if (bounds.width > 0f && bounds.height > 0f) action(bounds)
            }
        }
    }

    fun focusedHidesRing(): Boolean {
        for (owner in listOfNotNull(activeOwner())) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                if (!config.getOrElse(SemanticsProperties.Focused) { false }) continue
                if (HidesFocusRingKey in config) return true
            }
        }
        return false
    }

    fun focusedArrows(): ControllerAxes? = focusedClaim(ControllerArrowsKey, merged = true)

    fun focusedPan(): ((Offset) -> Unit)? = focusedClaim(ControllerPanKey, merged = false)

    private fun <T> focusedClaim(key: SemanticsPropertyKey<T>, merged: Boolean): T? {
        val owner = activeOwner() ?: return null
        if (merged) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                if (!config.getOrElse(SemanticsProperties.Focused) { false }) continue
                config.getOrNull(key)?.let { return it }
            }
        }
        for (node in owner.getAllSemanticsNodes(mergingEnabled = false)) {
            if (!node.config.getOrElse(SemanticsProperties.Focused) { false }) continue
            var current: SemanticsNode? = node
            while (current != null) {
                current.config.getOrNull(key)?.let { return it }
                current = current.parent
            }
        }
        return null
    }

    fun focusedTextField(): Rect? {
        for (owner in listOfNotNull(activeOwner())) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                if (SemanticsActions.SetText !in config) continue
                if (!config.getOrElse(SemanticsProperties.Focused) { false }) continue
                return node.boundsInWindow
            }
        }
        return null
    }

    fun focusedField(): FieldPreview? {
        for (owner in listOfNotNull(activeOwner())) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                if (SemanticsActions.SetText !in config) continue
                if (!config.getOrElse(SemanticsProperties.Focused) { false }) continue
                return preview(config)
            }
        }
        return null
    }

    private fun preview(config: SemanticsConfiguration): FieldPreview {
        val value = config.getOrNull(SemanticsProperties.InputText) ?: config.getOrNull(SemanticsProperties.EditableText)
        val text = value?.text.orEmpty()
        val hint = config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }?.takeIf { it.isNotBlank() }
            ?: config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ")?.takeIf { it.isNotBlank() }
        return FieldPreview(
            text = text,
            selection = config.getOrNull(SemanticsProperties.TextSelectionRange),
            password = SemanticsProperties.Password in config,
            hint = hint,
        )
    }
}

private const val MAX_FOCUS_ATTEMPTS = 8

private const val SAME_SPOT = 0.6f

internal class FocusMark(val owner: SemanticsOwner, val bounds: Rect)

internal data class FocusedNode(val owner: SemanticsOwner, val id: Int)

internal data class FieldPreview(
    val text: String,
    val selection: TextRange?,
    val password: Boolean,
    val hint: String?,
)
