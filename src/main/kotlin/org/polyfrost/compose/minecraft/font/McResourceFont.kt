package org.polyfrost.compose.minecraft.font

import com.google.gson.JsonObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream
import kotlin.jvm.optionals.getOrNull
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManager
import org.polyfrost.compose.mc.McFontQueue
import org.polyfrost.compose.mc.McGlyph
import org.polyfrost.compose.mc.McGlyphs
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.PolyBitmap
import org.polyfrost.compose.host.ComposeHost
import org.polyfrost.compose.host.HostCallbacks
import org.polyfrost.compose.minecraft.platform.BackgroundTasks
import org.polyfrost.compose.minecraft.platform.HostEvents
import org.polyfrost.compose.minecraft.platform.HostJson
import org.polyfrost.compose.minecraft.platform.RenderThread
import org.slf4j.LoggerFactory
//? if > 1.8.9 {
import net.minecraft.resources.Identifier
//?} else {
/*import net.ornithemc.osl.core.api.util.NamespacedIdentifiers
import org.polyfrost.compose.minecraft.compat.ArgentumCompat
*///?}

internal object McResourceFont {
    private val LOGGER = LoggerFactory.getLogger("PolyCompose/McResourceFont")

    private const val ROOT_FONT = "minecraft:default"
    private const val OPTION_UNIFORM = 1
    private const val OPTION_JP = 2
    private const val UNIHEX_BLOCK_MASK = 0x7F
    private const val UNIHEX_HEIGHT = 16
    private const val UNIHEX_SHEET_WIDTH = 256
    //? if = 1.8.9 {
    /*private const val SHEET_CHARS =
        "ÀÁÂÈÊËÍÓÔÕÚßãõğİ" +
        "ıŒœŞşŴŵžȇ\u0000\u0000\u0000\u0000\u0000\u0000\u0000" +
        " !\"#\$%&'()*+,-./" +
        "0123456789:;<=>?" +
        "@ABCDEFGHIJKLMNO" +
        "PQRSTUVWXYZ[\\]^_" +
        "`abcdefghijklmno" +
        "pqrstuvwxyz{|}~\u0000" +
        "ÇüéâäàåçêëèïîìÄÅ" +
        "ÉæÆôöòûùÿÖÜø£Ø×ƒ" +
        "áíóúñÑªº¿®¬½¼¡«»" +
        "░▒▓│┤╡╢╖╕╣║╗╝╜╛┐" +
        "└┴┬├─┼╞╟╚╔╩╦╠═╬╧" +
        "╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
        "αβΓπΣσμτΦΘΩδ∞∅∈∩" +
        "≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u0000"
    *///?}

    private class Unihex(val rows: IntArray, val left: Int, val right: Int)

    private class Layer(
        val glyphs: Map<Int, McGlyph>,
        val unihex: Map<Int, Unihex>,
        val ttf: Ttf? = null,
        val legacy: LegacyUnicode? = null,
    ) {
        private val baked = ConcurrentHashMap<Int, McGlyph>()

        fun glyph(cp: Int): McGlyph? {
            if (ttf != null) return ttf.glyph(cp)
            if (legacy != null) return baked[cp] ?: legacy.glyph(cp)?.let { baked.putIfAbsent(cp, it) ?: it }
            glyphs[cp]?.let { return it }
            baked[cp]?.let { return it }
            if (cp !in unihex) return null
            val block = bakeBlock(cp and UNIHEX_BLOCK_MASK.inv(), unihex)
            for ((code, g) in block) baked.putIfAbsent(code, g)
            return baked[cp]
        }
    }

    private class LegacyUnicode(val sizes: ByteArray, val boldOffset: Float, val shadow: Float, val readPage: (Int) -> PolyBitmap?) {
        private val pages = ConcurrentHashMap<Int, Optional<PolyBitmap>>()

        fun glyph(cp: Int): McGlyph? {
            val size = sizes.getOrNull(cp)?.toInt()?.and(0xFF) ?: return null
            if (size == 0) return null
            val start = size ushr 4
            val columns = (size and 15) + 1 - start
            if (columns <= 0) return null

            val page = pages.computeIfAbsent(cp ushr 8) { Optional.ofNullable(readPage(it)) }.orElse(null) ?: return null
            val x = (cp and 15) * 16 + start
            val y = (cp ushr 4 and 15) * 16
            return McGlyph(
                page, x / 256f, y / 256f, (x + columns) / 256f, (y + 16) / 256f,
                0f, columns / 2f, 8f, (columns / 2 + 1).toFloat(), boldOffset, 1f, shadow,
            )
        }
    }

    private class Ttf(
        private val face: StbFonts.Face,
        private val size: Float,
        private val oversample: Float,
        private val shiftX: Float,
        private val shiftY: Float,
        private val skip: Set<Int>,
    ) {
        private val cache = ConcurrentHashMap<Int, McGlyph>()

        fun glyph(cp: Int): McGlyph? {
            if (cp in skip) return null
            val cached = cache[cp]
            if (cached != null) return cached.takeIf { it !== MISSING }
            val baked = bake(cp)
            cache.putIfAbsent(cp, baked ?: MISSING)
            return baked
        }

        private fun bake(cp: Int): McGlyph? {
            val id = face.glyph(cp)
            if (id == 0) return null
            val advance = face.advance(id) * size
            val raster = face.raster(id, size * oversample, 0f)
            if (raster == null || raster.w <= 0 || raster.h <= 0) return McGlyph(null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, advance, 1f)
            val pad = Math.round((raster.offX / oversample + shiftX) * oversample).coerceAtLeast(0)
            val width = raster.w + pad
            val pixels = ByteArray(width * raster.h * 4)
            for (row in 0 until raster.h) for (col in 0 until raster.w) {
                val o = (row * width + pad + col) * 4
                pixels[o] = -1
                pixels[o + 1] = -1
                pixels[o + 2] = -1
                pixels[o + 3] = raster.coverage[row * raster.w + col]
            }
            return McGlyph(
                PolyBitmap(width, raster.h, pixels), 0f, 0f, 1f, 1f,
                7f + raster.offY / oversample + shiftY, width / oversample, raster.h / oversample, advance, 1f,
            )
        }

        fun free() = face.free()

        private companion object {
            val MISSING = McGlyph(null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        }
    }

    private class Font(private val layers: List<Layer>) {
        fun glyph(cp: Int): McGlyph? {
            for (i in layers.indices) layers[i].glyph(cp)?.let { return it }
            return null
        }

        fun close() {
            for (layer in layers) layer.ttf?.free()
        }
    }

    @Volatile private var font: Font? = null
    @Volatile private var requested = -1
    @Volatile private var wanted = false
    private val generation = AtomicInteger()

    fun init() {
        McGlyphs.source = { cp -> font?.glyph(cp) }
        McFontQueue.measureWidth = McGlyphs::width
        McFontQueue.measureLiteralWidth = { text, scale -> McGlyphs.width(text, scale, true) }
        HostEvents.onResourcesReloaded(::reload)
        // vanilla applies font option changes without a resource reload
        HostEvents.onClientTick { if (requested != fontOptions()) reload() }
    }

    fun initGlyphs() {
        McGlyphs.source = { cp -> font?.glyph(cp) ?: demand() }
        HostEvents.onResourcesReloaded { if (wanted) reload() }
        HostEvents.onClientTick { if (wanted && requested != fontOptions()) reload() }
    }

    private fun demand(): McGlyph? {
        if (!wanted) wanted = true
        return null
    }

    private fun reload() {
        //? if = 1.8.9
        //val renderer = captureRenderer() ?: return
        val options = fontOptions()
        requested = options
        val gen = generation.incrementAndGet()
        //~ if = 1.8.9 'Minecraft.getInstance().resourceManager' -> 'ResourceManager.client()'
        val rm = Minecraft.getInstance().resourceManager
        BackgroundTasks.submit {
            //~ if = 1.8.9 'load(rm, options)' -> 'loadRendererFont(rm, renderer)'
            val loaded = runCatching { load(rm, options) }.onFailure { LOGGER.warn("Failed to load the game font", it) }.getOrNull()
            RenderThread.schedule(Runnable {
                if (gen != generation.get()) {
                    loaded?.close()
                    return@Runnable
                }
                val previous = font
                font = loaded
                previous?.close()
                FontManager.invalidateMetrics()
                HostCallbacks.each(ComposeHost.fontListeners, "fonts changed listener") { it.run() }
            })
        }
    }

    private fun fontOptions(): Int {
        //? if > 1.8.9 {
        val options = Minecraft.getInstance().options
        return (if (options.forceUnicodeFont().get()) OPTION_UNIFORM else 0) or (if (options.japaneseGlyphVariants().get()) OPTION_JP else 0)
        //?} else {
        /*return if (Minecraft.getInstance().font?.unicode == true) OPTION_UNIFORM else 0
        *///?}
    }

    private fun load(rm: ResourceManager, options: Int): Font? {
        val providers = ArrayList<JsonObject>()
        collectProviders(rm, ROOT_FONT, providers, HashSet(), options)
        val layers = ArrayList<Layer>()
        var glyphs = HashMap<Int, McGlyph>()
        var unihex = HashMap<Int, Unihex>()
        fun flush() {
            if (glyphs.isNotEmpty() || unihex.isNotEmpty()) layers += Layer(glyphs, unihex)
            glyphs = HashMap()
            unihex = HashMap()
        }
        // like vanilla, the first provider with a codepoint wins
        fun unclaimed(cp: Int) = cp !in glyphs && cp !in unihex
        for (provider in providers) {
            val type = provider.get("type")?.asString
            runCatching {
                when (type) {
                    "space" -> provider.getAsJsonObject("advances")?.entrySet()?.forEach { (key, advance) ->
                        if (key.isEmpty()) return@forEach
                        val cp = key.codePointAt(0)
                        if (unclaimed(cp)) glyphs[cp] = McGlyph(null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, advance.asFloat, 1f)
                    }
                    "bitmap" -> loadBitmap(rm, provider).forEach { (cp, g) -> if (unclaimed(cp)) glyphs[cp] = g }
                    "unihex" -> loadUnihex(rm, provider).forEach { (cp, u) -> if (unclaimed(cp)) unihex[cp] = u }
                    "legacy_unicode" -> loadLegacyUnicode(rm, provider)?.let {
                        flush()
                        layers += Layer(emptyMap(), emptyMap(), legacy = it)
                    }
                    "ttf" -> loadTtf(rm, provider)?.let {
                        flush()
                        layers += Layer(emptyMap(), emptyMap(), it)
                    }
                }
            }.onFailure { LOGGER.warn("Failed to load {} font provider, skipping", type, it) }
        }
        flush()
        return if (layers.isEmpty()) null else Font(layers)
    }

    //? if = 1.8.9 {
    /*private class RendererState(val sheet: String, val advances: FloatArray, val unicode: Boolean, val argentum: Boolean)

    private fun captureRenderer(): RendererState? {
        val renderer = Minecraft.getInstance().font ?: return null
        val argentum = ArgentumCompat.sheetAdvances(renderer)
        val advances = argentum ?: FloatArray(SHEET_CHARS.length) { renderer.getWidth(SHEET_CHARS[it]).toFloat() }
        return RendererState(renderer.fontLocation.toString(), advances, renderer.unicode, argentum != null)
    }

    private fun loadRendererFont(rm: ResourceManager, renderer: RendererState): Font {
        val glyphs = HashMap<Int, McGlyph>()
        glyphs[' '.code] = McGlyph(null, 0f, 0f, 0f, 0f, 0f, 0f, 0f, renderer.advances[SHEET_CHARS.indexOf(' ')], 1f)
        if (!renderer.unicode) loadRendererSheet(rm, renderer, glyphs)
        val layers = arrayListOf(Layer(glyphs, emptyMap()))
        val vanillaWhole = !renderer.argentum && !renderer.unicode
        loadLegacyUnicode(rm, "minecraft:font/glyph_sizes.bin", "minecraft:font/unicode_page_%s.png", if (vanillaWhole) 1f else 0.5f, if (vanillaWhole) 0f else 0.5f)
            ?.let { layers += Layer(emptyMap(), emptyMap(), legacy = it) }
        return Font(layers)
    }

    private fun loadRendererSheet(rm: ResourceManager, renderer: RendererState, out: MutableMap<Int, McGlyph>) {
        val image = read(rm, renderer.sheet, "")?.let(PolyBitmap::decode) ?: return
        val cellW = image.width / 16
        val cellH = image.height / 16
        if (cellW == 0 || cellH == 0) return
        val sheet = spaced(image, 16, 16, cellW, cellH)
        for ((slot, char) in SHEET_CHARS.withIndex()) {
            if (char == '\u0000' || char == ' ') continue
            val advance = renderer.advances[slot]
            val drawn = (advance - 1f).coerceIn(0f, 8f)
            val x = slot % 16 * cellW
            val y = slot / 16 * cellH
            val inked = drawn > 0f && (y until y + cellH).any { py -> (x until x + cellW).any { px -> image.pixels[(py * image.width + px) * 4 + 3].toInt() != 0 } }
            val sx = slot % 16 * (cellW + 2) + 1
            val sy = slot / 16 * (cellH + 2) + 1
            out[char.code] = McGlyph(
                sheet.takeIf { inked }, sx / sheet.width.toFloat(), sy / sheet.height.toFloat(), (sx + drawn * cellW / 8f) / sheet.width.toFloat(), (sy + cellH) / sheet.height.toFloat(),
                0f, drawn, 8f, advance, 1f,
            )
        }
    }
    *///?}

    private fun loadLegacyUnicode(rm: ResourceManager, provider: JsonObject): LegacyUnicode? {
        val sizesPath = provider.get("sizes")?.asString ?: return null
        val template = provider.get("template")?.asString ?: return null
        return loadLegacyUnicode(rm, sizesPath, template, 0.5f, 0.5f)
    }

    private fun loadLegacyUnicode(rm: ResourceManager, sizesPath: String, template: String, boldOffset: Float, shadow: Float): LegacyUnicode? {
        val sizes = read(rm, sizesPath, "") ?: return null
        return LegacyUnicode(sizes, boldOffset, shadow) { page ->
            val path = template.replace("%s", String.format(Locale.ROOT, "%02x", page))
            read(rm, path, "textures/")?.let(PolyBitmap::decode)
        }
    }

    private fun loadTtf(rm: ResourceManager, provider: JsonObject): Ttf? {
        val file = provider.get("file")?.asString ?: return null
        val bytes = read(rm, file, "font/") ?: return null.also { LOGGER.warn("ttf font provider references missing file {}", file) }
        val shift = provider.getAsJsonArray("shift")
        val skip = HashSet<Int>()
        val skipElement = provider.get("skip")
        if (skipElement != null && skipElement.isJsonArray) skipElement.asJsonArray.forEach { e -> e.asString.codePoints().forEach { skip += it } }
        else if (skipElement != null && skipElement.isJsonPrimitive) skipElement.asString.codePoints().forEach { skip += it }
        val oversample = (provider.get("oversample")?.asFloat ?: 1f).takeIf { it > 0f } ?: 1f
        return Ttf(
            StbFonts.Face(bytes),
            provider.get("size")?.asFloat ?: 11f,
            oversample,
            shift?.get(0)?.asFloat ?: 0f,
            shift?.get(1)?.asFloat ?: 0f,
            skip,
        )
    }

    private fun collectProviders(rm: ResourceManager, ref: String, out: MutableList<JsonObject>, visited: MutableSet<String>, options: Int) {
        if (!visited.add(ref)) return
        val stack = runCatching { rm.getResourceStack(id(ref, "font/", ".json")) }.getOrDefault(emptyList())
        // vanilla lets the highest-priority pack's providers go first
        for (resource in stack.asReversed()) {
            val root = runCatching { resource.open().use { HostJson.parseOrNull(String(it.readAllBytes(), Charsets.UTF_8)) } }.getOrNull()
            val providers = (root as? JsonObject)?.getAsJsonArray("providers") ?: continue
            for (element in providers) {
                val provider = element as? JsonObject ?: continue
                if (!filterPasses(provider, options)) continue
                if (provider.get("type")?.asString == "reference") provider.get("id")?.asString?.let { collectProviders(rm, it, out, visited, options) }
                else out += provider
            }
        }
    }

    private fun filterPasses(provider: JsonObject, options: Int): Boolean {
        val filter = provider.getAsJsonObject("filter") ?: return true
        filter.get("uniform")?.let { if (it.asBoolean != (options and OPTION_UNIFORM != 0)) return false }
        filter.get("jp")?.let { if (it.asBoolean != (options and OPTION_JP != 0)) return false }
        return true
    }

    private fun loadBitmap(rm: ResourceManager, provider: JsonObject): Map<Int, McGlyph> {
        val file = provider.get("file")?.asString ?: return emptyMap()
        val image = read(rm, file, "textures/")?.let(PolyBitmap::decode) ?: return emptyMap()
        val rows = provider.getAsJsonArray("chars")?.map { it.asString.codePoints().toArray() } ?: return emptyMap()
        val cols = rows.firstOrNull()?.size ?: 0
        if (cols == 0 || rows.any { it.size != cols }) return emptyMap()
        val cellW = image.width / cols
        val cellH = image.height / rows.size
        if (cellW == 0 || cellH == 0) return emptyMap()
        val ascent = provider.get("ascent")?.asInt ?: 7
        val scale = (provider.get("height")?.asInt ?: 8).toFloat() / cellH
        val sheet = spaced(image, cols, rows.size, cellW, cellH)
        val out = HashMap<Int, McGlyph>()
        rows.forEachIndexed { r, cps ->
            cps.forEachIndexed { c, cp ->
                if (cp == 0 || cp in out) return@forEachIndexed
                val x = c * cellW
                val y = r * cellH
                val w = (cellW downTo 1).firstOrNull { px -> (y until y + cellH).any { py -> image.pixels[(py * image.width + x + px - 1) * 4 + 3].toInt() != 0 } } ?: 0
                val sx = c * (cellW + 2) + 1
                val sy = r * (cellH + 2) + 1
                out[cp] = McGlyph(
                    sheet.takeIf { w > 0 }, sx / sheet.width.toFloat(), sy / sheet.height.toFloat(), (sx + w) / sheet.width.toFloat(), (sy + cellH) / sheet.height.toFloat(),
                    (7 - ascent).toFloat(), w * scale, cellH * scale, Math.round(w * scale) + 1f, 1f,
                )
            }
        }
        return out
    }

    // repacks the sheet with a 1px border of edge pixels around each cell
    private fun spaced(image: PolyBitmap, cols: Int, rows: Int, cellW: Int, cellH: Int): PolyBitmap {
        val width = cols * (cellW + 2)
        val pixels = ByteArray(width * rows * (cellH + 2) * 4)
        for (r in 0 until rows) for (c in 0 until cols) for (py in -1..cellH) for (px in -1..cellW) {
            val from = ((r * cellH + py.coerceIn(0, cellH - 1)) * image.width + c * cellW + px.coerceIn(0, cellW - 1)) * 4
            image.pixels.copyInto(pixels, ((r * (cellH + 2) + 1 + py) * width + c * (cellW + 2) + 1 + px) * 4, from, from + 4)
        }
        return PolyBitmap(width, rows * (cellH + 2), pixels)
    }

    private fun loadUnihex(rm: ResourceManager, provider: JsonObject): Map<Int, Unihex> {
        val file = provider.get("hex_file")?.asString ?: return emptyMap()
        val bytes = read(rm, file, "") ?: return emptyMap<Int, Unihex>().also { LOGGER.warn("unihex font provider references missing file {}", file) }
        val overrides = provider.getAsJsonArray("size_overrides")?.mapNotNull { element ->
            runCatching {
                val o = element.asJsonObject
                intArrayOf(o.get("from").asString.codePointAt(0), o.get("to").asString.codePointAt(0), o.get("left").asInt, o.get("right").asInt)
            }.getOrNull()
        }.orEmpty()
        val out = HashMap<Int, Unihex>()
        var skipped = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(".hex")) continue
                for (line in String(zip.readBytes(), Charsets.US_ASCII).lineSequence()) {
                    if (line.isNotEmpty() && !parseHex(line, overrides, out)) skipped++
                }
            }
        }
        if (skipped > 0) LOGGER.warn("Skipped {} malformed unihex entries in {}", skipped, file)
        return out
    }

    // "<codepoint>:<16 rows of 2, 4, 6 or 8 hex digits>", rows stored left-aligned
    private fun parseHex(line: String, overrides: List<IntArray>, out: MutableMap<Int, Unihex>): Boolean {
        val colon = line.indexOf(':')
        val perRow = (line.length - colon - 1) / 16
        if (colon !in 4..6 || perRow !in 2..8 step 2 || line.length - colon - 1 != perRow * 16) return false
        val cp = line.substring(0, colon).toIntOrNull(16) ?: return false
        val rows = IntArray(16)
        var mask = 0
        var p = colon + 1
        for (r in 0 until 16) {
            var v = 0
            repeat(perRow) {
                val d = Character.digit(line[p++], 16)
                if (d < 0) return false
                v = (v shl 4) or d
            }
            rows[r] = v shl (32 - perRow * 4)
            mask = mask or rows[r]
        }
        // vanilla applies the first matching override
        val o = overrides.firstOrNull { cp in it[0]..it[1] }
        out[cp] = when {
            o != null -> Unihex(rows, o[2], o[3])
            mask == 0 -> Unihex(rows, 0, perRow * 4)
            else -> Unihex(rows, Integer.numberOfLeadingZeros(mask), 31 - Integer.numberOfTrailingZeros(mask))
        }
        return true
    }

    private fun bakeBlock(base: Int, unihex: Map<Int, Unihex>): Map<Int, McGlyph> {
        val out = HashMap<Int, McGlyph>()
        val inked = ArrayList<Int>()
        for (cp in base..base + UNIHEX_BLOCK_MASK) {
            val u = unihex[cp] ?: continue
            val w = u.right - u.left + 1
            // unihex glyphs are 16px tall drawn at half size
            if (w <= 0 || u.rows.all { it == 0 }) out[cp] = McGlyph(null, 0f, 0f, 1f, 1f, 0f, w / 2f, 8f, (w / 2 + 1).toFloat(), 0.5f)
            else inked += cp
        }
        if (inked.isEmpty()) return out
        val cellH = UNIHEX_HEIGHT + 2
        val xs = IntArray(inked.size)
        val ys = IntArray(inked.size)
        var x = 0
        var y = 0
        var width = 0
        for ((k, cp) in inked.withIndex()) {
            val u = unihex.getValue(cp)
            val cellW = u.right - u.left + 3
            if (x > 0 && x + cellW > UNIHEX_SHEET_WIDTH) { x = 0; y += cellH }
            xs[k] = x
            ys[k] = y
            x += cellW
            width = maxOf(width, x)
        }
        val height = y + cellH
        val px = ByteArray(width * height * 4)
        for ((k, cp) in inked.withIndex()) {
            val u = unihex.getValue(cp)
            val w = u.right - u.left + 1
            for (gy in -1..UNIHEX_HEIGHT) for (gx in -1..w) {
                val bit = u.left + gx.coerceIn(0, w - 1)
                val row = u.rows[gy.coerceIn(0, UNIHEX_HEIGHT - 1)]
                if (bit in 0..31 && (row ushr (31 - bit)) and 1 != 0) {
                    val o = ((ys[k] + 1 + gy) * width + xs[k] + 1 + gx) * 4
                    px.fill(-1, o, o + 4)
                }
            }
        }
        val sheet = PolyBitmap(width, height, px)
        for ((k, cp) in inked.withIndex()) {
            val u = unihex.getValue(cp)
            val w = u.right - u.left + 1
            val sx = xs[k] + 1
            val sy = ys[k] + 1
            out[cp] = McGlyph(
                sheet, sx / width.toFloat(), sy / height.toFloat(), (sx + w) / width.toFloat(), (sy + UNIHEX_HEIGHT) / height.toFloat(),
                0f, w / 2f, 8f, (w / 2 + 1).toFloat(), 0.5f,
            )
        }
        return out
    }

    private fun read(rm: ResourceManager, ref: String, folder: String): ByteArray? =
        runCatching { rm.getResource(id(ref, folder)).getOrNull()?.open()?.use { it.readAllBytes() } }.getOrNull()

    private fun id(ref: String, folder: String, suffix: String = "") =
        //~ if = 1.8.9 'Identifier.fromNamespaceAndPath' -> 'NamespacedIdentifiers.from'
        Identifier.fromNamespaceAndPath(ref.substringBefore(':', "minecraft"), folder + ref.substringAfter(':') + suffix)
}
