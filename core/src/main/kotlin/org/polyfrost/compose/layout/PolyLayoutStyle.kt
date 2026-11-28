package org.polyfrost.compose.layout

import org.polyfrost.compose.node.PolyNode
import org.polyfrost.compose.render.PolyColor

public enum class LayoutType { Box, Row, Column }

public class PolyLayoutStyle {
    internal var owner: PolyNode? = null
    internal var pending: Boolean = false

    public var width: PolySize = PolySize.Wrap
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var height: PolySize = PolySize.Wrap
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var minWidth: Float = Float.NaN
        set(value) { if (!field.equals(value)) { beforeLayoutChange(); field = value } }
    public var maxWidth: Float = Float.NaN
        set(value) { if (!field.equals(value)) { beforeLayoutChange(); field = value } }
    public var minHeight: Float = Float.NaN
        set(value) { if (!field.equals(value)) { beforeLayoutChange(); field = value } }
    public var maxHeight: Float = Float.NaN
        set(value) { if (!field.equals(value)) { beforeLayoutChange(); field = value } }
    public var fitContent: Boolean = false
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var align: PolyAlign = PolyAlign.TopLeft
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var padding: PolyInsets = PolyInsets()
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var margin: PolyInsets = PolyInsets()
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var positionMode: PolyPositionMode = PolyPositionMode.Relative
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var x: Float = 0f
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var y: Float = 0f
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var gap: Float = 0f
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var layoutType: LayoutType = LayoutType.Box
        set(value) { if (field != value) { beforeLayoutChange(); field = value } }
    public var background: PolyColor = PolyColor.TRANSPARENT
    public var radius: Float = 0f
    public var borderColor: PolyColor = PolyColor.TRANSPARENT
    public var borderWidth: Float = 0f
    public var clipToBounds: Boolean = false
    public var alpha: Float = 1f
    public var fade: (() -> Float)? = null
    public var tag: Any? = null

    private var laidWidth: PolySize = PolySize.Wrap
    private var laidHeight: PolySize = PolySize.Wrap
    private var laidMinWidth: Float = Float.NaN
    private var laidMaxWidth: Float = Float.NaN
    private var laidMinHeight: Float = Float.NaN
    private var laidMaxHeight: Float = Float.NaN
    private var laidFitContent: Boolean = false
    private var laidAlign: PolyAlign = PolyAlign.TopLeft
    private var laidPadding: PolyInsets = padding
    private var laidMargin: PolyInsets = margin
    private var laidPositionMode: PolyPositionMode = PolyPositionMode.Relative
    private var laidX: Float = 0f
    private var laidY: Float = 0f
    private var laidGap: Float = 0f
    private var laidLayoutType: LayoutType = LayoutType.Box

    private fun beforeLayoutChange() {
        if (pending) return
        val tracker = owner?.tracker ?: return
        pending = true
        laidWidth = width
        laidHeight = height
        laidMinWidth = minWidth
        laidMaxWidth = maxWidth
        laidMinHeight = minHeight
        laidMaxHeight = maxHeight
        laidFitContent = fitContent
        laidAlign = align
        laidPadding = padding
        laidMargin = margin
        laidPositionMode = positionMode
        laidX = x
        laidY = y
        laidGap = gap
        laidLayoutType = layoutType
        tracker.pendingStyles.add(this)
    }

    internal fun layoutDiffers(): Boolean =
        laidWidth != width || laidHeight != height || !laidMinWidth.equals(minWidth) || !laidMaxWidth.equals(maxWidth) ||
            !laidMinHeight.equals(minHeight) || !laidMaxHeight.equals(maxHeight) || laidFitContent != fitContent || laidAlign != align || laidPadding != padding ||
            laidMargin != margin || laidPositionMode != positionMode || laidX != x || laidY != y ||
            laidGap != gap || laidLayoutType != layoutType
}
