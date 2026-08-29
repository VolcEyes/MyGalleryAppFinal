package com.example.galleryapp


import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.objectbox.Box
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

import androidx.recyclerview.widget.LinearLayoutManager
import kotlin.math.sqrt

import android.content.Context
import android.graphics.ImageDecoder
import android.widget.ImageView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class MainActivity : AppCompatActivity() {

    private var textClipHelper: TextClipHelper? = null // <-- ADD THIS

    private lateinit var progressBar: ProgressBar

    // ML Helpers
    //private var faceNetHelper: FaceNetHelper? = null
    private var faceNetHelper: OnnxFaceHelper? = null

    private var mobileClipHelper: MobileClipHelper? = null // <-- ADD THIS

    // Database Boxes
    private lateinit var imageBox: Box<ImageEntity>
    private lateinit var faceBox: Box<FaceEntity>

    private lateinit var contextImageBox: Box<ContextImageEntity> // <-- ADD THIS

    private lateinit var personBox: Box<PersonEntity> // Add this line
    // UI Elements

    private lateinit var searchViewContext: androidx.appcompat.widget.SearchView // <-- ADD THIS
    private lateinit var recyclerView: RecyclerView

    private lateinit var imageAdapter: ImageAdapter

    private var scrfdHelper: ScrfdHelper? = null
    private var imageList = ArrayList<Image>()//

    // This ensures only 3 images are processed by the ML models at the exact same time
    private val mlMutex = Mutex()

    // Add these with your other UI Elements
    private lateinit var progressContainer: View
    private lateinit var tvProgressText: TextView
    private lateinit var progressBarHorizontal: ProgressBar

    private lateinit var mergeHistoryBox: Box<MergeHistoryEntity>

// Inside onCreate():
// mergeHistoryBox = GalleryApp.boxStore.boxFor(MergeHistoryEntity::class.java)



    // Safety flag
    private var isProcessing = false

    // Permission Launcher for Gallery Access
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            loadGalleryImages()
        } else {
            Toast.makeText(this, "Permission denied to read images", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        searchViewContext = findViewById(R.id.search_view_context)

        // Listen for user text input for context search (Prepared for Step 5)
// Listen for user text input for context search
        searchViewContext.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!query.isNullOrEmpty()) {
                    Toast.makeText(this@MainActivity, "Searching for: $query", Toast.LENGTH_SHORT).show()
                    searchViewContext.clearFocus() // Hide the keyboard

                    lifecycleScope.launch(Dispatchers.IO) {
                        // 1. Convert the user's text into a 512-dimensional vector
                        // 1. Get Text Vector
                        // 1. Get Text Vector
                        val textVector = textClipHelper?.getTextVector(query)

                        if (textVector != null) {
                            // 2. Perform HNSW Vector Search
                            // Only ask ObjectBox for exactly the number of results you want to display (e.g., 30)
                            val maxResults = 30
                            // Database - Reading Data: Contextual search via HNSW index
                            val queryBuilder = contextImageBox.query()
                                .nearestNeighbors(ContextImageEntity_.clipVector, textVector, maxResults)
                                .build()

                            val resultsWithScores = queryBuilder.findWithScores()
                            queryBuilder.close()

                            val searchResults = ArrayList<Image>()

                            // 3. Map Results directly (No strict threshold filtering)
                            for (result in resultsWithScores) {
                                val cosineSimilarity = 1.0 - result.score

                                // LOG THE SCORES: Check Logcat to see the actual math values!
                                android.util.Log.d("CLIP_SEARCH", "Image: ${result.get().imageUri} | Similarity: $cosineSimilarity")

                                // Optional: Only filter out EXTREMELY low scores (e.g., below 0.10) to prevent absolute garbage
                                if (cosineSimilarity > 0.10) {
                                    val entity = result.get()

                                    val originalImage = imageBox.query()
                                        .equal(ImageEntity_.imageUri, entity.imageUri, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
                                        .build()
                                        .findFirst()

                                    val dateMod = originalImage?.dateModified?.toString() ?: "0"
                                    searchResults.add(Image(entity.imageUri, dateMod))
                                }
                            }

                            // 4. Update UI
                            withContext(Dispatchers.Main) {
                                if (searchResults.isEmpty()) {
                                    Toast.makeText(this@MainActivity, "No matching images found.", Toast.LENGTH_SHORT).show()
                                    imageList.clear()
                                    imageAdapter.notifyDataSetChanged()
                                } else {
                                    imageList.clear()
                                    imageList.addAll(searchResults)
                                    imageAdapter.notifyDataSetChanged()
                                }
                            }
                        

                            // 4. Update UI
                            withContext(Dispatchers.Main) {
                                if (searchResults.isEmpty()) {
                                    Toast.makeText(this@MainActivity, "No matching images found.", Toast.LENGTH_SHORT).show()
                                    imageList.clear()
                                    imageAdapter.notifyDataSetChanged()
                                } else {
                                    imageList.clear()
                                    imageList.addAll(searchResults)
                                    imageAdapter.notifyDataSetChanged()
                                }
                            }

                        } else {
                            withContext(Dispatchers.Main) {
                                Toast.makeText(this@MainActivity, "Model is still loading, please try again in a moment.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                return true // Return true to indicate we handled the search submission
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                // Optional: If the user clears the search bar, restore the full gallery
                if (newText.isNullOrEmpty() && imageList.isNotEmpty()) {
                    imageList.clear()
                    imageAdapter.notifyDataSetChanged()

                    // Call your existing load function to bring all images back
                    loadGalleryImages()
                }
                return false
            }
        })

        progressContainer = findViewById(R.id.progress_container)
        tvProgressText = findViewById(R.id.tv_progress_text)
        progressBarHorizontal = findViewById(R.id.progress_bar_horizontal)

        // References to specific entity boxes are grabbed so there can be interactions with the respective tables.
        imageBox = GalleryApp.boxStore.boxFor(ImageEntity::class.java)
        faceBox = GalleryApp.boxStore.boxFor(FaceEntity::class.java)
        personBox = GalleryApp.boxStore.boxFor(PersonEntity::class.java) // Add this line
        mergeHistoryBox = GalleryApp.boxStore.boxFor(MergeHistoryEntity::class.java)

        contextImageBox = GalleryApp.boxStore.boxFor(ContextImageEntity::class.java)

        recyclerView = findViewById(R.id.image_recycler)
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        imageAdapter = ImageAdapter(this, imageList)
        recyclerView.adapter = imageAdapter

        // Initialize ML Helpers in the background to prevent UI freezing
// In MainActivity.kt -> onCreate()

        recyclerView = findViewById(R.id.image_recycler)
        progressBar = findViewById(R.id.recycler_progress) // Find the ProgressBar


        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("AppDebug", "Starting ML Models...")
                faceNetHelper = OnnxFaceHelper(this@MainActivity)
                scrfdHelper= ScrfdHelper(this@MainActivity)
                mobileClipHelper = MobileClipHelper(this@MainActivity) // <-- ADD THIS
                textClipHelper = TextClipHelper(this@MainActivity) // <-- ADD THIS
                Log.d("AppDebug", "ML Models Loaded Successfully!")

                // ---> CALL YOUR DEBUG FUNCTION HERE <---
                // Change 0.62f to whatever value you want to test on this run
                debugRecalculateClusters(0.58f)

                withContext(Dispatchers.Main) {
                    checkPermissionsAndLoadImages()
                }
            } catch (e: Exception) {
                Log.e("AppDebug", "CRITICAL ERROR LOADING ML MODELS", e)
            }
        }
    }

    private fun checkPermissionsAndLoadImages() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            loadGalleryImages()
        } else {
            requestPermissionLauncher.launch(permission)
        }
    }

    private fun loadGalleryImages() {
        lifecycleScope.launch(Dispatchers.IO) {
            val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_MODIFIED)
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            // 1. Create a temporary list to hold images
            val temporaryList = ArrayList<Image>()

            contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateModified = cursor.getLong(dateModifiedColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

                    // 2. Add to the temporary list ONLY (no Main Thread calls here)
                    val imageItem = Image(contentUri.toString(), dateModified.toString())
                    temporaryList.add(imageItem)

                }
            }

            // 4. Update the UI exactly ONE time after the loop is completely finished
            withContext(Dispatchers.Main) {
                val startPosition = imageList.size
                imageList.addAll(temporaryList)
                imageAdapter.notifyItemRangeInserted(startPosition, temporaryList.size)
                progressBar.visibility = View.GONE
            }
        }
    }

    private suspend fun processImage(imageUri: Uri, dateModified: Long) {
        val uriString = imageUri.toString()

        // Database - Reading Data: Checking for Duplicate Processing
        // 1. Check if this image was already processed to avoid duplicate work
        val existingImage = imageBox.query()
            .equal(
                ImageEntity_.imageUri, //This is the column in your ObjectBox database where I
                // saved the addresses of all the photos you have already processed in the past
                uriString, // This is the address of the photo in my  app is currently looking at on the phone's storage.
                io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE //Ignores Casings
            )
            .build() // finalizes the setup. Compiles the criteria into an executable query.
            .findFirst() //Because image URIs are generally unique on an Android device, we only need to find one to know the image exists.
        // Using findFirst() is much faster and uses less memory than find(), which would force the database to scan
        // the entire table looking for multiple matches and return a list.

        if (existingImage != null) return
        // Database - Writing Data: Saving Images and Faces. ImageEntity connects with faceEntity later
        // 2. Save the base image to ObjectBox
        val imageEntity = ImageEntity(imageUri = uriString, dateModified = dateModified)
        imageBox.put(imageEntity) // When new data is created (like a newly scanned photo or detected face), it is saved using .put().

        // 3. Load Bitmap safely
        val bitmap = loadBitmapFromUri(imageUri) ?: return

        val originalWidth = bitmap.width
        val originalHeight = bitmap.height

        // --- NEW STEP 4: Scale down for detection instead of tiling ---
        // InsightFace SCRFD works best on smaller images (around 640px).
        // This makes detection incredibly fast and uses less memory.
        val maxDetectSize = 640f
        val scaleFactor = if (originalWidth > originalHeight) {
            maxDetectSize / originalWidth
        } else {
            maxDetectSize / originalHeight
        }

        // Only scale if the original image is actually larger than 640px
        val detectBitmap = if (scaleFactor < 1.0f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (originalWidth * scaleFactor).toInt(),
                (originalHeight * scaleFactor).toInt(),
                true
            )
        } else {
            bitmap
        }

        // We need the inverse scale factor to map SCRFD's coordinates back to the original massive image
        val inverseScaleFactor = if (scaleFactor < 1.0f) 1.0f / scaleFactor else 1.0f

        // 5. Detect faces on the smaller image (No more tiling grid!)
        val detections = scrfdHelper?.detectFaces(detectBitmap) ?: emptyList()

        detections.forEach { detection ->
            // Only process confident faces
            if (detection.score > 0.55f) {
                val keypoints = detection.keypoints

                // 1. Extract all 5 critical keypoints (10 floats) and map them BACK to the original high-res image
                val srcPoints = floatArrayOf(
                    keypoints[0] * inverseScaleFactor, keypoints[1] * inverseScaleFactor, // Left Eye X, Y
                    keypoints[2] * inverseScaleFactor, keypoints[3] * inverseScaleFactor, // Right Eye X, Y
                    keypoints[4] * inverseScaleFactor, keypoints[5] * inverseScaleFactor, // Nose X, Y
                    keypoints[6] * inverseScaleFactor, keypoints[7] * inverseScaleFactor, // Left Mouth Corner X, Y
                    keypoints[8] * inverseScaleFactor, keypoints[9] * inverseScaleFactor  // Right Mouth Corner X, Y
                )

                // Calculate the actual bounding box in the original image for saving to the database
                val pxMinX = (detection.boundingBox[0] * inverseScaleFactor).toInt().coerceAtLeast(0)
                val pxMinY = (detection.boundingBox[1] * inverseScaleFactor).toInt().coerceAtLeast(0)
                val pxMaxX = (detection.boundingBox[2] * inverseScaleFactor).toInt().coerceAtMost(originalWidth)
                val pxMaxY = (detection.boundingBox[3] * inverseScaleFactor).toInt().coerceAtMost(originalHeight)

                // 2. Perform Similarity Transform & Crop directly from the base massive image
                // This uses the updated FaceAligner to output a perfect 112x112 face for Buffalo_S
                val alignedAndCroppedFace = FaceAligner.alignAndCrop(bitmap, srcPoints)

                // 3. Get the embedding vector using Buffalo_S
                val faceVector = faceNetHelper?.getFaceVector(alignedAndCroppedFace)

                // Save cropped face to internal storage
                val faceFileName =
                    "face_${System.currentTimeMillis()}_${java.util.UUID.randomUUID()}.jpg"
                val file = java.io.File(applicationContext.filesDir, faceFileName)
                java.io.FileOutputStream(file).use { out ->
                    alignedAndCroppedFace.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
                // Database - Writing Data: Saving Images and Faces
                // Save the detected face to database and link it
                val faceEntity = FaceEntity(
                    faceVector = faceVector,
                    boundingBox = "$pxMinX,$pxMinY,$pxMaxX,$pxMaxY",
                    faceImagePath = file.absolutePath
                )
                faceEntity.image.target = imageEntity //When SCRFD detects a face, it saves the base image and the cropped face entity.
                // It also links the face to the image using the ObjectBox relation (.target).
                faceBox.put(faceEntity)

                // FREE MEMORY
                alignedAndCroppedFace.recycle()
            }
        }

        // FREE MEMORY
        // If we created a separate scaled bitmap, recycle it
        if (detectBitmap !== bitmap) {
            detectBitmap.recycle()
        }

        // Destroy the large base image at the very end
        bitmap.recycle()
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            // First decode just the bounds to calculate the sample size
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Target maximum dimensions (e.g., 1024x1024 is usually enough for face detection)
            val reqWidth = 3072
            val reqHeight = 3072
            var inSampleSize = 1

            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            // Now decode the actual bitmap using the calculated sample size
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888

            var bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // 2. Android can sometimes ignore inPreferredConfig.
            // If the config is still wrong or null (like HARDWARE), we MUST convert it safely.
            if (bitmap != null && bitmap.config != Bitmap.Config.ARGB_8888) {
                val convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle() // Recycle the old incorrect format to prevent memory leaks
                bitmap = convertedBitmap
            }

            return bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        faceNetHelper?.close()
        mobileClipHelper?.close()
        textClipHelper?.close() // <-- ADD THIS
    }

    //SEARCH MENU

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    // 2. Listens for clicks on the Menu Item
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                showSearchBottomSheet()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // 3. Displays the 2/3 screen
    private fun showSearchBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        // Inflate the bottom sheet layout
        val view = layoutInflater.inflate(R.layout.bottom_sheet_search, null)
        bottomSheetDialog.setContentView(view)
        val btnScanNewImages = view.findViewById<Button>(R.id.btnScanNewImagesBottomSheet)

        val facesRecyclerView = view.findViewById<RecyclerView>(R.id.recycler_faces)
        val btnApply = view.findViewById<Button>(R.id.btn_apply)
        val btnViewMergeHistory = view.findViewById<Button>(R.id.btn_view_merge_history)
        // Add this right after you initialize facesRecyclerView and btnApply
        val btnProcessImages = view.findViewById<Button>(R.id.btn_process_images)

        val btnViewTotalFaces = view.findViewById<Button>(R.id.btn_view_total_faces)
        val btnScanContext = view.findViewById<Button>(R.id.btn_scan_context) // <-- ADD THIS

        btnProcessImages.setOnClickListener {
            startImageProcessing()
            bottomSheetDialog.dismiss() // Close the sheet so they can see the progress bar!
        }

        btnScanContext.setOnClickListener {
            startContextScan()
            bottomSheetDialog.dismiss()
        }

        facesRecyclerView.layoutManager = GridLayoutManager(this, 4)

        // --- NEW SIMPLIFIED LOGIC ---

        // 1. Grab all successfully clustered people from the database
// 1. Grab all successfully clustered people from the database
        val allPersons = personBox.all
        val uniqueFacesToDisplay = ArrayList<FaceEntity>()

        // 2. Extract exactly ONE cover face for each unique person to show in the grid
        for (person in allPersons) {
            val uniqueImageCount = person.faces.map { it.image.targetId }.distinct().size

            if (uniqueImageCount >= 3) {

                // --- THE FIX ---
                // First, try to find the specific face the user set as the preview
                var coverFace = person.faces.find { it.faceImagePath == person.coverFaceImagePath }

                // Fallback: If no custom preview is set (or it was deleted), just use the first face
                if (coverFace == null) {
                    coverFace = person.faces.firstOrNull()
                }

                if (coverFace != null) {
                    uniqueFacesToDisplay.add(coverFace)
                }
            }
        }

        val btnMergePeople = view.findViewById<Button>(R.id.btn_merge_people)

        var chosenFace: FaceEntity? = null
        val selectedFacesForMerge = mutableListOf<FaceEntity>()

        val faceAdapter = FaceAdapter(
            context = this,
            faces = uniqueFacesToDisplay,
            selectedFaces = selectedFacesForMerge,
            onClick = { selectedFace ->

                // 1. Handle the Selection/Deselection FIRST
                if (selectedFacesForMerge.contains(selectedFace)) {
                    // They tapped an already selected face, so remove it
                    selectedFacesForMerge.remove(selectedFace)
                } else {
                    // They are selecting a new face
                    if (selectedFacesForMerge.size < 2) {
                        selectedFacesForMerge.add(selectedFace)
                    } else {
                        // If they tap a 3rd face, replace the 2nd one
                        selectedFacesForMerge[1] = selectedFace
                    }
                }

                // 2. THE BUG FIX: Safely update chosenFace
                // Instead of blindly taking whatever was tapped, we take the LAST face
                // that is currently sitting in the selection list. If the list is empty, it becomes null.
                chosenFace = selectedFacesForMerge.lastOrNull()

                // 3. Update the Merge Button state
                btnMergePeople.isEnabled = selectedFacesForMerge.size == 2

                // 4. Redraw the blue rings
                facesRecyclerView.adapter?.notifyDataSetChanged()
            }
        )
        facesRecyclerView.adapter = faceAdapter

        btnViewMergeHistory?.setOnClickListener {
            // Check if the history box is empty before opening
            if (mergeHistoryBox.isEmpty) {
                Toast.makeText(this@MainActivity, "No merged profiles to show.", Toast.LENGTH_SHORT).show()
            } else {
                showMergeHistoryBottomSheet()

                // Optional: Close the main search sheet so it doesn't stack awkwardly behind the new one
                // bottomSheetDialog.dismiss()
            }
        }

        btnViewTotalFaces.setOnClickListener {
            val personId = chosenFace?.person?.targetId
            if (personId != null) {
                // Pass the current dialog so we can refresh it later!
                showPersonFacesBottomSheet(personId, bottomSheetDialog)
            }
        }

        // ... Keep your btnApply.setOnClickListener code exactly as it is right here ...
        // 4. Apply button logic
        btnApply.setOnClickListener {
            if (chosenFace == null) {
                Toast.makeText(this@MainActivity, "Please tap a face first.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            lifecycleScope.launch(Dispatchers.IO) {
                val cachedPerson = chosenFace?.person?.target

                if (cachedPerson != null) {
                    // SCENARIO A: The face belongs to a clustered Person
                    val freshPerson = personBox.get(cachedPerson.id)

                    if (freshPerson != null) {
                        val photosOfPerson = freshPerson.faces
                            .mapNotNull { it.image.target }
                            .distinctBy { it.imageUri }

                        val validPhotos = mutableListOf<ImageEntity>()
                        val ghostPhotos = mutableListOf<ImageEntity>()

                        // 1. Verify which images are physically accessible
                        for (entity in photosOfPerson) {
                            if (isImageUriValid(entity.imageUri)) {
                                validPhotos.add(entity)
                            } else {
                                ghostPhotos.add(entity)
                            }
                        }

                        // 2. Auto-heal: Delete ghost records and empty clusters
                        if (ghostPhotos.isNotEmpty()) {
                            for (ghost in ghostPhotos) {
                                for (face in ghost.faces) {
                                    val person = face.person.target

                                    // Delete the physical face crop
                                    try { java.io.File(face.faceImagePath).delete() } catch(e: Exception){}
                                    faceBox.remove(face)

                                    // Clean up the Person cluster so it doesn't leave an empty bubble
                                    if (person != null) {
                                        person.faces.remove(face)
                                        if (person.faces.isEmpty()) {
                                            // Destroy the cluster if no faces are left
                                            personBox.remove(person)
                                        } else if (person.coverFaceImagePath == face.faceImagePath) {
                                            // If we just deleted their cover photo, pick a new one
                                            person.coverFaceImagePath = person.faces.first().faceImagePath
                                            personBox.put(person)
                                        }
                                    }
                                }
                                imageBox.remove(ghost)
                            }
                        }

                        val filteredImages = validPhotos.map { entity ->
                            Image(entity.imageUri, entity.dateModified.toString())
                        }

                        withContext(Dispatchers.Main) {
                            imageList.clear()
                            imageList.addAll(filteredImages)
                            imageAdapter.notifyDataSetChanged()

                            Toast.makeText(this@MainActivity, "Found ${filteredImages.size} photos of this person", Toast.LENGTH_SHORT).show()
                            bottomSheetDialog.dismiss()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: Could not load person details.", Toast.LENGTH_SHORT).show()
                        }
                    }

                } else {
                    // SCENARIO B: Unclustered face (Fallback)
                    val singleImageEntity = chosenFace?.image?.target

                    if (singleImageEntity != null) {
                        val isValid = isImageUriValid(singleImageEntity.imageUri)

                        withContext(Dispatchers.Main) {
                            if (isValid) {
                                val singleImage = Image(singleImageEntity.imageUri, singleImageEntity.dateModified.toString())
                                imageList.clear()
                                imageList.add(singleImage)
                                imageAdapter.notifyDataSetChanged()
                                Toast.makeText(this@MainActivity, "Showing 1 unclustered photo", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(this@MainActivity, "This photo was deleted from the device.", Toast.LENGTH_SHORT).show()
                            }
                            bottomSheetDialog.dismiss()
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@MainActivity, "Error: Could not load the image.", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        btnMergePeople.setOnClickListener {
            if (selectedFacesForMerge.size == 2) {
                // Get the Person IDs belonging to the 2 tapped faces
                val person1Id = selectedFacesForMerge[0].person.targetId
                val person2Id = selectedFacesForMerge[1].person.targetId

                if (person1Id != 0L && person2Id != 0L && person1Id != person2Id) {
                    // 1. Run the merge function
                    userMergePersons(person1Id, person2Id)

                    // 2. Alert the user
                    Toast.makeText(this@MainActivity, "Profiles Merged Successfully!", Toast.LENGTH_SHORT).show()

                    // 3. Close and instantly re-open the bottom sheet to refresh the UI
                    bottomSheetDialog.dismiss()
                    showSearchBottomSheet()
                } else {
                    Toast.makeText(this@MainActivity, "Cannot merge the same person.", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 5. Show the Dialog (2/3 height)
        val screenHeight = resources.displayMetrics.heightPixels
        val twoThirdsHeight = (screenHeight * 0.85).toInt()
        view.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, twoThirdsHeight)

        bottomSheetDialog.setContentView(view)
        val bottomSheetBehavior = BottomSheetBehavior.from(view.parent as View)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_EXPANDED
        bottomSheetBehavior.skipCollapsed = true

        btnScanNewImages?.setOnClickListener {
            // Ensure you have requested READ_MEDIA_IMAGES (Android 13+)
            // or READ_EXTERNAL_STORAGE (Android 12-) permissions before calling this!
            Toast.makeText(this, "Scanning for new images in background...", Toast.LENGTH_SHORT).show()

            scanNewlyAddedImages(this)

            // Optional: Close the bottom sheet automatically after clicking
            // bottomSheetDialog.dismiss()
        }

        bottomSheetDialog.show()
    }

    private fun startImageProcessing() {
        if (isProcessing) {
            Toast.makeText(this, "Already processing...", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        progressContainer.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val totalImages = imageList.size
            var processedCount = 0

            // 1. Setup the initial progress bar state
            withContext(Dispatchers.Main) {
                progressBarHorizontal.max = totalImages
                progressBarHorizontal.progress = 0
                tvProgressText.text = "Processed 0 / $totalImages images"
            }

            // 2. Process every image one by one
            for (image in imageList) {
                // Note: Ensure '.imageUri' and '.dateModified' match the property names in your Image.kt data class
                val uri = Uri.parse(image.imagePath)
                val dateMod = image.imageTitle.toLongOrNull() ?: 0L

                mlMutex.withLock {
                    processImage(uri, dateMod)
                }

                processedCount++

                // 3. Update the UI as it progresses
                withContext(Dispatchers.Main) {
                    progressBarHorizontal.progress = processedCount
                    tvProgressText.text = "Processed $processedCount / $totalImages images"
                }
            }

            // 4. Once all images are scanned, run the clustering algorithm automatically!
            withContext(Dispatchers.Main) {
                tvProgressText.text = "Clustering faces together..."
            }

            runFacialRecognitionBatchJob()

            // 5. Processing is completely finished
            withContext(Dispatchers.Main) {
                isProcessing = false
                Toast.makeText(this@MainActivity, "Processing is Complete!", Toast.LENGTH_LONG).show()

                // Hide the progress bar after 3 seconds
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    progressContainer.visibility = View.GONE
                }, 3000)
            }
        }
    }

    private fun startContextScan() {
        if (isProcessing) {
            Toast.makeText(this, "Already processing...", Toast.LENGTH_SHORT).show()
            return
        }

        isProcessing = true
        progressContainer.visibility = View.VISIBLE

        lifecycleScope.launch(Dispatchers.IO) {
            val totalImages = imageList.size
            var processedCount = 0

            withContext(Dispatchers.Main) {
                progressBarHorizontal.max = totalImages
                progressBarHorizontal.progress = 0
                tvProgressText.text = "Contextualizing 0 / $totalImages images"
            }

            for (image in imageList) {
                val uriString = image.imagePath

// Skip if this image already has a context vector in the DB
                val existing = contextImageBox.query()
                    .equal(
                        ContextImageEntity_.imageUri,
                        uriString,
                        io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE
                    )
                    .build()
                    .findFirst()

                if (existing == null) {
                    val uri = Uri.parse(uriString)
                    val bitmap = loadThumbnailForClip(uri)
                    if (bitmap != null) {
                        mlMutex.withLock {
                            // Extract Apple MobileCLIP vector
                            val vector = mobileClipHelper?.getImageVector(bitmap)
                            if (vector != null) {
                                val entity = ContextImageEntity(
                                    imageUri = uriString,
                                    clipVector = vector
                                )
                                contextImageBox.put(entity)
                            }
                        }
                        bitmap.recycle()
                    }
                }

                processedCount++
                withContext(Dispatchers.Main) {
                    progressBarHorizontal.progress = processedCount
                    tvProgressText.text = "Contextualizing $processedCount / $totalImages images"
                }
            }

            withContext(Dispatchers.Main) {
                isProcessing = false
                Toast.makeText(this@MainActivity, "Context Scan Complete!", Toast.LENGTH_LONG).show()
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    progressContainer.visibility = View.GONE
                }, 3000)
            }
        }
    }

    private fun loadThumbnailForClip(uri: Uri): Bitmap? {
        return try {
            val options = BitmapFactory.Options()
            options.inJustDecodeBounds = true // Check dimensions without loading into RAM
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // MobileCLIP only needs 224x224. We load at max 512x512 to save massive amounts of RAM
            val reqWidth = 512
            val reqHeight = 512
            var inSampleSize = 1

            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight: Int = options.outHeight / 2
                val halfWidth: Int = options.outWidth / 2
                while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            // Now load the actual heavily down-sampled image
            options.inJustDecodeBounds = false
            options.inSampleSize = inSampleSize
            options.inPreferredConfig = Bitmap.Config.ARGB_8888 // Required by ONNX

            var bitmap = contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream, null, options)
            }

            // Ensure proper format for ONNX
            if (bitmap != null && bitmap.config != Bitmap.Config.ARGB_8888) {
                val convertedBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
                bitmap.recycle()
                bitmap = convertedBitmap
            }

            return bitmap
        } catch (e: Throwable) {
            // Catching Throwable instead of Exception catches silent OutOfMemoryErrors!
            e.printStackTrace()
            null
        }
    }
// ...

    @SuppressLint("NotifyDataSetChanged")
    private fun showPersonFacesBottomSheet(personId: Long, parentDialog: BottomSheetDialog? = null) {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_person_faces, null)

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_all_faces)
        val btnSetPreview = view.findViewById<Button>(R.id.btn_set_preview)

        recyclerView.layoutManager = GridLayoutManager(this, 4)

        // 1. Set content view FIRST so we can measure the layout
        bottomSheetDialog.setContentView(view)

        // 2. Safely find the internal BottomSheet FrameLayout and set it to 2/3 height
        val bottomSheet = bottomSheetDialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        if (bottomSheet != null) {
            val behavior = BottomSheetBehavior.from(bottomSheet)
            val screenHeight = resources.displayMetrics.heightPixels
            val twoThirdsHeight = (screenHeight * 0.85).toInt()

            val layoutParams = bottomSheet.layoutParams
            layoutParams.height = twoThirdsHeight
            bottomSheet.layoutParams = layoutParams

            behavior.state = BottomSheetBehavior.STATE_EXPANDED
            behavior.skipCollapsed = true
        }

        // 3. Launch background thread to validate images
        lifecycleScope.launch(Dispatchers.IO) {
            val person = personBox.get(personId) ?: return@launch

            val validFaces = mutableListOf<FaceEntity>()
            val ghostFaces = mutableListOf<FaceEntity>()

            // Validate each face attached to this person
            for (face in person.faces) {
                val image = face.image.target
                if (image != null && isImageUriValid(image.imageUri)) {
                    validFaces.add(face)
                } else {
                    ghostFaces.add(face)
                }
            }

            //Database - Deleting data: Auto healing ghost files. Data is removed using .remove()
            // Auto-heal: delete ghost faces and their physical files. If an image is deleted from the
            // device storage, the app cleans up the database by unlinking and removing the associated faces and image records.
            if (ghostFaces.isNotEmpty()) {
                for (ghost in ghostFaces) {
                    // This line tells the Android system to permanently delete that physical cropped image to free up storage space.
                    // It acts as a safety net. If the physical file was already accidentally deleted by a cache-cleaner app, or the file
                    // system denies access, the delete() command would normally cause a fatal crash. The catch block silently absorbs the
                    // error so the app can continue running smoothly
                    try { java.io.File(ghost.faceImagePath).delete() } catch(e: Exception){}
                    //This command interacts directly with the ObjectBox database. It completely deletes the FaceEntity record from the faceBox table.
                    // This destroys the 512-dimension mathematical vector, the bounding box coordinates, and the database row itself.
                    faceBox.remove(ghost)

                    //ObjectBox manages database relationships (like a Face belonging to a Person) through active lists.
                    // This line accesses the PersonEntity (the clustered profile of a recognized person) and breaks the link
                    // by removing the deleted face from the person's list of photos. This guarantees that when the user taps on
                    // that person to view their photos, the app won't try to load a blank or corrupted square where the deleted image used to be.
                    person.faces.remove(ghost) // Unlink from the person
                }
                // Save the person entity changes
                personBox.put(person)
            }

            // 4. Back to the Main Thread to update the UI
            withContext(Dispatchers.Main) {
                // If all photos were deleted, destroy the empty person cluster and close
                if (validFaces.isEmpty()) {
                    personBox.remove(person)
                    bottomSheetDialog.dismiss()
                    parentDialog?.dismiss()
                    showSearchBottomSheet() // Refresh search sheet so the empty cluster vanishes
                    return@withContext
                }

                // Setup the selection list for the adapter
                var selectedPreviewFace: FaceEntity? = null
                val selectedPreviewList = mutableListOf<FaceEntity>()

                val faceAdapter = FaceAdapter(
                    context = this@MainActivity,
                    faces = validFaces, // Use only the strictly validated faces
                    selectedFaces = selectedPreviewList,
                    onClick = { selectedFace ->
                        selectedPreviewFace = selectedFace
                        btnSetPreview.isEnabled = true

                        selectedPreviewList.clear()
                        selectedPreviewList.add(selectedFace)

                        // CRITICAL: Tell the adapter to redraw so the blue ring moves!
                        recyclerView.adapter?.notifyDataSetChanged()
                    },
                    onLongClick = { faceToDelete ->
                        android.app.AlertDialog.Builder(this@MainActivity)
                            .setTitle("Not a face?")
                            .setMessage("Remove this falsely detected image from the database?")
                            .setPositiveButton("Remove") { _, _ ->
                                removeFalsePositiveFace(faceToDelete.id)
                                Toast.makeText(this@MainActivity, "Face removed.", Toast.LENGTH_SHORT).show()
                                bottomSheetDialog.dismiss()

                                // Reopen to refresh the list
                                val updatedPerson = personBox.get(personId)
                                if (updatedPerson != null && updatedPerson.faces.isNotEmpty()) {
                                    showPersonFacesBottomSheet(personId, parentDialog)
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                )

                recyclerView.adapter = faceAdapter

                // 5. Handle saving the new cover photo
                btnSetPreview.setOnClickListener {
                    if (selectedPreviewFace != null) {
                        setCustomCoverFaceForPerson(personId, selectedPreviewFace!!.faceImagePath)

                        bottomSheetDialog.dismiss()
                        parentDialog?.dismiss()
                        showSearchBottomSheet() // Refresh the main search sheet to show the new cover!
                    }
                }

                bottomSheetDialog.show()
            }
        }
    }

    fun setCustomCoverFaceForPerson(personId: Long, chosenFacePath: String) {
        // Fetch the fresh Person entity from ObjectBox
        val person = personBox.get(personId)

        if (person != null) {
            // Update the string path to point to the user's favorite face crop
            person.coverFaceImagePath = chosenFacePath

            // Save it back to the database
            personBox.put(person)

            Toast.makeText(this, "Preview photo updated successfully!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun scanNewlyAddedImages(context: Context) {
        lifecycleScope.launch(Dispatchers.IO) {
            val prefs = context.getSharedPreferences("GalleryPrefs", Context.MODE_PRIVATE)
            // Retrieve the timestamp of the last scan (0L if it's the first time)
            val lastScanTime = prefs.getLong("last_scan_time", 0L)
            var maxScannedTime = lastScanTime

            // 1. Query MediaStore for images added strictly AFTER lastScanTime
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            val selectionArgs = arrayOf((lastScanTime / 1000).toString()) // MediaStore date is in seconds
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

            var newlyProcessedCount = 0

            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val dateAdded = cursor.getLong(dateAddedColumn) * 1000 // Convert back to milliseconds

                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    if (dateAdded > maxScannedTime) {
                        maxScannedTime = dateAdded
                    }

                    // --- THE FIX ---
                    // Reuse your robust processImage() function!
                    // It handles downscaling, prevents duplicates, extracts the face vector,
                    // securely saves the cropped JPG to disk, and saves the FaceEntity to the DB.
                    mlMutex.withLock {
                        processImage(contentUri, dateAdded)
                    }
                    newlyProcessedCount++
                }
            }

            if (newlyProcessedCount > 0) {
                // 2. Trigger your Incremental DBSCAN
                // runFacialRecognitionBatchJob() will automatically find all the new FaceEntities
                // processImage() just created (because their personId == 0) and cluster them!
                runFacialRecognitionBatchJob()

                // 3. Save the newest timestamp so we skip these images next time
                prefs.edit().putLong("last_scan_time", maxScannedTime).apply()

                // Notify UI that the background job is done
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Scan Complete! Extracted & clustered $newlyProcessedCount new images.", Toast.LENGTH_SHORT).show()
                }
            } else {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "No new images found to scan.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    // Helper to safely load Bitmaps across different Android versions
    private fun loadBitmapFromUri(context: Context, uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // Prevents hardware bitmap crashes with ONNX
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    // Conceptual logic for your background job
    fun runFacialRecognitionBatchJob() {
        // Look for 0 (unassigned), not null!
        // Database - reading data: Fetching unclustered faces
        val allUnassignedFaces = faceBox.query().equal(FaceEntity_.personId, 0L).build().find()

        Log.d("ClusterDebug", "Started Job: Found ${allUnassignedFaces.size} faces to cluster")

        if (allUnassignedFaces.isEmpty()) {
            Log.d("ClusterDebug", "No new faces to process.")
            return
        }

        // Using 0.60f to allow a slightly wider margin for error in matching
// 1. Initialize with your ObjectBox instances
        val clusterer = FaceClusterer(
            faceBox = faceBox,
            personBox = personBox,
            eps = 0.58f,
            minPts = 3
        )

// 2. Add your extracted faces to the queue
        clusterer.enqueueFacesForClustering(allUnassignedFaces)

// 3. Run the incremental DBSCAN
// This handles EVERYTHING: finding neighbors, making new PersonEntities,
// setting thumbnails, and saving to the database.
        clusterer.processClusteringQueue()

        Log.d("ClusterDebug", "Incremental DBSCAN finished processing queue.")
    }

    private fun userMergePersons(primaryPersonId: Long, duplicatePersonId: Long) {
        val primaryPerson = personBox.get(primaryPersonId)
        val duplicatePerson = personBox.get(duplicatePersonId)

        if (primaryPerson != null && duplicatePerson != null) {
            // 1. CREATE THE HISTORY RECEIPT
            // Grab all the IDs of the faces we are about to move
            val faceIds = duplicatePerson.faces.map { it.id }.joinToString(",")

            val historyRecord = MergeHistoryEntity(
                primaryPersonId = primaryPersonId,
                primaryCoverPath = primaryPerson.coverFaceImagePath,
                mergedCoverPath = duplicatePerson.coverFaceImagePath,
                faceIdsMoved = faceIds
            )
            mergeHistoryBox.put(historyRecord)

            // 2. Move faces (Your existing code)
            for (face in duplicatePerson.faces) {
                face.person.target = primaryPerson
                faceBox.put(face)
            }

            // 3. Delete the old profile (Your existing code)
            personBox.remove(duplicatePerson)
        }
    }

    private fun unmergePersons(historyId: Long) {
        val historyRecord = mergeHistoryBox.get(historyId) ?: return

        // 1. Recreate the person we originally deleted
        val restoredPerson = PersonEntity(
            name = "Restored Person",
            coverFaceImagePath = historyRecord.mergedCoverPath
        )
        personBox.put(restoredPerson) // This assigns them a brand new ID

        // 2. Extract the IDs from the receipt and move those specific faces back
        val faceIdsToMoveBack = historyRecord.faceIdsMoved.split(",").mapNotNull { it.toLongOrNull() }

        for (faceId in faceIdsToMoveBack) {
            val face = faceBox.get(faceId)
            if (face != null) {
                face.person.target = restoredPerson
                faceBox.put(face)
            }
        }

        // 3. Destroy the history receipt so it disappears from the UI
        mergeHistoryBox.remove(historyRecord)
    }

    /**
     * DEBUG ONLY: Destroys all current clusters, unassigns faces, and recalculates
     * everything from scratch using a specific EPS value.
     */
    private fun debugRecalculateClusters(testEps: Float) {
        Log.d("AppDebug", "--- STARTING EPS RECALCULATION: $testEps ---")

        // 1. Erase all existing "People" and their merge histories
        personBox.removeAll()
        mergeHistoryBox.removeAll()

        // 2. Fetch all extracted faces and detach them from any person
        val allFaces = faceBox.all
        for (face in allFaces) {
            face.person.target = null // Unassign the person
        }
        // Save the unassigned state back to the database in one batch
        faceBox.put(allFaces)

        Log.d("AppDebug", "Cleared old clusters. Processing ${allFaces.size} faces...")

        // 3. Initialize the clusterer with your test EPS value
        val clusterer = FaceClusterer(
            faceBox = faceBox,
            personBox = personBox,
            eps = testEps,
            minPts = 2
        )

        // 4. Run the clustering loop on every face in the database
        clusterer.enqueueFacesForClustering(allFaces)
        clusterer.processClusteringQueue()

        val finalPersonCount = personBox.count()
        Log.d("AppDebug", "--- RECALCULATION COMPLETE ---")
        Log.d("AppDebug", "Resulting Unique People: $finalPersonCount")

        // Update the UI on the main thread when finished
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(this@MainActivity, "Debug Clustering Done (eps: $testEps) -> $finalPersonCount people", Toast.LENGTH_LONG).show()
        }
    }


    /**
     * Deletes a falsely detected face from the database and internal storage.
     */
    private fun removeFalsePositiveFace(faceId: Long) {
        val face = faceBox.get(faceId) ?: return
        val person = face.person.target

        // 1. Delete the physical cropped image file from Android storage to free space
        try {
            val file = java.io.File(face.faceImagePath)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Handle the Person cluster cleanly
        if (person != null) {
            // Unlink the face
            person.faces.remove(face)

            if (person.faces.isEmpty()) {
                // If that hand was the ONLY thing in this cluster, destroy the empty cluster
                personBox.remove(person)
            } else {
                // If the hand happened to be the cover photo for the person, assign a new cover
                if (person.coverFaceImagePath == face.faceImagePath) {
                    person.coverFaceImagePath = person.faces.first().faceImagePath
                }
                personBox.put(person) // Save changes
            }
        }

        // 3. Finally, delete the Face from the database
        faceBox.remove(face)
    }


    @SuppressLint("MissingInflatedId")
    private fun showMergeHistoryBottomSheet() {
        val bottomSheetDialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.bottom_sheet_person_faces, null) // You can reuse an existing simple recyclerview layout
        bottomSheetDialog.setContentView(view)

        // Set the title
        view.findViewById<TextView>(R.id.tv_title)?.text = "Merged Persons"

        val recyclerView = view.findViewById<RecyclerView>(R.id.recycler_all_faces)
        recyclerView.layoutManager = LinearLayoutManager(this)

        // Grab all history records
        val historyList = mergeHistoryBox.all

        if (historyList.isEmpty()) {
            Toast.makeText(this, "No merged profiles found.", Toast.LENGTH_SHORT).show()
            return
        }

        // A simple anonymous adapter to handle the green bordered rows
        val adapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): RecyclerView.ViewHolder {
                val rowView = layoutInflater.inflate(R.layout.row_merge_history, parent, false)
                return object : RecyclerView.ViewHolder(rowView) {}
            }

            override fun getItemCount() = historyList.size

            override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
                val history = historyList[position]
                val ivPrimary = holder.itemView.findViewById<ImageView>(R.id.iv_primary_face)
                val ivMerged = holder.itemView.findViewById<ImageView>(R.id.iv_merged_face)

                // Load images using Glide or BitmapFactory
                com.bumptech.glide.Glide.with(this@MainActivity).load(history.primaryCoverPath).into(ivPrimary)
                com.bumptech.glide.Glide.with(this@MainActivity).load(history.mergedCoverPath).into(ivMerged)

                holder.itemView.setOnClickListener {
                    // Show an alert dialog to confirm the unmerge when the green border is tapped
                    android.app.AlertDialog.Builder(this@MainActivity)
                        .setTitle("Unmerge Faces?")
                        .setMessage("This will separate these photos back into two distinct people.")
                        .setPositiveButton("Unmerge") { _, _ ->
                            unmergePersons(history.id)
                            Toast.makeText(this@MainActivity, "Successfully Unmerged!", Toast.LENGTH_SHORT).show()
                            bottomSheetDialog.dismiss()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            }
        }

        recyclerView.adapter = adapter
        bottomSheetDialog.show()
    }

    private fun isImageUriValid(uriString: String): Boolean {
        return try {
            val uri = Uri.parse(uriString)

            // 1. Android 10+ (API 29) "Recycle Bin" Check
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val projection = arrayOf(
                    MediaStore.Images.Media.IS_TRASHED,
                    MediaStore.Images.Media.IS_PENDING
                )

                contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val isTrashed = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_TRASHED)) == 1
                        val isPending = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Images.Media.IS_PENDING)) == 1

                        // If the gallery put it in the trash bin, consider it dead.
                        if (isTrashed || isPending) {
                            return false
                        }
                    } else {
                        return false // The database record is completely gone
                    }
                }
            } else {
                // Android 9 and below (No trash bin feature)
                contentResolver.query(uri, arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return false
                }
            }

            // 2. Final Physical Check (Ensures file isn't 0 bytes or corrupted)
            contentResolver.openInputStream(uri)?.use { stream ->
                return stream.available() > 0
            } ?: false

        } catch (e: Exception) {
            // Any SecurityException or FileNotFoundException means the file is completely inaccessible.
            false
        }
    }
}