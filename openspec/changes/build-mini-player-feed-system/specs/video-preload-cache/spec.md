## ADDED Requirements

### Requirement: Predictive preload
The system SHALL preload upcoming videos according to a bounded and documented strategy.

#### Scenario: Current video starts successfully
- **WHEN** the current video reaches the preload trigger condition
- **THEN** the scheduler starts preloading the next eligible video item

#### Scenario: User swipes quickly
- **WHEN** the scheduler detects repeated fast forward swipes
- **THEN** the scheduler can expand the preload window within the configured maximum

#### Scenario: Direction changes
- **WHEN** the user reverses swipe direction
- **THEN** stale preload jobs outside the new predicted window are cancelled

### Requirement: Three-level cache
The system SHALL provide memory, disk, and network cache resolution with LRU eviction for cached video data.

#### Scenario: Cached segment exists in memory
- **WHEN** playback or preload requests a byte range already available in memory
- **THEN** the cache serves that data without disk or network access

#### Scenario: Cached segment exists on disk
- **WHEN** playback or preload requests a byte range not in memory but available on disk
- **THEN** the cache serves that data from disk and can promote hot data into memory

#### Scenario: Cache exceeds configured limit
- **WHEN** disk cache size exceeds the configured limit
- **THEN** the cache evicts least-recently-used entries until it is within limit

### Requirement: Local proxy playback
The system SHALL support cache-aware playback through a local HTTP proxy that handles Range requests.

#### Scenario: Player opens local proxy URL
- **WHEN** the player requests a local proxy URL for a video
- **THEN** the proxy maps the request to the original video source and serves bytes from cache or network

#### Scenario: Range request is received
- **WHEN** the player or extractor requests a byte range
- **THEN** the proxy returns the requested range with appropriate partial-content behavior when supported by the source

#### Scenario: Remote source ignores Range
- **WHEN** the remote source responds with `200 OK` instead of `206 Partial Content`
- **THEN** the proxy falls back to sequential serving and records that the source does not support resumable range caching

#### Scenario: Proxy serves metadata headers
- **WHEN** the proxy responds to the player or extractor
- **THEN** the response preserves valid content length, content range, and accept-ranges behavior based on known source capabilities

### Requirement: Playback buffer watermarks
The system SHALL use startup, low-water, and high-water buffer thresholds to shield extraction and decoding from network jitter.

#### Scenario: Initial playback starts
- **WHEN** the player prepares a remote MP4 source through the proxy
- **THEN** playback starts only after required MP4 metadata and the configured startup buffer are available

#### Scenario: Buffer drops below low-water mark
- **WHEN** the local proxy cannot provide enough contiguous bytes ahead of the current playback position
- **THEN** the player enters buffering state and stops queueing incomplete compressed samples into the decoder

#### Scenario: Buffer reaches high-water mark
- **WHEN** enough contiguous bytes are available after a buffering stall
- **THEN** the player resumes sample extraction and decoding from the buffered position

### Requirement: Network disturbance handling
The system SHALL handle timeout, slow throughput, interrupted transfer, inconsistent Range response, and unreachable source without corrupting playback state.

#### Scenario: Network times out during current playback
- **WHEN** the current playback fetch times out before enough data is buffered
- **THEN** the proxy retries with bounded backoff and the player remains in buffering rather than decoding invalid data

#### Scenario: Source returns inconsistent range metadata
- **WHEN** the source response conflicts with known content length or cached byte ranges
- **THEN** the proxy marks the source or variant unhealthy and prevents corrupted bytes from being served to the extractor

#### Scenario: Current playback competes with preload
- **WHEN** current playback and next-item preload need network at the same time
- **THEN** current playback requests take priority and preload is paused, cancelled, or slowed

### Requirement: Cache consistency
The system SHALL use stable cache keys, segment metadata, and per-entry synchronization to avoid corrupting partially downloaded media.

#### Scenario: Concurrent playback and preload request same video
- **WHEN** playback and preload request overlapping byte ranges for the same cache entry
- **THEN** the cache coordinates reads and writes through a single entry state without corrupting partial data

#### Scenario: App restarts with partial cache
- **WHEN** the app starts after a previous partial download
- **THEN** the cache index identifies valid completed byte ranges before serving or resuming the entry

### Requirement: Resume-capable network fetch
The system SHALL use OkHttp directly for network access and SHALL support resumable byte-range fetching when the remote source supports it.

#### Scenario: Network fetch is interrupted
- **WHEN** a partially downloaded cache entry is interrupted
- **THEN** a later request resumes from the known cached byte boundary when the source supports Range

#### Scenario: Custom interceptor observes transfer
- **WHEN** OkHttp fetches video bytes
- **THEN** a custom interceptor records transfer metrics needed for bandwidth and cache diagnostics

#### Scenario: Seek target changes required range
- **WHEN** the user seeks to a position outside the currently buffered range
- **THEN** stale forward prefetch is cancelled or deprioritized and the proxy fetches the byte range needed for the seek target first

### Requirement: Weak-network variant selection
The system SHALL detect degraded bandwidth and select lower-quality variants for future playback or preload when variant metadata is available.

#### Scenario: Bandwidth drops below threshold
- **WHEN** observed throughput stays below the configured threshold
- **THEN** the scheduler selects a lower-quality variant for subsequent prepare or preload requests

#### Scenario: Bandwidth recovers
- **WHEN** observed throughput recovers above the configured threshold for a stable interval
- **THEN** the scheduler can select a higher-quality variant for subsequent prepare or preload requests
