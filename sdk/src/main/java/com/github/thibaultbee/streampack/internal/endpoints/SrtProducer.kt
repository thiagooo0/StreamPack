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
package com.github.thibaultbee.streampack.internal.endpoints

import android.content.Context
import android.util.Log
import com.github.thibaultbee.srtdroid.Srt
import com.github.thibaultbee.srtdroid.enums.Boundary
import com.github.thibaultbee.srtdroid.enums.ErrorType
import com.github.thibaultbee.srtdroid.enums.SockOpt
import com.github.thibaultbee.srtdroid.enums.Transtype
import com.github.thibaultbee.srtdroid.listeners.SocketListener
import com.github.thibaultbee.srtdroid.models.MsgCtrl
import com.github.thibaultbee.srtdroid.models.Socket
import com.github.thibaultbee.srtdroid.models.Stats
import com.github.thibaultbee.streampack.internal.data.Packet
import com.github.thibaultbee.streampack.listeners.OnConnectionListener
import com.github.thibaultbee.streampack.logger.ILogger
import com.github.thibaultbee.streampack.utils.FileRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.InetSocketAddress

class SrtProducer(
    var context: Context,
    private val coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
    val logger: ILogger
) : IEndpoint {
    var onConnectionListener: OnConnectionListener? = null
    val TAG = this::class.java.simpleName
    private var socket = Socket()
    private var bitrate = 0L

    private val fileRecorder = FileRecorder(context, "srt")

    companion object {
        private const val PAYLOAD_SIZE = 1316
    }

    /**
     * Get/set SRT stream ID
     */
    var streamId: String
        get() {
            val id = socket.getSockFlag(SockOpt.STREAMID) as String
            Log.i(TAG, "get streamId: $id")
            return id
        }
        set(value) {
            Log.i(TAG, "set streamId: $value")
            socket.setSockFlag(SockOpt.STREAMID, value)
        }

    /**
     * Get/set SRT stream passPhrase
     */
    var passPhrase: String
        get() = socket.getSockFlag(SockOpt.PASSPHRASE) as String
        set(value) {
            Log.i(TAG, "set passPhrase: $value")
            socket.setSockFlag(SockOpt.PASSPHRASE, value)
        }


    /**
     * Get SRT stats
     */
    val stats: Stats
        get() {
            val stat = socket.bistats(clear = true, instantaneous = true)
            Log.i(TAG, "get stats : $stat")
            return stat
        }

    override fun configure(startBitrate: Int) {
        Log.i(TAG, "configure : $startBitrate")
        this.bitrate = startBitrate.toLong()
    }

    suspend fun connect(ip: String, port: Int) = withContext(coroutineDispatcher) {
        Log.i(TAG, "connect : $ip : $port")
        try {
            socket.listener = object : SocketListener {
                override fun onConnectionLost(
                    ns: Socket,
                    error: ErrorType,
                    peerAddress: InetSocketAddress,
                    token: Int
                ) {
                    Log.i(TAG, "onConnectionLost")
                    socket = Socket()
                    onConnectionListener?.onLost(error.toString())
                }

                override fun onListen(
                    ns: Socket,
                    hsVersion: Int,
                    peerAddress: InetSocketAddress,
                    streamId: String
                ) = 0 // Only for server - not needed here
            }
            socket.setSockFlag(SockOpt.PAYLOADSIZE, PAYLOAD_SIZE)
            socket.setSockFlag(SockOpt.TRANSTYPE, Transtype.LIVE)
            socket.connect(ip, port)
            onConnectionListener?.onSuccess()
        } catch (e: Exception) {
            Log.i(TAG, e.toString())
            socket = Socket()
            onConnectionListener?.onFailed(e.message ?: "Unknown error")
            throw e
        }
    }

    fun disconnect() {
        Log.i(TAG, "disconnect")
        socket.close()
        socket = Socket()
    }

    var count = 0
    override fun write(packet: Packet) {
        if (++count == 5) {
            Log.i(TAG, "write five bytes")
        }
        Log.i(TAG, "write ${packet.buffer.limit()} bytes")

        val boundary = when {
            packet.isFirstPacketFrame && packet.isLastPacketFrame -> Boundary.SOLO
            packet.isFirstPacketFrame -> Boundary.FIRST
            packet.isLastPacketFrame -> Boundary.LAST
            else -> Boundary.SUBSEQUENT
        }
        val msgCtrl =
            if (packet.ts == 0L) {
                Log.i(TAG, "ts = 0")
                MsgCtrl(boundary = boundary)
            } else {
                MsgCtrl(
                    ttl = 500,
                    srcTime = packet.ts,
                    boundary = boundary
                )
            }
        fileRecorder.write(packet.buffer)
        packet.buffer.rewind()
        socket.send(packet.buffer, msgCtrl)
    }

    override fun startStream() {
        Log.i(TAG, "startStream")
        if (!socket.isConnected) {
            throw ConnectException("SrtEndpoint should be connected at this point")
        }

        socket.setSockFlag(SockOpt.MAXBW, 0L)
        socket.setSockFlag(SockOpt.INPUTBW, bitrate)
    }

    override fun stopStream() {
        Log.i(TAG, "stopStream")
    }

    override fun release() {
        Log.i(TAG, "release")
        Srt.cleanUp()
    }
}