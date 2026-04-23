package com.familyguardpro.deviceowner

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.IBinder
import android.os.StatFs
import android.util.Log
import com.familyguardpro.services.WebSocketSyncService
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * FileManagerService - Device Owner file management service
 * 
 * Provides file browsing and deletion capabilities for Device Owner mode.
 * Parent can browse child's file system and delete files remotely.
 */
class FileManagerService : Service() {

    companion object {
        private const val TAG = "FileManagerService"
        
        const val ACTION_LIST_FILES = "com.familyguardpro.ACTION_LIST_FILES"
        const val ACTION_DELETE_FILE = "com.familyguardpro.ACTION_DELETE_FILE"
        const val ACTION_DELETE_FILES = "com.familyguardpro.ACTION_DELETE_FILES"
        const val ACTION_GET_STORAGE_INFO = "com.familyguardpro.ACTION_GET_STORAGE_INFO"
        
        fun listFiles(context: Context, path: String, requestId: String) {
            val intent = Intent(context, FileManagerService::class.java).apply {
                action = ACTION_LIST_FILES
                putExtra("path", path)
                putExtra("requestId", requestId)
            }
            context.startService(intent)
        }
        
        fun deleteFile(context: Context, filePath: String, requestId: String) {
            val intent = Intent(context, FileManagerService::class.java).apply {
                action = ACTION_DELETE_FILE
                putExtra("filePath", filePath)
                putExtra("requestId", requestId)
            }
            context.startService(intent)
        }
        
        fun deleteFiles(context: Context, filePaths: List<String>, requestId: String) {
            val intent = Intent(context, FileManagerService::class.java).apply {
                action = ACTION_DELETE_FILES
                putExtra("filePaths", ArrayList(filePaths))
                putExtra("requestId", requestId)
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_LIST_FILES -> {
                val path = intent.getStringExtra("path") ?: "/sdcard"
                val requestId = intent.getStringExtra("requestId") ?: ""
                handleListFiles(path, requestId)
            }
            ACTION_DELETE_FILE -> {
                val filePath = intent.getStringExtra("filePath") ?: return START_NOT_STICKY
                val requestId = intent.getStringExtra("requestId") ?: ""
                handleDeleteFile(filePath, requestId)
            }
            ACTION_DELETE_FILES -> {
                @Suppress("UNCHECKED_CAST")
                val filePaths = intent.getStringArrayListExtra("filePaths") ?: return START_NOT_STICKY
                val requestId = intent.getStringExtra("requestId") ?: ""
                handleDeleteFiles(filePaths, requestId)
            }
            ACTION_GET_STORAGE_INFO -> {
                val requestId = intent.getStringExtra("requestId") ?: ""
                handleGetStorageInfo(requestId)
            }
        }
        return START_NOT_STICKY
    }

    /**
     * List files in a directory
     */
    private fun handleListFiles(path: String, requestId: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Listing files in: $path")
                
                val directory = File(path)
                
                if (!directory.exists()) {
                    sendError("Directory does not exist: $path", requestId)
                    return@launch
                }
                
                if (!directory.isDirectory) {
                    sendError("Path is not a directory: $path", requestId)
                    return@launch
                }
                
                if (!directory.canRead()) {
                    sendError("Cannot read directory: $path", requestId)
                    return@launch
                }
                
                val files = directory.listFiles() ?: emptyArray()
                val filesJson = JSONArray()
                
                // Sort: directories first, then files, alphabetically
                val sortedFiles = files.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                
                for (file in sortedFiles) {
                    try {
                        val fileJson = JSONObject().apply {
                            put("name", file.name)
                            put("path", file.absolutePath)
                            put("type", getFileType(file))
                            put("mimeType", getMimeType(file))
                            put("size", file.length())
                            put("lastModified", file.lastModified())
                            put("isDirectory", file.isDirectory)
                            put("isReadable", file.canRead())
                            put("isWritable", file.canWrite())
                            put("isHidden", file.isHidden)
                            
                            // For directories, get child count
                            if (file.isDirectory) {
                                val children = file.listFiles()
                                put("childCount", children?.size ?: 0)
                            }
                        }
                        filesJson.put(fileJson)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error reading file ${file.name}: ${e.message}")
                    }
                }
                
                // Send response
                val response = JSONObject().apply {
                    put("type", "file_list_response")
                    put("requestId", requestId)
                    put("path", path)
                    put("parentPath", directory.parent ?: "/")
                    put("files", filesJson)
                    put("fileCount", filesJson.length())
                    put("timestamp", System.currentTimeMillis())
                }
                
                WebSocketSyncService.sendMessage("file_list_response", response)
                Log.d(TAG, "Listed ${filesJson.length()} files in $path")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error listing files", e)
                sendError("Failed to list files: ${e.message}", requestId)
            }
        }
    }

    /**
     * Delete a single file
     */
    private fun handleDeleteFile(filePath: String, requestId: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Deleting file: $filePath")
                
                val file = File(filePath)
                
                if (!file.exists()) {
                    sendError("File not found: $filePath", requestId)
                    return@launch
                }
                
                // Security check - prevent deleting system files
                if (isProtectedPath(filePath)) {
                    sendError("Cannot delete protected system file: $filePath", requestId)
                    return@launch
                }
                
                val deleted = if (file.isDirectory) {
                    deleteRecursively(file)
                } else {
                    file.delete()
                }
                
                val response = JSONObject().apply {
                    put("type", "file_delete_response")
                    put("requestId", requestId)
                    put("filePath", filePath)
                    put("success", deleted)
                    put("message", if (deleted) "File deleted successfully" else "Failed to delete file")
                    put("timestamp", System.currentTimeMillis())
                }
                
                WebSocketSyncService.sendMessage("file_delete_response", response)
                Log.d(TAG, "Delete result for $filePath: $deleted")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting file", e)
                sendError("Delete failed: ${e.message}", requestId)
            }
        }
    }

    /**
     * Delete multiple files
     */
    private fun handleDeleteFiles(filePaths: List<String>, requestId: String) {
        serviceScope.launch {
            try {
                Log.d(TAG, "Deleting ${filePaths.size} files")
                
                val results = JSONArray()
                var successCount = 0
                var failCount = 0
                
                for (filePath in filePaths) {
                    try {
                        val file = File(filePath)
                        
                        if (isProtectedPath(filePath)) {
                            results.put(JSONObject().apply {
                                put("path", filePath)
                                put("success", false)
                                put("error", "Protected path")
                            })
                            failCount++
                            continue
                        }
                        
                        val deleted = if (file.isDirectory) {
                            deleteRecursively(file)
                        } else {
                            file.delete()
                        }
                        
                        results.put(JSONObject().apply {
                            put("path", filePath)
                            put("success", deleted)
                        })
                        
                        if (deleted) successCount++ else failCount++
                        
                    } catch (e: Exception) {
                        results.put(JSONObject().apply {
                            put("path", filePath)
                            put("success", false)
                            put("error", e.message)
                        })
                        failCount++
                    }
                }
                
                val response = JSONObject().apply {
                    put("type", "file_delete_multiple_response")
                    put("requestId", requestId)
                    put("results", results)
                    put("successCount", successCount)
                    put("failCount", failCount)
                    put("timestamp", System.currentTimeMillis())
                }
                
                WebSocketSyncService.sendMessage("file_delete_multiple_response", response)
                Log.d(TAG, "Multi-delete: $successCount success, $failCount failed")
                
            } catch (e: Exception) {
                Log.e(TAG, "Error in multi-delete", e)
                sendError("Multi-delete failed: ${e.message}", requestId)
            }
        }
    }

    /**
     * Get storage information
     */
    private fun handleGetStorageInfo(requestId: String) {
        serviceScope.launch {
            try {
                val storageInfo = JSONObject()
                
                // Internal storage
                val internalPath = Environment.getDataDirectory()
                val internalStats = StatFs(internalPath.path)
                storageInfo.put("internal", JSONObject().apply {
                    put("total", internalStats.totalBytes)
                    put("available", internalStats.availableBytes)
                    put("used", internalStats.totalBytes - internalStats.availableBytes)
                })
                
                // External storage (SD card)
                val externalPath = Environment.getExternalStorageDirectory()
                if (externalPath.exists()) {
                    val externalStats = StatFs(externalPath.path)
                    storageInfo.put("external", JSONObject().apply {
                        put("path", externalPath.absolutePath)
                        put("total", externalStats.totalBytes)
                        put("available", externalStats.availableBytes)
                        put("used", externalStats.totalBytes - externalStats.availableBytes)
                    })
                }
                
                // Common directories info
                val directories = JSONArray()
                val commonDirs = listOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                )
                
                for (dir in commonDirs) {
                    if (dir.exists()) {
                        directories.put(JSONObject().apply {
                            put("name", dir.name)
                            put("path", dir.absolutePath)
                            put("fileCount", dir.listFiles()?.size ?: 0)
                        })
                    }
                }
                storageInfo.put("directories", directories)
                
                val response = JSONObject().apply {
                    put("type", "storage_info_response")
                    put("requestId", requestId)
                    put("storage", storageInfo)
                    put("timestamp", System.currentTimeMillis())
                }
                
                WebSocketSyncService.sendMessage("storage_info_response", response)
                
            } catch (e: Exception) {
                Log.e(TAG, "Error getting storage info", e)
                sendError("Storage info failed: ${e.message}", requestId)
            }
        }
    }

    /**
     * Recursively delete directory
     */
    private fun deleteRecursively(file: File): Boolean {
        if (file.isDirectory) {
            file.listFiles()?.forEach { child ->
                deleteRecursively(child)
            }
        }
        return file.delete()
    }

    /**
     * Check if path is protected (system files)
     */
    private fun isProtectedPath(path: String): Boolean {
        val protectedPaths = listOf(
            "/system",
            "/data/system",
            "/data/data",
            "/data/app",
            "/vendor",
            "/product",
            "/oem"
        )
        return protectedPaths.any { path.startsWith(it) }
    }

    /**
     * Get file type for UI display
     */
    private fun getFileType(file: File): String {
        if (file.isDirectory) return "directory"
        
        val extension = file.extension.lowercase()
        return when {
            extension in listOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif") -> "image"
            extension in listOf("mp4", "avi", "mkv", "mov", "3gp", "webm", "flv") -> "video"
            extension in listOf("mp3", "wav", "m4a", "flac", "aac", "ogg", "wma") -> "audio"
            extension in listOf("pdf", "doc", "docx", "txt", "xlsx", "xls", "ppt", "pptx") -> "document"
            extension == "apk" -> "apk"
            extension in listOf("zip", "rar", "7z", "tar", "gz") -> "archive"
            else -> "file"
        }
    }

    /**
     * Get MIME type for file
     */
    private fun getMimeType(file: File): String {
        if (file.isDirectory) return "inode/directory"
        
        val extension = file.extension.lowercase()
        return when (extension) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "mp4" -> "video/mp4"
            "mp3" -> "audio/mpeg"
            "pdf" -> "application/pdf"
            "apk" -> "application/vnd.android.package-archive"
            "zip" -> "application/zip"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
    }

    /**
     * Send error response
     */
    private fun sendError(error: String, requestId: String) {
        val response = JSONObject().apply {
            put("type", "file_error_response")
            put("requestId", requestId)
            put("error", error)
            put("success", false)
            put("timestamp", System.currentTimeMillis())
        }
        WebSocketSyncService.sendMessage("file_error_response", response)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
