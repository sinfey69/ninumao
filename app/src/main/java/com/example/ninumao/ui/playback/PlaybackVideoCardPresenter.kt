package com.example.ninumao.ui.playback

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.example.ninumao.R
import com.example.ninumao.model.VideoItem

// PlaybackVideoCardPresenter 渲染播放页底部列表的视频卡片。
class PlaybackVideoCardPresenter : Presenter() {

    // onCreateViewHolder 创建播放页底部视频卡片视图。
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_playback_video_card, parent, false)
        view.clipToOutline = true
        val resources = parent.context.resources
        val width = resources.getDimensionPixelSize(R.dimen.video_card_width)
        val height = resources.getDimensionPixelSize(R.dimen.video_card_height)
        view.layoutParams = ViewGroup.LayoutParams(width, height)
        return ViewHolder(view)
    }

    // onBindViewHolder 绑定视频封面与标题到卡片。
    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as VideoItem
        val root = viewHolder.view
        val imageView = root.findViewById<ImageView>(R.id.card_image)
        val titleView = root.findViewById<TextView>(R.id.card_title)

        titleView.text = buildString {
            append(video.title)
            if (!video.createdAt.isNullOrBlank()) {
                append("\n")
                append(video.createdAt)
            }
        }

        imageView.load(video.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.app_banner)
            error(R.drawable.app_banner)
        }
    }

    // onUnbindViewHolder 释放卡片封面资源。
    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val imageView = viewHolder.view.findViewById<ImageView>(R.id.card_image)
        imageView.setImageDrawable(null)
    }
}
