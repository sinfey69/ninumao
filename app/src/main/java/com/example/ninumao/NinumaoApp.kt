package com.example.ninumao

import android.app.Activity
import android.app.Application
import android.os.Bundle
import coil.Coil
import com.example.ninumao.data.config.ConfigRepository
import com.example.ninumao.data.weibo.WeiboRepository
import com.example.ninumao.server.ConfigHttpServer
import com.example.ninumao.util.AppCacheCleaner
import com.example.ninumao.util.ImageLoaderFactory
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
    private var startedActivityCount = 0

    // 设置页改配置后置位，首页 onResume 消费并刷新（避免 SharedFlow replay 导致退出播放也误刷新）
    @Volatile
    var pendingBrowseRefresh: Boolean = false
        private set

    private val _configUpdatedEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val configUpdatedEvents: SharedFlow<Unit> = _configUpdatedEvents.asSharedFlow()

    override fun onCreate() {
        super.onCreate()
        instance = this
        configRepository = ConfigRepository(this)
        weiboRepository = WeiboRepository()
        // 先清理过期磁盘缓存，再初始化 Coil，避免边用边删损坏 journal
        AppCacheCleaner.onAppStart(this)
        try {
            Coil.setImageLoader(ImageLoaderFactory.create(this))
        } catch (e: Exception) {
            AppCacheCleaner.wipeImageCache(this)
            Coil.setImageLoader(ImageLoaderFactory.create(this))
        }
        registerActivityLifecycleCallbacks(activityLifecycleCallbacks)
        appScope.launch {
            configRepository.ensureInitialized()
            startConfigServer()
        }
    }

    // notifyConfigUpdated 广播配置变更事件，并标记首页需要刷新。
    fun notifyConfigUpdated() {
        pendingBrowseRefresh = true
        _configUpdatedEvents.tryEmit(Unit)
    }

    // consumePendingBrowseRefresh 首页消费「待刷新」标记，返回是否需要重载列表。
    fun consumePendingBrowseRefresh(): Boolean {
        if (!pendingBrowseRefresh) return false
        pendingBrowseRefresh = false
        return true
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

    // activityLifecycleCallbacks 在应用退到后台时清理临时缓存。
    private val activityLifecycleCallbacks = object : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityStarted(activity: Activity) {
            startedActivityCount++
        }

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivityStopped(activity: Activity) {
            startedActivityCount--
            if (startedActivityCount <= 0) {
                startedActivityCount = 0
                appScope.launch {
                    AppCacheCleaner.onAppBackground(applicationContext)
                }
            }
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        lateinit var instance: NinumaoApp
            private set
    }
}
