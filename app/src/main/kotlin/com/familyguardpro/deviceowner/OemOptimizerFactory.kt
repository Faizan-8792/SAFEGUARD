package com.familyguardpro.deviceowner

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Factory for creating the appropriate OEM optimizer based on device manufacturer.
 */
object OemOptimizerFactory {
    
    private const val TAG = "OemOptimizerFactory"
    
    /**
     * Create the appropriate OEM optimizer for the current device.
     */
    fun createOptimizer(context: Context): BaseOemOptimizer {
        val manufacturer = Build.MANUFACTURER.lowercase()
        Log.d(TAG, "Creating optimizer for manufacturer: $manufacturer (${Build.MODEL})")
        
        return when {
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                Log.d(TAG, "Using Vivo optimizer")
                VivoOemOptimizer(context)
            }
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || 
            manufacturer.contains("poco") || manufacturer.contains("miui") -> {
                Log.d(TAG, "Using Xiaomi optimizer")
                XiaomiOemOptimizer(context)
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || 
            manufacturer.contains("oneplus") -> {
                Log.d(TAG, "Using OPPO optimizer")
                OppoOemOptimizer(context)
            }
            manufacturer.contains("samsung") -> {
                Log.d(TAG, "Using Samsung optimizer")
                SamsungOemOptimizer(context)
            }
            else -> {
                Log.d(TAG, "Using Generic optimizer for: $manufacturer")
                GenericOemOptimizer(context)
            }
        }
    }
    
    /**
     * Get the manufacturer category name
     */
    fun getManufacturerCategory(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> "Vivo"
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || 
            manufacturer.contains("poco") -> "Xiaomi"
            manufacturer.contains("oppo") || manufacturer.contains("realme") || 
            manufacturer.contains("oneplus") -> "OPPO"
            manufacturer.contains("samsung") -> "Samsung"
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> "Huawei"
            else -> Build.MANUFACTURER
        }
    }
}
