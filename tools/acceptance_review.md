# Acceptance Review

## A1-A5 Basic Requirements

- A1 Feed: implemented with full-screen vertical `RecyclerView` and `PagerSnapHelper`.
- A2 Controls: tap pause/play, progress seek, and long-press speed command wired.
- A3 Decode/render: implemented with `MediaExtractor`, `MediaCodec`, and `TextureView`; high-level players absent.
- A4 Local social UI: local like and comment placeholder state implemented per generated item id.
- A5 Mock data: `android-app/app/src/main/assets/mock/videos.json` contains 100 base MP4 entries and 10,000 generated identities are supported.

## A6-A11 Advanced Requirements

- A6 Preload: deterministic current/next/fast-forward policy documented and implemented as cache intents.
- A7 Cache: memory and disk cache primitives with LRU and segment index implemented.
- A8 Watch while downloading: local proxy and Range fetcher implemented behind feature flag.
- A9 10k smoothness: lightweight binding and stable IDs implemented; Perfetto/Systrace capture pending.
- A10 Weak network: bandwidth estimator and future variant selection implemented.
- A11 Lifecycle: background release and foreground restore hooks implemented.

## Required Evidence Still Device-Bound

- First-frame samples on target device.
- Perfetto and Systrace frame-rate traces.
- Android Studio Profiler memory capture.
- 200-swipe zero-crash run.
- Repeated-view cache hit-rate metrics.
