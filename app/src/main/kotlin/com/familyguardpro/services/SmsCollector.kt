package com.familyguardpro.services

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Telephony
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * SMS Collector - Collects SMS messages from device
 */
object SmsCollector {
    
    private const val TAG = "SmsCollector"
    
    /**
     * Collect SMS messages from the last 48 hours
     */
    fun collectSms(context: Context, hours: Int = 48): JSONArray {
        val smsArray = JSONArray()
        
        // Check permissions
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) 
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "SMS permission not granted")
            return smsArray
        }
        
        val cutoffTime = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
        
        try {
            // Query SMS inbox and sent messages
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE,
                Telephony.Sms.READ
            )
            
            val selection = "${Telephony.Sms.DATE} > ?"
            val selectionArgs = arrayOf(cutoffTime.toString())
            val sortOrder = "${Telephony.Sms.DATE} DESC"
            
            val cursor: Cursor? = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )
            
            cursor?.use {
                val idIndex = it.getColumnIndex(Telephony.Sms._ID)
                val addressIndex = it.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyIndex = it.getColumnIndex(Telephony.Sms.BODY)
                val dateIndex = it.getColumnIndex(Telephony.Sms.DATE)
                val typeIndex = it.getColumnIndex(Telephony.Sms.TYPE)
                val readIndex = it.getColumnIndex(Telephony.Sms.READ)
                
                while (it.moveToNext()) {
                    try {
                        val address = if (addressIndex >= 0) it.getString(addressIndex) ?: "" else ""
                        val body = if (bodyIndex >= 0) it.getString(bodyIndex) ?: "" else ""
                        val date = if (dateIndex >= 0) it.getLong(dateIndex) else 0L
                        val type = if (typeIndex >= 0) it.getInt(typeIndex) else 1
                        val read = if (readIndex >= 0) it.getInt(readIndex) == 1 else false
                        
                        // Get contact name from phone number
                        val contactName = getContactName(context, address)
                        
                        // Convert type to string
                        val typeString = when (type) {
                            Telephony.Sms.MESSAGE_TYPE_INBOX -> "inbox"
                            Telephony.Sms.MESSAGE_TYPE_SENT -> "sent"
                            Telephony.Sms.MESSAGE_TYPE_DRAFT -> "draft"
                            Telephony.Sms.MESSAGE_TYPE_OUTBOX -> "outbox"
                            else -> "inbox"
                        }
                        
                        val smsJson = JSONObject().apply {
                            put("address", address)
                            put("contactName", contactName)
                            put("body", body)
                            put("type", typeString)
                            put("read", read)
                            put("date", date)
                        }
                        
                        smsArray.put(smsJson)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing SMS: ${e.message}")
                    }
                }
            }
            
            Log.d(TAG, "Collected ${smsArray.length()} SMS messages")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error collecting SMS: ${e.message}", e)
        }
        
        return smsArray
    }
    
    /**
     * Get contact name from phone number
     */
    private fun getContactName(context: Context, phoneNumber: String): String {
        if (phoneNumber.isBlank()) return ""
        
        // Check contacts permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) 
            != PackageManager.PERMISSION_GRANTED) {
            return ""
        }
        
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(phoneNumber)
            )
            
            val projection = arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME)
            
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(ContactsContract.PhoneLookup.DISPLAY_NAME)
                    if (nameIndex >= 0) {
                        return cursor.getString(nameIndex) ?: ""
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting contact name: ${e.message}")
        }
        
        return ""
    }
    
    /**
     * Check if SMS permission is granted
     */
    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == 
            PackageManager.PERMISSION_GRANTED
    }
}
