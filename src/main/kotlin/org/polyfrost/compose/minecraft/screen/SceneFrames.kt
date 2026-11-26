@file:OptIn(InternalComposeUiApi::class)

package org.polyfrost.compose.minecraft.screen

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.platform.FrameRecomposer
import androidx.compose.ui.scene.ComposeScene
import androidx.compose.ui.scene.hasInvalidations
import org.polyfrost.compose.profile.ProfileHost
import org.polyfrost.compose.profile.ProfilePhase
import org.polyfrost.compose.profile.measure

internal class SceneFrames(private val schedule: () -> Unit) {
    private var inFrame = false

    val onInvalidation: () -> Unit = { if (!inFrame) schedule() }

    fun hasWork(recomposer: FrameRecomposer, scene: ComposeScene): Boolean {
        Snapshot.sendApplyNotifications()
        return recomposer.hasPendingWork() || scene.hasInvalidations()
    }

    fun advance(recomposer: FrameRecomposer, scene: ComposeScene, nanos: Long, profile: ProfileHost?) {
        inFrame = true
        try {
            profile.measure(ProfilePhase.Compose) { recomposer.performFrame(nanos) }
            profile.measure(ProfilePhase.Layout) { retryingDisposedLayers { scene.measureAndLayout() } }
        } finally {
            inFrame = false
        }
        if (scene.hasInvalidations()) schedule()
    }

    fun draw(scene: ComposeScene, canvas: Canvas) {
        inFrame = true
        try {
            retryingDisposedLayers { scene.draw(canvas) }
        } finally {
            inFrame = false
        }
        if (scene.hasInvalidations()) schedule()
    }
}

private const val DISPOSED_LAYER_RETRIES = 3

private const val DISPOSED_OWNER_MESSAGE = "RootNodeOwner is already disposed"

internal inline fun <T> retryingDisposedLayers(block: () -> T): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: IllegalArgumentException) {
            if (e.message != DISPOSED_OWNER_MESSAGE || ++attempt > DISPOSED_LAYER_RETRIES) throw e
        }
    }
}
