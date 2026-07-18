package com.example.ninumao.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ninumao.NinumaoApp
import com.example.ninumao.R
import com.example.ninumao.ui.settings.SettingsActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// MainActivity 承载视频列表或引导页，并管理全局加载覆盖层。
class MainActivity : FragmentActivity() {

    private var loadingOverlay: View? = null
    private var decideScreenJob: Job? = null
    private var showingBrowse: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        decideStartScreen()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时可能刚保存了 UID，需要再判断一次
        decideStartScreen()
    }

    // decideStartScreen 根据 UID 是否配置决定显示引导页还是视频列表。
    private fun decideStartScreen() {
        val app = application as NinumaoApp
        decideScreenJob?.cancel()
        decideScreenJob = lifecycleScope.launch {
            val config = app.configRepository.getConfig()
            if (config.uid.isBlank()) {
                showingBrowse = false
                showOnboarding()
            } else {
                showBrowse()
            }
        }
    }

    // showOnboarding 显示引导/空状态页。
    private fun showOnboarding() {
        val currentFrag = supportFragmentManager.findFragmentById(android.R.id.content)
        if (currentFrag != null) {
            supportFragmentManager.beginTransaction().remove(currentFrag).commitNowAllowingStateLoss()
        }
        setContentView(R.layout.activity_onboard)
        loadingOverlay = null
        findViewById<View>(R.id.onboard_action_btn)?.setOnClickListener { openSettings() }
        window.decorView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && isOpenSettingsKey(keyCode)) {
                openSettings()
                true
            } else false
        }
    }

    // showBrowse 显示多列网格视频列表，并注入加载覆盖层。
    private fun showBrowse() {
        val current = supportFragmentManager.findFragmentById(android.R.id.content)
        if (current is VideoGridFragment || showingBrowse) {
            window.decorView.post { attachLoadingOverlay() }
            return
        }
        showingBrowse = true
        supportFragmentManager.beginTransaction()
            .replace(android.R.id.content, VideoGridFragment(), TAG_BROWSE)
            .commitNowAllowingStateLoss()
        attachLoadingOverlay()
    }

    // attachLoadingOverlay 在根 DecorView 上叠加加载图，订阅 ViewModel 的 isLoading 状态。
    private fun attachLoadingOverlay() {
        val root = window.decorView as? FrameLayout ?: return
        if (loadingOverlay != null) return

        val overlay = LayoutInflater.from(this)
            .inflate(R.layout.overlay_loading, root, false)
        overlay.visibility = View.GONE
        root.addView(overlay)
        loadingOverlay = overlay

        val app = application as NinumaoApp
        val vm = ViewModelProvider(
            this,
            VideoViewModelFactory(app.configRepository, app.weiboRepository),
        )[VideoViewModel::class.java]

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.uiState.collect { state ->
                    // 只有首次加载（没有任何视频）时才显示加载图
                    overlay.visibility = if (state.isLoading && state.videos.isEmpty()) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isOpenSettingsKey(keyCode)) {
            openSettings()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // isOpenSettingsKey 判断是否为打开设置的按键。
    private fun isOpenSettingsKey(keyCode: Int): Boolean {
        return keyCode == KeyEvent.KEYCODE_MENU ||
            keyCode == KeyEvent.KEYCODE_SEARCH ||
            keyCode == KeyEvent.KEYCODE_INFO
    }

    // openSettings 打开设置页。
    fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    companion object {
        private const val TAG_BROWSE = "browse"
    }
}
