package com.voropai.labs.audiotracer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioFormat
import android.media.AudioRecord
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
        const val ACTION_START_AUTO = "com.voropai.labs.audiotracer.START_AUTO"
        const val ACTION_STOP_AUTO = "com.voropai.labs.audiotracer.STOP_AUTO"
    }

    // Manual recording (existing functionality)
    private var recorder: MediaRecorder? = null
    private var isPaused = false
    private var isRecording = false
    private var currentFile: File? = null
    private var recordingStartTime: Long = 0
    private var fileRollingInProgress = false
    private var fileSizeMonitorJob: kotlinx.coroutines.Job? = null
    private var notificationUpdateJob: kotlinx.coroutines.Job? = null

    // Automatic recording (new functionality)
    private var audioRecord: AudioRecord? = null
    private var isArmed = false
    private var isAutoRecording = false
    private var vadEngine: VadEngine? = null
    private var preRollBuffer: PreRollBuffer? = null
    private var autoRecordingJob: kotlinx.coroutines.Job? = null
    private var currentAutoFile: File? = null
    private var autoFileWriter: PcmAudioWriter? = null

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
            ACTION_START -> startManualRecording()
            ACTION_PAUSE -> pauseManualRecording()
            ACTION_RESUME -> resumeManualRecording()
            ACTION_STOP -> stopManualRecording()
            ACTION_START_AUTO -> startAutoRecording()
            ACTION_STOP_AUTO -> stopAutoRecording()
        }
        
        if (isRecording || isArmed) {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        
        return START_STICKY
    }

    // ===== MANUAL RECORDING (existing functionality) =====

    private fun startManualRecording() {
        if (isRecording) return
        
        try {
            val file = storageManager.startRecordingSession(StorageManager.RecordingMode.MANUAL)
            ensureDirectoryExists(file.parentFile)
            
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.HE_AAC)
                setAudioChannels(1)
                setAudioSamplingRate(Constants.Audio.SAMPLE_RATE)
                setAudioEncodingBitRate(Constants.Audio.ENCODING_BIT_RATE)
                setOutputFile(file.absolutePath)
                
                prepare()
                start()
                currentFile = file
                storageManager.setCurrentFile(file)
                storageManager.setSessionStartTime(System.currentTimeMillis())
                isRecording = true
                isPaused = false
                recordingStartTime = System.currentTimeMillis()
                
                startFileSizeMonitoring()
                startNotificationUpdates()
                updateNotification()
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun pauseManualRecording() {
        if (!isRecording || isPaused) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.pause()
                isPaused = true
                fileSizeMonitorJob?.cancel()
                notificationUpdateJob?.cancel()
                updateNotification()
            } catch (e: Exception) {
                // Handle pause failure
            }
        }
    }

    private fun resumeManualRecording() {
        if (!isRecording || !isPaused) return
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                recorder?.resume()
                isPaused = false
                startFileSizeMonitoring()
                startNotificationUpdates()
                updateNotification()
            } catch (e: Exception) {
                // Handle resume failure
            }
        }
    }

    private fun stopManualRecording() {
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
            
            if (!isArmed) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            } else {
                updateNotification()
            }
        }
    }

    // ===== AUTOMATIC RECORDING (new functionality) =====

    private fun startAutoRecording() {
        if (isArmed) return
        
        try {
            // Initialize VAD components
            vadEngine = VadEngine.default()
            preRollBuffer = PreRollBuffer(1.5) // 1.5 seconds pre-roll
            
            // Initialize AudioRecord for VAD processing
            audioRecord = MicSampler().open(
                sampleRate = VadEngine.SAMPLE_RATE,
                channel = AudioFormat.CHANNEL_IN_MONO
            )
            
            isArmed = true
            isAutoRecording = false
            
            // Start VAD processing loop
            autoRecordingJob = serviceScope.launch {
                audioRecord?.startRecording()
                vadProcessingLoop()
            }
            
            updateNotification()
        } catch (e: Exception) {
            stopAutoRecording()
        }
    }

    private fun stopAutoRecording() {
        isArmed = false
        isAutoRecording = false
        
        // Stop VAD processing
        autoRecordingJob?.cancel()
        autoRecordingJob = null
        
        // Stop and release AudioRecord
        try {
            audioRecord?.apply {
                stop()
                release()
            }
        } catch (e: Exception) {
            // Handle cleanup errors
        } finally {
            audioRecord = null
        }
        
        // Close current auto recording file
        try {
            autoFileWriter?.close()
        } catch (e: Exception) {
            // Handle cleanup errors
        } finally {
            autoFileWriter = null
            currentAutoFile = null
        }
        
        // Clean up VAD components
        vadEngine?.reset()
        vadEngine = null
        preRollBuffer?.clear()
        preRollBuffer = null
        
        if (!isRecording) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private suspend fun vadProcessingLoop() {
        val frame = ShortArray(VadEngine.FRAME_SAMPLES)
        var isActive = true
        
        while (isArmed && isActive) {
            try {
                val n = audioRecord?.read(frame, 0, frame.size, AudioRecord.READ_BLOCKING) ?: 0
                if (n > 0) {
                    // Add to pre-roll buffer
                    preRollBuffer?.push(frame, n)
                    
                    // Process with VAD
                    val decision = vadEngine?.process(frame, n) ?: VadEngine.Decision(false, false)
                    
                    if (!isAutoRecording && decision.start) {
                        beginAutoRecording()
                    }
                    
                    if (isAutoRecording && decision.stop) {
                        endAutoRecording()
                    }
                    
                    // Write to current file if recording
                    if (isAutoRecording) {
                        writeAudioFrame(frame, n)
                    }
                }
                
                delay(VadEngine.FRAME_MS.toLong())
            } catch (e: Exception) {
                isActive = false
                break
            }
        }
    }

    private fun beginAutoRecording() {
        if (isAutoRecording) return
        
        try {
            val file = storageManager.startRecordingSession(StorageManager.RecordingMode.AUTOMATIC)
            ensureDirectoryExists(file.parentFile)
            
            autoFileWriter = PcmAudioWriter(file)
            
            currentAutoFile = file
            storageManager.setCurrentFile(file)
            storageManager.setSessionStartTime(System.currentTimeMillis())
            isAutoRecording = true
            
            // Write pre-roll buffer to capture context before speech
            preRollBuffer?.let { buffer ->
                // Drain the pre-roll buffer to capture audio before speech detection
                buffer.drainTo(autoFileWriter!!)
            }
            
            updateNotification()
        } catch (e: Exception) {
            // Handle recording start failure
        }
    }

    private fun endAutoRecording() {
        if (!isAutoRecording) return
        
        try {
            autoFileWriter?.close()
        } catch (e: Exception) {
            // Handle close failure
        } finally {
            autoFileWriter = null
            currentAutoFile = null
            isAutoRecording = false
            storageManager.resetSession()
            updateNotification()
        }
    }

    private fun writeAudioFrame(frame: ShortArray, n: Int) {
        if (isAutoRecording && autoFileWriter != null) {
            try {
                autoFileWriter?.write(frame, 0, n)
            } catch (e: Exception) {
                // Handle write failure
            }
        }
    }

    // ===== SHARED FUNCTIONALITY =====

    private fun getTodayFile(): File {
        return storageManager.getTodayFile()
    }

    private fun ensureDirectoryExists(directory: File?) {
        directory?.mkdirs()
    }
    
    private fun startFileSizeMonitoring() {
        fileSizeMonitorJob?.cancel()
        fileSizeMonitorJob = serviceScope.launch {
            var checkCount = 0
            var lastFileSize = 0L
            
            while (isRecording && !isPaused) {
                try {
                    val currentFile = currentFile
                    if (currentFile == null || !currentFile.exists()) {
                        delay(500)
                        continue
                    }
                    
                    val currentFileSize = currentFile.length()
                    val maxSize = Constants.Audio.MAX_FILE_SIZE_BYTES
                    checkCount++
                    
                    if (currentFileSize >= maxSize) {
                        performFileRoll()
                        break
                    }
                    
                    delay(500)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }
    
    private fun startNotificationUpdates() {
        notificationUpdateJob?.cancel()
        notificationUpdateJob = serviceScope.launch {
            while (isRecording) {
                try {
                    updateNotification()
                    delay(1000)
                } catch (e: Exception) {
                    break
                }
            }
        }
    }
    
    private fun performFileRoll() {
        if (fileRollingInProgress) return
        
        fileRollingInProgress = true
        
        try {
            recorder?.apply {
                stop()
                release()
            }
            
            val nextFile = storageManager.createNextFile()
            ensureDirectoryExists(nextFile.parentFile)
            
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
            
            currentFile = nextFile
            storageManager.setCurrentFile(nextFile)
            fileRollingInProgress = false
            
            startFileSizeMonitoring()
            startNotificationUpdates()
            updateNotification()
            
        } catch (e: Exception) {
            fileRollingInProgress = false
            stopManualRecording()
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
            isAutoRecording -> "Auto Recording"
            isRecording && isPaused -> "Paused"
            isRecording -> "Recording"
            isArmed -> "Listening for voice..."
            else -> "Stopped"
        }
        
        val recordingDuration = storageManager.getCurrentSessionDurationFormatted()
        
        val fileInfo = if (currentFile != null || currentAutoFile != null) {
            val fileIndex = storageManager.getCurrentFileIndex()
            val recordingMode = storageManager.getCurrentRecordingMode()
            val modeSuffix = storageManager.getRecordingModeAbbreviation(recordingMode)
            val partInfo = if (fileIndex > 0) {
                " (Part ${fileIndex + 1})"
            } else {
                ""
            }
            " [$modeSuffix]$partInfo"
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
        
        val autoToggleIntent = PendingIntent.getService(
            this, 2, Intent(this, AudioRecorderService::class.java).apply {
                action = if (isArmed) ACTION_STOP_AUTO else ACTION_START_AUTO
            }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("AudioTracer$fileInfo")
            .setContentText("$status - $recordingDuration")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(isRecording || isArmed)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        
        // Add actions based on current state
        if (isRecording) {
            builder.addAction(
                android.R.drawable.ic_media_pause,
                if (isPaused) "Resume" else "Pause",
                pauseResumeIntent
            )
            builder.addAction(
                android.R.drawable.ic_media_next,
                "Stop",
                stopIntent
            )
        }
        
        if (isArmed || !isRecording) {
            builder.addAction(
                android.R.drawable.ic_btn_speak_now,
                if (isArmed) "Stop Listening" else "Start Listening",
                autoToggleIntent
            )
        }
        
        return builder.build()
    }

    private fun updateNotification() {
        if (isRecording || isArmed) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, buildNotification())
        }
    }

    override fun onDestroy() {
        stopManualRecording()
        stopAutoRecording()
        serviceScope.cancel()
        super.onDestroy()
    }
} 