## Context

The project is an Android short-video Feed with a deliberate constraint: high-level player wrappers are forbidden. The implementation must demonstrate direct control over decoding, rendering, preloading, cache, scheduling, and lifecycle using Kotlin, Coroutines/Flow, OkHttp, and Android media APIs.

The current repository only contains requirements and OpenSpec structure, so this design establishes the first application architecture. The grading emphasis is split between working behavior, architecture quality, performance proof, and principle defense. That makes observability and explainable decisions as important as feature coverage.

Execution-level details are split into focused appendices:

- `docs/architecture-interfaces.md`: package ownership, core interfaces, state machine, threading, error model, tunable parameters.
- `docs/proxy-cache-design.md`: local proxy protocol, Range/header behavior, cache index, segment state, buffer watermarks, failures.
- `docs/mock-catalog-schema.md`: mock JSON schema, URL validation, 10k generation, bad data handling.
- `docs/feed-player-sequence.md`: Feed selection, `TextureView` lifecycle, player slots, background/resume flows.
- `docs/perf-validation-plan.md`: required tools, metrics events, evidence directory, acceptance collection steps.
- `docs/execution-decisions.md`: SDK, MVP audio scope, mock URL source strategy, FPS wording, target device evidence.

## Goals / Non-Goals

**Goals:**

- Build a small playback kernel that uses `MediaExtractor`, `MediaCodec`, and `SurfaceView` or `TextureView` instead of ExoPlayer/IjkPlayer.
- Provide a Douyin-style full-screen vertical Feed where only a tiny active playback window is hot at any time.
- Keep first-frame startup under the target by preparing the current and predicted next video early.
- Implement explainable preload, local proxy, Range-based cache, and weak-network fallback behavior.
- Produce architecture, decision, AI usage, bugfix, and profiling artifacts that support final delivery and answer defense.

**Non-Goals:**

- Full commercial-grade HLS/DASH support is not required for the initial kernel.
- Backend services, account systems, remote like/comment persistence, and recommendation algorithms are out of scope.
- DRM, subtitles, live streaming, downloads for offline viewing, and background audio playback are out of scope.
- ExoPlayer, IjkPlayer, AndroidVideoCache, Retrofit advanced abstractions, and other mature playback/cache wrappers are not allowed.

## Decisions

### Use Traditional View Feed Shell

Use a traditional Android View shell with `RecyclerView` plus `PagerSnapHelper` for MVP. This gives tighter control over `SurfaceView`/`TextureView` attachment, item recycling, stable IDs, and performance tracing than a Compose-first implementation. `ViewPager2` remains a fallback only if implementation proves `RecyclerView` paging less stable.

Alternatives considered:

- Compose UI: cleaner state expression, but media surface embedding and recycling introduces extra lifecycle complexity.
- Single custom `ViewGroup`: maximum control, but too much infrastructure before validating playback and cache.

### Prefer `TextureView` for MVP, Keep Surface Abstraction

Start with `TextureView` because it behaves predictably inside scrollable full-screen pages and supports transform operations. Hide it behind a render target abstraction so `SurfaceView` can be tested later for lower composition overhead.

Alternatives considered:

- `SurfaceView` first: better direct rendering path, but harder to coordinate with list transitions and z-order.
- OpenGL renderer: powerful but unnecessary before the core `MediaCodec` pipeline is stable.

### Split Architecture by Ownership

Use modules or packages with clear ownership:

- `data`: mock JSON parsing, video metadata, variant URLs.
- `feed`: visible window tracking, page selection, social UI state, scheduling signals.
- `player`: playback state machine, extractor, codec, renderer, progress clock.
- `cache`: local proxy server, Range fetcher, memory cache, disk cache, LRU index.
- `network`: raw OkHttp client, custom interceptors, bandwidth observation.
- `perf`: metrics collection, first-frame markers, cache-hit counters, trace helpers.

The important boundary is that Feed scheduling never manipulates `MediaCodec` directly; it asks a player coordinator to prepare, play, pause, stop, or release.

### Use a Small Player Pool

Maintain at most three actively managed player slots: current, next, and previous. Current owns playback focus; next can be prepared or partially buffered; previous can be paused briefly for quick reverse swipes, then released.

Alternatives considered:

- One player only: simpler, but hurts first-frame latency on fast swipes.
- One player per visible/recycled item: risks codec exhaustion, memory spikes, and hard-to-debug lifecycle leaks.

### Model Playback as a State Machine

Represent playback with explicit states: `Idle`, `Preparing`, `Prepared`, `FirstFrameRendered`, `Playing`, `Paused`, `Buffering`, `Ended`, `Error`, and `Released`. State transitions emit Flow updates to the UI and metrics layer.

This makes lifecycle release, error recovery, and answer defense much cleaner than implicit boolean flags.

Codec work should run off the main thread. The player owns decode coroutines or handler threads for extractor reads, input-buffer queueing, output-buffer release, EOS handling, seek flush, and release. UI state updates flow back to the main thread through immutable playback snapshots. Audio is part of the MVP requirement for sources that contain audio tracks: audio rendering must use a separate `AudioTrack` path, and video frame release must synchronize against audio presentation time.

Surface lifecycle is treated as a first-class state transition. If the surface is destroyed while playback is active, the player pauses output, releases the old surface binding, and either rebinds after a new surface is available or fully recreates the codec if the device does not support safe rebinding.

### Use an Audio-Clock A/V Sync Strategy

Use the audio playback clock as the master clock whenever a playable audio track exists, mirroring the principle used by mature players: audio output advances at hardware pace, while video rendering is scheduled to match media presentation time. The player keeps a `MediaClock` abstraction so silent sources can fall back to a monotonic system clock, while normal MP4 playback uses `AudioTrack` position as the authoritative media time.

The clock model stores:

- `anchorMediaTimeUs`: media timestamp at the most recent start, seek, or speed change.
- `anchorElapsedRealtimeNs`: monotonic time when that anchor was established.
- `playbackSpeed`: current speed factor for long-press speed mode.
- `audioTrackPositionUs`: derived from `AudioTrack` playback head position when audio is active.

Video output scheduling compares each decoded frame `presentationTimeUs` with the current media clock:

- If the video frame is early, delay release until its presentation time.
- If the frame is slightly late, render immediately to preserve continuity.
- If the frame is far behind the clock, drop the frame and continue draining until video catches up.
- If buffering or seek invalidates the anchor, pause scheduling, flush decoders, and establish a new clock anchor before rendering.

Use conservative thresholds to keep behavior explainable: render immediately for small lateness, drop frames only when lateness exceeds a larger threshold, and record late/drop counters for profiling. The exact threshold values can be tuned during testing, but the policy must be documented in `DECISIONS.md`.

For speed changes, audio playback uses `AudioTrack` playback parameters when supported, while video compares frame timestamps against the speed-adjusted media clock. On pause, preserve the current media time and stop advancing the clock. On resume, create a new elapsed-time anchor from the preserved media time. On seek, flush audio and video decoders, seek extractors to the nearest sync point, clear queued buffers, and re-anchor the media clock to the actual resumed timestamp.

### Use Local HTTP Proxy for Cache-Aware Playback

The player consumes a local URL exposed by an in-app proxy. The proxy maps local requests to cache lookups and OkHttp Range network fetches. Disk writes are append/resume capable, and response headers preserve content length/range information when possible.

The proxy must explicitly handle `206 Partial Content`, `200 OK` fallback, `Content-Length`, `Content-Range`, and `Accept-Ranges`. Cache keys are derived from normalized source URL plus quality variant. Disk cache writes use a segment/index file so partially available byte ranges can be served safely while network fetch continues. Concurrent readers and writers coordinate through per-entry locks to avoid corrupting partial files.

Mock data is not just a URL list. Each item should include stable identity, dimensions, duration, mime type, estimated bitrate, known Range support, poster/cover, and quality variants. For MVP, every playable item must provide at least one progressive MP4 variant with a fast-start layout when possible, meaning the MP4 metadata is available near the beginning of the file. HLS URLs may be present for future extension, but they need an MP4 fallback or unsupported marker.

The proxy/cache layer protects `MediaExtractor` from network jitter by turning an unstable remote stream into a local, sequentially readable source. The extractor reads from the local proxy; the proxy maintains a playback buffer ahead of the current read position and fetches missing byte ranges asynchronously. Playback starts only after a minimum startup buffer is available or the MP4 header plus first media bytes are confirmed readable. If the buffer drains below a low-water mark, the player enters `Buffering` instead of feeding invalid or partial samples into the decoder. Playback resumes when the high-water mark is reached.

Network transfer policy:

- Current playback bytes have highest priority.
- Seek target ranges cancel or deprioritize stale forward prefetches.
- Next-item preload is lower priority than current playback and can be paused under poor bandwidth.
- Failed Range fetches retry with bounded backoff and resume from the last verified byte.
- If the remote source does not support Range, the proxy falls back to sequential download and disables precise seek/resume for that source.
- If a source repeatedly stalls or returns inconsistent length/range headers, mark it unhealthy and skip to fallback variant or next item.

Alternatives considered:

- Feed `MediaExtractor` directly with remote URLs: simpler but gives little cache control and makes Range/resume behavior less demonstrable.
- Pre-download full files: good cache hit rate but poor startup and storage behavior.

### Keep Preload Strategy Deterministic

Use a strategy that is simple enough to measure and explain:

- Always prioritize current video first.
- When current first frame appears or playback reaches a threshold, preload next one item.
- If swipe velocity is high or repeated forward swipes are detected, preload next two items.
- Cancel stale preload jobs when the user changes direction or leaves the screen.

This can later be upgraded to score-based ranking without changing the scheduler interface.

### Simulate Multi-Bitrate Weak-Network Fallback

Represent each video item as one or more variants. Bandwidth estimation comes from OkHttp transfer samples. If effective throughput drops below a configured threshold, the scheduler selects a lower variant for future prepare/preload requests. Already-playing sessions can either continue or restart at the nearest progress point depending on kernel readiness.

For MVP stability, weak-network switching affects future prepare/preload decisions rather than attempting seamless mid-stream switching. Use a rolling throughput window and hysteresis so one short dip does not immediately downgrade quality and one short recovery does not immediately upgrade it.

Weak-network handling should distinguish transport health from decoder health. Network stalls cause the player to stop queueing new compressed samples and enter `Buffering`; they must not force codec teardown unless the source is abandoned. Decoder errors, malformed samples, and unsupported formats are reported separately so the app can skip the item or surface an error state without confusing them with bandwidth drops.

### Instrument First-Class Metrics

First-frame time, prepare duration, dropped/late UI frames, memory peak, cache hit/miss, bytes served from cache, proxy failures, and release latency must be recorded in code-level metrics and summarized in delivery docs.

Acceptance targets are treated as hard delivery checks: first frame under 800ms on a Snapdragon 778G-class device, 95% of Feed frames at or above 55fps during trace review, single-video playback memory peak under 200MB, 200 automated swipes with zero crashes, and repeated-view cache hit rate at or above 90%.

Android Studio Profiler, Perfetto, and Systrace are all required evidence sources, not alternatives.

## Risks / Trade-offs

- `MediaCodec` device differences → Keep codec setup conservative, release resources deterministically, and document tested devices.
- `TextureView` adds composition overhead → Abstract render target and measure; switch hot path to `SurfaceView` if traces show composition cost.
- Remote MP4 Range support is inconsistent → Validate mock URLs, include fallback URLs, and record unsupported sources as data-quality failures.
- Local proxy complexity can destabilize playback → Build it after direct MP4 playback works, then route playback through proxy behind a feature flag.
- HLS support may expand scope too far → Treat MP4 as required MVP and HLS as an optional extension if time allows.
- Aggressive preload can waste network and memory → Bound concurrent jobs, cancel stale jobs, and keep player pool fixed.
- 10k Feed smoothness can be masked by mock data → Use stable item IDs, lightweight bind logic, and trace with the final data size.

## Migration Plan

1. Scaffold the Android app structure and documentation files.
2. Implement mock video data and a non-playing vertical Feed shell.
3. Add direct MP4 playback through the mini player kernel.
4. Introduce player pool and Feed scheduling.
5. Add local proxy, Range fetch, cache index, and preload jobs.
6. Add lifecycle release/resume and weak-network variant selection.
7. Run profiling and fill delivery evidence.

Rollback is simple during development: direct playback remains usable before enabling the proxy/cache path, and each advanced feature can be guarded by configuration while debugging.

## Open Questions

- Are training-provided video URLs guaranteed to support HTTP Range?
- Is HLS required for final acceptance, or is 100+ MP4 URLs sufficient if the kernel constraints are met?
