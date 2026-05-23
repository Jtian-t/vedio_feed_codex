# Architecture Decisions

## ADR-001 Traditional View Feed Shell

Use `RecyclerView` with `PagerSnapHelper` for the Feed. It gives direct holder attach/detach control, stable IDs, and predictable `TextureView` ownership.

## ADR-002 TextureView MVP Render Target

Use `TextureView` behind `RenderTarget`. It behaves predictably in scrolling pages while leaving room to evaluate `SurfaceView` later.

## ADR-003 Bounded Player Pool

Use current, next, and previous slots. Only current is guaranteed to own active codec resources; nearby slots are warm metadata/cache intent, preventing one player per list item.

## ADR-004 Audio-Master Clock Policy

Use `AudioTrack` playback position as the master clock whenever the source contains a supported audio track. Silent sources fall back to the monotonic clock. Video frame release is scheduled against the active media clock to preserve A/V sync.

## ADR-005 Proxy Cache Policy

Use a local HTTP proxy and Range fetcher for cache-aware playback, but keep direct MP4 playback as the default debug path until device validation is complete.

## ADR-006 HLS Scope

HLS URLs are metadata only for MVP unless an MP4 fallback exists. HLS-only items render unsupported state rather than entering the player.

## ADR-007 Weak-Network Policy

Use rolling OkHttp transfer samples and hysteresis to select lower variants for future prepare/preload requests. Avoid unstable mid-playback switching.
