package org.polyfrost.compose.render

import org.polyfrost.compose.backend.Icu
import org.polyfrost.compose.backend.LIGATURE_PLACEHOLDER
import org.polyfrost.compose.backend.reversedClusters
import java.text.Bidi

public fun visualOrder(text: String): String {
    if (text.all { it < '֐' }) return text
    if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) return visualLine(text)
    val out = StringBuilder(text.length)
    var start = 0
    for (i in text.indices) {
        val c = text[i]
        if (c != '\n' && c != '\r') continue
        out.append(visualLine(text.substring(start, i))).append(c)
        start = i + 1
    }
    return out.append(visualLine(text.substring(start))).toString()
}

private fun visualLine(line: String): String {
    if (!Bidi.requiresBidi(line.toCharArray(), 0, line.length) && line.none(::isBidiControl)) return line
    val bidi = Bidi(line, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    val glyphs = Icu.shape(line).takeIf { it.length == line.length } ?: line
    val count = bidi.runCount
    val runs = Array<Any>(count) { it }
    Bidi.reorderVisually(ByteArray(count) { bidi.getRunLevel(it).toByte() }, 0, runs, 0, count)
    val out = StringBuilder(line.length)
    for (run in runs) {
        val i = run as Int
        val s = glyphs.substring(bidi.getRunStart(i), bidi.getRunLimit(i))
        out.append(if (bidi.getRunLevel(i) and 1 == 0) s.replace(LIGATURE_PLACEHOLDER.toString(), "") else reversedClusters(s))
    }
    return out.toString().filterNot(::isBidiControl)
}

private fun isBidiControl(c: Char) = c == '\u061C' || c == '\u200E' || c == '\u200F' || c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'
