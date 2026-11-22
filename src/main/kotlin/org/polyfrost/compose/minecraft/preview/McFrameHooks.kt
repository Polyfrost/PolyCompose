package org.polyfrost.compose.minecraft.preview

import java.util.concurrent.CopyOnWriteArrayList
import org.jetbrains.annotations.ApiStatus
import org.polyfrost.compose.host.HostCallbacks

object McFrameHooks {
    private val beforeUi = CopyOnWriteArrayList<Runnable>()
    private var running = false

    @JvmStatic
    fun beforeUi(callback: Runnable): AutoCloseable {
        beforeUi += callback
        return AutoCloseable { beforeUi -= callback }
    }

    private val beforeExtract = CopyOnWriteArrayList<Runnable>()
    private var extracting = false

    /** For 26.1+: like [beforeUi], but runs before GUI extraction, which happens before rendering */
    @JvmStatic
    fun beforeExtract(callback: Runnable): AutoCloseable {
        beforeExtract += callback
        return AutoCloseable { beforeExtract -= callback }
    }

    @ApiStatus.Internal
    @JvmStatic
    fun runBeforeExtract() {
        if (beforeExtract.isEmpty() || extracting) return
        extracting = true
        try {
            HostCallbacks.each(beforeExtract, "A pre-extract frame hook") { it.run() }
        } finally {
            extracting = false
        }
    }

    @JvmStatic
    val hasBeforeUiCallbacks: Boolean
        get() = beforeUi.isNotEmpty()

    @ApiStatus.Internal
    @JvmStatic
    fun runBeforeUi() {
        if (beforeUi.isEmpty() || running) return
        running = true
        try {
            HostCallbacks.each(beforeUi, "A pre-UI frame hook") { it.run() }
        } finally {
            running = false
        }
    }
}
