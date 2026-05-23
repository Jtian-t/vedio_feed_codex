package com.example.minifeed.proxy

import com.example.minifeed.Tunables

class BufferWatermarkPolicy(
    private val startupBytes: Long = Tunables.STARTUP_BUFFER_BYTES,
    private val lowWaterBytes: Long = Tunables.LOW_WATER_BUFFER_BYTES,
    private val highWaterBytes: Long = Tunables.HIGH_WATER_BUFFER_BYTES
) {
    fun canStart(contiguousBytes: Long, hasMp4Header: Boolean): Boolean {
        return hasMp4Header && contiguousBytes >= startupBytes
    }

    fun shouldEnterBuffering(contiguousBytesAhead: Long): Boolean {
        return contiguousBytesAhead < lowWaterBytes
    }

    fun shouldResume(contiguousBytesAhead: Long): Boolean {
        return contiguousBytesAhead >= highWaterBytes
    }
}
