package com.familyguardpro.applock

import android.app.Activity
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.MediaController
import android.widget.VideoView
import android.view.View
import com.familyguardpro.R
import java.io.File

/**
 * Vault Viewer - View photos and videos from the vault
 */
class VaultViewerActivity : Activity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault_viewer)
        
        val filePath = intent.getStringExtra("file_path") ?: run {
            finish()
            return
        }
        val type = intent.getStringExtra("type") ?: "image"
        
        val imageView = findViewById<ImageView>(R.id.imageView)
        val videoView = findViewById<VideoView>(R.id.videoView)
        val backButton = findViewById<ImageButton>(R.id.backButton)
        
        backButton.setOnClickListener {
            finish()
        }
        
        when (type) {
            "image" -> {
                imageView.visibility = View.VISIBLE
                videoView.visibility = View.GONE
                
                val file = File(filePath)
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                imageView.setImageBitmap(bitmap)
            }
            "video" -> {
                imageView.visibility = View.GONE
                videoView.visibility = View.VISIBLE
                
                val uri = Uri.fromFile(File(filePath))
                videoView.setVideoURI(uri)
                
                val mediaController = MediaController(this)
                mediaController.setAnchorView(videoView)
                videoView.setMediaController(mediaController)
                videoView.start()
            }
        }
    }
    
    override fun onPause() {
        super.onPause()
        // Clean up temp file
        val filePath = intent.getStringExtra("file_path")
        filePath?.let {
            try {
                File(it).delete()
            } catch (e: Exception) {}
        }
    }
}
