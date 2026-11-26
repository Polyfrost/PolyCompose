package org.polyfrost.compose.render

import org.polyfrost.compose.backend.Icu
import org.polyfrost.compose.backend.LIGATURE_PLACEHOLDER
import java.text.Bidi
import java.text.BreakIterator

public fun visualOrder(text: String): String {
    if (text.all { it < '\u0590' }) return text
    return VisualCache.get(text, plain = true) { reorder(text) }
}

private fun reorder(text: String): String {
    val out = StringBuilder(text.length)
    eachLine(text, out) { start, end -> appendVisualLine(text.substring(start, end), out, null) }
    return out.toString()
}

public fun mcVisualOrder(text: String, literal: Boolean): String {
    if (literal || '§' !in text) return visualOrder(text)
    if (text.all { it < '\u0590' }) return text
    return VisualCache.get(text, plain = false) { reorderFormatted(text) }
}

private fun reorderFormatted(text: String): String {
    val out = StringBuilder(text.length + 8)
    val style = McStyle()
    eachLine(text, out) { start, end ->
        val plain = StringBuilder(end - start)
        val styles = ArrayList<String>(end - start)
        var i = start
        while (i < end) {
            val c = text[i++]
            if (c == '§' && i < end) {
                style.apply(text[i++])
                continue
            }
            plain.append(c)
            styles.add(style.codes())
        }
        val line = plain.toString()
        if (!needsReorder(line)) {
            out.append(text, start, end)
        } else {
            appendVisualLine(line, out, styles)
            out.append("§r").append(style.codes())
        }
    }
    return out.toString()
}

private object VisualCache {
    private const val MAX = 256
    private val plain = lru()
    private val formatted = lru()

    private fun lru() = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > MAX
    }

    inline fun get(text: String, plain: Boolean, compute: () -> String): String {
        val map = if (plain) this.plain else formatted
        synchronized(map) { map[text] }?.let { return it }
        val visual = compute()
        synchronized(map) { map[text] = visual }
        return visual
    }
}

internal fun laidOut(text: String): String =
    if (text.all { it < '\u0590' } || !Bidi.requiresBidi(text.toCharArray(), 0, text.length)) text else "\u202D$text\u202C"

private class McStyle {
    private var color = ' '
    private val formats = StringBuilder()

    fun apply(code: Char) {
        val c = code.lowercaseChar()
        when (c) {
            in '0'..'9', in 'a'..'f' -> { color = c; formats.setLength(0) }
            'r' -> { color = ' '; formats.setLength(0) }
            in 'k'..'o' -> if (c !in formats) formats.append(c)
        }
    }

    fun codes(): String {
        if (color == ' ' && formats.isEmpty()) return ""
        val sb = StringBuilder()
        if (color != ' ') sb.append('§').append(color)
        for (f in formats) sb.append('§').append(f)
        return sb.toString()
    }
}

private inline fun eachLine(text: String, out: StringBuilder, block: (start: Int, end: Int) -> Unit) {
    var start = 0
    for (i in text.indices) {
        val c = text[i]
        if (c != '\n' && c != '\r') continue
        block(start, i)
        out.append(c)
        start = i + 1
    }
    block(start, text.length)
}

private fun needsReorder(line: String): Boolean =
    line.any(::isBidiControl) || Bidi.requiresBidi(line.toCharArray(), 0, line.length)

private fun appendVisualLine(line: String, out: StringBuilder, styles: List<String>?) {
    if (styles == null && !needsReorder(line)) {
        out.append(line)
        return
    }
    val bidi = Bidi(line, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    val glyphs = Icu.shape(line).takeIf { it.length == line.length } ?: line
    val count = bidi.runCount
    val runs = Array<Any>(count) { it }
    Bidi.reorderVisually(ByteArray(count) { bidi.getRunLevel(it).toByte() }, 0, runs, 0, count)
    val emitter = Emitter(glyphs, out, styles)
    for (run in runs) {
        val r = run as Int
        val rs = bidi.getRunStart(r)
        val re = bidi.getRunLimit(r)
        if (bidi.getRunLevel(r) and 1 == 0) {
            for (i in rs until re) emitter.emit(i, false)
            continue
        }
        val clusters = BreakIterator.getCharacterInstance().also { it.setText(glyphs.substring(rs, re)) }
        var ce = clusters.last()
        var cs = clusters.previous()
        while (cs != BreakIterator.DONE) {
            for (i in rs + cs until rs + ce) emitter.emit(i, true)
            ce = cs
            cs = clusters.previous()
        }
    }
}

private class Emitter(private val glyphs: String, private val out: StringBuilder, private val styles: List<String>?) {
    private var style: String? = null

    fun emit(i: Int, mirror: Boolean) {
        val c = glyphs[i]
        if (c == LIGATURE_PLACEHOLDER || isBidiControl(c)) return
        if (styles != null && styles[i] != style) {
            style = styles[i]
            out.append("§r").append(style)
        }
        out.append(if (mirror && !c.isSurrogate()) Icu.mirror(c.code).toChar() else c)
    }
}

private fun isBidiControl(c: Char) = c == '\u061C' || c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'
