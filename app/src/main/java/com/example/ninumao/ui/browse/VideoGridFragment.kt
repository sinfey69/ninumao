package com.example.ninumao.ui.browse

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.leanback.app.VerticalGridSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.FocusHighlight
import androidx.leanback.widget.VerticalGridPresenter
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.ninumao.NinumaoApp
import com.example.ninumao.playback.PlaybackSession
import com.example.ninumao.R
import com.example.ninumao.model.VideoItem
import com.example.ninumao.ui.playback.PlaybackActivity
import com.example.ninumao.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

// VideoGridFragment 以多列网格形式展示视频列表，支持 D-pad 导航。
class VideoGridFragment : VerticalGridSupportFragment() {

    private lateinit var viewModel: VideoViewModel
    private lateinit var gridAdapter: ArrayObjectAdapter

    // playbackLauncher 接收播放页退出时回传的最后播放索引，恢复光标位置。
    private val playbackLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val lastIndex = result.data?.getIntExtra(PlaybackActivity.RESULT_LAST_INDEX, -1) ?: -1
        if (lastIndex >= 0) {
            // post 保证在 adapter 数据刷新完成后再定位光标
            view?.post { setSelectedPosition(lastIndex) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val gp = VerticalGridPresenter(FocusHighlight.ZOOM_FACTOR_MEDIUM, false)
        gp.numberOfColumns = NUM_COLUMNS
        gridPresenter = gp
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val app = requireActivity().application as NinumaoApp
        viewModel = ViewModelProvider(
            this,
            VideoViewModelFactory(app.configRepository, app.weiboRepository),
        )[VideoViewModel::class.java]

        // 先用临时 presenter 挂上 adapter，等布局完成后换成精确尺寸的 presenter
        gridAdapter = ArrayObjectAdapter(VideoGridCardPresenter(0, 0))
        adapter = gridAdapter
        title = getString(R.string.browse_title)

        // 等根视图布局完成后，用实际宽度重建 presenter
        view.post { rebuildPresenterWithActualWidth(view) }

        setupClickListeners()
        observeState()
        observeConfigUpdates()
    }

    // rebuildPresenterWithActualWidth 用根视图真实宽度计算卡片尺寸，避免超出边界。
    private fun rebuildPresenterWithActualWidth(rootView: View) {
        if (!isAdded) return  // Fragment 已 detach，跳过
        val dm = requireContext().resources.displayMetrics
        val rootWidth = rootView.width.takeIf { it > 0 } ?: dm.widthPixels
        // Leanback 内部约扣 48dp start，保守预留 10%
        val availableWidth = (rootWidth * 0.90).toInt()
        val gap = (dm.density * 4).toInt()
        val cardWidth = availableWidth / NUM_COLUMNS - gap
        val cardHeight = cardWidth * 9 / 16
        val savedVideos = viewModel.uiState.value.videos

        gridAdapter = ArrayObjectAdapter(VideoGridCardPresenter(cardWidth, cardHeight))
        adapter = gridAdapter
        savedVideos.forEach(gridAdapter::add)
    }

    // setupClickListeners 绑定点击和选中事件。
    private fun setupClickListeners() {
        setOnItemViewClickedListener { _, item, _, _ ->
            if (item is VideoItem) {
                val state = viewModel.uiState.value
                val videos = state.videos
                // 优先用 adapter 位置，避免 TV 端 indexOf 因对象引用不同返回 -1 导致总是播第一个
                val adapterIndex = gridAdapter.indexOf(item)
                val index = when {
                    adapterIndex >= 0 -> adapterIndex
                    else -> videos.indexOfFirst { it.id == item.id }.coerceAtLeast(0)
                }
                PlaybackSession.prepare(
                    videos = videos,
                    index = index,
                    nextSinceId = state.nextSinceId,
                    hasMore = state.hasMore,
                )
                playbackLauncher.launch(PlaybackActivity.createIntent(requireContext(), index))
            }
        }

        setOnItemViewSelectedListener { _, item, _, _ ->
            val videos = viewModel.uiState.value.videos
            if (videos.isEmpty()) return@setOnItemViewSelectedListener
            val index = videos.indexOf(item as? VideoItem)
            if (index >= 0 && index >= videos.size - NUM_COLUMNS * 2) {
                viewModel.loadMore()
            }
        }
    }

    // observeState 订阅列表状态并更新网格。
    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    renderVideos(state.videos)
                    if (state.errorMessage != null && state.videos.isEmpty()) {
                        Toast.makeText(requireContext(), state.errorMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // observeConfigUpdates 配置变更后刷新列表。
    private fun observeConfigUpdates() {
        val app = requireActivity().application as NinumaoApp
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.configUpdatedEvents.collect { viewModel.refresh() }
            }
        }
    }

    // renderVideos 刷新网格数据：重置时全量替换，追加时仅添加差量以保持光标位置。
    private fun renderVideos(videos: List<VideoItem>) {
        val currentSize = gridAdapter.size()
        when {
            currentSize == 0 || videos.size < currentSize -> {
                gridAdapter.clear()
                videos.forEach(gridAdapter::add)
            }
            videos.size > currentSize -> {
                videos.drop(currentSize).forEach(gridAdapter::add)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.uiState.value.videos.isEmpty()) {
            viewModel.refresh()
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

    companion object {
        private const val NUM_COLUMNS = 3
    }
}
