package org.polyfrost.compose.host.controller

import androidx.compose.foundation.focusGroup
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties

public fun Modifier.focusEntry(target: FocusRequester, enabled: Boolean = true): Modifier =
    focusProperties {
        onEnter = {
            if (enabled) runCatching { target.requestFocus() }
        }
    }.focusGroup()
