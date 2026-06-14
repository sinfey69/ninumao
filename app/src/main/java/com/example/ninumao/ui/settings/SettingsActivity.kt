package com.example.ninumao.ui.settings

import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ninumao.BuildConfig
import com.example.ninumao.NinumaoApp
import com.example.ninumao.R
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
    }

    override fun onResume() {
        super.onResume()
        refreshDisplay()
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
            findViewById<View>(R.id.btn_clear_log).setOnClickListener {
                DebugLogger.clear()
            }
            observeLog()
        } else {
            findViewById<View>(R.id.debug_log_section)?.visibility = View.GONE
        }
    }

    // observeLog 订阅调试日志并滚动到底部。
    private fun observeLog() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                DebugLogger.logFlow.collect { log ->
                    val logText = findViewById<TextView>(R.id.log_text) ?: return@collect
                    val logScroll = findViewById<ScrollView>(R.id.log_scroll) ?: return@collect
                    logText.text = log.ifBlank { "（暂无日志，点主页刷新即可生成）" }
                    logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
                }
            }
        }
    }

    // saveUid 校验并保存 UID。
    private fun saveUid(raw: String) {
        val uid = raw.trim()
        if (uid.isBlank()) {
            Toast.makeText(this, R.string.error_no_uid, Toast.LENGTH_SHORT).show()
            return
        }
        val app = application as NinumaoApp
        lifecycleScope.launch {
            app.configRepository.updateUid(uid)
            app.notifyConfigUpdated()
            Toast.makeText(this@SettingsActivity, R.string.settings_saved, Toast.LENGTH_SHORT).show()
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

    // refreshDisplay 刷新当前 UID、PIN 与二维码。
    private fun refreshDisplay() {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            val config = app.configRepository.getConfig()

            // 填入当前 UID 到输入框
            val etUid = findViewById<EditText>(R.id.et_uid) ?: return@launch
            if (etUid.text.isNullOrBlank()) {
                etUid.setText(config.uid)
            }

            // 填入当前 Cookie 到输入框
            val etCookie = findViewById<EditText>(R.id.et_cookie) ?: return@launch
            if (etCookie.text.isNullOrBlank()) {
                etCookie.setText(config.cookie)
            }

            findViewById<TextView>(R.id.uid_current_text)?.text =
                if (config.uid.isBlank()) "当前：未设置" else "当前：${config.uid}"
            findViewById<TextView>(R.id.pin_text)?.text =
                getString(R.string.settings_pin) + "：" + config.pin

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
