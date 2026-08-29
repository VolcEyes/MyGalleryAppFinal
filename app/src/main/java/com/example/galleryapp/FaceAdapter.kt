package com.example.galleryapp

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import android.content.res.ColorStateList
import android.graphics.Color
import androidx.core.content.ContextCompat

// Define the FaceAdapter class, inheriting from RecyclerView.Adapter.
// It uses FaceViewHolder to hold the views.
class FaceAdapter(
    // A reference to the application context, needed for inflating layouts and loading images.
    private val context: Context,
    // A list of FaceEntity objects representing all the faces to be displayed in the list.
    private val faces: List<FaceEntity>,
    // A mutable list containing the FaceEntity objects that the user has currently selected.
    private val selectedFaces: MutableList<FaceEntity>,
    // A lambda function (callback) to be executed when a face item is single-clicked.
    private val onClick: (FaceEntity) -> Unit,
    private val onLongClick: ((FaceEntity) -> Unit)? = null
) : RecyclerView.Adapter<FaceAdapter.FaceViewHolder>() {

    // 1. Declare the selection ring here inside the ViewHolder
    inner class FaceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        //This line links the Kotlin code to the specific UI element where the face thumbnail will be drawn
        val ivFace: ImageView = view.findViewById(R.id.img_face_preview)

        // Find the new overlay ring
        val selectionRing: View = view.findViewById(R.id.iv_selection_ring)
    }

    // This method is called by the RecyclerView when it needs a new ViewHolder instance to represent an item.
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FaceViewHolder {
        // Inflate the XML layout file (row_face_preview.xml) into a View object.
        // 'parent' is the RecyclerView itself. 'false' means we don't attach the view to the parent yet;
        // the RecyclerView will handle that when necessary.
        val view = LayoutInflater.from(context).inflate(R.layout.row_face_preview, parent, false)
        // Return a new instance of our custom FaceViewHolder, passing in the inflated view.
        return FaceViewHolder(view)
    }
    // This method is called by the RecyclerView to display the data at the specified position.
    override fun onBindViewHolder(holder: FaceViewHolder, position: Int) {
        // Retrieve the FaceEntity object corresponding to the current position in the list.
        val face = faces[position]
        // Load the face image
        // Use Glide to load the image from the file path stored in 'face.faceImagePath'
        // and asynchronously display it in the 'ivFace' ImageView held by the ViewHolder.
        Glide.with(context).load(face.faceImagePath).into(holder.ivFace)

        // Check if the current face object exists within the 'selectedFaces' list.
        // This is the core logic for rendering the multi-selection state.
        //This condition cross-references your master list of chosen faces to determine exactly
        // how the current row should look right now.
        //If the face is in the list, it guarantees the selection ring is explicitly turned on.
        //Equally important is the else block, which acts as a required visual reset to hide the
        // ring if the face is not selected.
        if (selectedFaces.contains(face)) {
            // If the face is in the selected list, make the overlay ring visible.
            holder.selectionRing.visibility = View.VISIBLE
        } else {
            holder.selectionRing.visibility = View.GONE
        }

        // Attach a standard click listener to the entire row item (holder.itemView).
        holder.itemView.setOnClickListener {
            // When the user taps the item, trigger the 'onClick' lambda function provided in the constructor,
            // passing the exact FaceEntity that was tapped back to the parent Fragment/Activity.
            onClick(face)
        }

        // Attach a long-click listener to the entire row item.
        holder.itemView.setOnLongClickListener {
            // Use the safe call operator (?.) to invoke 'onLongClick' only if a function was actually provided.
            // This prevents a crash if the developer didn't pass a long-click behavior.
            onLongClick?.invoke(face)
            true
        }
    }

    override fun getItemCount(): Int = faces.size
}