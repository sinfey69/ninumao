package com.example.ninumao.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.leanback.widget.Presenter
import coil.load
import com.example.ninumao.R
import com.example.ninumao.model.VideoItem

// VideoGridCardPresenter 渲染网格模式下的视频卡片，封面图优先展示。
// cardWidthPx/cardHeightPx 由 Fragment 根据实际网格宽度动态计算后传入。
class VideoGridCardPresenter(
    private val cardWidthPx: Int,
    private val cardHeightPx: Int,
) : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_video_grid_card, parent, false)
        view.clipToOutline = true
        if (cardWidthPx > 0 && cardHeightPx > 0) {
            view.layoutParams = ViewGroup.LayoutParams(cardWidthPx, cardHeightPx)
        }
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val video = item as VideoItem
        val root = viewHolder.view
        root.findViewById<ImageView>(R.id.card_image).load(video.coverUrl) {
            crossfade(true)
            placeholder(R.drawable.app_banner)
            error(R.drawable.app_banner)
        }
        root.findViewById<TextView>(R.id.card_title).text = video.title
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        viewHolder.view.findViewById<ImageView>(R.id.card_image).setImageDrawable(null)
    }
}
