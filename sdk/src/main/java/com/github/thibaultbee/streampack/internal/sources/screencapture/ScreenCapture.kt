/*
 * Copyright (C) 2021 Thibault B.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.thibaultbee.streampack.internal.sources.screencapture

import android.content.Context
import android.media.projection.MediaProjection
import android.view.Surface
import com.github.thibaultbee.streampack.data.VideoConfig
import com.github.thibaultbee.streampack.internal.interfaces.Streamable
import com.github.thibaultbee.streampack.internal.sources.camera.CameraHelper
import com.github.thibaultbee.streampack.logger.ILogger

class ScreenCapture(
    context: Context,
    logger: ILogger
) : Streamable<VideoConfig> {
    var encoderSurface: Surface? = null

    private var screenCaptureController = ScreenCaptureController(context, logger = logger)

    /**
     * As timestamp source differs from one camera to another. Computes an offset.
     */
    val timestampOffset = CameraHelper.getTimeOffsetToMonoClock(context)

    private var isStreaming = false
    internal var isPreviewing = false

    var mediaProjection: MediaProjection? = null

    var density: Int = 0

    override fun configure(config: VideoConfig) {
        screenCaptureController.configure(config)
    }

    suspend fun startScreenCapture(
        mediaProjection: MediaProjection,
        density: Int

    ) {
        this.mediaProjection = mediaProjection
        this.density = density
        encoderSurface?.let {
            screenCaptureController.startScreenCapture(mediaProjection, density, it)
        }
        isPreviewing = true
    }

    suspend fun restartScreenCapture() {
        encoderSurface?.let {
            screenCaptureController.startScreenCapture(mediaProjection!!, density, it)
        }
        isPreviewing = true
    }

    fun stopScreenCapture() {
        isPreviewing = false
        screenCaptureController.stopCamera()
    }

    private fun checkStream() =
        require(encoderSurface != null) { "encoder surface must not be null" }

    override fun startStream() {
        checkStream()
        isStreaming = true
    }

    override fun stopStream() {
        if (isStreaming) {
            checkStream()
            isStreaming = false
        }
    }

    override fun release() {
        stopStream()
    }

}