package com.example.ninumao.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_config")

// ConfigRepository 负责读写 UID、Cookie 与访问 PIN。
class ConfigRepository(private val context: Context) {

    private val dataStore = context.configDataStore

    val configFlow: Flow<AppConfig> = dataStore.data.map { prefs ->
        AppConfig(
            uid = prefs[KEY_UID].orEmpty(),
            cookie = prefs[KEY_COOKIE].orEmpty(),
            pin = prefs[KEY_PIN].orEmpty(),
            webPort = prefs[KEY_WEB_PORT] ?: AppConfig.DEFAULT_WEB_PORT,
        )
    }

    // ensureInitialized 确保 PIN 等默认值已写入。
    suspend fun ensureInitialized() {
        val current = configFlow.first()
        if (current.pin.isBlank()) {
            saveConfig(current.copy(pin = generatePin()))
        }
    }

    // getConfig 读取当前配置快照。
    suspend fun getConfig(): AppConfig {
        ensureInitialized()
        return configFlow.first()
    }

    // saveConfig 持久化完整配置。
    suspend fun saveConfig(config: AppConfig) {
        dataStore.edit { prefs ->
            prefs[KEY_UID] = config.uid.trim()
            prefs[KEY_COOKIE] = config.cookie.trim()
            prefs[KEY_PIN] = config.pin.ifBlank { generatePin() }
            prefs[KEY_WEB_PORT] = config.webPort
        }
    }

    // updateUid 仅更新博主 UID。
    suspend fun updateUid(uid: String) {
        val current = getConfig()
        saveConfig(current.copy(uid = uid.trim()))
    }

    // updateCookie 仅更新 Cookie。
    suspend fun updateCookie(cookie: String) {
        val current = getConfig()
        saveConfig(current.copy(cookie = cookie.trim()))
    }

    // refreshPin 重新生成访问 PIN。
    suspend fun refreshPin(): String {
        val pin = generatePin()
        val current = getConfig()
        saveConfig(current.copy(pin = pin))
        return pin
    }

    // generatePin 生成 6 位数字 PIN。
    private fun generatePin(): String {
        return Random.nextInt(100000, 999999).toString()
    }

    companion object {
        private val KEY_UID = stringPreferencesKey("uid")
        private val KEY_COOKIE = stringPreferencesKey("cookie")
        private val KEY_PIN = stringPreferencesKey("pin")
        private val KEY_WEB_PORT = intPreferencesKey("web_port")
    }
}
