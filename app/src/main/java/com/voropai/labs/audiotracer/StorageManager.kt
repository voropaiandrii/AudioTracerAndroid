package com.voropai.labs.audiotracer

import android.content.Context
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class StorageManager(private val context: Context) {
    
    fun getAudioDirectory(): File {
        // Create AudioTracer folder in Downloads directory for easy access
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val audioTracerDir = File(downloadsDir, "AudioTracer")
        
        if (!audioTracerDir.exists()) {
            audioTracerDir.mkdirs()
        }
        
        return audioTracerDir
    }
    
    fun getTodayFile(): File {
        val audioDir = getAudioDirectory()
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return File(audioDir, "$date.m4a")
    }
    
    fun getAvailableStorage(): Long {
        return getAudioDirectory().usableSpace
    }
    
    fun isExternalStorageWritable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED
    }
    
    fun getAudioFiles(): List<File> {
        val audioDir = getAudioDirectory()
        return if (audioDir.exists() && audioDir.isDirectory) {
            audioDir.listFiles { file ->
                file.isFile && file.extension.lowercase() in listOf("m4a", "mp3", "wav")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        } else {
            emptyList()
        }
    }
} 