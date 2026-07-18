package com.example.ninumao.ui.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ninumao.BuildConfig
import com.example.ninumao.NinumaoApp
import com.example.ninumao.R
import com.example.ninumao.data.config.RecentBlogger
import com.example.ninumao.util.DebugLogger
import com.example.ninumao.util.DeviceUtils
import com.example.ninumao.util.NetworkUtils
import com.example.ninumao.util.QrCodeGenerator
import kotlinx.coroutines.launch

// SettingsActivity 承载设置页与二维码配置入口。
class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.fragment_settings)
        bindActions()
        refreshDisplay()
        focusPrimaryControl()
    }

    override fun onResume() {
        super.onResume()
        refreshDisplay()
    }

    // focusPrimaryControl 进入设置页时默认聚焦 UID 输入框，避免落到调试日志区。
    private fun focusPrimaryControl() {
        val etUid = findViewById<EditText>(R.id.et_uid) ?: return
        val settingsScroll = findViewById<ScrollView>(R.id.settings_scroll)
        etUid.post {
            settingsScroll?.scrollTo(0, 0)
            etUid.requestFocus()
        }
    }

    // bindActions 绑定输入框与按钮事件。
    private fun bindActions() {
        val etUid = findViewById<EditText>(R.id.et_uid)
        val etCookie = findViewById<EditText>(R.id.et_cookie)

        // UID 输入框按回车保存。
        etUid.setOnEditorActionListener { _, actionId, event ->
            val isDone = actionId == EditorInfo.IME_ACTION_DONE
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER &&
                event.action == KeyEvent.ACTION_DOWN
            if (isDone || isEnter) {
                saveUid(etUid.text.toString())
                true
            } else false
        }

        findViewById<View>(R.id.btn_save_uid).setOnClickListener {
            saveUid(etUid.text.toString())
        }

        findViewById<View>(R.id.btn_save_cookie).setOnClickListener {
            saveCookie(etCookie.text.toString())
        }

        findViewById<View>(R.id.btn_refresh_pin).setOnClickListener {
            refreshPin()
        }

        if (BuildConfig.DEBUG) {
            findViewById<View>(R.id.debug_log_section)?.visibility = View.VISIBLE
            findViewById<View>(R.id.btn_toggle_debug_log)?.setOnClickListener {
                toggleDebugLog()
            }
            findViewById<View>(R.id.btn_clear_log)?.setOnClickListener {
                DebugLogger.clear()
            }
            updateDebugLogUi()
            observeLog()
        } else {
            findViewById<View>(R.id.debug_log_section)?.visibility = View.GONE
        }
    }

    // toggleDebugLog 切换调试日志采集开关。
    private fun toggleDebugLog() {
        val enabled = !DebugLogger.collectionEnabled
        DebugLogger.setEnabled(enabled)
        updateDebugLogUi()
        Toast.makeText(
            this,
            if (enabled) R.string.settings_debug_log_on else R.string.settings_debug_log_off,
            Toast.LENGTH_SHORT,
        ).show()
    }

    // updateDebugLogUi 按开关状态刷新日志区控件。
    private fun updateDebugLogUi() {
        val enabled = DebugLogger.collectionEnabled
        findViewById<Button>(R.id.btn_toggle_debug_log)?.text = getString(
            if (enabled) R.string.settings_debug_log_disable else R.string.settings_debug_log_enable,
        )
        findViewById<View>(R.id.btn_clear_log)?.visibility =
            if (enabled) View.VISIBLE else View.GONE
        findViewById<View>(R.id.log_scroll)?.visibility =
            if (enabled) View.VISIBLE else View.GONE
    }

    // observeLog 订阅调试日志并滚动到底部，但不抢焦点。
    private fun observeLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugLogger.logFlow.collect { log ->
                    if (!DebugLogger.collectionEnabled) return@collect
                    val logText = findViewById<TextView>(R.id.log_text) ?: return@collect
                    val logScroll = findViewById<ScrollView>(R.id.log_scroll) ?: return@collect
                    logText.text = log.ifBlank { "（暂无日志，刷新首页列表即可生成）" }
                    // 仅滚动日志区域，不改变当前焦点控件
                    logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    // saveUid 校验并保存 UID，同时拉取博主名称写入最近列表。
    private fun saveUid(raw: String) {
        val uid = raw.trim()
        if (uid.isBlank()) {
            Toast.makeText(this, R.string.error_no_uid, Toast.LENGTH_SHORT).show()
            return
        }
        val app = application as NinumaoApp
        lifecycleScope.launch {
            Toast.makeText(this@SettingsActivity, R.string.settings_saving_uid, Toast.LENGTH_SHORT).show()
            val config = app.configRepository.getConfig()
            val name = app.weiboRepository.fetchBloggerName(config, uid)
            app.configRepository.updateUid(uid, displayName = name.orEmpty())
            app.notifyConfigUpdated()
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            refreshDisplay()
        }
    }

    // switchToRecentUid 一键切换到最近使用的博主。
    private fun switchToRecentUid(uid: String, displayName: String) {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.updateUid(uid, displayName = displayName)
            app.notifyConfigUpdated()
            findViewById<EditText>(R.id.et_uid)?.setText(uid)
            val label = displayName.ifBlank { uid }
            Toast.makeText(
                this@SettingsActivity,
                getString(R.string.settings_switched, label),
                Toast.LENGTH_SHORT,
            ).show()
            refreshDisplay()
        }
    }

    // saveCookie 保存 Cookie。
    private fun saveCookie(raw: String) {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.updateCookie(raw.trim())
            app.notifyConfigUpdated()
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    // refreshDisplay 刷新当前 UID、最近列表、PIN 与二维码。
    private fun refreshDisplay() {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            val config = app.configRepository.getConfig()

            // 填入当前 UID（输入中时不覆盖用户正在编辑的内容）
            val etUid = findViewById<EditText>(R.id.et_uid) ?: return@launch
            if (!etUid.isFocused && etUid.text?.toString() != config.uid) {
                etUid.setText(config.uid)
            }

            // 填入当前 Cookie 到输入框
            val etCookie = findViewById<EditText>(R.id.et_cookie) ?: return@launch
            if (etCookie.text.isNullOrBlank()) {
                etCookie.setText(config.cookie)
            }

            val currentName = config.recentBloggers.firstOrNull { it.uid == config.uid }?.displayName
            findViewById<TextView>(R.id.uid_current_text)?.text = when {
                config.uid.isBlank() -> "当前：未设置"
                !currentName.isNullOrBlank() && currentName != config.uid ->
                    "当前：$currentName（${config.uid}）"
                else -> "当前：${config.uid}"
            }
            findViewById<TextView>(R.id.pin_text)?.text =
                getString(R.string.settings_pin) + "：" + config.pin

            renderRecentBloggers(config.recentBloggers, config.uid)

            val qrImage = findViewById<ImageView>(R.id.qr_image) ?: return@launch
            val configUrlText = findViewById<TextView>(R.id.config_url_text) ?: return@launch
            val noWifiText = findViewById<TextView>(R.id.no_wifi_text) ?: return@launch
            val emulatorHintText = findViewById<TextView>(R.id.emulator_hint_text) ?: return@launch

            val configUrl = resolveConfigUrl(config.webPort, config.pin)
            if (configUrl == null) {
                qrImage.visibility = View.GONE
                configUrlText.visibility = View.GONE
                emulatorHintText.visibility = View.GONE
                noWifiText.visibility = View.VISIBLE
            } else {
                qrImage.visibility = View.VISIBLE
                configUrlText.visibility = View.VISIBLE
                noWifiText.visibility = View.GONE
                configUrlText.text = getString(R.string.config_url_format, configUrl)
                qrImage.setImageBitmap(QrCodeGenerator.generate(configUrl, size = 512))
                if (DeviceUtils.isEmulator()) {
                    emulatorHintText.visibility = View.VISIBLE
                    emulatorHintText.text = getString(R.string.settings_emulator_hint, config.pin)
                } else {
                    emulatorHintText.visibility = View.GONE
                }
            }
        }
    }

    // renderRecentBloggers 渲染最近博主按钮列表（显示名称），支持一键切换。
    private fun renderRecentBloggers(
        recentBloggers: List<RecentBlogger>,
        currentUid: String,
    ) {
        val container = findViewById<LinearLayout>(R.id.recent_uid_container) ?: return
        val emptyView = findViewById<TextView>(R.id.recent_uid_empty) ?: return
        container.removeAllViews()

        if (recentBloggers.isEmpty()) {
            emptyView.visibility = View.VISIBLE
            return
        }
        emptyView.visibility = View.GONE

        val density = resources.displayMetrics.density
        val marginTop = (8 * density).toInt()
        recentBloggers.forEach { blogger ->
            val title = if (blogger.uid == currentUid) {
                getString(R.string.settings_recent_uid_current, blogger.displayName)
            } else {
                blogger.displayName
            }
            val subtitle = getString(R.string.settings_recent_uid_subtitle, blogger.uid)
            val button = Button(this).apply {
                isFocusable = true
                isAllCaps = false
                text = "$title\n$subtitle"
                setTextColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.white))
                backgroundTintList = ContextCompat.getColorStateList(
                    this@SettingsActivity,
                    if (blogger.uid == currentUid) R.color.accent else R.color.primary,
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = marginTop
                }
                setOnClickListener {
                    if (blogger.uid != currentUid) {
                        switchToRecentUid(blogger.uid, blogger.name)
                    } else {
                        findViewById<EditText>(R.id.et_uid)?.setText(blogger.uid)
                    }
                }
            }
            container.addView(button)
        }
    }

    // resolveConfigUrl 解析可用于展示/扫码的配置地址。
    private fun resolveConfigUrl(port: Int, pin: String): String? {
        if (DeviceUtils.isEmulator()) return "http://127.0.0.1:$port/?pin=$pin"
        val ip = NetworkUtils.getLanIpAddress(this) ?: return null
        return NetworkUtils.buildConfigUrl(ip, port, pin)
    }

    // refreshPin 重新生成访问 PIN。
    private fun refreshPin() {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.refreshPin()
            refreshDisplay()
            Toast.makeText(this@SettingsActivity, R.string.settings_refresh_pin, Toast.LENGTH_SHORT).show()
        }
    }
}
