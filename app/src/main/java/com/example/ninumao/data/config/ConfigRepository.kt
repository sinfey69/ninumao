package com.example.ninumao.data.config

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.random.Random

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "app_config")

// ConfigRepository 负责读写 UID、Cookie、访问 PIN 与最近博主列表。
class ConfigRepository(private val context: Context) {

    private val dataStore = context.configDataStore
    private val recentAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter<List<RecentBlogger>>(
            Types.newParameterizedType(List::class.java, RecentBlogger::class.java),
        )

    val configFlow: Flow<AppConfig> = dataStore.data.map { prefs ->
        val recentRaw = prefs[KEY_RECENT_BLOGGERS]
            ?.takeIf { it.isNotBlank() }
            ?: prefs[KEY_RECENT_UIDS_LEGACY].orEmpty()
        AppConfig(
            uid = prefs[KEY_UID].orEmpty(),
            cookie = prefs[KEY_COOKIE].orEmpty(),
            pin = prefs[KEY_PIN].orEmpty(),
            webPort = prefs[KEY_WEB_PORT] ?: AppConfig.DEFAULT_WEB_PORT,
            recentBloggers = parseRecentBloggers(recentRaw),
        )
    }

    // ensureInitialized 确保 PIN 已写入，并把已有 UID 迁入最近列表。
    suspend fun ensureInitialized() {
        val current = configFlow.first()
        val needsPin = current.pin.isBlank()
        val needsRecentSeed = current.uid.isNotBlank() && current.recentBloggers.isEmpty()
        if (needsPin || needsRecentSeed) {
            saveConfig(
                current.copy(
                    pin = if (needsPin) generatePin() else current.pin,
                ),
            )
        }
    }

    // getConfig 读取当前配置快照。
    suspend fun getConfig(): AppConfig {
        ensureInitialized()
        return configFlow.first()
    }

    // saveConfig 持久化完整配置，并维护最近博主列表。
    suspend fun saveConfig(config: AppConfig) {
        val trimmedUid = config.uid.trim()
        val recent = if (trimmedUid.isNotBlank()) {
            val existingName = config.recentBloggers.firstOrNull { it.uid == trimmedUid }?.name.orEmpty()
            prependRecentBlogger(
                config.recentBloggers,
                RecentBlogger(uid = trimmedUid, name = existingName),
            )
        } else {
            config.recentBloggers.take(AppConfig.MAX_RECENT_UIDS)
        }
        dataStore.edit { prefs ->
            prefs[KEY_UID] = trimmedUid
            prefs[KEY_COOKIE] = config.cookie.trim()
            prefs[KEY_PIN] = config.pin.ifBlank { generatePin() }
            prefs[KEY_WEB_PORT] = config.webPort
            prefs[KEY_RECENT_BLOGGERS] = encodeRecentBloggers(recent)
            // 清理旧版仅 UID 的键，避免双份数据
            prefs.remove(KEY_RECENT_UIDS_LEGACY)
        }
    }

    // updateUid 更新博主 UID，可选写入展示名称并记入最近列表。
    suspend fun updateUid(uid: String, displayName: String = "") {
        val current = getConfig()
        val trimmed = uid.trim()
        val name = displayName.trim().ifBlank {
            current.recentBloggers.firstOrNull { it.uid == trimmed }?.name.orEmpty()
        }
        val recent = prependRecentBlogger(
            current.recentBloggers,
            RecentBlogger(uid = trimmed, name = name),
        )
        saveConfig(current.copy(uid = trimmed, recentBloggers = recent))
    }

    // updateRecentBloggerName 更新最近列表中某 UID 的博主名称。
    suspend fun updateRecentBloggerName(uid: String, displayName: String) {
        val trimmedUid = uid.trim()
        val trimmedName = displayName.trim()
        if (trimmedUid.isBlank() || trimmedName.isBlank()) return
        val current = getConfig()
        val recent = prependRecentBlogger(
            current.recentBloggers,
            RecentBlogger(uid = trimmedUid, name = trimmedName),
        )
        // 若当前 UID 就是该博主，同步写回；否则只更新最近列表
        val next = if (current.uid == trimmedUid) {
            current.copy(recentBloggers = recent)
        } else {
            current.copy(recentBloggers = recent)
        }
        dataStore.edit { prefs ->
            prefs[KEY_RECENT_BLOGGERS] = encodeRecentBloggers(next.recentBloggers)
        }
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

    // prependRecentBlogger 将博主插到最近列表最前，同 UID 去重并截断。
    private fun prependRecentBlogger(
        existing: List<RecentBlogger>,
        blogger: RecentBlogger,
    ): List<RecentBlogger> {
        val trimmedUid = blogger.uid.trim()
        if (trimmedUid.isBlank()) return existing.take(AppConfig.MAX_RECENT_UIDS)
        val mergedName = blogger.name.ifBlank {
            existing.firstOrNull { it.uid == trimmedUid }?.name.orEmpty()
        }
        val head = RecentBlogger(uid = trimmedUid, name = mergedName)
        return (listOf(head) + existing.filter { it.uid != trimmedUid })
            .take(AppConfig.MAX_RECENT_UIDS)
    }

    // parseRecentBloggers 解析最近博主列表，兼容旧版纯 UID 逗号串。
    private fun parseRecentBloggers(raw: String): List<RecentBlogger> {
        if (raw.isBlank()) return emptyList()
        if (raw.trimStart().startsWith("[")) {
            return try {
                recentAdapter.fromJson(raw).orEmpty()
                    .map { it.copy(uid = it.uid.trim(), name = it.name.trim()) }
                    .filter { it.uid.isNotEmpty() }
                    .distinctBy { it.uid }
                    .take(AppConfig.MAX_RECENT_UIDS)
            } catch (_: Exception) {
                emptyList()
            }
        }
        // 旧格式：uid1,uid2,uid3
        return raw.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(AppConfig.MAX_RECENT_UIDS)
            .map { RecentBlogger(uid = it, name = "") }
    }

    // encodeRecentBloggers 将最近博主列表编码为 JSON。
    private fun encodeRecentBloggers(list: List<RecentBlogger>): String {
        return recentAdapter.toJson(list.take(AppConfig.MAX_RECENT_UIDS))
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
        private val KEY_RECENT_BLOGGERS = stringPreferencesKey("recent_bloggers")
        private val KEY_RECENT_UIDS_LEGACY = stringPreferencesKey("recent_uids")
    }
}
