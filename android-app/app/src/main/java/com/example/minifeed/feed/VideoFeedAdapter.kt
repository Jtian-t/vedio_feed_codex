package com.example.minifeed

import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.minifeed.data.LocalSocialState
import com.example.minifeed.data.LocalSocialStore
import com.example.minifeed.data.VideoItem
import com.example.minifeed.feed.FeedPageHandle
import com.example.minifeed.feed.FeedPlaybackState
import com.example.minifeed.feed.PlayerCoordinator
import com.example.minifeed.player.TextureRenderTarget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

class VideoFeedAdapter(
    private val items: List<VideoItem>,
    private val coordinator: PlayerCoordinator,
    private val socialStore: LocalSocialStore,
    private val lifecycleScope: CoroutineScope
) : RecyclerView.Adapter<VideoFeedAdapter.VideoViewHolder>() {
    private val socialStates = mutableMapOf<String, LocalSocialState>()
    private val attachedHolders = mutableSetOf<VideoViewHolder>()
    private var playbackState = FeedPlaybackState()

    init {
        setHasStableIds(true)
        lifecycleScope.launch {
            coordinator.activeState.collect { state ->
                playbackState = state
                attachedHolders.forEach { it.renderPlayback(state) }
            }
        }
    }

    override fun getItemId(position: Int): Long = items[position].id.hashCode().toLong()
    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VideoViewHolder {
        return VideoViewHolder(VideoPageView(parent.context), coordinator, socialStore, lifecycleScope, socialStates)
    }

    override fun onBindViewHolder(holder: VideoViewHolder, position: Int) {
        holder.bind(position, items[position])
        holder.renderPlayback(playbackState)
    }

    override fun onViewAttachedToWindow(holder: VideoViewHolder) {
        super.onViewAttachedToWindow(holder)
        attachedHolders += holder
        val position = holder.bindingAdapterPosition
        if (position != RecyclerView.NO_POSITION) {
            lifecycleScope.launch { coordinator.attachPage(holder.pageHandle(position)) }
        }
    }

    override fun onViewDetachedFromWindow(holder: VideoViewHolder) {
        super.onViewDetachedFromWindow(holder)
        val position = holder.bindingAdapterPosition
        attachedHolders -= holder
        holder.resetSpeedGesture()
        if (position != RecyclerView.NO_POSITION) {
            lifecycleScope.launch { coordinator.onPageDetached(position) }
        }
    }

    class VideoViewHolder(
        private val pageView: VideoPageView,
        private val coordinator: PlayerCoordinator,
        private val socialStore: LocalSocialStore,
        private val lifecycleScope: CoroutineScope,
        private val socialStates: MutableMap<String, LocalSocialState>
    ) : RecyclerView.ViewHolder(pageView) {
        private var item: VideoItem? = null
        private val renderTarget = TextureRenderTarget(pageView.textureView)
        private var userSeeking = false
        private var longPressSpeedActive = false
        private var longPressSpeedRunnable: Runnable? = null

        fun bind(index: Int, item: VideoItem) {
            this.item = item
            userSeeking = false
            longPressSpeedActive = false
            longPressSpeedRunnable?.let { pageView.removeCallbacks(it) }
            longPressSpeedRunnable = null
            pageView.showSpeedBadge(Tunables.NORMAL_PLAYBACK_SPEED, visible = false)
            pageView.hideComments()
            pageView.applyVideoTransform(item.width, item.height)
            val state = socialStates.getOrPut(item.id) { socialStore.load(item.id, item.social) }
            pageView.title.text = item.title
            pageView.author.text = "@${item.author}"
            pageView.renderSocial(state)
            pageView.unsupported.visibility = if (item.playableVariant == null) View.VISIBLE else View.GONE
            pageView.like.setOnClickListener {
                val current = socialStates[item.id] ?: socialStore.load(item.id, item.social)
                val next = current.copy(
                    liked = !current.liked,
                    likeCount = (current.likeCount + if (current.liked) -1 else 1).coerceAtLeast(0)
                )
                socialStates[item.id] = next
                socialStore.save(item.id, next)
                pageView.renderSocial(next)
            }
            pageView.comment.setOnClickListener {
                pageView.showComments(socialStates[item.id] ?: socialStore.load(item.id, item.social))
            }
            pageView.commentClose.setOnClickListener {
                pageView.hideComments()
            }
            pageView.commentSend.setOnClickListener {
                val text = pageView.commentInput.text?.toString()?.trim().orEmpty()
                if (text.isEmpty()) return@setOnClickListener
                val current = socialStates[item.id] ?: socialStore.load(item.id, item.social)
                val next = current.copy(comments = current.comments + text)
                socialStates[item.id] = next
                socialStore.save(item.id, next)
                pageView.commentInput.text?.clear()
                pageView.renderSocial(next)
                pageView.showComments(next)
            }
            pageView.progress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        pageView.time.text = "${formatTime(progress.toLong())} / ${formatTime(seekBar.max.toLong())}"
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {
                    userSeeking = true
                }
                override fun onStopTrackingTouch(seekBar: SeekBar) {
                    val target = seekBar.progress.toLong()
                    userSeeking = false
                    lifecycleScope.launch { coordinator.seekTo(target) }
                }
            })
            pageView.setOnClickListener { coordinator.togglePlayback() }
            pageView.setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        longPressSpeedActive = false
                        longPressSpeedRunnable?.let { pageView.removeCallbacks(it) }
                        val speedRunnable = Runnable {
                            longPressSpeedActive = true
                            coordinator.setSpeed(Tunables.LONG_PRESS_SPEED)
                            pageView.showSpeedBadge(Tunables.LONG_PRESS_SPEED, visible = true)
                        }
                        longPressSpeedRunnable = speedRunnable
                        pageView.postDelayed(speedRunnable, 350)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        longPressSpeedRunnable?.let { pageView.removeCallbacks(it) }
                        longPressSpeedRunnable = null
                        val wasSpeeding = longPressSpeedActive
                        coordinator.setSpeed(Tunables.NORMAL_PLAYBACK_SPEED)
                        longPressSpeedActive = false
                        pageView.showSpeedBadge(Tunables.NORMAL_PLAYBACK_SPEED, visible = false)
                        if (!wasSpeeding && event.actionMasked == MotionEvent.ACTION_UP) {
                            pageView.performClick()
                        }
                        true
                    }
                    else -> true
                }
            }
        }

        fun resetSpeedGesture() {
            longPressSpeedRunnable?.let { pageView.removeCallbacks(it) }
            longPressSpeedRunnable = null
            if (longPressSpeedActive) {
                coordinator.setSpeed(Tunables.NORMAL_PLAYBACK_SPEED)
            }
            longPressSpeedActive = false
            pageView.showSpeedBadge(Tunables.NORMAL_PLAYBACK_SPEED, visible = false)
        }

        fun renderPlayback(state: FeedPlaybackState) {
            val bound = item ?: return
            val isActive = state.activeItemId == bound.id
            val duration = when {
                isActive && state.durationMs > 0 -> state.durationMs
                bound.durationMs > 0 -> bound.durationMs
                else -> 0L
            }
            if (isActive && state.videoWidth > 0 && state.videoHeight > 0) {
                pageView.applyVideoTransform(state.videoWidth, state.videoHeight)
            }
            val position = if (isActive) state.positionMs.coerceIn(0L, duration.coerceAtLeast(0L)) else 0L
            pageView.progress.max = duration.coerceAtMost(Int.MAX_VALUE.toLong()).toInt().coerceAtLeast(1)
            if (!userSeeking) {
                pageView.progress.progress = position.coerceAtMost(pageView.progress.max.toLong()).toInt()
            }
            pageView.time.text = "${formatTime(position)} / ${formatTime(duration)}"
            if (!longPressSpeedActive) {
                pageView.showSpeedBadge(
                    speed = state.speed,
                    visible = isActive && abs(state.speed - Tunables.NORMAL_PLAYBACK_SPEED) > 0.01f
                )
            }
            pageView.playIndicator.visibility = when {
                !isActive -> View.GONE
                state.preparing -> View.VISIBLE
                state.playing -> View.GONE
                else -> View.VISIBLE
            }
            pageView.playIndicator.text = if (state.preparing) "Loading" else "Play"
            pageView.error.visibility = if (isActive && state.error != null) View.VISIBLE else View.GONE
            pageView.error.text = state.error ?: ""
        }

        fun pageHandle(position: Int = bindingAdapterPosition): FeedPageHandle {
            val bound = item ?: error("ViewHolder is not bound")
            return FeedPageHandle(position, bound, renderTarget)
        }

        private fun formatTime(ms: Long): String {
            val safeMs = ms.coerceAtLeast(0L)
            val totalSeconds = safeMs / 1_000L
            val minutes = totalSeconds / 60L
            val seconds = totalSeconds % 60L
            return String.format(Locale.US, "%d:%02d", minutes, seconds)
        }
    }

}

class VideoPageView(context: android.content.Context) : FrameLayout(context) {
    val textureView = TextureView(context)
    val title = TextView(context)
    val author = TextView(context)
    val like = LinearLayout(context)
    val likeIcon = HeartIconView(context)
    val likeCount = TextView(context)
    val comment = LinearLayout(context)
    val commentIcon = CommentIconView(context)
    val commentCount = TextView(context)
    val commentsPanel = LinearLayout(context)
    val commentsTitle = TextView(context)
    val commentsList = LinearLayout(context)
    val commentInput = EditText(context)
    val commentSend = Button(context)
    val commentClose = Button(context)
    val unsupported = TextView(context)
    val error = TextView(context)
    val playIndicator = TextView(context)
    val speedBadge = TextView(context)
    val progress = SeekBar(context)
    val time = TextView(context)
    private var videoWidth = 0
    private var videoHeight = 0

    init {
        layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setBackgroundColor(Color.BLACK)
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))

        val bottom = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 0, 32, 48)
            gravity = Gravity.BOTTOM
        }
        title.setTextColor(Color.WHITE)
        title.textSize = 20f
        author.setTextColor(Color.LTGRAY)
        author.textSize = 14f
        time.setTextColor(Color.WHITE)
        time.textSize = 12f
        time.gravity = Gravity.END
        progress.max = 1
        bottom.addView(title)
        bottom.addView(author)
        bottom.addView(time, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        bottom.addView(progress, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 48))
        addView(bottom, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))

        val side = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        configureSocialButton(like, likeIcon, likeCount)
        configureSocialButton(comment, commentIcon, commentCount)
        side.addView(like)
        side.addView(comment)
        val sideParams = LayoutParams(widthPx(88), LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM)
        sideParams.setMargins(0, 0, widthPx(14), heightPx(116))
        addView(side, sideParams)

        commentsPanel.orientation = LinearLayout.VERTICAL
        commentsPanel.setPadding(28, 20, 28, 24)
        commentsPanel.background = GradientDrawable().apply {
            setColor(0xF2121212.toInt())
            cornerRadii = floatArrayOf(28f, 28f, 28f, 28f, 0f, 0f, 0f, 0f)
        }
        commentsPanel.visibility = View.GONE
        commentsPanel.isClickable = true
        commentsPanel.isFocusable = true

        val commentsHeader = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        commentsTitle.setTextColor(Color.WHITE)
        commentsTitle.textSize = 18f
        commentClose.text = "Close"
        commentsHeader.addView(
            commentsTitle,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        commentsHeader.addView(commentClose)
        commentsPanel.addView(commentsHeader)

        val commentsScroll = ScrollView(context)
        commentsList.orientation = LinearLayout.VERTICAL
        commentsList.setPadding(0, 12, 0, 12)
        commentsScroll.addView(commentsList)
        commentsPanel.addView(
            commentsScroll,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val commentComposer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        commentInput.hint = "Add a comment"
        commentInput.setTextColor(Color.WHITE)
        commentInput.setHintTextColor(Color.LTGRAY)
        commentInput.setBackgroundColor(0x22FFFFFF)
        commentInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        commentInput.setSingleLine(true)
        commentSend.text = "Send"
        commentComposer.addView(
            commentInput,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        commentComposer.addView(commentSend)
        commentsPanel.addView(commentComposer)
        addView(commentsPanel, LayoutParams(LayoutParams.MATCH_PARENT, heightPx(320), Gravity.BOTTOM))

        unsupported.text = "Unsupported source"
        unsupported.setTextColor(Color.WHITE)
        unsupported.textSize = 18f
        unsupported.gravity = Gravity.CENTER
        unsupported.setBackgroundColor(0x99000000.toInt())
        addView(unsupported, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        playIndicator.text = "Play"
        playIndicator.setTextColor(Color.WHITE)
        playIndicator.textSize = 18f
        playIndicator.gravity = Gravity.CENTER
        playIndicator.setBackgroundColor(0x66000000)
        addView(playIndicator, LayoutParams(180, 96, Gravity.CENTER))

        speedBadge.text = "5.0x"
        speedBadge.setTextColor(Color.WHITE)
        speedBadge.textSize = 24f
        speedBadge.gravity = Gravity.CENTER
        speedBadge.setBackgroundColor(0xCC000000.toInt())
        speedBadge.visibility = View.GONE
        addView(speedBadge, LayoutParams(180, 88, Gravity.CENTER))

        error.setTextColor(Color.WHITE)
        error.textSize = 13f
        error.gravity = Gravity.CENTER
        error.setBackgroundColor(0x99000000.toInt())
        error.visibility = View.GONE
        addView(error, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP))
    }

    fun showSpeedBadge(speed: Float, visible: Boolean) {
        speedBadge.text = String.format(Locale.US, "%.1fx", speed)
        speedBadge.visibility = if (visible) View.VISIBLE else View.GONE
    }

    fun renderSocial(state: LocalSocialState) {
        likeIcon.setLiked(state.liked)
        likeCount.text = state.likeCount.coerceAtLeast(0).toString()
        commentCount.text = state.comments.size.toString()
        if (commentsPanel.visibility == View.VISIBLE) {
            showComments(state)
        }
    }

    fun showComments(state: LocalSocialState) {
        commentsTitle.text = "Comments (${state.comments.size})"
        commentsList.removeAllViews()
        if (state.comments.isEmpty()) {
            commentsList.addView(commentRow("No comments yet"))
        } else {
            state.comments.forEachIndexed { index, text ->
                commentsList.addView(commentRow("${index + 1}. $text"))
            }
        }
        commentsPanel.visibility = View.VISIBLE
    }

    fun hideComments() {
        commentsPanel.visibility = View.GONE
        commentInput.text?.clear()
    }

    fun applyVideoTransform(videoWidth: Int, videoHeight: Int) {
        this.videoWidth = videoWidth
        this.videoHeight = videoHeight
        if (videoWidth <= 0 || videoHeight <= 0) {
            textureView.setTransform(Matrix())
            return
        }
        textureView.post {
            updateTextureFrame()
            updateTextureTransform()
        }
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        updateTextureFrame()
        updateTextureTransform()
    }

    private fun updateTextureFrame() {
        val parentWidth = width
        val parentHeight = height
        if (parentWidth <= 0 || parentHeight <= 0) return

        val targetWidth = min(parentWidth, parentHeight * 4 / 3)
        val targetHeight = min(parentHeight, targetWidth * 3 / 4)
        val params = textureView.layoutParams as LayoutParams
        if (params.width != targetWidth || params.height != targetHeight || params.gravity != Gravity.CENTER) {
            params.width = targetWidth
            params.height = targetHeight
            params.gravity = Gravity.CENTER
            textureView.layoutParams = params
        }
    }

    private fun updateTextureTransform() {
        if (videoWidth <= 0 || videoHeight <= 0) return
        val viewWidth = textureView.width.toFloat()
        val viewHeight = textureView.height.toFloat()
        if (viewWidth <= 0f || viewHeight <= 0f) return

        val viewAspect = viewWidth / viewHeight
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val scaleX: Float
        val scaleY: Float
        if (videoAspect > viewAspect) {
            scaleX = 1f
            scaleY = viewAspect / videoAspect
        } else {
            scaleX = videoAspect / viewAspect
            scaleY = 1f
        }

        val matrix = Matrix()
        matrix.setScale(scaleX, scaleY, viewWidth / 2f, viewHeight / 2f)
        textureView.setTransform(matrix)
    }

    private fun commentRow(text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(0, 10, 0, 10)
        }
    }

    private fun heightPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun widthPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun configureSocialButton(container: LinearLayout, icon: View, count: TextView) {
        container.orientation = LinearLayout.VERTICAL
        container.gravity = Gravity.CENTER
        container.minimumWidth = widthPx(56)
        container.minimumHeight = heightPx(64)
        container.setPadding(6, 10, 6, 10)
        count.setTextColor(Color.WHITE)
        count.textSize = 12f
        count.gravity = Gravity.CENTER
        count.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        container.addView(icon, LinearLayout.LayoutParams(widthPx(42), heightPx(42)))
        container.addView(count, LinearLayout.LayoutParams(widthPx(56), LinearLayout.LayoutParams.WRAP_CONTENT))
    }
}

class HeartIconView(context: android.content.Context) : View(context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var liked = false

    fun setLiked(liked: Boolean) {
        if (this.liked == liked) return
        this.liked = liked
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        path.reset()
        path.moveTo(w * 0.5f, h * 0.84f)
        path.cubicTo(w * 0.08f, h * 0.56f, w * 0.02f, h * 0.26f, w * 0.24f, h * 0.14f)
        path.cubicTo(w * 0.38f, h * 0.06f, w * 0.48f, h * 0.15f, w * 0.5f, h * 0.27f)
        path.cubicTo(w * 0.52f, h * 0.15f, w * 0.62f, h * 0.06f, w * 0.76f, h * 0.14f)
        path.cubicTo(w * 0.98f, h * 0.26f, w * 0.92f, h * 0.56f, w * 0.5f, h * 0.84f)
        path.close()

        fillPaint.color = if (liked) LIKE_RED else UNLIKED_FILL
        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)
    }

    private companion object {
        const val LIKE_RED = 0xFFFF2D55.toInt()
        const val UNLIKED_FILL = 0xCC111111.toInt()
    }
}

class CommentIconView(context: android.content.Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val bubble = RectF()
    private val tail = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = 6f
        bubble.set(inset, inset, width - inset, height * 0.72f)
        canvas.drawRoundRect(bubble, 12f, 12f, paint)

        tail.reset()
        tail.moveTo(width * 0.38f, height * 0.72f)
        tail.lineTo(width * 0.3f, height * 0.9f)
        tail.lineTo(width * 0.56f, height * 0.72f)
        canvas.drawPath(tail, paint)
    }
}
