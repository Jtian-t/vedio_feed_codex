## ADDED Requirements

### Requirement: Low-level playback kernel
The system SHALL play MP4 video using Android low-level media APIs and MUST NOT use ExoPlayer, IjkPlayer, Android `MediaPlayer`, or any mature high-level playback wrapper.

#### Scenario: Play MP4 through media codec
- **WHEN** a valid MP4 video item is selected for playback
- **THEN** the system initializes extraction and decoding through low-level media APIs and renders decoded frames to the configured render target

#### Scenario: Reject high-level player dependency
- **WHEN** project dependencies and playback code are reviewed
- **THEN** no high-level mature playback wrapper is present in dependency declarations or playback implementation

### Requirement: Render target integration
The system SHALL render decoded video frames to a `Surface` supplied by `SurfaceView` or `TextureView`.

#### Scenario: Surface becomes available
- **WHEN** the Feed page render surface is created
- **THEN** the player binds the surface before starting video frame rendering

#### Scenario: Surface is destroyed
- **WHEN** the Feed page render surface is destroyed
- **THEN** the player stops rendering to that surface and releases or detaches dependent resources without crashing

### Requirement: Playback controls
The system SHALL support play, pause, seek/progress display, and long-press speed control for the current video.

#### Scenario: Tap toggles pause
- **WHEN** the user taps the currently playing video
- **THEN** playback toggles between playing and paused states

#### Scenario: Long press enables speed mode
- **WHEN** the user long-presses the currently playing video
- **THEN** the player applies the configured fast playback speed until the long press ends

#### Scenario: Progress updates during playback
- **WHEN** a video is playing
- **THEN** the UI receives progress updates suitable for a progress bar

### Requirement: Player state machine
The system SHALL expose playback state through explicit states covering idle, preparing, prepared, first-frame rendered, playing, paused, buffering, ended, error, and released.

#### Scenario: First frame transition is emitted
- **WHEN** the first decoded frame is rendered
- **THEN** the player emits a first-frame rendered state with timing data

#### Scenario: Playback failure is reported
- **WHEN** decoding, rendering, or source loading fails
- **THEN** the player emits an error state with a diagnostic reason and releases unsafe resources

### Requirement: Decode threading and buffer loop
The system SHALL run extraction, codec input queueing, codec output release, end-of-stream handling, and release operations off the main thread.

#### Scenario: Playback is active
- **WHEN** the player is decoding video frames
- **THEN** buffer dequeue, queue, release, and extractor read operations do not block the main UI thread

#### Scenario: Video reaches end
- **WHEN** the extractor reaches end of stream
- **THEN** the player queues EOS into the codec, drains remaining output, and emits an ended state

### Requirement: Seek and flush behavior
The system SHALL support progress-based seeking when the source and extractor support seeking.

#### Scenario: User seeks within video
- **WHEN** the user moves the progress control to a new position
- **THEN** the player flushes codec state, seeks the extractor, and resumes rendering from the nearest valid sync point

### Requirement: Surface rebinding policy
The system SHALL handle render surface destruction and recreation without leaking codec or surface resources.

#### Scenario: Surface is recreated for the active item
- **WHEN** a new render surface becomes available after the previous surface was destroyed
- **THEN** the player either safely rebinds rendering or recreates the codec and resumes from the remembered playback position

### Requirement: Audio strategy
The system SHALL play audio for MP4 sources that contain a supported audio track and SHALL synchronize audio and video by presentation timestamps.

#### Scenario: Source contains audio
- **WHEN** the video source contains a supported audio track
- **THEN** the player decodes audio with low-level media APIs, renders PCM through `AudioTrack`, and keeps video frame release aligned with audio presentation time

#### Scenario: Source has no audio
- **WHEN** the video source does not contain a supported audio track
- **THEN** the player may fall back to video-only playback and report that the source is silent

### Requirement: Media clock abstraction
The system SHALL use a media clock abstraction to drive progress and video frame scheduling.

#### Scenario: Audio playback is used
- **WHEN** playback includes audio output through `AudioTrack`
- **THEN** the media clock uses audio playback position as the authoritative media time whenever a valid audio position is available

#### Scenario: Silent source playback is used
- **WHEN** playback runs without a supported audio track
- **THEN** the media clock advances from a monotonic elapsed-time anchor and the configured playback speed

### Requirement: Video frame scheduling
The system SHALL compare decoded video frame presentation timestamps against the media clock before rendering.

#### Scenario: Decoded video frame is early
- **WHEN** a decoded frame has a presentation timestamp later than the current media clock
- **THEN** the player delays releasing the frame for rendering until the target presentation time

#### Scenario: Decoded video frame is slightly late
- **WHEN** a decoded frame is behind the media clock but within the configured render tolerance
- **THEN** the player renders the frame immediately and records late-frame metrics

#### Scenario: Decoded video frame is too late
- **WHEN** a decoded frame is behind the media clock beyond the configured drop threshold
- **THEN** the player drops the frame, records a dropped-frame metric, and continues draining output

### Requirement: Clock re-anchoring
The system SHALL re-anchor the media clock after pause, resume, seek, buffering recovery, and playback-speed changes.

#### Scenario: Playback resumes from pause
- **WHEN** the user resumes playback after pausing
- **THEN** the media clock restarts from the preserved media position and a new elapsed-time anchor

#### Scenario: Seek completes
- **WHEN** audio and video extractors and decoders finish seeking and flushing
- **THEN** the media clock anchors to the actual resumed media timestamp before frames are rendered

#### Scenario: Playback speed changes
- **WHEN** long-press speed mode starts or ends
- **THEN** the media clock updates its anchor and speed factor so progress and video frame scheduling remain continuous

### Requirement: Lifecycle resource release
The system SHALL release codec and rendering resources when the app enters the background and SHALL support fast recovery when the app returns.

#### Scenario: App enters background
- **WHEN** the host lifecycle receives pause or stop
- **THEN** active codec resources, rendering surfaces, and player jobs are released or paused according to lifecycle policy

#### Scenario: App resumes
- **WHEN** the host lifecycle resumes after background release
- **THEN** the current video can prepare again from the remembered item and position
