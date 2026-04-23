package com.familyguardpro.applock

import android.app.Activity
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Intruder Photos Activity - View captured intruder selfies
 */
class IntruderPhotosActivity : Activity() {
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    
    private val intruderPhotos = mutableListOf<IntruderPhoto>()
    private lateinit var adapter: IntruderAdapter
    
    data class IntruderPhoto(
        val file: File,
        val timestamp: Long,
        val dateString: String
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intruder_photos)
        
        initViews()
        loadIntruderPhotos()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.intruderRecyclerView)
        emptyView = findViewById(R.id.emptyIntruderView)
        
        adapter = IntruderAdapter()
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter
        
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }
        
        findViewById<ImageButton>(R.id.clearAllButton).setOnClickListener {
            clearAllPhotos()
        }
    }
    
    private fun loadIntruderPhotos() {
        intruderPhotos.clear()
        
        val intruderDir = File(filesDir, "intruders")
        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault())
        
        intruderDir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach { file ->
            if (file.isFile && file.name.startsWith("intruder_") && file.name.endsWith(".jpg")) {
                val timestamp = file.lastModified()
                intruderPhotos.add(IntruderPhoto(
                    file, 
                    timestamp,
                    dateFormat.format(Date(timestamp))
                ))
            }
        }
        
        updateUI()
    }
    
    private fun updateUI() {
        if (intruderPhotos.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }
    
    private fun clearAllPhotos() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Clear All?")
            .setMessage("Delete all intruder photos?")
            .setPositiveButton("Delete All") { _, _ ->
                val intruderDir = File(filesDir, "intruders")
                intruderDir.listFiles()?.forEach { it.delete() }
                loadIntruderPhotos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun deletePhoto(photo: IntruderPhoto) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete Photo?")
            .setMessage("Delete this intruder photo?")
            .setPositiveButton("Delete") { _, _ ->
                photo.file.delete()
                loadIntruderPhotos()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    inner class IntruderAdapter : RecyclerView.Adapter<IntruderAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val photoView: ImageView = view.findViewById(R.id.intruderPhoto)
            val dateView: TextView = view.findViewById(R.id.intruderDate)
            val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_intruder, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val photo = intruderPhotos[position]
            
            holder.dateView.text = photo.dateString
            
            val bitmap = BitmapFactory.decodeFile(photo.file.absolutePath)
            holder.photoView.setImageBitmap(bitmap)
            
            holder.deleteButton.setOnClickListener {
                deletePhoto(photo)
            }
            
            holder.itemView.setOnClickListener {
                // Show full screen
                showFullScreenPhoto(photo)
            }
        }
        
        override fun getItemCount() = intruderPhotos.size
    }
    
    private fun showFullScreenPhoto(photo: IntruderPhoto) {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setContentView(R.layout.dialog_fullscreen_image)
        
        val imageView = dialog.findViewById<ImageView>(R.id.fullscreenImage)
        val bitmap = BitmapFactory.decodeFile(photo.file.absolutePath)
        imageView.setImageBitmap(bitmap)
        
        imageView.setOnClickListener { dialog.dismiss() }
        
        dialog.show()
    }
}
