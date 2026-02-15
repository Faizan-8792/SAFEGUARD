package com.familyguardpro.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioRecorder(private val context: Context) {
    
    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 44100 // Higher sample rate for better sensitivity
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 4 // Larger buffer for better capture
        
        // AGC (Automatic Gain Control) settings for maximum sensitivity
        private const val TARGET_LEVEL = 28000.0 // Target amplitude (near max for 16-bit)
        private const val AGC_ATTACK = 0.01 // Fast attack for quick response
        private const val AGC_RELEASE = 0.0005 // Slow release to maintain gain
        private const val MIN_GAIN = 1.0 // Minimum gain
        private const val MAX_GAIN = 50.0 // Maximum gain boost (50x amplification)
        private const val NOISE_GATE_THRESHOLD = 150 // Noise gate to reduce background hiss
    }
    
    private var currentGain = 10.0 // Start with 10x gain for far distance capture
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var outputFile: File? = null
    
    private val bufferSize = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        CHANNEL_CONFIG,
        AUDIO_FORMAT
    ) * BUFFER_SIZE_FACTOR
    
    interface AudioDataCallback {
        fun onAudioData(data: ByteArray)
    }
    
    private var audioDataCallback: AudioDataCallback? = null
    
    fun setAudioDataCallback(callback: AudioDataCallback?) {
        audioDataCallback = callback
    }
    
    fun startRecording(outputPath: String? = null): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }
        
        try {
            // Try most sensitive audio source first
            val audioSource = when {
                android.os.Build.VERSION.SDK_INT >= 24 &&
                    android.media.AudioDeviceInfo::class.java.declaredFields.any { it.name == "TYPE_BUILTIN_MIC" } -> {
                    // Try UNPROCESSED if available
                    try {
                        MediaRecorder.AudioSource.UNPROCESSED
                    } catch (e: Exception) {
                        MediaRecorder.AudioSource.VOICE_RECOGNITION
                    }
                }
                else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            audioRecord = AudioRecord(
                audioSource,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            outputPath?.let {
                outputFile = File(it)
            }
            
            isRecording = true
            audioRecord?.startRecording()
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                recordAudio()
            }
            
            Log.d(TAG, "Recording started")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }
    
    fun startLiveStream(): Boolean {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return false
        }
        
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return false
        }
        
        try {
            // Try most sensitive audio sources in order
            val audioSources = listOf(
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.CAMCORDER,
                MediaRecorder.AudioSource.MIC
            )
            
            for (source in audioSources) {
                try {
                    audioRecord = AudioRecord(
                        source,
                        SAMPLE_RATE,
                        CHANNEL_CONFIG,
                        AUDIO_FORMAT,
                        bufferSize
                    )
                    
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        Log.d(TAG, "AudioRecord initialized with source: $source")
                        break
                    } else {
                        audioRecord?.release()
                        audioRecord = null
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to init with source $source", e)
                    audioRecord = null
                }
            }
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return false
            }
            
            isRecording = true
            audioRecord?.startRecording()
            
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                streamAudio()
            }
            
            Log.d(TAG, "Live stream started with MAX SENSITIVITY mode")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting live stream", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }
    
    private suspend fun recordAudio() {
        val shortBuffer = ShortArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        val audioData = mutableListOf<Byte>()
        
        while (isRecording) {
            val samplesRead = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            
            if (samplesRead > 0) {
                // Apply AGC for maximum sensitivity
                val amplifiedBuffer = applyAGC(shortBuffer, samplesRead)
                val byteBuffer = shortArrayToByteArray(amplifiedBuffer)
                
                // Store for file output
                for (byte in byteBuffer) {
                    audioData.add(byte)
                }
                
                // Also send to callback if set
                audioDataCallback?.onAudioData(byteBuffer)
            }
        }
        
        // Save to WAV file if output file is set
        outputFile?.let { file ->
            saveAsWav(file, audioData.toByteArray())
        }
    }
    
    private suspend fun streamAudio() {
        val shortBuffer = ShortArray(bufferSize / 2) // 16-bit = 2 bytes per sample
        
        while (isRecording) {
            val samplesRead = audioRecord?.read(shortBuffer, 0, shortBuffer.size) ?: 0
            
            if (samplesRead > 0) {
                // Apply AGC for maximum sensitivity
                val amplifiedBuffer = applyAGC(shortBuffer, samplesRead)
                val byteBuffer = shortArrayToByteArray(amplifiedBuffer)
                audioDataCallback?.onAudioData(byteBuffer)
            }
        }
    }
    
    /**
     * Apply Automatic Gain Control (AGC) to boost quiet audio
     * This allows capturing audio from far distances
     */
    private fun applyAGC(buffer: ShortArray, length: Int): ShortArray {
        val result = ShortArray(length)
        
        // Calculate RMS (Root Mean Square) of the buffer
        var sumSquares = 0.0
        for (i in 0 until length) {
            sumSquares += buffer[i].toDouble() * buffer[i].toDouble()
        }
        val rms = kotlin.math.sqrt(sumSquares / length)
        
        // Apply noise gate - if signal is below threshold, apply maximum gain
        if (rms < NOISE_GATE_THRESHOLD) {
            currentGain = MAX_GAIN
        } else {
            // Calculate target gain to reach target level
            val targetGain = TARGET_LEVEL / rms
            
            // Smooth gain adjustment (AGC)
            if (targetGain < currentGain) {
                // Signal is loud, reduce gain quickly (attack)
                currentGain = currentGain - (currentGain - targetGain) * AGC_ATTACK
            } else {
                // Signal is quiet, increase gain slowly (release)
                currentGain = currentGain + (targetGain - currentGain) * AGC_RELEASE
            }
            
            // Clamp gain to valid range
            currentGain = currentGain.coerceIn(MIN_GAIN, MAX_GAIN)
        }
        
        // Apply gain to each sample
        for (i in 0 until length) {
            var amplified = (buffer[i] * currentGain).toInt()
            // Clamp to prevent clipping
            amplified = amplified.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            result[i] = amplified.toShort()
        }
        
        return result
    }
    
    /**
     * Convert ShortArray to ByteArray (Little Endian for PCM)
     */
    private fun shortArrayToByteArray(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            bytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = (shorts[i].toInt() shr 8 and 0xFF).toByte()
        }
        return bytes
    }
    
    fun stopRecording(): File? {
        isRecording = false
        
        // Wait for the recording coroutine to finish saving the file
        // Don't cancel it - let it complete the saveAsWav() call
        runBlocking {
            try {
                withTimeout(10000) { // 10 second timeout
                    recordingJob?.join()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Timeout waiting for recording job: ${e.message}")
                recordingJob?.cancel()
            }
        }
        recordingJob = null
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        Log.w(TAG, "Recording stopped, file=${outputFile?.absolutePath}, size=${outputFile?.length()}")
        return outputFile
    }
    
    fun isRecording(): Boolean = isRecording
    
    private fun saveAsWav(file: File, audioData: ByteArray) {
        try {
            FileOutputStream(file).use { fos ->
                // WAV header
                val totalDataLen = audioData.size + 36
                val byteRate = SAMPLE_RATE * 2 // 16-bit mono
                
                val header = ByteBuffer.allocate(44).apply {
                    order(ByteOrder.LITTLE_ENDIAN)
                    
                    // RIFF header
                    put("RIFF".toByteArray())
                    putInt(totalDataLen)
                    put("WAVE".toByteArray())
                    
                    // fmt chunk
                    put("fmt ".toByteArray())
                    putInt(16) // Chunk size
                    putShort(1) // Audio format (PCM)
                    putShort(1) // Num channels (mono)
                    putInt(SAMPLE_RATE) // Sample rate
                    putInt(byteRate) // Byte rate
                    putShort(2) // Block align
                    putShort(16) // Bits per sample
                    
                    // data chunk
                    put("data".toByteArray())
                    putInt(audioData.size)
                }
                
                fos.write(header.array())
                fos.write(audioData)
            }
            
            Log.w(TAG, "Saved WAV file: ${file.absolutePath}, size=${file.length()}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving WAV file", e)
        }
    }
    
    fun release() {
        stopRecording()
    }
}
