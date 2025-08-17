package com.voropai.labs.audiotracer

import android.app.Application
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// Interface for ViewModel operations needed by the UI
interface RecorderViewModelInterface {
    val status: StateFlow<RecordingStatus>
    val freeStorage: StateFlow<StorageInfo>
    val recordingDuration: StateFlow<String>
    fun hasRequiredPermissions(): Boolean
    fun startRecording()
    fun pauseRecording()
    fun resumeRecording()
    fun stopRecording()
    fun startAutoRecording()
    fun stopAutoRecording()
    fun isAutoRecordingEnabled(): Boolean
    fun isManualRecordingEnabled(): Boolean
    fun updateStorageInfo()
    fun updateRecordingDuration()
}

// Mock ViewModel for previews
class MockRecorderViewModel : RecorderViewModelInterface {
    private val _status = MutableStateFlow(RecordingStatus.Recording)
    override val status: StateFlow<RecordingStatus> get() = _status
    
    private val _freeStorage = MutableStateFlow(StorageInfo(1024 * 1024 * 512, "1:02:03:45", 16000))
    override val freeStorage: StateFlow<StorageInfo> get() = _freeStorage
    
    private val _recordingDuration = MutableStateFlow("00:15:32")
    override val recordingDuration: StateFlow<String> get() = _recordingDuration
    
    override fun hasRequiredPermissions() = true
    override fun startRecording() {}
    override fun pauseRecording() {}
    override fun resumeRecording() {}
    override fun stopRecording() {}
    override fun startAutoRecording() {}
    override fun stopAutoRecording() {}
    override fun isAutoRecordingEnabled() = false
    override fun isManualRecordingEnabled() = true
    override fun updateStorageInfo() {}
    override fun updateRecordingDuration() {}
}

class MockRecorderViewModelNoPermissions : RecorderViewModelInterface {
    private val _status = MutableStateFlow(RecordingStatus.Stopped)
    override val status: StateFlow<RecordingStatus> get() = _status
    
    private val _freeStorage = MutableStateFlow(StorageInfo(1024 * 1024 * 256, "0:12:30:15", 16000))
    override val freeStorage: StateFlow<StorageInfo> get() = _freeStorage
    
    private val _recordingDuration = MutableStateFlow("00:00:00")
    override val recordingDuration: StateFlow<String> get() = _recordingDuration
    
    override fun hasRequiredPermissions() = false
    override fun startRecording() {}
    override fun pauseRecording() {}
    override fun resumeRecording() {}
    override fun stopRecording() {}
    override fun startAutoRecording() {}
    override fun stopAutoRecording() {}
    override fun isAutoRecordingEnabled() = false
    override fun isManualRecordingEnabled() = false
    override fun updateStorageInfo() {}
    override fun updateRecordingDuration() {}
}

@Composable
fun AudioTracerScreen(
    viewModel: RecorderViewModelInterface,
    onRequestPermissions: () -> Unit,
    onRequestManageExternalStorage: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val storageInfo by viewModel.freeStorage.collectAsState()
    val recordingDuration by viewModel.recordingDuration.collectAsState()
    val hasPermissions by remember { derivedStateOf { viewModel.hasRequiredPermissions() } }

    val coroutineScope = rememberCoroutineScope()

    // Periodically update storage info every 10 seconds
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updateStorageInfo()
            delay(10_000)
        }
    }

    // Update recording duration every second when recording
    LaunchedEffect(status) {
        if (status == RecordingStatus.Recording || status == RecordingStatus.Paused || 
            status == RecordingStatus.Armed || status == RecordingStatus.AutoRecording) {
            while (status == RecordingStatus.Recording || status == RecordingStatus.Paused ||
                   status == RecordingStatus.Armed || status == RecordingStatus.AutoRecording) {
                viewModel.updateRecordingDuration()
                delay(1_000)
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Audio Tracer",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            if (!hasPermissions) {
                PermissionRequestCard(
                    onRequestPermissions = onRequestPermissions,
                    onRequestManageExternalStorage = onRequestManageExternalStorage
                )
            } else {
                StatusDisplay(status = status)

                Spacer(modifier = Modifier.height(24.dp))

                // Recording mode selection
                RecordingModeSelector(
                    status = status,
                    onManualMode = { viewModel.startRecording() },
                    onAutoMode = { viewModel.startAutoRecording() },
                    onStop = { 
                        if (viewModel.isAutoRecordingEnabled()) {
                            viewModel.stopAutoRecording()
                        } else {
                            viewModel.stopRecording()
                        }
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Manual recording controls (only show when in manual mode)
                if (viewModel.isManualRecordingEnabled()) {
                    ManualRecordingControls(
                        status = status,
                        onPauseResume = {
                            if (status == RecordingStatus.Recording) viewModel.pauseRecording()
                            else if (status == RecordingStatus.Paused) viewModel.resumeRecording()
                        },
                        onStop = { viewModel.stopRecording() }
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                }

                RecordingDurationDisplay(recordingDuration = recordingDuration)

                Spacer(modifier = Modifier.height(16.dp))

                StorageInfoDisplay(storageInfo = storageInfo)
            }
        }
    }
}

@Composable
fun RecordingModeSelector(
    status: RecordingStatus,
    onManualMode: () -> Unit,
    onAutoMode: () -> Unit,
    onStop: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Recording Mode",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onManualMode,
                enabled = status == RecordingStatus.Stopped
            ) {
                Text("Manual Recording")
            }
            
            Button(
                onClick = onAutoMode,
                enabled = status == RecordingStatus.Stopped
            ) {
                Text("Voice Detection")
            }
        }
        
        if (status != RecordingStatus.Stopped) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onStop,
                enabled = status != RecordingStatus.Stopped
            ) {
                Text("Stop All")
            }
        }
    }
}

@Composable
fun ManualRecordingControls(
    status: RecordingStatus,
    onPauseResume: () -> Unit,
    onStop: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Manual Controls",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onPauseResume,
                enabled = status == RecordingStatus.Recording || status == RecordingStatus.Paused
            ) {
                Text(if (status == RecordingStatus.Recording) "Pause" else "Resume")
            }
            
            Button(
                onClick = onStop,
                enabled = status != RecordingStatus.Stopped
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
fun PermissionRequestCard(
    onRequestPermissions: () -> Unit,
    onRequestManageExternalStorage: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Permissions Required",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = "Audio Tracer needs microphone, notification, and storage permissions to record audio and save files for easy access.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            Button(onClick = onRequestPermissions) {
                Text("Grant Basic Permissions")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = onRequestManageExternalStorage) {
                    Text("Grant Storage Access")
                }
            }
        }
    }
}

@Composable
fun StatusDisplay(status: RecordingStatus) {
    val statusText = when (status) {
        RecordingStatus.Recording -> "Recording"
        RecordingStatus.Paused -> "Paused"
        RecordingStatus.Armed -> "Listening for voice..."
        RecordingStatus.AutoRecording -> "Auto Recording"
        RecordingStatus.Stopped -> "Stopped"
    }
    Text(
        text = "Status: $statusText",
        style = MaterialTheme.typography.titleLarge
    )
}

@Composable
fun RecordingDurationDisplay(recordingDuration: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Recording Duration",
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = recordingDuration,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
fun StorageInfoDisplay(storageInfo: StorageInfo) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Free storage, KB:\n${storageInfo.formattedKiloBytes()}",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Audio time left, DD:HH:mm:ss:\n${storageInfo.timeLeft} (${storageInfo.audioBitRate/1000} kbps)",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AudioTracerScreenPreview() {
    MaterialTheme {
        AudioTracerScreen(
            viewModel = MockRecorderViewModel(),
            onRequestPermissions = {},
            onRequestManageExternalStorage = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
fun AudioTracerScreenPermissionPreview() {
    MaterialTheme {
        AudioTracerScreen(
            viewModel = MockRecorderViewModelNoPermissions(),
            onRequestPermissions = {},
            onRequestManageExternalStorage = {}
        )
    }
}