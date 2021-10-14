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
package com.github.thibaultbee.streampack.streamers

import android.Manifest
import android.content.Context
import android.media.projection.MediaProjection
import android.util.Log
import androidx.annotation.RequiresPermission
import com.github.thibaultbee.streampack.data.AudioConfig
import com.github.thibaultbee.streampack.data.VideoConfig
import com.github.thibaultbee.streampack.error.StreamPackError
import com.github.thibaultbee.streampack.internal.data.Frame
import com.github.thibaultbee.streampack.internal.data.Packet
import com.github.thibaultbee.streampack.internal.encoders.AudioMediaCodecEncoder
import com.github.thibaultbee.streampack.internal.encoders.IEncoderListener
import com.github.thibaultbee.streampack.internal.encoders.VideoMediaCodecEncoder
import com.github.thibaultbee.streampack.internal.endpoints.IEndpoint
import com.github.thibaultbee.streampack.internal.events.EventHandler
import com.github.thibaultbee.streampack.internal.muxers.IMuxerListener
import com.github.thibaultbee.streampack.internal.muxers.ts.TSMuxer
import com.github.thibaultbee.streampack.internal.muxers.ts.data.ServiceInfo
import com.github.thibaultbee.streampack.internal.sources.AudioCapture
import com.github.thibaultbee.streampack.internal.sources.screencapture.ScreenCapture
import com.github.thibaultbee.streampack.listeners.OnErrorListener
import com.github.thibaultbee.streampack.logger.ILogger
import com.github.thibaultbee.streampack.streamers.interfaces.IStreamer
import com.github.thibaultbee.streampack.utils.CameraStreamerConfigurationHelper
import com.github.thibaultbee.streampack.utils.FileRecorder
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer

/**
 * Base class of CaptureStreamer: [CameraTsFileStreamer] or [CameraSrtLiveStreamer]
 * Use this class, only if you want to implement a custom endpoint.
 *
 * @param context application context
 * @param tsServiceInfo MPEG-TS service description
 * @param endpoint a [IEndpoint] implementation
 * @param logger a [ILogger] implementation
 */
open class BaseScreenCaptureStreamer(
    private val context: Context,
    private val tsServiceInfo: ServiceInfo,
    protected val endpoint: IEndpoint,
    private val logger: ILogger
) : EventHandler(), IStreamer {
    /**
     * Listener that reports streamer error.
     * Supports only one listener.
     */
    var onErrorListener: OnErrorListener? = null

    private var audioTsStreamId: Short? = null
    private var videoTsStreamId: Short? = null

    // Keep video configuration
    private var videoConfig: VideoConfig? = null
    private var audioConfig: AudioConfig? = null

    private var srtBytesRecorder = FileRecorder(context, "srtBytes.h264")

    // Only handle stream error (error on muxer, endpoint,...)
    override var onInternalErrorListener = object : OnErrorListener {
        override fun onError(error: StreamPackError) {
            onStreamError(error)
        }
    }

    private val onInternalCodecErrorListener = object : OnErrorListener {
        override fun onError(error: StreamPackError) {
            onStreamError(error)
        }
    }

    private val audioEncoderListener = object : IEncoderListener {
        override fun onInputFrame(buffer: ByteBuffer): Frame {
            return audioSource.getFrame(buffer)
        }

        override fun onOutputFrame(frame: Frame) {
            audioTsStreamId?.let {
                try {
                    tsMux.encode(frame, it)
                } catch (e: Exception) {
                    throw StreamPackError(e)
                }
            }
        }
    }

    private var isStartTsMux = false
    private val videoEncoderListener = object : IEncoderListener {
        override fun onInputFrame(buffer: ByteBuffer): Frame {
            // Not needed for video
            throw StreamPackError(RuntimeException("No video input on VideoEncoder"))
        }

        override fun onOutputFrame(frame: Frame) {
            videoTsStreamId?.let {
                try {
                    frame.pts += videoSource.timestampOffset
                    frame.dts = if (frame.dts != null) {
                        frame.dts!! + videoSource.timestampOffset
                    } else {
                        null
                    }

                    tsMux.encode(frame, it)
                } catch (e: Exception) {
                    // Send exception to encoder
                    throw StreamPackError(e)
                }
            }
        }
    }

    private val muxListener = object : IMuxerListener {
        override fun onOutputFrame(packet: Packet) {
            try {
                srtBytesRecorder.write(packet.buffer)
                packet.buffer.rewind()
                Log.d("srt", "receive ${packet.buffer.limit()} bytes")
                endpoint.write(packet)
            } catch (e: Exception) {
                // Send exception to encoder
                throw StreamPackError(e)
            }
        }
    }

    /**
     * Manages error on stream.
     * Stops only stream.
     *
     * @param error triggered [StreamPackError]
     */
    private fun onStreamError(error: StreamPackError) {
        try {
            stopStream()
            onErrorListener?.onError(error)
        } catch (e: Exception) {
            logger.e(this, "onStreamError: Can't stop stream")
        }
    }

    private val audioSource = AudioCapture(logger)
    private val videoSource =
        ScreenCapture(context, logger = logger)

    private var audioEncoder =
        AudioMediaCodecEncoder(audioEncoderListener, onInternalCodecErrorListener, logger)
    private var videoEncoder =
        VideoMediaCodecEncoder(
            videoEncoderListener,
            onInternalCodecErrorListener,
            context,
            logger
        )

    protected var audioBitrate: Int
        get() = audioEncoder.bitrate
        set(value) {
            audioEncoder.bitrate = value
        }
    protected var videoBitrate: Int
        get() = videoEncoder.bitrate
        set(value) {
            videoEncoder.bitrate = value
        }


    private val tsMux = TSMuxer(context, muxListener)

    /**
     * Configures both video and audio settings.
     * It is the first method to call after a [BaseScreenCaptureStreamer] instantiation.
     * It must be call when both stream and capture are not running.
     *
     * Use [CameraStreamerConfigurationHelper] to get value limits.
     *
     * If video encoder does not support [VideoConfig.level] or [VideoConfig.profile], it fallbacks
     * to video encoder default level and default profile.
     *
     * Inside, it creates most of record and encoders object.
     *
     * @param audioConfig Audio configuration to set
     * @param videoConfig Video configuration to set
     *
     * @throws [StreamPackError] if configuration can not be applied.
     * @see [release]
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun configure(audioConfig: AudioConfig, videoConfig: VideoConfig) {
        // Keep settings when we need to reconfigure
        this.videoConfig = videoConfig
        this.audioConfig = audioConfig

        try {
            audioSource.configure(audioConfig)
            audioEncoder.configure(audioConfig)
            videoSource.configure(videoConfig)
            videoEncoder.configure(videoConfig)

            endpoint.configure(videoConfig.startBitrate + audioConfig.startBitrate)
        } catch (e: Exception) {
            release()
            throw StreamPackError(e)
        }
    }


    fun startScreenCapture(
        mediaProjection: MediaProjection,
        density: Int
    ) {
        require(audioConfig != null) { "Audio has not been configured!" }
        require(videoConfig != null) { "Video has not been configured!" }

        runBlocking {
            try {
                videoSource.encoderSurface = videoEncoder.inputSurface
                videoSource.startScreenCapture(mediaProjection, density)

                audioSource.startStream()
            } catch (e: Exception) {
                stopScreenCapture()
                throw StreamPackError(e)
            }
        }
    }

    fun stopScreenCapture() {
        stopStreamImpl()
        videoSource.stopScreenCapture()
        audioSource.stopStream()
    }

    /**
     * Starts audio/video stream.
     * Stream depends of the endpoint: Audio/video could be write to a file or send to a remote
     * device.
     * To avoid creating an unresponsive UI, do not call on main thread.
     *
     * @see [stopStream]
     */
    override fun startStream() {
        require(audioConfig != null) { "Audio has not been configured!" }
        require(videoConfig != null) { "Video has not been configured!" }
        require(videoEncoder.mimeType != null) { "Missing video encoder mime type! Encoder not configured?" }
        require(audioEncoder.mimeType != null) { "Missing audio encoder mime type! Encoder not configured?" }

        try {
            endpoint.startStream()

            val streams = mutableListOf<String>()
            videoEncoder.mimeType?.let { streams.add(it) }
            audioEncoder.mimeType?.let { streams.add(it) }

            tsMux.addService(tsServiceInfo)
            tsMux.addStreams(tsServiceInfo, streams)
            videoEncoder.mimeType?.let { videoTsStreamId = tsMux.getStreams(it)[0].pid }
            audioEncoder.mimeType?.let { audioTsStreamId = tsMux.getStreams(it)[0].pid }

            audioEncoder.startStream()
            videoSource.startStream()
            videoEncoder.startStream()
        } catch (e: Exception) {
            stopStream()
            throw StreamPackError(e)
        }
    }

    /**
     * Stops audio/video stream.
     *
     * Internally, it resets audio and video recorders and encoders to get them ready for another
     * [startStream] session. It explains why camera is restarted when calling this method.
     *
     * @see [startStream]
     */
    override fun stopStream() {
        stopStreamImpl()

        // Encoder does not return to CONFIGURED state... so we have to reset everything for video...
        resetAudio()
        resetVideo()
    }

    /**
     * Stops audio/video stream implementation.
     *
     * @see [stopStream]
     */
    private fun stopStreamImpl() {
        videoSource.stopStream()
        videoEncoder.stopStream()
        audioEncoder.stopStream()

        tsMux.stop()

        endpoint.stopStream()
    }

    /**
     * Prepares audio encoder for another session
     *
     * @see [stopStream]
     */
    private fun resetAudio() {
        if (audioConfig == null) {
            logger.w(this, "Audio has not been configured!")
            return
        }

        audioEncoder.release()

        // Reconfigure
        audioEncoder.configure(audioConfig!!)
    }

    /**
     * Prepares camera and video encoder for another session
     *
     * @see [stopStream]
     */
    private fun resetVideo() {
        if (videoConfig == null) {
            logger.w(this, "Video has not been configured!")
            return
        }

        val restartPreview = videoSource.isPreviewing
        videoSource.stopScreenCapture()
        videoEncoder.release()

        // And restart...
        runBlocking {
            videoEncoder.configure(videoConfig!!)
            videoSource.encoderSurface = videoEncoder.inputSurface
            if (restartPreview) {
                videoSource.restartScreenCapture()
            }
        }
    }

    /**
     * Releases recorders and encoders object.
     * It also stops preview if needed
     *
     * @see [configure]
     */
    fun release() {
        stopScreenCapture()
        audioEncoder.release()
        videoEncoder.release()
        audioSource.release()
        videoSource.release()
        endpoint.release()
    }
}