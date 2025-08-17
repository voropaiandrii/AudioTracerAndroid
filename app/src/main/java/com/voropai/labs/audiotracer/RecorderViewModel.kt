package com.voropai.labs.audiotracer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.text.DecimalFormat
import javax.inject.Inject

enum class RecordingStatus { Stopped, Recording, Paused, Armed, AutoRecording }

@HiltViewModel
open class RecorderViewModel @Inject constructor(
    app: Application
) : AndroidViewModel(app), RecorderViewModelInterface {

    private val _status = MutableStateFlow(RecordingStatus.Stopped)
    override open val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private val _freeStorage = MutableStateFlow(StorageInfo(0L, "00:00", 0))
    override open val freeStorage: StateFlow<StorageInfo> = _freeStorage.asStateFlow()

    private val _recordingDuration = MutableStateFlow("00:00:00:00")
    override open val recordingDuration: StateFlow<String> = _recordingDuration.asStateFlow()

    private val permissionManager = PermissionManager(app)
    private val storageManager = StorageManager(app)

    init {
        updateStorageInfo()
        updateRecordingDuration()
        checkServiceStatus()
    }

    override open fun hasRequiredPermissions(): Boolean {
        return permissionManager.hasRequiredPermissions()
    }

    fun getRequiredPermissions(): Array<String> {
        return permissionManager.getRequiredPermissions()
    }

    fun getPermissionRequestCode(): Int {
        return permissionManager.getPermissionRequestCode()
    }

    // ===== MANUAL RECORDING (existing functionality) =====

    override open fun startRecording() {
        if (!hasRequiredPermissions()) return
        
        val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
        _status.value = RecordingStatus.Recording
        updateRecordingDuration()
    }

    override open fun pauseRecording() {
        if (_status.value == RecordingStatus.Recording) {
            val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
                action = AudioRecorderService.ACTION_PAUSE
            }
            getApplication<Application>().startForegroundService(intent)
            _status.value = RecordingStatus.Paused
            updateRecordingDuration()
        }
    }

    override open fun resumeRecording() {
        if (_status.value == RecordingStatus.Paused) {
            val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
                action = AudioRecorderService.ACTION_RESUME
            }
            getApplication<Application>().startForegroundService(intent)
            _status.value = RecordingStatus.Recording
            updateRecordingDuration()
        }
    }

    override open fun stopRecording() {
        val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_STOP
        }
        getApplication<Application>().startForegroundService(intent)
        _status.value = RecordingStatus.Stopped
        storageManager.resetSession()
        _recordingDuration.value = "00:00:00:00"
    }

    // ===== AUTOMATIC RECORDING (new functionality) =====

    override fun startAutoRecording() {
        if (!hasRequiredPermissions()) return
        
        val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_START_AUTO
        }
        getApplication<Application>().startForegroundService(intent)
        _status.value = RecordingStatus.Armed
    }

    override fun stopAutoRecording() {
        val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_STOP_AUTO
        }
        getApplication<Application>().startForegroundService(intent)
        _status.value = RecordingStatus.Stopped
        storageManager.resetSession()
        _recordingDuration.value = "00:00:00:00"
    }

    override fun isAutoRecordingEnabled(): Boolean {
        return _status.value == RecordingStatus.Armed || _status.value == RecordingStatus.AutoRecording
    }

    override fun isManualRecordingEnabled(): Boolean {
        return _status.value == RecordingStatus.Recording || _status.value == RecordingStatus.Paused
    }

    private fun checkServiceStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            // Check if there's an active recording session
            if (storageManager.hasActiveSession()) {
                // Determine if it's auto or manual recording based on service state
                // For now, we'll assume manual recording if there's an active session
                _status.value = RecordingStatus.Recording
                updateRecordingDuration()
            } else {
                // Fallback to checking today's file for backward compatibility
                val todayFile = storageManager.getTodayFile()
                if (todayFile.exists()) {
                    val lastModified = todayFile.lastModified()
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastModified < 60000) { // Modified within last minute
                        _status.value = RecordingStatus.Recording
                        updateRecordingDuration()
                    }
                }
            }
        }
    }

    private fun getTodayFile(): File {
        return storageManager.getTodayFile()
    }

    override open fun updateStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val freeBytes = storageManager.getAvailableStorage()
            val timeLeft = getAudioTimeLeft(freeBytes)
            _freeStorage.value = StorageInfo(freeBytes, timeLeft, Constants.Audio.ENCODING_BIT_RATE)
        }
    }

    override open fun updateRecordingDuration() {
        viewModelScope.launch(Dispatchers.IO) {
            val duration = storageManager.getCurrentSessionDurationFormatted()
            _recordingDuration.value = duration
        }
    }

    private fun getAvailableStorage(): Long {
        return storageManager.getAvailableStorage()
    }

    private fun getAudioTimeLeft(bytes: Long): String {
        // kbps to KB/s
        val seconds = bytes / (Constants.Audio.ENCODING_BIT_RATE / 8)
        val days = seconds / (24 * 60 * 60)
        val hours = (seconds / (60 * 60)) % 24
        val minutes = (seconds / 60) % 60
        val remainingSeconds = seconds % 60

        return String.format(
            "%d:%02d:%02d:%02d",
            days,
            hours,
            minutes,
            remainingSeconds
        )
    }
}

data class StorageInfo(
    val freeBytes: Long,
    val timeLeft: String,
    val audioBitRate: Int
) {
    fun formattedKiloBytes(): String {
        val df = DecimalFormat("#,###")
        return df.format(freeBytes/1024)
    }
} 