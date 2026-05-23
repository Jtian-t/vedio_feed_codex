package com.example.minifeed.network

import com.example.minifeed.Tunables
import kotlin.math.roundToLong

class BandwidthEstimator {
    private val samples = ArrayDeque<Long>()

    fun addSample(bytes: Long, durationMs: Long) {
        if (durationMs <= 0 || bytes <= 0) return
        val kbps = ((bytes * 8.0) / durationMs).roundToLong()
        samples += kbps
        while (samples.size > Tunables.WEAK_NETWORK_SAMPLE_WINDOW) samples.removeFirst()
    }

    fun currentKbps(): Long {
        if (samples.isEmpty()) return -1L
        return samples.sorted()[samples.size / 2]
    }

    fun stableSampleCount(): Int = samples.size
}
