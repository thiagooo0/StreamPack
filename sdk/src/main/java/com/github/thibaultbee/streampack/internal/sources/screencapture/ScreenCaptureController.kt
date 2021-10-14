package com.github.thibaultbee.streampack.internal.sources.screencapture

import android.Manifest
import android.content.Context
import android.hardware.camera2.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.github.thibaultbee.streampack.data.VideoConfig
import com.github.thibaultbee.streampack.error.CameraError
import com.github.thibaultbee.streampack.internal.sources.camera.CameraExecutorManager
import com.github.thibaultbee.streampack.internal.sources.camera.CameraHandlerManager
import com.github.thibaultbee.streampack.logger.ILogger
import kotlinx.coroutines.*
import java.security.InvalidParameterException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScreenCaptureController(
    private val context: Context,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val logger: ILogger
) {
    private var virtualDisplay: VirtualDisplay? = null

    private var width: Int = 860
    private var height: Int = 480

    fun configure(config: VideoConfig) {
        width = config.resolution.width
        height = config.resolution.height
    }

    fun startScreenCapture(mediaProjection: MediaProjection, density: Int, surface: Surface) {
        Log.d("srt","startScreenCapture =================")
        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "Screenshot",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                surface,
                null,
                null
            )
        }
    }

    fun stopCamera() {
        virtualDisplay?.release()
    }
}