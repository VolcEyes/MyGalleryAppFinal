package com.example.galleryapp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint

//* PURPOSE: Standardizes the angle and position of a detected face.
object FaceAligner {
// A float array of 5 X,Y coordinates representing the "ideal"
// * locations of the left eye, right eye, nose, and mouth corners on
// * a perfect 112x112 canvas.
    // - WHY: Used as the absolute target destination for the alignment math.
    private val REFERENCE_POINTS = floatArrayOf(
        38.2946f, 51.6963f,
        73.5318f, 51.5014f,
        56.0252f, 71.7366f,
        41.5493f, 92.3655f,
        70.7299f, 92.2041f
    )

    /**
     * @param originalBitmap The large, uncropped image
     * @param srcPoints FloatArray of size 10: [leftEyeX, leftEyeY, rightEyeX, rightEyeY, noseX, noseY, leftMouthX, leftMouthY, rightMouthX, rightMouthY]
     * @return A perfectly aligned 112x112 bitmap ready for Buffalo_S
     */

    //HOW: Creates a blank 112x112 canvas. It takes the original full-size photo
    //and draws it onto the tiny canvas through the calculated transform matrix,
    //effectively rotating the face upright, zooming in on it, and cropping
    //it perfectly in one step.OR How it works: Takes the 5 keypoints (eyes, nose, mouth)
    //found by SCRFD and uses a mathematical
    //algorithm to rotate, scale, and crop the face into a strict 112x112 pixel square.
    //- WHY: Facial recognition models cannot accurately compare faces if one
    //person is looking slightly down or their head is tilted. Alignment
    //ensures the eyes are always perfectly horizontal and in the exact
    //same pixel coordinates before recognition happens.
    fun alignAndCrop(originalBitmap: Bitmap, srcPoints: FloatArray): Bitmap {
        // Use Umeyama algorithm to get a strict Similarity Transform matrix
        val matrix = calculateSimilarityTransform(srcPoints, REFERENCE_POINTS)

        // Create the 112x112 target bitmap directly
        val alignedBitmap = Bitmap.createBitmap(112, 112, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(alignedBitmap)

        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true // Smooths out the pixels during the warp
        }

        // Draw the original image onto the 112x112 canvas using the calculated warp matrix
        canvas.drawBitmap(originalBitmap, matrix, paint)

        return alignedBitmap
    }

    /**
     * Computes a 2D Similarity Transform (scale, rotation, translation)
     * without shearing, using the Umeyama least-squares algorithm.
     */

    //- HOW: Uses the Umeyama least-squares algorithm to compare the actual
    // * facial keypoints (from ScrfdHelper) against the REFERENCE_POINTS.
    // * It computes an Android graphics Matrix containing the exact rotation,
    // * scale, and translation needed to match them up.
    // * - WHY: Needed because standard Android Matrix calculations don't easily
    // * handle mapping 5 arbitrary points to 5 target points simultaneously
    // * without distorting (shearing) the image.
    private fun calculateSimilarityTransform(src: FloatArray, dst: FloatArray): Matrix {
        val numPoints = src.size / 2

        var srcMeanX = 0f
        var srcMeanY = 0f
        var dstMeanX = 0f
        var dstMeanY = 0f

        // 1. Find the centroids of both point sets
        for (i in 0 until numPoints) {
            srcMeanX += src[i * 2]
            srcMeanY += src[i * 2 + 1]
            dstMeanX += dst[i * 2]
            dstMeanY += dst[i * 2 + 1]
        }

        srcMeanX /= numPoints
        srcMeanY /= numPoints
        dstMeanX /= numPoints
        dstMeanY /= numPoints

        // 2. Subtract centroids and calculate variance/covariance
        var sigmaX = 0f
        var sigmaY = 0f
        var variance = 0f

        for (i in 0 until numPoints) {
            val srcX = src[i * 2] - srcMeanX
            val srcY = src[i * 2 + 1] - srcMeanY
            val dstX = dst[i * 2] - dstMeanX
            val dstY = dst[i * 2 + 1] - dstMeanY

            //Dot product of 2 vectors. It measures alignment "How much do these two vectors
            //point in the exact same direction?"This accumulator helps the algorithm figure out the Scale
            sigmaX += (srcX * dstX + srcY * dstY)
            //2D Cross Product
            sigmaY += (srcX * dstY - srcY * dstX)
            //The pythagorean theorem (a^2 + b^2 = c^2).
            //If the left eye is at coordinate (-3, 4), its squared distance from the center is (-3)^2 + 4^2 = 9 + 16 = 25.
            //The loop calculates this squared distance for the left eye, right eye, nose, and mouth, and adds them all into
            //one massive running total.
            variance += (srcX * srcX + srcY * srcY)
        }

        // Safety check to avoid division by zero
        if (variance < 1e-6f) {
            return Matrix().apply {
                setTranslate(dstMeanX - srcMeanX, dstMeanY - srcMeanY)
            }
        }

        // 3. Calculate scale and rotation (a and b)


        val a = sigmaX / variance
        val b = sigmaY / variance

        // 4. Calculate translation. how much should the image move to get to 56.02, 74.64 after
        // rotating and scaling
        val tx = dstMeanX - (a * srcMeanX - b * srcMeanY)
        val ty = dstMeanY - (b * srcMeanX + a * srcMeanY)

        // 5. Construct Android Matrix
        // Android Matrix values are stored as:
        // [ MSCALE_X, MSKEW_X,  MTRANS_X ]
        // [ MSKEW_Y,  MSCALE_Y, MTRANS_Y ]
        // [ MPERSP_0, MPERSP_1, MPERSP_2 ]

        //The top-left corner matrix of a's and b's is strictly responsible
        //for twisting and resizing the image around the (0,0) origin. As established earlier, a
        //handles the cosine (scale + X-axis alignment) and b handles the sine (scale + Y-axis twist).

        //The rightmost column, tx and ty, acts as the linear shifter.
        //After the 2x2 core applies the twist, this column "pushes" the entire image linearly along the
        //X and Y axes to its final destination on the canvas.

        //The bottom row [0f, 0f, 1f] tells the graphics engine not to apply any 3D perspective distortion
        //(like 3D shearing or depth warping) to the flat image.
        val matrixValues = floatArrayOf(
            a, -b, tx,
            b,  a, ty,
            0f, 0f, 1f
        )
        //In Android, the canvas API cannot process loose math variables. It requires a specific object
        //to understand how to manipulate pixels. You arrange your 9 floats into a flat, 1D array (matrixValues).
        //Then, you instantiate a blank android.graphics.Matrix object.
        //finally, you inject your array into it using the setValues() method.
        //This resulting Matrix object is highly optimized. When you eventually pass it into canvas.drawBitmap
        //(originalBitmap, matrix, paint), Android hands this exact 3x3 matrix directly over to the device's GPU
        //(via the underlying Skia graphics library). The hardware processes this 3x3 matrix against millions of
        //pixels in parallel, executing the rotation, scaling, and translation in a fraction of a millisecond to achieve the 112x112 crop.
        return Matrix().apply { setValues(matrixValues) }
    }
}