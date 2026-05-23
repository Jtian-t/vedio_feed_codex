# Mock Catalog Schema

## File

Use `assets/mock/videos.json` for the base catalog. The app can synthesize 10,000 Feed entries from this base file for scroll testing.

## Schema

```json
{
  "version": 1,
  "items": [
    {
      "id": "video_0001",
      "title": "Sample Video 1",
      "author": "mock_author",
      "durationMs": 15000,
      "width": 720,
      "height": 1280,
      "coverUri": "https://example.com/cover.jpg",
      "tags": ["mock", "feed"],
      "variants": [
        {
          "variantId": "540p",
          "url": "https://example.com/video-540p.mp4",
          "mimeType": "video/mp4",
          "quality": "Q540P",
          "bitrateKbps": 900,
          "width": 540,
          "height": 960,
          "rangeSupport": "UNKNOWN",
          "fastStart": true,
          "contentLength": null
        }
      ],
      "optionalHlsUrl": null
    }
  ]
}
```

## Validation Rules

- `id` must be unique in the base catalog.
- At least 100 items must have one playable MP4 variant.
- `durationMs` must be greater than `0`.
- `width`, `height`, and `bitrateKbps` must be positive.
- `mimeType` for MVP playback must be `video/mp4`.
- `url` must be HTTPS where possible.
- HLS-only entries must be marked unsupported or provide MP4 fallback.
- If `contentLength` is known, it must match validated remote length.

## URL Validation Workflow

For each MP4 variant:

1. Send `HEAD` request.
2. Record `Content-Length`, `Accept-Ranges`, `ETag`, and `Last-Modified` if available.
3. Send `Range: bytes=0-1` request.
4. Mark Range support as `YES` only if response is `206` with valid `Content-Range`.
5. Attempt first-byte availability check for startup.
6. Optionally verify fast-start MP4 by confirming metadata is readable near the file beginning during playback smoke test.

## 10k Entry Generation

Generated IDs use:

```text
{baseId}__repeat_{repeatIndex}
```

The generated item keeps variant metadata from the base item but has a unique Feed identity so local like/comment state remains stable.

## Bad Data Handling

| Bad Data | Behavior |
| --- | --- |
| Missing MP4 variant | Mark unsupported; item can render placeholder but not play |
| Invalid URL | Exclude from playable catalog and record validation error |
| Range unknown | Allow playback, but avoid seek/resume claims until observed |
| Non-fast-start MP4 | Allow only after smoke test; record first-frame risk |
| HLS-only | Do not send to MVP player |

## Example Acceptance Checks

- Base catalog has at least 100 playable MP4 variants.
- At least 30 variants validate Range support for cache/resume demonstration.
- At least 3 quality levels exist for weak-network demo across selected sample items.
