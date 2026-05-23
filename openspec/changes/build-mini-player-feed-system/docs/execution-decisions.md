# Execution Decisions

## Status

These decisions remove execution ambiguity before `/opsx:apply`.

## Android SDK

- `minSdk`: 26
- `targetSdk`: latest stable SDK available in the local Android Gradle setup
- Rationale: API 26 gives stable enough media, lifecycle, and profiling support while keeping implementation scope reasonable.

## MVP Audio Scope

- MVP path: audio-video playback is required for sources with audio tracks.
- Audio implementation: decode the audio track with low-level Android media APIs and render PCM through `AudioTrack`.
- A/V sync: audio playback position is the master clock whenever audio is available; video frame release must be scheduled against that audio clock.
- Fallback: sources without audio tracks may play video-only, but this must be reported as source capability rather than the default player mode.

## Mock URL Source Strategy

- Base catalog must contain at least 100 playable progressive MP4 variants.
- Prefer sources that support HTTPS, HTTP Range, stable `Content-Length`, and fast-start MP4 layout.
- At least a subset of entries should include multiple quality variants for weak-network demonstration.
- HLS URLs are optional metadata only unless an MP4 fallback exists.
- URL validation results must be recorded before claiming cache, Range, or weak-network behavior.

## Performance Target Wording

- Engineering target: approach 60fps during Feed scroll.
- Acceptance check: project table requires 95% frames at or above 55fps.
- Reports should show both where possible: measured FPS distribution and pass/fail against `95% >=55fps`.

## Device Evidence

- Primary target: Snapdragon 778G-class Android device.
- If that exact device is unavailable, use a documented equivalent or stronger device and clearly record model, SoC, Android version, and build type.
- Do not rely on environment-limit exceptions for final delivery unless unavoidable and explicitly accepted by the reviewer.
