package com.example.parakeet06bv3

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat


class PermissionManager(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE = 100
    }

    fun hasAudioPermission(): Boolean {
        return ActivityCompat.checkSelfPermission(activity, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    fun requestAudioPermission() {
        ActivityCompat.requestPermissions(activity, arrayOf(android.Manifest.permission.RECORD_AUDIO), REQUEST_CODE)
    }
}