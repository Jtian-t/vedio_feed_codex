package com.example.minifeed.data

data class VideoItem(
    val id: String,
    val baseId: String,
    val title: String,
    val author: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val coverUri: String,
    val tags: List<String>,
    val variants: List<VideoVariant>,
    val optionalHlsUrl: String?,
    val social: LocalSocialState = LocalSocialState()
) {
    val playableVariant: VideoVariant?
        get() = variants.firstOrNull { it.mimeType == "video/mp4" }
}

data class VideoVariant(
    val variantId: String,
    val url: String,
    val mimeType: String,
    val quality: Quality,
    val bitrateKbps: Int,
    val width: Int,
    val height: Int,
    val rangeSupport: RangeSupport,
    val fastStart: Boolean,
    val contentLength: Long?
)

data class LocalSocialState(
    val liked: Boolean = false,
    val likeCount: Int = 0,
    val comments: List<String> = emptyList()
)

enum class RangeSupport { YES, NO, UNKNOWN }

enum class Quality(val rank: Int) {
    Q360P(0),
    Q540P(1),
    Q720P(2),
    Q1080P(3)
}
