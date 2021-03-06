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
package com.github.thibaultbee.streampack.app.ui.main

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.projection.MediaProjection
import android.os.Environment
import android.util.Log
import android.util.Range
import android.view.Surface
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.github.thibaultbee.streampack.app.configuration.Configuration
import com.github.thibaultbee.streampack.app.configuration.Configuration.Endpoint.EndpointType
import com.github.thibaultbee.streampack.data.AudioConfig
import com.github.thibaultbee.streampack.data.BitrateRegulatorConfig
import com.github.thibaultbee.streampack.data.VideoConfig
import com.github.thibaultbee.streampack.error.StreamPackError
import com.github.thibaultbee.streampack.internal.muxers.ts.data.ServiceInfo
import com.github.thibaultbee.streampack.listeners.OnConnectionListener
import com.github.thibaultbee.streampack.listeners.OnErrorListener
import com.github.thibaultbee.streampack.regulator.DefaultSrtBitrateRegulatorFactory
import com.github.thibaultbee.streampack.streamers.*
import kotlinx.coroutines.launch
import java.io.File

class ScreenCaptureModel(application: Application) : AndroidViewModel(application) {
    private val TAG = this::class.java.simpleName

    private val configuration = Configuration(getApplication())

    private lateinit var streamer: BaseScreenCaptureStreamer

    val streamerError = MutableLiveData<String>()

    val streamAdditionalPermissions: List<String>
        get() {
            val permissions = mutableListOf(
                Manifest.permission.RECORD_AUDIO
            )
            return permissions
        }

    fun createStreamer() {
        viewModelScope.launch {
            val tsServiceInfo = ServiceInfo(
                ServiceInfo.ServiceType.DIGITAL_TV,
                0x4698,
                configuration.muxer.service,
                configuration.muxer.provider
            )

            try {
                val streamerBuilder = if (configuration.endpoint.enpointType == EndpointType.SRT) {
                    ScreenCaptureSrtLiveStreamer.Builder().setBitrateRegulator(
                        if (configuration.endpoint.connection.enableBitrateRegulation) {
                            DefaultSrtBitrateRegulatorFactory()
                        } else {
                            null
                        },
                        if (configuration.endpoint.connection.enableBitrateRegulation) {
                            BitrateRegulatorConfig.Builder()
                                .setVideoBitrateRange(configuration.endpoint.connection.videoBitrateRange)
                                .setAudioBitrateRange(
                                    Range(
                                        configuration.audio.bitrate,
                                        configuration.audio.bitrate
                                    )
                                )
                                .build()
                        } else {
                            null
                        },
                    )
                } else {
                    CameraTsFileStreamer.Builder()
                }

                val videoConfig = VideoConfig.Builder()
                    .setMimeType(configuration.video.encoder)
                    .setStartBitrate(configuration.video.bitrate * 1000)  // to b/s
                    .setResolution(configuration.video.resolution)
                    .setFps(configuration.video.fps)
                    .build()

                val audioConfig = AudioConfig.Builder()
                    .setMimeType(configuration.audio.encoder)
                    .setStartBitrate(configuration.audio.bitrate)
                    .setSampleRate(configuration.audio.sampleRate)
                    .setNumberOfChannel(configuration.audio.numberOfChannels)
                    .setByteFormat(configuration.audio.byteFormat)
                    .setEchoCanceler(configuration.audio.enableEchoCanceler)
                    .setNoiseSuppressor(configuration.audio.enableNoiseSuppressor)
                    .build()

                streamer = streamerBuilder
                    .setContext(getApplication())
                    .setServiceInfo(tsServiceInfo)
                    .setConfiguration(audioConfig, videoConfig)
                    .build() as BaseScreenCaptureStreamer

                streamer.onErrorListener = object : OnErrorListener {
                    override fun onError(error: StreamPackError) {
                        streamerError.postValue("${error.javaClass.simpleName}: ${error.message}")
                    }
                }

                if (streamer is ScreenCaptureSrtLiveStreamer) {
                    (streamer as ScreenCaptureSrtLiveStreamer).onConnectionListener =
                        object : OnConnectionListener {
                            override fun onLost(message: String) {
                                streamerError.postValue("Connection lost: $message")
                            }

                            override fun onFailed(message: String) {
                                // Not needed as we catch startStream
                            }

                            override fun onSuccess() {
                                Log.i(TAG, "Connection succeeded")
                            }
                        }
                }
                Log.d(TAG, "Streamer is created")
            } catch (e: Throwable) {
                Log.e(TAG, "createStreamer failed", e)
                streamerError.postValue("createStreamer: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun startScreenCapture(
        mediaProjection: MediaProjection,
        density: Int
    ) {
        viewModelScope.launch {
            try {
                streamer.startScreenCapture(mediaProjection, density)
            } catch (e: Throwable) {
                Log.e(TAG, "startPreview failed", e)
                streamerError.postValue("startPreview: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun stopScreenCapture() {
        viewModelScope.launch {
            try {
                streamer.stopScreenCapture()
            } catch (e: Throwable) {
                Log.e(TAG, "stopPreview failed", e)
            }
        }
    }

    fun startStream() {
        Log.i(TAG, "startStream")
        viewModelScope.launch {
            try {
                val captureSrtLiveStreamer = streamer as ScreenCaptureSrtLiveStreamer
                captureSrtLiveStreamer.streamId = configuration.endpoint.connection.streamID
                captureSrtLiveStreamer.passPhrase = configuration.endpoint.connection.passPhrase
                captureSrtLiveStreamer.connect(
                    configuration.endpoint.connection.ip,
                    configuration.endpoint.connection.port
                )
                streamer.startStream()
            } catch (e: Throwable) {
                Log.e(TAG, "startStream failed", e)
                streamerError.postValue("startStream: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun stopStream() {
        Log.i(TAG, "stopStream")
        viewModelScope.launch {
            try {
                streamer.stopStream()
                if (streamer is ScreenCaptureSrtLiveStreamer) {
                    (streamer as ScreenCaptureSrtLiveStreamer).disconnect()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "stopStream failed", e)
            }
        }
    }


    override fun onCleared() {
        super.onCleared()
        try {
            streamer.release()
        } catch (e: Exception) {
            Log.e(TAG, "streamer.release failed", e)
        }
    }
}
