@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.minecraft.compat.controlify

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.scene.ComposeSceneFocusManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextRange
import java.lang.reflect.Field
import org.polyfrost.compose.host.controller.ControllerArrowsKey
import org.polyfrost.compose.host.controller.ControllerAxes
import org.polyfrost.compose.host.controller.HidesFocusRingKey

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
        for (owner in owners.toList()) {
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
        for (owner in owners.toList()) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                if (!config.getOrElse(SemanticsProperties.Focused) { false }) continue
                if (HidesFocusRingKey in config) return true
            }
        }
        return false
    }

    fun focusedArrows(): ControllerAxes? {
        for (owner in owners.toList()) {
            for (node in owner.getAllSemanticsNodes(mergingEnabled = true)) {
                val config = node.config
                if (!config.getOrElse(SemanticsProperties.Focused) { false }) continue
                config.getOrNull(ControllerArrowsKey)?.let { return it }
            }
        }
        return null
    }

    fun focusedTextField(): Rect? {
        for (owner in owners.toList()) {
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
        for (owner in owners.toList()) {
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

internal class FocusMark(val owner: SemanticsOwner, val bounds: Rect)

internal data class FieldPreview(
    val text: String,
    val selection: TextRange?,
    val password: Boolean,
    val hint: String?,
)
