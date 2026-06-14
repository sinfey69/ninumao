package com.example.ninumao.util

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

// ErrorMapper 将网络异常转为用户可读的提示文案。
object ErrorMapper {

    // toUserMessage 把异常映射为中文错误说明。
    fun toUserMessage(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is UnknownHostException ||
                message.contains("Unable to resolve host", ignoreCase = true) ||
                message.contains("No address associated with hostname", ignoreCase = true) -> {
                "无法连接微博服务器，请检查电视网络是否正常。\n" +
                    "若刚关闭 VPN，可尝试重启路由器或把 DNS 改为 223.5.5.5 / 114.114.114.114。"
            }
            error is ConnectException -> "网络连接失败，请确认电视已连接 WiFi 或网线。"
            error is SocketTimeoutException -> "连接微博超时，请稍后重试。"
            message.isNotBlank() -> message
            else -> "加载失败，请稍后重试"
        }
    }
}
