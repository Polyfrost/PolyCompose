package org.polyfrost.compose.minecraft.compat

//? if = 1.8.9 {
/*import java.lang.reflect.Method
import net.minecraft.client.render.TextRenderer
import org.polyfrost.compose.minecraft.platform.LoadedMods
import org.slf4j.LoggerFactory

object ArgentumCompat {
    private class Batcher(val get: Method, val width: Method)

    private val batcher: Batcher? by lazy {
        if (!LoadedMods.isLoaded("argentum")) return@lazy null
        runCatching {
            val get = Class.forName("dev.rdh.argentum.impl.ext.TextRendererExtension").getMethod("argentum\$getBatcher")
            val width = get.returnType.getMethod("getCharWidth", Int::class.javaPrimitiveType)
            Batcher(get, width).takeIf { width.returnType == Float::class.javaPrimitiveType }
        }.getOrNull().also {
            if (it == null) LoggerFactory.getLogger("PolyCompose/ArgentumCompat").warn("Couldn't read Argentum's text widths; Minecraft-font text falls back to the renderer's rounded widths")
        }
    }

    @JvmStatic
    fun sheetAdvances(renderer: TextRenderer): FloatArray? {
        if (java.lang.Boolean.getBoolean("argentum.vanillaText")) return null
        val b = batcher ?: return null
        return runCatching {
            val instance = b.get.invoke(renderer)
            FloatArray(256) { b.width.invoke(instance, it) as Float }
        }.getOrNull()
    }
}
*///?}
