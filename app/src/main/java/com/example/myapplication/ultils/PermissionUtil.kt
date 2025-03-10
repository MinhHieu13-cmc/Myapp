package com.example.myapplication.ultils

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionUtil {
    fun allPermissionsGranted(context: Context, allPermission: Array<String>) = allPermission.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}