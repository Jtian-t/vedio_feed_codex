package com.example.minifeed.player

import android.view.Surface
import com.example.minifeed.data.VideoItem
import com.example.minifeed.data.VideoVariant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface MiniPlayer {
    val states: StateFlow<PlayerState>
    val events: Flow<PlayerEvent>

    suspend fun prepare(source: PlaybackSource, renderTarget: RenderTarget, startPositionMs: Long = 0)
    fun play()
    fun pause()
    suspend fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    suspend fun rebindRenderTarget(renderTarget: RenderTarget)
    suspend fun release(reason: ReleaseReason)
}

interface RenderTarget {
    val surfaceEvents: Flow<SurfaceEvent>
    fun currentSurface(): Surface?
    fun onVideoSizeChanged(width: Int, height: Int) = Unit
}

data class PlaybackSource(
    val item: VideoItem,
    val variant: VideoVariant,
    val uri: String
)

sealed interface SurfaceEvent {
    data class Available(val surface: Surface) : SurfaceEvent
    data object Lost : SurfaceEvent
}

sealed interface PlayerState {
    data object Idle : PlayerState
    data class Preparing(val itemId: String) : PlayerState
    data class Prepared(val itemId: String, val durationMs: Long) : PlayerState
    data class FirstFrameRendered(val itemId: String, val elapsedMs: Long) : PlayerState
    data class Playing(val itemId: String, val positionMs: Long, val durationMs: Long, val speed: Float) : PlayerState
    data class Paused(val itemId: String, val positionMs: Long) : PlayerState
    data class Buffering(val itemId: String, val positionMs: Long) : PlayerState
    data class Ended(val itemId: String) : PlayerState
    data class Error(val itemId: String?, val error: PlayerError) : PlayerState
    data object Released : PlayerState
}

sealed interface PlayerEvent {
    data class Progress(val itemId: String, val positionMs: Long, val durationMs: Long) : PlayerEvent
    data class FirstFrame(val itemId: String, val elapsedMs: Long) : PlayerEvent
    data class FormatChanged(val itemId: String, val width: Int, val height: Int) : PlayerEvent
    data class Completed(val itemId: String) : PlayerEvent
    data class Failed(val itemId: String?, val error: PlayerError) : PlayerEvent
}

sealed class PlayerError(val diagnostic: String) {
    class SourceUnsupported(reason: String) : PlayerError(reason)
    class NetworkTimeout(reason: String) : PlayerError(reason)
    class RangeInvalid(reason: String) : PlayerError(reason)
    class CacheCorrupt(reason: String) : PlayerError(reason)
    class DecodeConfigureFailed(reason: String) : PlayerError(reason)
    class DecodeRuntimeFailed(reason: String) : PlayerError(reason)
    class SurfaceLost(reason: String) : PlayerError(reason)
    class LifecycleRelease(reason: String) : PlayerError(reason)
}

enum class ReleaseReason {
    PageChanged,
    SurfaceDestroyed,
    LifecycleRelease,
    ErrorRecovery,
    AppShutdown
}
