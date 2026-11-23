package org.polyfrost.compose.mc

import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.render.PolyBitmap

@ApiStatus.Internal
public object McHeadBake {
    private const val MAX_TEXELS = 16

    public fun flat(face: PolyBitmap, hat: PolyBitmap): PolyBitmap {
        requireSquare(face, hat)
        val out = ByteArray(face.pixels.size)
        for (o in out.indices step 4) over(hat.pixels, o, face.pixels, o, out, o)
        return PolyBitmap(face.width, face.height, out)
    }

    public fun layered(face: PolyBitmap, hat: PolyBitmap): PolyBitmap {
        requireSquare(face, hat)
        val n = texels(face.width)
        val side = 9 * n
        val faceSide = 8 * n
        val inset = n / 2
        val f = downsample(face, n).pixels
        val h = downsample(hat, n).pixels
        val out = ByteArray(side * side * 4)
        for (y in 0 until side) {
            val hatRow = y / 9 * n
            val fy = y - inset
            val faceRow = if (fy in 0 until faceSide) fy / 8 * n else -1
            for (x in 0 until side) {
                val hatAt = (hatRow + x / 9) * 4
                val fx = x - inset
                val faceAt = if (faceRow >= 0 && fx in 0 until faceSide) (faceRow + fx / 8) * 4 else -1
                over(h, hatAt, f, faceAt, out, (y * side + x) * 4)
            }
        }
        return PolyBitmap(side, side, out)
    }

    private fun downsample(layer: PolyBitmap, n: Int): PolyBitmap {
        val src = layer.width
        if (src == n) return layer
        val p = layer.pixels
        val out = ByteArray(n * n * 4)
        for (y in 0 until n) {
            val y0 = y * src / n
            val y1 = (y + 1) * src / n
            for (x in 0 until n) {
                val x0 = x * src / n
                val x1 = (x + 1) * src / n
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (sy in y0 until y1) {
                    for (sx in x0 until x1) {
                        val i = (sy * src + sx) * 4
                        val pa = p[i + 3].toInt() and 0xFF
                        a += pa
                        r += (p[i].toInt() and 0xFF) * pa
                        g += (p[i + 1].toInt() and 0xFF) * pa
                        b += (p[i + 2].toInt() and 0xFF) * pa
                    }
                }
                val count = (y1 - y0) * (x1 - x0)
                val o = (y * n + x) * 4
                if (a > 0) {
                    out[o] = ((r + a / 2) / a).toByte()
                    out[o + 1] = ((g + a / 2) / a).toByte()
                    out[o + 2] = ((b + a / 2) / a).toByte()
                }
                out[o + 3] = ((a + count / 2) / count).toByte()
            }
        }
        return PolyBitmap(n, n, out)
    }

    private fun texels(size: Int): Int = minOf(size, MAX_TEXELS)

    private fun requireSquare(face: PolyBitmap, hat: PolyBitmap) {
        val src = face.width
        require(src > 0 && face.height == src && hat.width == src && hat.height == src) { "face and hat must be the same square size" }
    }

    private fun over(top: ByteArray, t: Int, bottom: ByteArray, b: Int, out: ByteArray, o: Int) {
        val ta = top[t + 3].toInt() and 0xFF
        if (ta == 255 || b < 0) {
            System.arraycopy(top, t, out, o, 4)
            return
        }
        if (ta == 0) {
            System.arraycopy(bottom, b, out, o, 4)
            return
        }
        val ba = (bottom[b + 3].toInt() and 0xFF) * (255 - ta)
        val a = ta * 255 + ba
        for (c in 0 until 3) {
            val tc = top[t + c].toInt() and 0xFF
            val bc = bottom[b + c].toInt() and 0xFF
            out[o + c] = ((tc * ta * 255 + bc * ba + a / 2) / a).toByte()
        }
        out[o + 3] = ((a + 127) / 255).toByte()
    }
}
