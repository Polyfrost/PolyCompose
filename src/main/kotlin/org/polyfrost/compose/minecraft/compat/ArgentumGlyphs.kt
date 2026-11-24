package org.polyfrost.compose.minecraft.compat

//? if = 1.8.9 {
/*import dev.rdh.argentum.api.render.text.GlyphInfo
import dev.rdh.argentum.api.render.text.TextGlyphs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import net.minecraft.client.render.TextRenderer
import org.polyfrost.compose.host.HostErrors
import org.polyfrost.compose.mc.McGlyph
import org.polyfrost.compose.minecraft.platform.LoadedMods
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.render.PolyBitmap

object ArgentumGlyphs {
    val available: Boolean by lazy {
        LoadedMods.isLoaded("argentum") &&
            runCatching { Class.forName("dev.rdh.argentum.api.render.text.TextGlyphs", false, ArgentumGlyphs::class.java.classLoader) }.isSuccess
    }

    private val MISSING = McGlyph(null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    @Volatile private var warned = false

    // Argentum loads its fonts lazily and isn't thread-safe, so glyphs are only resolved on the render thread
    class Source(private val renderer: TextRenderer) {
        private val glyphs = ConcurrentHashMap<Int, McGlyph>()

        fun glyph(cp: Int): McGlyph? {
            glyphs[cp]?.let { return it.takeIf { it !== MISSING } }
            if (!RenderThread.isCurrent()) {
                if (!warned) {
                    warned = true
                    HostErrors.warn("A Minecraft-font glyph was looked up off the render thread; it falls back to the bundled font", IllegalStateException("Called from thread ${Thread.currentThread().name}"))
                }
                return null
            }
            val g = TextGlyphs.glyph(renderer, cp)?.let(::convert) ?: MISSING
            return (glyphs.putIfAbsent(cp, g) ?: g).takeIf { it !== MISSING }
        }

        fun obfuscate(cp: Int): Int = if (RenderThread.isCurrent()) TextGlyphs.obfuscate(renderer, cp, ThreadLocalRandom.current()) else cp
    }

    private fun convert(info: GlyphInfo): McGlyph {
        val boldAdvance = if (info.advance > 0f) 1f else 0f
        val shadow = if (info.boldShift < 1f) 1f - info.boldShift else 1f
        val width = info.width.coerceAtLeast(0f)
        val argb = info.argb
        if (argb == null || width == 0f || info.imageWidth == 0 || info.imageHeight == 0) return McGlyph(null, 0f, 0f, 0f, 0f, info.top, width, info.height, info.advance, info.boldShift, boldAdvance, shadow)
        val w = info.imageWidth
        val h = info.imageHeight
        val bw = w + 2
        val bh = h + 2
        val pixels = ByteArray(bw * bh * 4)
        for (y in 0 until bh) for (x in 0 until bw) {
            val c = argb[(y - 1).coerceIn(0, h - 1) * w + (x - 1).coerceIn(0, w - 1)]
            val o = (y * bw + x) * 4
            pixels[o] = (c shr 16).toByte()
            pixels[o + 1] = (c shr 8).toByte()
            pixels[o + 2] = c.toByte()
            pixels[o + 3] = (c ushr 24).toByte()
        }
        val texels = info.texelsPerUnit
        return McGlyph(
            PolyBitmap(bw, bh, pixels), 1f / bw, 1f / bh, (1f + width * texels) / bw, (1f + info.height * texels) / bh,
            info.top, width, info.height, info.advance, info.boldShift, boldAdvance, shadow,
        )
    }
}
*///?}
