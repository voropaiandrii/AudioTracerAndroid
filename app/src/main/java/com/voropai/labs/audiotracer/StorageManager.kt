package com.voropai.labs.audiotracer

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class StorageManager(private val context: Context) {
    
    private var currentSessionId: String? = null
    private var currentFileIndex: Int = 0
    private var currentFile: File? = null
    
    private val prefs: SharedPreferences = context.getSharedPreferences("AudioTracerPrefs", Context.MODE_PRIVATE)
    private val SESSION_START_TIME_KEY = "session_start_time"
    
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
        val date = SimpleDateFormat("yyyy-MM-dd-HH-mm-SS", Locale.US).format(Date())
        return File(audioDir, "$date.m4a")
    }
    
    /**
     * Start a new recording session and return the first file
     */
    fun startRecordingSession(): File {
        val audioDir = getAudioDirectory()
        val timestamp = SimpleDateFormat("yyyy-MM-dd-HH-mm-SS", Locale.US).format(Date())
        currentSessionId = timestamp
        currentFileIndex = 0
        return createSessionFile(currentFileIndex)
    }
    
    /**
     * Create the next file in the current recording session
     */
    fun createNextFile(): File {
        currentFileIndex++
        return createSessionFile(currentFileIndex)
    }
    
    /**
     * Create a file for the current session with the given index
     */
    fun createSessionFile(index: Int): File {
        val audioDir = getAudioDirectory()
        val sessionId = currentSessionId ?: startRecordingSession().nameWithoutExtension
        val fileName = "${sessionId}_p${index}.m4a"
        return File(audioDir, fileName)
    }
    
    /**
     * Get the current session ID
     */
    fun getCurrentSessionId(): String? = currentSessionId
    
    /**
     * Get the current file index
     */
    fun getCurrentFileIndex(): Int = currentFileIndex
    
    /**
     * Reset the session state
     */
    fun resetSession() {
        currentSessionId = null
        currentFileIndex = 0
        prefs.edit().remove(SESSION_START_TIME_KEY).apply()
    }
    
    /**
     * Get the current file size in bytes
     */
    fun getCurrentFileSize(): Long {
        return currentFile?.length() ?: 0L
    }
    
    /**
     * Get the current file
     */
    fun getCurrentFile(): File? = currentFile
    
    /**
     * Set the current file
     */
    fun setCurrentFile(file: File?) {
        currentFile = file
    }
    
    /**
     * Set the session start time
     */
    fun setSessionStartTime(startTime: Long) {
        prefs.edit().putLong(SESSION_START_TIME_KEY, startTime).apply()
    }
    
    /**
     * Get the current session duration in milliseconds
     */
    fun getCurrentSessionDuration(): Long {
        val sessionStartTime = prefs.getLong(SESSION_START_TIME_KEY, 0L)
        return if (sessionStartTime > 0) {
            System.currentTimeMillis() - sessionStartTime
        } else {
            0L
        }
    }
    
    /**
     * Get the current session duration formatted as DD:HH:mm:ss
     */
    fun getCurrentSessionDurationFormatted(): String {
        val duration = getCurrentSessionDuration()
        if (duration <= 0) return "00:00:00:00"
        
        val days = duration / (24 * 60 * 60 * 1000)
        val hours = (duration / (60 * 60 * 1000)) % 24
        val minutes = (duration / (60 * 1000)) % 60
        val seconds = (duration / 1000) % 60
        
        return String.format("%02d:%02d:%02d:%02d", days, hours, minutes, seconds)
    }
    
    /**
     * Check if there's an active recording session
     */
    fun hasActiveSession(): Boolean {
        return prefs.getLong(SESSION_START_TIME_KEY, 0L) > 0
    }
    
    /**
     * Get all files for the current session
     */
    fun getSessionFiles(): List<File> {
        val sessionId = currentSessionId ?: return emptyList()
        val audioDir = getAudioDirectory()
        return audioDir.listFiles { file ->
            file.isFile && file.name.startsWith(sessionId) && file.extension.lowercase() == "m4a"
        }?.sortedBy { it.name } ?: emptyList()
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