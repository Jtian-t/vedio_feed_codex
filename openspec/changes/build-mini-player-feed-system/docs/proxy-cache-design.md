# Proxy Cache Design

## Goal

Provide a local, cache-aware HTTP source for `MediaExtractor` so playback can use Range, preload, resume, and weak-network buffering without exposing decoder code to unstable remote transport.

## Local URL Format

```text
http://127.0.0.1:{port}/video/{cacheKey}?variant={variantId}
```

`cacheKey` is a URL-safe hash of normalized source URL, variant id, mime type, and content length if known.

## Request Flow

```text
MediaExtractor
  -> LocalProxyServer
  -> ProxyRequestParser
  -> CacheStore.read(range)
  -> RangeFetcher.fetch(missing ranges)
  -> CacheStore.write(...)
  -> ProxyResponseWriter
  -> MediaExtractor
```

## Header Behavior

| Input | Remote Result | Proxy Response |
| --- | --- | --- |
| No Range, complete length known | `200 OK` | `200 OK`, `Content-Length`, `Accept-Ranges: bytes` if supported |
| `Range: bytes=a-b` | `206 Partial Content` | `206 Partial Content`, same valid `Content-Range` semantics |
| `Range: bytes=a-` | `206 Partial Content` | `206 Partial Content`, `Content-Range: bytes a-end/total` |
| Range requested, remote returns `200 OK` | `200 OK` | Sequential serving, mark `rangeSupport=NO` |
| Remote length changes | inconsistent | Mark unhealthy; do not serve mixed cached bytes |

The proxy must not invent a `Content-Range` that cannot be proven from source metadata.

## Cache Index Format

```json
{
  "version": 1,
  "cacheKey": "sha256...",
  "sourceUrl": "https://example.com/video.mp4",
  "variantId": "720p",
  "mimeType": "video/mp4",
  "contentLength": 12345678,
  "etag": "\"abc\"",
  "lastModified": "Wed, 01 Jan 2025 00:00:00 GMT",
  "rangeSupport": "YES",
  "segments": [
    { "start": 0, "endInclusive": 1048575, "state": "COMPLETE" }
  ],
  "lastAccessEpochMs": 1770000000000,
  "unhealthyReason": null
}
```

Segment ranges are inclusive and non-overlapping after normalization.

## Cache Entry State Machine

| State | Meaning | Next |
| --- | --- | --- |
| `EMPTY` | No trusted bytes | `FETCHING`, `UNHEALTHY` |
| `FETCHING` | One writer owns missing range | `PARTIAL`, `COMPLETE`, `UNHEALTHY` |
| `PARTIAL` | Some valid ranges exist | `FETCHING`, `COMPLETE`, `EVICTING` |
| `COMPLETE` | Entire content is cached | `EVICTING` |
| `UNHEALTHY` | Source/cache cannot be trusted | `EVICTING` |
| `EVICTING` | Entry is being removed | `EMPTY` |

One cache entry has one write lock. Multiple readers can read completed byte ranges.

## Buffer Watermarks

| Watermark | Default | Action |
| --- | --- | --- |
| Startup | `512 KiB` or first decodable sample | Allow initial extraction |
| Low-water | `256 KiB` contiguous ahead | Emit buffering and stop sample queueing |
| High-water | `1 MiB` contiguous ahead | Resume sample extraction |

For fast-start MP4, the proxy prioritizes bytes from offset `0`. For non-fast-start MP4, URL validation should mark the item risky because metadata may require tail reads.

## Fetch Priority

| Priority | Request |
| --- | --- |
| P0 | Current playback missing bytes |
| P1 | Current seek target |
| P2 | Current item forward buffer |
| P3 | Next item preload |
| P4 | Previous item warm cache |

P0/P1 requests cancel or pause P3/P4 jobs when bandwidth is constrained.

## Failure Handling

| Failure | Action |
| --- | --- |
| Timeout | Retry up to 3 times with backoff |
| Interrupted body | Resume from last completed byte if Range supported |
| `416 Range Not Satisfiable` | Revalidate content length; mark unhealthy if mismatch |
| Missing/invalid `Content-Range` | Treat as non-Range or unhealthy depending on request |
| Length changed across requests | Mark variant unhealthy and delete partial entry |
| Disk write failure | Stop caching, serve network only if safe, emit metric |

## Proxy Lifecycle

1. Start proxy when app enters foreground and first playback is requested.
2. Keep one local server instance per process.
3. Stop accepting new requests on background entry.
4. Let active current playback release first, then close sockets.
5. Flush cache index after each completed segment and before process shutdown when possible.

## Direct Playback Fallback

Direct remote MP4 playback remains available behind a debug flag for isolating whether a bug belongs to player decode or proxy/cache transport.
