package org.polyfrost.compose.minecraft.mc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mojang.authlib.GameProfile
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier
import org.polyfrost.compose.backend.drawPoly
import org.polyfrost.compose.composables.NodeCanvas
import org.polyfrost.compose.composables.PolyModifier
import org.polyfrost.compose.composables.size
import org.polyfrost.compose.minecraft.platform.BackgroundTasks
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.mc.McHeadBake
import org.polyfrost.compose.render.FilterQuality
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.render.RenderContext
import org.slf4j.LoggerFactory

//? if >= 1.21.4 {
import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.renderer.texture.DynamicTexture
//?} else {
/*import com.mojang.blaze3d.platform.GlStateManager
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL11
*///?}

//? if > 1.8.9
import net.minecraft.client.resources.DefaultPlayerSkin
//? if = 1.8.9 {
/*import net.minecraft.client.render.texture.HttpTexture
import org.polyfrost.compose.minecraft.mixin.render.HttpTextureAccessor
*///?}

enum class McHeadStyle {
    FACE,
    FLAT,
    LAYERED,
}

class McSkinLayers internal constructor(
    val texture: String,
    val face: PolyBitmap,
    val hat: PolyBitmap,
    val hasHat: Boolean,
) {
    val size: Int
        get() = face.width

    val layered: PolyBitmap by lazy { if (hasHat) McHeadBake.layered(face, hat) else face }

    val flattened: PolyBitmap by lazy { if (hasHat) McHeadBake.flat(face, hat) else face }

    fun bitmap(style: McHeadStyle): PolyBitmap = when {
        !hasHat || style == McHeadStyle.FACE -> face
        style == McHeadStyle.FLAT -> flattened
        else -> layered
    }
}

sealed interface McSkinLayersResult {
    data object Pending : McSkinLayersResult

    data object Unreadable : McSkinLayersResult

    class Loaded(val layers: McSkinLayers) : McSkinLayersResult
}

object McPlayerFaces {
    private val LOG = LoggerFactory.getLogger("PolyCompose/PlayerFaces")
    private const val MAX_ENTRIES = 256
    private const val MAX_LAYER_SCALE = 8
    private const val TIMEOUT_MS = 8000
    private const val MAX_ATTEMPTS = 6
    private const val MAX_CONCURRENT_FETCHES = 4
    private const val RETRY_BASE_MS = 15_000L
    private const val RETRY_MAX_MS = 10L * 60 * 1000
    private const val CLIENT_RECHECK_NS = 1_000_000_000L
    private const val UNREADABLE_RETRY_NS = 1_000_000_000L
    private const val ACTIVE_NS = 10_000_000_000L
    private const val IDLE_NS = 30_000_000_000L
    private const val TICK_INTERVAL = 20
    private const val MAX_SKIN_SIDE = 4096
    private val TEXTURES_VALUE = Regex("\"name\"\\s*:\\s*\"textures\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\"")
    private val SKIN_URL = Regex("\"SKIN\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\"")

    private class Entry(val id: UUID) {
        @Volatile var fetched: McSkinLayers? = null
        @Volatile var settled = false
        @Volatile var fetching = false
        var attempts = 0
        var clientKey: String? = null
        var clientTexture: Any? = null
        var client: McSkinLayers? = null
        var observed: McSkinLayers? = null
        var observedDefault = false
        var clientCheckedAt = 0L
        @Volatile var lastUsed = 0L
    }

    private class TextureLayers(val result: McSkinLayersResult, val readAt: Long, val location: Identifier?, val owner: Any?)

    private val revisionState = mutableIntStateOf(0)
    private val entries = lru<UUID, Entry>()
    private val fetchQueue = ArrayDeque<Entry>()
    private var activeFetches = 0
    private val defaults = lru<String, McSkinLayers?>()
    private val textures = lru<String, TextureLayers>()
    private var tickHook: AutoCloseable? = null
    private var reloadHook: AutoCloseable? = null
    @Volatile private var hooksQueued = false
    private var ticks = 0

    @JvmStatic
    val revision: Int
        get() = revisionState.intValue

    @JvmStatic
    fun localPlayerId(): UUID? = runCatching {
        //? if > 1.8.9 {
        Minecraft.getInstance().user.profileId
        //?} else
        //Minecraft.getInstance().session.profile.id
    }.getOrNull()

    @JvmStatic
    @JvmOverloads
    fun face(id: UUID?, hat: Boolean = true): PolyBitmap? {
        val layers = layers(id) ?: return null
        return if (hat) layers.flattened else layers.face
    }

    @JvmStatic
    fun face(profile: GameProfile, hat: Boolean): PolyBitmap? = face(profile.id, hat)

    @JvmStatic
    fun layers(id: UUID?): McSkinLayers? {
        val target = id ?: localPlayerId() ?: return null
        ensureHooks()
        val now = System.nanoTime()
        val entry = synchronized(entries) { entries.getOrPut(target) { Entry(target) } }
        entry.lastUsed = now
        if (RenderThread.isCurrent()) refreshClient(entry, now)
        val client = entry.client
        if (client != null) return client
        ensureFetch(entry)
        return entry.fetched ?: defaultLayers(target)
    }

    @JvmStatic
    fun defaultLayers(id: UUID): McSkinLayers? {
        ensureHooks()
        val (namespace, path) = defaultSkin(id)
        val key = "$namespace:$path"
        synchronized(defaults) { if (defaults.containsKey(key)) return defaults[key] }
        val layers = McResources.image(namespace, path)?.let { buildLayers(key, it, opaqueFace = true) }
        synchronized(defaults) { defaults[key] = layers }
        return layers
    }

    @JvmStatic
    fun layers(texture: String): McSkinLayersResult {
        ensureHooks()
        val now = System.nanoTime()
        val render = RenderThread.isCurrent()
        val known = synchronized(textures) { textures[texture] }
        if (known != null) {
            if (known.result !is McSkinLayersResult.Loaded) {
                if (now - known.readAt < UNREADABLE_RETRY_NS) return known.result
            } else if (!render || known.location == null || textureOwner(known.location) === known.owner) {
                return known.result
            }
        }
        if (!render) return McSkinLayersResult.Pending
        val location = parse(texture) ?: return McSkinLayersResult.Unreadable
        val result = when (download(location)) {
            Download.PENDING -> McSkinLayersResult.Pending
            Download.FAILED -> McSkinLayersResult.Unreadable
            Download.DONE -> {
                val bitmap = readTexture(location) ?: McResources.image(location.namespace, location.path)
                bitmap?.let { buildLayers(texture, it, opaqueFace = false) }?.let(McSkinLayersResult::Loaded)
                    ?: if (awaitingUpload(location)) McSkinLayersResult.Pending else McSkinLayersResult.Unreadable
            }
        }
        val owner = if (result is McSkinLayersResult.Loaded) textureOwner(location) else null
        synchronized(textures) { textures[texture] = TextureLayers(result, now, location, owner) }
        return result
    }

    @JvmStatic
    @JvmOverloads
    fun draw(ctx: RenderContext, layers: McSkinLayers, x: Float, y: Float, w: Float, h: Float, hat: Boolean = true) {
        if (w <= 0f || h <= 0f) return
        ctx.image(if (hat && layers.hasHat) layers.flattened else layers.face, x, y, w, h, FilterQuality.None)
    }

    @JvmStatic
    @JvmOverloads
    fun drawHead(
        ctx: RenderContext,
        layers: McSkinLayers,
        x: Float,
        y: Float,
        size: Float,
        style: McHeadStyle = McHeadStyle.FLAT,
        flip: Boolean = false,
        tint: Int = -1,
    ) {
        if (size <= 0f || tint ushr 24 == 0) return
        val overhang = if (style == McHeadStyle.LAYERED && layers.hasHat) size / 16f else 0f
        val side = size + 2f * overhang
        val v0 = if (flip) 1f else 0f
        ctx.renderer.image(layers.bitmap(style), x - overhang, y - overhang, side, side, 0f, v0, 1f, 1f - v0, tint, FilterQuality.None)
    }

    @JvmStatic
    fun invalidate() {
        synchronized(defaults) { defaults.clear() }
        synchronized(textures) { textures.clear() }
        synchronized(entries) {
            for (entry in entries.values) {
                entry.client = null
                entry.clientKey = null
                entry.clientTexture = null
                entry.clientCheckedAt = 0L
                if (entry.observedDefault) entry.observed = null
            }
        }
        bump()
    }

    private fun ensureHooks() {
        if (!RenderThread.isCurrent()) {
            if ((reloadHook == null || tickHook == null) && !hooksQueued) {
                hooksQueued = true
                RenderThread.schedule {
                    hooksQueued = false
                    ensureHooks()
                }
            }
            return
        }
        if (reloadHook == null) reloadHook = HostEvents.onResourcesReloaded(::invalidate)
        if (tickHook == null) tickHook = HostEvents.onClientTick(::onTick)
    }

    private fun onTick() {
        if (++ticks % TICK_INTERVAL != 0) return
        val now = System.nanoTime()
        val recent = synchronized(entries) { entries.values.filter { now - it.lastUsed < ACTIVE_NS } }
        if (recent.isEmpty()) {
            val idle = synchronized(entries) { entries.values.none { now - it.lastUsed < IDLE_NS } }
            if (idle) {
                tickHook?.close()
                tickHook = null
            }
            return
        }
        for (entry in recent) refreshClient(entry, now, force = true)
    }

    private fun refreshClient(entry: Entry, now: Long, force: Boolean = false) {
        if (!force && now - entry.clientCheckedAt < CLIENT_RECHECK_NS) return
        entry.clientCheckedAt = now
        val location = runCatching { clientSkin(entry.id) }.getOrNull()
        val key = location?.toString()
        val owner = location?.let(::textureOwner)
        if (key != null && key == entry.clientKey && owner === entry.clientTexture) return
        val read = when {
            location == null || download(location) != Download.DONE -> null
            key == defaultKey(entry.id) -> replacedDefault(location)?.let { buildLayers(key, it, opaqueFace = true) } ?: defaultLayers(entry.id)
            else -> runCatching { readTexture(location) ?: McResources.image(location.namespace, location.path) }.getOrNull()
                ?.let { buildLayers(key!!, it, opaqueFace = true) }
        }
        if (read != null) {
            entry.observed = read
            entry.observedDefault = key == defaultKey(entry.id)
        }
        entry.clientKey = if (read != null) key else null
        entry.clientTexture = if (read != null) owner else null
        val layers = read ?: entry.observed ?: if (entry.observedDefault) defaultLayers(entry.id) else null
        if (layers === entry.client) return
        entry.client = layers
        bump()
    }

    private fun ensureFetch(entry: Entry) {
        synchronized(entry) {
            if (entry.settled || entry.fetching) return
            entry.fetching = true
        }
        enqueueFetch(entry)
    }

    private fun enqueueFetch(entry: Entry) {
        synchronized(fetchQueue) { fetchQueue.addLast(entry) }
        pumpFetches()
    }

    private fun pumpFetches() {
        while (true) {
            val next = synchronized(fetchQueue) {
                if (activeFetches >= MAX_CONCURRENT_FETCHES) null else fetchQueue.removeFirstOrNull()?.also { activeFetches++ }
            } ?: return
            BackgroundTasks.submit {
                try {
                    fetch(next)
                } finally {
                    synchronized(fetchQueue) { activeFetches-- }
                    pumpFetches()
                }
            }
        }
    }

    private fun fetch(entry: Entry) {
        val outcome = runCatching { download(entry.id) }
        val layers = outcome.getOrNull()
        if (outcome.isSuccess) {
            RenderThread.schedule {
                entry.fetched = layers
                entry.settled = true
                entry.fetching = false
                if (layers != null) bump()
            }
            return
        }
        LOG.debug("Failed to load the skin of {}", entry.id, outcome.exceptionOrNull())
        entry.attempts++
        if (entry.attempts >= MAX_ATTEMPTS) {
            entry.settled = true
            entry.fetching = false
            return
        }
        val delay = (RETRY_BASE_MS shl (entry.attempts - 1)).coerceAtMost(RETRY_MAX_MS)
        BackgroundTasks.schedule({ enqueueFetch(entry) }, delay, TimeUnit.MILLISECONDS)
    }

    private fun download(id: UUID): McSkinLayers? {
        val profile = httpGet("https://sessionserver.mojang.com/session/minecraft/profile/${id.toString().replace("-", "")}") ?: return null
        val encoded = TEXTURES_VALUE.find(String(profile, StandardCharsets.UTF_8))?.groupValues?.get(1) ?: return null
        val textures = String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8)
        val url = SKIN_URL.find(textures)?.groupValues?.get(1) ?: return null
        val bytes = httpGet(url) ?: return null
        val skin = PolyBitmap.decode(bytes) ?: return null
        return buildLayers(url, skin, opaqueFace = true)
    }

    private fun httpGet(url: String): ByteArray? {
        val connection = URI(url).toURL().openConnection(proxy()) as HttpURLConnection
        return try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readBytes() }
                HttpURLConnection.HTTP_NO_CONTENT, HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("GET $url returned $code")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun proxy(): Proxy = runCatching {
        //? if > 1.8.9 {
        Minecraft.getInstance().proxy
        //?} else
        //Minecraft.getInstance().networkProxy
    }.getOrNull() ?: Proxy.NO_PROXY

    private fun bump() {
        if (!RenderThread.isCurrent()) {
            RenderThread.schedule(::bump)
            return
        }
        Snapshot.withMutableSnapshot { revisionState.intValue++ }
    }

    private fun parse(texture: String): Identifier? = runCatching {
        //? if > 1.8.9 {
        Identifier.tryParse(texture)
        //?} else
        //Identifier(texture)
    }.getOrNull()

    private fun clientSkin(id: UUID): Identifier? {
        val mc = Minecraft.getInstance()
        //? if > 1.8.9 {
        val skin = mc.player?.takeIf { it.uuid == id }?.skin ?: mc.connection?.getPlayerInfo(id)?.skin ?: return null
        //? if >= 1.21.10 {
        return skin.body().texturePath()
        //?} else
        //return skin.texture()
        //?} else {
        /*return mc.player?.takeIf { it.getUuid() == id }?.skinTextureLocation ?: mc.networkHandler?.getOnlinePlayer(id)?.skinTexture
        *///?}
    }

    private fun defaultKey(id: UUID): String = defaultSkin(id).let { (namespace, path) -> "$namespace:$path" }

    private fun defaultSkin(id: UUID): Pair<String, String> {
        //? if > 1.8.9 {
        //? if >= 1.21.10 {
        val location = DefaultPlayerSkin.get(id).body().texturePath()
        //?} else
        //val location = DefaultPlayerSkin.get(id).texture()
        return location.namespace to location.path
        //?} else
        //return McResources.DEFAULT_NAMESPACE to if ((id.hashCode() and 1) == 1) "textures/entity/alex.png" else "textures/entity/steve.png"
    }

    private enum class Download { DONE, PENDING, FAILED }

    private fun download(location: Identifier): Download {
        //? if = 1.8.9 {
        /*val texture = Minecraft.getInstance().textureManager.getTexture(location) as? HttpTexture ?: return Download.DONE
        val access = texture as HttpTextureAccessor
        fun uploaded(): Boolean {
            val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
            try {
                texture.getGlId()
            } finally {
                bindTexture(previous)
            }
            return access.`polycompose$isUploaded`()
        }
        if (uploaded()) return Download.DONE
        val downloader = access.`polycompose$getDownloader`()
        if (downloader == null || downloader.isAlive) return Download.PENDING
        return if (uploaded()) Download.DONE else Download.FAILED
        *///?} else
        return Download.DONE
    }

    private fun textureOwner(location: Identifier): Any? = runCatching { Minecraft.getInstance().textureManager.getTexture(location) }.getOrNull()

    private fun replacedDefault(location: Identifier): PolyBitmap? {
        //? if >= 1.21.4 {
        return runCatching { readTexture(location) }.getOrNull()
        //?} else
        //return null
    }

    private fun awaitingUpload(location: Identifier): Boolean {
        //? if >= 1.21.4 {
        return false
        //?} elif > 1.8.9 {
        /*val texture = Minecraft.getInstance().textureManager.getTexture(location) ?: return false
        return glWidth(texture.id) <= 0
        *///?} else {
        /*val texture = Minecraft.getInstance().textureManager.getTexture(location) ?: return true
        return glWidth(texture.glId) <= 0
        *///?}
    }

    private fun readTexture(location: Identifier): PolyBitmap? {
        val mc = Minecraft.getInstance()
        //? if >= 1.21.4 {
        val texture = mc.textureManager.getTexture(location) as? DynamicTexture ?: return null
        val image: NativeImage? = texture.pixels
        if (image == null) return null
        return fromNative(image)
        //?} elif > 1.8.9 {
        /*val texture = mc.textureManager.getTexture(location) ?: return null
        return glRead(texture.id)
        *///?} else {
        /*val texture = mc.textureManager.getTexture(location) ?: return null
        return glRead(texture.glId)
        *///?}
    }

    //? if >= 1.21.4 {
    private fun fromNative(image: NativeImage): PolyBitmap? {
        val w = image.width
        val h = image.height
        if (w <= 0 || h <= 0 || w > MAX_SKIN_SIDE || h > MAX_SKIN_SIDE) return null
        val out = ByteArray(w * h * 4)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = image.getPixel(x, y)
                val i = (y * w + x) * 4
                out[i] = (argb ushr 16).toByte()
                out[i + 1] = (argb ushr 8).toByte()
                out[i + 2] = argb.toByte()
                out[i + 3] = (argb ushr 24).toByte()
            }
        }
        return PolyBitmap(w, h, out)
    }
    //?} else {
    /*private fun glRead(id: Int): PolyBitmap? {
        if (id <= 0) return null
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        bindTexture(id)
        try {
            val w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
            val h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT)
            if (w <= 0 || h <= 0 || w > MAX_SKIN_SIDE || h > MAX_SKIN_SIDE) return null
            val buffer = BufferUtils.createByteBuffer(w * h * 4)
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buffer)
            val out = ByteArray(w * h * 4)
            buffer.get(out)
            return PolyBitmap(w, h, out)
        } finally {
            bindTexture(previous)
        }
    }

    private fun glWidth(id: Int): Int {
        if (id <= 0) return 0
        val previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D)
        bindTexture(id)
        try {
            return GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH)
        } finally {
            bindTexture(previous)
        }
    }

    private fun bindTexture(id: Int) {
        //? if > 1.8.9 {
        GlStateManager._bindTexture(id)
        //?} else
        //GlStateManager.bindTexture(id)
    }
    *///?}

    private fun buildLayers(texture: String, skin: PolyBitmap, opaqueFace: Boolean): McSkinLayers? {
        val scale = skin.width / 64
        if (scale < 1 || skin.height < 16 * scale) return null
        val step = minOf(scale, MAX_LAYER_SCALE)
        val size = 8 * step
        val legacy = skin.height <= 32 * scale
        val hasHat = !legacy || !isOpaque(skin, 32 * scale, 0, 64 * scale, 32 * scale)
        fun layer(u: Int, opaque: Boolean, visible: Boolean): PolyBitmap {
            val out = ByteArray(size * size * 4)
            if (!visible) return PolyBitmap(size, size, out)
            for (y in 0 until size) {
                val sy = 8 * scale + y * scale / step
                for (x in 0 until size) {
                    val sx = u * scale + x * scale / step
                    val s = (sy * skin.width + sx) * 4
                    val o = (y * size + x) * 4
                    out[o] = skin.pixels[s]
                    out[o + 1] = skin.pixels[s + 1]
                    out[o + 2] = skin.pixels[s + 2]
                    out[o + 3] = if (opaque) 0xFF.toByte() else skin.pixels[s + 3]
                }
            }
            return PolyBitmap(size, size, out)
        }
        val face = layer(8, opaqueFace, true)
        val hat = layer(40, false, hasHat)
        return McSkinLayers(texture, face, hat, hasHat && (3 until hat.pixels.size step 4).any { hat.pixels[it] != 0.toByte() })
    }

    private fun isOpaque(skin: PolyBitmap, x0: Int, y0: Int, x1: Int, y1: Int): Boolean {
        for (y in y0 until minOf(y1, skin.height)) {
            for (x in x0 until minOf(x1, skin.width)) {
                if ((skin.pixels[(y * skin.width + x) * 4 + 3].toInt() and 0xFF) < 128) return false
            }
        }
        return true
    }

    private fun <K, V> lru(): LinkedHashMap<K, V> = object : LinkedHashMap<K, V>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<K, V>): Boolean = size > MAX_ENTRIES
    }
}

private const val FACE_REFRESH_MS = 1000L

@Composable
private fun KeepFaceFresh(id: UUID?) {
    LaunchedEffect(id) {
        while (true) {
            delay(FACE_REFRESH_MS)
            McPlayerFaces.layers(id)
        }
    }
}

@Composable
fun McPlayerFace(
    id: UUID?,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    hat: Boolean = true,
) {
    KeepFaceFresh(id)
    Box(
        modifier
            .size(size)
            .drawBehind {
                McPlayerFaces.revision
                val layers = McPlayerFaces.layers(id) ?: return@drawBehind
                val w = this.size.width
                val h = this.size.height
                drawPoly { McPlayerFaces.draw(it, layers, 0f, 0f, w, h, hat) }
            },
    )
}

@Composable
fun McPlayerFace(
    profile: GameProfile,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    hat: Boolean = true,
) {
    McPlayerFace(profile.id, modifier, size, hat)
}

@Composable
fun NodeMcPlayerFace(
    id: UUID?,
    size: Float = 16f,
    modifier: PolyModifier = PolyModifier,
    hat: Boolean = true,
) {
    KeepFaceFresh(id)
    val revision = McPlayerFaces.revision
    val onDraw: RenderContext.(Float, Float, Float, Float) -> Unit = remember(revision, id, hat) {
        { x, y, w, h ->
            val layers = McPlayerFaces.layers(id)
            if (layers != null) McPlayerFaces.draw(this, layers, x, y, w, h, hat)
        }
    }
    NodeCanvas(modifier.size(size, size), onDraw = onDraw)
}

@Composable
fun McPlayerFace(
    id: UUID?,
    style: McHeadStyle,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    flip: Boolean = false,
) {
    KeepFaceFresh(id)
    Box(
        modifier
            .size(size)
            .drawBehind {
                McPlayerFaces.revision
                val layers = McPlayerFaces.layers(id) ?: return@drawBehind
                val side = minOf(this.size.width, this.size.height)
                val x = (this.size.width - side) / 2f
                val y = (this.size.height - side) / 2f
                drawPoly { McPlayerFaces.drawHead(it, layers, x, y, side, style, flip) }
            },
    )
}

@Composable
fun McPlayerFace(
    profile: GameProfile,
    style: McHeadStyle,
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    flip: Boolean = false,
) {
    McPlayerFace(profile.id, style, modifier, size, flip)
}

@Composable
fun NodeMcPlayerFace(
    id: UUID?,
    style: McHeadStyle,
    size: Float = 16f,
    modifier: PolyModifier = PolyModifier,
    flip: Boolean = false,
) {
    KeepFaceFresh(id)
    val revision = McPlayerFaces.revision
    val onDraw: RenderContext.(Float, Float, Float, Float) -> Unit = remember(revision, id, style, flip) {
        { x, y, w, h ->
            val layers = McPlayerFaces.layers(id)
            val side = minOf(w, h)
            if (layers != null) McPlayerFaces.drawHead(this, layers, x + (w - side) / 2f, y + (h - side) / 2f, side, style, flip)
        }
    }
    NodeCanvas(modifier.size(size, size), onDraw = onDraw)
}
