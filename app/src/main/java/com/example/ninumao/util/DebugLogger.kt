package com.example.ninumao.util

import android.util.Log
import com.example.ninumao.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// DebugLogger 仅在 Debug 构建时收集日志，Release 版不输出接口请求信息。
object DebugLogger {

    private const val TAG = "NinumaoDebug"
    private const val MAX_LINES = 80

    private val _logFlow = MutableStateFlow("")
    val logFlow: StateFlow<String> = _logFlow.asStateFlow()

    // isEnabled 当前构建是否启用调试日志。
    val isEnabled: Boolean get() = BuildConfig.DEBUG

    // log 记录一行日志到 Logcat 和内存缓冲（Release 版为空操作）。
    fun log(tag: String, msg: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "[$tag] $msg")
        val current = _logFlow.value
        val lines = current.lines().takeLast(MAX_LINES - 1).toMutableList()
        lines.add("[$tag] $msg")
        _logFlow.value = lines.joinToString("\n")
    }

    // clear 清空日志缓冲。
    fun clear() {
        if (!BuildConfig.DEBUG) return
        _logFlow.value = ""
    }
}
