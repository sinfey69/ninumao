package com.example.ninumao.data.weibo

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// QrLoginSession 一次扫码登录会话。
data class QrLoginSession(
    val qrid: String,
    val imageUrl: String,
)

// QrCheckResult 扫码状态轮询结果。
sealed class QrCheckResult {
    object Waiting : QrCheckResult()
    object Scanned : QrCheckResult()
    object Expired : QrCheckResult()
    data class Confirmed(val alt: String) : QrCheckResult()
    data class Failed(val message: String) : QrCheckResult()
}

// MemoryCookieJar 在扫码登录过程中保存各域 Cookie。
class MemoryCookieJar : CookieJar {
    private val store = mutableListOf<Cookie>()

    // saveFromResponse 按名称+域覆盖旧 Cookie。
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        synchronized(store) {
            cookies.forEach { incoming ->
                store.removeAll { it.name == incoming.name && it.domain == incoming.domain }
                store.add(incoming)
            }
        }
    }

    // loadForRequest 返回匹配当前 URL 的 Cookie。
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        synchronized(store) {
            return store.filter { it.matches(url) }
        }
    }

    // toHeaderString 只保留微博接口需要的 Cookie，优先 weibo.cn / weibo.com。
    fun toHeaderString(): String {
        synchronized(store) {
            val preferredDomains = listOf("weibo.cn", "weibo.com", "sina.com.cn")
            return KEEP_COOKIE_NAMES.mapNotNull { name ->
                val candidates = store.filter { it.name == name && it.value.isNotBlank() }
                val picked = preferredDomains.firstNotNullOfOrNull { domain ->
                    candidates.firstOrNull { it.domain.contains(domain) }
                } ?: candidates.firstOrNull()
                picked?.let { "${it.name}=${it.value}" }
            }.joinToString("; ")
        }
    }

    companion object {
        // KEEP_COOKIE_NAMES 写入配置并回传给微博接口的 Cookie 名。
        val KEEP_COOKIE_NAMES = setOf(
            "SUB", "SUBP", "ALF", "SSOLoginState", "MLOGIN",
            "_T_WM", "XSRF-TOKEN", "WBPSESS", "SCF", "SUHB",
        )
    }
}

// WeiboQrLoginClient 走新浪 SSO 扫码登录，换取可访问微博接口的 Cookie。
class WeiboQrLoginClient {

    private val cookieJar = MemoryCookieJar()
    private val client = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // createSession 申请二维码 qrid 与图片地址。
    suspend fun createSession(): QrLoginSession = withContext(Dispatchers.IO) {
        val callback = callbackName()
        val url = "$QR_IMAGE?entry=weibo&size=180&callback=$callback"
        val body = getText(url)
        val data = parseJsonp(body).optJSONObject("data")
            ?: throw IllegalStateException("二维码接口无 data")
        val qrid = data.optString("qrid").trim()
        val image = data.optString("image").trim()
        if (qrid.isBlank() || image.isBlank()) {
            throw IllegalStateException("二维码接口未返回 qrid")
        }
        QrLoginSession(qrid = qrid, imageUrl = normalizeUrl(image))
    }

    // downloadQrImage 下载微博返回的二维码图片。
    suspend fun downloadQrImage(imageUrl: String): Bitmap? = withContext(Dispatchers.IO) {
        val bytes = getBytes(imageUrl)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // check 查询二维码扫描/确认状态。
    suspend fun check(qrid: String): QrCheckResult = withContext(Dispatchers.IO) {
        val callback = callbackName()
        val url = "$QR_CHECK?entry=weibo&qrid=$qrid&callback=$callback"
        val body = getText(url)
        val json = parseJsonp(body)
        val ret = json.opt("retcode")?.toString().orEmpty()
        when (ret) {
            RET_WAITING, "50101" -> QrCheckResult.Waiting
            RET_SCANNED, "50102" -> QrCheckResult.Scanned
            RET_EXPIRED, RET_TIMEOUT, "50103", "50104" -> QrCheckResult.Expired
            RET_OK -> {
                val alt = json.optJSONObject("data")?.optString("alt").orEmpty()
                if (alt.isBlank()) {
                    QrCheckResult.Failed("确认成功但未返回登录票据")
                } else {
                    QrCheckResult.Confirmed(alt)
                }
            }
            else -> QrCheckResult.Failed("扫码状态异常 retcode=$ret")
        }
    }

    // exchangeCookie 用确认票据换取 Cookie 字符串。
    suspend fun exchangeCookie(alt: String): String = withContext(Dispatchers.IO) {
        val callback = callbackName()
        val loginUrl = buildString {
            append(SSO_LOGIN)
            append("?entry=weibo&returntype=TEXT&crossdomain=1&cdult=3")
            append("&domain=weibo.com&savestate=30&alt=")
            append(java.net.URLEncoder.encode(alt, "UTF-8"))
            append("&callback=").append(callback)
        }
        val body = getText(loginUrl)
        val json = parseJsonp(body)
        val urls = json.optJSONArray("crossDomainUrlList")
        if (urls != null) {
            for (i in 0 until urls.length()) {
                val cross = urls.optString(i)
                if (cross.isNotBlank()) {
                    runCatching { getText(cross) }
                }
            }
        }
        runCatching { getText("https://m.weibo.cn/", userAgent = MOBILE_UA) }
        val cookie = sanitizeCookie(cookieJar.toHeaderString())
        if (!cookie.contains("SUB=")) {
            throw IllegalStateException("登录成功但未拿到 SUB Cookie")
        }
        cookie
    }

    // getText 发起 GET 并返回文本。
    private fun getText(url: String, userAgent: String = USER_AGENT): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "*/*")
            .header("Referer", "https://weibo.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.string().orEmpty()
        }
    }

    // getBytes 发起 GET 并返回二进制。
    private fun getBytes(url: String): ByteArray {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "image/*")
            .header("Referer", "https://weibo.com/")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            return response.body?.bytes() ?: ByteArray(0)
        }
    }

    // parseJsonp 去掉 STK_xxx(...) 包装并解析 JSON。
    private fun parseJsonp(raw: String): JSONObject {
        val start = raw.indexOf('(')
        val end = raw.lastIndexOf(')')
        val json = if (start >= 0 && end > start) {
            raw.substring(start + 1, end)
        } else {
            raw.trim()
        }
        return JSONObject(json)
    }

    // normalizeUrl 补全二维码图片协议。
    private fun normalizeUrl(url: String): String {
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> "https://$url"
        }
    }

    // callbackName 生成 SSO JSONP 回调名。
    private fun callbackName(): String = "STK_${System.currentTimeMillis()}"

    companion object {
        private const val QR_IMAGE = "https://login.sina.com.cn/sso/qrcode/image"
        private const val QR_CHECK = "https://login.sina.com.cn/sso/qrcode/check"
        private const val SSO_LOGIN = "https://login.sina.com.cn/sso/login.php"
        private const val RET_WAITING = "50114001"
        private const val RET_SCANNED = "50114002"
        private const val RET_TIMEOUT = "50114003"
        private const val RET_EXPIRED = "50114004"
        private const val RET_OK = "20000000"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        // sanitizeCookie 去掉扫码过程中多余的护照域 Cookie，避免触发 432。
        fun sanitizeCookie(raw: String): String {
            if (raw.isBlank()) return ""
            val kept = linkedMapOf<String, String>()
            raw.split(';').forEach { part ->
                val item = part.trim()
                if (!item.contains('=')) return@forEach
                val name = item.substringBefore('=').trim()
                val value = item.substringAfter('=').trim()
                if (name in MemoryCookieJar.KEEP_COOKIE_NAMES && value.isNotBlank()) {
                    kept[name] = value
                }
            }
            return kept.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
    }
}
