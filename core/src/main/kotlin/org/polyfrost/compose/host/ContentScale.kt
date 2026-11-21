package org.polyfrost.compose.host

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf

public val LocalContentScale: ProvidableCompositionLocal<Float> = compositionLocalOf { 1f }
