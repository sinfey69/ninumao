package com.example.ninumao.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.leanback.widget.OnItemViewSelectedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ninumao.NinumaoApp
import com.example.ninumao.R
import com.example.ninumao.model.VideoItem
import com.example.ninumao.ui.playback.PlaybackActivity
import com.example.ninumao.playback.PlaybackSession
import com.example.ninumao.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

// MainBrowseFragment 展示微博视频列表主界面。
class MainBrowseFragment : BrowseSupportFragment() {

    private lateinit var viewModel: VideoViewModel
    private lateinit var rowsAdapter: ArrayObjectAdapter
    private lateinit var videoRowAdapter: ArrayObjectAdapter
    private lateinit var settingsRowAdapter: ArrayObjectAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as NinumaoApp
        viewModel = ViewModelProvider(
            this,
            VideoViewModelFactory(app.configRepository, app.weiboRepository),
        )[VideoViewModel::class.java]

        setupUi(view)
        observeState()
        observeConfigUpdates()
    }

    // setupUi 初始化 Leanback 标题与点击事件。
    private fun setupUi(rootView: View) {
        title = getString(R.string.browse_title)
        headersState = HEADERS_DISABLED
        brandColor = ContextCompat.getColor(requireContext(), R.color.primary_dark)
        searchAffordanceColor = ContextCompat.getColor(requireContext(), R.color.accent)

        val rowPresenter = ListRowPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM, false)
        rowsAdapter = ArrayObjectAdapter(rowPresenter)
        videoRowAdapter = ArrayObjectAdapter(VideoCardPresenter())
        settingsRowAdapter = ArrayObjectAdapter(SettingsCardPresenter())
        adapter = rowsAdapter

        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is SettingsCardItem -> openSettings()
                is VideoItem -> {
                    val state = viewModel.uiState.value
                    val videos = state.videos
                    val index = videos.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                    PlaybackSession.prepare(
                        videos = videos,
                        index = index,
                        nextCursor = state.nextCursor,
                        hasMore = state.hasMore,
                    )
                    startActivity(PlaybackActivity.createIntent(requireContext(), index))
                }
            }
        }

        onItemViewSelectedListener = OnItemViewSelectedListener { _, item, _, _ ->
            val videos = viewModel.uiState.value.videos
            if (videos.isEmpty()) {
                return@OnItemViewSelectedListener
            }
            val index = videos.indexOf(item as? VideoItem)
            if (index >= 0 && index >= videos.size - 3) {
                viewModel.loadMore()
            }
        }

        setOnSearchClickedListener { openSettings() }

        renderContent(emptyList(), null)
        focusSettingsRow(rootView)
    }

    // focusSettingsRow 启动时将焦点移到「打开设置」卡片。
    private fun focusSettingsRow(rootView: View) {
        rootView.post {
            setSelectedPosition(0, false)
        }
    }

    // openSettings 打开设置页。
    private fun openSettings() {
        val activity = activity
        if (activity is MainActivity) {
            activity.openSettings()
        } else {
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
        }
    }

    // observeState 订阅列表状态并刷新 UI。
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderContent(state.videos, state.errorMessage)
                    if (state.errorMessage != null && state.videos.isEmpty()) {
                        Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // observeConfigUpdates 在配置变更后刷新列表。
    private fun observeConfigUpdates() {
        val app = requireActivity().application as NinumaoApp
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.configUpdatedEvents.collect {
                    viewModel.refresh()
                }
            }
        }
    }

    // renderContent 渲染设置入口与视频列表。
    private fun renderContent(videos: List<VideoItem>, errorMessage: String?) {
        rowsAdapter.clear()
        videoRowAdapter.clear()
        settingsRowAdapter.clear()

        val settingsSubtitle = when {
            errorMessage != null -> errorMessage
            else -> getString(R.string.browse_search_hint)
        }
        if (videos.isEmpty()) {
            return
        }
        videos.forEach(videoRowAdapter::add)
        rowsAdapter.add(ListRow(HeaderItem(1, getString(R.string.video_row_title)), videoRowAdapter))
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.uiState.value.videos.isEmpty()) {
            viewModel.refresh()
        }
        view?.let { focusSettingsRow(it) }
    }
}
