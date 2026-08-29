// Το πακέτο που περιέχει όλες τις κλάσεις της εφαρμογής
package com.example.galleryapp

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

// Αυτή είναι η κύρια κλάση ImageAdapter που λειτουργεί ως προσαρμογέας (Adapter) για το RecyclerView.
// Κληρονομεί από RecyclerView.Adapter<ImageAdapter.ImageViewHolder> ώστε να παρέχει views και δεδομένα.
// Δέχεται Context (για Glide και intents) και ArrayList<Image> (τα δεδομένα των εικόνων).
class ImageAdapter(private var context: Context, private var imagesList: ArrayList<Image>) :
    RecyclerView.Adapter<ImageAdapter.ImageViewHolder>() {

    // Η εσωτερική κλάση ImageViewHolder είναι υπεύθυνη για την αποθήκευση των references στα views κάθε item.
    // Αυτή η τεχνική (ViewHolder pattern) βελτιώνει δραματικά την απόδοση του RecyclerView.
    class ImageViewHolder(itemView: View):RecyclerView.ViewHolder(itemView){
        // Μεταβλητή που θα κρατά την αναφορά στο ImageView του custom item (αποφεύγουμε επαναλαμβανόμενα findViewById)
        var image: ImageView?=null

        // Το init block εκτελείται αμέσως μετά τη δημιουργία του αντικειμένου ImageViewHolder
        init {
            // Συνδέουμε την μεταβλητή image με το πραγματικό ImageView από το layout χρησιμοποιώντας το ID row_image
            image=itemView.findViewById(R.id.row_image)
        }
    }

    // Αυτή η μέθοδος καλείται από το RecyclerView όταν χρειάζεται να δημιουργηθεί ένα καινούργιο ViewHolder (νέο row/item).
    // Εδώ φορτώνουμε το XML layout και επιστρέφουμε τον ViewHolder που θα επαναχρησιμοποιηθεί.
    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): ImageViewHolder {
        // Δημιουργούμε LayoutInflater από το context του γονικού ViewGroup (parent)
        val inflater = LayoutInflater.from(parent.context)
        // Φορτώνουμε το layout row_custom_recycler_item και δημιουργούμε View (χωρίς να το προσαρτήσουμε αμέσως στο parent)
        val view = inflater.inflate(R.layout.row_custom_recycler_item,parent,false)
        // Δημιουργούμε και επιστρέφουμε νέο ImageViewHolder περνώντας το inflated view
        return ImageViewHolder(view)
    }

    // Αυτή η μέθοδος καλείται κάθε φορά που το RecyclerView πρέπει να γεμίσει (bind) δεδομένα σε ένα υπάρχον ViewHolder.
    // Εδώ φορτώνουμε την εικόνα με Glide και ρυθμίζουμε το click listener για πλήρη προβολή.
    override fun onBindViewHolder(
        holder: ImageViewHolder,
        position: Int
    ) {
        // Παίρνουμε το τρέχον αντικείμενο Image από τη λίστα με βάση τη θέση
        val currentImage=imagesList[position]

        // Χρησιμοποιούμε Glide για να φορτώσουμε την εικόνα από το content URI με την επιλογή centerCrop
        Glide.with(context)
            .load(currentImage.imagePath)
            .apply(RequestOptions().centerCrop())
            .into(holder.image!!)

        // Προσθέτουμε click listener στο ImageView ώστε όταν πατηθεί να ανοίξει η δραστηριότητα πλήρους εικόνας
        holder.image!!.setOnClickListener {
            // Δημιουργούμε Intent για εκκίνηση της ImageFullActivity
            val intent= Intent(context, ImageFullActivity::class.java).apply {
                // Προσθέτουμε extra δεδομένα: το path της εικόνας (content URI)
                putExtra("image_path", imagesList[position].imagePath)   // content URI
                // Προσθέτουμε extra δεδομένα: τον τίτλο της εικόνας για την action bar
                putExtra("image_title", imagesList[position].imageTitle)
            }
            // Ξεκινάμε την ImageFullActivity περνώντας το intent
            context.startActivity(intent)
        }

    }

    // Αυτή η μέθοδος καλείται από το RecyclerView για να μάθει πόσα items υπάρχουν συνολικά.
    // Επιστρέφει το μέγεθος της λίστας ώστε να δημιουργηθούν τα σωστά items.
    override fun getItemCount(): Int {

        return imagesList.size
    }

    /**
     * Updates the displayed list (used for live filtering).
     */
    fun updateList(newList: ArrayList<Image>) {
        imagesList = newList
        notifyDataSetChanged()
    }


}