## Why

The project needs a Douyin-style short-video feed that proves media fundamentals rather than relying on mature player wrappers such as ExoPlayer or IjkPlayer. Building a small playback kernel and Feed scheduler creates a controllable foundation for first-frame latency, smooth vertical paging, preload strategy, cache hit rate, weak-network behavior, and principle-based defense.

## What Changes

- Introduce a self-built mini player kernel based on Android low-level media APIs, using `MediaCodec` with `SurfaceView` or `TextureView` rendering.
- Add a full-screen vertical Feed experience with smooth single-page snapping, local play/pause/progress interactions, long-press speed control, like/comment UI state, and mock video data.
- Add Feed scheduling around a small active playback window, keeping only current/previous/next playback resources hot enough for fast switching.
- Add preload strategy, three-level cache, and local proxy playback so videos can be fetched through HTTP Range requests and played while downloading.
- Add lifecycle resource governance so codecs, surfaces, network jobs, and cache tasks release on background entry and resume quickly on return.
- Add performance and delivery documentation requirements for architecture, decisions, AI usage, bug investigations, and profiling evidence.

## Capabilities

### New Capabilities

- `mini-player-kernel`: Low-level video playback kernel covering decoding, rendering, playback controls, progress, speed changes, errors, and lifecycle.
- `short-video-feed`: Full-screen vertical video Feed covering paging, list data, visible item scheduling, local social UI state, and 10k-item smoothness.
- `video-preload-cache`: Preload, local proxy, memory/disk/network cache, LRU eviction, Range requests, resume support, and weak-network adaptation.
- `performance-delivery`: Profiling, acceptance evidence, architecture docs, AI usage notes, bugfix records, and demo deliverables.

### Modified Capabilities

- None.

## Impact

- Android app code will be organized around UI, Feed scheduling, playback kernel, cache/proxy, data, performance instrumentation, and lifecycle boundaries.
- Dependencies should stay lightweight: Kotlin, Coroutines/Flow, OkHttp used directly with custom interceptors, Android media APIs, and optional LeakCanary for diagnostics.
- Mature high-level playback libraries such as ExoPlayer, IjkPlayer, and Retrofit advanced abstractions are intentionally excluded.
- Test and validation workflows will include automated sliding stability checks, first-frame measurement, memory profiling, cache-hit measurement, and Perfetto/Systrace evidence.
