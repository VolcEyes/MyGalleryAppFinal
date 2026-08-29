package com.example.galleryapp

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.scale
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer

//* PURPOSE: Finds bounding boxes and facial landmarks (eyes, nose, mouth) in an image.
class ScrfdHelper(context: Context) {
    private var ortEnv: OrtEnvironment = OrtEnvironment.getEnvironment()
    private var ortSession: OrtSession? = null

//    SCRFD models perform best on medium-to-large square images. 640x640
//    * provides the optimal balance between catching small faces and running fast.
    private val inputSize = 640

    init {
        // Load the SCRFD model from assets
        val modelBytes = context.assets.open("scrfd_500m_bnkps.onnx").readBytes()
        ortSession = ortEnv.createSession(modelBytes)
    }

    data class FaceDetection(
        val boundingBox: FloatArray, // [xmin, ymin, xmax, ymax]
        val keypoints: FloatArray,   // [leftEyeX, leftEyeY, rightEyeX, rightEyeY, noseX, noseY, leftMouthX, leftMouthY, rightMouthX, rightMouthY]
        val score: Float
    )

    //Prepares the image, runs the SCRFD ONNX model to find faces, and extracts
    // * both bounding boxes (where the face is) and keypoints (coordinates for
    // eyes, nose, mouth).
    fun detectFaces(bitmap: Bitmap): List<FaceDetection> {
        val letterboxData = applyLetterbox(bitmap, inputSize)
        val paddedBitmap = letterboxData.paddedBitmap
        val scale = letterboxData.scale
        val xOffset = letterboxData.xOffset
        val yOffset = letterboxData.yOffset

        // 2. Convert padded image to NCHW Buffer and Run Inference
        val floatBuffer = convertBitmapToNchwBuffer(paddedBitmap)
        val shape = longArrayOf(1, 3, inputSize.toLong(), inputSize.toLong())
        val tensor = OnnxTensor.createTensor(ortEnv, floatBuffer, shape)

        //Passes the pre-processed `OnnxTensor` into the ONNX C++ engine and blocks
        // the thread until the neural network finishes calculating the outputs
        val result = ortSession?.run(mapOf("input.1" to tensor))
        // By the time the ortSession?.run() function finishes, it gives you those
        // perfectly flattened length arrays because handling 4D tensors like [1,8, 20, 20]
        // is extremely slow. From B C W H to B (W*H) C where W*H is product of the 2 achors of each grid

        val detections = mutableListOf<FaceDetection>()

        /* ... [Keep your exact Tensor parsing and Stride loops here] ... */

// 4. Parse Outputs Dynamically
        // Instead of guessing the index order, we map the tensors based on their actual dimensions.
        //The ONNX result contains multiple unlabelled multidimensional arrays.
        // * The code loops through them and categorizes them by their "channel" size

        /**
         * THE PROBLEM: UNPREDICTABLE ONNX OUTPUTS
         * ---------------------------------------
         * When you run the SCRFD face detection model, it doesn't just give you one
         * list of faces. It returns 9 separate multidimensional arrays (tensors).
         * This is because it looks for faces at 3 different zoom levels (strides),
         * and for each zoom level, it outputs 3 things: Scores, Bounding Boxes, and Keypoints.
         * * In standard Python or C++ implementations, developers sometimes guess the order
         * (e.g., output 0 is scores, output 1 is boxes...). However, in Android ONNX Runtime,
         * the dictionary keys might be scrambled. If the code accidentally reads a
         * 10-channel keypoint array while expecting a 1-channel score array, the app
         * will immediately crash with an IndexOutOfBoundsException.
         */

        /**
         * THE SOLUTION: DYNAMIC SORTING BY SHAPE
         * --------------------------------------
         * Instead of guessing the order, the code looks at the mathematical "shape"
         * of the array to figure out what it contains. It loops through all 9 outputs
         * and checks the "channels" (the size of the innermost array).
         * * Here is exactly what the channel numbers mean:
         * * -> when (channels) == 1 (The 'scoresMap')
         * WHY 1?: This tensor represents the Confidence Score. It only needs 1 number
         * per anchor (e.g., 0.95) to say "I am 95% sure there is a face here."
         * * -> when (channels) == 4 (The 'bboxesMap')
         * WHY 4?: This tensor represents the Bounding Box offsets. It requires exactly
         * 4 numbers to define a rectangle: [Left, Top, Right, Bottom].
         * * -> when (channels) == 10 (The 'kpsMap')
         * WHY 10?: This tensor represents Facial Keypoints (Landmarks). The model tracks
         * 5 distinct features (Left Eye, Right Eye, Nose, Left Mouth, Right Mouth).
         * Each feature needs an X and a Y coordinate. (5 features * 2 coordinates = 10).
         *
         * /**
         *  * WHY USE MAPS? (mutableMapOf<Int, Array<FloatArray>>)
         *  * ----------------------------------------------------
         *  * You'll notice the code saves these arrays into Maps using `numAnchors` as the key.
         *  * * Since there are 3 different zoom levels (strides of 8, 16, and 32), the grid
         *  * sizes are different.
         *  * - Stride 8 creates a massive grid with thousands of anchors (looks for tiny faces).
         *  * - Stride 32 creates a small grid with fewer anchors (looks for huge faces).
         *  * * By saving them as `scoresMap[numAnchors] = data`, the code guarantees that
         *  * when it starts processing the 32-stride grid later on, it grabs the exact
         *  * scores, boxes, and keypoints that belong together, perfectly matching them
         *  * up by their length.
         *  */
         */
        val scoresMap = mutableMapOf<Int, Array<FloatArray>>() // Map of num_anchors -> array
        val bboxesMap = mutableMapOf<Int, Array<FloatArray>>()
        val kpsMap = mutableMapOf<Int, Array<FloatArray>>()

        result?.forEach { entry ->
            val onnxValue = entry.value.value
            var data: Array<FloatArray>? = null

            // Check if the output shape is [1, N, C] (Standard for InsightFace)
            if (onnxValue is Array<*> && onnxValue.isNotEmpty() && onnxValue[0] is Array<*>) {
                try {
                    data = (onnxValue as Array<Array<FloatArray>>)[0]
                } catch (e: Exception) {
                    // Ignore cast exceptions and move on
                }
            }
            // Fallback: Check if the output shape is [N, C]
            else if (onnxValue is Array<*> && onnxValue.isNotEmpty() && onnxValue[0] is FloatArray) {
                try {
                    data = onnxValue as Array<FloatArray>
                } catch (e: Exception) {
                    // Ignore
                }
            }

            if (data != null && data.isNotEmpty()) {
                val numAnchors = data.size
                val channels = data[0].size

                // Map the tensor to the correct category based on the channel size!

                when (channels) {
                    1 -> scoresMap[numAnchors] = data
                    4 -> bboxesMap[numAnchors] = data
                    10 -> kpsMap[numAnchors] = data
                }
            }
        }

        // Now process the mapped tensors
        val strides = intArrayOf(8, 16, 32)
        for (stride in strides) {
            val featureWidth = inputSize / stride
            val featureHeight = inputSize / stride

            //In Object Detection, an "anchor" is a pre-defined reference box. Even if the AI knows a face is centered exactly on grid cell (10, 10),
            // it needs to guess the shape and size of that face.
            //
            //To increase accuracy, the designers of the SCRFD model programmed it to look at every single cell and
            // make two separate guesses based on two different base sizes.
            //So, for a 20x20 grid:
            //400 grid cells × 2 guesses per cell = 800 total anchors.

            //The exact pixel dimensions of those 2 base sizes (often called "Anchor Boxes" or "Priors") are actually not written anywhere
            //in the ScrfdHelper.kt file. They are mathematical constants baked directly into the scrfd_500m_bnkps.onnx model file when the AI was trained.
            //However, here is exactly what they represent conceptually:
            //When the AI looks at a specific grid cell (let's say Stride 32, which looks for huge faces), it places two invisible, default "starting squares"
            //right in the center of that cell.
            //Usually, they are just two different scales for that specific zoom level:
            //Base Size 1 (The Smaller Guess): A box that roughly matches the size of the grid cell (e.g., a 128x128 pixel square).
            //Base Size 2 (The Larger Guess): A box that is slightly larger (e.g., a 256x256 pixel square).
            //(If the model is looking at Stride 8 for tiny faces, the two base sizes would be much smaller, like 16x16 and 32x32 pixels).
            //Why does it need 2 starting sizes?
            //Neural networks are bad at guessing absolute pixel coordinates from scratch. They are much better at making small adjustments to an existing shape.
            //If a real face on the screen is 140x140 pixels, it is much easier for the AI to look at Base Size 1 (128x128) and say: "Stretch this box slightly by 12 pixels."
            //If the face is 240x240 pixels, the AI will completely ignore Base Size 1, look at Base Size 2 (256x256), and say: "Shrink this box slightly by 16 pixels."

            val expectedAnchors = featureWidth * featureHeight * 2

            // Grab the safely mapped arrays for this specific stride
            val scores = scoresMap[expectedAnchors]
            val bboxes = bboxesMap[expectedAnchors]
            val kps = kpsMap[expectedAnchors]

            // If the model didn't map them successfully, skip this stride
            if (scores == null || bboxes == null || kps == null) continue

            //Χωρίζουν την εικόνα σε ένα πλέγμα (grid) και ελέγχουν κάθε "κελί" του. Το y διατρέχει τις γραμμές
            //(κάθετα) και το x διατρέχει τις στήλες (οριζόντια).

            //for (anchor in 0 until 2): Μέσα σε κάθε ένα κελί (x, y), το μοντέλο AI ελέγχει 2 διαφορετικά
            //αρχικά μεγέθη πλαισίων (τα anchors που συζητήσαμε).
            var anchorIndex = 0
            for (y in 0 until featureHeight) {
                for (x in 0 until featureWidth) {
                    for (anchor in 0 until 2) {

                        // The AI's Confidence Score
                        val score = scores[anchorIndex][0]
                        // Only process faces with high confidence
                        if (score > 0.5f) {


                            //First, the code calculates exactly where the center of this grid cell
                            //is on the 640x640 canvas:
                            //4a. Calculate the center of the current grid cell
                            //anchorCenterX / anchorCenterY is the invisible starting
                            //location based on the stride grid.
                            val anchorCenterX = (x * stride).toFloat()
                            val anchorCenterY = (y * stride).toFloat()

                            // 4b. Decode Bounding Box (Coordinates on the 640x640 canvas)
                            //bbox[0], bbox[1], etc., are the raw offset numbers the AI calculated to stretch the box.
                            // example
                            //val bbox = floatArrayOf(2.5f, 3.0f, 2.5f, 4.0f)
                            // [Left, Top, Right, Bottom]
                            //Remember, these are not pixels. They mean:
                            //
                            //"The left edge is 2.5 grid cells away from the center."
                            //
                            //"The top edge is 3.0 grid cells away from the center."
                            //Step 3: The Multiplication and Subtraction
                            //Now we run your specific lines of code.
                            //val xmin = anchorCenterX - bbox[0] * stride
                            // Math: 160 - (2.5 * 16)
                            // Math: 160 - 40
                            // xmin = 120
                            //The code translates "2.5 grid cells" into exactly "40 actual pixels".
                            //
                            //The Subtraction: It takes the center point (160) and walks 40 pixels to the left.
                            // The left edge of the face starts at pixel 120.
                            //Thanks to that math, the code has successfully translated the AI's abstract "grid guesses"
                            // into a perfect bounding box on your 640x640 canvas.

                            val bbox = bboxes[anchorIndex]
                            val xmin = anchorCenterX - bbox[0] * stride
                            val ymin = anchorCenterY - bbox[1] * stride
                            val xmax = anchorCenterX + bbox[2] * stride
                            val ymax = anchorCenterY + bbox[3] * stride

                            // 4c. Decode Keypoints (Coordinates on the 640x640 canvas)
                            val kpDistances = kps[anchorIndex]
                            val realKeypoints = FloatArray(10)
                            for (i in 0 until 5) {
                                realKeypoints[i * 2] = anchorCenterX + kpDistances[i * 2] * stride
                                realKeypoints[(i * 2) + 1] = anchorCenterY + kpDistances[(i * 2) + 1] * stride
                            }

                            // 4d. REVERSE LETTERBOX: Map back to the original uncropped image
                            val realXMin = (xmin - xOffset) / scale
                            val realYMin = (ymin - yOffset) / scale
                            val realXMax = (xmax - xOffset) / scale
                            val realYMax = (ymax - yOffset) / scale

                            val scaledBbox = floatArrayOf(realXMin, realYMin, realXMax, realYMax)

                            val scaledKeypoints = FloatArray(10)
                            for (i in 0 until 10 step 2) {
                                scaledKeypoints[i] = (realKeypoints[i] - xOffset) / scale
                                scaledKeypoints[i+1] = (realKeypoints[i+1] - yOffset) / scale
                            }

                            detections.add(FaceDetection(scaledBbox, scaledKeypoints, score))
                            // ...
                        }
                        anchorIndex++
                    }
                }
            }
        }

        // 5. IMPORTANT: Apply Non-Maximum Suppression (NMS) here.
        // Because anchors overlap, SCRFD will detect the exact same face multiple times across different strides.
        // You must run a standard NMS function on the `detections` list using an IoU threshold of ~0.4f
        // to keep only the highest scoring box per face before returning the list.

//The NMS function doesn't know which boxes belong to which strides, and it doesn't know which boxes are duplicates until it runs the math.
// It just assumes that any two boxes that share more than 40% of the exact same pixel space on the screen must be the same face, and it always deletes the one
// with the lower AI confidence score.

        val finalDetections = applyNMS(detections, iouThreshold = 0.4f)

        tensor.close()
        result?.close()

        // FIX: Recycle the new padded canvas instead of the old scaled variable
        paddedBitmap.recycle()

        return finalDetections

    }
//    - HOW: Normalizes pixels using the formula: `(color - 127.5f) / 128.0f`
//    * and splits RGB into separate consecutive blocks (NCHW format).
//    * - WHY: Maps the 0-255 pixel range to a -1.0 to 1.0 float range, which
//    * is the mathematical range the SCRFD weights were trained on.
private fun convertBitmapToNchwBuffer(bitmap: Bitmap): FloatBuffer {
    // ... (Same implementation as OnnxFaceHelper convertBitmapToNchwBuffer) ...
    // Δέσμευσε μνήμη για τον FloatBuffer με συνολικό μέγεθος: 3 κανάλια (RGB) * πλάτος * ύψος
    val floatBuffer = FloatBuffer.allocate(3 * inputSize * inputSize)
    // Δημιούργησε έναν πίνακα ακεραίων για να αποθηκεύσεις τα pixels της εικόνας
    val pixels = IntArray(inputSize * inputSize)
    // Αντίγραψε τα δεδομένα των pixels από το αντικείμενο Bitmap μέσα στον πίνακα pixels
    bitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
    // Όρισε το σημείο εκκίνησης (offset) στη μνήμη για τα δεδομένα του κόκκινου καναλιού (R) στο 0
    val rOffset = 0
    // Όρισε το offset για το πράσινο κανάλι (G) ακριβώς μετά το τέλος των κόκκινων pixels
    val gOffset = inputSize * inputSize
    // Όρισε το offset για το μπλε κανάλι (B) ακριβώς μετά το τέλος των πράσινων pixels
    val bOffset = 2 * inputSize * inputSize

    // Ξεκίνα έναν βρόχο (loop) για να επεξεργαστείς το κάθε ένα pixel του πίνακα
    for (i in pixels.indices) {
        // Διάβασε την πλήρη ακέραια τιμή του χρώματος (που περιέχει ARGB) για το τρέχον pixel
        val color = pixels[i]
        // Απομόνωσε το κόκκινο χρώμα (δεξιά ολίσθηση κατά 16 bits) και κανονικοποίησέ το στην κλίμακα [-1, 1]
        val r = (((color shr 16) and 0xFF) - 127.5f) / 128.0f
        // Απομόνωσε το πράσινο χρώμα (δεξιά ολίσθηση κατά 8 bits) και κανονικοποίησέ το στην κλίμακα [-1, 1]
        val g = (((color shr 8) and 0xFF) - 127.5f) / 128.0f
        // Απομόνωσε το μπλε χρώμα (χωρίς ολίσθηση) και κανονικοποίησέ το στην κλίμακα [-1, 1]
        val b = ((color and 0xFF) - 127.5f) / 128.0f
        // Τοποθέτησε την κανονικοποιημένη τιμή του κόκκινου στη σωστή, συνεχή θέση μνήμης (στο πρώτο μπλοκ)
        floatBuffer.put(rOffset + i, r)
        // Τοποθέτησε την κανονικοποιημένη τιμή του πράσινου στη σωστή, συνεχή θέση μνήμης (στο δεύτερο μπλοκ)
        floatBuffer.put(gOffset + i, g)
        // Τοποθέτησε την κανονικοποιημένη τιμή του μπλε στη σωστή, συνεχή θέση μνήμης (στο τρίτο μπλοκ)
        floatBuffer.put(bOffset + i, b)
    } // Τέλος του βρόχου (η αγκύλη προστέθηκε για να κλείσει σωστά ο κώδικας)

    // Επίστρεψε τον έτοιμο FloatBuffer που είναι πλέον σε μορφή NCHW
    return floatBuffer
} // Τέλος της συνάρτησης (η αγκύλη προστέθηκε για να κλείσει σωστά ο κώδικας)

    /**
     * Filters out overlapping bounding boxes, keeping only the ones with the highest confidence scores.
     */
    private fun applyNMS(detections: List<FaceDetection>, iouThreshold: Float = 0.4f): List<FaceDetection> {
        val keptDetections = mutableListOf<FaceDetection>()

        // 1. Sort all detections by confidence score in descending order
        val sortedDetections = detections.sortedByDescending { it.score }.toMutableList()

        while (sortedDetections.isNotEmpty()) {
            // 2. Take the box with the highest score and keep it
            val bestDetection = sortedDetections.removeAt(0)
            keptDetections.add(bestDetection)

            // 3. Compare this best box against all remaining boxes
            val iterator = sortedDetections.iterator()
            while (iterator.hasNext()) {
                val nextDetection = iterator.next()

                // If the overlap (IoU) is higher than the threshold, it's a duplicate. Remove it.
                val iou = calculateIoU(bestDetection.boundingBox, nextDetection.boundingBox)
                if (iou > iouThreshold) {
                    iterator.remove()
                }
            }
        }
        return keptDetections
    }

    /**
     * Calculates the Intersection over Union (IoU) between two bounding boxes.
     * Box format: [xmin, ymin, xmax, ymax]
     */
    private fun calculateIoU(box1: FloatArray, box2: FloatArray): Float {
        // Calculate the coordinates of the intersection rectangle
        val intersectXMin = maxOf(box1[0], box2[0])
        val intersectYMin = maxOf(box1[1], box2[1])
        val intersectXMax = minOf(box1[2], box2[2])
        val intersectYMax = minOf(box1[3], box2[3])

        // Calculate intersection area
        val intersectWidth = maxOf(0f, intersectXMax - intersectXMin)
        val intersectHeight = maxOf(0f, intersectYMax - intersectYMin)
        val intersectArea = intersectWidth * intersectHeight

        // Calculate the area of both bounding boxes
        val box1Area = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val box2Area = (box2[2] - box2[0]) * (box2[3] - box2[1])

        // Calculate Union area
        val unionArea = box1Area + box2Area - intersectArea

        // Return IoU ratio (safeguard against division by zero)
        return if (unionArea > 0f) intersectArea / unionArea else 0f
    }


    data class LetterboxResult(
        val paddedBitmap: Bitmap,
        val scale: Float,
        val xOffset: Float,
        val yOffset: Float
    )

    /**
     * Replicates the InsightFace/OpenCV cv2.copyMakeBorder letterbox technique.
     * Scales the image preserving aspect ratio, then pads it to a perfect square.
     */
//
//    Scales the image up/down so its longest edge is 640. Instead of
//    * stretching the shorter edge, it pads the leftover empty space with a
//    * neutral gray color `(114, 114, 114)`. It tracks the `scale`, `xOffset`,
//    * and `yOffset` to reverse the math later.
//    * - WHY: If you stretch a 16:9 photo into a 1:1 square, the faces get
//    * squished. The AI will fail to recognize them as faces. Letterboxing
//    * preserves the true aspect ratio. Neutral gray (114) is used because
//    * it equates to essentially "zero" activation in the neural network.
    private fun applyLetterbox(bitmap: Bitmap, targetSize: Int): LetterboxResult {
        // 1. Calculate the scale factor to fit the longest edge exactly to targetSize
        val scale = minOf(
            targetSize.toFloat() / bitmap.width,
            targetSize.toFloat() / bitmap.height
        )

        // 2. Calculate the new dimensions of the image
        val newWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val newHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

        // 3. Calculate offsets to center the scaled image horizontally and vertically
        val xOffset = (targetSize - newWidth) / 2f
        val yOffset = (targetSize - newHeight) / 2f

        // 4. Create the target canvas filled with Neural Network Neutral Gray (114, 114, 114)
        val paddedBitmap = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(paddedBitmap)
        canvas.drawColor(android.graphics.Color.rgb(114, 114, 114))

        // 5. Scale the original image and draw it onto the center of the canvas
        val scaledOriginal = Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
        canvas.drawBitmap(scaledOriginal, xOffset, yOffset, null)

        // Clean up the intermediate scaled bitmap to prevent memory leaks
        if (scaledOriginal !== bitmap) {
            scaledOriginal.recycle()
        }

        return LetterboxResult(paddedBitmap, scale, xOffset, yOffset)
    }
}