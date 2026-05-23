## ADDED Requirements

### Requirement: Heart-based like control
The system SHALL render the like action as a heart control with a separate count label.

#### Scenario: Video is not liked
- **WHEN** a Feed item is bound with local liked state set to false
- **THEN** the like control displays a black or dark heart and the current like count without showing the words `Like` or `Liked`

#### Scenario: Video is liked
- **WHEN** the user taps the heart control for an unliked Feed item
- **THEN** the like control displays a red heart and increments the visible like count by one

#### Scenario: User removes like
- **WHEN** the user taps the heart control for a liked Feed item
- **THEN** the like control returns to a black or dark heart and decrements the visible like count without going below zero

### Requirement: Compact social action column
The system SHALL present like and comment actions as compact icon-style controls suitable for the right-side Feed action column.

#### Scenario: Social controls are displayed
- **WHEN** a Feed page is visible
- **THEN** the like and comment controls display icon-style actions with numeric labels that do not wrap into broken words

#### Scenario: Comments are available
- **WHEN** a Feed item has local comments
- **THEN** the comment control displays the current local comment count in the action column

### Requirement: Local comment panel
The system SHALL provide a local comment panel for viewing and adding comments on the current Feed item.

#### Scenario: User opens comments
- **WHEN** the user taps the comment control
- **THEN** the app displays a comment panel for the bound Feed item with the existing local comments and an input field

#### Scenario: User sends a comment
- **WHEN** the user enters non-empty text and taps send
- **THEN** the comment is appended to the current Feed item's local comment list and the visible comment count updates

#### Scenario: User sends empty comment
- **WHEN** the user taps send with empty or whitespace-only input
- **THEN** the app does not append a comment and keeps the existing comment list unchanged

### Requirement: Local social persistence
The system SHALL persist local social state on device per Feed item id.

#### Scenario: App reloads after like
- **WHEN** the user likes a Feed item and later relaunches the app
- **THEN** the Feed item restores the liked heart state and adjusted like count from local storage

#### Scenario: App reloads after comment
- **WHEN** the user adds a local comment and later relaunches the app
- **THEN** the Feed item restores the local comment list and comment count from local storage

#### Scenario: Feed view is recycled
- **WHEN** RecyclerView recycles and rebinds a Feed page
- **THEN** the page restores social state from the in-memory cache or local store for the bound item id

#### Scenario: Persistence read fails
- **WHEN** local social state cannot be decoded for an item
- **THEN** the app falls back to the item's catalog social defaults without crashing
