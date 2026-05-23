## ADDED Requirements

### Requirement: Full-screen vertical Feed
The system SHALL present videos as a full-screen vertical feed with one primary video page active at a time.

#### Scenario: User swipes to next video
- **WHEN** the user swipes upward from the current full-screen video
- **THEN** the Feed snaps to the next video page and makes it the active playback item

#### Scenario: User swipes to previous video
- **WHEN** the user swipes downward from the current full-screen video
- **THEN** the Feed snaps to the previous video page and makes it the active playback item

### Requirement: Feed playback scheduling
The system SHALL coordinate playback through a bounded active window containing the current item and nearby predicted items.

#### Scenario: Current page changes
- **WHEN** the active Feed page changes
- **THEN** the scheduler plays the new current item, pauses or releases the old item, and prepares eligible nearby items

#### Scenario: Large list is loaded
- **WHEN** the Feed contains 10,000 video metadata entries
- **THEN** the system keeps UI binding lightweight and does not create one player per list item

### Requirement: Mock video catalog
The system SHALL provide a mock JSON catalog containing at least 100 video entries with playable progressive MP4 URLs for MVP and MAY include HLS URLs as optional extension data.

#### Scenario: App starts with mock data
- **WHEN** the app launches without a backend
- **THEN** the Feed loads video entries from the bundled or locally available mock JSON catalog

#### Scenario: Catalog contains variant metadata
- **WHEN** a video entry supports multiple quality variants
- **THEN** the catalog exposes enough metadata for the scheduler to choose a variant

#### Scenario: HLS URL appears in catalog
- **WHEN** a catalog entry contains an HLS URL before HLS support is implemented
- **THEN** the app marks it as unsupported or uses its MP4 fallback instead of attempting unstable playback

#### Scenario: Catalog entry is parsed
- **WHEN** the app parses a mock video entry
- **THEN** the entry includes stable id, title or author metadata, duration, width, height, mime type, estimated bitrate, cover URL or asset, Range-support hint, and at least one playable MP4 variant URL

#### Scenario: Large Feed is generated from mock data
- **WHEN** the Feed needs 10,000 entries for scroll testing
- **THEN** the app can repeat or synthesize entries from the base catalog while preserving stable item identities

### Requirement: Local social UI state
The system SHALL provide local like and comment UI interactions without requiring backend persistence.

#### Scenario: User likes a video
- **WHEN** the user taps the like control on a Feed item
- **THEN** the UI updates the local liked state and count display for that item

#### Scenario: User opens comments
- **WHEN** the user taps the comment control
- **THEN** the app displays a local comment UI placeholder or local comment list for that item

### Requirement: Smooth Feed operation
The system SHALL be designed to maintain smooth vertical scrolling under the acceptance target for frame rate.

#### Scenario: Automated repeated swiping
- **WHEN** an automated test performs 200 vertical swipe transitions
- **THEN** the Feed completes the transitions without crashing

#### Scenario: Performance trace is captured
- **WHEN** a Perfetto or Systrace session records Feed scrolling
- **THEN** the trace can be used to evaluate whether the Feed meets the target frame-rate acceptance criteria
