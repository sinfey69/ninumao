package com.example.ninumao.playback

import com.example.ninumao.model.VideoItem

// PlaybackSession 在 Activity 间传递播放列表，避免 Intent 过大导致 TV 端丢数据。
object PlaybackSession {

    // SessionData 封装播放页启动所需上下文（列表、索引、分页状态）。
    data class SessionData(
        val playlist: List<VideoItem>,
        val startIndex: Int,
        val nextSinceId: Long?,
        val hasMore: Boolean,
    )

    private var playlist: List<VideoItem> = emptyList()
    private var startIndex: Int = 0
    private var nextSinceId: Long? = null
    private var hasMore: Boolean = false

    // prepare 写入待播放列表、起始索引与下一页游标。
    fun prepare(videos: List<VideoItem>, index: Int, nextSinceId: Long?, hasMore: Boolean) {
        playlist = videos
        startIndex = index.coerceIn(0, (videos.size - 1).coerceAtLeast(0))
        this.nextSinceId = nextSinceId
        this.hasMore = hasMore
    }

    // consume 读取并清空会话，防止下次误用旧数据。
    fun consume(): SessionData {
        val videos = playlist
        val index = startIndex
        val cursor = nextSinceId
        val canLoadMore = hasMore
        playlist = emptyList()
        startIndex = 0
        nextSinceId = null
        hasMore = false
        return SessionData(
            playlist = videos,
            startIndex = index,
            nextSinceId = cursor,
            hasMore = canLoadMore,
        )
    }
}
