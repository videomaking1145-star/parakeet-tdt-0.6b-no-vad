package com.example.parakeet06bv3

import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class PermissionManager(private val activity: Activity) {

    companion object {
        const val REQUEST_CODE = 100
        // 💡 여기에 필요한 권한을 다 때려 박으면 돼
        val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.CAMERA
        )
    }

    // 모든 권한이 승인되었는지 한 번에 체크
    fun hasAllPermissions(): Boolean {
        return REQUIRED_PERMISSIONS.all { permission ->
            ContextCompat.checkSelfPermission(activity, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    // 없는 권한들 모아서 시스템 팝업 띄우기
    fun requestPermissions() {
        ActivityCompat.requestPermissions(activity, REQUIRED_PERMISSIONS, REQUEST_CODE)
    }
}