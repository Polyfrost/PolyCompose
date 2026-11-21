package org.polyfrost.compose.minecraft.mc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
//? if > 1.8.9 {
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
//?} else {
/*import net.minecraft.item.Item
import net.minecraft.item.ItemStack
*///?}
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.composables.NodeCanvas
import org.polyfrost.compose.composables.PolyModifier
import org.polyfrost.compose.composables.size
import org.polyfrost.compose.host.LocalContentScale
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.render.RenderContext

object McItemIcons {
    const val ITEM_SIZE: Float = 16f

    @JvmStatic
    val available: Boolean
        get() = McItemAtlas.available.value

    @JvmStatic
    val unavailableReason: String?
        get() = McItemAtlas.unavailableReason

    @JvmStatic
    val cooldownOverlay: Boolean
        //? if > 1.8.9 {
        get() = true
        //?} else
        //get() = false

    @JvmStatic
    fun open(stack: ItemStack): McItemIconHandle = McItemAtlas.open(stack)

    @JvmStatic
    fun requestAvailability() {
        McItemAtlas.requestAvailability()
    }

    /** Renders icons needed by HUD consumers before the HUD dirty gate. */
    @JvmStatic
    fun renderPending() {
        McItemAtlas.renderAll()
    }

    @JvmStatic
    @JvmOverloads
    fun drawDecorations(ctx: RenderContext, stack: ItemStack, x: Float, y: Float, size: Float = ITEM_SIZE, countOverride: String? = null) {
        McItemDecorations.draw(ctx, stack, x, y, size, countOverride)
    }
}

private fun drawFadedDecorations(
    ctx: RenderContext,
    handle: McItemIconHandle,
    x: Float,
    y: Float,
    size: Float,
    countOverride: String?,
    alpha: Float,
) {
    if (alpha <= 0f) return
    if (alpha >= 1f) {
        handle.drawDecorations(ctx, x, y, size, countOverride)
        return
    }
    ctx.saveLayerAlpha(alpha)
    try {
        handle.drawDecorations(ctx, x, y, size, countOverride)
    } finally {
        ctx.restore()
    }
}

@Composable
fun McItemIcon(
    stack: ItemStack,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    decorations: Boolean = true,
    countOverride: String? = null,
    alpha: Float = 1f,
) {
    val handle = rememberItemIconHandle(stack, decorations)
    val density = LocalDensity.current
    SideEffect { McItemAtlas.requestSize(handle, with(density) { size.toPx() }) }
    Box(
        modifier
            .size(size)
            .drawBehind {
                handle.revision
                if (!McItemIcons.available) return@drawBehind
                val w = this.size.width
                val h = this.size.height
                drawPoly { ctx ->
                    handle.draw(ctx, 0f, 0f, w, h, alpha)
                    if (decorations) drawFadedDecorations(ctx, handle, 0f, 0f, minOf(w, h), countOverride, alpha)
                }
            },
    )
}

@Composable
fun McItemIcon(
    item: Item,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    alpha: Float = 1f,
) {
    val stack = rememberDefaultStack(item)
    if (stack == null) {
        Box(modifier.size(size))
        return
    }
    McItemIcon(stack, modifier, size, decorations = false, alpha = alpha)
}

@Composable
fun NodeMcItemIcon(
    stack: ItemStack,
    size: Float = McItemIcons.ITEM_SIZE,
    modifier: PolyModifier = PolyModifier,
    decorations: Boolean = true,
    countOverride: String? = null,
    alpha: Float = 1f,
) {
    val handle = rememberItemIconHandle(stack, decorations)
    val scale = LocalContentScale.current
    SideEffect { McItemAtlas.requestSize(handle, size * McWindow.guiScale() * scale) }
    val revision = handle.revision
    val available = McItemIcons.available
    val onDraw: RenderContext.(Float, Float, Float, Float) -> Unit = remember(handle, revision, available, decorations, countOverride, alpha) {
        { x, y, w, h ->
            if (available) {
                handle.draw(this, x, y, w, h, alpha)
                if (decorations) drawFadedDecorations(this, handle, x, y, minOf(w, h), countOverride, alpha)
            }
        }
    }
    NodeCanvas(modifier.size(size, size), onDraw = onDraw)
}

@Composable
fun NodeMcItemIcon(
    item: Item,
    size: Float = McItemIcons.ITEM_SIZE,
    modifier: PolyModifier = PolyModifier,
    alpha: Float = 1f,
) {
    val stack = rememberDefaultStack(item)
    if (stack == null) {
        NodeCanvas(modifier.size(size, size)) { _, _, _, _ -> }
        return
    }
    NodeMcItemIcon(stack, size, modifier, decorations = false, alpha = alpha)
}

private class ItemIconHandleHolder(val handle: McItemIconHandle) : RememberObserver {
    override fun onRemembered() = Unit
    override fun onForgotten() = handle.close()
    override fun onAbandoned() = handle.close()
}

@Composable
private fun rememberItemIconHandle(stack: ItemStack, decorations: Boolean): McItemIconHandle {
    val handle = remember { ItemIconHandleHolder(McItemAtlas.open(stack)) }.handle
    SideEffect {
        handle.update(stack)
        handle.decorations = decorations
    }
    return handle
}

@Composable
private fun rememberDefaultStack(item: Item): ItemStack? {
    val available = McItemIcons.available
    SideEffect { if (!available) McItemAtlas.requestAvailability() }
    return remember(item, available) { if (available) runCatching { ItemStack(item) }.getOrNull() else null }
}
