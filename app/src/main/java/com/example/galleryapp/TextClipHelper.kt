package com.example.galleryapp

import android.content.Context
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer
import java.util.Collections

class TextClipHelper(context: Context) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null
    private var tokenizer: ClipTokenizer? = null

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()

            // Safe Loading: Copy from assets to internal storage using streams (avoids OOM)
            val modelFile = File(context.filesDir, "text_model.ort")
            if (!modelFile.exists()) {
                context.assets.open("text_model.ort").use { inputStream ->
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream) // Copies in small chunks
                    }
                }
            }

            // Load the model using its absolute path instead of a massive ByteArray
            ortSession = ortEnv?.createSession(modelFile.absolutePath, options)

            // Initialize our Tokenizer
            tokenizer = ClipTokenizer(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getTextVector(text: String): FloatArray? {
        if (ortSession == null || ortEnv == null || tokenizer == null) return null

        return try {
            val tokens = tokenizer!!.tokenize(text)

            val inputTensor = OnnxTensor.createTensor(
                ortEnv,
                LongBuffer.wrap(tokens),
                longArrayOf(1, 77)
            )

            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "text"
            val results = ortSession?.run(Collections.singletonMap(inputName, inputTensor))

            val output = results?.get(0)?.value as Array<FloatArray>

            inputTensor.close()
            results?.close()

            return output[0]
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
    }
}