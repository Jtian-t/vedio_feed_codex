package com.example.minifeed

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.PagerSnapHelper
import androidx.recyclerview.widget.RecyclerView
import com.example.minifeed.cache.MemoryDiskCacheStore
import com.example.minifeed.cache.PreloadScheduler
import com.example.minifeed.data.CatalogRepository
import com.example.minifeed.data.SharedPreferencesLocalSocialStore
import com.example.minifeed.network.BandwidthEstimator
import com.example.minifeed.network.OkHttpRangeFetcher
import com.example.minifeed.perf.InMemoryMetricsSink
import com.example.minifeed.player.MediaCodecMiniPlayer
import com.example.minifeed.proxy.SimpleLocalProxyServer
import com.example.minifeed.scheduler.PlayerSlotCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: VideoFeedAdapter
    private lateinit var coordinator: PlayerSlotCoordinator
    private var createdOnce = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val metrics = InMemoryMetricsSink()
        val bandwidthEstimator = BandwidthEstimator()
        val cacheStore = MemoryDiskCacheStore(this, metrics)
        val rangeFetcher = OkHttpRangeFetcher(metrics, bandwidthEstimator)
        val proxyServer = SimpleLocalProxyServer(cacheStore, rangeFetcher, metrics)
        val preloadScheduler = PreloadScheduler(cacheStore, bandwidthEstimator)
        val socialStore = SharedPreferencesLocalSocialStore(this)

        val loadResult = CatalogRepository(this).loadGeneratedFeed()
        if (loadResult.validationErrors.isNotEmpty()) {
            Toast.makeText(this, loadResult.validationErrors.first(), Toast.LENGTH_LONG).show()
        }
        coordinator = PlayerSlotCoordinator(
            items = loadResult.items,
            preloadScheduler = preloadScheduler,
            proxyServer = proxyServer,
            playerFactory = { MediaCodecMiniPlayer(metrics) }
        )

        recyclerView = RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@MainActivity, RecyclerView.VERTICAL, false)
            itemAnimator = null
            setHasFixedSize(true)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }
        val snapHelper = PagerSnapHelper()
        snapHelper.attachToRecyclerView(recyclerView)
        adapter = VideoFeedAdapter(
            items = loadResult.items,
            coordinator = coordinator,
            socialStore = socialStore,
            lifecycleScope = scope
        )
        recyclerView.adapter = adapter
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    val snapView = snapHelper.findSnapView(rv.layoutManager) ?: return
                    val index = rv.layoutManager?.getPosition(snapView) ?: return
                    scope.launch { coordinator.onPageSelected(index) }
                }
            }
        })
        setContentView(recyclerView)
        recyclerView.post {
            scope.launch {
                val holder = recyclerView.findViewHolderForAdapterPosition(0) as? VideoFeedAdapter.VideoViewHolder
                holder?.pageHandle()?.let { coordinator.attachPage(it) }
                coordinator.onPageSelected(0)
                createdOnce = true
            }
        }
    }

    override fun onPause() {
        super.onPause()
        scope.launch { coordinator.onAppBackgrounded() }
    }

    override fun onResume() {
        super.onResume()
        if (::coordinator.isInitialized && createdOnce) {
            scope.launch { coordinator.onAppForegrounded() }
        }
    }
}
