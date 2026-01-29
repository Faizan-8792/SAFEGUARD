package com.familyguardpro.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * Audio recorder utility for call recording and ambient audio recording.
 * Records audio as PCM and converts to MP3 format.
 */
class AudioRecorder(private val context: Context) {

    companion object {
        private const val TAG = "AudioRecorder"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BITS_PER_SAMPLE = 16
        private const val CHANNELS = 1
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var outputFile: File? = null

    /**
     * Start recording audio to a file.
     * Output is saved as WAV format which can be converted to MP3.
     */
    fun startRecording(file: File) {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        outputFile = file
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            isRecording = true
            audioRecord?.startRecording()

            recordingThread = thread {
                writeAudioDataToFile(bufferSize)
            }

            Log.d(TAG, "Recording started: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            isRecording = false
        }
    }

    /**
     * Stop recording and finalize the audio file.
     */
    fun stopRecording() {
        if (!isRecording) return

        isRecording = false

        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null

            recordingThread?.join(1000)
            recordingThread = null

            // Add WAV header to the file
            outputFile?.let { addWavHeader(it) }

            Log.d(TAG, "Recording stopped")

        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    private fun writeAudioDataToFile(bufferSize: Int) {
        val buffer = ByteArray(bufferSize)
        val tempFile = File(outputFile?.parent, "temp_${outputFile?.name}")
        
        try {
            FileOutputStream(tempFile).use { outputStream ->
                while (isRecording) {
                    val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                    if (read > 0) {
                        outputStream.write(buffer, 0, read)
                    }
                }
            }

            // Rename temp file to output file
            if (tempFile.exists()) {
                tempFile.renameTo(outputFile)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio data", e)
        }
    }

    private fun addWavHeader(file: File) {
        try {
            val tempFile = File(file.parent, "wav_${file.name}")
            
            val audioData = file.readBytes()
            val dataLength = audioData.size
            val totalLength = dataLength + 36

            FileOutputStream(tempFile).use { fos ->
                // RIFF header
                fos.write("RIFF".toByteArray())
                fos.write(intToByteArray(totalLength))
                fos.write("WAVE".toByteArray())

                // fmt sub-chunk
                fos.write("fmt ".toByteArray())
                fos.write(intToByteArray(16)) // Subchunk1Size
                fos.write(shortToByteArray(1)) // AudioFormat (PCM)
                fos.write(shortToByteArray(CHANNELS.toShort())) // NumChannels
                fos.write(intToByteArray(SAMPLE_RATE)) // SampleRate
                fos.write(intToByteArray(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8)) // ByteRate
                fos.write(shortToByteArray((CHANNELS * BITS_PER_SAMPLE / 8).toShort())) // BlockAlign
                fos.write(shortToByteArray(BITS_PER_SAMPLE.toShort())) // BitsPerSample

                // data sub-chunk
                fos.write("data".toByteArray())
                fos.write(intToByteArray(dataLength))
                fos.write(audioData)
            }

            // Replace original file with WAV file
            file.delete()
            tempFile.renameTo(file)

            Log.d(TAG, "WAV header added to file")

        } catch (e: Exception) {
            Log.e(TAG, "Error adding WAV header", e)
        }
    }

    private fun intToByteArray(value: Int): ByteArray {
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
    }

    private fun shortToByteArray(value: Short): ByteArray {
        return ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value).array()
    }

    /**
     * Check if currently recording.
     */
    fun isRecording(): Boolean = isRecording

    /**
     * Get the current recording duration in seconds.
     */
    fun getRecordingDuration(): Int {
        val fileSize = outputFile?.length() ?: 0
        // Bytes / (sample rate * channels * bytes per sample) = seconds
        return (fileSize / (SAMPLE_RATE * CHANNELS * 2)).toInt()
    }
}
