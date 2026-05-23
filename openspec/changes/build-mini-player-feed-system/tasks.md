## 1. Project Foundation

- [x] 1.1 Create Android Kotlin project structure with app, data, feed, player, cache, network, perf, and docs ownership boundaries
- [x] 1.2 Configure Kotlin, Coroutines/Flow, raw OkHttp, Android media APIs, and optional LeakCanary without ExoPlayer, IjkPlayer, Retrofit advanced abstractions, or AndroidVideoCache
- [x] 1.3 Add baseline `README.md`, `AI_USAGE.md`, `DECISIONS.md`, and `BUGFIX.md` placeholders with required final sections
- [x] 1.4 Record UI decision ADR explaining traditional View Feed shell and `TextureView` MVP render target
- [x] 1.5 Create core package skeletons and interfaces from `docs/architecture-interfaces.md`
- [x] 1.6 Add default tunable parameters for buffer watermarks, preload size, cache limits, retry, frame lateness, and weak-network thresholds
- [x] 1.7 Apply execution decisions from `docs/execution-decisions.md` for SDK, MVP audio scope, URL strategy, FPS wording, and target device evidence

## 2. Feed Shell and Data

- [x] 2.1 Create mock JSON catalog with at least 100 progressive MP4 entries and optional variant/HLS fallback metadata
- [x] 2.2 Validate catalog entries against `docs/mock-catalog-schema.md`, including URL, mime, duration, dimensions, Range hint, and MP4 variant rules
- [x] 2.3 Implement Feed data loading with stable IDs and support for 10,000 generated metadata entries
- [x] 2.4 Build `RecyclerView` plus `PagerSnapHelper` full-screen vertical Feed with lightweight item binding
- [x] 2.5 Add local like state, local comment placeholder/list UI, and per-item state restoration
- [x] 2.6 Add unsupported-source handling so HLS-only entries do not crash the MVP player
- [x] 2.7 Implement 10k generated ID rule so repeated base videos keep unique Feed identities

## 3. Mini Player Kernel

- [x] 3.1 Define player state machine, public control API, playback events, metrics events, and error model from `docs/architecture-interfaces.md`
- [x] 3.2 Implement `TextureView` render target adapter with `Surface` availability and destruction callbacks
- [x] 3.3 Implement MP4 track extraction with `MediaExtractor` and video decoder setup with `MediaCodec`
- [x] 3.4 Implement off-main-thread decode loop for input queueing, output release, EOS handling, and deterministic release
- [x] 3.5 Implement play, pause, progress updates, tap-to-toggle, long-press speed mode, and end-of-video handling
- [x] 3.6 Implement seek/flush behavior from progress control to nearest supported sync point
- [x] 3.7 Apply required MVP audio scope from `docs/execution-decisions.md` with `AudioTrack` playback for sources that contain audio
- [x] 3.8 Implement media clock abstraction with audio-clock mode as the normal path and monotonic-clock fallback for silent sources
- [x] 3.9 Implement video frame scheduling against media clock with early-frame delay, late-frame immediate render, and severe-late frame drop
- [x] 3.10 Implement clock re-anchoring for pause, resume, seek completion, buffering recovery, and long-press speed changes
- [x] 3.11 Record late-frame and dropped-frame counters for playback diagnostics and performance reports
- [x] 3.12 Implement surface recreation recovery by rebinding or recreating codec from remembered position
- [x] 3.13 Add player state transition metrics while implementing each state transition

## 4. Feed Scheduling

- [x] 4.1 Implement player coordinator so Feed never manipulates `MediaCodec` directly
- [x] 4.2 Implement bounded player slots for current, next, and previous items with explicit ownership rules
- [x] 4.3 Play only the active page and pause or release old pages on page changes
- [x] 4.4 Implement Feed selection and Surface attach/detach sequence from `docs/feed-player-sequence.md`
- [x] 4.5 Prepare predicted next player slot after first-frame or playback-progress trigger without cache preload dependency
- [x] 4.6 Cancel stale slot preparation when swipe direction or active page changes
- [x] 4.7 Release active codec, surface, proxy requests, and jobs on background entry in documented order
- [x] 4.8 Restore current item and position on resume after current holder surface becomes available

## 5. Network, Proxy, and Cache

- [x] 5.1 Implement raw OkHttp client with custom interceptor for transfer timing, byte counts, and bandwidth samples
- [x] 5.2 Implement cache keys from normalized source URL and quality variant metadata
- [x] 5.3 Implement `RangeFetcher` and header validation for `206 Partial Content`, `200 OK` fallback, `416`, content length, and content range
- [x] 5.4 Implement disk cache index schema and segment state machine from `docs/proxy-cache-design.md`
- [x] 5.5 Implement memory cache for hot byte ranges or metadata with bounded size
- [x] 5.6 Add per-entry synchronization for concurrent playback/preload reads and writes
- [x] 5.7 Implement local HTTP proxy request parser and response writer with local URL format
- [x] 5.8 Implement local HTTP proxy that serves player requests from memory, disk, or network
- [x] 5.9 Add repeated-view cache-hit metrics and evidence path for the 90% target
- [x] 5.10 Implement startup, low-water, and high-water buffer thresholds for proxy-backed playback
- [x] 5.11 Prioritize current playback network requests over preload and cancel stale fetches after seek or direction change
- [x] 5.12 Add bounded retry/backoff for timeout and interrupted Range fetches
- [x] 5.13 Detect inconsistent content length or range metadata and mark unhealthy variants without serving corrupted bytes
- [x] 5.14 Add buffering state integration so network stalls pause sample queueing instead of destabilizing decoder state

## 6. Preload and Weak-Network Strategy

- [x] 6.1 Document deterministic preload strategy for current, next one, and fast-swipe next two items
- [x] 6.2 Implement cache preload jobs separately from next player-slot preparation
- [x] 6.3 Implement bounded concurrent preload jobs with cancellation and priority for current playback
- [x] 6.4 Add variant selection from catalog metadata for multiple quality URLs
- [x] 6.5 Implement rolling throughput estimation with downgrade and upgrade hysteresis
- [x] 6.6 Apply weak-network quality changes to future prepare/preload requests rather than unstable mid-playback switching
- [x] 6.7 Add manual or test-network scenario showing quality downgrade and recovery behavior
- [x] 6.8 Add network disturbance test cases for timeout, slow throughput, interrupted transfer, non-Range source, and source skip/fallback

## 7. Observability and Performance

- [x] 7.1 Implement metrics event schema and sink from `docs/perf-validation-plan.md` before final performance testing
- [x] 7.2 Record first-frame timing from prepare start to first rendered frame
- [x] 7.3 Record prepare duration, release latency, playback errors, proxy failures, and cache hit/miss counters
- [x] 7.4 Add memory observation workflow for single-video playback under 200MB
- [ ] 7.5 Add automated or scripted 200-swipe stability test with zero-crash result capture
- [ ] 7.6 Capture Perfetto evidence showing 95% Feed frames at or above 55fps
- [ ] 7.7 Capture Systrace evidence showing scroll and scheduling behavior
- [ ] 7.8 Capture Android Studio Profiler evidence for memory and playback behavior
- [ ] 7.9 Compare first-frame samples against the under-800ms target on a Snapdragon 778G-class device or equivalent
- [x] 7.10 Write performance report summary using the template in `docs/perf-validation-plan.md`

## 8. Delivery Materials

- [x] 8.1 Complete `README.md` with architecture diagram, run guide, feature checklist, and known limitations
- [x] 8.2 Complete `AI_USAGE.md` with at least five adopted or rejected AI suggestions and rationale
- [x] 8.3 Complete `DECISIONS.md` with ADR entries for UI shell, render target, player pool, A/V sync clock policy, proxy cache, HLS scope, and weak-network policy
- [x] 8.4 Complete `BUGFIX.md` with at least three real debugging cases, investigation process, and root cause
- [x] 8.5 Add performance report assets or links for first-frame, FPS, memory, cache hit, and stability evidence
- [x] 8.6 Prepare 5 to 10 minute demo video outline covering Feed, playback controls, cache/preload, weak network, lifecycle, and profiling evidence
- [x] 8.7 Maintain at least 30 meaningful Git commits or document environment constraints if commit history cannot be represented

## 9. Final Acceptance Review

- [x] 9.1 Verify A1 through A5 basic requirements against the implemented app and README checklist
- [x] 9.2 Verify A6 through A11 advanced requirements against implementation, strategy docs, and performance evidence
- [x] 9.3 Verify banned dependencies are absent from Gradle files and playback/cache implementation
- [x] 9.4 Run OpenSpec status and ensure all required artifacts are complete before implementation handoff
