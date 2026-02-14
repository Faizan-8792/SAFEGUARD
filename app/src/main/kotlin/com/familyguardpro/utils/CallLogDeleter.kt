package com.familyguardpro.utils

import android.content.ContentResolver
import android.content.Context
import android.provider.CallLog
import android.util.Log

object CallLogDeleter {
    
    private const val TAG = "CallLogDeleter"
    
    /**
     * Delete all call logs from the device
     * Requires WRITE_CALL_LOG permission
     */
    fun deleteAllCallLogs(context: Context): Boolean {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val deletedCount = contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                null,
                null
            )
            Log.d(TAG, "Deleted $deletedCount call logs")
            true
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: WRITE_CALL_LOG required", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call logs", e)
            false
        }
    }
    
    /**
     * Delete specific call log by ID
     */
    fun deleteCallLog(context: Context, callId: String): Boolean {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val selection = "${CallLog.Calls._ID} = ?"
            val selectionArgs = arrayOf(callId)
            
            val deletedCount = contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                selection,
                selectionArgs
            )
            Log.d(TAG, "Deleted call log with ID: $callId")
            deletedCount > 0
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: WRITE_CALL_LOG required", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call log", e)
            false
        }
    }
    
    /**
     * Delete call logs by phone number
     */
    fun deleteCallLogsByNumber(context: Context, phoneNumber: String): Boolean {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val selection = "${CallLog.Calls.NUMBER} = ?"
            val selectionArgs = arrayOf(phoneNumber)
            
            val deletedCount = contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                selection,
                selectionArgs
            )
            Log.d(TAG, "Deleted $deletedCount call logs for number: $phoneNumber")
            deletedCount > 0
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: WRITE_CALL_LOG required", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting call logs", e)
            false
        }
    }
    
    /**
     * Delete call logs older than specified time
     */
    fun deleteOldCallLogs(context: Context, olderThanMillis: Long): Boolean {
        return try {
            val contentResolver: ContentResolver = context.contentResolver
            val cutoffTime = System.currentTimeMillis() - olderThanMillis
            val selection = "${CallLog.Calls.DATE} < ?"
            val selectionArgs = arrayOf(cutoffTime.toString())
            
            val deletedCount = contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                selection,
                selectionArgs
            )
            Log.d(TAG, "Deleted $deletedCount old call logs")
            deletedCount > 0
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied: WRITE_CALL_LOG required", e)
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting old call logs", e)
            false
        }
    }
}
