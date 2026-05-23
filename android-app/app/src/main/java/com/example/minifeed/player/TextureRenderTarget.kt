package com.example.minifeed.player

import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class TextureRenderTarget(private val textureView: TextureView) : RenderTarget {
    private val mutableSurfaceEvents = MutableSharedFlow<SurfaceEvent>(extraBufferCapacity = 8)
    override val surfaceEvents: SharedFlow<SurfaceEvent> = mutableSurfaceEvents
    private var surface: Surface? = null

    init {
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                replaceSurface(Surface(texture))
            }

            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit

            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                val old = surface
                surface = null
                mutableSurfaceEvents.tryEmit(SurfaceEvent.Lost)
                old?.release()
                return true
            }

            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
        }
        if (textureView.isAvailable) {
            replaceSurface(Surface(textureView.surfaceTexture))
        }
    }

    override fun currentSurface(): Surface? = surface

    private fun replaceSurface(newSurface: Surface) {
        surface?.release()
        surface = newSurface
        mutableSurfaceEvents.tryEmit(SurfaceEvent.Available(newSurface))
    }
}
