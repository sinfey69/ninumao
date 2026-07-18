package com.example.ninumao.data.weibo

import com.example.ninumao.BuildConfig
import com.example.ninumao.data.config.AppConfig
import com.example.ninumao.model.VideoItem
import com.example.ninumao.util.DebugLogger
import com.example.ninumao.util.TextUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

// VideoPageResult 表示一页视频列表及下一页游标。
data class VideoPageResult(
    val videos: List<VideoItem>,
    val nextSinceId: Long?,
)

// WeiboRepository 负责解析微博视频列表与播放地址。
class WeiboRepository {

    private var cachedVideoContainerId: String? = null
    private var cachedUid: String? = null

    // fetchVideoPage 拉取指定 UID 的一页视频。
    suspend fun fetchVideoPage(config: AppConfig, sinceId: Long? = null): VideoPageResult {
        val uid = config.uid.trim()
        require(uid.isNotBlank()) { "UID 未配置" }

        DebugLogger.log("Weibo", "开始请求 uid=$uid sinceId=$sinceId cookie=${if (config.cookie.isBlank()) "无" else "已设置(${config.cookie.length}字符)"}")

        val api = createApi(config, uid)
        if (cachedUid != uid) {
            cachedUid = uid
            cachedVideoContainerId = null
            lastResolvedBloggerName = null
        }

        val containerId = cachedVideoContainerId ?: resolveVideoContainerId(api, uid).also {
            cachedVideoContainerId = it
            DebugLogger.log("Weibo", "解析到 videoContainerId=$it")
        }

        DebugLogger.log("Weibo", "请求视频列表 containerId=$containerId")
        val response = api.getContainerIndex(
            uid = uid,
            containerId = containerId,
            sinceId = sinceId,
        )
        DebugLogger.log("Weibo", "响应 ok=${response.ok} cards=${response.data?.cards?.size} sinceId=${response.data?.cardListInfo?.sinceId}")

        if (response.ok != 1) {
            val msg = "微博接口返回异常 ok=${response.ok}"
            DebugLogger.log("Weibo", "ERROR: $msg")
            throw IllegalStateException(msg)
        }

        val cards = response.data?.cards.orEmpty()
        val videos = extractVideos(cards)
        DebugLogger.log("Weibo", "解析到视频 ${videos.size} 条，总 cards=${cards.size}")
        if (videos.isEmpty() && cards.isNotEmpty()) {
            DebugLogger.log("Weibo", "cards 非空但无视频，card_types=${cards.map { it.cardType }}")
        }
        val nextSinceId = response.data?.cardListInfo?.sinceId
        return VideoPageResult(videos = videos, nextSinceId = nextSinceId)
    }

    // fetchBloggerName 根据 UID 拉取博主昵称，失败时返回 null。
    suspend fun fetchBloggerName(config: AppConfig, uid: String): String? {
        val trimmed = uid.trim()
        if (trimmed.isBlank()) return null
        return try {
            val api = createApi(config, trimmed)
            val initResponse = api.getContainerIndex(uid = trimmed)
            val info = initResponse.data?.userInfo
            val name = info?.screenName?.trim().orEmpty().ifBlank {
                info?.name?.trim().orEmpty()
            }
            name.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            DebugLogger.log("Weibo", "拉取博主名称失败 uid=$trimmed: ${e.message}")
            null
        }
    }

    // resolveVideoContainerId 从 init 响应解析视频 Tab 的 containerid。
    private suspend fun resolveVideoContainerId(api: WeiboApi, uid: String): String {
        DebugLogger.log("Weibo", "请求 init 接口 uid=$uid")
        val initResponse = api.getContainerIndex(uid = uid)
        DebugLogger.log("Weibo", "init 响应 ok=${initResponse.ok} tabs=${initResponse.data?.tabsInfo?.tabs?.map { "${it.tabKey}(${it.containerid})" }}")
        val tabs = initResponse.data?.tabsInfo?.tabs.orEmpty()
        val videoTab = tabs.firstOrNull { tab ->
            tab.tabKey == "original_video" || tab.title?.contains("视频") == true
        }
        val containerId = videoTab?.containerid ?: "107603$uid"
        DebugLogger.log("Weibo", "选择 containerId=$containerId (tab=${videoTab?.tabKey ?: "fallback"})")
        // 顺带缓存博主名到最近列表（由调用方在拿到名称后写入配置）
        lastResolvedBloggerName = initResponse.data?.userInfo?.screenName?.trim()
            ?.ifBlank { initResponse.data?.userInfo?.name?.trim() }
            ?.takeIf { !it.isNullOrBlank() }
        return containerId
    }

    // lastResolvedBloggerName 最近一次 init 解析到的博主昵称。
    @Volatile
    var lastResolvedBloggerName: String? = null
        private set

    // extractVideos 从 cards 递归提取视频条目。
    private fun extractVideos(cards: List<Card>): List<VideoItem> {
        val result = mutableListOf<VideoItem>()
        cards.forEach { card ->
            card.mblog?.let { mblog ->
                mapVideoItem(mblog)?.let(result::add)
            }
            card.cardGroup?.let { group ->
                result.addAll(extractVideos(group))
            }
        }
        return result.distinctBy { it.id }
    }

    // mapVideoItem 将 mblog 映射为 VideoItem。
    private fun mapVideoItem(mblog: Mblog): VideoItem? {
        val pageInfo = mblog.pageInfo ?: return null
        if (pageInfo.type != "video" && pageInfo.mediaInfo == null) {
            return null
        }
        val streamUrl = resolveStreamUrl(pageInfo) ?: return null
        val id = mblog.id ?: streamUrl
        return VideoItem(
            id = id,
            title = TextUtils.stripHtml(mblog.text),
            coverUrl = normalizeUrl(pageInfo.pagePic?.url),
            streamUrl = streamUrl,
            createdAt = mblog.createdAt,
        )
    }

    // resolveStreamUrl 从 page_info 中提取可播放地址。
    private fun resolveStreamUrl(pageInfo: PageInfo): String? {
        val hd = pageInfo.mediaInfo?.streamUrlHd
        val sd = pageInfo.mediaInfo?.streamUrl
        val fromUrls = pageInfo.urls?.values?.firstOrNull { url ->
            url.contains(".mp4") || url.contains(".m3u8")
        }
        return normalizeUrl(hd) ?: normalizeUrl(sd) ?: normalizeUrl(fromUrls)
    }

    // normalizeUrl 补全协议相对地址。
    private fun normalizeUrl(url: String?): String? {
        if (url.isNullOrBlank()) {
            return null
        }
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            else -> url
        }
    }

    // createApi 按当前配置构建 Retrofit 实例。
    private fun createApi(config: AppConfig, uid: String): WeiboApi {
        val clientBuilder = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(buildHeaderInterceptor(config, uid))
        if (BuildConfig.DEBUG) {
            clientBuilder.addInterceptor(
                HttpLoggingInterceptor { msg ->
                    if (DebugLogger.isEnabled) {
                        DebugLogger.log("HTTP", msg)
                    }
                }.apply {
                    level = HttpLoggingInterceptor.Level.BODY
                },
            )
        }
        val client = clientBuilder.build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WeiboApi::class.java)
    }

    // buildHeaderInterceptor 注入微博移动端请求头。
    private fun buildHeaderInterceptor(config: AppConfig, uid: String): Interceptor {
        return Interceptor { chain ->
            val builder = chain.request().newBuilder()
                .header("User-Agent", USER_AGENT)
                .header("Referer", "https://m.weibo.cn/u/$uid")
                .header("Accept", "application/json, text/plain, */*")
                .header("X-Requested-With", "XMLHttpRequest")
            if (config.cookie.isNotBlank()) {
                builder.header("Cookie", config.cookie)
            }
            chain.proceed(builder.build())
        }
    }

    companion object {
        private const val BASE_URL = "https://m.weibo.cn/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
    }
}
