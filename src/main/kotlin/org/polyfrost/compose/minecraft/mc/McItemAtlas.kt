package org.polyfrost.compose.minecraft.mc

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import com.mojang.blaze3d.pipeline.RenderTarget
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.sqrt
import net.minecraft.client.Minecraft
//? if > 1.8.9 {
import net.minecraft.world.item.ItemStack
//?} else {
/*import net.minecraft.item.ItemStack
*///?}
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.McWindow
import org.polyfrost.compose.minecraft.preview.McFrameHooks
import org.polyfrost.compose.minecraft.render.GuiTargetRedirect
import org.polyfrost.compose.minecraft.render.OffscreenTarget
import org.polyfrost.compose.minecraft.render.TextureRenderer
import org.polyfrost.compose.render.RenderContext
import org.slf4j.LoggerFactory

//? if >= 26.2 {
import com.mojang.renderpearl.api.GpuFormat
import org.joml.Vector4f
//?}

//? if >= 26.1 {
import java.util.concurrent.CompletableFuture
import net.minecraft.client.renderer.state.gui.GuiItemRenderState
import net.minecraft.client.renderer.state.gui.GuiRenderState
import net.minecraft.data.registries.VanillaRegistries
import net.minecraft.util.Util
//?}

//? if >= 1.21.11 {
import org.polyfrost.compose.minecraft.compat.GnetumCompat
import org.polyfrost.compose.minecraft.compat.GnetumOffscreenState
//?}

//? if >= 1.21.8 {
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.GuiRenderer
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import org.joml.Matrix3x2f
//?}

//? if > 1.8.9 {
import com.mojang.blaze3d.systems.RenderSystem
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemDisplayContext
//?}

//? if >= 1.21.8 && < 26.2 {
/*import net.minecraft.client.renderer.fog.FogRenderer
import org.polyfrost.compose.minecraft.mixin.render.GameRendererAccessor
*///?}

//? if >= 1.21.8 && < 26.1 {
/*import net.minecraft.client.gui.render.state.GuiItemRenderState
import net.minecraft.client.gui.render.state.GuiRenderState
*///?}

//? if = 1.21.5 {
/*import com.mojang.renderpearl.api.textures.GpuTexture
import com.mojang.renderpearl.api.textures.TextureFormat
*///?}

//? if >= 1.21.4 && < 1.21.8 {
/*import net.minecraft.client.renderer.item.ItemStackRenderState
*///?}

//? if < 1.21.8 && > 1.8.9 {
/*import com.mojang.blaze3d.platform.Lighting
import java.util.IdentityHashMap
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import kotlin.math.roundToInt
*///?}

//? if < 1.21.8
//import org.polyfrost.compose.minecraft.render.withGuiProjection

//? if < 1.21.5 {
/*import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.opengl.GL11
*///?}

//? if < 1.21.4 && > 1.8.9 {
/*import net.minecraft.client.renderer.ItemBlockRenderTypes
import net.minecraft.client.resources.model.BakedModel
import net.minecraft.client.resources.model.ModelResourceLocation
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Items
*///?}

//? if < 1.21.8 && > 1.8.9 {
/*import net.minecraft.util.RandomSource
*///?}

//? if < 1.21.4 && > 1.8.9 {
/*import net.minecraft.core.Direction
*///?}

//? if = 1.8.9 {
/*import net.minecraft.client.render.platform.Lighting
*///?}

interface McItemIconHandle : AutoCloseable {
    val stack: ItemStack

    val isClosed: Boolean

    val revision: Int

    var decorations: Boolean

    fun update(stack: ItemStack)

    fun draw(ctx: RenderContext, x: Float, y: Float, w: Float, h: Float, alpha: Float = 1f): Boolean

    fun drawDecorations(ctx: RenderContext, x: Float, y: Float, size: Float, countOverride: String? = null)

    override fun close()
}

internal object McItemAtlas {
    private val LOG = LoggerFactory.getLogger("PolyCompose/ItemIcons")
    private const val CELL_SIZE = 16
    private const val SPECIAL_REFRESH_NS = 1_000_000_000L

    private class IdentityBuffer {
        //? if >= 1.21.8 {
        val values = ArrayList<Any?>()
        fun clear() = values.clear()
        fun sameAs(other: IdentityBuffer): Boolean = values == other.values
        //?} else {
        /*private var kinds = ByteArray(16)
        private var refs = arrayOfNulls<Any>(16)
        private var ints = IntArray(16)
        private var size = 0

        fun clear() {
            for (index in 0 until size) refs[index] = null
            size = 0
        }

        fun ref(value: Any?) {
            ensureCapacity()
            kinds[size] = 0
            refs[size] = value
            size++
        }

        fun int(value: Int) {
            ensureCapacity()
            kinds[size] = 1
            ints[size] = value
            size++
        }

        fun value(value: Any?) {
            ensureCapacity()
            kinds[size] = 2
            refs[size] = value
            size++
        }

        fun sameAs(other: IdentityBuffer): Boolean {
            if (size != other.size) return false
            for (index in 0 until size) {
                if (kinds[index] != other.kinds[index]) return false
                when (kinds[index].toInt()) {
                    0 -> if (refs[index] !== other.refs[index]) return false
                    2 -> if (refs[index] != other.refs[index]) return false
                    else -> if (ints[index] != other.ints[index]) return false
                }
            }
            return true
        }

        private fun ensureCapacity() {
            if (size < kinds.size) return
            val capacity = size * 2
            kinds = kinds.copyOf(capacity)
            refs = refs.copyOf(capacity)
            ints = ints.copyOf(capacity)
        }
        *///?}
    }

    //? if >= 1.21.8 {
    private class ReusableTrackingState : TrackingItemStackRenderState() {
        private var identity: Any = emptyList<Any?>()
        private var collector = ArrayList<Any?>()

        fun useIdentity(buffer: ArrayList<Any?>) {
            collector = buffer
            identity = buffer
        }

        fun freezeIdentity(identity: List<Any?>) {
            this.identity = identity
        }

        override fun clear() {
            super.clear()
            collector.clear()
        }

        override fun appendModelIdentityElement(element: Any) {
            collector.add(element)
        }

        override fun getModelIdentity(): Any = identity
    }
    //?}

    private class IconEntry(override var stack: ItemStack) : McItemIconHandle {
        val revisionState = mutableIntStateOf(0)
        var requestedSizePx = 0
        var closedFlag = false
        var slot = 0
        var x = 0
        var y = 0
        val atlasSource = FloatArray(4)
        var atlas: AtlasState? = null
        var animated = false
        var special = false
        var specialRefreshAtNs = 0L
        var failed = false
    var failedStack: ItemStack? = null
        var identityInitialized = false
        var resolvedTick = -1
        var resolvedStack: ItemStack? = null
        var committedIdentity = IdentityBuffer()
        var scratchIdentity = IdentityBuffer()
        var decorationKey = Long.MIN_VALUE
        override var decorations: Boolean = true
        //? if >= 1.21.8 {
        var stableIdentity: List<Any?> = emptyList()
        val resolutionState = ReusableTrackingState()
        //?} elif >= 1.21.4 {
        /*val resolutionState = ItemStackRenderState()
        *///?} elif > 1.8.9 {
        /*var resolvedGuiModel: BakedModel? = null
        var resolvedUsesBlockLight = true
        *///?}
        //? if < 1.21.8 && > 1.8.9
        //val resolutionRandom = RandomSource.create(42L)

        override val isClosed: Boolean
            get() = closedFlag

        override val revision: Int
            get() = revisionState.intValue

        fun setRenderSizePx(size: Int) {
            synchronized(lock) {
                val requested = pageSizeFor(size.coerceIn(1, MAX_CELL_PX))
                if (closedFlag || requestedSizePx == requested) return
                requestedSizePx = requested
                topologyDirty = true
            }
            ensureHooks()
        }

        override fun update(stack: ItemStack) {
            synchronized(lock) {
                if (closedFlag || this.stack === stack) return
                this.stack = stack
                decorationKey = Long.MIN_VALUE
                val variant = failedStack
                if (variant != null && !sameVariant(variant, stack)) {
                    releaseFailure(this)
                    identityInitialized = false
                }
                atlas?.cleanTick = -1
            }
        }

        override fun draw(ctx: RenderContext, x: Float, y: Float, w: Float, h: Float, alpha: Float): Boolean {
            if (closedFlag || w <= 0f || h <= 0f) return false
            revisionState.intValue
            setRenderSizePx(renderPx(ctx, maxOf(w, h)))
            if (!available.value) return false
            val state = readyAtlas(this) ?: return false
            val src = atlasSource
            if (src[2] <= src[0] || src[3] <= src[1]) return false
            val textures = ctx.renderer as? TextureRenderer ?: return false
            val target = state.offscreenTarget.target ?: return false
            val size = state.layout?.atlasSizePx?.toFloat() ?: return false
            textures.texture(target, x, y, w, h, src[0] / size, 1f - src[1] / size, src[2] / size, 1f - src[3] / size, alpha.coerceIn(0f, 1f))
            return true
        }

        override fun drawDecorations(ctx: RenderContext, x: Float, y: Float, size: Float, countOverride: String?) {
            if (closedFlag) return
            revisionState.intValue
            McItemDecorations.draw(ctx, stack, x, y, size, countOverride)
        }

        override fun close() {
            synchronized(lock) {
                if (closedFlag) return
                closedFlag = true
                active.remove(this)
                releaseFailure(this)
                topologyDirty = true
            }
        }
    }

    private data class AtlasLayout(val iconsPerSide: Int, val iconSizePx: Int) {
        val atlasSizePx: Int get() = iconsPerSide * iconSizePx
        val capacity: Int get() = iconsPerSide * iconsPerSide
    }

    private class AtlasState(val renderSizePx: Int) {
        val offscreenTarget = OffscreenTarget()
        val entries = ArrayList<IconEntry>()
        val changed = ArrayList<IconEntry>()
        var layout: AtlasLayout? = null
        var readyForSampling = false
        var rebuild = true
        var animatedTick = -1
        var cleanTick = -1
        val freeSlots = ArrayDeque<Int>()
        var nextUnusedSlot = 0
        //? if >= 1.21.8
        var itemGuiResources: ItemGuiResources? = null
    }

    //? if >= 1.21.8
    private class ItemGuiResources(val state: GuiRenderState, val renderer: GuiRenderer)

    private const val MAX_CELL_PX = 512
    private const val MAX_FAILED_VARIANTS = 256
    private const val PAGE_STEP_PX = 16

    private fun pageSizeFor(size: Int) = ((size + PAGE_STEP_PX - 1) / PAGE_STEP_PX * PAGE_STEP_PX).coerceAtMost(MAX_CELL_PX)

    private val lock = Any()
    private val atlases = HashMap<Int, AtlasState>()
    private val active = LinkedHashSet<IconEntry>()
    //? if = 1.21.5
    //private val clearTiles = HashMap<Int, GpuTexture>()
    @Volatile
    private var topologyDirty = true
    @Volatile
    private var renderingFailed = false
    private val failedVariants = ArrayList<ItemStack>()
    private var clientTick = 0
    private var lastLevel: Any? = null
    private var lastViewportW = 0
    private var lastViewportH = 0
    private var frameHook: AutoCloseable? = null
    private var tickHook: AutoCloseable? = null
    private var reloadHook: AutoCloseable? = null
    private var initializationStarted = false

    val available = mutableStateOf(false)

    @Volatile
    var unavailableReason: String? = "Item icons start when the first icon is shown"
        private set

    fun open(stack: ItemStack): McItemIconHandle {
        val entry = IconEntry(stack)
        synchronized(lock) {
            active.add(entry)
            topologyDirty = true
        }
        requestAvailability()
        return entry
    }

    private fun renderPx(ctx: RenderContext, size: Float): Int = renderPx(size * ctx.deviceScale())

    private fun renderPx(devicePx: Float): Int = ceil(devicePx - 0.01f).toInt().coerceAtLeast(1)

    fun requestSize(handle: McItemIconHandle, devicePx: Float) {
        val entry = handle as? IconEntry ?: return
        if (entry.requestedSizePx == 0) entry.setRenderSizePx(renderPx(devicePx))
    }

    private fun ensureHooks() {
        if (frameHook == null) frameHook = McFrameHooks.beforeUi(::renderAll)
        if (tickHook == null) tickHook = HostEvents.onClientTick(::onTick)
        if (reloadHook == null) reloadHook = HostEvents.onResourcesReloaded(::clearCaches)
    }

    private fun releaseHooks() {
        frameHook?.close()
        frameHook = null
        tickHook?.close()
        tickHook = null
        reloadHook?.close()
        reloadHook = null
        lastLevel = null
    }

    fun requestAvailability() {
        if (initializationStarted) return
        initializationStarted = true
        //? if >= 26.1 {
        val componentsBound = { BuiltInRegistries.ITEM.all { BuiltInRegistries.ITEM.wrapAsHolder(it).areComponentsBound() } }
        CompletableFuture.supplyAsync(
            {
                if (componentsBound()) null else {
                    //~ if < 26.3 'createWorldLookup' -> 'createLookup'
                    val lookup = VanillaRegistries.createWorldLookup()
                    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(lookup)
                }
            },
            Util.backgroundExecutor(),
        ).whenComplete { pending, failure ->
            Minecraft.getInstance().schedule {
                try {
                    if (failure != null) throw failure
                    if (!componentsBound()) pending?.forEach { it.apply() }
                    markAvailable()
                } catch (throwable: Throwable) {
                    unavailableReason = "Item components could not be bound before joining a world: $throwable"
                    LOG.warn("Could not enable item icons before joining a world", throwable)
                    ensureHooks()
                }
            }
        }
        //?} else
        //markAvailable()
    }

    private fun markAvailable() {
        unavailableReason = null
        Snapshot.withMutableSnapshot { available.value = true }
    }

    private fun onTick() {
        clientTick++
        val mc = Minecraft.getInstance()
        val level = mc.level
        if (level !== lastLevel) {
            val hadLevel = lastLevel != null
            lastLevel = level
            if (level != null) {
                if (!available.value) markAvailable()
                if (hadLevel || atlases.isNotEmpty()) clearCaches()
            }
        }
        val w = McWindow.viewportWidth()
        val h = McWindow.viewportHeight()
        if (w != lastViewportW || h != lastViewportH) {
            lastViewportW = w
            lastViewportH = h
            recover()
        }
        val changed = ArrayList<IconEntry>()
        synchronized(lock) {
            for (entry in active) {
                if (!entry.decorations) continue
                val key = McItemDecorations.key(entry.stack)
                if (key == entry.decorationKey) continue
                val first = entry.decorationKey == Long.MIN_VALUE
                entry.decorationKey = key
                if (!first) changed.add(entry)
            }
        }
        if (changed.isNotEmpty()) invalidateEntries(changed)
    }

    private fun clearCaches() {
        synchronized(lock) {
            destroyAtlases(queueRendererDisposal = true)
        }
        //? if < 1.21.8 && > 1.8.9
        //animatedSprites.clear()
        recover()
    }

    private fun recover() {
        var changed = renderingFailed
        renderingFailed = false
        val retry = ArrayList<IconEntry>()
        synchronized(lock) {
            if (failedVariants.isNotEmpty()) changed = true
            failedVariants.clear()
            active.forEach { entry ->
                if (!entry.failed) return@forEach
                changed = true
                entry.failed = false
                entry.failedStack = null
                entry.identityInitialized = false
                entry.atlas?.cleanTick = -1
                retry.add(entry)
            }
        }
        if (changed) invalidateEntries(retry)
    }

    private fun sameVariant(a: ItemStack, b: ItemStack): Boolean {
        //? if > 1.8.9 {
        return ItemStack.isSameItemSameComponents(a, b)
        //?} else
        //return ItemStack.matchesItem(a, b) && ItemStack.matchesNbt(a, b)
    }

    private fun findFailedVariant(stack: ItemStack): ItemStack? {
        for (index in failedVariants.indices) {
            val variant = failedVariants[index]
            if (sameVariant(variant, stack)) return variant
        }
        return null
    }

    private fun markFailed(entry: IconEntry, stage: String, throwable: Throwable) {
        val stack = entry.stack
        val firstFailure = synchronized(lock) {
            entry.failed = true
            val known = findFailedVariant(stack)
            entry.failedStack = known ?: stack.copy().also {
                if (failedVariants.size >= MAX_FAILED_VARIANTS) failedVariants.removeAt(0)
                failedVariants.add(it)
            }
            known == null
        }
        if (firstFailure) {
            LOG.warn("Item icon for {} failed while {}, it stays blank until the next world change, resize or resource reload", stack.item, stage, throwable)
        }
    }

    private fun releaseFailure(entry: IconEntry) {
        val variant = entry.failedStack ?: return
        entry.failedStack = null
        entry.failed = false
        if (active.none { it !== entry && it.failedStack === variant }) failedVariants.removeAll { it === variant }
    }

    fun renderAll() {
        if (!available.value || renderingFailed) return
        try {
            reconcileTopology()
            for (page in atlases.values) renderAtlas(page)
        } catch (throwable: Throwable) {
            renderingFailed = true
            destroyAtlases()
            LOG.warn("Failed to render item icons", throwable)
        }
    }

    private fun readyAtlas(entry: IconEntry): AtlasState? = synchronized(lock) {
        val state = entry.atlas
        if (state == null || state.offscreenTarget.target == null) {
            if (state?.entries?.isNotEmpty() == true) {
                resetAtlas(state, state.layout)
            }
            null
        } else {
            state.takeIf { it.readyForSampling }
        }
    }

    private fun renderAtlas(atlasState: AtlasState): Boolean {
        val layout = atlasState.layout ?: return false
        if (atlasState.entries.isEmpty()) return false
        if (atlasState.cleanTick == clientTick && !atlasState.rebuild && atlasState.offscreenTarget.target != null) return false

        val offscreenTarget = atlasState.offscreenTarget
        val previousTarget = offscreenTarget.target

        val resolved = offscreenTarget.resolve(layout.atlasSizePx, layout.atlasSizePx)
        val target = offscreenTarget.target
        check(resolved && target != null) {
            "Failed to create a ${layout.iconSizePx}px item icon atlas"
        }

        val guiSize = layout.iconsPerSide * CELL_SIZE
        val targetChanged = target !== previousTarget
        if (targetChanged) atlasState.readyForSampling = false

        val changed = atlasState.changed
        changed.clear()
        val rebuild = targetChanged || atlasState.rebuild
        val now = System.nanoTime()
        var special = false
        for (entry in atlasState.entries) {
            if (entry.failed) continue
            if (entry.special) special = true
            val stale = rebuild || !entry.identityInitialized || entry.resolvedTick != clientTick || entry.resolvedStack !== entry.stack
            val resolvedChanged = stale && try {
                resolveIconState(entry)
            } catch (throwable: Throwable) {
                markFailed(entry, "resolving its model", throwable)
                changed.add(entry)
                continue
            }
            entry.resolvedTick = clientTick
            entry.resolvedStack = entry.stack
            val specialRefresh = entry.special && now - entry.specialRefreshAtNs >= 0
            if (resolvedChanged || (entry.animated && atlasState.animatedTick != clientTick) || rebuild || specialRefresh) {
                changed.add(entry)
                if (entry.special) entry.specialRefreshAtNs = now + SPECIAL_REFRESH_NS
            }
        }
        atlasState.animatedTick = clientTick
        atlasState.cleanTick = if (special) -1 else clientTick

        if (changed.isEmpty()) return false

        if (rebuild) clearTarget(target) else clearCells(target, changed, layout)

        renderItemsIsolated(atlasState, target, layout, changed, guiSize)
        atlasState.readyForSampling = true
        atlasState.rebuild = false
        invalidateEntries(if (rebuild) atlasState.entries.toList() else changed.toList())
        return true
    }

    private fun invalidateEntries(entries: Collection<IconEntry>) {
        if (entries.isEmpty()) return
        Snapshot.withMutableSnapshot {
            for (entry in entries) entry.revisionState.intValue++
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun resolveIconState(entry: IconEntry): Boolean {
        val stack = entry.stack
        val client = Minecraft.getInstance()
        val scratch = entry.scratchIdentity
        scratch.clear()
        //? if >= 1.21.8 {
        val state = entry.resolutionState
        state.useIdentity(scratch.values)
        client.itemModelResolver.updateForTopItem(
            state,
            stack,
            ItemDisplayContext.GUI,
            client.level,
            client.player,
            0,
        )
        val animated = state.isAnimated
        val special = false
        //?} else if >= 1.21.4 {
        /*val state = entry.resolutionState
        client.itemModelResolver.updateForTopItem(
            state,
            stack,
            ItemDisplayContext.GUI,
            //? if = 1.21.4
            //false,
            client.level,
            client.player,
            0,
        )
        val animated = appendStateIdentity(scratch, state)
        val special = false
        *///?} elif > 1.8.9 {
        /*val renderer = client.itemRenderer
        val resolvedModel = renderer.getModel(stack, client.level, client.player, 0)
        val guiModel = when {
            stack.`is`(Items.TRIDENT) -> renderer.itemModelShaper.modelManager.getModel(
                ModelResourceLocation.inventory(Identifier.withDefaultNamespace("trident")),
            )
            stack.`is`(Items.SPYGLASS) -> renderer.itemModelShaper.modelManager.getModel(
                ModelResourceLocation.inventory(Identifier.withDefaultNamespace("spyglass")),
            )
            else -> resolvedModel
        }
        entry.resolvedGuiModel = guiModel
        entry.resolvedUsesBlockLight = resolvedModel.usesBlockLight()
        var animated = stack.hasFoil()
        var tinted = false
        val special = guiModel.isCustomRenderer
        if (special) scratch.int(stack.components.hashCode())
        scratch.ref(guiModel)
        val random = entry.resolutionRandom
        random.setSeed(42L)
        var quadCount = 0
        val directions = Direction.entries
        var directionIndex = 0
        while (directionIndex < directions.size) {
            val quads = guiModel.getQuads(null, directions[directionIndex++], random)
            var quadIndex = 0
            while (quadIndex < quads.size) {
                val quad = quads[quadIndex++]
                if (animatedSprite(quad.sprite)) animated = true
                scratch.ref(quad)
                scratch.int(if (quad.isTinted) 1 else 0)
                if (quad.isTinted) tinted = true
                quadCount++
            }
            random.setSeed(42L)
        }
        val quads = guiModel.getQuads(null, null, random)
        var quadIndex = 0
        while (quadIndex < quads.size) {
            val quad = quads[quadIndex++]
            if (animatedSprite(quad.sprite)) animated = true
            scratch.ref(quad)
            scratch.int(if (quad.isTinted) 1 else 0)
            if (quad.isTinted) tinted = true
            quadCount++
        }
        scratch.int(quadCount)
        if (tinted) scratch.int(stack.components.hashCode())
        scratch.ref(guiModel.transforms.getTransform(ItemDisplayContext.GUI))
        scratch.ref(ItemBlockRenderTypes.getRenderType(stack, true))
        scratch.int(if (resolvedModel.usesBlockLight()) 1 else 0)
        *///?} else {
        /*val guiModel = client.itemRenderer.modelShaper.getModel(stack)
        val animated = stack.hasEnchantmentGlint() || guiModel.particleIcon?.isAnimated == true
        val special = guiModel.isCustomRenderer
        scratch.ref(guiModel)
        scratch.ref(stack.item)
        scratch.int(stack.metadata)
        scratch.int(stack.nbt?.hashCode() ?: 0)
        *///?}
        val changed = !entry.identityInitialized || !scratch.sameAs(entry.committedIdentity)
        if (changed) {
            val old = entry.committedIdentity
            entry.committedIdentity = scratch
            entry.scratchIdentity = old
            //? if >= 1.21.8
            entry.stableIdentity = entry.committedIdentity.values.toList()
        }
        //? if >= 1.21.8
        entry.resolutionState.freezeIdentity(entry.stableIdentity)
        entry.identityInitialized = true
        val animationChanged = entry.animated != animated
        entry.animated = animated
        entry.special = special
        return changed || animationChanged
    }

    //? if = 1.21.5 {
    /*private fun appendStateIdentity(scratch: IdentityBuffer, state: ItemStackRenderState): Boolean {
        val count = state.activeLayerCount
        val layers = state.layers
        var animated = false
        scratch.int(count)
        for (index in 0 until count) {
            val layer = layers[index]
            val quads = layer.quads
            scratch.int(quads.size)
            for (quadIndex in quads.indices) {
                val quad = quads[quadIndex]
                scratch.ref(quad)
                if (animatedSprite(quad.sprite())) animated = true
            }
            scratch.ref(layer.particleIcon)
            scratch.ref(layer.transform)
            scratch.int(if (layer.usesBlockLight) 1 else 0)
            if (appendLayerIdentity(scratch, layer)) animated = true
        }
        return animated
    }
    *///?} elif = 1.21.4 {
    /*private fun appendStateIdentity(scratch: IdentityBuffer, state: ItemStackRenderState): Boolean {
        val count = state.activeLayerCount
        val layers = state.layers
        var animated = false
        scratch.int(count)
        for (index in 0 until count) {
            val layer = layers[index]
            val model = layer.model
            scratch.ref(model)
            if (model != null && animatedSprite(model.particleIcon)) animated = true
            if (appendLayerIdentity(scratch, layer)) animated = true
        }
        return animated
    }
    *///?}

    //? if >= 1.21.4 && < 1.21.8 {
    /*private fun appendLayerIdentity(scratch: IdentityBuffer, layer: ItemStackRenderState.LayerRenderState): Boolean {
        val foil = layer.foilType
        scratch.ref(layer.renderType)
        scratch.ref(foil)
        val tints = layer.tintLayers
        scratch.int(tints.size)
        for (tint in tints) scratch.int(tint)
        scratch.ref(layer.specialRenderer)
        scratch.value(layer.argumentForSpecialRendering)
        return foil != ItemStackRenderState.FoilType.NONE
    }
    *///?}

    private fun renderItemsIsolated(atlasState: AtlasState, target: RenderTarget, layout: AtlasLayout, changed: List<IconEntry>, guiSize: Int) {
        val renderable = if (changed.any { it.failed }) changed.filter { !it.failed } else changed
        if (renderable.isEmpty()) {
            //? if < 1.21.5
            //Minecraft.getInstance().mainRenderTarget.bindWrite(true)
            return
        }
        if (runCatching { renderItems(atlasState, target, renderable, guiSize, guiSize) }.isSuccess) return
        clearCells(target, renderable, layout)
        for (entry in renderable) {
            val single = listOf(entry)
            try {
                renderItems(atlasState, target, single, guiSize, guiSize)
            } catch (throwable: Throwable) {
                markFailed(entry, "rendering", throwable)
                clearCells(target, single, layout)
            }
        }
    }

    @Suppress("LongMethod")
    private fun renderItems(page: AtlasState, target: RenderTarget, entries: List<IconEntry>, guiWidth: Int, guiHeight: Int) {
        val mc = Minecraft.getInstance()
        //? if > 1.8.9 {
        val previousTarget = GuiTargetRedirect.target
        val previousScissor = GuiTargetRedirect.scissorTransform
        //?}
        val renderSizePx = checkNotNull(page.layout).iconSizePx

        //? if >= 1.21.8 {
        try {
            val resources = page.itemGuiResources ?: GuiRenderState().let { state ->
                //? if >= 1.21.11
                (state as? GnetumOffscreenState)?.`polycompose$markOffscreen`()
                ItemGuiResources(state, createItemGuiRenderer(mc, state)).also { page.itemGuiResources = it }
            }
            //? if >= 1.21.11 {
            val graphics = GuiGraphicsExtractor(mc, resources.state, guiWidth, guiHeight)
            //?} else
            //val graphics = GuiGraphicsExtractor(mc, resources.state)
            val pose = graphics.pose()
            pose.pushMatrix()
            pose.scale(target.width.toFloat() / guiWidth.toFloat(), target.height.toFloat() / guiHeight.toFloat())
            try {
                entries.forEach { entry ->
                    val itemPose = Matrix3x2f(pose)
                    val scissor = ScreenRectangle(
                        entry.x / CELL_SIZE * renderSizePx, entry.y / CELL_SIZE * renderSizePx,
                        renderSizePx, renderSizePx,
                    )
                    //? if >= 26.1 {
                    resources.state.addItem(GuiItemRenderState(itemPose, entry.resolutionState, entry.x, entry.y, scissor))
                    //?} else {
                    /*resources.state.submitItem(
                        GuiItemRenderState(entry.stack.item.name.toString(), itemPose, entry.resolutionState, entry.x, entry.y, scissor),
                    )
                    *///?}
                }
            } finally {
                pose.popMatrix()
            }

            val previousRenderSize = GuiTargetRedirect.itemRenderSizePx
            GuiTargetRedirect.target = target
            GuiTargetRedirect.scissorTransform = GuiTargetRedirect.ScissorTransform { left, top, right, bottom ->
                intArrayOf(left, target.height - bottom, right - left, bottom - top)
            }
            GuiTargetRedirect.itemRenderSizePx = renderSizePx
            try {
                //? if >= 1.21.11 {
                GnetumCompat.restoring { drawGui(mc, resources.renderer) }
                //?} else
                //drawGui(mc, resources.renderer)
            } finally {
                GuiTargetRedirect.scissorTransform = previousScissor
                GuiTargetRedirect.itemRenderSizePx = previousRenderSize
                GuiTargetRedirect.target = previousTarget
            }
            //? if >= 26.1 {
            resources.renderer.endFrame()
            //?} else
            //resources.renderer.incrementFrameNumber()
        } catch (throwable: Throwable) {
            discardItemGuiResources(page)
            throw throwable
        }
        //?} elif > 1.8.9 {
        /*val bufferSource = mc.renderBuffers().bufferSource()
        val graphics = GuiGraphics(mc, bufferSource)
        withGuiProjection(guiWidth, guiHeight) {
            GuiTargetRedirect.target = target
            GuiTargetRedirect.scissorTransform = atlasScissorTransform(target, renderSizePx, CELL_SIZE.toFloat())

            //? if < 1.21.5 {
            /*target.bindWrite(true)
            RenderSystem.disableScissor()
            *///?}

            try {
                entries.forEach { entry ->
                    graphics.enableScissor(entry.x, entry.y, entry.x + CELL_SIZE, entry.y + CELL_SIZE)
                    try {
                        val pose = graphics.pose()
                        pose.pushPose()
                        pose.translate(entry.x + 8f, entry.y + 8f, 150f)
                        try {
                            pose.scale(16f, -16f, 16f)
                            //? if >= 1.21.4 {
                            val flat = !entry.resolutionState.usesBlockLight()
                            if (flat) {
                                graphics.flush()
                                Lighting.setupForFlatItems()
                            }
                            entry.resolutionState.render(pose, bufferSource, 15728880, OverlayTexture.NO_OVERLAY)
                            //?} else {
                            /*val model = checkNotNull(entry.resolvedGuiModel)
                            val flat = !entry.resolvedUsesBlockLight
                            if (flat) Lighting.setupForFlatItems()
                            mc.itemRenderer.render(
                                entry.stack, ItemDisplayContext.GUI, false, pose, bufferSource,
                                15728880, OverlayTexture.NO_OVERLAY, model,
                            )
                            *///?}
                            graphics.flush()
                            if (flat) Lighting.setupFor3DItems()
                        } finally {
                            pose.popPose()
                        }
                    } finally { graphics.disableScissor() }
                }
                graphics.flush()
            } finally {
                //? if < 1.21.5 {
                /*Lighting.setupFor3DItems()
                RenderSystem.disableScissor()
                *///?}
                GuiTargetRedirect.scissorTransform = previousScissor
                GuiTargetRedirect.target = previousTarget
                //? if < 1.21.5
                //(previousTarget ?: mc.mainRenderTarget).bindWrite(true)
            }
        }
        *///?} else {
        /*withGuiProjection(guiWidth, guiHeight) {
            target.bindWrite(true)
            Lighting.turnOnGui()
            GL11.glEnable(GL11.GL_SCISSOR_TEST)
            try {
                entries.forEach { entry ->
                    val left = entry.x / CELL_SIZE * renderSizePx
                    val top = entry.y / CELL_SIZE * renderSizePx
                    GL11.glScissor(left, target.height - top - renderSizePx, renderSizePx, renderSizePx)
                    GlStateManager.pushMatrix()
                    try {
                        mc.itemRenderer.renderGuiItem(entry.stack, entry.x, entry.y)
                    } finally {
                        GlStateManager.popMatrix()
                    }
                }
            } finally {
                GL11.glDisable(GL11.GL_SCISSOR_TEST)
                Lighting.turnOff()
                mc.mainRenderTarget.bindWrite(true)
            }
        }
        *///?}
    }

    //? if < 1.21.8 && > 1.8.9 {
    /*private val animatedSprites = IdentityHashMap<TextureAtlasSprite, Boolean>()

    private fun animatedSprite(sprite: TextureAtlasSprite): Boolean =
        animatedSprites.getOrPut(sprite) { sprite.contents().uniqueFrames.limit(2).count() > 1 }

    private fun atlasScissorTransform(target: RenderTarget, renderSizePx: Int, scissorSlotSize: Float) =
        GuiTargetRedirect.ScissorTransform { left, top, right, bottom ->
            val leftPx = (left / scissorSlotSize).roundToInt() * renderSizePx
            val topPx = (top / scissorSlotSize).roundToInt() * renderSizePx
            val rightPx = (right / scissorSlotSize).roundToInt() * renderSizePx
            val bottomPx = (bottom / scissorSlotSize).roundToInt() * renderSizePx
            intArrayOf(leftPx, target.height - bottomPx, rightPx - leftPx, bottomPx - topPx)
        }
    *///?}

    //? if >= 1.21.8 {
    private fun drawGui(mc: Minecraft, renderer: GuiRenderer) {
        //? if >= 26.2 {
        renderer.render()
        //?} else {
        /*val fog = (mc.gameRenderer as GameRendererAccessor).`polycompose$getFogRenderer`().getBuffer(FogRenderer.FogMode.NONE)
        renderer.render(fog)
        *///?}
    }

    private fun createItemGuiRenderer(client: Minecraft, state: GuiRenderState): GuiRenderer {
        //? if >= 26.2 {
        return GuiRenderer(state, client.gameRenderer.featureRenderDispatcher(), emptyList())
        //? } else if >= 1.21.10 {
        /*return GuiRenderer(
            state,
            client.renderBuffers().bufferSource(),
            client.gameRenderer.getSubmitNodeStorage(),
            client.gameRenderer.getFeatureRenderDispatcher(),
            emptyList(),
        )
        *///? } else {
        /*return GuiRenderer(state, client.renderBuffers().bufferSource(), emptyList())
        *///? }
    }

    private fun discardItemGuiResources(page: AtlasState, queued: Boolean = false) {
        val resources = page.itemGuiResources
        page.itemGuiResources = null
        if (resources != null) {
            val close: () -> Unit = {
                runCatching { resources.renderer.close() }
                    .onFailure { LOG.warn("Failed to release item icon render resources", it) }
            }
            if (queued) Minecraft.getInstance().schedule(Runnable(close)) else close()
        }
    }
    //? }

    private fun clearTarget(target: RenderTarget) {
        //? if >= 1.21.5 {
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        val colorTexture = target.colorTexture ?: return
        val depthTexture = target.depthTexture ?: return
        //? if >= 26.2 {
        val clearColor = Vector4f(0f, 0f, 0f, 0f)
        val clearDepth = 0.0
        //?} else {
        /*val clearColor = 0
        val clearDepth = 1.0
        *///?}
        encoder.clearColorAndDepthTextures(colorTexture, clearColor, depthTexture, clearDepth)
        //?} else if >= 1.21.4 {
        /*target.clear()
        *///?} elif > 1.8.9 {
        /*target.clear(Minecraft.ON_OSX)
        *///?} else {
        /*target.clear()
        *///?}
    }

    @Suppress("LongMethod")
    private fun clearCells(target: RenderTarget, entries: List<IconEntry>, layout: AtlasLayout) {
        val guiSize = layout.iconsPerSide * CELL_SIZE

        //? if >= 1.21.8 {
        val colorTexture = target.colorTexture ?: return
        val depthTexture = target.depthTexture ?: return
        val encoder = RenderSystem.getDevice().createCommandEncoder()
        //~ if < 26.2 '= 0.0' -> '= 1.0'
        val clearDepth = 0.0

        entries.forEach { entry ->
            val left = entry.x * target.width / guiSize
            val top = entry.y * target.height / guiSize
            val right = (entry.x + CELL_SIZE) * target.width / guiSize
            val bottom = (entry.y + CELL_SIZE) * target.height / guiSize

            encoder.clearColorAndDepthTextures(
                colorTexture, GuiRenderer.CLEAR_COLOR, depthTexture, clearDepth,
                left, target.height - bottom, right - left, bottom - top,
                //? if >= 26.3
                0,
            )
        }
        //?} elif = 1.21.5 {
        /*val colorTexture = target.colorTexture ?: return
        val device = RenderSystem.getDevice()
        val encoder = device.createCommandEncoder()
        val clearTile = clearTiles.getOrPut(layout.iconSizePx) {
            device.createTexture(
                "PolyCompose item icon clear tile",
                TextureFormat.RGBA8,
                layout.iconSizePx,
                layout.iconSizePx,
                1,
            ).also { encoder.clearColorTexture(it, 0) }
        }

        entries.forEach { entry ->
            val left = entry.x * target.width / guiSize
            val top = entry.y * target.height / guiSize
            val right = (entry.x + CELL_SIZE) * target.width / guiSize
            val bottom = (entry.y + CELL_SIZE) * target.height / guiSize

            encoder.copyTextureToTexture(
                clearTile, colorTexture, 0,
                left, target.height - bottom, 0, 0,
                right - left, bottom - top,
            )
        }
        target.depthTexture?.let { encoder.clearDepthTexture(it, 1.0) }
        *///?} else {
        /*target.bindWrite(false)
        //? if > 1.8.9 {
        GlStateManager._clearColor(0f, 0f, 0f, 0f)
        GlStateManager._clearDepth(1.0)
        GlStateManager._colorMask(true, true, true, true)
        GlStateManager._depthMask(true)
        GlStateManager._enableScissorTest()
        //?} else {
        /*GlStateManager.clearColor(0f, 0f, 0f, 0f)
        GlStateManager.clearDepth(1.0)
        GlStateManager.colorMask(true, true, true, true)
        GlStateManager.depthMask(true)
        GL11.glEnable(GL11.GL_SCISSOR_TEST)
        *///?}

        try {
            entries.forEach { entry ->
                val left = entry.x * target.width / guiSize
                val top = entry.y * target.height / guiSize
                val right = (entry.x + CELL_SIZE) * target.width / guiSize
                val bottom = (entry.y + CELL_SIZE) * target.height / guiSize
                val width = right - left
                val height = bottom - top
                //? if > 1.8.9 {
                GlStateManager._scissorBox(left, target.height - bottom, width, height)
                //? if >= 1.21.4 {
                GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
                //?} else
                //GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX)
                //?} else {
                /*GL11.glScissor(left, target.height - bottom, width, height)
                GlStateManager.clear(GL11.GL_COLOR_BUFFER_BIT or GL11.GL_DEPTH_BUFFER_BIT)
                *///?}
            }
        } finally {
            //? if > 1.8.9 {
            GlStateManager._disableScissorTest()
            //?} else
            //GL11.glDisable(GL11.GL_SCISSOR_TEST)
            Minecraft.getInstance().mainRenderTarget.bindWrite(true)
        }
        *///?}
    }

    private fun reconcileTopology() {
        if (!topologyDirty) return
        synchronized(lock) {
            if (!topologyDirty) return
            if (active.isEmpty()) {
                destroyAtlases()
                topologyDirty = false
                releaseHooks()
                return
            }

            val maxTextureSizePx = maxSupportedTextureSize()
            for (page in atlases.values) {
                for (index in page.entries.lastIndex downTo 0) {
                    val entry = page.entries[index]
                    if (entry !in active || entry.requestedSizePx != page.renderSizePx) releaseSlot(entry)
                }
            }
            active.forEach { entry ->
                if (entry.atlas != null || entry.requestedSizePx <= 0) return@forEach
                entry.failedStack = findFailedVariant(entry.stack)
                entry.failed = entry.failedStack != null
                val page = atlases.getOrPut(entry.requestedSizePx) { AtlasState(entry.requestedSizePx) }
                allocateSlot(page, entry, maxTextureSizePx)
            }

            val iterator = atlases.values.iterator()
            while (iterator.hasNext()) {
                val page = iterator.next()
                if (page.entries.isEmpty()) {
                    disposeAtlas(page)
                    //? if >= 1.21.8
                    discardItemGuiResources(page)
                    iterator.remove()
                }
            }

            //? if = 1.21.5 {
            /*val tileIterator = clearTiles.iterator()
            while (tileIterator.hasNext()) {
                val (size, texture) = tileIterator.next()
                if (atlases.values.none { it.layout?.iconSizePx == size }) {
                    texture.close()
                    tileIterator.remove()
                }
            }
            *///?}
            topologyDirty = false
        }
    }

    private fun allocateSlot(page: AtlasState, entry: IconEntry, maxTextureSizePx: Int) {
        takeFreeSlot(page)?.let { slot ->
            occupy(page, entry, slot)
            return
        }
        val side = ceil(sqrt(((page.layout?.capacity ?: 0) + 1) * 1.5)).toInt()
        val cellSizePx = page.renderSizePx.coerceAtMost(maxTextureSizePx / side).coerceAtLeast(1)
        val grown = AtlasLayout(side, cellSizePx)
        //? if >= 1.21.8
        if (page.layout?.iconSizePx != cellSizePx) discardItemGuiResources(page)
        resetAtlas(page, grown)
        page.entries.forEach { place(it, it.slot, grown) }
        occupy(page, entry, checkNotNull(takeFreeSlot(page)))
    }

    private fun takeFreeSlot(page: AtlasState): Int? {
        page.freeSlots.pollFirst()?.let { return it }
        if (page.nextUnusedSlot >= (page.layout?.capacity ?: 0)) return null
        return page.nextUnusedSlot++
    }

    private fun occupy(page: AtlasState, entry: IconEntry, slot: Int) {
        check(entry.atlas == null) { "Item atlas entry is already placed" }
        entry.atlas = page
        place(entry, slot, checkNotNull(page.layout))
        page.entries.add(entry)
        entry.identityInitialized = false
        page.cleanTick = -1
    }

    private fun releaseSlot(entry: IconEntry) {
        val page = entry.atlas ?: return
        page.entries.remove(entry)
        entry.atlas = null
        page.freeSlots.addLast(entry.slot)
    }

    private fun maxSupportedTextureSize(): Int {
        //? if >= 26.2 {
        return RenderSystem.getDevice().deviceInfo.limits().maxTextureSizeForFormat(GpuFormat.RGBA8_UNORM)
        //?} else if >= 1.21.5 {
        /*return RenderSystem.getDevice().getMaxTextureSize()
        *///?} elif > 1.8.9 {
        /*return RenderSystem.maxSupportedTextureSize()
        *///?} else
        //return Minecraft.getMaxTextureSize()
    }

    private fun resetAtlas(state: AtlasState, layout: AtlasLayout?) {
        synchronized(lock) {
            state.layout = layout
            state.rebuild = true
        }
    }

    private fun destroyAtlases(queueRendererDisposal: Boolean = false) {
        synchronized(lock) {
            invalidateEntries(active.toList())
            atlases.values.forEach { page ->
                disposeAtlas(page)
                //? if >= 1.21.8
                discardItemGuiResources(page, queueRendererDisposal)
            }
            //? if = 1.21.5 {
            /*clearTiles.values.forEach(GpuTexture::close)
            clearTiles.clear()
            *///?}
            atlases.clear()
            topologyDirty = true
        }
    }

    private fun disposeAtlas(state: AtlasState) {
        state.offscreenTarget.dispose()
        state.entries.forEach { it.atlas = null }
        state.entries.clear()
        state.freeSlots.clear()
        state.nextUnusedSlot = 0
    }

    private fun place(entry: IconEntry, slot: Int, layout: AtlasLayout) {
        val x = slot % layout.iconsPerSide * CELL_SIZE
        val y = slot / layout.iconsPerSide * CELL_SIZE
        val scale = layout.iconSizePx.toFloat() / CELL_SIZE
        entry.slot = slot
        entry.x = x
        entry.y = y
        entry.atlasSource[0] = x * scale
        entry.atlasSource[1] = y * scale
        entry.atlasSource[2] = (x + CELL_SIZE) * scale
        entry.atlasSource[3] = (y + CELL_SIZE) * scale
    }
}
