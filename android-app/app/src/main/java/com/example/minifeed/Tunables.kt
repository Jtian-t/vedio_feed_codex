package com.example.minifeed

object Tunables {
    const val STARTUP_BUFFER_BYTES = 512 * 1024L
    const val LOW_WATER_BUFFER_BYTES = 256 * 1024L
    const val HIGH_WATER_BUFFER_BYTES = 1024 * 1024L
    const val PRELOAD_BYTES = 1024 * 1024L
    const val MAX_PRELOAD_JOBS_NORMAL = 1
    const val MAX_PRELOAD_JOBS_FAST_FORWARD = 2
    const val DISK_CACHE_LIMIT_BYTES = 512L * 1024L * 1024L
    const val MEMORY_CACHE_LIMIT_BYTES = 16L * 1024L * 1024L
    const val RANGE_RETRY_COUNT = 3
    const val INITIAL_RETRY_DELAY_MS = 300L
    const val MAX_RETRY_DELAY_MS = 2_000L
    const val LATE_FRAME_TOLERANCE_US = 30_000L
    const val DROP_FRAME_THRESHOLD_US = 80_000L
    const val WEAK_NETWORK_SAMPLE_WINDOW = 5
    const val DOWNGRADE_BITRATE_MULTIPLIER = 1.3
    const val UPGRADE_BITRATE_MULTIPLIER = 2.0
    const val NORMAL_PLAYBACK_SPEED = 1.0f
    const val MIN_PLAYBACK_SPEED = 0.25f
    const val MAX_PLAYBACK_SPEED = 5.0f
    const val LONG_PRESS_SPEED = 5.0f
}
