package com.example.ninumao.data.weibo

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
// ContainerIndexResponse 对应 m.weibo.cn container/getIndex 响应。
data class ContainerIndexResponse(
    val ok: Int = 0,
    val data: ContainerData? = null,
)

@JsonClass(generateAdapter = true)
// ContainerData 包含 tabs 与 cards 列表数据。
data class ContainerData(
    @Json(name = "tabsInfo") val tabsInfo: TabsInfo? = null,
    @Json(name = "cardlistInfo") val cardListInfo: CardListInfo? = null,
    val cards: List<Card>? = null,
)

@JsonClass(generateAdapter = true)
// TabsInfo 描述用户主页各 Tab 信息。
data class TabsInfo(
    val tabs: List<Tab>? = null,
)

@JsonClass(generateAdapter = true)
// Tab 表示单个主页 Tab。
data class Tab(
    @Json(name = "tabKey") val tabKey: String? = null,
    val title: String? = null,
    val containerid: String? = null,
)

@JsonClass(generateAdapter = true)
// CardListInfo 提供分页游标。
data class CardListInfo(
    @Json(name = "since_id") val sinceId: Long? = null,
)

@JsonClass(generateAdapter = true)
// Card 为列表卡片，可能嵌套 card_group。
data class Card(
    @Json(name = "card_type") val cardType: Int? = null,
    val mblog: Mblog? = null,
    @Json(name = "card_group") val cardGroup: List<Card>? = null,
)

@JsonClass(generateAdapter = true)
// Mblog 为微博正文对象。
data class Mblog(
    val id: String? = null,
    val text: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "page_info") val pageInfo: PageInfo? = null,
)

@JsonClass(generateAdapter = true)
// PagePic 封装封面图对象，page_pic 字段在 API 中是对象而非字符串。
data class PagePic(
    val url: String? = null,
)

@JsonClass(generateAdapter = true)
// PageInfo 包含视频封面与播放地址。
data class PageInfo(
    val type: String? = null,
    @Json(name = "page_pic") val pagePic: PagePic? = null,
    @Json(name = "media_info") val mediaInfo: MediaInfo? = null,
    val urls: Map<String, String>? = null,
)

@JsonClass(generateAdapter = true)
// MediaInfo 提供视频流地址。
data class MediaInfo(
    @Json(name = "stream_url") val streamUrl: String? = null,
    @Json(name = "stream_url_hd") val streamUrlHd: String? = null,
    @Json(name = "h5_url") val h5Url: String? = null,
)
