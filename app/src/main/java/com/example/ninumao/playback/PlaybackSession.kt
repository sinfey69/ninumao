package com.example.ninumao.playback

import com.example.ninumao.data.weibo.PageCursor
import com.example.ninumao.model.VideoItem

// PlaybackSession 在 Activity 间传递播放列表，避免 Intent 过大导致 TV 端丢数据。
object PlaybackSession {

    // SessionData 封装播放页启动所需上下文（列表、索引、分页状态）。
    data class SessionData(
        val playlist: List<VideoItem>,
        val startIndex: Int,
        val nextCursor: PageCursor?,
        val hasMore: Boolean,
    )

    private var playlist: List<VideoItem> = emptyList()
    private var startIndex: Int = 0
    private var nextCursor: PageCursor? = null
    private var hasMore: Boolean = false

    // prepare 写入待播放列表、起始索引与下一页游标。
    fun prepare(videos: List<VideoItem>, index: Int, nextCursor: PageCursor?, hasMore: Boolean) {
        playlist = videos
        startIndex = index.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
        this.nextCursor = nextCursor
        this.hasMore = hasMore
    }

    // consume 读取并清空会话，防止下次误用旧数据。
    fun consume(): SessionData {
        val videos = playlist
        val index = startIndex
        val cursor = nextCursor
        val canLoadMore = hasMore
        playlist = emptyList()
        startIndex = 0
        nextCursor = null
        hasMore = false
        return SessionData(
            playlist = videos,
            startIndex = index,
            nextCursor = cursor,
            hasMore = canLoadMore,
        )
    }
}
