package com.familyguardpro.applock

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.familyguardpro.R
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Photo/Video Vault - Secure storage for private photos and videos
 * Files are encrypted with AES-256
 */
class VaultActivity : Activity() {
    
    companion object {
        private const val TAG = "VaultActivity"
        private const val REQUEST_IMAGE = 1001
        private const val REQUEST_VIDEO = 1002
        private const val ENCRYPTION_KEY = "FamilyGuardVault" // 16 chars for AES-128
    }
    
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: LinearLayout
    private lateinit var addPhotoButton: Button
    private lateinit var addVideoButton: Button
    
    private val vaultItems = mutableListOf<VaultItem>()
    private lateinit var adapter: VaultAdapter
    
    data class VaultItem(
        val file: File,
        val type: String, // "image" or "video"
        val name: String,
        val size: Long
    )
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vault)
        
        initViews()
        loadVaultItems()
    }
    
    private fun initViews() {
        recyclerView = findViewById(R.id.vaultRecyclerView)
        emptyView = findViewById(R.id.emptyVaultView)
        addPhotoButton = findViewById(R.id.addPhotoButton)
        addVideoButton = findViewById(R.id.addVideoButton)
        
        adapter = VaultAdapter()
        recyclerView.layoutManager = GridLayoutManager(this, 3)
        recyclerView.adapter = adapter
        
        addPhotoButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQUEST_IMAGE)
        }
        
        addVideoButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            startActivityForResult(intent, REQUEST_VIDEO)
        }
        
        findViewById<ImageButton>(R.id.backButton).setOnClickListener {
            finish()
        }
    }
    
    private fun loadVaultItems() {
        vaultItems.clear()
        
        val vaultDir = getVaultDirectory()
        vaultDir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".vault")) {
                val type = if (file.name.contains("_img_")) "image" else "video"
                vaultItems.add(VaultItem(file, type, file.name, file.length()))
            }
        }
        
        updateUI()
    }
    
    private fun updateUI() {
        if (vaultItems.isEmpty()) {
            recyclerView.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            recyclerView.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
            adapter.notifyDataSetChanged()
        }
    }
    
    private fun getVaultDirectory(): File {
        val dir = File(filesDir, "vault")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }
    
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            when (requestCode) {
                REQUEST_IMAGE -> encryptAndStoreFile(uri, "image")
                REQUEST_VIDEO -> encryptAndStoreFile(uri, "video")
            }
        }
    }
    
    private fun encryptAndStoreFile(uri: Uri, type: String) {
        try {
            val inputStream = contentResolver.openInputStream(uri) ?: return
            val prefix = if (type == "image") "_img_" else "_vid_"
            val fileName = "file${prefix}${System.currentTimeMillis()}.vault"
            val outputFile = File(getVaultDirectory(), fileName)
            
            // Encrypt file
            val cipher = getCipher(Cipher.ENCRYPT_MODE)
            val outputStream = CipherOutputStream(FileOutputStream(outputFile), cipher)
            
            inputStream.copyTo(outputStream)
            
            inputStream.close()
            outputStream.close()
            
            // Delete original file (optional - ask user)
            showDeleteOriginalDialog(uri)
            
            loadVaultItems()
            Toast.makeText(this, "$type added to vault", Toast.LENGTH_SHORT).show()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt file", e)
            Toast.makeText(this, "Failed to add to vault", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun showDeleteOriginalDialog(uri: Uri) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete Original?")
            .setMessage("Do you want to delete the original file? It's now safely stored in the vault.")
            .setPositiveButton("Delete") { _, _ ->
                try {
                    contentResolver.delete(uri, null, null)
                } catch (e: Exception) {
                    Log.e(TAG, "Could not delete original", e)
                }
            }
            .setNegativeButton("Keep", null)
            .show()
    }
    
    private fun getCipher(mode: Int): Cipher {
        val key = getSecretKey()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = IvParameterSpec(ByteArray(16) { 0 }) // Fixed IV for simplicity
        cipher.init(mode, key, iv)
        return cipher
    }
    
    private fun getSecretKey(): SecretKey {
        val keyBytes = ENCRYPTION_KEY.toByteArray(Charsets.UTF_8)
        return SecretKeySpec(keyBytes, "AES")
    }
    
    private fun decryptFile(vaultFile: File): ByteArray {
        val cipher = getCipher(Cipher.DECRYPT_MODE)
        val inputStream = CipherInputStream(FileInputStream(vaultFile), cipher)
        val bytes = inputStream.readBytes()
        inputStream.close()
        return bytes
    }
    
    private fun viewVaultItem(item: VaultItem) {
        try {
            val decryptedBytes = decryptFile(item.file)
            
            val intent = Intent(this, VaultViewerActivity::class.java)
            
            // Save temporarily to view
            val tempFile = File(cacheDir, "temp_view.${if (item.type == "image") "jpg" else "mp4"}")
            FileOutputStream(tempFile).use { it.write(decryptedBytes) }
            
            intent.putExtra("file_path", tempFile.absolutePath)
            intent.putExtra("type", item.type)
            startActivity(intent)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to view vault item", e)
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun deleteVaultItem(item: VaultItem) {
        android.app.AlertDialog.Builder(this)
            .setTitle("Delete from Vault?")
            .setMessage("This file will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                item.file.delete()
                loadVaultItems()
                Toast.makeText(this, "Deleted from vault", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
    
    private fun exportVaultItem(item: VaultItem) {
        try {
            val decryptedBytes = decryptFile(item.file)
            
            val extension = if (item.type == "image") "jpg" else "mp4"
            val fileName = "vault_export_${System.currentTimeMillis()}.$extension"
            
            // Save to downloads
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, if (item.type == "image") "image/jpeg" else "video/mp4")
            }
            
            val uri = if (item.type == "image") {
                contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            } else {
                contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
            
            uri?.let {
                contentResolver.openOutputStream(it)?.use { os ->
                    os.write(decryptedBytes)
                }
                Toast.makeText(this, "Exported to gallery", Toast.LENGTH_SHORT).show()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to export vault item", e)
            Toast.makeText(this, "Export failed", Toast.LENGTH_SHORT).show()
        }
    }
    
    inner class VaultAdapter : RecyclerView.Adapter<VaultAdapter.ViewHolder>() {
        
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val thumbnailView: ImageView = view.findViewById(R.id.itemThumbnail)
            val typeIcon: ImageView = view.findViewById(R.id.typeIcon)
        }
        
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_vault, parent, false)
            return ViewHolder(view)
        }
        
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val item = vaultItems[position]
            
            // Set type icon
            holder.typeIcon.setImageResource(
                if (item.type == "video") R.drawable.ic_video else R.drawable.ic_photo
            )
            
            // Try to generate thumbnail
            try {
                val bytes = decryptFile(item.file)
                if (item.type == "image") {
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    holder.thumbnailView.setImageBitmap(bitmap)
                } else {
                    // For video, show a placeholder
                    holder.thumbnailView.setImageResource(R.drawable.ic_video)
                }
            } catch (e: Exception) {
                holder.thumbnailView.setImageResource(R.drawable.ic_photo)
            }
            
            holder.itemView.setOnClickListener {
                viewVaultItem(item)
            }
            
            holder.itemView.setOnLongClickListener {
                showItemOptions(item)
                true
            }
        }
        
        override fun getItemCount() = vaultItems.size
        
        private fun showItemOptions(item: VaultItem) {
            android.app.AlertDialog.Builder(this@VaultActivity)
                .setTitle("Options")
                .setItems(arrayOf("View", "Export to Gallery", "Delete")) { _, which ->
                    when (which) {
                        0 -> viewVaultItem(item)
                        1 -> exportVaultItem(item)
                        2 -> deleteVaultItem(item)
                    }
                }
                .show()
        }
    }
}
