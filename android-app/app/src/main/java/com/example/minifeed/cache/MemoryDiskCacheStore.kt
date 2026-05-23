package com.example.minifeed.cache

import android.content.Context
import com.example.minifeed.Tunables
import com.example.minifeed.data.VideoVariant
import com.example.minifeed.perf.CacheLayer
import com.example.minifeed.perf.MetricEvent
import com.example.minifeed.perf.MetricsSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoryDiskCacheStore(
    context: Context,
    private val metrics: MetricsSink
) : CacheStore {
    private val root = File(context.cacheDir, "video-cache").also { it.mkdirs() }
    private val memory = LinkedHashMap<CacheKey, ByteArray>(16, 0.75f, true)
    private val locks = mutableMapOf<CacheKey, Mutex>()
    private val preloadIntents = mutableListOf<String>()

    override suspend fun openEntry(key: CacheKey): CacheEntry = withEntryLock(key) {
        readIndex(key) ?: CacheEntry(key, emptyList(), null, null).also { writeIndex(it) }
    }

    override suspend fun read(key: CacheKey, range: LongRange): CacheReadResult = withEntryLock(key) {
        memory[key]?.let { bytes ->
            if (bytes.size > range.last) {
                val slice = bytes.copyOfRange(range.first.toInt(), (range.last + 1).toInt())
                metrics.record(MetricEvent.CacheRead(key.value, slice.size.toLong(), CacheLayer.MEMORY))
                return@withEntryLock CacheReadResult.Hit(key, slice, CacheReadSource.MEMORY)
            }
        }
        val file = dataFile(key)
        if (file.exists() && file.length() > range.last) {
            val bytes = withContext(Dispatchers.IO) {
                file.inputStream().use {
                    it.skip(range.first)
                    it.readNBytes((range.last - range.first + 1).toInt())
                }
            }
            promote(key, bytes)
            metrics.record(MetricEvent.CacheRead(key.value, bytes.size.toLong(), CacheLayer.DISK))
            CacheReadResult.Hit(key, bytes, CacheReadSource.DISK)
        } else {
            metrics.record(MetricEvent.CacheRead(key.value, 0L, CacheLayer.MISS))
            CacheReadResult.Miss(key, range)
        }
    }

    override suspend fun write(key: CacheKey, offset: Long, bytes: ByteArray) = withEntryLock(key) {
        withContext(Dispatchers.IO) {
            dataFile(key).outputStream().use { out ->
                if (offset != 0L) dataFile(key).inputStream().use { existing -> existing.copyTo(out) }
                out.write(bytes)
            }
        }
        promote(key, bytes)
        val entry = readIndex(key) ?: CacheEntry(key, emptyList(), null, null)
        val segment = CacheSegment(offset, offset + bytes.size - 1, SegmentState.COMPLETE)
        writeIndex(entry.copy(segments = normalize(entry.segments + segment)))
        evictIfNeeded()
    }

    override suspend fun markUnhealthy(key: CacheKey, reason: CacheError) = withEntryLock(key) {
        val entry = readIndex(key) ?: CacheEntry(key, emptyList(), null, null)
        writeIndex(entry.copy(unhealthyReason = reason.reason, segments = entry.segments.map { it.copy(state = SegmentState.UNHEALTHY) }))
    }

    override suspend fun evictIfNeeded() {
        withContext(Dispatchers.IO) {
            val entries = root.listFiles { file -> file.extension == "idx" }.orEmpty()
                .mapNotNull { readIndexFile(it) to it }
                .sortedBy { it.first?.let { entry -> indexFile(entry.key).lastModified() } ?: 0L }
            var total = root.listFiles { file -> file.extension == "bin" }.orEmpty().sumOf { it.length() }
            for ((entry, _) in entries) {
                if (total <= Tunables.DISK_CACHE_LIMIT_BYTES) break
                if (entry != null) {
                    val file = dataFile(entry.key)
                    total -= file.length()
                    file.delete()
                    indexFile(entry.key).delete()
                }
            }
        }
    }

    override fun notePreloadIntent(key: CacheKey, variant: VideoVariant, bytes: Long, priority: Int) {
        preloadIntents += "${key.value}:${variant.variantId}:$bytes:P$priority"
    }

    private suspend fun <T> withEntryLock(key: CacheKey, block: suspend () -> T): T {
        val lock = locks.getOrPut(key) { Mutex() }
        return lock.withLock { block() }
    }

    private fun promote(key: CacheKey, bytes: ByteArray) {
        memory[key] = bytes
        var total = memory.values.sumOf { it.size }
        val iterator = memory.entries.iterator()
        while (total > Tunables.MEMORY_CACHE_LIMIT_BYTES && iterator.hasNext()) {
            val entry = iterator.next()
            total -= entry.value.size
            iterator.remove()
        }
    }

    private fun normalize(segments: List<CacheSegment>): List<CacheSegment> {
        return segments.sortedBy { it.start }.fold(mutableListOf()) { acc, next ->
            val last = acc.lastOrNull()
            if (last != null && last.endInclusive + 1 >= next.start && last.state == next.state) {
                acc[acc.lastIndex] = last.copy(endInclusive = maxOf(last.endInclusive, next.endInclusive))
            } else {
                acc += next
            }
            acc
        }
    }

    private fun readIndex(key: CacheKey): CacheEntry? = readIndexFile(indexFile(key))

    private fun readIndexFile(file: File): CacheEntry? {
        if (!file.exists()) return null
        val json = JSONObject(file.readText())
        val key = CacheKey(json.getString("cacheKey"))
        val segmentsJson = json.optJSONArray("segments") ?: JSONArray()
        val segments = List(segmentsJson.length()) { i ->
            val item = segmentsJson.getJSONObject(i)
            CacheSegment(item.getLong("start"), item.getLong("endInclusive"), SegmentState.valueOf(item.getString("state")))
        }
        return CacheEntry(
            key = key,
            segments = segments,
            contentLength = if (json.isNull("contentLength")) null else json.getLong("contentLength"),
            unhealthyReason = if (json.isNull("unhealthyReason")) null else json.getString("unhealthyReason")
        )
    }

    private fun writeIndex(entry: CacheEntry) {
        val segments = JSONArray()
        entry.segments.forEach {
            segments.put(JSONObject().put("start", it.start).put("endInclusive", it.endInclusive).put("state", it.state.name))
        }
        val json = JSONObject()
            .put("version", 1)
            .put("cacheKey", entry.key.value)
            .put("contentLength", entry.contentLength)
            .put("segments", segments)
            .put("lastAccessEpochMs", System.currentTimeMillis())
            .put("unhealthyReason", entry.unhealthyReason)
        indexFile(entry.key).writeText(json.toString(2))
    }

    private fun dataFile(key: CacheKey) = File(root, "${key.value}.bin")
    private fun indexFile(key: CacheKey) = File(root, "${key.value}.idx")
}
