package com.example.ninumao

import android.app.Application
import com.example.ninumao.data.config.ConfigRepository
import com.example.ninumao.data.weibo.WeiboRepository
import com.example.ninumao.server.ConfigHttpServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

// NinumaoApp 负责初始化全局依赖与内嵌配置服务。
class NinumaoApp : Application() {

    lateinit var configRepository: ConfigRepository
        private set

    lateinit var weiboRepository: WeiboRepository
        private set

    private var configHttpServer: ConfigHttpServer? = null
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _configUpdatedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configUpdatedEvents: SharedFlow<Unit> = _configUpdatedEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        configRepository = ConfigRepository(this)
        weiboRepository = WeiboRepository()
        appScope.launch {
            configRepository.ensureInitialized()
            startConfigServer()
        }
    }

    // notifyConfigUpdated 广播配置变更事件。
    fun notifyConfigUpdated() {
        _configUpdatedEvents.tryEmit(Unit)
    }

    // startConfigServer 启动局域网配置 HTTP 服务。
    private suspend fun startConfigServer() {
        val config = configRepository.getConfig()
        configHttpServer?.stopServer()
        configHttpServer = ConfigHttpServer(
            port = config.webPort,
            context = this,
            configRepository = configRepository,
            onConfigUpdated = { notifyConfigUpdated() },
        ).also { it.startServer() }
    }

    companion object {
        lateinit var instance: NinumaoApp
            private set
    }
}
