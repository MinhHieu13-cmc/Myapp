package com.example.myapplication

import android.content.Context
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object TFLiteModelLoader {
    @Throws(IOException::class)
    fun loadModelFile(context: Context, modelFile: String): ByteBuffer {
        val inputStream: InputStream = context.assets.open(modelFile)
        val size: Int = inputStream.available()
        val buffer = ByteBuffer.allocateDirect(size)
        buffer.order(ByteOrder.nativeOrder())
        val bytes = ByteArray(size)
        inputStream.read(bytes)
        buffer.put(bytes)
        inputStream.close()
        buffer.rewind()
        return buffer
    }
}
