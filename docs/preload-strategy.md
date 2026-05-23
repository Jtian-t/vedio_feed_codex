# Deterministic Preload Strategy

The Feed scheduler uses a bounded, explainable strategy:

- Current playback owns network priority.
- After first progress or 20% playback, preload the next item.
- Fast forward swipes may expand the window to the next two items.
- Direction change or background entry cancels stale preload jobs.
- Cache preload is separate from player-slot preparation; the next slot can be prepared without waiting for cache preload.

Weak-network variant selection uses recent OkHttp transfer samples. Downgrades and upgrades affect future prepare or preload requests only, avoiding unstable mid-playback switching in the MVP.
