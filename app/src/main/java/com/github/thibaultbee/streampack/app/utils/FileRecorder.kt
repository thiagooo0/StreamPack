package com.github.thibaultbee.streampack.app.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class FileRecorder {
    private var fileOutputStream: FileOutputStream? = null

    var file: File? = null
        set(value) {
            fileOutputStream = FileOutputStream(value, false)
        }

    fun write(byteBuffer: ByteBuffer) {
        fileOutputStream?.channel?.write(byteBuffer)
    }

    fun write(byteArray: ByteArray) {
        fileOutputStream?.write(byteArray)
    }
}