package com.example.minifeed.network

import com.example.minifeed.Tunables
import com.example.minifeed.data.VideoItem
import com.example.minifeed.data.VideoVariant

class VariantSelector(private val bandwidthEstimator: BandwidthEstimator) {
    private var selectedRank: Int? = null
    private var recoveryWindows = 0

    fun select(item: VideoItem): VideoVariant? {
        val variants = item.variants.filter { it.mimeType == "video/mp4" }.sortedBy { it.quality.rank }
        if (variants.isEmpty()) return null
        val throughputKbps = bandwidthEstimator.currentKbps()
        if (throughputKbps <= 0 || bandwidthEstimator.stableSampleCount() < 2) {
            selectedRank = variants.last().quality.rank
            return variants.last()
        }

        val current = variants.lastOrNull { it.quality.rank == selectedRank } ?: variants.last()
        val downgrade = variants.lastOrNull {
            throughputKbps > (it.bitrateKbps * Tunables.DOWNGRADE_BITRATE_MULTIPLIER)
        } ?: variants.first()
        if (downgrade.quality.rank < current.quality.rank) {
            recoveryWindows = 0
            selectedRank = downgrade.quality.rank
            return downgrade
        }

        val canUpgrade = throughputKbps > current.bitrateKbps * Tunables.UPGRADE_BITRATE_MULTIPLIER
        recoveryWindows = if (canUpgrade) recoveryWindows + 1 else 0
        if (recoveryWindows >= 2) {
            val upgraded = variants.firstOrNull { it.quality.rank > current.quality.rank } ?: current
            selectedRank = upgraded.quality.rank
            recoveryWindows = 0
            return upgraded
        }

        selectedRank = current.quality.rank
        return current
    }
}
