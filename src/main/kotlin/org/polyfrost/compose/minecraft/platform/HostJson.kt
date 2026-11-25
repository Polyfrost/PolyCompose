package org.polyfrost.compose.minecraft.platform

import com.google.gson.JsonElement
import com.google.gson.JsonParser

object HostJson {
    @JvmStatic
    internal fun parse(json: String): JsonElement = JsonParser.parseString(json)
}
