package com.example.minifeed.proxy

import com.example.minifeed.cache.CacheError
import com.example.minifeed.cache.CacheKey
import com.example.minifeed.cache.CacheReadResult
import com.example.minifeed.cache.CacheStore
import com.example.minifeed.data.VideoVariant
import com.example.minifeed.network.OkHttpRangeFetcher
import com.example.minifeed.network.RangeFetchRequest
import com.example.minifeed.network.RangeFetchResult
import com.example.minifeed.perf.MetricEvent
import com.example.minifeed.perf.MetricsSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class SimpleLocalProxyServer(
    private val cacheStore: CacheStore,
    private val fetcher: OkHttpRangeFetcher,
    private val metrics: MetricsSink
) : LocalProxyServer {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val variants = ConcurrentHashMap<CacheKey, VideoVariant>()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null
    private var endpoint = ProxyEndpoint("127.0.0.1", 0)

    override suspend fun start(): ProxyEndpoint = withContext(Dispatchers.IO) {
        if (serverSocket != null) return@withContext endpoint
        val socket = ServerSocket(0)
        serverSocket = socket
        endpoint = ProxyEndpoint("127.0.0.1", socket.localPort)
        acceptJob = scope.launch {
            while (!socket.isClosed) {
                runCatching { socket.accept() }.onSuccess { client ->
                    launch { handle(client) }
                }
            }
        }
        endpoint
    }

    override fun buildLocalUrl(variant: VideoVariant): String {
        val key = CacheKey.fromVariant(variant)
        variants[key] = variant
        return "http://${endpoint.host}:${endpoint.port}/video/${key.value}?variant=${URLEncoder.encode(variant.variantId, "UTF-8")}"
    }

    override suspend fun stop() {
        withContext(Dispatchers.IO) {
            runCatching { serverSocket?.close() }
            serverSocket = null
        }
        acceptJob?.cancelAndJoin()
        acceptJob = null
    }

    private suspend fun handle(socket: Socket) {
        socket.use { client ->
            val reader = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isBlank()) break
                val split = line.indexOf(":")
                if (split > 0) headers[line.substring(0, split).trim().lowercase()] = line.substring(split + 1).trim()
            }
            val request = parseRequest(requestLine, headers) ?: return writeResponse(client, 404, emptyMap(), ByteArray(0))
            val variant = variants[request.key] ?: return writeResponse(client, 404, emptyMap(), ByteArray(0))
            val range = request.range ?: 0L..((variant.contentLength ?: Long.MAX_VALUE) - 1L).coerceAtMost(1024L * 1024L - 1L)
            when (val cached = cacheStore.read(request.key, range)) {
                is CacheReadResult.Hit -> writeVideoResponse(client, cached.bytes, range, variant.contentLength, partial = request.range != null)
                is CacheReadResult.Unhealthy -> {
                    metrics.record(MetricEvent.ProxyFailure(request.key.value, cached.reason))
                    writeResponse(client, 503, emptyMap(), cached.reason.toByteArray())
                }
                is CacheReadResult.Miss -> fetchAndServe(client, request.key, variant, cached.missing, request.range != null)
            }
        }
    }

    private suspend fun fetchAndServe(socket: Socket, key: CacheKey, variant: VideoVariant, range: LongRange, partial: Boolean) {
        when (val result = fetcher.fetch(RangeFetchRequest(variant.url, range, priority = 0, knownContentLength = variant.contentLength))) {
            is RangeFetchResult.Success -> {
                cacheStore.write(key, result.range?.first ?: 0L, result.bytes)
                writeVideoResponse(socket, result.bytes, result.range ?: range, result.contentLength, partial && result.range != null)
            }
            is RangeFetchResult.Invalid -> {
                cacheStore.markUnhealthy(key, CacheError.RangeInvalid(result.reason))
                metrics.record(MetricEvent.ProxyFailure(key.value, result.reason))
                writeResponse(socket, 502, emptyMap(), result.reason.toByteArray())
            }
            is RangeFetchResult.NotSatisfiable -> writeResponse(socket, 416, mapOf("Content-Range" to "bytes */${result.contentLength ?: "*"}"), ByteArray(0))
            is RangeFetchResult.Failed -> {
                metrics.record(MetricEvent.ProxyFailure(key.value, result.reason))
                writeResponse(socket, 504, emptyMap(), result.reason.toByteArray())
            }
        }
    }

    private fun parseRequest(requestLine: String, headers: Map<String, String>): ProxyRequest? {
        val path = requestLine.split(" ").getOrNull(1) ?: return null
        val keyValue = path.substringAfter("/video/", "").substringBefore("?").takeIf { it.isNotBlank() } ?: return null
        val variantId = path.substringAfter("variant=", "").takeIf { it.isNotBlank() }
        val range = headers["range"]?.removePrefix("bytes=")?.let { raw ->
            val parts = raw.split("-")
            val start = parts.getOrNull(0)?.toLongOrNull()
            val end = parts.getOrNull(1)?.toLongOrNull()
            if (start != null && end != null) start..end else null
        }
        return ProxyRequest(CacheKey(keyValue), variantId, range)
    }

    private fun writeVideoResponse(socket: Socket, bytes: ByteArray, range: LongRange, total: Long?, partial: Boolean) {
        val headers = mutableMapOf(
            "Content-Type" to "video/mp4",
            "Content-Length" to bytes.size.toString(),
            "Accept-Ranges" to "bytes"
        )
        if (partial && total != null) headers["Content-Range"] = "bytes ${range.first}-${range.first + bytes.size - 1}/$total"
        writeResponse(socket, if (partial) 206 else 200, headers, bytes)
    }

    private fun writeResponse(socket: Socket, code: Int, headers: Map<String, String>, body: ByteArray) {
        val reason = when (code) {
            200 -> "OK"
            206 -> "Partial Content"
            404 -> "Not Found"
            416 -> "Range Not Satisfiable"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            504 -> "Gateway Timeout"
            else -> "OK"
        }
        val out = socket.getOutputStream()
        out.write("HTTP/1.1 $code $reason\r\n".toByteArray())
        headers.forEach { (name, value) -> out.write("$name: $value\r\n".toByteArray()) }
        out.write("Connection: close\r\n\r\n".toByteArray())
        out.write(body)
        out.flush()
    }
}
