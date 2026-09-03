package com.example.ninumao.data.weibo

import com.example.ninumao.data.config.AppConfig
import com.example.ninumao.model.VideoItem
import com.example.ninumao.util.TextUtils
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.TimeUnit

// PageCursor 微博视频列表分页游标。
data class PageCursor(
    val sinceId: String? = null,
    val page: Int? = null,
    val lastVideoId: String? = null,
    val useWaterFall: Boolean = false,
)

// VideoPageResult 表示一页视频列表及下一页游标。
data class VideoPageResult(
    val videos: List<VideoItem>,
    val nextCursor: PageCursor?,
)

// WeiboTabPage 微博列表容器兜底结果。
private data class WeiboTabPage(
    val videos: List<VideoItem>,
    val sinceId: String?,
)

// WeiboRepository 负责解析微博视频列表与播放地址。
class WeiboRepository {

    private var cachedVideoContainerId: String? = null
    private var cachedUid: String? = null

    // fetchVideoPage 拉取指定 UID 的一页视频。
    suspend fun fetchVideoPage(config: AppConfig, cursor: PageCursor? = null): VideoPageResult {
        val uid = config.uid.trim()
        require(uid.isNotBlank()) { "UID 未配置" }

        val api = createApi(config, uid)
        if (cachedUid != uid) {
            cachedUid = uid
            cachedVideoContainerId = null
            lastResolvedBloggerName = null
        }

        if (cursor == null || cursor.useWaterFall) {
            try {
                return fetchWaterFallPage(api, uid, cursor)
            } catch (e: Exception) {
                if (cursor?.useWaterFall == true) {
                    throw e
                }
            }
        }

        val containerId = cachedVideoContainerId ?: resolveVideoContainerId(api, uid).also {
            cachedVideoContainerId = it
        }

        val response = if (cursor == null) {
            api.getContainerIndex(type = "uid", uid = uid, containerId = containerId)
        } else {
            try {
                requestNextVideoPage(api, containerId, cursor)
            } catch (e: Exception) {
                ContainerIndexResponse(ok = 0)
            }
        }
        var rawSinceId = response.data?.cardListInfo?.sinceId

        if (response.ok != 1 && cursor == null) {
            val msg = "微博接口返回异常 ok=${response.ok}"
            throw IllegalStateException(msg)
        }

        val cards = response.data?.cards.orEmpty()
        var videos = extractVideos(cards)
        if (cursor != null && !hasPageProgress(cursor, videos)) {
            val fallback = fetchWeiboTabVideos(api, uid, cursor)
            if (fallback.videos.isNotEmpty()) {
                videos = fallback.videos
                rawSinceId = fallback.sinceId
            }
        }
        return VideoPageResult(videos = videos, nextCursor = resolveNextCursor(cursor, rawSinceId, videos))
    }

    // fetchWaterFallPage 通过桌面视频瀑布流接口拉一页，用 next_cursor 翻页。
    private suspend fun fetchWaterFallPage(
        api: WeiboApi,
        uid: String,
        cursor: PageCursor?,
    ): VideoPageResult {
        val wfCursor = if (cursor == null) "0" else cursor.sinceId ?: "0"
        val response = api.getWaterFallContent(uid = uid, cursor = wfCursor)
        if (response.ok != 1) {
            throw IllegalStateException("瀑布流接口返回异常 ok=${response.ok}")
        }
        val list = response.data?.list.orEmpty()
        if (cursor == null) {
            lastResolvedBloggerName = list.firstOrNull()?.user?.screenName?.trim()
                ?.ifBlank { list.firstOrNull()?.user?.name?.trim() }
                ?.takeIf { !it.isNullOrBlank() }
        }
        val videos = list.mapNotNull { mapVideoItem(it) }.distinctBy { it.id }
        if (cursor == null && videos.isEmpty()) {
            throw IllegalStateException("瀑布流首页无视频")
        }
        val next = response.data?.nextCursor?.raw?.trim()
        val hasMore = !next.isNullOrBlank() && next != "0" && !next.startsWith("-") && videos.isNotEmpty()
        return VideoPageResult(
            videos = videos,
            nextCursor = if (hasMore) {
                PageCursor(
                    sinceId = next,
                    page = (cursor?.page ?: 1) + 1,
                    lastVideoId = videos.last().id,
                    useWaterFall = true,
                )
            } else {
                null
            },
        )
    }

    // requestNextVideoPage 请求视频 Tab 下一页：不带 type=uid，避免被打回首页。
    private suspend fun requestNextVideoPage(
        api: WeiboApi,
        containerId: String,
        cursor: PageCursor,
    ): ContainerIndexResponse {
        val sinceId = cursor.sinceId ?: ((cursor.page ?: 2) - 1).toString()
        return api.getContainerIndex(
            containerId = containerId,
            sinceId = sinceId,
            page = cursor.page,
            pageType = "03",
        )
    }

    // fetchWeiboTabVideos 视频 Tab 翻页失败时，改走微博列表容器并只保留视频。
    private suspend fun fetchWeiboTabVideos(
        api: WeiboApi,
        uid: String,
        cursor: PageCursor,
    ): WeiboTabPage {
        val weiboContainer = "107603$uid"
        val sinceId = cursor.lastVideoId ?: cursor.sinceId?.takeIf { it.length > 8 }
        val response = api.getContainerIndex(
            type = "uid",
            uid = uid,
            containerId = weiboContainer,
            sinceId = sinceId,
        )
        val videos = extractVideos(response.data?.cards.orEmpty())
        return WeiboTabPage(
            videos = videos,
            sinceId = response.data?.cardListInfo?.sinceId ?: sinceId,
        )
    }

    // hasPageProgress 判断本页是否相对上一页产生了新视频。
    private fun hasPageProgress(cursor: PageCursor, videos: List<VideoItem>): Boolean {
        if (videos.isEmpty()) return false
        val lastId = cursor.lastVideoId ?: return true
        return videos.last().id != lastId
    }

    // resolveNextCursor 从本页 since_id 或页码推算下一页游标。
    private fun resolveNextCursor(
        requested: PageCursor?,
        rawSinceId: String?,
        videos: List<VideoItem>,
    ): PageCursor? {
        if (videos.isEmpty()) return null
        val currentPage = requested?.page ?: 1
        val nextPage = currentPage + 1
        return PageCursor(
            sinceId = rawSinceId ?: (nextPage - 1).toString(),
            page = nextPage,
            lastVideoId = videos.last().id,
        )
    }

    // fetchBloggerName 根据 UID 拉取博主昵称，失败时返回 null。
    suspend fun fetchBloggerName(config: AppConfig, uid: String): String? {
        val trimmed = uid.trim()
        if (trimmed.isBlank()) return null
        return try {
            val api = createApi(config, trimmed)
            val initResponse = api.getContainerIndex(type = "uid", uid = trimmed)
            val info = initResponse.data?.userInfo
            val name = info?.screenName?.trim().orEmpty().ifBlank {
                info?.name?.trim().orEmpty()
            }
            name.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    // resolveVideoContainerId 从 init 响应解析视频 Tab 的 containerid。
    private suspend fun resolveVideoContainerId(api: WeiboApi, uid: String): String {
        val initResponse = api.getContainerIndex(type = "uid", uid = uid)
        val tabs = initResponse.data?.tabsInfo?.tabs.orEmpty()
        val videoTab = tabs.firstOrNull { tab ->
            tab.tabKey == "original_video" || tab.title?.contains("视频") == true
        }
        val containerId = videoTab?.containerid ?: "107603$uid"
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
            title = TextUtils.stripHtml(mblog.text ?: mblog.textRaw),
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
        val fromPlayback = pageInfo.mediaInfo?.playbackList
            ?.mapNotNull { it.playInfo?.url }
            ?.lastOrNull { url -> url.contains(".mp4") || url.contains(".m3u8") }
        return normalizeUrl(hd) ?: normalizeUrl(sd) ?: normalizeUrl(fromUrls) ?: normalizeUrl(fromPlayback)
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
        val client = clientBuilder.build()

        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .add(CardListInfo::class.java, CardListInfoJsonAdapter())
            .add(PagePic::class.java, PagePicJsonAdapter())
            .add(FlexibleCursor::class.java, FlexibleCursorJsonAdapter())
            .build()

        return Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(WeiboApi::class.java)
    }

    // buildHeaderInterceptor 按域名注入请求头；Cookie 只带关键字段，432 时去掉 Cookie 重试。
    private fun buildHeaderInterceptor(config: AppConfig, uid: String): Interceptor {
        return Interceptor { chain ->
            val request = chain.request()
            val host = request.url.host
            val isDesktop = host.contains("weibo.com") && !host.contains("m.weibo")
            val referer = if (isDesktop) {
                "https://weibo.com/u/$uid?tabtype=newVideo"
            } else {
                "https://m.weibo.cn/u/$uid"
            }
            val cookie = WeiboQrLoginClient.sanitizeCookie(config.cookie)
            val builder = request.newBuilder()
                .header("User-Agent", if (isDesktop) DESKTOP_UA else USER_AGENT)
                .header("Referer", referer)
                .header("Accept", "application/json, text/plain, */*")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("X-Requested-With", "XMLHttpRequest")
            if (!isDesktop) {
                builder.header("MWeibo-Pwa", "1")
            }
            val xsrf = cookie.substringAfter("XSRF-TOKEN=", "").substringBefore(";").trim()
            if (xsrf.isNotBlank()) {
                builder.header("X-XSRF-TOKEN", xsrf)
            }
            if (cookie.isNotBlank()) {
                builder.header("Cookie", cookie)
            }
            val response = chain.proceed(builder.build())
            if (response.code == 432 && cookie.isNotBlank() && !isDesktop) {
                response.close()
                val retry = request.newBuilder()
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", "https://m.weibo.cn/u/$uid")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .header("MWeibo-Pwa", "1")
                    .removeHeader("Cookie")
                    .removeHeader("X-XSRF-TOKEN")
                    .build()
                return@Interceptor chain.proceed(retry)
            }
            response
        }
    }

    companion object {
        private const val BASE_URL = "https://m.weibo.cn/"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
