package com.example.minifeed.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaFormat
import com.example.minifeed.Tunables
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

interface AudioSink {
    fun configure(format: MediaFormat): Boolean
    fun setPlaybackSpeed(speed: Float)
    fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long): Boolean
    fun play()
    fun pause()
    fun flush(anchorMediaTimeUs: Long)
    fun release()
    fun currentMediaTimeUs(): Long?
}

class ResamplingAudioSink : AudioSink {
    private var audioTrack: AudioTrack? = null
    private var sampleRate = 0
    private var channelCount = 0
    private var bytesPerFrame = 0
    private var playbackSpeed = Tunables.NORMAL_PLAYBACK_SPEED
    private var outputScratch = ByteArray(0)

    private var anchorMediaTimeUs = 0L
    private var anchorPlaybackHeadFrames = 0L
    private var playbackHeadWrapCount = 0L
    private var lastPlaybackHead = 0L
    private var hasClockAnchor = false
    private var anchorOnNextInputBuffer = true

    override fun configure(format: MediaFormat): Boolean {
        val newSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else {
            return false
        }
        val newChannelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else {
            return false
        }
        val encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }
        if (encoding != AudioFormat.ENCODING_PCM_16BIT) return false
        val channelMask = when (newChannelCount) {
            1 -> AudioFormat.CHANNEL_OUT_MONO
            2 -> AudioFormat.CHANNEL_OUT_STEREO
            else -> return false
        }
        if (audioTrack != null && sampleRate == newSampleRate && channelCount == newChannelCount) {
            return true
        }

        releaseTrack()
        val minBufferSize = AudioTrack.getMinBufferSize(newSampleRate, channelMask, encoding)
        if (minBufferSize <= 0) return false

        val bufferMultiplier = max(4, ceil(Tunables.MAX_PLAYBACK_SPEED).toInt())
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(newSampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(encoding)
                    .build()
            )
            .setBufferSizeInBytes(minBufferSize * bufferMultiplier)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        sampleRate = newSampleRate
        channelCount = newChannelCount
        bytesPerFrame = newChannelCount * 2
        resetClock(anchorMediaTimeUs)
        anchorOnNextInputBuffer = true
        return true
    }

    override fun setPlaybackSpeed(speed: Float) {
        val nextSpeed = speed.coerceIn(Tunables.MIN_PLAYBACK_SPEED, Tunables.MAX_PLAYBACK_SPEED)
        if (abs(playbackSpeed - nextSpeed) <= 0.01f) return
        val mediaTimeUs = currentMediaTimeUs() ?: anchorMediaTimeUs
        playbackSpeed = nextSpeed
        resetClock(mediaTimeUs)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long): Boolean {
        val track = audioTrack ?: return false
        if (anchorOnNextInputBuffer || !hasClockAnchor || presentationTimeUs < anchorMediaTimeUs) {
            resetClock(presentationTimeUs)
            anchorOnNextInputBuffer = false
        }
        val writeBuffer = resample(buffer)
        while (writeBuffer.hasRemaining()) {
            val written = track.write(writeBuffer, writeBuffer.remaining(), AudioTrack.WRITE_BLOCKING)
            if (written <= 0) return false
        }
        return true
    }

    override fun play() {
        audioTrack?.play()
    }

    override fun pause() {
        audioTrack?.pause()
    }

    override fun flush(anchorMediaTimeUs: Long) {
        audioTrack?.pause()
        audioTrack?.flush()
        resetClock(anchorMediaTimeUs)
        anchorOnNextInputBuffer = true
    }

    override fun release() {
        releaseTrack()
        sampleRate = 0
        channelCount = 0
        bytesPerFrame = 0
        hasClockAnchor = false
        anchorOnNextInputBuffer = true
        outputScratch = ByteArray(0)
    }

    override fun currentMediaTimeUs(): Long? {
        if (audioTrack == null || sampleRate <= 0 || !hasClockAnchor) return null
        val playedFrames = (totalPlaybackHeadFrames() - anchorPlaybackHeadFrames).coerceAtLeast(0L)
        val playedUsAtNormalSpeed = playedFrames * 1_000_000L / sampleRate
        return anchorMediaTimeUs + (playedUsAtNormalSpeed * playbackSpeed).toLong()
    }

    private fun resample(input: ByteBuffer): ByteBuffer {
        if (bytesPerFrame <= 0 || input.remaining() <= 0) return input.slice()
        if (abs(playbackSpeed - Tunables.NORMAL_PLAYBACK_SPEED) <= 0.01f) return input.slice()

        val inputBytes = input.remaining()
        val inputFrames = inputBytes / bytesPerFrame
        if (inputFrames <= 0) return ByteBuffer.allocate(0)
        val outputFrames = max(1, ceil(inputFrames / playbackSpeed).toInt())
        val outputBytes = outputFrames * bytesPerFrame
        if (outputScratch.size < outputBytes) {
            outputScratch = ByteArray(outputBytes)
        }

        val inputStart = input.position()
        for (outputFrame in 0 until outputFrames) {
            val inputFrame = (outputFrame * playbackSpeed).toInt().coerceIn(0, inputFrames - 1)
            val inputOffset = inputStart + inputFrame * bytesPerFrame
            val outputOffset = outputFrame * bytesPerFrame
            for (byteIndex in 0 until bytesPerFrame) {
                outputScratch[outputOffset + byteIndex] = input.get(inputOffset + byteIndex)
            }
        }
        return ByteBuffer.wrap(outputScratch, 0, outputBytes)
    }

    private fun resetClock(mediaTimeUs: Long) {
        anchorMediaTimeUs = mediaTimeUs.coerceAtLeast(0L)
        playbackHeadWrapCount = 0L
        lastPlaybackHead = 0L
        anchorPlaybackHeadFrames = totalPlaybackHeadFrames()
        hasClockAnchor = true
    }

    private fun totalPlaybackHeadFrames(): Long {
        val head = audioTrack?.playbackHeadPosition?.toLong()?.and(0xFFFF_FFFFL) ?: return 0L
        if (head < lastPlaybackHead) {
            playbackHeadWrapCount += 1L
        }
        lastPlaybackHead = head
        return (playbackHeadWrapCount shl 32) + head
    }

    private fun releaseTrack() {
        runCatching { audioTrack?.pause() }
        runCatching { audioTrack?.flush() }
        runCatching { audioTrack?.release() }
        audioTrack = null
    }
}
