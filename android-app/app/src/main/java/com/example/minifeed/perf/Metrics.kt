package com.example.minifeed.perf

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

interface MetricsSink {
    fun record(event: MetricEvent)
}

sealed interface MetricEvent {
    data class PrepareStarted(val itemId: String, val source: String, val timeMs: Long = now()) : MetricEvent
    data class PrepareFinished(val itemId: String, val durationMs: Long) : MetricEvent
    data class FirstFrameRendered(val itemId: String, val elapsedMs: Long) : MetricEvent
    data class CacheRead(val key: String, val bytes: Long, val source: CacheLayer) : MetricEvent
    data class NetworkFetch(val url: String, val range: LongRange?, val bytes: Long, val durationMs: Long) : MetricEvent
    data class PlaybackError(val itemId: String?, val type: String, val recovery: String) : MetricEvent
    data class PlayerStateTransition(val from: String, val to: String, val itemId: String?) : MetricEvent
    data class FrameLate(val itemId: String, val lateByMs: Long) : MetricEvent
    data class FrameDrop(val itemId: String, val lateByMs: Long) : MetricEvent
    data class ReleaseLatency(val reason: String, val elapsedMs: Long) : MetricEvent
    data class ProxyFailure(val key: String, val reason: String) : MetricEvent
    data class SwipeStability(val count: Int, val crashes: Int) : MetricEvent
}

enum class CacheLayer { MEMORY, DISK, NETWORK, MISS }

class InMemoryMetricsSink : MetricsSink {
    private val mutableEvents = MutableSharedFlow<MetricEvent>(extraBufferCapacity = 256)
    val events: SharedFlow<MetricEvent> = mutableEvents

    override fun record(event: MetricEvent) {
        mutableEvents.tryEmit(event)
    }
}

fun now(): Long = SystemClock.elapsedRealtime()
