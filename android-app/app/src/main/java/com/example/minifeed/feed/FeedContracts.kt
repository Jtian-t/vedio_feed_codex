package com.example.minifeed.feed

import com.example.minifeed.data.VideoItem
import com.example.minifeed.player.RenderTarget
import kotlinx.coroutines.flow.StateFlow

data class FeedPageHandle(
    val index: Int,
    val item: VideoItem,
    val renderTarget: RenderTarget
)

data class FeedWindow(
    val items: List<VideoItem>,
    val currentIndex: Int
) {
    fun current(): VideoItem? = items.getOrNull(currentIndex)
    fun next(distance: Int = 1): VideoItem? = items.getOrNull(currentIndex + distance)
    fun previous(): VideoItem? = items.getOrNull(currentIndex - 1)
}

data class FeedPlaybackState(
    val activeIndex: Int = -1,
    val activeItemId: String? = null,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val speed: Float = 1.0f,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    val error: String? = null
)

enum class SwipeDirection { FORWARD, BACKWARD, NONE }

enum class CancelReason { DirectionChanged, PageChanged, Backgrounded, SeekChanged }

interface PlayerCoordinator {
    val activeState: StateFlow<FeedPlaybackState>
    suspend fun attachPage(page: FeedPageHandle)
    suspend fun onPageSelected(index: Int)
    suspend fun onPageDetached(index: Int)
    suspend fun onAppBackgrounded()
    suspend fun onAppForegrounded()
    fun togglePlayback()
    fun setSpeed(speed: Float)
    suspend fun seekTo(positionMs: Long)
}
