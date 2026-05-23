# Performance Validation Plan

## Evidence Directory

Use:

```text
reports/
  perfetto/
  systrace/
  profiler/
  metrics/
  screenshots/
```

## Required Tools

All three are required by the project:

- Android Studio Profiler
- Perfetto
- Systrace

LeakCanary is optional for leak investigation.

## Metrics Events

```kotlin
sealed interface MetricEvent {
    data class PrepareStarted(val itemId: String, val source: String, val timeMs: Long) : MetricEvent
    data class FirstFrameRendered(val itemId: String, val elapsedMs: Long) : MetricEvent
    data class CacheRead(val key: String, val bytes: Long, val source: CacheLayer) : MetricEvent
    data class NetworkFetch(val url: String, val range: LongRange?, val bytes: Long, val durationMs: Long) : MetricEvent
    data class PlaybackError(val itemId: String, val type: String, val recovery: String) : MetricEvent
    data class FrameDrop(val itemId: String, val lateByMs: Long) : MetricEvent
}
```

## Acceptance Checks

| Requirement | Evidence |
| --- | --- |
| First frame `<800ms` | Metrics JSON plus screen recording on target device |
| 95% frames `>=55fps` | Perfetto and Systrace scroll trace |
| Memory `<200MB` | Android Studio Profiler capture |
| 200 swipes, 0 crash | Test log and video/screenshot |
| Cache hit `>=90%` repeated viewing | Metrics summary from cache events |
| Weak-network downgrade | Network simulation notes plus metrics |

## Collection Steps

1. Install release or profileable build on Snapdragon 778G-class device or documented equivalent.
2. Clear app data and cache.
3. Run cold-start first-frame scenario for selected sample videos.
4. Run repeated-view scenario after cache warming.
5. Run 200 automated vertical swipes.
6. Capture Perfetto trace for Feed scroll.
7. Capture Systrace for Feed scroll or decode-related scheduling.
8. Capture Android Studio Profiler memory session during single-video playback.
9. Export metrics JSON and summarize pass/fail.

## Report Summary Template

```markdown
## Device
- Model:
- Android version:
- SoC:
- Build variant:

## Results
- First-frame samples:
- FPS trace:
- Memory peak:
- Cache hit rate:
- Swipe stability:
- Weak-network behavior:

## Artifacts
- Perfetto:
- Systrace:
- Profiler:
- Metrics JSON:
```
