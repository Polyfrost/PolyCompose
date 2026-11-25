package org.polyfrost.compose.minecraft.compat

//? if = 1.8.9 {
/*import dev.rdh.argentum.api.render.text.GlyphInfo
import dev.rdh.argentum.api.render.text.TextGlyphs
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import net.minecraft.client.render.TextRenderer
import org.polyfrost.compose.mc.McGlyph
import org.polyfrost.compose.minecraft.platform.LoadedMods
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyBitmap

object ArgentumGlyphs {
    val available: Boolean by lazy {
        LoadedMods.isLoaded("argentum") && !"true".equals(System.getProperty("argentum.vanillaText"), ignoreCase = true) &&
            runCatching { Class.forName("dev.rdh.argentum.api.render.text.TextGlyphs", false, ArgentumGlyphs::class.java.classLoader) }.isSuccess
    }

    private val MISSING = McGlyph(null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)

    // Argentum loads its fonts lazily and isn't thread-safe, so glyphs are only resolved on the render thread
    class Source(private val renderer: TextRenderer) {
        private val glyphs = ConcurrentHashMap<Int, McGlyph>()
        private val pending = ConcurrentHashMap.newKeySet<Int>()
        private val scheduled = AtomicBoolean()

        fun glyph(cp: Int): McGlyph? {
            glyphs[cp]?.let { return it.takeIf { it !== MISSING } }
            if (RenderThread.isCurrent()) return resolve(cp).takeIf { it !== MISSING }
            pending.add(cp)
            if (scheduled.compareAndSet(false, true)) RenderThread.execute(::drain)
            return null
        }

        private fun drain() {
            scheduled.set(false)
            var resolved = false
            val it = pending.iterator()
            while (it.hasNext()) {
                val cp = it.next()
                it.remove()
                resolve(cp)
                resolved = true
            }
            if (resolved) FontManager.invalidateMetrics()
        }

        private fun resolve(cp: Int): McGlyph {
            val g = TextGlyphs.glyph(renderer, cp)?.let(::convert) ?: MISSING
            return glyphs.putIfAbsent(cp, g) ?: g
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
