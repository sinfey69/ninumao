package com.example.ninumao.data.config

// RecentBlogger 表示最近使用过的博主，便于按名称一键切换。
data class RecentBlogger(
    val uid: String,
    val name: String = "",
) {
    // displayName 优先展示博主名称，名称为空时回退 UID。
    val displayName: String
        get() = name.ifBlank { uid }
}
