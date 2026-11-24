package org.polyfrost.compose.host.controller

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.semantics
import org.jetbrains.annotations.ApiStatus

@ApiStatus.Internal
public val FocusEntryKey: SemanticsPropertyKey<FocusRequester> = SemanticsPropertyKey("FocusEntry")

public fun Modifier.focusEntry(target: FocusRequester, enabled: Boolean = true): Modifier =
    then(if (enabled) Modifier.semantics { this[FocusEntryKey] = target } else Modifier).focusGroup()

private const val SHOW_FOCUS_FRAMES = 30

@ApiStatus.Internal
public val ShownGroupKey: SemanticsPropertyKey<Any> = SemanticsPropertyKey("ControllerShownGroup")

public fun Modifier.focusWhenShown(shown: Boolean, key: Any? = null): Modifier = composed {
    val requester = remember { FocusRequester() }
    val token = remember { Any() }
    val targets = LocalControllerTargets.current
    LaunchedEffect(shown, key) {
        if (!shown || !Controllers.isActive) return@LaunchedEffect
        repeat(SHOW_FOCUS_FRAMES) {
            withFrameNanos { }
            val inside = targets?.focusInside
            if (inside != null && runCatching { inside(token) }.getOrDefault(false)) return@LaunchedEffect
            if (inside == null && runCatching { requester.requestFocus(FocusDirection.Down) }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    semantics { this[ShownGroupKey] = token }.focusRequester(requester).focusGroup()
}
