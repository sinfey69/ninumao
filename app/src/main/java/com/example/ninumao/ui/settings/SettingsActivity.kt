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
import com.example.ninumao.data.weibo.QrCheckResult
import com.example.ninumao.data.weibo.WeiboQrLoginClient
import com.example.ninumao.util.DeviceUtils
import com.example.ninumao.util.NetworkUtils
import com.example.ninumao.util.QrCodeGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// SettingsActivity 承载设置页与二维码配置入口。
class SettingsActivity : FragmentActivity() {

    private var qrLoginJob: Job? = null
    private var qrAutoStarted: Boolean = false

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

    override fun onDestroy() {
        qrLoginJob?.cancel()
        super.onDestroy()
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

        findViewById<View>(R.id.btn_qr_login).setOnClickListener {
            startQrLogin()
        }

        findViewById<View>(R.id.btn_logout).setOnClickListener {
            logoutWeibo()
        }

        findViewById<View>(R.id.btn_refresh_pin).setOnClickListener {
            refreshPin()
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

    // startQrLogin 生成微博登录二维码并轮询扫码状态。
    private fun startQrLogin() {
        qrLoginJob?.cancel()
        val qrImage = findViewById<ImageView>(R.id.login_qr_image) ?: return
        val statusView = findViewById<TextView>(R.id.login_qr_status) ?: return
        val startBtn = findViewById<Button>(R.id.btn_qr_login) ?: return
        qrLoginJob = lifecycleScope.launch {
            try {
                startBtn.isEnabled = false
                statusView.text = getString(R.string.loading)
                val client = WeiboQrLoginClient()
                val session = client.createSession()
                val bitmap = client.downloadQrImage(session.imageUrl)
                if (bitmap == null) {
                    throw IllegalStateException("二维码图片下载失败")
                }
                qrImage.setImageBitmap(bitmap)
                qrImage.visibility = View.VISIBLE
                startBtn.text = getString(R.string.settings_qr_login_refresh)
                startBtn.isEnabled = true
                statusView.text = getString(R.string.settings_qr_login_waiting)
                while (isActive) {
                    delay(1500)
                    when (val result = client.check(session.qrid)) {
                        QrCheckResult.Waiting ->
                            statusView.text = getString(R.string.settings_qr_login_waiting)
                        QrCheckResult.Scanned ->
                            statusView.text = getString(R.string.settings_qr_login_scanned)
                        QrCheckResult.Expired -> {
                            statusView.text = getString(R.string.settings_qr_login_expired)
                            break
                        }
                        is QrCheckResult.Confirmed -> {
                            val cookie = client.exchangeCookie(result.alt)
                            persistQrCookie(cookie)
                            renderLoginStatus(true)
                            statusView.text = getString(R.string.settings_qr_login_success)
                            Toast.makeText(
                                this@SettingsActivity,
                                R.string.settings_qr_login_success,
                                Toast.LENGTH_SHORT,
                            ).show()
                            break
                        }
                        is QrCheckResult.Failed -> {
                            statusView.text = getString(R.string.settings_qr_login_failed, result.message)
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                statusView.text = getString(
                    R.string.settings_qr_login_failed,
                    e.message ?: e.javaClass.simpleName,
                )
                startBtn.isEnabled = true
            }
        }
    }

    // persistQrCookie 把扫码得到的登录态写入配置。
    private suspend fun persistQrCookie(cookie: String) {
        val app = application as NinumaoApp
        app.configRepository.updateCookie(cookie)
        app.notifyConfigUpdated()
    }

    // logoutWeibo 清除扫码登录态。
    private fun logoutWeibo() {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.updateCookie("")
            app.notifyConfigUpdated()
            renderLoginStatus(false)
            qrAutoStarted = false
            Toast.makeText(this@SettingsActivity, R.string.settings_logout_done, Toast.LENGTH_SHORT).show()
            startQrLogin()
        }
    }

    // renderLoginStatus 刷新登录状态：已登录时收起登录码，未登录时露出扫码区。
    private fun renderLoginStatus(loggedIn: Boolean) {
        findViewById<TextView>(R.id.login_status_text)?.text = getString(
            if (loggedIn) R.string.settings_login_on else R.string.settings_login_off,
        )
        findViewById<View>(R.id.btn_logout)?.visibility =
            if (loggedIn) View.VISIBLE else View.GONE
        findViewById<View>(R.id.btn_qr_login)?.visibility =
            if (loggedIn) View.GONE else View.VISIBLE
        if (loggedIn) {
            qrLoginJob?.cancel()
            findViewById<ImageView>(R.id.login_qr_image)?.visibility = View.GONE
            findViewById<TextView>(R.id.login_qr_status)?.text = ""
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

            val loggedIn = config.cookie.isNotBlank()
            renderLoginStatus(loggedIn)
            if (!loggedIn && !qrAutoStarted) {
                qrAutoStarted = true
                startQrLogin()
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
                isFocusableInTouchMode = true
                isAllCaps = false
                text = if (blogger.uid == currentUid) "● $title · $subtitle" else "$title · $subtitle"
                textSize = 13.5f
                setPadding((16 * density).toInt(), (10 * density).toInt(), (16 * density).toInt(), (10 * density).toInt())
                setTextColor(ContextCompat.getColor(this@SettingsActivity, android.R.color.white))
                background = ContextCompat.getDrawable(this@SettingsActivity, R.drawable.bg_recent_chip)
                backgroundTintList = null
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
