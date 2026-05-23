package com.example.minifeed.proxy

import com.example.minifeed.cache.CacheKey
import com.example.minifeed.data.VideoVariant

interface LocalProxyServer {
    suspend fun start(): ProxyEndpoint
    fun buildLocalUrl(variant: VideoVariant): String
    suspend fun stop()
}

data class ProxyEndpoint(val host: String, val port: Int)

data class ProxyRequest(val key: CacheKey, val variantId: String?, val range: LongRange?)
