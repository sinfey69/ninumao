package com.example.ninumao.data.weibo

import com.squareup.moshi.Json
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter

@JsonClass(generateAdapter = true)
// ContainerIndexResponse 对应 m.weibo.cn container/getIndex 响应。
data class ContainerIndexResponse(
    val ok: Int = 0,
    val data: ContainerData? = null,
)

@JsonClass(generateAdapter = true)
// ContainerData 包含 tabs 与 cards 列表数据。
data class ContainerData(
    @Json(name = "userInfo") val userInfo: UserInfo? = null,
    @Json(name = "tabsInfo") val tabsInfo: TabsInfo? = null,
    @Json(name = "cardlistInfo") val cardListInfo: CardListInfo? = null,
    val cards: List<Card>? = null,
)

@JsonClass(generateAdapter = true)
// UserInfo 表示博主基本资料。
data class UserInfo(
    val id: Long? = null,
    @Json(name = "screen_name") val screenName: String? = null,
    val name: String? = null,
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

// CardListInfo 提供分页游标，since_id 由 CardListInfoJsonAdapter 容错解析。
data class CardListInfo(
    val sinceId: String? = null,
)

// CardListInfoJsonAdapter 兼容 since_id 为数字或字符串，避免整页解析失败。
class CardListInfoJsonAdapter : JsonAdapter<CardListInfo>() {
    // fromJson 读取 cardlistInfo，未知字段直接跳过。
    override fun fromJson(reader: JsonReader): CardListInfo? {
        if (reader.peek() == JsonReader.Token.NULL) {
            return reader.nextNull()
        }
        reader.beginObject()
        var sinceId: String? = null
        while (reader.hasNext()) {
            if (reader.nextName() == "since_id") {
                sinceId = readSinceId(reader)
            } else {
                reader.skipValue()
            }
        }
        reader.endObject()
        return CardListInfo(sinceId = sinceId)
    }

    // toJson 仅写出 since_id。
    override fun toJson(writer: JsonWriter, value: CardListInfo?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("since_id")
        writer.value(value.sinceId)
        writer.endObject()
    }

    // readSinceId 把数字或字符串 since_id 转成可请求的游标，0 视为无效。
    private fun readSinceId(reader: JsonReader): String? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            JsonReader.Token.STRING -> reader.nextString()?.trim()?.takeIf { it.isNotEmpty() && it != "0" }
            JsonReader.Token.NUMBER -> reader.nextLong().takeIf { it != 0L }?.toString()
            else -> {
                reader.skipValue()
                null
            }
        }
    }
}

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
    @Json(name = "text_raw") val textRaw: String? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "page_info") val pageInfo: PageInfo? = null,
    val user: UserInfo? = null,
)

// PagePic 封装封面图，兼容对象或纯 URL 字符串。
data class PagePic(
    val url: String? = null,
)

// PagePicJsonAdapter 兼容 page_pic 为对象或字符串。
class PagePicJsonAdapter : JsonAdapter<PagePic>() {
    // fromJson 读取封面地址。
    override fun fromJson(reader: JsonReader): PagePic? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> reader.nextNull()
            JsonReader.Token.STRING -> PagePic(url = reader.nextString())
            JsonReader.Token.BEGIN_OBJECT -> {
                reader.beginObject()
                var url: String? = null
                while (reader.hasNext()) {
                    if (reader.nextName() == "url") {
                        url = reader.nextString()
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                PagePic(url = url)
            }
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    // toJson 按对象写出封面。
    override fun toJson(writer: JsonWriter, value: PagePic?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        writer.beginObject()
        writer.name("url")
        writer.value(value.url)
        writer.endObject()
    }
}

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
    @Json(name = "playback_list") val playbackList: List<PlaybackItem>? = null,
)

@JsonClass(generateAdapter = true)
// PlaybackItem 桌面瀑布流中的单个清晰度条目。
data class PlaybackItem(
    @Json(name = "play_info") val playInfo: PlayInfo? = null,
)

@JsonClass(generateAdapter = true)
// PlayInfo 包含可播放地址。
data class PlayInfo(
    val url: String? = null,
)

@JsonClass(generateAdapter = true)
// WaterFallResponse 对应 weibo.com 视频瀑布流响应。
data class WaterFallResponse(
    val ok: Int = 0,
    val data: WaterFallData? = null,
)

@JsonClass(generateAdapter = true)
// WaterFallData 包含视频列表与下一页 cursor。
data class WaterFallData(
    val list: List<Mblog>? = null,
    @Json(name = "next_cursor") val nextCursor: FlexibleCursor? = null,
)

// FlexibleCursor 兼容数字或字符串游标。
data class FlexibleCursor(val raw: String)

// FlexibleCursorJsonAdapter 读取数字 / 字符串 cursor。
class FlexibleCursorJsonAdapter : JsonAdapter<FlexibleCursor>() {
    // fromJson 把数字或字符串读成游标。
    override fun fromJson(reader: JsonReader): FlexibleCursor? {
        return when (reader.peek()) {
            JsonReader.Token.NULL -> reader.nextNull()
            JsonReader.Token.STRING -> reader.nextString()?.let { FlexibleCursor(it) }
            JsonReader.Token.NUMBER -> FlexibleCursor(reader.nextLong().toString())
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    // toJson 按字符串写出游标。
    override fun toJson(writer: JsonWriter, value: FlexibleCursor?) {
        if (value == null) {
            writer.nullValue()
        } else {
            writer.value(value.raw)
        }
    }
}
