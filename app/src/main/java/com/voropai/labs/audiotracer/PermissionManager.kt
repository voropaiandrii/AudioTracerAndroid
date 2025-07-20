package com.voropai.labs.audiotracer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

class PermissionManager(private val context: Context) {
    
    fun hasRequiredPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        val basicPermissionsGranted = permissions.all { permission ->
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
        
        // For Android 11+, also check MANAGE_EXTERNAL_STORAGE
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            basicPermissionsGranted && Environment.isExternalStorageManager()
        } else {
            basicPermissionsGranted
        }
    }
    
    fun getRequiredPermissions(): Array<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
    }
    
    fun needsManageExternalStoragePermission(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
    
    fun getPermissionRequestCode(): Int = 100
} 