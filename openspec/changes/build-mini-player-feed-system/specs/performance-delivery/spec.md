## ADDED Requirements

### Requirement: First-frame measurement
The system SHALL measure first-frame time for video startup and SHALL provide evidence that the target device class reaches first frame in less than 800ms for accepted test cases.

#### Scenario: Video preparation begins
- **WHEN** preparation starts for the current video
- **THEN** the system records a start timestamp for first-frame measurement

#### Scenario: First frame is rendered
- **WHEN** the first frame reaches the render target
- **THEN** the system records elapsed first-frame time and associates it with the video item and source path

#### Scenario: First-frame acceptance is reviewed
- **WHEN** first-frame evidence is reviewed on a Snapdragon 778G-class device or equivalent
- **THEN** accepted startup samples meet the less-than-800ms target

### Requirement: Memory and cache metrics
The system SHALL collect metrics for memory peak, cache hits, cache misses, and bytes served by cache versus network, and SHALL provide evidence for memory under 200MB during single-video playback and repeated-view cache hit rate at or above 90%.

#### Scenario: Repeated viewing session
- **WHEN** the user watches videos that were previously cached
- **THEN** the system records cache-hit data demonstrating at least 90% cache hit rate for the repeated-view scenario

#### Scenario: Single video playback session
- **WHEN** a video plays from start through normal viewing
- **THEN** memory observations demonstrate a playback memory peak below 200MB for the accepted test case

### Requirement: Profiling evidence
The project SHALL include performance evidence from Android Studio Profiler, Perfetto, and Systrace, with Perfetto or Systrace evidence showing that 95% of Feed frames are at least 55fps during accepted scroll profiling.

#### Scenario: Feed scroll profiling
- **WHEN** the developer captures a scroll performance trace
- **THEN** the repository includes evidence that 95% of Feed frames meet or exceed 55fps in the accepted trace

#### Scenario: Required profiling tools are reviewed
- **WHEN** the final performance report is reviewed
- **THEN** Android Studio Profiler, Perfetto, and Systrace artifacts are all present or explicitly documented with a reviewer-accepted environment limitation

#### Scenario: Playback memory profiling
- **WHEN** the developer captures a playback memory profile
- **THEN** the repository includes evidence that can be reviewed against the memory peak criteria

### Requirement: Automated stability evidence
The project SHALL provide evidence that 200 automated vertical swipe transitions complete with zero crashes.

#### Scenario: Automated swipe test completes
- **WHEN** the automated swipe stability test performs 200 transitions
- **THEN** the test result records zero application crashes

### Requirement: Delivery documentation
The project SHALL include required delivery documents covering architecture, run instructions, AI usage, architecture decisions, and bug investigations.

#### Scenario: README is reviewed
- **WHEN** a reviewer opens `README.md`
- **THEN** it contains architecture overview, run instructions, and a feature checklist

#### Scenario: AI usage is reviewed
- **WHEN** a reviewer opens `AI_USAGE.md`
- **THEN** it contains at least five AI-assisted decisions or rejected suggestions with rationale

#### Scenario: Decisions are reviewed
- **WHEN** a reviewer opens `DECISIONS.md`
- **THEN** it records key architecture decisions in ADR-style entries

#### Scenario: Bugfix notes are reviewed
- **WHEN** a reviewer opens `BUGFIX.md`
- **THEN** it records at least three real debugging cases with investigation process and root cause

#### Scenario: Demo video is reviewed
- **WHEN** the final delivery package is reviewed
- **THEN** it includes a 5 to 10 minute demo video covering the implemented Feed, playback, cache, lifecycle, and performance evidence

#### Scenario: Commit history is reviewed
- **WHEN** the final Git repository is reviewed
- **THEN** it contains at least 30 meaningful commits or a documented explanation if the training environment constrains commit history

### Requirement: Principle defense support
The project SHALL preserve enough implementation notes and diagrams to explain playback, rendering, cache, scheduling, and lifecycle principles.

#### Scenario: Reviewer asks media pipeline question
- **WHEN** the developer is asked to explain how video reaches the screen
- **THEN** project documentation and code organization support an answer from source bytes through decode and render

#### Scenario: Reviewer asks cache scheduling question
- **WHEN** the developer is asked to explain preload and cache behavior
- **THEN** project documentation and metrics support an answer covering strategy, eviction, Range fetch, and weak-network fallback
