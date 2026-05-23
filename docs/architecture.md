# Architecture

Mini Player Feed is an Android Kotlin short-video feed implemented with low-level media APIs. It intentionally avoids ExoPlayer, IjkPlayer, MediaPlayer, Retrofit, and AndroidVideoCache.

## Main Components

```text
Feed UI
  -> RecyclerView + PagerSnapHelper
  -> VideoFeedAdapter
  -> PlayerSlotCoordinator

Playback
  -> MediaCodecMiniPlayer
      -> video extractor -> video MediaCodec -> TextureRenderTarget
      -> audio extractor -> audio MediaCodec -> ResamplingAudioSink -> AudioTrack
      -> MediaClock reads AudioSink.currentMediaTimeUs()

Preload and cache
  -> PreloadScheduler
  -> MemoryDiskCacheStore
  -> SimpleLocalProxyServer
  -> OkHttpRangeFetcher
```

## Playback Model

`MediaCodecMiniPlayer` owns the playback session. It creates independent extractors and decoders for video and audio.

Video frames keep their original presentation timestamps and are released to a `TextureView` surface. Audio is decoded to 16-bit PCM and sent through `ResamplingAudioSink`.

The player supports:

- prepare, play, pause, seek, release;
- progress and state events;
- full-screen vertical feed playback;
- long-press `5.0x` speed mode;
- audio/video sync using the audio output clock;
- speed-scaled video late/drop thresholds.

## Fast Playback

Fast playback is implemented by processing audio, not by moving the progress bar faster.

```text
decoded PCM at 1x
  -> ResamplingAudioSink
  -> speed-adjusted PCM
  -> AudioTrack normal output
  -> AudioSink.currentMediaTimeUs()
  -> MediaClock
  -> video frame scheduler
```

At `5.0x`, the sink emits roughly one fifth of the decoded PCM frames. This makes audio duration really shorter and keeps the progress position tied to actual audio output.

The current implementation uses resampling-style speed-up. Pitch rises during fast playback, which is deliberate for stability and easy verification. A future time-stretch processor can preserve pitch for lower speeds.

## Audio Master Clock

`AudioSink.currentMediaTimeUs()` maps `AudioTrack.playbackHeadPosition` back to media time:

```text
media time = anchor PTS + played output frames converted to input media duration
```

`MediaClock` uses this audio position whenever audio is configured and playback is not buffering. If audio is unavailable, it falls back to the system elapsed-time clock.

## Video Sync

Video does not implement speed by seeking over content. It follows the audio clock:

```text
if frame PTS is early:
  wait
if frame PTS is close to audio clock:
  render
if frame PTS is too late:
  drop stale frame
```

At high speed, source FPS multiplied by speed may exceed the display refresh rate. In that case, stale frames are dropped, but the media timeline remains continuous.

## Cache and Network

The project includes an OkHttp range fetcher, memory/disk cache primitives, a preload scheduler, and a local proxy implementation. Proxy playback is currently behind `AppConfig.USE_PROXY_PLAYBACK`; direct MP4 playback remains the primary smoke-test path.

## Local Social State

The feed keeps local social state per `VideoItem.id`. `SharedPreferencesLocalSocialStore` persists liked state, like count, and local comments. `VideoFeedAdapter` keeps a small in-memory cache for cheap RecyclerView binding and writes through to the store on every like toggle or comment send.

The right-side social column uses icon-style controls rather than word buttons. The like control renders a black/dark heart when unliked and a red heart when liked, with the count in a separate label. The comment control uses a compact icon and separate count label, and opens the bottom local comment panel.

