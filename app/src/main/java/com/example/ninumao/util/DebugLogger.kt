package com.example.ninumao.util

import android.util.Log
import com.example.ninumao.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// DebugLogger 收集调试日志；默认关闭，需在设置页手动开启。
object DebugLogger {

    private const val TAG = "NinumaoDebug"
    private const val MAX_LINES = 80

    private val _logFlow = MutableStateFlow("")
    val logFlow: StateFlow<String> = _logFlow.asStateFlow()

    // collectionEnabled 用户是否开启日志采集（默认关闭）。
    @Volatile
    var collectionEnabled: Boolean = false
        private set

    // isEnabled 当前是否应采集日志（需 Debug 构建且用户已开启）。
    val isEnabled: Boolean
        get() = BuildConfig.DEBUG && collectionEnabled

    // log 记录一行日志到 Logcat 和内存缓冲。
    fun log(tag: String, msg: String) {
        if (!isEnabled) return
        Log.d(TAG, "[$tag] $msg")
        val current = _logFlow.value
        val lines = current.lines().takeLast(MAX_LINES - 1).toMutableList()
        lines.add("[$tag] $msg")
        _logFlow.value = lines.joinToString("\n")
    }

    // clear 清空日志缓冲。
    fun clear() {
        _logFlow.value = ""
    }

    // setEnabled 开关日志采集；关闭时同步清空缓冲。
    fun setEnabled(enabled: Boolean) {
        collectionEnabled = enabled && BuildConfig.DEBUG
        if (!collectionEnabled) {
            clear()
        }
    }
}
