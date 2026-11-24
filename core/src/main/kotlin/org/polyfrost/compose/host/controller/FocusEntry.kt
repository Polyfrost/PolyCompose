package org.polyfrost.compose.host.controller

import androidx.compose.foundation.focusGroup
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester

public fun Modifier.focusEntry(target: FocusRequester, enabled: Boolean = true): Modifier =
    focusProperties {
        onEnter = {
            if (enabled) runCatching { target.requestFocus() }
        }
    }.focusGroup()

public fun Modifier.focusWhenShown(shown: Boolean, key: Any? = null): Modifier = composed {
    val requester = remember { FocusRequester() }
    LaunchedEffect(shown, key) {
        if (!shown || !Controllers.isActive) return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester.requestFocus() }
    }
    focusRequester(requester).focusGroup()
}
