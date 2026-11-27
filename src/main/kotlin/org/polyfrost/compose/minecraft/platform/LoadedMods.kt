package org.polyfrost.compose.minecraft.platform

import net.fabricmc.loader.api.FabricLoader

object LoadedMods {
    @JvmStatic
    fun isLoaded(id: String): Boolean = FabricLoader.getInstance().isModLoaded(id)

    @JvmStatic
    fun name(id: String): String? = FabricLoader.getInstance().getModContainer(id).map { it.metadata.name }.orElse(null)
}
