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
import org.polyfrost.compose.host.controller.FocusScopeKey

internal object SpatialNavigation {
    private const val MAX_ATTEMPTS = 8
    private const val OVERLAP_TOLERANCE = 0.3f
    private const val ORTHOGONAL_WEIGHT_SIDEWAYS = 30f
    private const val ORTHOGONAL_WEIGHT_VERTICAL = 4f
    private const val OUT_OF_BEAM_PENALTY = 2f
    private const val SCROLL_FRACTION = 0.5f

    const val NONE = 0
    const val MOVED = 1
    const val SCROLLED = 2
    const val BLOCKED = 3

    fun move(
        owner: SemanticsOwner,
        from: Rect,
        focused: SemanticsNode?,
        direction: FocusDirection,
        allowScroll: Boolean = true,
        enter: Boolean = true,
    ): Int {
        val scroller = focused?.let { scrollerFor(it, direction) }
        val scope = focused?.let(::scopeOf)
        if (focused != null && scope != null && (direction == FocusDirection.Next || direction == FocusDirection.Previous)) {
            return step(scope, focused, direction == FocusDirection.Next)
        }
        val nodes = owner.getAllSemanticsNodes(mergingEnabled = false)
        val candidates = ArrayList<Pair<Float, SemanticsNode>>()
        val inside = ArrayList<Triple<Float, Float, SemanticsNode>>()
        for (node in nodes) {
            if (node.id == focused?.id) continue
            if (scope != null && !contains(scope, node)) continue
            if (scroller != null && !contains(scroller, node)) continue
            if (SemanticsActions.RequestFocus !in node.config) continue
            val bounds = node.boundsInWindow
            if (bounds.width <= 0f || bounds.height <= 0f) continue
            val score = score(from, bounds, direction)
            if (score != null) {
                candidates += score to node
            } else if (enter && focused != null && contains(focused, node)) {
                inside += entryKey(from, node, direction) ?: continue
            }
        }
        if (candidates.isEmpty()) {
            inside.sortWith(compareBy({ it.first }, { it.second }))
            if (focusFirst(inside.map { it.third }, focused)) return MOVED
            if (scroller != null) {
                if (allowScroll && scroll(scroller, direction)) return SCROLLED
                return if (scope != null) BLOCKED else NONE
            }
            if (scope != null) return BLOCKED
        }
        candidates.sortBy { it.first }
        if (focusFirst(candidates.map { it.second }, focused)) return MOVED
        return if (scope != null) BLOCKED else NONE
    }

    private fun focusFirst(candidates: List<SemanticsNode>, focused: SemanticsNode?): Boolean {
        for (node in candidates.take(MAX_ATTEMPTS)) {
            if (focusEntryOf(node, focused)?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return true
            val action = node.config.getOrNull(SemanticsActions.RequestFocus)?.action ?: continue
            if (action()) return true
        }
        return false
    }

    private fun step(scope: SemanticsNode, focused: SemanticsNode, forward: Boolean): Int {
        val inScope = ArrayList<SemanticsNode>()
        collectFocusable(scope, focused.id, inScope)
        val order = if (forward) inScope else inScope.asReversed()
        val index = order.indexOfFirst { it.id == focused.id }
        val next = order.drop(index + 1) + order.take(max(index, 0))
        for (node in next.take(MAX_ATTEMPTS)) {
            if (focusEntryOf(node, focused)?.let { runCatching { it.requestFocus() }.getOrDefault(false) } == true) return MOVED
            val action = node.config.getOrNull(SemanticsActions.RequestFocus)?.action ?: continue
            if (runCatching { action() }.getOrDefault(false)) return MOVED
        }
        return BLOCKED
    }

    private fun collectFocusable(node: SemanticsNode, focusedId: Int, out: MutableList<SemanticsNode>) {
        val bounds = node.boundsInWindow
        if (node.id == focusedId || SemanticsActions.RequestFocus in node.config && bounds.width > 0f && bounds.height > 0f) out += node
        for (child in node.children) collectFocusable(child, focusedId, out)
    }

    private fun scroll(scroller: SemanticsNode, direction: FocusDirection): Boolean {
        val action = scroller.config.getOrNull(SemanticsActions.ScrollBy)?.action ?: return false
        val bounds = scroller.boundsInWindow
        val vertical = direction == FocusDirection.Up || direction == FocusDirection.Down
        val range = scroller.config.getOrNull(if (vertical) SemanticsProperties.VerticalScrollAxisRange else SemanticsProperties.HorizontalScrollAxisRange)
        val forward = direction == FocusDirection.Down || direction == FocusDirection.Right
        val sign = if (forward != (range?.reverseScrolling == true)) 1f else -1f
        val amount = (if (vertical) bounds.height else bounds.width) * SCROLL_FRACTION * sign
        return runCatching { if (vertical) action(0f, amount) else action(amount, 0f) }.getOrDefault(false)
    }

    private fun scrollerFor(node: SemanticsNode, direction: FocusDirection): SemanticsNode? {
        val key = when (direction) {
            FocusDirection.Up, FocusDirection.Down -> SemanticsProperties.VerticalScrollAxisRange
            FocusDirection.Left, FocusDirection.Right -> SemanticsProperties.HorizontalScrollAxisRange
            else -> return null
        }
        val forward = direction == FocusDirection.Down || direction == FocusDirection.Right
        var current: SemanticsNode? = node
        while (current != null) {
            val range = current.config.getOrNull(key)
            if (range != null && SemanticsActions.ScrollBy in current.config) {
                val value = range.value()
                val further = if (forward != range.reverseScrolling) value < range.maxValue() - 0.5f else value > 0.5f
                if (further) return current
            }
            current = current.parent
        }
        return null
    }

    private fun scopeOf(node: SemanticsNode): SemanticsNode? {
        var current: SemanticsNode? = node
        while (current != null) {
            if (FocusScopeKey in current.config) return current
            current = current.parent
        }
        return null
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

    private fun entryKey(from: Rect, node: SemanticsNode, direction: FocusDirection): Triple<Float, Float, SemanticsNode>? {
        val to = node.boundsInWindow
        val edge = when (direction) {
            FocusDirection.Down -> to.top
            FocusDirection.Up -> -to.bottom
            FocusDirection.Right -> to.left
            FocusDirection.Left -> -to.right
            else -> return null
        }
        val vertical = direction == FocusDirection.Up || direction == FocusDirection.Down
        val offset = if (vertical) abs(to.center.x - from.center.x) else abs(to.center.y - from.center.y)
        return Triple(edge, offset, node)
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
        val beamPenalty = if (overlap > 0f) 0f else OUT_OF_BEAM_PENALTY * max(from.width, from.height)
        return euclid + gap + orthogonal * weight + centreOffset * 0.05f - sqrt(max(overlap, 0f)) + beamPenalty
    }
}
