package com.example.galleryapp

import io.objectbox.Box
import io.objectbox.BoxStore

class FaceClusterer(
    // The ObjectBox collection storing individual recognized faces
    private val faceBox: Box<FaceEntity>,
    // The ObjectBox collection storing grouped "People" identities
    private val personBox: Box<PersonEntity>,
    // The maximum distance between two face vectors to be considered a match (Epsilon)
    private val eps: Float = 0.63f,
    // The minimum number of similar faces required to form a brand new cluster
    private val minPts: Int = 2
) {
    // A temporary memory queue to hold newly detected faces before they are clustered
    private val clusteringQueue = mutableListOf<FaceEntity>()

    /**
     * Called after FaceNet extracts vectors. Pushes faces into the staging queue.
     */
    fun enqueueFacesForClustering(faces: List<FaceEntity>) {
        clusteringQueue.addAll(faces)
    }

    /**
     * The main Incremental DBSCAN loop.
     * Run this when all active face detection jobs are finished.
     */
    fun processClusteringQueue() {
        // Exit early if there is no work to do
        if (clusteringQueue.isEmpty()) return

        // Holds faces that don't meet the minPts threshold initially (considered "noise" for now)
        val deferredFaces = mutableListOf<FaceEntity>()
        val iterator = clusteringQueue.iterator()

        // Iterate safely through the queue
        while (iterator.hasNext()) {
            val targetFace = iterator.next()

            // Fetch neighbors from the database that fall within the 'eps' distance
            val similarFaces = findSimilarFaces(targetFace)

            if (similarFaces.size < minPts) {
                // Not enough matches to form a cluster right now.
                // Defer it to check again at the very end in case new clusters formed during this loop.
                deferredFaces.add(targetFace)
            } else {
                // Threshold met. Assign to existing person or create a new one.
                assignOrCluster(targetFace, similarFaces)
            }

            // Remove the face from the active queue once it has been processed
            iterator.remove()
        }

        // Retry deferred faces to see if they can join newly created clusters.
        retryDeferredFaces(deferredFaces)
    }

    /**
     * Queries ObjectBox for the nearest vectors and filters them strictly by the EPS threshold.
     */
    private fun findSimilarFaces(targetFace: FaceEntity): List<FaceEntity> {
        // Ensure the face has a valid embedding before searching
        val targetVector = targetFace.faceVector ?: return emptyList()
        val similarFaces = mutableListOf<FaceEntity>()

        // 1. Use ObjectBox HNSW Index to quickly find the top 100 closest faces.
        val maxNeighborsToCheck = 100
        val nearestFaces = faceBox.query(FaceEntity_.faceVector.nearestNeighbors(targetVector, maxNeighborsToCheck))
            .build()
            .find()

        // 2. Filter the results strictly by your EPS distance threshold
        for (compareFace in nearestFaces) {
            // Δεν καταμετράει τον εαυτό του το targetFace. Skip comparing the target face to itself if it's already in the database
            if (compareFace.id == targetFace.id) continue

            val compareVector = compareFace.faceVector ?: continue

            // Μετατροπή σκορ ομοιότητας σε απόσταση 3. Calculate distance: 1.0 - Cosine Similarity
            val distance = 1.0f - calculateCosineSimilarity(targetVector, compareVector)

            // 4. If the distance is smaller than or equal to EPS, it is a valid neighbor
            if (distance <= eps) {
                similarFaces.add(compareFace)
            }
        }

        return similarFaces
    }

    /**
     * Calculates the Cosine Similarity between two multi-dimensional float arrays.
     */
    private fun calculateCosineSimilarity(v1: FloatArray, v2: FloatArray): Float {
        var dotProduct = 0.0f
        var normA = 0.0f
        var normB = 0.0f

        // πολλαπλασιάζεται η τιμή του 1ου χαρακτηριστικού του Προσώπου Α με το 1ο χαρακτηριστικό του Προσώπου Β,
        // το 2ο με το 2ο, το 3ο με το 3ο, και όλα αυτά τα γινόμενα προστίθενται μαζί.
        for (i in v1.indices) {
            dotProduct += v1[i] * v2[i]

        // Για να βρούμε το μήκος ενός διανύσματος, υψώνουμε κάθε στοιχείο του στο τετράγωνο
            normA += v1[i] * v1[i]
            normB += v2[i] * v2[i]
        }


        //Εάν συγκρίναμε μόνο το dotProduct, το αποτέλεσμα θα επηρεαζόταν από το "πόσο έντονα" είναι τα χαρακτηριστικά (το μήκος του διανύσματος) και όχι μόνο από το "πόσο μοιάζουν".
        //Η διαίρεση του εσωτερικού γινομένου με τα μήκη των διανυσμάτων (dotProduct / (Math.sqrt(normA) * Math.sqrt(normB))) "κανονικοποιεί" το αποτέλεσμα.
        //
        //Αυτό σημαίνει ότι μετράμε πλέον μόνο τη γωνία μεταξύ των δύο διανυσμάτων. Εάν η τελική διαίρεση βγάλει το νούμερο 1.0,
        // σημαίνει ότι η γωνία είναι 0 μοίρες: τα δύο διανύσματα κοιτάζουν ακριβώς προς την ίδια κατεύθυνση στον πολυδιάστατο χώρο, άρα η Τεχνητή Νοημοσύνη θεωρεί ότι βλέπει το ίδιο ακριβώς πρόσωπο.
        // Prevent division by zero, then return the normalized similarity score
        return if (normA == 0.0f || normB == 0.0f) 0.0f else (dotProduct / (Math.sqrt(normA.toDouble()) * Math.sqrt(normB.toDouble()))).toFloat()
    }

    /**
     * Determines whether to add the face to an existing person or create a new one.
     */
    private fun assignOrCluster(targetFace: FaceEntity, similarFaces: List<FaceEntity>) {
        // 1. Filter the neighborhood to see if any faces already have a 'Person' assigned
        val assignedNeighbors = similarFaces.filter { it.person.target != null }

        if (assignedNeighbors.isNotEmpty()) {
            // 2. Existing cluster found!
            val targetVector = targetFace.faceVector ?: return

            // Find the single neighbor with the absolute highest similarity score to resolve conflicts(Clusters might be
            // too close and contain faces that belong to more than one cluster).
            val mostSimilarNeighbor = assignedNeighbors.maxByOrNull { neighbor ->
                //he ?: is called the Elvis Operator. It handles errors. If for some reason the neighbor's vector
                //is missing or corrupted (null), the return@maxByOrNull -1f kicks in. It tells the loop: "Give this
                // broken face a score of -1.0 (the lowest possible score) and skip to the next face." This prevents the app from crashing.
                val neighborVector = neighbor.faceVector ?: return@maxByOrNull -1f
                calculateCosineSimilarity(targetVector, neighborVector)
            }

            // 3. Assign our target face to this existing Person
            val existingPerson = mostSimilarNeighbor?.person?.target
            if (existingPerson != null) {
                targetFace.person.target = existingPerson
                faceBox.put(targetFace) // Save the update to the database
            }
        } else {
            // Database - Writing Data: Creating a new person cluster
            // 4. No existing person found among neighbors. Create a new cluster!
            val newPerson = PersonEntity(name = "Unknown Person")

            // Generate a thumbnail path for the UI
            newPerson.coverFaceImagePath = generatePersonThumbnail(targetFace)

            // Save the new person to the DB so it gets an ID
            personBox.put(newPerson)

            // 5. Assign the target face to the new person
            targetFace.person.target = newPerson

            // This line takes the updated targetFace (which now holds a reference to newPerson) and saves it back into the ObjectBox database
            // using the faceBox. This persists the relationship so the face is officially stored as belonging to that person.
            faceBox.put(targetFace)


            // 6. Assign ALL the unassigned neighbors to this new person as well
            // THIS LOOP RESCUES THE OLD NOISE FACES!
            for (neighbor in similarFaces) {
                neighbor.person.target = newPerson
                faceBox.put(neighbor) // Update the neighbor in the database
            }
        }
    }

    /**
     * Generates a thumbnail for the newly created person cluster.
     */
    private fun generatePersonThumbnail(face: FaceEntity): String {
        // Currently returns the existing face image path as a stub
        return face.faceImagePath
    }

    /**
     * Re-evaluates faces that initially failed to meet the clustering threshold.
     */
    private fun retryDeferredFaces(deferredFaces: List<FaceEntity>) {
        for (deferredFace in deferredFaces) {
            // 1. Check the neighborhood again. The database has likely changed.
            val similarFaces = findSimilarFaces(deferredFace)

            // 2. Check if any of these similar faces now belong to a Person
            val hasAssignedNeighbor = similarFaces.any { it.person.target != null }

            // 3. If it now has a person nearby, OR if it finally meets the minPts threshold, process it.
            if (hasAssignedNeighbor || similarFaces.size >= minPts) {
                assignOrCluster(deferredFace, similarFaces)
            }
        }
    }
}