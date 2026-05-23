# Feed Player Sequence

## Feed Choice

Use `RecyclerView` with `PagerSnapHelper` for MVP. This gives explicit control over ViewHolder attach/detach, stable IDs, and `TextureView` lifecycle. `ViewPager2` remains a fallback if paging behavior is simpler during implementation.

## Page Components

```text
VideoFeedFragment
  RecyclerView
    VideoViewHolder
      TextureView
      OverlayControls
      LikeCommentPanel
```

`VideoViewHolder` owns the render view but does not own playback. It exposes a `FeedPageHandle` to `PlayerCoordinator`.

## Page Selection Flow

```text
RecyclerView scroll idle
  -> SnapHelper finds centered item
  -> FeedController emits selected index
  -> PlayerCoordinator.onPageSelected(index)
  -> current slot pauses/releases old item
  -> current slot prepares new item with holder RenderTarget
  -> MiniPlayer emits FirstFrameRendered
  -> current slot plays
  -> next slot prepares/preloads predicted next item
```

## Surface Attach Flow

```text
ViewHolder attached
  -> TextureView available?
      yes -> FeedPageHandle emits SurfaceAvailable
      no  -> wait for SurfaceTextureListener
  -> Coordinator binds surface only if page is current or warm slot
```

## Surface Detach Flow

```text
ViewHolder detached
  -> FeedPageHandle emits SurfaceLost
  -> if holder is current:
       pause output and wait briefly for replacement holder
     else:
       release warm slot resources
```

## Player Slot Rules

| Slot | Resource Level | Behavior |
| --- | --- | --- |
| Current | Codec + surface + proxy request | Plays active item |
| Next | Prepared metadata or cache preload; codec only if safe | Speeds forward swipe |
| Previous | Recently paused position or cache warmth | Supports quick reverse swipe |

Only current is guaranteed to own an active codec. Next codec warm-up is optional and must yield under memory pressure.

## App Background Flow

```text
onPause/onStop
  -> Coordinator records current item id and position
  -> pause UI state updates
  -> MiniPlayer.release(LifecycleRelease)
  -> cancel preload jobs
  -> stop proxy accepting new requests
  -> flush cache index
```

## App Resume Flow

```text
onStart/onResume
  -> restart proxy lazily
  -> restore current item id and position
  -> wait for current ViewHolder surface
  -> prepare current item
  -> seek to remembered position if supported
  -> render first frame
```

## Gesture Mapping

| Gesture | Command |
| --- | --- |
| Tap video | `MiniPlayer.play()` or `pause()` |
| Drag progress | `MiniPlayer.seekTo(positionMs)` |
| Long press down | `MiniPlayer.setSpeed(2.0f)` |
| Long press up/cancel | `MiniPlayer.setSpeed(1.0f)` |
| Swipe page | `PlayerCoordinator.onPageSelected(index)` |

## Metrics Points

- Page selected timestamp.
- Prepare start timestamp.
- First frame rendered timestamp.
- Surface lost/rebound count.
- Slot release duration.
- Background release duration.
