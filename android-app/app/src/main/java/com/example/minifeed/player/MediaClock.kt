package com.example.minifeed.player

import android.os.SystemClock
import com.example.minifeed.Tunables
import kotlin.math.max

class MediaClock {
    private var anchorMediaTimeUs = 0L
    private var anchorElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
    private var speed = 1.0f
    private var running = false
    private var audioPositionProvider: (() -> Long?)? = null

    fun start(mediaTimeUs: Long) {
        anchorMediaTimeUs = mediaTimeUs
        anchorElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
        running = true
    }

    fun pause(): Long {
        val now = currentMediaTimeUs()
        anchorMediaTimeUs = now
        running = false
        return now
    }

    fun reAnchor(mediaTimeUs: Long) {
        anchorMediaTimeUs = max(0L, mediaTimeUs)
        anchorElapsedRealtimeNs = SystemClock.elapsedRealtimeNanos()
    }

    fun setSpeed(newSpeed: Float) {
        val current = currentMediaTimeUs()
        speed = newSpeed.coerceIn(Tunables.MIN_PLAYBACK_SPEED, Tunables.MAX_PLAYBACK_SPEED)
        reAnchor(current)
    }

    fun currentSpeed(): Float = speed

    fun setAudioPositionProvider(provider: (() -> Long?)?) {
        audioPositionProvider = provider
    }

    fun currentMediaTimeUs(): Long {
        audioPositionProvider?.invoke()?.let { audioUs ->
            if (running) return audioUs
        }
        if (!running) return anchorMediaTimeUs
        val elapsedUs = (SystemClock.elapsedRealtimeNanos() - anchorElapsedRealtimeNs) / 1_000L
        return anchorMediaTimeUs + (elapsedUs * speed).toLong()
    }
}
