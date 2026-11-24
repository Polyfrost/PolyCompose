package org.polyfrost.compose.render

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
    if (!Bidi.requiresBidi(line.toCharArray(), 0, line.length)) return line
    val bidi = Bidi(line, Bidi.DIRECTION_DEFAULT_LEFT_TO_RIGHT)
    val count = bidi.runCount
    val runs = Array<Any>(count) { it }
    Bidi.reorderVisually(ByteArray(count) { bidi.getRunLevel(it).toByte() }, 0, runs, 0, count)
    val out = StringBuilder(line.length)
    for (run in runs) {
        val i = run as Int
        val start = bidi.getRunStart(i)
        var end = bidi.getRunLimit(i)
        if (bidi.getRunLevel(i) and 1 == 0) {
            out.append(line, start, end)
            continue
        }
        while (end > start) {
            val cp = line.codePointBefore(end)
            end -= Character.charCount(cp)
            out.appendCodePoint(mirror(cp))
        }
    }
    return out.toString()
}

private fun mirror(cp: Int): Int = when (cp) {
    '('.code -> ')'.code
    ')'.code -> '('.code
    '['.code -> ']'.code
    ']'.code -> '['.code
    '{'.code -> '}'.code
    '}'.code -> '{'.code
    '<'.code -> '>'.code
    '>'.code -> '<'.code
    '«'.code -> '»'.code
    '»'.code -> '«'.code
    else -> cp
}
