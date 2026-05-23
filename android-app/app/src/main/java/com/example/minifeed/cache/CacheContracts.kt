package com.example.minifeed.cache

import com.example.minifeed.data.VideoVariant
import java.net.URI
import java.security.MessageDigest

data class CacheKey(val value: String) {
    companion object {
        fun fromVariant(variant: VideoVariant): CacheKey {
            val uri = URI(variant.url).normalize().toString()
            val raw = "$uri|${variant.variantId}|${variant.mimeType}|${variant.contentLength ?: -1}"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            return CacheKey(digest.joinToString("") { "%02x".format(it) })
        }
    }
}

data class CacheEntry(
    val key: CacheKey,
    val segments: List<CacheSegment>,
    val contentLength: Long?,
    val unhealthyReason: String?
)

data class CacheSegment(
    val start: Long,
    val endInclusive: Long,
    val state: SegmentState
)

enum class SegmentState { EMPTY, FETCHING, PARTIAL, COMPLETE, UNHEALTHY, EVICTING }

sealed interface CacheReadResult {
    data class Hit(val key: CacheKey, val bytes: ByteArray, val source: CacheReadSource) : CacheReadResult
    data class Miss(val key: CacheKey, val missing: LongRange) : CacheReadResult
    data class Unhealthy(val key: CacheKey, val reason: String) : CacheReadResult
}

enum class CacheReadSource { MEMORY, DISK }

sealed class CacheError(val reason: String) {
    class RangeInvalid(reason: String) : CacheError(reason)
    class Corrupt(reason: String) : CacheError(reason)
    class DiskFailure(reason: String) : CacheError(reason)
}

interface CacheStore {
    suspend fun openEntry(key: CacheKey): CacheEntry
    suspend fun read(key: CacheKey, range: LongRange): CacheReadResult
    suspend fun write(key: CacheKey, offset: Long, bytes: ByteArray)
    suspend fun markUnhealthy(key: CacheKey, reason: CacheError)
    suspend fun evictIfNeeded()
    fun notePreloadIntent(key: CacheKey, variant: VideoVariant, bytes: Long, priority: Int)
}
