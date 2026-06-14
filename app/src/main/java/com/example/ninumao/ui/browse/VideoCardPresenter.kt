package com.example.ninumao.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.example.ninumao.R
import com.example.ninumao.model.VideoItem

// VideoCardPresenter 渲染 Leanback 视频卡片。
class VideoCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_card, parent, false)
        val resources = parent.context.resources
        val width = resources.getDimensionPixelSize(R.dimen.video_card_width)
        val height = resources.getDimensionPixelSize(R.dimen.video_card_height)
        view.layoutParams = ViewGroup.LayoutParams(width, height)
        return ViewHolder(view)
    }

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

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val imageView = viewHolder.view.findViewById<ImageView>(R.id.card_image)
        imageView.setImageDrawable(null)
    }
}
