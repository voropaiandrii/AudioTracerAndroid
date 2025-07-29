package com.voropai.labs.audiotracer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.MediaRecorder
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

@AndroidEntryPoint
class AudioRecorderService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "audio_recorder_channel"
        const val NOTIFICATION_ID = 1
        const val ACTION_START = "com.voropai.labs.audiotracer.START"
        const val ACTION_PAUSE = "com.voropai.labs.audiotracer.PAUSE"
        const val ACTION_RESUME = "com.voropai.labs.audiotracer.RESUME"
        const val ACTION_STOP = "com.voropai.labs.audiotracer.STOP"
    }

    private var recorder: MediaRecorder? = null
    private var isPaused = false
    private var isRecording = false
    private var currentFile: File? = null
    private var recordingStartTime: Long = 0
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var storageManager: StorageManager
    private var fileRollingInProgress = false
    private var fileSizeMonitorJob: kotlinx.coroutines.Job? = null
    private var notificationUpdateJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        storageManager = StorageManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
        }
        
        if (isRecording) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        
        return START_STICKY
    }

    private fun startRecording() {
        if (isRecording) return
        
        try {
            val file = storageManager.startRecordingSession()
            ensureDirectoryExists(file.parentFile)
            
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION) // tuned for speech capture
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(Constants.Audio.SAMPLE_RATE)          // 24–32 kHz works well with HE‑AAC
                setAudioEncodingBitRate(Constants.Audio.ENCODING_BIT_RATE)
                setOutputFile(file.absolutePath)
                
                // Use manual file size monitoring instead of MediaRecorder's built-in file rolling
                // This provides more reliable control over the file rolling process
                android.util.Log.d("AudioRecorderService", "Setting up manual file size monitoring with max size: ${Constants.Audio.MAX_FILE_SIZE_BYTES} bytes")
                
                try {
                    prepare()
                    start()
                    currentFile = file
                    storageManager.setCurrentFile(file)
                    storageManager.setSessionStartTime(System.currentTimeMillis())
                    isRecording = true
                    isPaused = false
                    recordingStartTime = System.currentTimeMillis()
                    
                    // Start file size monitoring AFTER recording has started
                    android.util.Log.d("AudioRecorderService", "Recording started, initiating file size monitoring")
                    startFileSizeMonitoring()
                    
                    // Start notification updates
                    startNotificationUpdates()
                    
                    // Add a test log to verify monitoring is working
                    android.util.Log.d("AudioRecorderService", "File size monitoring should now be active")
                    
                    updateNotification()
                } catch (e: Exception) {
                    release()
                    throw e
                }
            }
        } catch (e: Exception) {
            // Handle recording start failure
            stopSelf()
        }
    }

    private fun pauseRecording() {
        if (!isRecording || isPaused) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.pause()
                isPaused = true
                fileSizeMonitorJob?.cancel() // Pause file size monitoring
                notificationUpdateJob?.cancel() // Pause notification updates
                updateNotification() // Update once to show paused state
            } catch (e: Exception) {
                // Handle pause failure
            }
        }
    }

    private fun resumeRecording() {
        if (!isRecording || !isPaused) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.resume()
                isPaused = false
                startFileSizeMonitoring() // Resume file size monitoring
                startNotificationUpdates() // Resume notification updates
                updateNotification()
            } catch (e: Exception) {
                // Handle resume failure
            }
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        
        try {
            recorder?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Handle stop failure - file might be corrupted
        } finally {
            recorder = null
            isRecording = false
            isPaused = false
            fileRollingInProgress = false
            fileSizeMonitorJob?.cancel()
            fileSizeMonitorJob = null
            notificationUpdateJob?.cancel()
            notificationUpdateJob = null
            currentFile = null
            storageManager.setCurrentFile(null)
            recordingStartTime = 0
            storageManager.resetSession()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun getTodayFile(): File {
        return storageManager.getTodayFile()
    }

    private fun ensureDirectoryExists(directory: File?) {
        directory?.mkdirs()
    }
    
    private fun startFileSizeMonitoring() {
        fileSizeMonitorJob?.cancel()
        fileSizeMonitorJob = serviceScope.launch {
            android.util.Log.d("AudioRecorderService", "File size monitoring started")
            var checkCount = 0
            var lastFileSize = 0L
            
            while (isRecording && !isPaused) {
                try {
                    val currentFile = currentFile
                    if (currentFile == null) {
                        android.util.Log.w("AudioRecorderService", "Current file is null, skipping size check")
                        delay(500)
                        continue
                    }
                    
                    if (!currentFile.exists()) {
                        android.util.Log.w("AudioRecorderService", "Current file does not exist: ${currentFile.absolutePath}")
                        delay(500)
                        continue
                    }
                    
                    val currentFileSize = currentFile.length()
                    val maxSize = Constants.Audio.MAX_FILE_SIZE_BYTES
                    checkCount++
                    
                    // Log if file size is changing
                    if (currentFileSize != lastFileSize) {
                        android.util.Log.d("AudioRecorderService", "Check #$checkCount - File size changed: $lastFileSize -> $currentFileSize bytes, Max: $maxSize bytes, File: ${currentFile.name}")
                        lastFileSize = currentFileSize
                    } else if (checkCount % 10 == 0) { // Log every 10th check if size hasn't changed
                        android.util.Log.d("AudioRecorderService", "Check #$checkCount - File size stable: $currentFileSize bytes, Max: $maxSize bytes, File: ${currentFile.name}")
                    }
                    
                    if (currentFileSize >= maxSize) {
                        android.util.Log.d("AudioRecorderService", "File size limit reached ($currentFileSize >= $maxSize), initiating file roll")
                        performFileRoll()
                        break
                    }
                    
                    delay(500) // Check every 500ms for more responsive file rolling
                } catch (e: Exception) {
                    android.util.Log.e("AudioRecorderService", "Error in file size monitoring: ${e.message}", e)
                    break
                }
            }
            android.util.Log.d("AudioRecorderService", "File size monitoring stopped")
        }
    }
    
    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            android.util.Log.d("AudioRecorderService", "Starting notification updates")
            while (isRecording) {
                try {
                    updateNotification()
                    delay(1000) // Update every second
                } catch (e: Exception) {
                    android.util.Log.e("AudioRecorderService", "Error updating notification: ${e.message}", e)
                    break
                }
            }
            android.util.Log.d("AudioRecorderService", "Notification updates stopped")
        }
    }
    
    private fun performFileRoll() {
        if (fileRollingInProgress) {
            android.util.Log.w("AudioRecorderService", "File rolling already in progress")
            return
        }
        
        fileRollingInProgress = true
        android.util.Log.d("AudioRecorderService", "Starting file roll")
        
        try {
            // Stop current recording
            recorder?.apply {
                stop()
                release()
            }
            
            // Create next file
            val nextFile = storageManager.createNextFile()
            ensureDirectoryExists(nextFile.parentFile)
            android.util.Log.d("AudioRecorderService", "Next file: ${nextFile.absolutePath}")
            
            // Start new recording with next file
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(Constants.Audio.SAMPLE_RATE)
                setAudioEncodingBitRate(Constants.Audio.ENCODING_BIT_RATE)
                setOutputFile(nextFile.absolutePath)
                
                prepare()
                start()
            }
            
            // Update current file reference
            currentFile = nextFile
            storageManager.setCurrentFile(nextFile)
            fileRollingInProgress = false
            
            // Restart file size monitoring
            startFileSizeMonitoring()
            
            // Restart notification updates
            startNotificationUpdates()
            
            android.util.Log.d("AudioRecorderService", "File roll completed successfully")
            updateNotification()
            
        } catch (e: Exception) {
            android.util.Log.e("AudioRecorderService", "Error during file roll: ${e.message}", e)
            fileRollingInProgress = false
            // If file roll fails, stop recording
            stopRecording()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Audio Recorder",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Audio recording service notification"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val status = when {
            isRecording && isPaused -> "Paused"
            isRecording -> "Recording"
            else -> "Stopped"
        }
        
        val recordingDuration = storageManager.getCurrentSessionDurationFormatted()
        
        // Add file info to notification
        val fileInfo = if (currentFile != null) {
            val fileIndex = storageManager.getCurrentFileIndex()
            if (fileIndex > 0) {
                " (Part ${fileIndex + 1})"
            } else {
                ""
            }
        } else {
            ""
        }
        
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, AudioRecorderService::class.java).apply {
                action = ACTION_STOP
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val pauseResumeIntent = PendingIntent.getService(
            this, 1, Intent(this, AudioRecorderService::class.java).apply {
                action = if (isPaused) ACTION_RESUME else ACTION_PAUSE
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioTracer$fileInfo")
            .setContentText("$status - $recordingDuration")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumeIntent
            )
            .addAction(
                // TODO: Should be stop instead of next
                android.R.drawable.ic_media_next,
                "Stop",
                stopIntent
            )
            .setOngoing(isRecording)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification() {
        if (isRecording) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRecording()
        serviceScope.cancel()
    }
} 