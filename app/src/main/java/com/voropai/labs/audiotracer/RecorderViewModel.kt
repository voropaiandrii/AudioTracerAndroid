package com.voropai.labs.audiotracer

import android.app.Application
import android.content.Intent
import android.os.Environment
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
import java.util.concurrent.TimeUnit
import javax.inject.Inject

enum class RecordingStatus { Stopped, Recording, Paused }

@HiltViewModel
class RecorderViewModel @Inject constructor(
    app: Application
) : AndroidViewModel(app) {

    private val _status = MutableStateFlow(RecordingStatus.Stopped)
    val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private val _freeStorage = MutableStateFlow(StorageInfo(0L, "00:00"))
    val freeStorage: StateFlow<StorageInfo> = _freeStorage.asStateFlow()

    private val permissionManager = PermissionManager(app)
    private val storageManager = StorageManager(app)

    init {
        updateStorageInfo()
        checkServiceStatus()
    }

    fun hasRequiredPermissions(): Boolean {
        return permissionManager.hasRequiredPermissions()
    }

    fun getRequiredPermissions(): Array<String> {
        return permissionManager.getRequiredPermissions()
    }

    fun getPermissionRequestCode(): Int {
        return permissionManager.getPermissionRequestCode()
    }

    fun startRecording() {
        if (!hasRequiredPermissions()) return
        
        val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_START
        }
        getApplication<Application>().startForegroundService(intent)
        _status.value = RecordingStatus.Recording
    }

    fun pauseRecording() {
        if (_status.value == RecordingStatus.Recording) {
            val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
                action = AudioRecorderService.ACTION_PAUSE
            }
            getApplication<Application>().startForegroundService(intent)
            _status.value = RecordingStatus.Paused
        }
    }

    fun resumeRecording() {
        if (_status.value == RecordingStatus.Paused) {
            val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
                action = AudioRecorderService.ACTION_RESUME
            }
            getApplication<Application>().startForegroundService(intent)
            _status.value = RecordingStatus.Recording
        }
    }

    fun stopRecording() {
        val intent = Intent(getApplication(), AudioRecorderService::class.java).apply {
            action = AudioRecorderService.ACTION_STOP
        }
        getApplication<Application>().startForegroundService(intent)
        _status.value = RecordingStatus.Stopped
    }

    private fun checkServiceStatus() {
        viewModelScope.launch(Dispatchers.IO) {
            // Check if service is running by looking for today's file
            val todayFile = storageManager.getTodayFile()
            if (todayFile.exists()) {
                // Service might be running, check if it's actually recording
                // For now, we'll assume it's recording if file exists and has recent modification
                val lastModified = todayFile.lastModified()
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastModified < 60000) { // Modified within last minute
                    _status.value = RecordingStatus.Recording
                }
            }
        }
    }

    private fun getTodayFile(): File {
        return storageManager.getTodayFile()
    }

    fun updateStorageInfo() {
        viewModelScope.launch(Dispatchers.IO) {
            val freeBytes = storageManager.getAvailableStorage()
            val timeLeft = getAudioTimeLeft(freeBytes)
            _freeStorage.value = StorageInfo(freeBytes, timeLeft)
        }
    }

    private fun getAvailableStorage(): Long {
        return storageManager.getAvailableStorage()
    }

    private fun getAudioTimeLeft(bytes: Long): String {
        // 128 kbps = 16 KB/s
        val seconds = bytes / 16_000
        val minutes = TimeUnit.SECONDS.toMinutes(seconds)
        val remainingSeconds = seconds % 60
        return String.format("%02d:%02d", minutes, remainingSeconds)
    }
}

data class StorageInfo(
    val freeBytes: Long,
    val timeLeft: String
) {
    fun formattedBytes(): String {
        val df = DecimalFormat("#,###")
        return df.format(freeBytes)
    }
} 