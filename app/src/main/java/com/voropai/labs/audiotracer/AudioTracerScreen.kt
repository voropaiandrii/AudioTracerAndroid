package com.voropai.labs.audiotracer

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun AudioTracerScreen(
    viewModel: RecorderViewModel,
    onRequestPermissions: () -> Unit,
    onRequestManageExternalStorage: () -> Unit
) {
    val status by viewModel.status.collectAsState()
    val storageInfo by viewModel.freeStorage.collectAsState()
    val hasPermissions by remember { derivedStateOf { viewModel.hasRequiredPermissions() } }

    val coroutineScope = rememberCoroutineScope()

    // Periodically update storage info every 10 seconds
    LaunchedEffect(Unit) {
        while (true) {
            viewModel.updateStorageInfo()
            delay(10_000)
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

                RecordingControls(
                    status = status,
                    onStart = { viewModel.startRecording() },
                    onPauseResume = {
                        if (status == RecordingStatus.Recording) viewModel.pauseRecording()
                        else if (status == RecordingStatus.Paused) viewModel.resumeRecording()
                    },
                    onStop = { viewModel.stopRecording() }
                )

                Spacer(modifier = Modifier.height(32.dp))

                StorageInfoDisplay(storageInfo = storageInfo)
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
fun RecordingControls(
    status: RecordingStatus,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Button(
            onClick = onStart,
            enabled = status == RecordingStatus.Stopped
        ) {
            Text("Start")
        }
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

@Composable
fun StatusDisplay(status: RecordingStatus) {
    val statusText = when (status) {
        RecordingStatus.Recording -> "Recording"
        RecordingStatus.Paused -> "Paused"
        RecordingStatus.Stopped -> "Stopped"
    }
    Text(
        text = "Status: $statusText",
        style = MaterialTheme.typography.titleLarge
    )
}

@Composable
fun StorageInfoDisplay(storageInfo: StorageInfo) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "Free storage: ${storageInfo.formattedBytes()} bytes",
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = "Audio time left: ${storageInfo.timeLeft} (128 kbps)",
            style = MaterialTheme.typography.bodyMedium
        )
    }
} 