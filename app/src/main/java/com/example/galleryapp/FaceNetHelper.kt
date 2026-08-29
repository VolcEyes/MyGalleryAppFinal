package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

//* PURPOSE: Converts a cropped face into a 512-dimensional vector.
class OnnxFaceHelper(context: Context) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

//* - WHY: The Buffalo_S MobileFaceNet architecture requires a strict
// * 112x112 pixel input tensor.
    private val imageSize = 112

    init {
        // Load the model from the assets folder
        // Runs the recognition model to turn an aligned face into a mathematical ID.
        val modelBytes = context.assets.open("w600k_mbf.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    /**
     * Function: getFaceVector(alignedFace: Bitmap)
     * How it works: Takes the 112x112 perfectly aligned face and passes it through the MobileFaceNet
     * model to generate a 512-dimensional array of floats (an "embedding" or "vector").
     * Why it's needed: This vector is the mathematical representation or "fingerprint" of the face.
     * You cannot reliably compare raw pixels to recognize someone.
     */

    fun getFaceVector(alignedFace: Bitmap): FloatArray? {

        //A safety measure. Even though FaceAligner outputs 112x112,
        // * this ensures the bitmap passed to the buffer is strictly the correct
        // * size to prevent memory crashes.
        val scaledBitmap = alignedFace.scale(imageSize, imageSize, false)

        // 1. Convert to NCHW FloatBuffer
        val floatBuffer = convertBitmapToNchwBuffer(scaledBitmap)

        // 2. Create the tensor: Shape is [1, 3, 112, 112]
        val shape = longArrayOf(1, 3, imageSize.toLong(), imageSize.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

        // 3. Run Inference
        val result = ortSession?.run(mapOf("input.1" to tensor))
        // 4. Extract the 512-dimensional output embedding
        val outputData = result?.get(0)?.value as? Array<FloatArray>
        val embedding = outputData?.get(0)

        // FREE MEMORY
        tensor.close()
        result?.close()
        scaledBitmap.recycle()

        return embedding
    }

    private fun convertBitmapToNchwBuffer(bitmap: Bitmap): FloatBuffer {
        // a) Creates a FloatBuffer of size 3 * 112 * 112.
        val floatBuffer = FloatBuffer.allocate(3 * imageSize * imageSize)
        val pixels = IntArray(imageSize * imageSize)
        bitmap.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)

        // NCHW separation: Calculate starting indices for Red, Green, and Blue channels
        val rOffset = 0
        val gOffset = imageSize * imageSize
        val bOffset = 2 * imageSize * imageSize

        for (i in pixels.indices) {
            val color = pixels[i]

            // b) Extract RGB and normalize
            // Normalizes the RGB values using `(color - 127.5f) / 128.0f`.
            val r = (((color shr 16) and 0xFF) - 127.5f) / 128.0f
            val g = (((color shr 8) and 0xFF) - 127.5f) / 128.0f
            val b = ((color and 0xFF) - 127.5f) / 128.0f

            // c) Place into the buffer at the correct NCHW offset
            // Places Red values at the beginning (`rOffset`), Greens in the
            // * middle (`gOffset`), and Blues at the end (`bOffset`).
            floatBuffer.put(rOffset + i, r)
            floatBuffer.put(gOffset + i, g)
            floatBuffer.put(bOffset + i, b)

            //WHY: Maps the 0-255 color range to a -1.0 to 1.0 scale. Separates
            // * the interleaved RGB pixels (RGBRGBRGB...) into planar channels
            // * (RRR...GGG...BBB...) also known as NCHW format [Batch=1, Channels=3,
            // * Height=112, Width=112], which is strictly required by the ONNX model.
        }
        return floatBuffer
    }

    fun close() {
        ortSession?.close()
        ortEnv.close()
    }
}