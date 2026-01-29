package com.familyguardpro.utils

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.provider.CallLog
import android.util.Log
import androidx.annotation.RequiresPermission

/**
 * Utility class for deleting call logs from the device.
 * Used for remote delete functionality.
 */
object CallLogDeleter {

    private const val TAG = "CallLogDeleter"

    /**
     * Deletes ALL call logs from the device.
     * Requires WRITE_CALL_LOG permission.
     * 
     * @param context Application context
     * @return Number of deleted call log entries
     */
    @RequiresPermission(Manifest.permission.WRITE_CALL_LOG)
    fun deleteAllCallLogs(context: Context): Int {
        return try {
            val resolver: ContentResolver = context.contentResolver
            val deletedCount = resolver.delete(
                CallLog.Calls.CONTENT_URI,
                null,
                null
            )
            Log.d(TAG, "Deleted $deletedCount call log entries")
            deletedCount
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: WRITE_CALL_LOG required", e)
            -1
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call logs", e)
            -1
        }
    }

    /**
     * Deletes call logs older than specified hours.
     * 
     * @param context Application context
     * @param hoursOld Delete logs older than this many hours
     * @return Number of deleted call log entries
     */
    @RequiresPermission(Manifest.permission.WRITE_CALL_LOG)
    fun deleteOldCallLogs(context: Context, hoursOld: Int): Int {
        return try {
            val resolver: ContentResolver = context.contentResolver
            val cutoffTime = System.currentTimeMillis() - (hoursOld * 60 * 60 * 1000L)
            
            val deletedCount = resolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.DATE} < ?",
                arrayOf(cutoffTime.toString())
            )
            Log.d(TAG, "Deleted $deletedCount old call log entries")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting old call logs", e)
            -1
        }
    }

    /**
     * Deletes call logs for a specific phone number.
     * 
     * @param context Application context
     * @param phoneNumber The phone number to delete logs for
     * @return Number of deleted call log entries
     */
    @RequiresPermission(Manifest.permission.WRITE_CALL_LOG)
    fun deleteCallLogsForNumber(context: Context, phoneNumber: String): Int {
        return try {
            val resolver: ContentResolver = context.contentResolver
            
            // Normalize phone number
            val normalizedNumber = phoneNumber.replace(Regex("[^0-9+]"), "")
            
            val deletedCount = resolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.NUMBER} LIKE ?",
                arrayOf("%$normalizedNumber%")
            )
            Log.d(TAG, "Deleted $deletedCount call logs for $phoneNumber")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call logs for number", e)
            -1
        }
    }

    /**
     * Deletes a specific call log entry by ID.
     * 
     * @param context Application context
     * @param callLogId The ID of the call log entry to delete
     * @return true if deleted successfully
     */
    @RequiresPermission(Manifest.permission.WRITE_CALL_LOG)
    fun deleteCallLogById(context: Context, callLogId: Long): Boolean {
        return try {
            val resolver: ContentResolver = context.contentResolver
            
            val deletedCount = resolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls._ID} = ?",
                arrayOf(callLogId.toString())
            )
            
            val success = deletedCount > 0
            Log.d(TAG, "Delete call log $callLogId: $success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call log by ID", e)
            false
        }
    }

    /**
     * Deletes call logs by type (incoming, outgoing, missed).
     * 
     * @param context Application context
     * @param callType One of CallLog.Calls.INCOMING_TYPE, OUTGOING_TYPE, MISSED_TYPE
     * @return Number of deleted call log entries
     */
    @RequiresPermission(Manifest.permission.WRITE_CALL_LOG)
    fun deleteCallLogsByType(context: Context, callType: Int): Int {
        return try {
            val resolver: ContentResolver = context.contentResolver
            
            val deletedCount = resolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.TYPE} = ?",
                arrayOf(callType.toString())
            )
            Log.d(TAG, "Deleted $deletedCount call logs of type $callType")
            deletedCount
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call logs by type", e)
            -1
        }
    }

    /**
     * Checks if the app has permission to write call logs.
     */
    fun hasWriteCallLogPermission(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG) == 
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}
