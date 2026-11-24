package org.polyfrost.compose.host.controller

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
public val FocusEntryKey: SemanticsPropertyKey<FocusRequester> = SemanticsPropertyKey("FocusEntry")

public fun Modifier.focusEntry(target: FocusRequester, enabled: Boolean = true): Modifier =
    then(if (enabled) Modifier.semantics { this[FocusEntryKey] = target } else Modifier).focusProperties {
        onEnter = {
            if (enabled) runCatching { target.requestFocus(FocusDirection.Down) }
        }
    }.focusGroup()

private const val SHOW_FOCUS_FRAMES = 30

public fun Modifier.focusWhenShown(shown: Boolean, key: Any? = null): Modifier = composed {
    val requester = remember { FocusRequester() }
    val targets = LocalControllerTargets.current
    val bounds = remember { arrayOfNulls<Rect>(1) }
    LaunchedEffect(shown, key) {
        if (!shown || !Controllers.isActive) return@LaunchedEffect
        repeat(SHOW_FOCUS_FRAMES) {
            withFrameNanos { }
            val area = bounds[0]
            val inside = targets?.focusInside
            if (area != null && inside != null && runCatching { inside(area) }.getOrDefault(false)) return@LaunchedEffect
            if (inside == null && runCatching { requester.requestFocus(FocusDirection.Down) }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    onGloballyPositioned { bounds[0] = it.boundsInWindow() }.focusRequester(requester).focusGroup()
}
