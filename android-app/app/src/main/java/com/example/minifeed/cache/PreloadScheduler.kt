package com.example.minifeed.cache

import com.example.minifeed.Tunables
import com.example.minifeed.data.VideoItem
import com.example.minifeed.data.VideoVariant
import com.example.minifeed.feed.SwipeDirection
import com.example.minifeed.network.BandwidthEstimator
import com.example.minifeed.network.VariantSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class PreloadScheduler(
    private val cacheStore: CacheStore,
    private val bandwidthEstimator: BandwidthEstimator
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val variantSelector = VariantSelector(bandwidthEstimator)
    private var maxJobs = Tunables.MAX_PRELOAD_JOBS_NORMAL
    private var activeWindow: List<VideoItem> = emptyList()

    fun onPlaybackProgress(item: VideoItem, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        if (positionMs > 500 || positionMs > durationMs / 5) {
            activeWindow.dropWhile { it.id != item.id }.drop(1).take(1).forEach { preload(it, priority = 3) }
        }
    }

    fun onSwipeVelocity(direction: SwipeDirection, velocityPxPerSec: Float) {
        maxJobs = if (direction == SwipeDirection.FORWARD && velocityPxPerSec > 2_000f) {
            Tunables.MAX_PRELOAD_JOBS_FAST_FORWARD
        } else {
            Tunables.MAX_PRELOAD_JOBS_NORMAL
        }
    }

    fun onActiveItemChanged(index: Int, items: List<VideoItem>) {
        activeWindow = items.drop(index).take(1 + maxJobs)
        val keepIds = activeWindow.mapTo(mutableSetOf()) { it.id }
        jobs.filterKeys { it !in keepIds }.values.forEach { it.cancel() }
        activeWindow.drop(1).forEach { preload(it, priority = 3) }
    }

    fun onSeekOrCurrentPriorityChanged(activeItemId: String?) {
        jobs.filterKeys { it != activeItemId }.values.forEach { it.cancel() }
    }

    fun preload(item: VideoItem, priority: Int) {
        val variant = selectVariant(item) ?: return
        if (jobs[item.id]?.isActive == true) return
        val semaphore = Semaphore(maxJobs)
        jobs[item.id] = scope.launch {
            semaphore.withPermit {
                val key = CacheKey.fromVariant(variant)
                cacheStore.openEntry(key)
                val result = cacheStore.read(key, 0L until Tunables.PRELOAD_BYTES)
                if (result !is CacheReadResult.Hit) {
                    cacheStore.notePreloadIntent(key, variant, Tunables.PRELOAD_BYTES, priority)
                }
            }
        }
    }

    suspend fun cancelAll() {
        jobs.values.forEach { it.cancelAndJoin() }
        jobs.clear()
    }

    private fun selectVariant(item: VideoItem): VideoVariant? {
        return variantSelector.select(item)
    }
}
