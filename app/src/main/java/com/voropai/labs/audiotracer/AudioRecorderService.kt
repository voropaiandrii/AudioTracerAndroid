package com.voropai.labs.audiotracer

import android.app.*
import android.content.*
import android.media.MediaRecorder
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

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
            val file = storageManager.getTodayFile()
            ensureDirectoryExists(file.parentFile)
            
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath)
                
                try {
                    prepare()
                    start()
                    currentFile = file
                    isRecording = true
                    isPaused = false
                    recordingStartTime = System.currentTimeMillis()
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
                updateNotification()
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
            currentFile = null
            recordingStartTime = 0
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
        
        val recordingDuration = if (recordingStartTime > 0) {
            val duration = System.currentTimeMillis() - recordingStartTime
            val minutes = duration / 60000
            val seconds = (duration % 60000) / 1000
            String.format("%02d:%02d", minutes, seconds)
        } else {
            "00:00"
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
            .setContentTitle("AudioTracer")
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