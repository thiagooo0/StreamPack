package com.github.thibaultbee.streampack.utils

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

class FileRecorder {
    private var fileOutputStream: FileOutputStream? = null

    var file: File? = null
        set(value) {
            fileOutputStream = FileOutputStream(value, false)
        }

    constructor(file: File?) {
        this.file = file
    }

    constructor(context: Context, name: String) {
        file = File(context.getExternalFilesDir("video"), name)
    }

    fun write(byteBuffer: ByteBuffer) {
        fileOutputStream?.channel?.write(byteBuffer)
    }

    fun write(byteArray: ByteArray) {
        fileOutputStream?.write(byteArray)
    }
}