package com.example.galleryapp

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.request.RequestOptions

// Η κλάση ImageFullActivity είναι μια δραστηριότητα που εμφανίζει μια εικόνα σε πλήρη οθόνη, λαμβάνοντας δεδομένα από intent.
class ImageFullActivity : AppCompatActivity() {

    // Η μέθοδος onCreate καλείται κατά τη δημιουργία της δραστηριότητας και ρυθμίζει την προβολή της εικόνας.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_image_full)

        val fullImageView = findViewById<ImageView>(R.id.iv_full_image)

        val imagePath = intent.getStringExtra("image_path") ?: ""
        val imageTitle = intent.getStringExtra("image_title") ?: "Image"

        supportActionBar?.title = imageTitle

        if (fullImageView == null) {
            Log.e("ImageFullActivity", "ImageView with ID 'iv_full_image' NOT FOUND!")
            Toast.makeText(this, "Layout error", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        if (imagePath.isNotEmpty()) {
            try {
                // ✅ FIXED: Use Glide instead of setImageURI
                Glide.with(this)
                    .load(imagePath)                    // works with content URI
                    .apply(RequestOptions().fitCenter()) // matches your scaleType="fitCenter"
                    .into(fullImageView)

                Log.d("ImageFullActivity", "✅ Loaded with Glide: $imagePath")
            } catch (e: Exception) {
                Log.e("ImageFullActivity", "Failed to load image", e)
                Toast.makeText(this, "Cannot display image", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "No image path received", Toast.LENGTH_SHORT).show()
        }
    }
}