package com.example.ninumao.ui.browse

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.leanback.widget.Presenter
import com.example.ninumao.R

// SettingsCardItem 表示主界面上的设置入口卡片。
data class SettingsCardItem(
    val title: String,
    val subtitle: String,
)

// SettingsCardPresenter 渲染设置入口卡片。
class SettingsCardPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_settings_card, parent, false)
        val resources = parent.context.resources
        val width = resources.getDimensionPixelSize(R.dimen.settings_card_width)
        val height = resources.getDimensionPixelSize(R.dimen.settings_card_height)
        view.layoutParams = ViewGroup.LayoutParams(width, height)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any?) {
        val card = item as SettingsCardItem
        val root = viewHolder.view
        root.findViewById<TextView>(R.id.settings_card_title).text = card.title
        root.findViewById<TextView>(R.id.settings_card_subtitle).text = card.subtitle
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) = Unit
}
