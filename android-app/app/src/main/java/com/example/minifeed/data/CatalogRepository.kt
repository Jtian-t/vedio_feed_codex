package com.example.minifeed.data

import android.content.Context
import com.example.minifeed.AppConfig
import org.json.JSONArray
import org.json.JSONObject

class CatalogRepository(private val context: Context) {
    fun loadBaseCatalog(): CatalogLoadResult {
        val raw = context.assets.open("mock/videos.json").bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val items = root.getJSONArray("items").toVideoItems()
        val errors = CatalogValidator.validate(items)
        return CatalogLoadResult(items, errors)
    }

    fun loadGeneratedFeed(size: Int = AppConfig.GENERATED_FEED_SIZE): CatalogLoadResult {
        val base = loadBaseCatalog()
        if (base.items.isEmpty()) return base
        val generated = List(size) { index ->
            val source = base.items[index % base.items.size]
            val repeat = index / base.items.size
            source.copy(
                id = "${source.baseId}__repeat_$repeat",
                social = source.social.copy(likeCount = source.social.likeCount + repeat)
            )
        }
        return CatalogLoadResult(generated, base.validationErrors)
    }
}

data class CatalogLoadResult(
    val items: List<VideoItem>,
    val validationErrors: List<String>
)

object CatalogValidator {
    fun validate(items: List<VideoItem>): List<String> {
        val errors = mutableListOf<String>()
        val ids = mutableSetOf<String>()
        var playableMp4 = 0

        items.forEach { item ->
            if (!ids.add(item.baseId)) errors += "Duplicate base id ${item.baseId}"
            if (item.durationMs <= 0) errors += "${item.baseId}: durationMs must be positive"
            if (item.width <= 0 || item.height <= 0) errors += "${item.baseId}: dimensions must be positive"

            val mp4Variants = item.variants.filter { it.mimeType == "video/mp4" }
            if (mp4Variants.isEmpty() && item.optionalHlsUrl != null) {
                errors += "${item.baseId}: HLS-only entry is unsupported by MVP"
            }
            playableMp4 += mp4Variants.size.coerceAtMost(1)

            item.variants.forEach { variant ->
                if (!variant.url.startsWith("https://") && !variant.url.startsWith("http://")) {
                    errors += "${item.baseId}/${variant.variantId}: invalid URL ${variant.url}"
                }
                if (variant.mimeType != "video/mp4") {
                    errors += "${item.baseId}/${variant.variantId}: MVP requires video/mp4"
                }
                if (variant.width <= 0 || variant.height <= 0 || variant.bitrateKbps <= 0) {
                    errors += "${item.baseId}/${variant.variantId}: variant dimensions and bitrate must be positive"
                }
                if (variant.contentLength != null && variant.contentLength <= 0) {
                    errors += "${item.baseId}/${variant.variantId}: contentLength must be positive when known"
                }
            }
        }

        if (playableMp4 < 100) errors += "Catalog must contain at least 100 playable MP4 entries"
        return errors
    }
}

private fun JSONArray.toVideoItems(): List<VideoItem> {
    return List(length()) { index ->
        getJSONObject(index).toVideoItem()
    }
}

private fun JSONObject.toVideoItem(): VideoItem {
    val baseId = getString("id")
    val variants = getJSONArray("variants")
    return VideoItem(
        id = baseId,
        baseId = baseId,
        title = getString("title"),
        author = getString("author"),
        durationMs = getLong("durationMs"),
        width = getInt("width"),
        height = getInt("height"),
        coverUri = getString("coverUri"),
        tags = optJSONArray("tags")?.let { tags -> List(tags.length()) { tags.getString(it) } } ?: emptyList(),
        variants = List(variants.length()) { variants.getJSONObject(it).toVideoVariant() },
        optionalHlsUrl = if (isNull("optionalHlsUrl")) null else getString("optionalHlsUrl"),
        social = LocalSocialState(likeCount = optInt("likeCount", 0))
    )
}

private fun JSONObject.toVideoVariant(): VideoVariant {
    return VideoVariant(
        variantId = getString("variantId"),
        url = getString("url"),
        mimeType = getString("mimeType"),
        quality = Quality.valueOf(getString("quality")),
        bitrateKbps = getInt("bitrateKbps"),
        width = getInt("width"),
        height = getInt("height"),
        rangeSupport = RangeSupport.valueOf(getString("rangeSupport")),
        fastStart = getBoolean("fastStart"),
        contentLength = if (isNull("contentLength")) null else getLong("contentLength")
    )
}
