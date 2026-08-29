package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.FileOutputStream
import java.nio.FloatBuffer
import java.util.Collections

//This file extracts embeddings from general images.

class MobileClipHelper(context: Context) {
    private var ortEnv: OrtEnvironment? = null
    private var ortSession: OrtSession? = null

    init {
        try {
            ortEnv = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()

            val modelFile = File(context.filesDir, "vision_model.ort")

            // FIX 1: If the file exists but is suspiciously small (< 1MB), it's corrupted from a crash.
            // We force it to overwrite.
            if (!modelFile.exists() || modelFile.length() < 1000000L) {
                Log.d("MobileClipHelper", "Extracting vision_model.ort from assets...")
                context.assets.open("vision_model.ort").use { inputStream ->
                    FileOutputStream(modelFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            }

            ortSession = ortEnv?.createSession(modelFile.absolutePath, options)
            Log.d("MobileClipHelper", "Vision Model loaded successfully!")
        } catch (e: Exception) {
            Log.e("MobileClipHelper", "Failed to initialize vision model", e)
        }
    }

    fun getImageVector(bitmap: Bitmap): FloatArray? {
        if (ortSession == null || ortEnv == null) return null

        return try {
            //The MobileCLIP model architecture uses a strict fixed input size
            //of 256x256 pixels. Passing any other size will cause an ONNX crash.
            val resizedBitmap = Bitmap.createScaledBitmap(bitmap, 256, 256, true)
            val floatBuffer = preprocessImage(resizedBitmap)

            // Update tensor shape to match 256x256
            val inputTensor = OnnxTensor.createTensor(ortEnv, floatBuffer, longArrayOf(1, 3, 256, 256))

            val inputName = ortSession?.inputNames?.iterator()?.next() ?: "image"
            val results = ortSession?.run(Collections.singletonMap(inputName, inputTensor))

            val output = results?.get(0)?.value as Array<FloatArray>

            inputTensor.close()
            results?.close()

            return output[0]
        } catch (e: Exception) {
            // FIX 3: Print the exact error to Logcat instead of failing silently!
            Log.e("MobileClipHelper", "Inference Failed during getImageVector", e)
            null
        }
    }

    private fun preprocessImage(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        // a) Extracts pixel arrays.
        // Extracts all the pixels from the Android Bitmap into a standard flat 1D integer array.
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // c) Applies Z-Score Normalization using specific arrays:
        val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
        val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

        val floatBuffer = FloatBuffer.allocate(3 * width * height)

        for (i in 0 until width * height) {
            val pixel = pixels[i]

            // STEP B: Converts standard 0-255 RGB values to a 0.0-1.0 float scale.
            // The bitwise operations (shr, and 0xFF) isolate the Red, Green, and Blue
            // integer values. Dividing by 255.0f converts them to the 0.0-1.0 float scale.
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f

            // STEP C: Applies Z-Score Normalization using specific arrays.
            // The mathematical calculation `(r - mean[0]) / std[0]` is the direct
            // application of the Z-score formula using the arrays defined above.
            // --- combined with ---

            // STEP D: Restructures the data sequentially into planar format (NCHW).
            // Notice the buffer index calculations.
            // - Red values are placed at the beginning: `i`
            // - Green values are shifted by one full image size: `i + width * height`
            // - Blue values are shifted by two full image sizes: `i + 2 * width * height`

//            * - WHY: Machine learning models are trained on datasets that have been
//            * statistically normalized using those exact mean and standard deviation
//            * values. If you don't normalize the input image the exact same way,
//            * the model will output garbage vectors. Restructuring to planar
//            * (NCHW format) is required by the ONNX C++ engine.

            floatBuffer.put(i, (r - mean[0]) / std[0])
            floatBuffer.put(i + width * height, (g - mean[1]) / std[1])
            floatBuffer.put(i + 2 * width * height, (b - mean[2]) / std[2])
        }
        floatBuffer.rewind()
        return floatBuffer
    }

    fun close() {
        ortSession?.close()
        ortEnv?.close()
    }
}