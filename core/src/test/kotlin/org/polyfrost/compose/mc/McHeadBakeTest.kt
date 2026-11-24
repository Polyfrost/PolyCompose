package org.polyfrost.compose.mc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.polyfrost.compose.render.PolyBitmap

class McHeadBakeTest {
    private fun layer(size: Int, pixel: (Int, Int) -> Int): PolyBitmap {
        val out = ByteArray(size * size * 4)
        for (y in 0 until size) for (x in 0 until size) {
            val argb = pixel(x, y)
            val o = (y * size + x) * 4
            out[o] = (argb shr 16).toByte()
            out[o + 1] = (argb shr 8).toByte()
            out[o + 2] = argb.toByte()
            out[o + 3] = (argb ushr 24).toByte()
        }
        return PolyBitmap(size, size, out)
    }

    private fun PolyBitmap.argb(x: Int, y: Int): Int {
        val o = (y * width + x) * 4
        fun at(i: Int) = pixels[o + i].toInt() and 0xFF
        return (at(3) shl 24) or (at(0) shl 16) or (at(1) shl 8) or at(2)
    }

    private fun faceColor(x: Int, y: Int) = 0xFF000000.toInt() or (x shl 16) or (y shl 8) or 0x40

    @Test
    fun faceSitsHalfATexelInsideTheHat() {
        val bake = McHeadBake.layered(layer(8, ::faceColor), layer(8) { _, _ -> 0 })
        assertEquals(72, bake.width)
        assertEquals(72, bake.height)
        assertEquals(0, bake.argb(0, 0) ushr 24)
        assertEquals(0, bake.argb(3, 36) ushr 24)
        assertEquals(0, bake.argb(68, 36) ushr 24)
        assertEquals(faceColor(0, 0), bake.argb(4, 4))
        assertEquals(faceColor(0, 0), bake.argb(11, 11))
        assertEquals(faceColor(1, 0), bake.argb(12, 4))
        assertEquals(faceColor(7, 7), bake.argb(67, 67))

        val hd = McHeadBake.layered(layer(32, ::faceColor), layer(32) { _, _ -> 0 })
        assertEquals(288, hd.width)
        assertEquals(faceColor(2, 0), hd.argb(32, 16))
    }

    @Test
    fun hdHatKeepsOnePixelFeatures() {
        val hatColor = 0xFFFF0000.toInt()
        val bake = McHeadBake.layered(layer(32, ::faceColor), layer(32) { x, y -> if (x == 3 && y == 0) hatColor else 0 })
        assertEquals(hatColor, bake.argb(27, 0))
        assertEquals(hatColor, bake.argb(35, 8))
        assertEquals(0, bake.argb(36, 0) ushr 24)
        assertEquals(0, bake.argb(0, 0) ushr 24)
    }

    @Test
    fun hatTexelsCoverNinePixelsAndCoverTheFace() {
        val hatColor = 0xFFFF0000.toInt()
        val bake = McHeadBake.layered(layer(8, ::faceColor), layer(8) { x, y -> if (x == 1 && y == 0) hatColor else 0 })
        assertEquals(hatColor, bake.argb(9, 0))
        assertEquals(hatColor, bake.argb(17, 8))
        assertEquals(0, bake.argb(18, 0) ushr 24)
        assertEquals(hatColor, bake.argb(12, 4))
        assertEquals(faceColor(2, 0), bake.argb(20, 4))
    }

    @Test
    fun translucentHatBlendsOntoTheFace() {
        val bake = McHeadBake.layered(layer(8) { _, _ -> 0xFF0000FF.toInt() }, layer(8) { _, _ -> 0x80FF0000.toInt() })
        val mixed = bake.argb(36, 36)
        assertEquals(0xFF, mixed ushr 24)
        assertEquals(0x80, (mixed shr 16) and 0xFF)
        assertEquals(0x7F, mixed and 0xFF)
        assertEquals(0x80FF0000.toInt(), bake.argb(0, 0))
    }

    @Test
    fun translucentHatOverATransparentFaceKeepsItsAlpha() {
        val face = layer(8) { _, _ -> 0 }
        val hat = layer(8) { _, _ -> 0x80FF0000.toInt() }
        assertEquals(0x80FF0000.toInt(), McHeadBake.flat(face, hat).argb(3, 3))
        assertEquals(0x80FF0000.toInt(), McHeadBake.layered(face, hat).argb(36, 36))
    }
}
