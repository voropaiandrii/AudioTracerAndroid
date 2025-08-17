package com.voropai.labs.audiotracer

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Simple PCM audio writer for VAD-based recording
 * This is a basic implementation that writes raw PCM data
 * In a production app, you might want to use a more sophisticated audio encoder
 */
class PcmAudioWriter(private val outputFile: File) : AudioWriter {
    private var outputStream: FileOutputStream? = null
    private var isClosed = false
    
    init {
        try {
            outputStream = FileOutputStream(outputFile)
        } catch (e: IOException) {
            throw RuntimeException("Failed to create PCM writer for ${outputFile.absolutePath}", e)
        }
    }
    
    override fun write(frame: ShortArray, offset: Int, length: Int) {
        if (isClosed) return
        
        try {
            // Convert ShortArray to ByteArray (little-endian)
            val bytes = ByteArray(length * 2)
            var byteIndex = 0
            
            for (i in offset until offset + length) {
                val sample = frame[i]
                bytes[byteIndex++] = (sample.toInt() and 0xFF).toByte()
                bytes[byteIndex++] = (sample.toInt() shr 8 and 0xFF).toByte()
            }
            
            outputStream?.write(bytes)
        } catch (e: IOException) {
            throw RuntimeException("Failed to write PCM data", e)
        }
    }
    
    override fun close() {
        if (isClosed) return
        
        try {
            outputStream?.close()
        } catch (e: IOException) {
            // Log error but don't throw
        } finally {
            outputStream = null
            isClosed = true
        }
    }
    
    fun flush() {
        try {
            outputStream?.flush()
        } catch (e: IOException) {
            // Log error but don't throw
        }
    }
}
