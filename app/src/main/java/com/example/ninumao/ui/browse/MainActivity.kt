package com.example.ninumao.ui.browse

import android.app.Dialog
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
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
    private var exitDialog: Dialog? = null
    private var lastDialogShowTime: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupBackHandler()
        decideStartScreen()
    }

    override fun onResume() {
        super.onResume()
        // 从设置页返回时可能刚保存了 UID，需要再判断一次
        decideStartScreen()
    }

    override fun onDestroy() {
        exitDialog?.dismiss()
        exitDialog = null
        super.onDestroy()
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

    // setupBackHandler 注册 AndroidX 返回事件拦截，确保电视遥控器和手势返回一致。
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            },
        )
    }

    // handleBackPress 处理返回按键：若弹窗已显示则关闭弹窗，否则弹出退出确认。
    private fun handleBackPress() {
        val now = SystemClock.uptimeMillis()
        if (exitDialog?.isShowing == true) {
            // 避免按键抖动或快速连击误关弹窗（至少间隔 400ms）
            if (now - lastDialogShowTime > 400) {
                exitDialog?.dismiss()
            }
        } else {
            showExitConfirmDialog()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && isOpenSettingsKey(keyCode)) {
            openSettings()
            return true
        }
        // KEYCODE_ESCAPE 开启追踪，在 onKeyUp 触发，与系统 KEYCODE_BACK 机制保持一致，避免按压与抬起重复触发
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.repeatCount == 0) {
            event.startTracking()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && event.isTracking && !event.isCanceled) {
            handleBackPress()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    // showExitConfirmDialog 遥控器后退退出应用前弹出确认框，默认焦点在取消。
    private fun showExitConfirmDialog() {
        if (isFinishing || isDestroyed) return
        if (exitDialog?.isShowing == true) return

        lastDialogShowTime = SystemClock.uptimeMillis()
        val view = layoutInflater.inflate(R.layout.dialog_exit_confirm, null)
        val dialog = Dialog(this, R.style.Theme_Ninumao_ExitDialog).apply {
            setContentView(view)
            setCancelable(true)
            setCanceledOnTouchOutside(false)
        }
        val cancelBtn = view.findViewById<Button>(R.id.btn_exit_cancel)
        val confirmBtn = view.findViewById<Button>(R.id.btn_exit_confirm)
        cancelBtn.setOnClickListener { dialog.dismiss() }
        confirmBtn.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }
        // 弹窗内部响应返回键关闭：只在 ACTION_UP 触发，且避免刚弹出瞬间的残余抬起事件误关
        dialog.setOnKeyListener { _, keyCode, keyEvent ->
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    if (SystemClock.uptimeMillis() - lastDialogShowTime > 400) {
                        dialog.dismiss()
                    }
                    true
                } else {
                    keyEvent.action == KeyEvent.ACTION_DOWN
                }
            } else {
                false
            }
        }
        // 确保布局完成、Window 获得焦点后聚焦在取消按钮
        dialog.setOnShowListener {
            cancelBtn.post {
                cancelBtn.requestFocus()
            }
        }
        dialog.setOnDismissListener { exitDialog = null }
        exitDialog = dialog
        dialog.show()
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
