package com.example.minifeed.player

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import com.example.minifeed.AppConfig
import com.example.minifeed.Tunables
import com.example.minifeed.perf.MetricEvent
import com.example.minifeed.perf.MetricsSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToLong

class MediaCodecMiniPlayer(private val metrics: MetricsSink) : MiniPlayer {
    private companion object {
        const val MIN_FRAME_DELAY_MS = 1L
        const val MAX_FRAME_DELAY_MS = 50L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val mutableStates = MutableStateFlow<PlayerState>(PlayerState.Idle)
    private val mutableEvents = MutableSharedFlow<PlayerEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    override val states: StateFlow<PlayerState> = mutableStates
    override val events: SharedFlow<PlayerEvent> = mutableEvents

    private val clock = MediaClock()
    private var extractor: MediaExtractor? = null
    private var codec: MediaCodec? = null
    private var audioExtractor: MediaExtractor? = null
    private var audioCodec: MediaCodec? = null
    private val audioSink: AudioSink = ResamplingAudioSink()
    private var decodeJob: Job? = null
    private var audioDecodeJob: Job? = null
    private var source: PlaybackSource? = null
    private var durationUs = 0L
    private var currentPositionUs = 0L
    private var firstFrameRendered = false
    private var prepareStartMs = 0L
    private var inputEos = false
    private var audioInputEos = false
    private var audioOutputEos = false
    private var audioSinkConfigured = false
    private var playbackRequested = false
    private var released = false
    private var buffering = false

    override suspend fun prepare(source: PlaybackSource, renderTarget: RenderTarget, startPositionMs: Long) {
        mutex.withLock {
            releaseLocked(ReleaseReason.PageChanged)
            released = false
            clock.setSpeed(Tunables.NORMAL_PLAYBACK_SPEED)
            this.source = source
            transition(PlayerState.Preparing(source.item.id))
            prepareStartMs = SystemClock.elapsedRealtime()
            metrics.record(MetricEvent.PrepareStarted(source.item.id, source.uri, prepareStartMs))

            withContext(Dispatchers.Default) {
                val surface = renderTarget.currentSurface()
                    ?: throwPlayerError(source.item.id, PlayerError.SurfaceLost("Surface unavailable during prepare"))
                val mediaExtractor = MediaExtractor()
                mediaExtractor.setDataSource(source.uri)
                val trackIndex = selectVideoTrack(mediaExtractor)
                if (trackIndex < 0) {
                    mediaExtractor.release()
                    throwPlayerError(source.item.id, PlayerError.SourceUnsupported("No video track in ${source.uri}"))
                }
                mediaExtractor.selectTrack(trackIndex)
                val format = mediaExtractor.getTrackFormat(trackIndex)
                val displaySize = displaySizeFromFormat(format)
                durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    source.item.durationMs * 1_000L
                }
                if (startPositionMs > 0) {
                    mediaExtractor.seekTo(startPositionMs * 1_000L, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    currentPositionUs = startPositionMs * 1_000L
                } else {
                    currentPositionUs = 0L
                }
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: throw IllegalStateException("Missing video mime")
                val mediaCodec = MediaCodec.createDecoderByType(mime)
                mediaCodec.configure(format, surface, null, 0)
                mediaCodec.start()
                val preparedAudio = if (AppConfig.ENABLE_AUDIO_MVP) {
                    runCatching { prepareAudioDecoder(source.uri, currentPositionUs) }.getOrNull()
                } else {
                    null
                }
                extractor = mediaExtractor
                codec = mediaCodec
                audioExtractor = preparedAudio?.extractor
                audioCodec = preparedAudio?.codec
                inputEos = false
                audioInputEos = false
                audioOutputEos = preparedAudio == null
                audioSinkConfigured = false
                audioSink.flush(currentPositionUs)
                updateAudioClockProvider()
                firstFrameRendered = false
                mutableEvents.tryEmit(PlayerEvent.FormatChanged(source.item.id, displaySize.first, displaySize.second))
            }

            val prepareElapsed = SystemClock.elapsedRealtime() - prepareStartMs
            metrics.record(MetricEvent.PrepareFinished(source.item.id, prepareElapsed))
            transition(PlayerState.Prepared(source.item.id, durationUs / 1_000L))
        }
    }

    override fun play() {
        val item = source?.item ?: return
        playbackRequested = true
        buffering = false
        clock.start(currentPositionUs)
        updateAudioSinkPlayback()
        transition(PlayerState.Playing(item.id, currentPositionUs / 1_000L, durationUs / 1_000L, clock.currentSpeed()))
        if (decodeJob?.isActive != true) {
            decodeJob = scope.launch { decodeLoop() }
        }
        if (audioCodec != null && audioDecodeJob?.isActive != true && !audioOutputEos) {
            audioDecodeJob = scope.launch { decodeAudioLoop() }
        }
    }

    override fun pause() {
        val item = source?.item ?: return
        playbackRequested = false
        currentPositionUs = clock.pause()
        runCatching { audioSink.pause() }
        transition(PlayerState.Paused(item.id, currentPositionUs / 1_000L))
    }

    fun enterBuffering() {
        val item = source?.item ?: return
        if (buffering) return
        buffering = true
        currentPositionUs = clock.pause()
        clock.setAudioPositionProvider(null)
        runCatching { audioSink.pause() }
        transition(PlayerState.Buffering(item.id, currentPositionUs / 1_000L))
    }

    fun recoverFromBuffering() {
        val item = source?.item ?: return
        if (!buffering) return
        buffering = false
        clock.start(currentPositionUs)
        updateAudioSinkPlayback()
        transition(PlayerState.Playing(item.id, currentPositionUs / 1_000L, durationUs / 1_000L, clock.currentSpeed()))
    }

    override suspend fun seekTo(positionMs: Long) {
        mutex.withLock {
            val mediaExtractor = extractor ?: return
            val mediaCodec = codec ?: return
            val mediaAudioExtractor = audioExtractor
            val mediaAudioCodec = audioCodec
            val itemId = source?.item?.id
            val wasPlaying = playbackRequested
            val targetMs = positionMs.coerceIn(0L, (durationUs / 1_000L).coerceAtLeast(0L))
            val targetUs = targetMs * 1_000L
            playbackRequested = false
            decodeJob?.cancelAndJoin()
            audioDecodeJob?.cancelAndJoin()
            decodeJob = null
            audioDecodeJob = null
            runCatching {
                withContext(Dispatchers.Default) {
                    mediaCodec.flush()
                    mediaExtractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    if (mediaAudioExtractor != null && mediaAudioCodec != null) {
                        mediaAudioCodec.flush()
                        mediaAudioExtractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                        audioSink.flush(targetUs)
                    }
                }
            }.onFailure {
                reportError(itemId, PlayerError.DecodeRuntimeFailed(it.message ?: "Seek failed"))
                return
            }
            inputEos = false
            audioInputEos = false
            audioOutputEos = mediaAudioCodec == null
            updateAudioClockProvider()
            currentPositionUs = targetUs
            firstFrameRendered = false
            clock.reAnchor(targetUs)
            if (wasPlaying) {
                playbackRequested = true
                clock.start(currentPositionUs)
                updateAudioSinkPlayback()
                transition(PlayerState.Playing(itemId.orEmpty(), targetMs, durationUs / 1_000L, clock.currentSpeed()))
                decodeJob = scope.launch { decodeLoop() }
                if (audioCodec != null && !audioOutputEos) {
                    audioDecodeJob = scope.launch { decodeAudioLoop() }
                }
            } else {
                itemId?.let { transition(PlayerState.Paused(it, targetMs)) }
            }
        }
    }

    override fun setSpeed(speed: Float) {
        val mediaTimeUs = playbackPositionUs()
        clock.setSpeed(speed)
        audioSink.setPlaybackSpeed(clock.currentSpeed())
        clock.reAnchor(mediaTimeUs)
        currentPositionUs = mediaTimeUs
        updateAudioSinkPlayback()
        val item = source?.item ?: return
        if (playbackRequested && !buffering) {
            transition(PlayerState.Playing(item.id, mediaTimeUs / 1_000L, durationUs / 1_000L, clock.currentSpeed()))
        }
    }

    override suspend fun rebindRenderTarget(renderTarget: RenderTarget) {
        val rememberedPositionMs = currentPositionUs / 1_000L
        val rememberedSource = source ?: return
        prepare(rememberedSource, renderTarget, rememberedPositionMs)
        if (playbackRequested) play()
    }

    override suspend fun release(reason: ReleaseReason) {
        mutex.withLock {
            releaseLocked(reason)
            transition(PlayerState.Released)
        }
    }

    private suspend fun releaseLocked(reason: ReleaseReason) {
        val started = SystemClock.elapsedRealtime()
        playbackRequested = false
        withTimeoutOrNull(800L) { decodeJob?.cancelAndJoin() }
        withTimeoutOrNull(800L) { audioDecodeJob?.cancelAndJoin() }
        decodeJob = null
        audioDecodeJob = null
        withContext(Dispatchers.Default) {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { audioSink.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            runCatching { audioExtractor?.release() }
        }
        codec = null
        extractor = null
        audioCodec = null
        audioExtractor = null
        inputEos = false
        audioInputEos = false
        audioOutputEos = false
        audioSinkConfigured = false
        clock.setAudioPositionProvider(null)
        clock.setSpeed(Tunables.NORMAL_PLAYBACK_SPEED)
        firstFrameRendered = false
        released = true
        metrics.record(MetricEvent.ReleaseLatency(reason.name, SystemClock.elapsedRealtime() - started))
    }

    private suspend fun decodeLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (scope.isActive && playbackRequested && !released) {
            if (buffering) {
                delay(16)
                continue
            }
            val mediaCodec = codec ?: return
            val mediaExtractor = extractor ?: return
            val itemId = source?.item?.id ?: return

            if (!inputEos) queueInput(mediaCodec, mediaExtractor)

            val outputIndex = runCatching { mediaCodec.dequeueOutputBuffer(bufferInfo, 10_000L) }.getOrElse {
                reportError(itemId, PlayerError.DecodeRuntimeFailed(it.message ?: "Output dequeue failed"))
                return
            }
            when {
                outputIndex >= 0 -> {
                    currentPositionUs = max(playbackPositionUs(), bufferInfo.presentationTimeUs)
                    val render = bufferInfo.size > 0
                    if (render) scheduleFrame(itemId, mediaCodec, outputIndex, bufferInfo.presentationTimeUs)
                    else mediaCodec.releaseOutputBuffer(outputIndex, false)
                    emitProgress(itemId)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        transition(PlayerState.Ended(itemId))
                        mutableEvents.tryEmit(PlayerEvent.Completed(itemId))
                        playbackRequested = false
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val size = displaySizeFromFormat(mediaCodec.outputFormat)
                    mutableEvents.tryEmit(PlayerEvent.FormatChanged(itemId, size.first, size.second))
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(4)
            }
        }
    }

    private suspend fun decodeAudioLoop() {
        val bufferInfo = MediaCodec.BufferInfo()
        while (scope.isActive && playbackRequested && !released && !audioOutputEos) {
            if (buffering) {
                runCatching { audioSink.pause() }
                delay(16)
                continue
            }
            val mediaCodec = audioCodec ?: return
            val mediaExtractor = audioExtractor ?: return
            val itemId = source?.item?.id ?: return

            if (!audioInputEos) queueAudioInput(mediaCodec, mediaExtractor)

            val outputIndex = runCatching { mediaCodec.dequeueOutputBuffer(bufferInfo, 10_000L) }.getOrElse {
                reportError(itemId, PlayerError.DecodeRuntimeFailed(it.message ?: "Audio output dequeue failed"))
                return
            }
            when {
                outputIndex >= 0 -> {
                    if (bufferInfo.size > 0) {
                        if (!ensureAudioSink(mediaCodec.outputFormat)) {
                            mediaCodec.releaseOutputBuffer(outputIndex, false)
                            continue
                        }
                        val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                        if (outputBuffer != null) {
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            updateAudioSinkPlayback()
                            audioSink.handleBuffer(outputBuffer, bufferInfo.presentationTimeUs)
                        }
                    }
                    mediaCodec.releaseOutputBuffer(outputIndex, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        audioOutputEos = true
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    audioSinkConfigured = false
                    ensureAudioSink(mediaCodec.outputFormat)
                }
                outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> delay(4)
            }
        }
    }

    private fun queueInput(codec: MediaCodec, extractor: MediaExtractor) {
        val inputIndex = codec.dequeueInputBuffer(4_000L)
        if (inputIndex < 0) return
        val inputBuffer: ByteBuffer = codec.getInputBuffer(inputIndex) ?: return
        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputEos = true
        } else {
            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private fun queueAudioInput(codec: MediaCodec, extractor: MediaExtractor) {
        val inputIndex = codec.dequeueInputBuffer(4_000L)
        if (inputIndex < 0) return
        val inputBuffer: ByteBuffer = codec.getInputBuffer(inputIndex) ?: return
        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            audioInputEos = true
        } else {
            codec.queueInputBuffer(inputIndex, 0, sampleSize, extractor.sampleTime, 0)
            extractor.advance()
        }
    }

    private suspend fun scheduleFrame(itemId: String, codec: MediaCodec, outputIndex: Int, presentationTimeUs: Long) {
        val speed = clock.currentSpeed().coerceAtLeast(Tunables.MIN_PLAYBACK_SPEED)
        val lateToleranceUs = scaledMediaThresholdUs(Tunables.LATE_FRAME_TOLERANCE_US, speed)
        val dropThresholdUs = scaledMediaThresholdUs(Tunables.DROP_FRAME_THRESHOLD_US, speed)
        while (playbackRequested && !released && !buffering) {
            val mediaTimeUs = clock.currentMediaTimeUs()
            val latenessUs = mediaTimeUs - presentationTimeUs
            when {
                latenessUs < 0 -> {
                val delayMs = ((-latenessUs / speed) / 1_000f).roundToLong()
                    if (delayMs >= MIN_FRAME_DELAY_MS) {
                        delay(delayMs.coerceAtMost(MAX_FRAME_DELAY_MS))
                        continue
                    }
                }
                latenessUs in 1..lateToleranceUs -> {
                    metrics.record(MetricEvent.FrameLate(itemId, latenessUs / 1_000L))
                }
                latenessUs > dropThresholdUs -> {
                    metrics.record(MetricEvent.FrameDrop(itemId, latenessUs / 1_000L))
                    codec.releaseOutputBuffer(outputIndex, false)
                    return
                }
            }
            codec.releaseOutputBuffer(outputIndex, true)
            if (!firstFrameRendered) {
                firstFrameRendered = true
                val elapsed = SystemClock.elapsedRealtime() - prepareStartMs
                transition(PlayerState.FirstFrameRendered(itemId, elapsed))
                mutableEvents.tryEmit(PlayerEvent.FirstFrame(itemId, elapsed))
                metrics.record(MetricEvent.FirstFrameRendered(itemId, elapsed))
            }
            return
        }
        codec.releaseOutputBuffer(outputIndex, false)
    }

    private fun scaledMediaThresholdUs(thresholdUsAtNormalSpeed: Long, speed: Float): Long {
        return (thresholdUsAtNormalSpeed / speed).roundToLong().coerceAtLeast(1_000L)
    }

    private fun emitProgress(itemId: String) {
        currentPositionUs = playbackPositionUs()
        mutableEvents.tryEmit(PlayerEvent.Progress(itemId, currentPositionUs / 1_000L, durationUs / 1_000L))
        transition(PlayerState.Playing(itemId, currentPositionUs / 1_000L, durationUs / 1_000L, clock.currentSpeed()))
    }

    private fun playbackPositionUs(): Long {
        return clock.currentMediaTimeUs().coerceIn(0L, durationUs.coerceAtLeast(0L))
    }

    private fun transition(next: PlayerState) {
        val previous = mutableStates.value
        mutableStates.value = next
        metrics.record(MetricEvent.PlayerStateTransition(previous.javaClass.simpleName, next.javaClass.simpleName, source?.item?.id))
    }

    private fun selectVideoTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("video/") == true) return index
        }
        return -1
    }

    private fun selectAudioTrack(extractor: MediaExtractor): Int {
        for (index in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
            if (mime?.startsWith("audio/") == true) return index
        }
        return -1
    }

    private fun displaySizeFromFormat(format: MediaFormat): Pair<Int, Int> {
        val codedWidth = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
        val codedHeight = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
        val cropWidth = if (format.containsKey("crop-left") && format.containsKey("crop-right")) {
            format.getInteger("crop-right") - format.getInteger("crop-left") + 1
        } else {
            codedWidth
        }
        val cropHeight = if (format.containsKey("crop-top") && format.containsKey("crop-bottom")) {
            format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1
        } else {
            codedHeight
        }
        val rotation = if (format.containsKey(MediaFormat.KEY_ROTATION)) {
            format.getInteger(MediaFormat.KEY_ROTATION)
        } else {
            0
        }
        return if (rotation == 90 || rotation == 270) cropHeight to cropWidth else cropWidth to cropHeight
    }

    private fun prepareAudioDecoder(uri: String, startPositionUs: Long): PreparedAudio? {
        val mediaExtractor = MediaExtractor()
        return try {
            mediaExtractor.setDataSource(uri)
            val trackIndex = selectAudioTrack(mediaExtractor)
            if (trackIndex < 0) {
                mediaExtractor.release()
                null
            } else {
                mediaExtractor.selectTrack(trackIndex)
                val format = mediaExtractor.getTrackFormat(trackIndex)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                }
                val mime = format.getString(MediaFormat.KEY_MIME) ?: error("Missing audio mime")
                val mediaCodec = MediaCodec.createDecoderByType(mime)
                mediaCodec.configure(format, null, null, 0)
                mediaCodec.start()
                if (startPositionUs > 0) {
                    mediaExtractor.seekTo(startPositionUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                }
                PreparedAudio(mediaExtractor, mediaCodec)
            }
        } catch (error: Throwable) {
            runCatching { mediaExtractor.release() }
            null
        }
    }

    private fun ensureAudioSink(format: MediaFormat): Boolean {
        if (!audioSinkConfigured) {
            audioSinkConfigured = audioSink.configure(format)
            audioSink.setPlaybackSpeed(clock.currentSpeed())
            updateAudioClockProvider()
        }
        return audioSinkConfigured
    }

    private fun updateAudioSinkPlayback() {
        audioSink.setPlaybackSpeed(clock.currentSpeed())
        updateAudioClockProvider()
        if (playbackRequested && !buffering && audioSinkConfigured) {
            runCatching { audioSink.play() }
        } else {
            runCatching { audioSink.pause() }
        }
    }

    private fun updateAudioClockProvider() {
        clock.setAudioPositionProvider(
            if (audioSinkConfigured && !buffering) audioSink::currentMediaTimeUs else null
        )
    }

    private fun reportError(itemId: String?, error: PlayerError) {
        transition(PlayerState.Error(itemId, error))
        mutableEvents.tryEmit(PlayerEvent.Failed(itemId, error))
        metrics.record(MetricEvent.PlaybackError(itemId, error.javaClass.simpleName, "release slot and skip or retry once"))
    }

    private fun throwPlayerError(itemId: String?, error: PlayerError): Nothing {
        reportError(itemId, error)
        throw IllegalStateException(error.diagnostic)
    }

    private data class PreparedAudio(
        val extractor: MediaExtractor,
        val codec: MediaCodec
    )
}
