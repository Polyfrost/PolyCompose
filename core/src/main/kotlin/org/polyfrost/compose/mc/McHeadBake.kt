package org.polyfrost.compose.mc

import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.render.PolyBitmap

@ApiStatus.Internal
public object McHeadBake {
    public fun flat(face: PolyBitmap, hat: PolyBitmap): PolyBitmap {
        requireSquare(face, hat)
        val out = ByteArray(face.pixels.size)
        for (o in out.indices step 4) over(hat.pixels, o, face.pixels, o, out, o)
        return PolyBitmap(face.width, face.height, out)
    }

    public fun layered(face: PolyBitmap, hat: PolyBitmap): PolyBitmap {
        requireSquare(face, hat)
        val n = face.width
        val side = 9 * n
        val faceSide = 8 * n
        val inset = n / 2
        val f = face.pixels
        val h = hat.pixels
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
