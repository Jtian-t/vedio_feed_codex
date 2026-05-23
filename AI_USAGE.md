# AI Usage

1. Adopted: split packages by data, feed, player, scheduler, proxy, cache, network, and perf. Rationale: matches OpenSpec ownership and makes principle defense clearer.
2. Adopted: use `RecyclerView` plus `PagerSnapHelper` instead of Compose. Rationale: simpler surface lifecycle and stable item recycling.
3. Adopted: use video-only MVP first. Rationale: direct decode, seek, surface recovery, and cache are higher risk than audio extension.
4. Adopted: keep proxy playback behind a flag. Rationale: direct playback isolates decoder issues from transport/cache issues.
5. Rejected: use ExoPlayer or AndroidVideoCache. Rationale: they violate the assignment constraint and hide required implementation principles.
6. Rejected: mid-playback bitrate switching for MVP. Rationale: future-item selection is more stable and easier to explain without HLS/DASH support.
