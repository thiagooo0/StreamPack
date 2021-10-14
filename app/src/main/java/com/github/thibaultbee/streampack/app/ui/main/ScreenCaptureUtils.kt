package com.github.thibaultbee.streampack.app.ui.main

import android.app.Activity
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import com.github.thibaultbee.streampack.app.configuration.Configuration

class ScreenCaptureUtils(var configuration: Configuration) {
    private var virtualDisplay: VirtualDisplay? = null

    fun start(mediaProjection: MediaProjection, density: Int, surface: Surface) {
        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screenshot",
                configuration.video.resolution.width,
                configuration.video.resolution.height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
        }
    }
}