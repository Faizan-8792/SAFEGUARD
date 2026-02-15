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
        private const val DEFAULT_SAMPLE_RATE = 44100 // For non-call recording
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
    private var activeSampleRate = DEFAULT_SAMPLE_RATE // Actual sample rate used (may be 8000 for call recording)
    
    private var bufferSize = AudioRecord.getMinBufferSize(
        DEFAULT_SAMPLE_RATE,
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
    
    /**
     * Start recording audio to a file.
     * @param outputPath file path to save recording
     * @param forCallRecording if true, use VOICE_CALL source to capture phone call audio (both sides)
     */
    fun startRecording(outputPath: String? = null, forCallRecording: Boolean = false): Boolean {
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
            // IMPORTANT: Do NOT change AudioManager.mode during a cellular call!
            // Changing from MODE_IN_CALL to MODE_IN_COMMUNICATION mutes the mic completely
            // on most devices (Vivo, Samsung, etc). Let the system handle audio routing.
            
            if (forCallRecording) {
                // For cellular call recording on modern Android (9+):
                // - VOICE_CALL is blocked for non-system apps on most devices
                // - VOICE_COMMUNICATION is for VoIP, captures silence during cellular calls
                // - MIC is the ONLY reliable source that captures audio during calls
                //   (captures user's voice + leaked speaker audio)
                // - CAMCORDER may also work on some devices
                //
                // Strategy: Try VOICE_CALL first (in case it works), then MIC at 44100Hz
                
                val callConfigs = listOf(
                    // VOICE_CALL at telephony rates (best case - both sides)
                    Triple(MediaRecorder.AudioSource.VOICE_CALL, 8000, "VOICE_CALL"),
                    Triple(MediaRecorder.AudioSource.VOICE_CALL, 16000, "VOICE_CALL"),
                    Triple(MediaRecorder.AudioSource.VOICE_CALL, 44100, "VOICE_CALL"),
                    // MIC at 44100Hz (most reliable for calls)
                    Triple(MediaRecorder.AudioSource.MIC, 44100, "MIC"),
                    // CAMCORDER sometimes routes call audio on some devices
                    Triple(MediaRecorder.AudioSource.CAMCORDER, 44100, "CAMCORDER"),
                    // MIC at lower sample rates as last resort
                    Triple(MediaRecorder.AudioSource.MIC, 16000, "MIC"),
                    Triple(MediaRecorder.AudioSource.MIC, 8000, "MIC")
                )
                
                var usedSource = "UNKNOWN"
                
                for ((source, rate, name) in callConfigs) {
                    try {
                        val minBuf = AudioRecord.getMinBufferSize(rate, CHANNEL_CONFIG, AUDIO_FORMAT)
                        if (minBuf <= 0) {
                            Log.w(TAG, "Invalid buffer for $name@${rate}Hz")
                            continue
                        }
                        
                        val buf = minBuf * BUFFER_SIZE_FACTOR
                        audioRecord = AudioRecord(source, rate, CHANNEL_CONFIG, AUDIO_FORMAT, buf)
                        
                        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            usedSource = name
                            activeSampleRate = rate
                            bufferSize = buf
                            Log.w(TAG, "SUCCESS: AudioRecord init source=$name rate=${rate}Hz")
                            break
                        } else {
                            audioRecord?.release()
                            audioRecord = null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "FAILED: $name@${rate}Hz: ${e.message}")
                        try { audioRecord?.release() } catch (_: Exception) {}
                        audioRecord = null
                    }
                }
                
                Log.w(TAG, "Call recording: source=$usedSource, sampleRate=${activeSampleRate}Hz")
                
            } else {
                // Non-call recording: use sensitive sources at 44100Hz
                activeSampleRate = DEFAULT_SAMPLE_RATE
                bufferSize = AudioRecord.getMinBufferSize(DEFAULT_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
                
                val audioSources = listOf(
                    MediaRecorder.AudioSource.UNPROCESSED,
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    MediaRecorder.AudioSource.MIC
                )
                
                for (source in audioSources) {
                    try {
                        audioRecord = AudioRecord(source, DEFAULT_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
                        if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                            Log.w(TAG, "AudioRecord initialized: source=${getSourceName(source)}, rate=44100Hz")
                            break
                        } else {
                            audioRecord?.release()
                            audioRecord = null
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to init with source ${getSourceName(source)}: ${e.message}")
                        audioRecord?.release()
                        audioRecord = null
                    }
                }
            }
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization COMPLETELY FAILED")
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
            
            Log.w(TAG, "Recording started, sampleRate=$activeSampleRate, file=${outputFile?.absolutePath}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            audioRecord?.release()
            audioRecord = null
            return false
        }
    }
    
    private fun getSourceName(source: Int): String {
        return when (source) {
            MediaRecorder.AudioSource.VOICE_CALL -> "VOICE_CALL"
            MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
            MediaRecorder.AudioSource.MIC -> "MIC"
            MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
            MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
            MediaRecorder.AudioSource.CAMCORDER -> "CAMCORDER"
            else -> "SOURCE_$source"
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
            activeSampleRate = DEFAULT_SAMPLE_RATE
            bufferSize = AudioRecord.getMinBufferSize(DEFAULT_SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * BUFFER_SIZE_FACTOR
            
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
                        DEFAULT_SAMPLE_RATE,
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
        
        Log.w(TAG, "Recording stopped, file=${outputFile?.absolutePath}, size=${outputFile?.length()}, sampleRate=$activeSampleRate")
        return outputFile
    }
    
    fun isRecording(): Boolean = isRecording
    
    private fun saveAsWav(file: File, audioData: ByteArray) {
        try {
            FileOutputStream(file).use { fos ->
                // WAV header - use activeSampleRate (may be 8000Hz for call recording)
                val totalDataLen = audioData.size + 36
                val byteRate = activeSampleRate * 2 // 16-bit mono
                
                Log.w(TAG, "Saving WAV: sampleRate=$activeSampleRate, dataSize=${audioData.size}, byteRate=$byteRate")
                
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
                    putInt(activeSampleRate) // Sample rate
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
