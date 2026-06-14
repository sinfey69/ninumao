package com.example.ninumao.data.config

// AppConfig 保存应用运行所需的本地配置。
data class AppConfig(
    val uid: String = "",
    val cookie: String = "",
    val pin: String = "",
    val webPort: Int = DEFAULT_WEB_PORT,
) {
    companion object {
        const val DEFAULT_WEB_PORT = 8765
    }
}
