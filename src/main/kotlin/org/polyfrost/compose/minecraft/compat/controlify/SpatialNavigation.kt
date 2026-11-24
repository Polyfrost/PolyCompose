package org.polyfrost.compose.minecraft.compat.controlify

import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import org.polyfrost.compose.host.controller.FocusEntryKey

internal object SpatialNavigation {
    private const val MAX_ATTEMPTS = 8
    private const val OVERLAP_TOLERANCE = 0.3f
    private const val ORTHOGONAL_WEIGHT_SIDEWAYS = 30f
    private const val ORTHOGONAL_WEIGHT_VERTICAL = 2f

    fun move(owner: SemanticsOwner, from: Rect, focused: SemanticsNode?, direction: FocusDirection): Boolean {
        if (focused != null && canScrollFurther(focused, direction)) return false
        val nodes = owner.getAllSemanticsNodes(mergingEnabled = false)
        val candidates = ArrayList<Pair<Float, SemanticsNode>>()
        for (node in nodes) {
            if (node.id == focused?.id) continue
            if (SemanticsActions.RequestFocus !in node.config) continue
            val bounds = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val score = score(from, bounds, direction) ?: continue
            candidates += score to node
        }
        candidates.sortBy { it.first }
        for ((_, node) in candidates.take(MAX_ATTEMPTS)) {
            if (focusEntryOf(node, focused)?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return true
            val action = node.config.getOrNull(SemanticsActions.RequestFocus)?.action ?: continue
            if (action()) return true
        }
        return false
    }

    private fun canScrollFurther(node: SemanticsNode, direction: FocusDirection): Boolean {
        val key = when (direction) {
            FocusDirection.Up, FocusDirection.Down -> SemanticsProperties.VerticalScrollAxisRange
            FocusDirection.Left, FocusDirection.Right -> SemanticsProperties.HorizontalScrollAxisRange
            else -> return false
        }
        val forward = direction == FocusDirection.Down || direction == FocusDirection.Right
        var parent = node.parent
        while (parent != null) {
            val range = parent.config.getOrNull(key)
            if (range != null) {
                val value = range.value()
                val further = if (forward != range.reverseScrolling) value < range.maxValue() - 0.5f else value > 0.5f
                if (further) return true
            }
            parent = parent.parent
        }
        return false
    }

    private fun focusEntryOf(node: SemanticsNode, focused: SemanticsNode?): FocusRequester? {
        var parent = node.parent
        while (parent != null) {
            val entry = parent.config.getOrNull(FocusEntryKey)
            if (entry != null) return if (focused != null && contains(parent, focused)) null else entry
            parent = parent.parent
        }
        return null
    }

    private fun contains(ancestor: SemanticsNode, node: SemanticsNode): Boolean {
        var current: SemanticsNode? = node
        while (current != null) {
            if (current.id == ancestor.id) return true
            current = current.parent
        }
        return false
    }

    private fun score(from: Rect, to: Rect, direction: FocusDirection): Float? {
        val vertical = direction == FocusDirection.Up || direction == FocusDirection.Down
        val tolerance = OVERLAP_TOLERANCE * min(if (vertical) from.height else from.width, if (vertical) to.height else to.width)
        val primary = when (direction) {
            FocusDirection.Down -> to.top - from.bottom
            FocusDirection.Up -> from.top - to.bottom
            FocusDirection.Right -> to.left - from.right
            FocusDirection.Left -> from.left - to.right
            else -> return null
        }
        if (primary < -tolerance) return null
        val centreAhead = when (direction) {
            FocusDirection.Down -> to.center.y > from.center.y
            FocusDirection.Up -> to.center.y < from.center.y
            FocusDirection.Right -> to.center.x > from.center.x
            else -> to.center.x < from.center.x
        }
        if (!centreAhead) return null
        val gap = max(primary, 0f)
        val overlap = if (vertical) min(from.right, to.right) - max(from.left, to.left) else min(from.bottom, to.bottom) - max(from.top, to.top)
        val orthogonal = if (overlap > 0f) 0f else -overlap
        val centreOffset = if (vertical) abs(to.center.x - from.center.x) else abs(to.center.y - from.center.y)
        val weight = if (vertical) ORTHOGONAL_WEIGHT_VERTICAL else ORTHOGONAL_WEIGHT_SIDEWAYS
        val euclid = sqrt(gap * gap + orthogonal * orthogonal)
        return euclid + gap + orthogonal * weight + centreOffset * 0.05f - sqrt(max(overlap, 0f))
    }
}
