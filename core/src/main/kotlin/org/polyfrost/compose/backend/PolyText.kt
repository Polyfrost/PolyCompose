@file:OptIn(InternalComposeUiApi::class, ExperimentalTextApi::class)

package org.polyfrost.compose.backend

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmapConfig
import androidx.compose.ui.graphics.LinearGradient
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RadialGradient
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.SweepGradient
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.platform.PlatformShader
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.FontHinting
import androidx.compose.ui.text.FontRasterizationSettings
import androidx.compose.ui.text.FontSmoothing
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.PlatformParagraph
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextGranularity
import androidx.compose.ui.text.TextInclusionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.GenericFontFamily
import androidx.compose.ui.text.font.PlatformTypefacesLoader
import androidx.compose.ui.text.font.createPlatformFontFamilyResolver
import androidx.compose.ui.text.platform.PlatformText
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import com.ibm.icu.lang.UCharacter
import com.ibm.icu.text.ArabicShaping
import org.apache.logging.log4j.LogManager
import org.polyfrost.compose.mc.McGlyphs
import org.polyfrost.compose.render.FontManager
import org.polyfrost.compose.render.BlurPlacement
import org.polyfrost.compose.render.FilterQuality
import org.polyfrost.compose.render.PolyFade
import org.polyfrost.compose.render.PolyFont
import org.polyfrost.compose.render.PolyGlyphPage
import org.polyfrost.compose.render.PolyImageFill
import org.polyfrost.compose.render.ChromaBrush
import org.polyfrost.compose.render.PolyRenderer
import org.polyfrost.compose.render.PolyTessellator
import org.polyfrost.compose.render.laidOut
import org.polyfrost.compose.render.placeBlur
import org.polyfrost.compose.text.McFonts
import java.text.Bidi
import java.text.BreakIterator
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.function.IntSupplier
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class LoadedFace(val key: String, val weight: Int, val italic: Boolean)

private fun faceName(loaded: Any?): String = when (loaded) {
    is LoadedFace -> loaded.key
    is String -> loaded
    else -> FontManager.DEFAULT_KEY
}

private object GenericFonts {
    private val logger by lazy { LogManager.getLogger("PolyCompose") }
    private val reported = ConcurrentHashMap.newKeySet<String>()

    fun resolve(family: FontFamily): String {
        val generic = (family as? GenericFontFamily)?.name ?: return FontManager.DEFAULT_KEY
        if (FontManager.fontData(generic) != null) return generic
        if (generic != FontFamily.SansSerif.name && reported.add(generic)) {
            runCatching { logger.debug("No font registered for the {} family, using the default font", generic) }
        }
        return FontManager.DEFAULT_KEY
    }
}

internal const val LIGATURE_PLACEHOLDER = '\uFFFF'

// Minecraft ships ICU4J; without it Arabic is drawn unjoined and right-to-left brackets unmirrored
internal object Icu {
    private val shaping = runCatching { ArabicShaping(ArabicShaping.LETTERS_SHAPE or ArabicShaping.LENGTH_FIXED_SPACES_NEAR) }.getOrNull()
    private val mirroring = runCatching { UCharacter.getMirror('('.code) == ')'.code }.getOrDefault(false)

    // fixed length keeps offsets aligned with the logical text, leaving a space for each ligature that's replaced by a placeholder
    fun shape(text: String): String {
        if (shaping == null || text.none { it in '\u0600'..'\u08FF' }) return text
        val shaped = try { shaping.shape(text) } catch (_: Exception) { return text }
        if (shaped.length != text.length || ' ' !in shaped) return shaped
        val out = shaped.toCharArray()
        for (i in out.indices) if (out[i] == ' ' && text[i] != ' ') out[i] = LIGATURE_PLACEHOLDER
        return String(out)
    }

    fun mirror(cp: Int) = if (mirroring) UCharacter.getMirror(cp) else cp
}

// keeps combining marks after their base so they still draw over it
internal fun reversedClusters(text: String): String {
    val it = BreakIterator.getCharacterInstance().also { b -> b.setText(text) }
    val out = StringBuilder(text.length)
    var end = it.last()
    var start = it.previous()
    while (start != BreakIterator.DONE) {
        var i = start
        while (i < end) {
            val cp = text.codePointAt(i)
            if (cp != LIGATURE_PLACEHOLDER.code) out.appendCodePoint(Icu.mirror(cp))
            i += Character.charCount(cp)
        }
        end = start
        start = it.previous()
    }
    return out.toString()
}

public class PolyFontSource(public val key: String, private val bytes: () -> ByteArray, override val weight: FontWeight, override val style: FontStyle) : Font {
    internal fun register(): LoadedFace {
        if (FontManager.fontData(key) == null) FontManager.register(key, bytes())
        return LoadedFace(key, weight.weight, style == FontStyle.Italic)
    }

    override fun equals(other: Any?): Boolean = other is PolyFontSource && other.key == key && other.weight == weight && other.style == style
    override fun hashCode(): Int = (key.hashCode() * 31 + weight.hashCode()) * 31 + style.hashCode()
    override fun toString(): String = "PolyFontSource($key, $weight, $style)"
}

public object PolyText : PlatformText {
    public val typefaces: PlatformTypefacesLoader = object : PlatformTypefacesLoader {
        override fun loadBlocking(font: Font): Any? = (font as? PolyFontSource)?.register() ?: (font as? McFonts.Source)?.key
        override suspend fun awaitLoad(font: Font): Any? = loadBlocking(font)
        override val cacheKey: Any? get() = FontManager.familyRevision
        override fun loadPlatformTypes(fontFamily: FontFamily, fontWeight: FontWeight, fontStyle: FontStyle): Any =
            FontManager.resolveWeight(GenericFonts.resolve(fontFamily), fontWeight.weight)
        override val fontCollection: Any = Unit
    }

    override fun createParagraph(
        text: String, style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>, maxLines: Int, overflow: TextOverflow, constraints: Constraints,
        density: Density, fontFamilyResolver: FontFamily.Resolver,
    ): PlatformParagraph = createParagraph(createParagraphIntrinsics(text, style, annotations, placeholders, density, fontFamilyResolver), maxLines, overflow, constraints)

    override fun createParagraph(paragraphIntrinsics: ParagraphIntrinsics, maxLines: Int, overflow: TextOverflow, constraints: Constraints): PlatformParagraph =
        PolyParagraph(paragraphIntrinsics as PolyIntrinsics, maxLines, overflow, if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else Float.POSITIVE_INFINITY)

    override fun createParagraphIntrinsics(
        text: String, style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>, density: Density, fontFamilyResolver: FontFamily.Resolver,
    ): ParagraphIntrinsics = Intrinsics.of(text, style, annotations, placeholders, density, fontFamilyResolver)

    override fun createFontFamilyResolver(): FontFamily.Resolver = createPlatformFontFamilyResolver(typefaces)
    override fun createFontFamilyResolver(coroutineContext: CoroutineContext): FontFamily.Resolver = createPlatformFontFamilyResolver(typefaces, coroutineContext)

    private class CharBreaks {
        var text: String? = null
        val breaks: BreakIterator = BreakIterator.getCharacterInstance()
    }

    private val charBreaks = ThreadLocal.withInitial { CharBreaks() }

    private fun charBreaks(text: String): BreakIterator {
        val cached = charBreaks.get()
        if (cached.text != text) {
            cached.breaks.setText(text)
            cached.text = text
        }
        return cached.breaks
    }

    override fun findPrecedingBreak(text: String, index: Int): Int =
        charBreaks(text).preceding(index.coerceIn(0, text.length)).let { if (it == BreakIterator.DONE) 0 else it }

    override fun findFollowingBreak(text: String, index: Int): Int =
        charBreaks(text).following(index.coerceIn(0, text.length)).let { if (it == BreakIterator.DONE) text.length else it }

    override val defaultFontRasterizationSettings: FontRasterizationSettings = FontRasterizationSettings(FontSmoothing.AntiAlias, FontHinting.Slight, subpixelPositioning = true, autoHintingForced = false)
}

internal class TextRun(
    val start: Int, val end: Int, val font: PolyFont, val style: SpanStyle,
    val spanColor: Color = Color.Unspecified, val spanDecoration: TextDecoration? = null, val spanShadow: Shadow? = null, val skew: Float = 0f,
    val scaleX: Float = 1f, val letter: Float = 0f, val bold: Float = 0f, val placeholder: Placeholder? = null,
    val spanBrush: Brush? = null, val brushAlpha: Float = Float.NaN, val shift: Float = 0f,
) {
    val metrics get() = font.metrics
    val tracking get() = letter + bold
}

private const val BOLD_SYNTHESIS_WEIGHT = 600
private const val FAKE_BOLD_MIN_SIZE = 9f
private const val FAKE_BOLD_MAX_SIZE = 36f
private const val FAKE_BOLD_MIN_SCALE = 1f / 24f
private const val FAKE_BOLD_MAX_SCALE = 1f / 32f

private fun fakeBoldWidth(size: Float): Float {
    val t = ((size - FAKE_BOLD_MIN_SIZE) / (FAKE_BOLD_MAX_SIZE - FAKE_BOLD_MIN_SIZE)).coerceIn(0f, 1f)
    return size * (FAKE_BOLD_MIN_SCALE + (FAKE_BOLD_MAX_SCALE - FAKE_BOLD_MIN_SCALE) * t)
}

internal class PolyIntrinsics(
    val text: String, val style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
    val placeholders: List<AnnotatedString.Range<Placeholder>>, val density: Density, resolver: FontFamily.Resolver,
) : ParagraphIntrinsics {
    // an em paragraph size has no parent to resolve against, so it's relative to the 14 sp default
    val baseSize = style.fontSize.let { if (!it.isSpecified) 14f else if (it.isEm) it.value * 14f else with(density) { it.toPx() } }
    val locale: Locale = style.localeList?.firstOrNull()?.let { Locale.forLanguageTag(it.toLanguageTag()) } ?: Locale.getDefault()
    private val builtRevision = FontManager.revision
    val runs: List<TextRun>
    val glyphs = Icu.shape(text)
    val advances = FloatArray(text.length)
    // prefix sums keep width lookups O(1) for line breaking and bidi hit testing
    private val prefix = DoubleArray(text.length + 1)
    val lineHeightPx: Float
    val lineHeightSpec: Float
    private var kerns: FloatArray? = null
    val align: TextAlign = style.textAlign
    val firstIndent: Float
    val restIndent: Float

    init {
        val base = style.toSpanStyle()
        val spans = if (annotations.isEmpty()) emptyList() else annotations.mapNotNull { r -> (r.item as? SpanStyle)?.let { AnnotatedString.Range(it, r.start.coerceIn(0, text.length), r.end.coerceIn(0, text.length)) } }
        val bounds = if (spans.isEmpty() && placeholders.isEmpty()) {
            if (text.isEmpty()) intArrayOf(0) else intArrayOf(0, text.length)
        } else {
            val cuts = sortedSetOf(0, text.length)
            for (s in spans) { cuts += s.start; cuts += s.end }
            for (p in placeholders) { cuts += p.start.coerceIn(0, text.length); cuts += p.end.coerceIn(0, text.length) }
            cuts.toIntArray()
        }
        val fontCache = HashMap<Pair<String, Float>, PolyFont>()
        runs = ArrayList()
        for (i in 0 until bounds.size - 1) {
            val a = bounds[i]
            val b = bounds[i + 1]
            if (a >= b) continue
            var s = base
            var spanColor = Color.Unspecified
            var spanDecoration: TextDecoration? = null
            var spanShadow: Shadow? = null
            var spanBrush: Brush? = null
            var brushAlpha = Float.NaN
            for (sp in spans) if (sp.start <= a && sp.end >= b) {
                val item = sp.item
                s = s.merge(item)
                val brush = item.brush
                if (brush != null) {
                    if (spanBrush == null || !item.alpha.isNaN()) brushAlpha = item.alpha
                    spanBrush = brush
                    spanColor = Color.Unspecified
                } else if (item.color.isSpecified) {
                    spanColor = item.color
                    spanBrush = null
                    brushAlpha = Float.NaN
                }
                item.textDecoration?.let { spanDecoration = it }
                item.shadow?.let { spanShadow = it }
            }
            val loaded = resolver.resolve(s.fontFamily, s.fontWeight ?: FontWeight.Normal, s.fontStyle ?: FontStyle.Normal, s.fontSynthesis ?: FontSynthesis.All).value
            val name = faceName(loaded)
            val size = if (s.fontSize == style.fontSize) baseSize else px(s.fontSize, baseSize)
            val synthesis = s.fontSynthesis ?: FontSynthesis.All
            // like Skia, slant upright faces when italic is requested and the family has no italic face
            val fakeItalic = s.fontStyle == FontStyle.Italic && (loaded as? LoadedFace)?.italic != true && (synthesis == FontSynthesis.All || synthesis == FontSynthesis.Style)
            val userSkew = s.textGeometricTransform?.skewX ?: 0f
            val skew = (if (fakeItalic) 0.25f else 0f) - userSkew
            val scaleX = s.textGeometricTransform?.scaleX?.takeIf { it > 0f && it.isFinite() } ?: 1f
            val fakeBold = (s.fontWeight ?: FontWeight.Normal).weight >= BOLD_SYNTHESIS_WEIGHT && faceWeight(loaded, name) < BOLD_SYNTHESIS_WEIGHT &&
                !McFonts.isMinecraft(name) && (synthesis == FontSynthesis.All || synthesis == FontSynthesis.Weight)
            val letter = s.letterSpacing.let { if (!it.isSpecified) 0f else if (it.isEm) it.value * size else with(density) { it.toPx() } }
            val placeholder = placeholders.firstOrNull { it.start <= a && it.end >= b }?.item
            val font = fontCache.getOrPut(name to size) { PolyFont(name, size) }
            val shift = s.baselineShift?.multiplier?.takeIf { it.isFinite() && it != 0f }?.let { it * font.metrics.ascent } ?: 0f
            runs += TextRun(
                a, b, font, s, spanColor, spanDecoration, spanShadow, skew,
                scaleX, letter, if (fakeBold) fakeBoldWidth(size) else 0f, placeholder, spanBrush, brushAlpha, shift,
            )
        }
        if (runs.isEmpty()) {
            val name = faceName(resolver.resolve(base.fontFamily, base.fontWeight ?: FontWeight.Normal, base.fontStyle ?: FontStyle.Normal).value)
            runs += TextRun(0, 0, PolyFont(name, baseSize), base)
        }
        // shortcut: one glyph per code point without OpenType shaping, so scripts that reorder or substitute glyphs
        // (e.g. Devanagari) render unshaped; fixing that needs a shaping engine such as HarfBuzz
        val emojiFace = PolyGlyphPage.rasterizer?.emoji()
        for (r in runs.indices) {
            val run = runs[r]
            val placeholder = run.placeholder
            if (placeholder != null) {
                advances[run.start] = placeholderSize(placeholder.width, run)
                continue
            }
            val letter = run.tracking
            val scaleX = run.scaleX
            val mc = McFonts.isMinecraft(run.font.name)
            val kernFace = if (mc) null else PolyGlyphPage.rasterizer?.face(run.font.name)
            val kernScale = run.font.size * scaleX
            var kernAt = -1
            var kernGlyph = 0
            var i = run.start
            while (i < run.end) {
                val cp = glyphs.codePointAt(i)
                val n = Character.charCount(cp)
                val ignorable = PolyGlyphPage.isIgnorable(cp)
                val sequence = if (!mc && emojiFace != null && !ignorable) PolyGlyphPage.sequence(emojiFace, glyphs, i, run.end) else 0L
                if (sequence != 0L) {
                    val end = sequence.toInt()
                    advances[i] = run.font.measureTextWidth(glyphs.substring(i, end)) * scaleX + letter
                    kernAt = -1
                    i = end
                    continue
                }
                val emoji = !mc && i + n < glyphs.length && glyphs.codePointAt(i + n) == PolyGlyphPage.EMOJI_VARIATION
                advances[i] = if (cp == '\n'.code || cp == LIGATURE_PLACEHOLDER.code || ignorable) 0f else Advances.of(run.font, cp, emoji) * scaleX + letter
                if (kernFace != null && !ignorable) {
                    val glyph = if (PolyGlyphPage.kernable(cp)) kernFace.glyph(cp) else 0
                    if (glyph != 0 && kernAt >= 0) kern(kernAt, kernFace.kern(kernGlyph, glyph) * kernScale)
                    kernAt = if (glyph != 0) i else -1
                    kernGlyph = glyph
                }
                i += n
            }
        }
        for (i in advances.indices) prefix[i + 1] = prefix[i] + advances[i]
        val natural = runs.maxOf { it.metrics.spacing }
        lineHeightSpec = if (style.lineHeight.isSpecified) px(style.lineHeight, natural) else Float.NaN
        lineHeightPx = if (style.lineHeight.isSpecified) lineHeightSpec else round(natural)
        firstIndent = style.textIndent?.firstLine?.let { px(it, 0f) }?.takeIf { it.isFinite() } ?: 0f
        restIndent = style.textIndent?.restLine?.let { px(it, 0f) }?.takeIf { it.isFinite() } ?: 0f
    }

    val shifted: Boolean = runs.any { it.shift != 0f && it.placeholder == null }

    private val spaceCounts: IntArray by lazy {
        IntArray(text.length + 1).also { counts ->
            for (i in text.indices) counts[i + 1] = counts[i] + if (Character.isWhitespace(text[i])) 1 else 0
        }
    }

    fun spaces(from: Int, to: Int): Int = if (to <= from) 0 else spaceCounts[to] - spaceCounts[from]

    fun indentAt(lineStart: Int): Float = if (lineStart <= 0 || text[lineStart - 1] == '\n') firstIndent else restIndent

    fun px(unit: TextUnit, fallback: Float): Float = if (!unit.isSpecified) fallback else with(density) {
        if (unit.isEm) unit.value * baseSize else unit.toPx()
    }

    fun placeholderSize(unit: TextUnit, run: TextRun): Float = if (!unit.isSpecified) 0f else with(density) {
        if (unit.isEm) unit.value * run.font.size else unit.toPx()
    }

    private fun faceWeight(loaded: Any?, name: String): Int = (loaded as? LoadedFace)?.weight ?: FontManager.weightOf(name)

    private fun kern(at: Int, amount: Float) {
        if (amount == 0f) return
        advances[at] += amount
        (kerns ?: FloatArray(text.length).also { kerns = it })[at] = amount
    }

    fun kernAfter(at: Int): Float = kerns?.getOrNull(at) ?: 0f

    fun inkWidth(start: Int, end: Int): Float = if (end <= start) 0f else width(start, end) - kernAfter(end - 1)

    fun runIndexAt(offset: Int): Int {
        var lo = 0
        var hi = runs.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (runs[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun runAt(offset: Int): TextRun = runs[runIndexAt(offset)]

    fun width(start: Int, end: Int): Float = if (end <= start) 0f else (prefix[end] - prefix[start]).toFloat()

    override val maxIntrinsicWidth: Float by lazy {
        var best = 0f; var cur = 0f
        for (i in text.indices) { if (text[i] == '\n') { best = max(best, cur + firstIndent); cur = 0f } else cur += advances[i] }
        max(best, cur + firstIndent)
    }

    override val minIntrinsicWidth: Float by lazy {
        val it = BreakIterator.getLineInstance(locale).also { b -> b.setText(text) }
        var best = firstIndent
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            var e = end
            while (e > start && text[e - 1].isWhitespace()) e--
            best = max(best, width(start, e) + indentAt(start))
            start = end
            end = it.next()
        }
        best
    }

    override val hasStaleResolvedFonts get() = builtRevision != FontManager.revision
}

private object Intrinsics {
    private const val MAX_CACHED = 256

    private class Key(
        val text: String, val style: TextStyle, val annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        val placeholders: List<AnnotatedString.Range<Placeholder>>, val density: Density, val resolver: FontFamily.Resolver, val fonts: Int,
    ) {
        private val hash = ((text.hashCode() * 31 + style.hashCode()) * 31 + annotations.hashCode()) * 31 + placeholders.hashCode()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Key && hash == other.hash && fonts == other.fonts && placeholders == other.placeholders &&
            resolver === other.resolver && text == other.text && style == other.style && density == other.density && annotations == other.annotations
    }

    private val cache = object : LinkedHashMap<Key, PolyIntrinsics>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, PolyIntrinsics>?) = size > MAX_CACHED
    }

    fun of(
        text: String, style: TextStyle, annotations: List<AnnotatedString.Range<out AnnotatedString.Annotation>>,
        placeholders: List<AnnotatedString.Range<Placeholder>>, density: Density, resolver: FontFamily.Resolver,
    ): PolyIntrinsics {
        val fixed = Density(density.density, density.fontScale)
        val key = Key(text, style, annotations, placeholders, fixed, resolver, FontManager.revision)
        synchronized(cache) { cache[key] }?.let { return it }
        val made = PolyIntrinsics(text, style, annotations, placeholders, fixed, resolver)
        synchronized(cache) { cache[key] = made }
        return made
    }
}

private object SharedShadowBakes {
    private const val MAX_BYTES = 8L shl 20
    private const val MAX_ENTRIES = 32

    class Key(
        val intr: PolyIntrinsics, val maxLines: Int, val overflow: TextOverflow, val maxWidth: Float, val color: Color, val blur: Float, val runs: BitSet,
        val scale: Float, val revision: Int,
    ) {
        private val hash = ((((((System.identityHashCode(intr) * 31 + maxLines) * 31 + overflow.hashCode()) * 31 + maxWidth.toRawBits()) * 31 +
            color.hashCode()) * 31 + blur.toRawBits()) * 31 + runs.hashCode()) * 31 + scale.toRawBits()

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = other is Key && hash == other.hash && intr === other.intr && maxLines == other.maxLines &&
            overflow == other.overflow && maxWidth.toRawBits() == other.maxWidth.toRawBits() && scale.toRawBits() == other.scale.toRawBits() &&
            revision == other.revision && color == other.color && blur.toRawBits() == other.blur.toRawBits() && runs == other.runs
    }

    private val bakes = LinkedHashMap<Key, BlurPlacement>(16, 0.75f, true)
    private var bytes = 0L

    private fun sizeOf(p: BlurPlacement): Long = p.bitmap.width.toLong() * p.bitmap.height * 4L

    fun get(key: Key): BlurPlacement? = synchronized(bakes) { bakes[key] }

    fun put(key: Key, placed: BlurPlacement) {
        val size = sizeOf(placed)
        if (size > MAX_BYTES / 2) return
        synchronized(bakes) {
            bakes.put(key, placed)?.let { bytes -= sizeOf(it) }
            bytes += size
            val it = bakes.values.iterator()
            while ((bytes > MAX_BYTES || bakes.size > MAX_ENTRIES) && it.hasNext()) {
                bytes -= sizeOf(it.next())
                it.remove()
            }
        }
    }
}

private object Advances {
    private val cache = ConcurrentHashMap<PolyFont, ConcurrentHashMap<Int, Float>>()
    @Volatile private var fonts = -1
    private const val MAX_FONTS = 128

    private class Last(val font: PolyFont, val revision: Int, val widths: ConcurrentHashMap<Int, Float>)

    private var last: Last? = null

    private fun widths(font: PolyFont, revision: Int): ConcurrentHashMap<Int, Float> {
        val l = last
        if (l != null && l.font === font && l.revision == revision && fonts == revision) return l.widths
        if (cache.size > MAX_FONTS) cache.clear()
        val widths = cache.getOrPut(font) { ConcurrentHashMap() }
        last = Last(font, revision, widths)
        return widths
    }

    fun of(font: PolyFont, cp: Int, emoji: Boolean): Float {
        val revision = FontManager.revision
        if (revision != fonts) {
            cache.clear()
            fonts = revision
        }
        return widths(font, revision).getOrPut(if (emoji) cp.inv() else cp) {
            val s = String(Character.toChars(cp))
            font.measureTextWidth(if (emoji) s + String(Character.toChars(PolyGlyphPage.EMOJI_VARIATION)) else s)
        }
    }
}

internal class Seg(val str: String, val x: Float, val w: Float, val run: TextRun)

private class TextPass(val r: PolyRenderer, private val stroke: Stroke?, private val forced: Int?) : PolyRenderer by r {
    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float) = r.rect(x, y, w, h, forced ?: color, radius)

    override fun text(text: String, x: Float, y: Float, color: Int, font: PolyFont, letterSpacing: Float, skewX: Float) {
        val t = r as? PolyTessellator
        if (stroke != null && t != null) t.textStroke(text, x, y, forced ?: color, font, letterSpacing, skewX, stroke.width)
        else r.text(text, x, y, forced ?: color, font, letterSpacing, skewX)
    }

    override fun mcText(text: String, x: Float, y: Float, color: Int, shadow: Boolean, scale: Float, literal: Boolean, snapX: Boolean) {
        val t = r as? PolyTessellator
        if (stroke != null && t != null) t.mcTextStroke(text, x, y, forced ?: color, scale, literal, snapX)
        else r.mcText(text, x, y, forced ?: color, shadow, scale, literal, snapX)
    }
}

private fun tessellatorOf(r: PolyRenderer): PolyTessellator? {
    var cur = r
    while (cur is TextPass) cur = cur.r
    return cur as? PolyTessellator
}

internal class Line(
    val start: Int, val end: Int, val visibleEnd: Int, val top: Float, val bottom: Float, val baseline: Float, val left: Float, val width: Float, val ellipsis: Boolean,
    val rtl: Boolean = false, val runs: IntArray? = null, val runX: FloatArray? = null, val segs: Array<Seg>? = null,
    val gapStart: Int = -1, val gapEnd: Int = -1, val gapW: Float = 0f, val extra: Float = 0f, val justifyFrom: Int = start,
    val ascent: Float = 0f, val descent: Float = 0f,
)

internal class PolyParagraph(private val intr: PolyIntrinsics, maxLines: Int, overflow: TextOverflow, private val maxWidth: Float) : PlatformParagraph {
    private val text = intr.text
    private val lineStyle = intr.style.lineHeightStyle ?: LineHeightStyle.Default
    private val lines: List<Line>
    private val levels: ByteArray?
    override val didExceedMaxLines: Boolean
    override val width: Float
    override val height: Float
    override val placeholderRects: List<Rect?>

    init {
        val breaks = layout()
        val limit = if (maxLines <= 0) Int.MAX_VALUE else maxLines
        didExceedMaxLines = breaks.size > limit
        val kept = breaks.take(limit)
        val boxW = if (maxWidth.isFinite()) maxWidth else kept.maxOfOrNull { intr.inkWidth(it.first, it.third) + intr.indentAt(it.first) } ?: 0f
        val flags = when (intr.style.textDirection) {
            TextDirection.Ltr -> Bidi.DIRECTION_LEFT_TO_RIGHT
            TextDirection.Rtl -> Bidi.DIRECTION_RIGHT_TO_LEFT
            TextDirection.ContentOrRtl -> Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT
            else -> Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT
        }
        val forcedRtl = flags == Bidi.DIRECTION_RIGHT_TO_LEFT || flags == Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT
        val lv = if (!forcedRtl && !needsBidi(text)) null else ByteArray(text.length)
        var para: Bidi? = null
        var paraStart = 0
        var paraEnd = 0
        val sideEllipsis = overflow == TextOverflow.StartEllipsis || overflow == TextOverflow.MiddleEllipsis
        var nextTop = 0f
        lines = kept.mapIndexed { i, (s, kEnd, ve) ->
            val last = didExceedMaxLines && i == kept.lastIndex
            val gap = if (last && sideEllipsis && limit == 1 && lv == null) sideGap(s, overflow == TextOverflow.StartEllipsis) else null
            val e = gap?.get(2) ?: kEnd
            val ellipsize = last && (overflow == TextOverflow.Ellipsis || sideEllipsis)
            val vEnd = when {
                gap != null -> gap[3]
                ellipsize -> ellipsisEnd(s, ve)
                else -> ve
            }
            val ew = if (gap != null) ellipsisWidth(gapRunOffset(s, gap[0], gap[1])) else if (ellipsize) ellipsisWidth(vEnd) else 0f
            val w = if (gap != null) intr.width(s, gap[0]) + ew + intr.inkWidth(gap[1], vEnd) else intr.inkWidth(s, vEnd) + ew
            val top = nextTop
            val metrics = lineExtents(s, kEnd, i == 0, i == kept.lastIndex)
            val extents = placeholderExtents(s, vEnd, gap, metrics)
            var above = extents?.get(0) ?: metrics[0]
            var lineH = if (extents == null) metrics[0] + metrics[1] else extents[0] + extents[1]
            shiftExtents(s, vEnd, above, lineH - above)?.let { above = it[0]; lineH = it[0] + it[1] }
            nextTop = top + lineH
            if (lv != null && (para == null || s > paraEnd)) {
                paraStart = text.lastIndexOf('\n', s - 1) + 1
                paraEnd = text.indexOf('\n', s).let { if (it < 0) text.length else it }
                para = Bidi(text.substring(paraStart, paraEnd), flags).also { lv.fill(it.baseLevel.toByte(), paraStart, min(paraEnd + 1, text.length)) }
            }
            val b = para
            val rtl = b != null && !b.baseIsLeftToRight()
            val indent = intr.indentAt(s)
            val room = boxW - indent
            val start = if (rtl) 0f else indent
            val justify = intr.align == TextAlign.Justify && lv == null && gap == null && !ellipsize && kEnd < text.length && text[kEnd - 1] != '\n'
            val from = if (justify) firstInk(s, vEnd) else s
            val spaces = if (justify) justifiable(from, vEnd) else 0
            val extra = if (spaces > 0 && room > w) (room - w) / spaces else 0f
            val left = when (intr.align) {
                TextAlign.Center -> start + (room - w) / 2f
                TextAlign.Right -> start + room - w
                TextAlign.End -> if (rtl) start else start + room - w
                TextAlign.Left -> start
                else -> if (rtl) start + room - w else start
            }
            val baseline = top + above
            val line = if (gap != null) {
                Line(s, e, vEnd, top, top + lineH, baseline, max(start, left), w, true, rtl, gapStart = gap[0], gapEnd = gap[1], gapW = ew, ascent = metrics[2], descent = metrics[3])
            } else {
                Line(
                    s, e, vEnd, top, top + lineH, baseline, max(start, left), if (extra > 0f) room else w, ellipsize, rtl, extra = extra, justifyFrom = from,
                    ascent = metrics[2], descent = metrics[3],
                )
            }
            if (b == null || lv == null) line else bidiLine(line, b, paraStart, if (ellipsize) vEnd else min(e, paraEnd), ew, lv)
        }
        levels = lv
        width = if (maxWidth.isFinite()) maxWidth else lines.maxOfOrNull { it.width + intr.indentAt(it.start) } ?: 0f
        height = lines.lastOrNull()?.bottom ?: intr.lineHeightPx
        placeholderRects = placeholderRects()
    }

    private fun needsBidi(text: String): Boolean = text.any { it >= '֐' } && Bidi.requiresBidi(text.toCharArray(), 0, text.length)

    private inline fun forLineRuns(s: Int, e: Int, block: (TextRun) -> Unit) {
        val runs = intr.runs
        var k = intr.runIndexAt(s)
        var any = false
        while (k < runs.size && runs[k].start < e) {
            val run = runs[k++]
            if (run.placeholder == null && run.end > s) {
                any = true
                block(run)
            }
        }
        if (!any) block(intr.runAt(s))
    }

    private fun lineExtents(s: Int, e: Int, first: Boolean, last: Boolean): FloatArray {
        var above = 0f
        var below = 0f
        var fontAscent = 0f
        var fontDescent = 0f
        var fitAbove = Float.NEGATIVE_INFINITY
        var fitBelow = Float.NEGATIVE_INFINITY
        val spec = intr.lineHeightSpec
        val ratio = lineStyle.alignment.topRatio
        forLineRuns(s, e) { run ->
            val m = run.metrics
            val a = -m.ascent + m.leading / 2f
            val d = m.descent + m.leading / 2f
            above = max(above, a)
            below = max(below, d)
            fontAscent = max(fontAscent, -m.ascent)
            fontDescent = max(fontDescent, m.descent)
            val h = a + d
            if (!spec.isNaN() && h > 0f) {
                fitAbove = max(fitAbove, if (ratio in 0f..1f) a + (spec - h) * ratio else a * spec / h)
                fitBelow = max(fitBelow, if (ratio in 0f..1f) d + (spec - h) * (1f - ratio) else d * spec / h)
            }
        }
        if (spec.isNaN() || fitAbove == Float.NEGATIVE_INFINITY) {
            val natural = above + below
            val half = (round(natural) - natural) / 2f
            return floatArrayOf(above + half, below + half, fontAscent, fontDescent)
        }
        val mode = lineStyle.mode
        val tall = spec > intr.baseSize
        val padded = mode != LineHeightStyle.Mode.Tight
        var top = fitAbove
        var bottom = fitBelow
        if (first && (if (tall) lineStyle.trim.trimsFirstLineTop else padded)) top = above
        if (last && (if (tall) lineStyle.trim.trimsLastLineBottom else padded)) bottom = below
        if ((mode == LineHeightStyle.Mode.Minimum || mode == LineHeightStyle.Mode.PerLine) && top + bottom < above + below) {
            top = above
            bottom = below
        }
        return floatArrayOf(top, bottom, fontAscent, fontDescent)
    }

    private fun sideGap(s: Int, start: Boolean): IntArray? {
        if (!maxWidth.isFinite()) return null
        val newline = text.indexOf('\n', s)
        val end = if (newline < 0) text.length else newline + 1
        var visible = if (newline < 0) text.length else newline
        while (visible > s && text[visible - 1].isWhitespace()) visible--
        if (intr.width(s, visible) <= avail(s)) return null
        var a = s
        if (!start) {
            val runFont = intr.runAt(s)
            val half = (avail(s) - runFont.font.measureTextWidth(ELLIPSIS) * runFont.scaleX) / 2f
            while (a < visible) {
                val next = nextBoundary(a)
                if (intr.width(s, next) > half) break
                a = next
            }
        }
        val available = avail(s) - ellipsisWidth(gapRunOffset(s, a, a)) - intr.width(s, a)
        var b = a
        while (b < visible && intr.width(b, visible) > available) b = nextBoundary(b)
        return intArrayOf(a, b, end, visible)
    }

    private fun nextBoundary(i: Int): Int {
        var n = i + Character.charCount(text.codePointAt(i))
        while (n < text.length && intr.advances[n] == 0f && text[n] != '\n' && !Character.isWhitespace(text[n])) n++
        return n
    }

    private fun gapRunOffset(s: Int, gapStart: Int, gapEnd: Int) = if (gapStart <= s) gapEnd + 1 else gapStart

    private fun placeholderExtents(s: Int, vEnd: Int, gap: IntArray?, metrics: FloatArray): FloatArray? {
        if (intr.placeholders.isEmpty()) return null
        var above = metrics[0]
        var below = metrics[1]
        var boxed = 0f
        var boxAlign: PlaceholderVerticalAlign? = null
        val ascent = metrics[2]
        val descent = metrics[3]
        var any = false
        for (p in intr.placeholders) {
            if (!placeholderVisible(p.start, s, vEnd, gap)) continue
            any = true
            val h = intr.placeholderSize(p.item.height, intr.runAt(p.start))
            when (p.item.placeholderVerticalAlign) {
                PlaceholderVerticalAlign.AboveBaseline -> above = max(above, h)
                PlaceholderVerticalAlign.TextTop -> below = max(below, h - ascent)
                PlaceholderVerticalAlign.TextBottom -> above = max(above, h - descent)
                PlaceholderVerticalAlign.TextCenter -> {
                    val center = (descent - ascent) / 2f
                    above = max(above, h / 2f - center)
                    below = max(below, h / 2f + center)
                }
                else -> if (h > boxed) {
                    boxed = h
                    boxAlign = p.item.placeholderVerticalAlign
                }
            }
        }
        if (!any) return null
        val short = boxed - (above + below)
        if (short > 0f) when (boxAlign) {
            PlaceholderVerticalAlign.Top -> below += short
            PlaceholderVerticalAlign.Bottom -> above += short
            else -> { above += short / 2f; below += short / 2f }
        }
        return floatArrayOf(above, below)
    }

    private fun shiftExtents(s: Int, vEnd: Int, above: Float, below: Float): FloatArray? {
        if (!intr.shifted) return null
        var a = above
        var b = below
        var any = false
        val runs = intr.runs
        var k = intr.runIndexAt(s)
        while (k < runs.size && runs[k].start < vEnd) {
            val run = runs[k++]
            if (run.shift == 0f || run.placeholder != null || run.end <= s) continue
            any = true
            a = max(a, -run.metrics.ascent - run.shift)
            b = max(b, run.metrics.descent + run.shift)
        }
        return if (any) floatArrayOf(a, b) else null
    }

    private fun firstInk(s: Int, vEnd: Int): Int {
        var i = s
        while (i < vEnd && Character.isWhitespace(text[i])) i++
        return i
    }

    private fun justifiable(from: Int, to: Int): Int = intr.spaces(from, to)

    private fun stretched(l: Line, i: Int): Boolean = l.extra != 0f && i >= l.justifyFrom && i < l.visibleEnd && Character.isWhitespace(text[i])

    private fun span(l: Line, a: Int, b: Int): Float =
        intr.width(a, b) + if (l.extra == 0f || b <= a) 0f else l.extra * justifiable(max(a, l.justifyFrom), min(b, l.visibleEnd))

    private fun placeholderVisible(at: Int, s: Int, vEnd: Int, gap: IntArray?): Boolean =
        at in s until vEnd && (gap == null || at < gap[0] || at >= gap[1])

    private fun placeholderRects(): List<Rect?> {
        val placeholders = intr.placeholders
        if (placeholders.isEmpty()) return emptyList()
        return placeholders.map { p ->
            val l = lines.firstOrNull { p.start >= it.start && p.start < it.end } ?: return@map null
            if (!placeholderVisible(p.start, l.start, l.visibleEnd, if (l.gapStart >= 0) intArrayOf(l.gapStart, l.gapEnd) else null)) return@map null
            val run = intr.runAt(p.start)
            val h = intr.placeholderSize(p.item.height, run)
            val box = getBoundingBox(p.start)
            val top = when (p.item.placeholderVerticalAlign) {
                PlaceholderVerticalAlign.AboveBaseline -> l.baseline - h
                PlaceholderVerticalAlign.Top -> l.top
                PlaceholderVerticalAlign.Bottom -> l.bottom - h
                PlaceholderVerticalAlign.Center -> (l.top + l.bottom - h) / 2f
                PlaceholderVerticalAlign.TextTop -> l.baseline - l.ascent
                PlaceholderVerticalAlign.TextBottom -> l.baseline + l.descent - h
                else -> l.baseline + (l.descent - l.ascent) / 2f - h / 2f
            }
            Rect(box.left, top, box.right, top + h)
        }
    }

    private fun lineX(l: Line, offset: Int): Float = when {
        l.gapStart < 0 || offset <= l.gapStart -> l.left + span(l, l.start, offset)
        offset < l.gapEnd -> l.left + intr.width(l.start, l.gapStart)
        else -> l.left + intr.width(l.start, l.gapStart) + l.gapW + intr.width(l.gapEnd, offset)
    }

    private fun bidiLine(l: Line, para: Bidi, paraStart: Int, limit: Int, ellipsisW: Float, levels: ByteArray): Line {
        val s = l.start
        val line = if (limit > s) para.createLineBidi(s - paraStart, limit - paraStart) else null
        if (!l.rtl && (line == null || line.isLeftToRight)) return l
        if (line == null) {
            return Line(s, l.end, l.visibleEnd, l.top, l.bottom, l.baseline, l.left, l.width, l.ellipsis, true, IntArray(0), FloatArray(0), emptyArray(), ascent = l.ascent, descent = l.descent)
        }
        val n = line.runCount
        val order = Array<Any>(n) { it }
        Bidi.reorderVisually(ByteArray(n) { line.getRunLevel(it).toByte() }, 0, order, 0, n)
        val runs = IntArray(n * 3)
        val runX = FloatArray(n)
        val segs = ArrayList<Seg>()
        var x = if (l.rtl) l.left + ellipsisW - intr.width(l.visibleEnd, limit) else l.left
        for (v in 0 until n) {
            val k = order[v] as Int
            val rs = s + line.getRunStart(k)
            val re = s + line.getRunLimit(k)
            val level = line.getRunLevel(k)
            levels.fill(level.toByte(), rs, re)
            runs[v * 3] = rs
            runs[v * 3 + 1] = re
            runs[v * 3 + 2] = level
            runX[v] = x
            for (run in intr.runs) {
                val a = max(run.start, rs)
                val e = min(min(run.end, re), l.visibleEnd)
                if (a >= e) continue
                val glyphs = intr.glyphs.substring(a, e)
                segs += if (level and 1 == 0) Seg(glyphs, x + intr.width(rs, a), intr.width(a, e), run)
                else Seg(reversedClusters(glyphs), x + intr.width(e, re), intr.width(a, e), run)
            }
            x += intr.width(rs, re)
        }
        return Line(s, l.end, l.visibleEnd, l.top, l.bottom, l.baseline, l.left, l.width, l.ellipsis, l.rtl, runs, runX, segs.toTypedArray(), ascent = l.ascent, descent = l.descent)
    }

    private fun layout(): List<Triple<Int, Int, Int>> {
        val out = ArrayList<Triple<Int, Int, Int>>()
        val it = BreakIterator.getLineInstance(intr.locale).also { b -> b.setText(text) }
        var lineStart = 0
        var lineW = 0f
        var pos = it.first()
        var next = it.next()
        fun trimmedEnd(s: Int, e: Int): Int { var v = e; while (v > s && text[v - 1].isWhitespace()) v--; return v }
        while (next != BreakIterator.DONE) {
            val segW = intr.width(pos, trimmedEnd(pos, next))
            val segFull = intr.width(pos, next)
            if (lineW + segW > avail(lineStart) + 0.01f && pos > lineStart) {
                out += Triple(lineStart, pos, trimmedEnd(lineStart, pos))
                lineStart = pos
                lineW = 0f
            }
            if (segW > avail(lineStart) && pos == lineStart) {
                var e = pos
                var w = 0f
                while (e < next) {
                    val step = min(next, e + Character.charCount(text.codePointAt(e)))
                    var advance = 0f
                    for (i in e until step) advance += intr.advances[i]
                    if (w + advance > avail(lineStart) && e > lineStart) {
                        out += Triple(lineStart, e, e)
                        lineStart = e
                        w = 0f
                    }
                    w += advance
                    e = step
                }
                lineW = w
            } else lineW += segFull
            if (next > 0 && text[next - 1] == '\n') {
                out += Triple(lineStart, next, if (next - 1 > lineStart && text[next - 2] == '\r') next - 2 else next - 1)
                lineStart = next
                lineW = 0f
            }
            pos = next
            next = it.next()
        }
        if (lineStart < text.length || out.isEmpty() || text.endsWith('\n')) out += Triple(lineStart, text.length, trimmedEnd(lineStart, text.length))
        return out
    }

    private fun avail(lineStart: Int): Float = maxWidth - intr.indentAt(lineStart)

    private fun ellipsisWidth(at: Int) = intr.runAt(max(0, at - 1)).let { it.font.measureTextWidth(ELLIPSIS) * it.scaleX }

    private fun ellipsisEnd(start: Int, end: Int): Int {
        if (!maxWidth.isFinite()) return end
        var e = end
        var run: TextRun? = null
        var ellipsis = 0f
        while (e > start) {
            val r = intr.runAt(max(0, e - 1))
            if (r !== run) {
                run = r
                ellipsis = r.font.measureTextWidth(ELLIPSIS) * r.scaleX
            }
            if (intr.inkWidth(start, e) + ellipsis <= avail(start)) break
            e--
        }
        return e
    }

    private fun editEnd(l: Line) = when {
        l.ellipsis -> l.visibleEnd
        l.end > l.start && text[l.end - 1] == '\n' -> l.end - 1
        else -> l.end
    }

    private fun lineFor(offset: Int): Int {
        var lo = 0
        var hi = lines.lastIndex
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (offset < lines[mid].end) hi = mid else lo = mid + 1
        }
        return lo
    }

    override val minIntrinsicWidth get() = intr.minIntrinsicWidth
    override val maxIntrinsicWidth get() = intr.maxIntrinsicWidth
    override val firstBaseline get() = lines.firstOrNull()?.baseline ?: 0f
    override val lastBaseline get() = lines.lastOrNull()?.baseline ?: 0f
    override val lineCount get() = lines.size

    override fun getLineAscent(lineIndex: Int) = lines[lineIndex].top - lines[lineIndex].baseline
    override fun getLineDescent(lineIndex: Int) = lines[lineIndex].bottom - lines[lineIndex].baseline
    override fun getLineLeft(lineIndex: Int) = lines[lineIndex].left
    override fun getLineRight(lineIndex: Int) = lines[lineIndex].left + lines[lineIndex].width
    override fun getLineTop(lineIndex: Int) = lines[lineIndex].top
    override fun getLineBaseline(lineIndex: Int) = lines[lineIndex].baseline
    override fun getLineBottom(lineIndex: Int) = lines[lineIndex].bottom
    override fun getLineHeight(lineIndex: Int) = lines[lineIndex].bottom - lines[lineIndex].top
    override fun getLineWidth(lineIndex: Int) = lines[lineIndex].width
    override fun getLineStart(lineIndex: Int) = lines[lineIndex].start
    override fun getLineEnd(lineIndex: Int, visibleEnd: Boolean) = if (visibleEnd) lines[lineIndex].visibleEnd else lines[lineIndex].end
    override fun isLineEllipsized(lineIndex: Int) = lines[lineIndex].ellipsis
    override fun getLineForOffset(offset: Int) = lineFor(offset)

    override fun getHorizontalPosition(offset: Int, usePrimaryDirection: Boolean): Float {
        if (lines.isEmpty()) return 0f
        val l = lines[lineFor(offset)]
        if (l.runs != null) return bidiX(l, offset.coerceIn(l.start, editEnd(l)), usePrimaryDirection)
        return lineX(l, offset.coerceIn(l.start, editEnd(l)))
    }

    private fun runIndex(runs: IntArray, i: Int): Int {
        var k = 0
        while (k < runs.size) {
            if (i >= runs[k] && i < runs[k + 1]) return k
            k += 3
        }
        return -1
    }

    private fun edge(l: Line, k: Int, offset: Int): Float {
        val runs = l.runs!!
        val x = l.runX!![k / 3]
        return if (runs[k + 2] and 1 == 0) x + intr.width(runs[k], offset) else x + intr.width(offset, runs[k + 1])
    }

    private fun bidiX(l: Line, offset: Int, primary: Boolean): Float {
        val runs = l.runs!!
        val runX = l.runX!!
        if (runs.isEmpty()) return if (l.rtl) l.left + l.width else l.left
        val visLeft = runX[0]
        val visRight = runX[runX.size - 1] + intr.width(runs[runs.size - 3], runs[runs.size - 2])
        val paraLevel = if (l.rtl) 1 else 0
        val before = if (offset > l.start) runIndex(runs, offset - 1) else -1
        val at = runIndex(runs, offset)
        val levelBefore = if (before < 0) paraLevel else runs[before + 2]
        val levelAt = if (at < 0) paraLevel else runs[at + 2]
        val trailing = (levelBefore < levelAt) == primary
        return when {
            trailing && before >= 0 -> edge(l, before, offset)
            !trailing && at >= 0 -> edge(l, at, offset)
            trailing -> if (l.rtl) visRight else visLeft
            else -> if (l.rtl) visLeft else visRight
        }
    }

    override fun getCursorRect(offset: Int): Rect {
        val x = getHorizontalPosition(offset, true)
        val l = lines.getOrNull(lineFor(offset)) ?: return Rect(x, 0f, x, height)
        return Rect(x, l.top, x, l.bottom)
    }

    override fun getParagraphDirection(offset: Int) =
        if (lines.isNotEmpty() && lines[lineFor(offset)].rtl) ResolvedTextDirection.Rtl else ResolvedTextDirection.Ltr

    override fun getBidiRunDirection(offset: Int): ResolvedTextDirection {
        val lv = levels ?: return ResolvedTextDirection.Ltr
        if (lv.isEmpty()) return getParagraphDirection(offset)
        return if (lv[offset.coerceIn(0, lv.size - 1)].toInt() and 1 == 0) ResolvedTextDirection.Ltr else ResolvedTextDirection.Rtl
    }

    override fun getLineForVerticalPosition(vertical: Float): Int {
        var lo = 0
        var hi = lines.lastIndex
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (vertical < lines[mid].bottom) hi = mid else lo = mid + 1
        }
        return lo
    }

    override fun getOffsetForPosition(position: Offset): Int {
        if (lines.isEmpty()) return 0
        val l = lines[getLineForVerticalPosition(position.y)]
        var x = l.left
        val end = editEnd(l)
        if (l.runs != null) {
            val hi = if (l === lines.last() || end < l.end) end else max(l.start, end - 1)
            var best = l.start
            var bestD = Float.POSITIVE_INFINITY
            for (o in l.start..hi) {
                if (o > l.start && o < text.length && Character.isLowSurrogate(text[o]) && Character.isHighSurrogate(text[o - 1])) continue
                val d = abs(bidiX(l, o, true) - position.x)
                if (d < bestD) { bestD = d; best = o }
            }
            return best
        }
        var i = l.start
        while (i < end) {
            if (i == l.gapStart) {
                if (position.x < x + l.gapW / 2f) return i
                x += l.gapW
                i = max(l.gapEnd, i + 1)
                continue
            }
            val a = intr.advances[i] + if (stretched(l, i)) l.extra else 0f
            if (a != 0f) {
                if (position.x < x + a / 2f) return i
                x += a
            }
            i++
        }
        return if (l === lines.last() || end < l.end) end else max(l.start, end - 1)
    }

    private val charBreaks by lazy { BreakIterator.getCharacterInstance(intr.locale).also { it.setText(text) } }
    private val wordBreaks by lazy { BreakIterator.getWordInstance(intr.locale).also { it.setText(text) } }

    private fun segmentBounds(l: Line, a: Int, b: Int): Rect {
        var left = Float.POSITIVE_INFINITY
        var right = Float.NEGATIVE_INFINITY
        for (i in a until b) {
            val box = getBoundingBox(i)
            if (box.right <= box.left) continue
            left = min(left, box.left)
            right = max(right, box.right)
        }
        return if (left > right) Rect(l.left, l.top, l.left, l.bottom) else Rect(left, l.top, right, l.bottom)
    }

    private fun isWord(a: Int, b: Int): Boolean {
        for (i in a until b) if (Character.isLetterOrDigit(text[i])) return true
        return false
    }

    private fun includedSegments(l: Line, rect: Rect, granularity: TextGranularity, inclusion: TextInclusionStrategy): Long {
        val breaks = if (granularity == TextGranularity.Word) wordBreaks else charBreaks
        val end = editEnd(l)
        var first = -1
        var last = -1
        synchronized(breaks) {
            var a = l.start
            while (a < end) {
                val next = breaks.following(a)
                val b = if (next == BreakIterator.DONE) end else min(next, end)
                if ((granularity != TextGranularity.Word || isWord(a, b)) && inclusion.isIncluded(segmentBounds(l, a, b), rect)) {
                    if (first < 0) first = a
                    last = b
                }
                a = b
            }
        }
        return if (first < 0) -1L else (first.toLong() shl 32) or last.toLong()
    }

    override fun getRangeForRect(rect: Rect, granularity: TextGranularity, inclusionStrategy: TextInclusionStrategy): TextRange {
        if (lines.isEmpty()) return TextRange.Zero
        val from = getLineForVerticalPosition(rect.top)
        val to = getLineForVerticalPosition(rect.bottom)
        var start = -1
        var end = -1
        for (i in from..to) {
            val found = includedSegments(lines[i], rect, granularity, inclusionStrategy)
            if (found < 0L) continue
            if (start < 0) start = (found ushr 32).toInt()
            end = found.toInt()
        }
        return if (start < 0 || end <= start) TextRange.Zero else TextRange(start, end)
    }

    override fun getBoundingBox(offset: Int): Rect {
        val l = lines.getOrNull(lineFor(offset)) ?: return Rect.Zero
        val runs = l.runs
        val k = if (runs != null) runIndex(runs, offset) else -1
        if (k >= 0) {
            val a = edge(l, k, offset)
            val b = edge(l, k, offset + 1)
            return Rect(min(a, b), l.top, max(a, b), l.bottom)
        }
        val x = getHorizontalPosition(offset, true)
        val hidden = l.gapStart >= 0 && offset >= l.gapStart && offset < l.gapEnd
        val advance = (intr.advances.getOrNull(offset) ?: 0f) + if (stretched(l, offset)) l.extra else 0f
        return Rect(x, l.top, x + (if (hidden) 0f else advance), l.bottom)
    }

    override fun fillBoundingBoxes(range: TextRange, array: FloatArray, arrayStart: Int) {
        var k = arrayStart
        for (i in range.min until range.max) {
            val b = getBoundingBox(i)
            array[k++] = b.left; array[k++] = b.top; array[k++] = b.right; array[k++] = b.bottom
        }
    }

    override fun getWordBoundary(offset: Int): TextRange {
        val it = wordBreaks
        val o = offset.coerceIn(0, text.length)
        synchronized(it) {
            val end = if (it.isBoundary(o) && o < text.length) it.following(o) else it.following(o.coerceAtMost(max(0, text.length - 1)))
            val start = it.preceding(if (end == BreakIterator.DONE) text.length else end)
            return TextRange(if (start == BreakIterator.DONE) 0 else start, if (end == BreakIterator.DONE) text.length else end)
        }
    }

    override fun getPathForRange(start: Int, end: Int): Path {
        val p = PolyComposePath()
        for (l in lines) {
            val runs = l.runs
            if (runs != null) {
                var k = 0
                while (k < runs.size) {
                    val a = max(start, runs[k])
                    val b = min(end, runs[k + 1])
                    if (a < b) {
                        val x0 = edge(l, k, a)
                        val x1 = edge(l, k, b)
                        p.addRect(Rect(min(x0, x1), l.top, max(x0, x1), l.bottom), Path.Direction.CounterClockwise)
                    }
                    k += 3
                }
                continue
            }
            val s = max(start, l.start)
            val e = min(end, l.visibleEnd)
            if (s >= e && !(start < l.end && end > l.visibleEnd && s <= l.visibleEnd)) continue
            // measured from this line's start because a wrapped line's end offset resolves to the next line
            val x0 = lineX(l, s)
            val x1 = if (end > l.visibleEnd && l.end > l.visibleEnd) lineX(l, l.visibleEnd) + intr.advances.getOrElse(l.visibleEnd) { 0f } else lineX(l, e)
            p.addRect(Rect(x0, l.top, max(x1, x0), l.bottom), Path.Direction.CounterClockwise)
        }
        return p
    }

    override fun paint(canvas: Canvas, color: Color, shadow: Shadow?, textDecoration: TextDecoration?) =
        draw(canvas, if (color.isSpecified) color else null, 1f, shadow, textDecoration)

    override fun paint(canvas: Canvas, color: Color, shadow: Shadow?, textDecoration: TextDecoration?, drawStyle: DrawStyle?, blendMode: BlendMode) {
        val c = if (color.isSpecified) color else null
        draw(canvas, c, 1f, shadow, textDecoration, style = textPaint(drawStyle as? Stroke, blendMode, c?.alpha ?: 1f))
    }

    override fun paint(canvas: Canvas, brush: Brush, alpha: Float, shadow: Shadow?, textDecoration: TextDecoration?, drawStyle: DrawStyle?, blendMode: BlendMode) {
        val a = if (alpha.isNaN()) 1f else alpha
        val stroke = drawStyle as? Stroke
        if (brush is ChromaBrush) return drawChroma(canvas, brush, a, shadow, textDecoration, TextPaint(stroke, blendMode, a))
        if (brush is SolidColor || brush !is ShaderBrush) {
            val c = (brush as? SolidColor)?.value
            return draw(canvas, c, a, shadow, textDecoration, style = textPaint(stroke, blendMode, (c?.alpha ?: 1f) * a))
        }
        val shader = shaderOf(brush)
        val fade = (shader as? GradientShader)?.let { fadeOf(it, a) }
        if (fade != null) return draw(canvas, Color.White, 1f, shadow, textDecoration, fade, style = textPaint(stroke, blendMode, a))
        val fill = brushFill(brush, shader)
        if (fill == null) {
            val c = (shader as? GradientShader)?.let { Color(it.brush.sample(0.5f)) }
            return draw(canvas, c, a, shadow, textDecoration, style = textPaint(stroke, blendMode, (c?.alpha ?: 1f) * a))
        }
        draw(canvas, Color.White, a, shadow, textDecoration, fill = fill, style = textPaint(stroke, blendMode, a))
    }

    private class TextPaint(val stroke: Stroke?, val blend: BlendMode, val alpha: Float)

    private fun textPaint(stroke: Stroke?, blend: BlendMode, alpha: Float): TextPaint? =
        if (stroke == null && blend == BlendMode.SrcOver) null else TextPaint(stroke, blend, alpha)

    private var shaderBrush: ShaderBrush? = null
    private var shaderSize = Size.Zero
    private var shaderCached: PlatformShader? = null
    private var fadeShader: GradientShader? = null
    private var fadeAlpha = 0f
    private var fadeCached: PolyFade? = null

    private fun shaderOf(brush: ShaderBrush): PlatformShader {
        val size = Size(width, height)
        if (brush !is LinearGradient && brush !is RadialGradient && brush !is SweepGradient) return brush.createShader(size).platformShader
        val cached = shaderCached
        if (cached != null && brush === shaderBrush && size == shaderSize) return cached
        val made = brush.createShader(size).platformShader
        shaderBrush = brush
        shaderSize = size
        shaderCached = made
        return made
    }

    private fun fadeOf(shader: GradientShader, alpha: Float): PolyFade? {
        if (shader !== shaderCached) return shader.fade(alpha, true)
        if (shader === fadeShader && alpha.toRawBits() == fadeAlpha.toRawBits()) return fadeCached
        val made = shader.fade(alpha, true)
        fadeShader = shader
        fadeAlpha = alpha
        fadeCached = made
        return made
    }

    private var fillBrush: Brush? = null
    private var fillSize = Size.Zero
    private var fillCached: PolyImageFill? = null

    private fun brushFill(brush: Brush, shader: PlatformShader): PolyImageFill? {
        val size = Size(width, height)
        if (brush == fillBrush && size == fillSize) return fillCached
        val fill = ShaderRaster.fill(shader, 0f, 0f, width, height, 1f, 1f)
        fillBrush = brush
        fillSize = size
        fillCached = fill
        return fill
    }

    private fun draw(
        canvas: Canvas, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, fade: PolyFade? = null, fill: PolyImageFill? = null,
        style: TextPaint? = null,
    ) {
        FontManager.observedRevision.intValue
        (canvas as? PolyDrawCanvas)?.polyOp { pc ->
            val stroke = style?.stroke
            if (style == null || style.blend == BlendMode.SrcOver) return@polyOp drawStyled(pc.renderer, color, alpha, shadow, decoration, fade, fill, stroke)
            pc.blendedText(style.blend, style.alpha) { forced ->
                if (forced == null) drawStyled(pc.renderer, color, alpha, shadow, decoration, fade, fill, stroke)
                else paintLines(pc.renderer, color, alpha, shadow, decoration, PASS_ALL, stroke, forced)
            }
        }
    }

    private fun drawStyled(r: PolyRenderer, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, fade: PolyFade?, fill: PolyImageFill?, stroke: Stroke?) {
        if (fill != null) {
            val t = r as? PolyTessellator ?: return paintLines(r, Color(fill.colors[0].argb), alpha, shadow, decoration, PASS_ALL, stroke)
            paintLines(r, color, alpha, shadow, decoration, PASS_UNDER, stroke)
            t.textFill(fill)
            try {
                paintLines(r, color, alpha, shadow, decoration, PASS_OVER, stroke, outer = fill)
            } finally {
                t.textFill(null)
            }
            return
        }
        if (fade == null) return paintLines(r, color, alpha, shadow, decoration, PASS_ALL, stroke)
        paintLines(r, color, alpha, shadow, decoration, PASS_UNDER, stroke)
        r.saveLayerFade(fade)
        paintLines(r, color, alpha, shadow, decoration, PASS_OVER, stroke)
        r.restore()
    }

    private fun paintLines(
        r: PolyRenderer, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int, stroke: Stroke?, forced: Int? = null, outer: PolyImageFill? = null,
    ) {
        val plain = if (forced == null) r else TextPass(r, null, forced)
        if (stroke == null) return drawLines(plain, color, alpha, shadow, decoration, pass, outer)
        if (pass != PASS_OVER) drawLines(plain, color, alpha, shadow, decoration, PASS_UNDER, outer)
        if (pass != PASS_UNDER) drawLines(TextPass(r, stroke, forced), color, alpha, shadow, decoration, PASS_OVER, outer)
    }

    private fun drawChroma(canvas: Canvas, brush: ChromaBrush, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, style: TextPaint) {
        val pad = intr.lineHeightPx
        FontManager.observedRevision.intValue
        (canvas as? PolyDrawCanvas)?.polyOp { pc ->
            pc.blendedText(style.blend, style.alpha) { forced ->
                if (forced != null) paintLines(pc.renderer, Color(brush.argb()), alpha, shadow, decoration, PASS_ALL, style.stroke, forced)
                else pc.renderer.live(-pad, -pad, width + pad * 2f, height + pad * 2f, IntSupplier { brush.argb() }) { r ->
                    paintLines(r, Color(brush.argb()), alpha, shadow, decoration, PASS_ALL, style.stroke)
                }
            }
        }
    }

    private fun drawLines(
        r: PolyRenderer, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int, outer: PolyImageFill? = null, bake: Shadow? = null,
    ) {
        if (pass != PASS_OVER && bake == null) drawBlurredShadows(r, shadow)
        for (index in lines.indices) {
            val l = lines[index]
            var x = l.left
            val segs = l.segs ?: if (l.extra != 0f) justifiedSegs[index] else null
            if (segs != null) {
                for (sg in segs) {
                    drawRun(r, l, sg.run, sg.str, sg.x, sg.w, color, alpha, shadow, decoration, pass, outer, bake)
                    x = max(x, sg.x + sg.w)
                }
                if (l.rtl) x = l.left
            } else if (l.gapStart >= 0) {
                x = drawRange(r, l, index * 2, l.start, l.gapStart, x, color, alpha, shadow, decoration, pass, outer, bake)
                if (pass == PASS_ALL || pass == PASS_OVER) drawEllipsis(r, l, intr.runAt(max(0, gapRunOffset(l.start, l.gapStart, l.gapEnd) - 1)), x, color, alpha)
                x = drawRange(r, l, index * 2 + 1, l.gapEnd, l.visibleEnd, x + l.gapW, color, alpha, shadow, decoration, pass, outer, bake)
            } else x = drawRange(r, l, index * 2, l.start, l.visibleEnd, x, color, alpha, shadow, decoration, pass, outer, bake)
            if (l.ellipsis && l.gapStart < 0 && (pass == PASS_ALL || pass == PASS_OVER)) {
                drawEllipsis(r, l, intr.runAt(max(0, l.visibleEnd - 1)), x - intr.kernAfter(l.visibleEnd - 1), color, alpha)
            }
        }
    }

    private fun drawRange(
        r: PolyRenderer, l: Line, slot: Int, from: Int, to: Int, x0: Float, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int,
        outer: PolyImageFill?, bake: Shadow?,
    ): Float {
        var x = x0
        val runs = intr.runs
        for (k in runs.indices) {
            val run = runs[k]
            val s = max(run.start, from)
            val e = min(run.end, to)
            if (s >= e) continue
            val w = intr.width(s, e)
            drawRun(r, l, run, rangeText(slot, k, s, e), x, w, color, alpha, shadow, decoration, pass, outer, bake)
            x += w
        }
        return x
    }

    private var rangeTexts: Array<Array<String?>?>? = null

    private fun rangeText(slot: Int, k: Int, s: Int, e: Int): String {
        val all = rangeTexts ?: arrayOfNulls<Array<String?>>(lines.size * 2).also { rangeTexts = it }
        val row = all[slot] ?: arrayOfNulls<String>(intr.runs.size).also { all[slot] = it }
        return row[k] ?: intr.glyphs.substring(s, e).also { row[k] = it }
    }

    private val justifiedSegs: Array<Array<Seg>?> by lazy { Array(lines.size) { i -> lines[i].takeIf { it.extra != 0f }?.let(::justifiedSegs) } }

    private fun justifiedSegs(l: Line): Array<Seg> {
        val segs = ArrayList<Seg>()
        var x = l.left
        for (run in intr.runs) {
            val s = max(run.start, l.start)
            val e = min(run.end, l.visibleEnd)
            if (s >= e) continue
            var a = s
            while (a < e) {
                var b = a
                while (b < e && !stretched(l, b)) b++
                if (b < e) b++
                val w = span(l, a, b)
                segs += Seg(intr.glyphs.substring(a, b), x, w, run)
                x += w
                a = b
            }
        }
        return segs.toTypedArray()
    }

    private fun drawEllipsis(r: PolyRenderer, l: Line, run: TextRun, x: Float, color: Color?, alpha: Float) {
        val base = runColor(run, color)
        runText(r, run, ELLIPSIS, x, l.baseline + run.shift, base.copy(alpha = base.alpha * alpha).toArgb(), false)
    }

    private fun runColor(run: TextRun, color: Color?): Color {
        val brush = run.spanBrush
        if (brush != null) {
            val fallback = when (brush) {
                is ChromaBrush -> Color(brush.argb())
                is ShaderBrush -> spanFill(brush)?.let { Color(it.colors[0].argb) }
                else -> null
            }
            if (fallback != null) return fallback.copy(alpha = fallback.alpha * if (run.brushAlpha.isNaN()) 1f else run.brushAlpha.coerceIn(0f, 1f))
        }
        val span = run.spanColor
        if (span.isSpecified) return span
        if (color != null) return color
        val own = run.style.color
        return if (own.isSpecified) own else Color.Black
    }

    private val spanFills = HashMap<Brush, PolyImageFill>()
    private var spanFillSize = Size.Zero

    private fun spanFill(brush: ShaderBrush): PolyImageFill? {
        if (brush is ChromaBrush) return null
        val size = Size(width, height)
        synchronized(spanFills) {
            if (size != spanFillSize) {
                spanFills.clear()
                spanFillSize = size
            }
            spanFills[brush]?.let { return it }
        }
        val fill = ShaderRaster.fill(brush.createShader(size).platformShader, 0f, 0f, width, height, 1f, 1f) ?: return null
        synchronized(spanFills) { if (size == spanFillSize) spanFills[brush] = fill }
        return fill
    }

    private class ShadowBake(val color: Color, val blur: Float, val scale: Float, val revision: Int, val runs: BitSet, val placed: BlurPlacement)

    private val shadowBakes = ArrayList<ShadowBake>()
    private val bakeMaxLines = maxLines
    private val bakeOverflow = overflow

    private fun blurredShadow(run: TextRun, shadow: Shadow?): Shadow? {
        if (run.placeholder != null) return null
        val sh = run.spanShadow ?: shadow ?: run.style.shadow ?: return null
        if (McFonts.isMinecraft(run.font.name) && !McGlyphs.available()) return null
        return sh.takeIf { it.blurRadius > 0f && it.color.alpha > 0f && !McFonts.isShadow(it) }
    }

    private fun drawBlurredShadows(r: PolyRenderer, shadow: Shadow?) {
        var done: ArrayList<Shadow>? = null
        val runs = intr.runs
        for (k in runs.indices) {
            val sh = blurredShadow(runs[k], shadow) ?: continue
            if (done?.contains(sh) == true) continue
            done = (done ?: ArrayList()).also { it += sh }
            val p = shadowBake(r, sh, shadow) ?: continue
            val pw = p.bitmap.width.toFloat()
            val ph = p.bitmap.height.toFloat()
            r.image(
                p.bitmap, p.x + sh.offset.x, p.y + sh.offset.y, p.w, p.h, p.srcX / pw, p.srcY / ph, (p.srcX + p.srcW) / pw, (p.srcY + p.srcH) / ph, -1,
                FilterQuality.Low,
            )
        }
    }

    private fun selects(runs: BitSet, sh: Shadow, shadow: Shadow?): Boolean {
        val all = intr.runs
        for (i in all.indices) if ((blurredShadow(all[i], shadow) == sh) != runs[i]) return false
        return true
    }

    private fun selection(sh: Shadow, shadow: Shadow?): BitSet {
        val all = intr.runs
        val runs = BitSet(all.size)
        for (i in all.indices) if (blurredShadow(all[i], shadow) == sh) runs.set(i)
        return runs
    }

    private fun shadowBake(r: PolyRenderer, sh: Shadow, shadow: Shadow?): BlurPlacement? {
        val device = r.deviceScale().takeIf { it.isFinite() && it > 0f } ?: 1f
        val scale = (device * BAKE_STEPS).roundToInt().coerceIn(1, MAX_BAKE_SCALE * BAKE_STEPS) / BAKE_STEPS.toFloat()
        val revision = FontManager.revision
        synchronized(shadowBakes) {
            shadowBakes.firstOrNull {
                it.color == sh.color && it.blur == sh.blurRadius && it.scale == scale && it.revision == revision && selects(it.runs, sh, shadow)
            }?.let { return it.placed }
        }
        val runs = selection(sh, shadow)
        val key = SharedShadowBakes.Key(intr, bakeMaxLines, bakeOverflow, maxWidth, sh.color, sh.blurRadius, runs, scale, revision)
        SharedShadowBakes.get(key)?.let { shared ->
            remember(sh, scale, revision, runs, shared)
            return shared
        }
        val pad = max(intr.lineHeightPx, sh.blurRadius * 3f)
        val w = width + pad * 2f
        val h = height + pad * 2f
        if (!(w > 0f && h > 0f) || w.isInfinite() || h.isInfinite()) return null
        val k = min(scale, sqrt(MAX_BAKE_PIXELS / (w * h)))
        val bw = ceil(w * k).toInt().coerceAtLeast(1)
        val bh = ceil(h * k).toInt().coerceAtLeast(1)
        val image = PolyGraphics.createImageBitmap(bw, bh, ImageBitmapConfig.Argb8888, true, ColorSpaces.Srgb) as PolyImageBitmap
        val baker = (PolyGraphics.createCanvas(image) as PolyCanvas).renderer
        baker.scale(k, k)
        baker.translate(pad, pad)
        drawLines(baker, null, 1f, shadow, null, PASS_SHADOW, bake = sh)
        val placed = placeBlur(image.bitmap, sh.blurRadius, 0f, 0f, bw.toFloat(), bh.toFloat(), -pad, -pad, bw / k, bh / k)
            ?: BlurPlacement(image.bitmap, 0f, 0f, bw.toFloat(), bh.toFloat(), -pad, -pad, bw / k, bh / k)
        SharedShadowBakes.put(key, placed)
        remember(sh, scale, revision, runs, placed)
        return placed
    }

    private fun remember(sh: Shadow, scale: Float, revision: Int, runs: BitSet, placed: BlurPlacement) {
        synchronized(shadowBakes) {
            shadowBakes += ShadowBake(sh.color, sh.blurRadius, scale, revision, runs, placed)
            if (shadowBakes.size > MAX_SHADOW_BAKES) shadowBakes.removeAt(0)
        }
    }

    private fun runText(r: PolyRenderer, run: TextRun, str: String, x: Float, baseline: Float, color: Int, mcShadow: Boolean) {
        val sx = run.scaleX
        val scaled = sx != 1f
        if (scaled) {
            r.save()
            r.translate(x, 0f)
            r.scale(sx, 1f)
        }
        val at = if (scaled) 0f else x
        val tracking = run.tracking / sx
        if (McFonts.isMinecraft(run.font.name)) mcRun(r, run, str, at, baseline, color, tracking, mcShadow)
        else if (run.bold <= 0f) r.text(laidOut(str), at, baseline, color, run.font, tracking, run.skew / sx)
        else {
            val translucent = color ushr 24 < 0xFF
            if (translucent) r.saveLayerAlpha((color ushr 24) / 255f)
            val c = if (translucent) color or OPAQUE else color
            r.text(laidOut(str), at, baseline, c, run.font, tracking, run.skew / sx)
            emboldenPass(r, run, str, at + run.bold / sx, baseline, c, tracking, run.skew / sx)
            if (translucent) r.restore()
        }
        if (scaled) r.restore()
    }

    private fun emojiEnd(str: String, i: Int, face: PolyGlyphPage.Face?): Int {
        val cp = str.codePointAt(i)
        val after = i + Character.charCount(cp)
        if (face != null && !PolyGlyphPage.isIgnorable(cp)) {
            val sequence = PolyGlyphPage.sequence(face, str, i, str.length)
            if (sequence != 0L) return sequence.toInt()
        }
        val next = if (after < str.length) str.codePointAt(after) else 0
        if (next == PolyGlyphPage.EMOJI_VARIATION && Character.isEmoji(cp)) return after + Character.charCount(next)
        return if (Character.isEmojiPresentation(cp)) after else -1
    }

    private fun emboldenPass(r: PolyRenderer, run: TextRun, str: String, x: Float, baseline: Float, color: Int, tracking: Float, skew: Float) {
        val face = PolyGlyphPage.rasterizer?.emoji()
        var i = 0
        var hasEmoji = false
        while (i < str.length) {
            if (emojiEnd(str, i, face) >= 0) { hasEmoji = true; break }
            i += Character.charCount(str.codePointAt(i))
        }
        if (!hasEmoji) return r.text(laidOut(str), x, baseline, color, run.font, tracking, skew)
        var segStart = 0
        var segX = x
        var pen = x
        i = 0
        while (i < str.length) {
            val end = emojiEnd(str, i, face)
            if (end < 0) {
                val cp = str.codePointAt(i)
                if (!PolyGlyphPage.isIgnorable(cp)) pen += Advances.of(run.font, cp, false) + tracking
                i += Character.charCount(cp)
                continue
            }
            if (i > segStart) r.text(laidOut(str.substring(segStart, i)), segX, baseline, color, run.font, tracking, skew)
            pen += run.font.measureTextWidth(laidOut(str.substring(i, end))) + tracking
            i = end
            segStart = end
            segX = pen
        }
        if (segStart < str.length) r.text(laidOut(str.substring(segStart)), segX, baseline, color, run.font, tracking, skew)
    }

    private fun drawRun(
        r: PolyRenderer, l: Line, run: TextRun, str: String, x: Float, w: Float, color: Color?, alpha: Float, shadow: Shadow?, decoration: TextDecoration?, pass: Int,
        outer: PolyImageFill? = null, bake: Shadow? = null,
    ) {
        if (run.placeholder != null) return
        val baseline = l.baseline + run.shift
        if (pass == PASS_SHADOW) {
            if (bake != null && blurredShadow(run, shadow) == bake) runText(r, run, str, x, baseline, bake.color.toArgb(), false)
            return
        }
        val base = runColor(run, color)
        val c = if (alpha == 1f && base.colorSpace.isSrgb) base.toArgb() else base.copy(alpha = base.alpha * alpha).toArgb()
        val sh = run.spanShadow ?: shadow ?: run.style.shadow
        val mcShadow = McFonts.isShadow(sh)
        val mc = McFonts.isMinecraft(run.font.name)
        if (pass != PASS_OVER) {
            val background = run.style.background
            if (background.isSpecified && background.alpha > 0f) r.rect(x, l.top, w, l.bottom - l.top, background.toArgb(), 0f)
            val o = McFonts.scale(run.font)
            when {
                mcShadow && mc -> if (pass == PASS_UNDER) runText(r, run, str, x + o, baseline + o, McFonts.shadowArgb(c), false)
                mcShadow -> runText(r, run, str, x + o, baseline + o, McFonts.shadowArgb(c), false)
                sh != null && sh != Shadow.None && sh.color.alpha > 0f && blurredShadow(run, shadow) == null ->
                    runText(r, run, str, x + sh.offset.x, baseline + sh.offset.y, sh.color.toArgb(), false)
            }
        }
        if (pass == PASS_UNDER) return
        val brush = run.spanBrush
        val t = tessellatorOf(r)
        val fill = if (t != null && brush is ShaderBrush) spanFill(brush) else null
        val ink = if (fill == null) c else Color.White.copy(alpha = alpha * if (run.brushAlpha.isNaN()) 1f else run.brushAlpha.coerceIn(0f, 1f)).toArgb()
        if (fill != null) t?.textFill(fill)
        try {
            runText(r, run, str, x, baseline, ink, mc && mcShadow && pass == PASS_ALL)
            val deco = run.spanDecoration ?: decoration ?: run.style.textDecoration
            if (deco != null) {
                val m = run.metrics
                val th = max(1f, m.underlineThickness)
                if (TextDecoration.Underline in deco) r.rect(x, baseline + m.underlinePosition, w, th, ink, 0f)
                if (TextDecoration.LineThrough in deco) r.rect(x, baseline + m.strikeoutPosition, w, max(1f, m.strikeoutThickness), ink, 0f)
            }
        } finally {
            if (fill != null) t?.textFill(outer)
        }
    }

    private fun mcRun(r: PolyRenderer, run: TextRun, str: String, x: Float, baseline: Float, color: Int, letter: Float, shadow: Boolean) {
        val scale = McFonts.scale(run.font)
        val top = baseline - McFonts.ASCENT * scale
        val bold = run.font.name == McFonts.BOLD
        val prefix = if (run.skew != 0f) (if (bold) "§l§o" else "§o") else if (bold) "§l" else ""
        val formatted = prefix.isNotEmpty() && '§' !in str
        if (letter == 0f) {
            if (formatted) r.mcText(prefix + laidOut(str), x, top, color, shadow, scale, false, false)
            else r.mcText(laidOut(str), x, top, color, shadow, scale, true, false)
            return
        }
        var cx = x
        var i = 0
        while (i < str.length) {
            val n = Character.charCount(str.codePointAt(i))
            val ch = str.substring(i, i + n)
            if (formatted) r.mcText(prefix + laidOut(ch), cx, top, color, shadow, scale, false, false)
            else r.mcText(laidOut(ch), cx, top, color, shadow, scale, true, false)
            cx += run.font.measureTextWidth(laidOut(ch)) + letter
            i += n
        }
    }

    private companion object {
        const val PASS_ALL = 0
        const val PASS_UNDER = 1
        const val PASS_OVER = 2
        const val PASS_SHADOW = 3
        const val BAKE_STEPS = 4
        const val MAX_BAKE_SCALE = 8
        const val MAX_BAKE_PIXELS = 1048576f
        const val MAX_SHADOW_BAKES = 4
        const val ELLIPSIS = "…"
        const val OPAQUE = -0x1000000
    }
}
