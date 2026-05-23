package com.example.minifeed.network

import com.example.minifeed.Tunables
import com.example.minifeed.data.RangeSupport
import com.example.minifeed.perf.MetricEvent
import com.example.minifeed.perf.MetricsSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

data class RangeFetchRequest(
    val url: String,
    val range: LongRange?,
    val priority: Int,
    val knownContentLength: Long? = null
)

sealed interface RangeFetchResult {
    data class Success(
        val bytes: ByteArray,
        val range: LongRange?,
        val contentLength: Long?,
        val rangeSupport: RangeSupport,
        val etag: String?,
        val lastModified: String?
    ) : RangeFetchResult

    data class NotSatisfiable(val contentLength: Long?) : RangeFetchResult
    data class Invalid(val reason: String) : RangeFetchResult
    data class Failed(val reason: String) : RangeFetchResult
}

class OkHttpRangeFetcher(
    private val metrics: MetricsSink,
    private val bandwidthEstimator: BandwidthEstimator
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .addInterceptor(TimingInterceptor(metrics, bandwidthEstimator))
        .build()

    suspend fun fetch(request: RangeFetchRequest): RangeFetchResult {
        var delayMs = Tunables.INITIAL_RETRY_DELAY_MS
        repeat(Tunables.RANGE_RETRY_COUNT) { attempt ->
            val result = fetchOnce(request)
            if (result !is RangeFetchResult.Failed || attempt == Tunables.RANGE_RETRY_COUNT - 1) {
                return result
            }
            delay(delayMs)
            delayMs = (delayMs * 2).coerceAtMost(Tunables.MAX_RETRY_DELAY_MS)
        }
        return RangeFetchResult.Failed("Retries exhausted")
    }

    private suspend fun fetchOnce(request: RangeFetchRequest): RangeFetchResult = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(request.url)
            request.range?.let { builder.header("Range", "bytes=${it.first}-${it.last}") }
            client.newCall(builder.build()).execute().use { response ->
                when (response.code) {
                    206 -> parsePartial(response, request)
                    200 -> {
                        val bytes = response.body?.bytes() ?: ByteArray(0)
                        RangeFetchResult.Success(
                            bytes = bytes,
                            range = null,
                            contentLength = response.header("Content-Length")?.toLongOrNull(),
                            rangeSupport = RangeSupport.NO,
                            etag = response.header("ETag"),
                            lastModified = response.header("Last-Modified")
                        )
                    }
                    416 -> RangeFetchResult.NotSatisfiable(response.header("Content-Range")?.substringAfter("/")?.toLongOrNull())
                    else -> RangeFetchResult.Failed("HTTP ${response.code}")
                }
            }
        } catch (e: IOException) {
            RangeFetchResult.Failed(e.message ?: "Network error")
        }
    }

    private fun parsePartial(response: Response, request: RangeFetchRequest): RangeFetchResult {
        val contentRange = response.header("Content-Range")
            ?: return RangeFetchResult.Invalid("206 without Content-Range")
        val total = contentRange.substringAfter("/", "").toLongOrNull()
        val start = contentRange.substringAfter("bytes ").substringBefore("-").toLongOrNull()
        val end = contentRange.substringAfter("-").substringBefore("/").toLongOrNull()
        if (start == null || end == null || start > end) return RangeFetchResult.Invalid("Invalid Content-Range: $contentRange")
        if (request.knownContentLength != null && total != null && total != request.knownContentLength) {
            return RangeFetchResult.Invalid("Content length changed from ${request.knownContentLength} to $total")
        }
        val bytes = response.body?.bytes() ?: ByteArray(0)
        return RangeFetchResult.Success(
            bytes = bytes,
            range = start..end,
            contentLength = total,
            rangeSupport = RangeSupport.YES,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified")
        )
    }
}

private class TimingInterceptor(
    private val metrics: MetricsSink,
    private val bandwidthEstimator: BandwidthEstimator
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val started = System.currentTimeMillis()
        val response = chain.proceed(request)
        val durationMs = (System.currentTimeMillis() - started).coerceAtLeast(1L)
        val bytes = response.header("Content-Length")?.toLongOrNull() ?: 0L
        val range = request.header("Range")?.removePrefix("bytes=")?.let { raw ->
            val parts = raw.split("-")
            parts.getOrNull(0)?.toLongOrNull()?.let { start ->
                parts.getOrNull(1)?.toLongOrNull()?.let { end -> start..end }
            }
        }
        bandwidthEstimator.addSample(bytes, durationMs)
        metrics.record(MetricEvent.NetworkFetch(request.url.toString(), range, bytes, durationMs))
        return response
    }
}
