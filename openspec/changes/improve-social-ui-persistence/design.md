## Context

The feed currently renders social actions as `TextView` controls containing words such as `Like`, `Liked`, and `Comments`. This creates visual wrapping in the narrow right-side action column and does not match the short-video interaction pattern. The adapter also keeps social state in an in-memory map, so likes and comments are lost when the process is recreated.

The project is intentionally lightweight and does not use a backend. Social behavior should remain local to the device while feeling complete enough for demo and acceptance review.

## Goals / Non-Goals

**Goals:**

- Render like as a heart control instead of a word button.
- Use black/dark heart for unliked state and red heart for liked state.
- Keep count labels compact and readable under icon controls.
- Keep comments as a local panel with list, input, and send behavior.
- Persist local liked state, like count deltas, and local comments across app restarts.
- Avoid network calls and avoid new heavyweight dependencies.

**Non-Goals:**

- No backend synchronization.
- No user identity, moderation, replies, deletion, or pagination.
- No cross-device persistence.
- No Material Components dependency unless the project later adopts a broader design system.

## Decisions

### Use text glyph icons for the first heart UI pass

Use a `TextView` heart glyph for the like icon: a dark heart when unliked and a red heart when liked. Keep the numeric count in a separate label below the icon.

Rationale: the existing UI is programmatic Android views, not XML/vector drawables. A glyph keeps the change small, avoids introducing asset management, and fixes the immediate broken `Like/Liked` text display.

Alternative considered: vector drawable heart assets. This is more visually controllable and can be adopted later, but it adds asset files and selector/tint plumbing for little short-term benefit.

### Introduce a local social state store

Add a small store abstraction that can load and save `LocalSocialState` by item id. The first implementation should use `SharedPreferences` with JSON-like string encoding or Android `JSONObject`.

Rationale: the data volume is small, local-only, and keyed by video id. `SharedPreferences` is available without extra dependencies and is enough for liked state plus a short comment list.

Alternative considered: Room or DataStore. Both are stronger long-term storage choices, but they add dependencies and setup cost that are not justified for local demo social state.

### Keep adapter state as an in-memory cache backed by persistence

The adapter can continue using `MutableMap<String, LocalSocialState>` as a fast binding cache, but it should hydrate from the store and save changes on each user action.

Rationale: this minimizes UI churn and keeps RecyclerView binding cheap while making state durable.

### Persist local changes per generated feed id

Use the current `VideoItem.id` as the persistence key rather than `baseId`.

Rationale: the feed synthesizes 10,000 stable identities from base catalog entries. Per-feed-item state is less surprising: liking one repeated generated item does not automatically like every repeated copy.

## Risks / Trade-offs

- [Risk] Heart glyph rendering may differ across devices. → Mitigation: use common Unicode heart characters and keep the count label separate; switch to vector drawable if QA finds inconsistent rendering.
- [Risk] `SharedPreferences` string encoding can become awkward if comments grow. → Mitigation: cap local comment count per item or migrate to DataStore/Room later.
- [Risk] Saving on every tap/write can block if performed poorly. → Mitigation: use `SharedPreferences.apply()` and keep payload small.
- [Risk] The right-side controls may still overlap narrow screens. → Mitigation: use fixed compact action dimensions and separate icon/count labels.

## Migration Plan

1. Add a local social store and wire it into adapter construction.
2. On bind, read cached state or hydrate from the store.
3. On like/comment changes, update memory state, save to the store, and re-render the bound page.
4. Update README and architecture docs to describe local persistence.
5. Rollback by falling back to the in-memory map if persistence fails.

## Open Questions

- Should generated repeated items share social state through `baseId` in a future product mode?
- Should local comments have a maximum count per item for storage hygiene?
