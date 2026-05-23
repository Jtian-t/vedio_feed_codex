## Why

The current like/comment controls are implemented as text buttons, which makes the UI look like a debug overlay and causes awkward wrapping such as `Liked 273` and `Comments 0`. The feed needs a recognizable short-video social control surface: a heart icon with clear liked/unliked state, a comment entry point, and local persistence so user actions survive view recycling and app restarts.

## What Changes

- Replace the text-only like control with a heart-based control.
- Show an unliked heart in black/dark styling and a liked heart in red styling.
- Keep the like count visible below or near the heart without forcing awkward text wrapping.
- Keep the comment control visually consistent with the like control, using a compact icon-style button and count.
- Persist per-video local social state on device, including liked state, like count adjustment, and local comments.
- Restore persisted social state when the feed is rebuilt or the app is relaunched.
- Keep all persistence local-only; no backend API is introduced.

## Capabilities

### New Capabilities

- `local-social-ui-persistence`: Covers local like/comment UI interactions, heart visual states, comment list/input behavior, and on-device persistence of local social state.

### Modified Capabilities

- None.

## Impact

- Affects `VideoFeedAdapter` and `VideoPageView` UI composition.
- Adds a small local repository/store for social state, likely backed by `SharedPreferences` or another lightweight Android local storage API.
- Extends feed binding so social state is loaded from the local store and saved after user actions.
- Does not add network dependencies or backend contracts.
