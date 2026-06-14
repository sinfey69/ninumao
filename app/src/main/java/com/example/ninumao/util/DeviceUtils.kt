package com.example.ninumao.util

import android.os.Build

// DeviceUtils 提供设备环境判断。
object DeviceUtils {

    // isEmulator 判断是否在 Android 模拟器上运行。
    fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk", ignoreCase = true) ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.MANUFACTURER.contains("Genymotion", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk_gphone", ignoreCase = true)
    }
}
