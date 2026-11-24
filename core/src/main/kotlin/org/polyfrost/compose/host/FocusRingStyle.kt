package org.polyfrost.compose.host

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

public class FocusRingStyle(
    public val color: Color,
    public val halo: Color? = null,
    public val width: Dp = 2.dp,
    public val gap: Dp = 2.dp,
    public val cornerRadius: Dp = 6.dp,
) {
    public companion object {
        @JvmField
        public val Default: FocusRingStyle = FocusRingStyle(Color.White.copy(alpha = 0.9f), Color.Black.copy(alpha = 0.5f))
    }
}
