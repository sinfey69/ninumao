package com.example.ninumao.util

import android.text.Html
import java.util.regex.Pattern

// TextUtils 提供文案清洗工具。
object TextUtils {

    private val TAG_PATTERN = Pattern.compile("<[^>]+>")

    // stripHtml 去除 HTML 标签并压缩空白。
    fun stripHtml(raw: String?): String {
        if (raw.isNullOrBlank()) {
            return "无标题"
        }
        val unescaped = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        return TAG_PATTERN.matcher(unescaped).replaceAll("").trim().ifBlank { "无标题" }
    }
}
