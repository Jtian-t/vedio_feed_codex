# Mini Player Feed

Android Kotlin short-video Feed built around low-level media APIs. The player uses `MediaExtractor`, `MediaCodec`, `AudioTrack`, and `TextureView`; ExoPlayer, IjkPlayer, `MediaPlayer`, Retrofit, and AndroidVideoCache are intentionally absent.

The current playback path supports smooth long-press `5.0x` playback by accelerating decoded PCM audio, exposing the real audio output clock as the master media clock, and synchronizing video frames against that clock.

## Architecture

```text
Feed RecyclerView
  -> PlayerSlotCoordinator
  -> MediaCodecMiniPlayer
      -> video: MediaExtractor -> MediaCodec -> TextureRenderTarget
      -> audio: MediaExtractor -> MediaCodec -> ResamplingAudioSink -> AudioTrack
      -> MediaClock reads AudioSink.currentMediaTimeUs()

PreloadScheduler
  -> MemoryDiskCacheStore
  -> SimpleLocalProxyServer
  -> OkHttpRangeFetcher
```

## Run

Open this folder in Android Studio:

```text
D:\claude_program\Feed_vedio_codex\android-app
```

From this repository root, build with:

```powershell
& 'C:\Users\jin\.gradle\wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj\gradle-9.4.1\bin\gradle.bat' -p android-app :app:assembleDebug
```

## Feature Checklist

- [x] Full-screen vertical `RecyclerView` with `PagerSnapHelper`
- [x] 10,000 generated Feed identities from 100 MP4 catalog entries
- [x] Local like toggle with count updates and comment panel with input/list state
- [x] Unsupported HLS-only handling
- [x] Low-level audio-video `MediaCodec` playback with `AudioTrack` audio output
- [x] Software PCM resampling for real speed-up playback up to `5.0x`
- [x] Audio-output media clock as the master clock for audio/video sync
- [x] Video frame scheduling against the audio clock with speed-scaled late/drop thresholds
- [x] `TextureView` render target abstraction
- [x] Play, pause, seek, progress, and long-press speed control
- [x] Current/next/previous player-slot coordinator
- [x] Raw OkHttp Range fetcher with transfer metrics
- [x] Memory/disk cache primitives with segment index
- [x] Local proxy implementation behind feature flag
- [x] Metrics events for first frame, cache, network, release, and frame drops
- [ ] Physical-device Perfetto, Systrace, Profiler, and 200-swipe evidence

## Playback Design

Fast playback is implemented by processing audio, not by moving the progress bar faster or seeking over content. Audio is decoded to PCM, PCM is resampled according to speed, and the processed PCM is written to `AudioTrack`. The audio sink maps `AudioTrack.playbackHeadPosition` back to media time, and video frames are rendered or dropped based on that audio clock.

At high speeds such as `5.0x`, the media timeline remains continuous. The renderer may drop stale frames that the display cannot physically show, but it does not jump over future sections of the video.

See [docs/architecture.md](docs/architecture.md) for the component-level design.

## Known Limitations

- Audio is required for MP4 sources with supported audio tracks; silent-source fallback is allowed only when the source has no supported audio track.
- Proxy playback is present but disabled by default through `AppConfig.USE_PROXY_PLAYBACK` while direct MP4 playback remains the smoke-test path.
- Final performance evidence requires an Android device or emulator session and cannot be proven by a desktop-only build.
- `5.0x` audio uses resampling-style speed-up, so pitch rises like fast-forward playback. A future time-stretch processor can preserve pitch for lower speeds.

## Manual Smoke Test

- Launch the app and wait for the first page to leave `Loading`.
- Tap the video once to pause, and tap once again to resume.
- Long press the video; speed mode uses `5.0x` while pressed.
- Drag the progress bar and release; seek is performed on release to avoid repeated decoder flushes.
- Press Home or switch apps, wait a few seconds, then return from Recents. The current item should prepare again from the remembered position.
- After a cold relaunch, wait until the first page is attached before fast swiping through multiple pages.


