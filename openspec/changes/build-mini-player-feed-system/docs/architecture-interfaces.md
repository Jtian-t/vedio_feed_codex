# Architecture Interfaces

## Package Ownership

| Package | Owns | Must Not Own |
| --- | --- | --- |
| `data` | Mock catalog parsing, schema validation, generated 10k entries | Player state, cache files |
| `feed` | Full-screen paging UI, item binding, visible page events | `MediaCodec`, network fetch |
| `player` | Extraction, decode, render, clocks, playback state | Feed paging, disk cache eviction |
| `scheduler` | Player slots, prepare/play/release decisions, preload intent | HTTP byte serving |
| `proxy` | Local HTTP endpoint, request parsing, response headers | UI state |
| `cache` | Memory cache, disk index, LRU, segment consistency | Android surface lifecycle |
| `network` | OkHttp client, Range fetch, bandwidth samples | Playback state machine |
| `perf` | Metrics events, trace markers, report summaries | Business decisions |

## Core Data Types

```kotlin
data class VideoItem(
    val id: String,
    val title: String,
    val author: String,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val coverUri: String,
    val variants: List<VideoVariant>,
    val social: LocalSocialState = LocalSocialState()
)

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
    val contentLength: Long? = null
)

enum class RangeSupport { YES, NO, UNKNOWN }
enum class Quality { Q360P, Q540P, Q720P, Q1080P }
```

## Core Interfaces

```kotlin
interface MiniPlayer {
    val states: StateFlow<PlayerState>
    val events: Flow<PlayerEvent>

    suspend fun prepare(source: PlaybackSource, renderTarget: RenderTarget, startPositionMs: Long = 0)
    fun play()
    fun pause()
    suspend fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
    suspend fun rebindRenderTarget(renderTarget: RenderTarget)
    suspend fun release(reason: ReleaseReason)
}

interface RenderTarget {
    val surfaceEvents: Flow<SurfaceEvent>
    fun currentSurface(): Surface?
}

interface PlayerCoordinator {
    val activeState: StateFlow<FeedPlaybackState>
    suspend fun attachPage(page: FeedPageHandle)
    suspend fun onPageSelected(index: Int)
    suspend fun onPageDetached(index: Int)
    suspend fun onAppBackgrounded()
    suspend fun onAppForegrounded()
}

interface PreloadScheduler {
    fun onPlaybackProgress(item: VideoItem, positionMs: Long, durationMs: Long)
    fun onSwipeVelocity(direction: SwipeDirection, velocityPxPerSec: Float)
    fun onActiveItemChanged(index: Int, window: FeedWindow)
    suspend fun cancelStale(reason: CancelReason)
}

interface LocalProxyServer {
    suspend fun start(): ProxyEndpoint
    fun buildLocalUrl(variant: VideoVariant): String
    suspend fun stop()
}

interface CacheStore {
    suspend fun openEntry(key: CacheKey): CacheEntry
    suspend fun read(key: CacheKey, range: LongRange): CacheReadResult
    suspend fun write(key: CacheKey, offset: Long, bytes: ByteArray)
    suspend fun markUnhealthy(key: CacheKey, reason: CacheError)
    suspend fun evictIfNeeded()
}

interface RangeFetcher {
    suspend fun fetch(request: RangeFetchRequest): RangeFetchResult
}

interface MetricsSink {
    fun record(event: MetricEvent)
}
```

## Player State Machine

| From | Event | To | Action |
| --- | --- | --- | --- |
| `Idle` | `prepare()` | `Preparing` | Create extractor, configure codec, bind surface |
| `Preparing` | format selected | `Prepared` | Emit duration and format metadata |
| `Prepared` | first output frame rendered | `FirstFrameRendered` | Record first-frame metric |
| `FirstFrameRendered` | `play()` | `Playing` | Start clock and decode loop |
| `Playing` | `pause()` | `Paused` | Pause clock and audio output |
| `Playing` | proxy low-water | `Buffering` | Stop queueing incomplete samples |
| `Buffering` | proxy high-water | `Playing` | Re-anchor clock and continue decode |
| `Playing` | EOS drained | `Ended` | Emit completion event |
| any active | recoverable error | `Error` | Emit typed error and safe recovery action |
| any | `release()` | `Released` | Idempotently release extractor, codec, audio, surface refs |

Release is idempotent: repeated `release()` calls must be safe and must not throw.

## Threading Model

| Thread/Dispatcher | Work | Emits To |
| --- | --- | --- |
| Main | View binding, gestures, state rendering, surface callbacks | Coordinator commands |
| Decode thread | Extractor reads, codec queue/dequeue, frame scheduling | Player state/events |
| Audio thread or callback | `AudioTrack` write and playback-head sampling | Media clock |
| IO dispatcher | Cache index read/write, file reads/writes | Cache results |
| Proxy accept loop | Local socket accept and request parsing | Proxy response jobs |
| OkHttp dispatcher | Remote Range fetch | Network metrics and cache writes |

Only immutable state snapshots cross thread boundaries. UI never touches `MediaCodec` directly.

## Error Model

| Error Type | Examples | Recovery |
| --- | --- | --- |
| `SourceUnsupported` | HLS-only MVP item, unsupported mime | Use MP4 fallback or skip item |
| `NetworkTimeout` | Timeout, DNS failure, interrupted body | Buffer, retry with backoff, lower preload priority |
| `RangeInvalid` | Bad `Content-Range`, changed length | Mark variant unhealthy, do not serve bytes |
| `CacheCorrupt` | Segment checksum/range mismatch | Delete entry and refetch |
| `DecodeConfigureFailed` | Codec unavailable, bad format | Surface error and skip item |
| `DecodeRuntimeFailed` | Codec exception during queue/dequeue | Release player slot and retry once or skip |
| `SurfaceLost` | Surface destroyed while active | Pause output, rebind or recreate codec |
| `LifecycleRelease` | Background entry | Persist item/position and release resources |

## Tunable Parameters

| Parameter | Initial Value | Notes |
| --- | --- | --- |
| Startup buffer | `512 KiB` or first decodable sample range | Tune per MP4 source quality |
| Low-water buffer | `256 KiB` contiguous ahead | Enter `Buffering` below this |
| High-water buffer | `1 MiB` contiguous ahead | Resume after stall |
| Preload bytes | first `1 MiB` of next item | Enough for metadata and first frames |
| Max preload jobs | `1` normal, `2` fast forward swipe | Current playback always wins |
| Disk cache limit | `512 MiB` | LRU by last access |
| Memory cache limit | `16 MiB` | Hot ranges only |
| Range retry count | `3` | Exponential backoff |
| Initial retry delay | `300 ms` | Cap at `2 s` |
| Late-frame render tolerance | `30 ms` | Render immediately if within tolerance |
| Drop-frame threshold | `80 ms` | Drop severely late video frames |
| Weak-network window | last `5` transfer samples or `10 s` | Whichever is more stable |
| Downgrade threshold | throughput `< 1.3x` selected bitrate | Future items only |
| Upgrade threshold | throughput `> 2.0x` selected bitrate for stable window | Avoid oscillation |

## Implementation Order

1. Create interfaces and data types without implementation.
2. Implement video-only `MiniPlayer` direct MP4 path.
3. Add metrics to player state transitions.
4. Add `PlayerCoordinator` with a single current slot.
5. Expand coordinator to previous/current/next slots.
6. Add proxy/cache behind a feature flag while keeping direct MP4 fallback.
7. Enable preload and weak-network policy after proxy metrics are stable.
