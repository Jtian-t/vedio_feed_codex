package com.example.minifeed.scheduler

import com.example.minifeed.AppConfig
import com.example.minifeed.cache.PreloadScheduler
import com.example.minifeed.data.VideoItem
import com.example.minifeed.feed.FeedPageHandle
import com.example.minifeed.feed.FeedPlaybackState
import com.example.minifeed.feed.PlayerCoordinator
import com.example.minifeed.player.MediaCodecMiniPlayer
import com.example.minifeed.player.PlaybackSource
import com.example.minifeed.player.PlayerError
import com.example.minifeed.player.PlayerEvent
import com.example.minifeed.player.PlayerState
import com.example.minifeed.player.ReleaseReason
import com.example.minifeed.proxy.LocalProxyServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PlayerSlotCoordinator(
    private val items: List<VideoItem>,
    private val preloadScheduler: PreloadScheduler,
    private val proxyServer: LocalProxyServer?,
    private val playerFactory: () -> MediaCodecMiniPlayer
) : PlayerCoordinator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val current = PlayerSlot(SlotRole.Current, playerFactory())
    private val next = PlayerSlot(SlotRole.Next, playerFactory())
    private val previous = PlayerSlot(SlotRole.Previous, playerFactory())
    private val attachedPages = mutableMapOf<Int, FeedPageHandle>()
    private val mutableState = MutableStateFlow(FeedPlaybackState())
    override val activeState: StateFlow<FeedPlaybackState> = mutableState
    private val commandMutex = Mutex()
    private var activeIndex = -1
    private var rememberedItemId: String? = null
    private var rememberedPositionMs: Long = 0L
    private var desiredPlaying = false

    init {
        observeCurrent()
    }

    override suspend fun attachPage(page: FeedPageHandle) {
        if (page.index < 0) return
        commandMutex.withLock {
            attachedPages[page.index] = page
            if (page.index == activeIndex) {
                prepareCurrent(page, rememberedPositionMs)
            }
        }
    }

    override suspend fun onPageSelected(index: Int) {
        commandMutex.withLock {
            if (index !in items.indices) return
            if (index == activeIndex && desiredPlaying && mutableState.value.activeItemId == items[index].id) return
            rememberedPositionMs = 0L
            activeIndex = index
            mutableState.value = FeedPlaybackState(
                activeIndex = index,
                activeItemId = items[index].id,
                durationMs = items[index].durationMs,
                videoWidth = items[index].playableVariant?.width ?: items[index].width,
                videoHeight = items[index].playableVariant?.height ?: items[index].height,
                speed = 1.0f,
                preparing = true
            )
            val page = attachedPages[index] ?: return
            desiredPlaying = false
            current.release(ReleaseReason.PageChanged)
            previous.release(ReleaseReason.PageChanged)
            next.release(ReleaseReason.PageChanged)
            prepareCurrent(page, 0L)
            preloadScheduler.onActiveItemChanged(index, items)
            preparePredictedSlots(index)
        }
    }

    override suspend fun onPageDetached(index: Int) {
        if (index < 0) return
        attachedPages.remove(index)
    }

    override suspend fun onAppBackgrounded() {
        commandMutex.withLock {
            desiredPlaying = false
            rememberedItemId = items.getOrNull(activeIndex)?.id
            rememberedPositionMs = mutableState.value.positionMs
            current.release(ReleaseReason.LifecycleRelease)
            next.release(ReleaseReason.LifecycleRelease)
            previous.release(ReleaseReason.LifecycleRelease)
            preloadScheduler.cancelAll()
            proxyServer?.stop()
        }
    }

    override suspend fun onAppForegrounded() {
        commandMutex.withLock {
            val restoreIndex = items.indexOfFirst { it.id == rememberedItemId }.takeIf { it >= 0 } ?: activeIndex
            attachedPages[restoreIndex]?.let {
                activeIndex = restoreIndex
                prepareCurrent(it, rememberedPositionMs)
            }
        }
    }

    override fun togglePlayback() {
        if (desiredPlaying) {
            desiredPlaying = false
            current.player.pause()
        } else {
            desiredPlaying = true
            current.player.play()
        }
    }

    override fun setSpeed(speed: Float) {
        current.player.setSpeed(speed)
    }

    override suspend fun seekTo(positionMs: Long) {
        commandMutex.withLock {
            val duration = mutableState.value.durationMs.takeIf { it > 0 } ?: items.getOrNull(activeIndex)?.durationMs ?: 0L
            val target = if (duration > 0) positionMs.coerceIn(0L, duration) else positionMs.coerceAtLeast(0L)
            rememberedPositionMs = target
            preloadScheduler.onSeekOrCurrentPriorityChanged(items.getOrNull(activeIndex)?.id)
            runCatching { current.player.seekTo(target) }.onFailure {
                mutableState.value = mutableState.value.copy(error = it.message ?: "Seek failed", preparing = false)
            }
        }
    }

    private suspend fun prepareCurrent(page: FeedPageHandle, startPositionMs: Long) {
        val variant = page.item.playableVariant
        if (variant == null) {
            mutableState.value = FeedPlaybackState(
                activeIndex = page.index,
                activeItemId = page.item.id,
                durationMs = page.item.durationMs,
                videoWidth = page.item.playableVariant?.width ?: page.item.width,
                videoHeight = page.item.playableVariant?.height ?: page.item.height,
                speed = 1.0f,
                playing = false,
                preparing = false,
                error = PlayerError.SourceUnsupported("No MP4 fallback for ${page.item.id}").diagnostic
            )
            return
        }
        mutableState.value = mutableState.value.copy(
            activeIndex = page.index,
            activeItemId = page.item.id,
            durationMs = page.item.durationMs,
            videoWidth = page.item.playableVariant?.width ?: page.item.width,
            videoHeight = page.item.playableVariant?.height ?: page.item.height,
            speed = 1.0f,
            playing = false,
            preparing = true,
            error = null
        )
        runCatching {
            proxyServer?.start()
            val uri = if (AppConfig.USE_PROXY_PLAYBACK && proxyServer != null) {
                proxyServer.buildLocalUrl(variant)
            } else {
                variant.url
            }
            current.item = page.item
            current.player.prepare(PlaybackSource(page.item, variant, uri), page.renderTarget, startPositionMs)
            desiredPlaying = true
            current.player.play()
        }.onFailure {
            desiredPlaying = false
            mutableState.value = mutableState.value.copy(
                playing = false,
                preparing = false,
                error = it.message ?: "Playback prepare failed"
            )
        }
    }

    private suspend fun preparePredictedSlots(index: Int) {
        next.item = items.getOrNull(index + 1)
        previous.item = items.getOrNull(index - 1)
        next.item?.let { preloadScheduler.preload(it, priority = 3) }
    }

    private fun observeCurrent() {
        scope.launch {
            current.player.events.collect { event ->
                when (event) {
                    is PlayerEvent.Progress -> {
                        rememberedPositionMs = event.positionMs
                        mutableState.value = FeedPlaybackState(
                            activeIndex = activeIndex,
                            activeItemId = event.itemId,
                            positionMs = event.positionMs,
                            durationMs = event.durationMs,
                            videoWidth = mutableState.value.videoWidth,
                            videoHeight = mutableState.value.videoHeight,
                            speed = mutableState.value.speed,
                            playing = desiredPlaying,
                            preparing = false
                        )
                        items.getOrNull(activeIndex)?.let {
                            preloadScheduler.onPlaybackProgress(it, event.positionMs, event.durationMs)
                        }
                    }
                    is PlayerEvent.Completed -> {
                        desiredPlaying = false
                        mutableState.value = mutableState.value.copy(playing = false, preparing = false)
                    }
                    is PlayerEvent.Failed -> {
                        desiredPlaying = false
                        mutableState.value = mutableState.value.copy(playing = false, preparing = false, error = event.error.diagnostic)
                    }
                    is PlayerEvent.FormatChanged -> {
                        mutableState.value = mutableState.value.copy(videoWidth = event.width, videoHeight = event.height)
                    }
                    is PlayerEvent.FirstFrame -> Unit
                }
            }
        }
        scope.launch {
            current.player.states.collect { state ->
                when (state) {
                    is PlayerState.Paused -> {
                        desiredPlaying = false
                        mutableState.value = mutableState.value.copy(playing = false, preparing = false, positionMs = state.positionMs)
                    }
                    is PlayerState.Prepared -> {
                        mutableState.value = mutableState.value.copy(durationMs = state.durationMs, preparing = false)
                    }
                    is PlayerState.Preparing -> {
                        mutableState.value = mutableState.value.copy(preparing = true, error = null)
                    }
                    is PlayerState.Playing -> {
                        rememberedPositionMs = state.positionMs
                        mutableState.value = mutableState.value.copy(
                            activeItemId = state.itemId,
                            positionMs = state.positionMs,
                            durationMs = state.durationMs,
                            speed = state.speed,
                            playing = true,
                            preparing = false,
                            error = null
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

private enum class SlotRole { Current, Next, Previous }

private class PlayerSlot(
    private val role: SlotRole,
    val player: MediaCodecMiniPlayer,
    var item: VideoItem? = null
) {
    suspend fun release(reason: ReleaseReason) {
        item = null
        player.release(reason)
    }
}
