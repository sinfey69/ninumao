package com.example.ninumao.ui.playback

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.ItemBridgeAdapter
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.ninumao.NinumaoApp
import com.example.ninumao.data.weibo.PageCursor
import com.example.ninumao.playback.PlaybackSession
import com.example.ninumao.R
import com.example.ninumao.databinding.ActivityPlaybackBinding
import com.example.ninumao.model.VideoItem
import com.example.ninumao.util.DebugLogger
import kotlinx.coroutines.launch

// PlaybackActivity 使用 ExoPlayer 全屏播放微博视频，支持连续播放与两步返回确认退出。
class PlaybackActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlaybackBinding
    private var player: ExoPlayer? = null

    private lateinit var playlist: List<VideoItem>
    private var currentIndex: Int = 0
    private var nextCursor: PageCursor? = null
    private var hasMore: Boolean = false
    private var isPrefetching: Boolean = false
    private var pendingAdvanceAfterPrefetch: Boolean = false
    private var lastBackPressAt: Long = 0L
    private var isPlaylistOverlayVisible: Boolean = false
    private var playlistFocusIndex: Int = 0
    private var lastPlaylistConfirmAt: Long = 0L

    private lateinit var playlistAdapter: ArrayObjectAdapter

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgressText()
            progressHandler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlaybackBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val sessionData = PlaybackSession.consume()
        if (sessionData.playlist.isNotEmpty()) {
            playlist = sessionData.playlist
            currentIndex = sessionData.startIndex
            nextCursor = sessionData.nextCursor
            hasMore = sessionData.hasMore
        } else {
            playlist = readPlaylist()
            currentIndex = intent.getIntExtra(EXTRA_INDEX, 0)
                .coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            nextCursor = null
            hasMore = false
        }

        if (playlist.isEmpty()) {
            finish()
            return
        }

        setupBackHandler()
        setupTitleOverlay()
        setupPlaylistOverlay()
        syncPlaylistAdapter()
        binding.playlistOverlay.post { scrollPlaylistToIndex(currentIndex) }
        initializePlayer()
    }

    // setupBackHandler 兜底拦截系统返回事件（含部分设备不走 KeyEvent 的返回来源）。
    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBackPress()
                }
            },
        )
    }

    // setupTitleOverlay 绑定标题覆盖层，随 PlayerView 控制栏同步显示/隐藏。
    private fun setupTitleOverlay() {
        binding.playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility ->
                binding.titleOverlay.visibility = visibility
                if (visibility == View.VISIBLE) {
                    progressHandler.post(progressRunnable)
                } else {
                    progressHandler.removeCallbacks(progressRunnable)
                }
            },
        )
        updateTitleOverlay()
    }

    // setupPlaylistOverlay 初始化底部横向视频列表，供暂停或下键时选片。
    private fun setupPlaylistOverlay() {
        playlistAdapter = ArrayObjectAdapter(PlaybackVideoCardPresenter())
        val bridgeAdapter = ItemBridgeAdapter(playlistAdapter)
        bridgeAdapter.setAdapterListener(object : ItemBridgeAdapter.AdapterListener() {
            override fun onBind(viewHolder: ItemBridgeAdapter.ViewHolder) {
                viewHolder.itemView.setOnClickListener {
                    val position = viewHolder.adapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        selectVideoAt(position)
                    }
                }
            }
        })
        binding.playlistGrid.apply {
            adapter = bridgeAdapter
            isFocusable = true
            isFocusableInTouchMode = true
            setRowHeight(resources.getDimensionPixelSize(R.dimen.video_card_height))
            setHorizontalSpacing(resources.getDimensionPixelSize(R.dimen.playlist_item_spacing))
            setWindowAlignment(BaseGridView.WINDOW_ALIGN_NO_EDGE)
            setWindowAlignmentOffsetPercent(25f)
            setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView?,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    if (position != RecyclerView.NO_POSITION) {
                        playlistFocusIndex = position
                    }
                    if (position >= playlist.size - PREFETCH_TRIGGER_REMAINING) {
                        maybePrefetchMore(force = false)
                    }
                }
            })
        }
    }

    // initializePlayer 创建带 Referer/Cookie 的播放器，并注册连播监听。
    private fun initializePlayer() {
        val app = application as NinumaoApp
        lifecycleScope.launch {
            val config = app.configRepository.getConfig()
            val referer = if (config.uid.isNotBlank()) "https://m.weibo.cn/u/${config.uid}"
            else "https://m.weibo.cn/"

            val headers = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            if (config.cookie.isNotBlank()) headers["Cookie"] = config.cookie

            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setDefaultRequestProperties(headers)

            player = ExoPlayer.Builder(this@PlaybackActivity)
                .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                .build()
                .also { exoPlayer ->
                    binding.playerView.player = exoPlayer
                    loadVideo(exoPlayer, currentIndex)
                    exoPlayer.addListener(object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            showError(getString(R.string.error_playback) + "：" + error.message)
                        }
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) playNext()
                        }
                    })
                }
        }
    }

    // loadVideo 加载指定索引的视频并开始播放，不自动弹出控制栏。
    private fun loadVideo(exoPlayer: ExoPlayer, index: Int) {
        val video = playlist[index]
        exoPlayer.setMediaItem(MediaItem.fromUri(video.streamUrl))
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        binding.errorText.visibility = View.GONE
        binding.playerView.hideController()
        updateTitleOverlay()
        maybePrefetchMore(force = false)
    }

    // dispatchKeyEvent 拦截遥控器按键，实现无控制栏直控播放。
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.action == KeyEvent.ACTION_UP) handleBackPress()
            return true
        }
        if (isPlaylistOverlayVisible) {
            if (isPlaylistConfirmKey(code)) {
                if (event.action == KeyEvent.ACTION_UP) {
                    confirmPlaylistSelection()
                }
                return true
            }
            if (event.action == KeyEvent.ACTION_DOWN) {
                return dispatchKeyEventForPlaylistOverlay(event)
            }
            if (isPlaylistOverlayKey(code)) return true
            return super.dispatchKeyEvent(event)
        }
        if (isPlaylistConfirmKey(code)) {
            if (event.action == KeyEvent.ACTION_UP) {
                handlePlaybackConfirmKey(code)
            }
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> { playNext(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> { playPrev(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    showPlaylistOverlay()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    seekBy(SEEK_STEP_MS)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_REWIND,
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    seekBy(-SEEK_STEP_MS)
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // dispatchKeyEventForPlaylistOverlay 在底部列表可见时处理选片相关按键。
    private fun dispatchKeyEventForPlaylistOverlay(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                movePlaylistSelection(-1)
                true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                movePlaylistSelection(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                hidePlaylistOverlay()
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> isPlaylistOverlayKey(event.keyCode)
        }
    }

    // isPlaylistConfirmKey 判断是否为确认选片的按键。
    private fun isPlaylistConfirmKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> true
            else -> false
        }
    }

    // handlePlaybackConfirmKey 在列表未显示时响应确认键，仅于 ACTION_UP 触发。
    private fun handlePlaybackConfirmKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> setPlaying(true)
            KeyEvent.KEYCODE_MEDIA_PAUSE -> setPlaying(false)
            else -> togglePlayPause()
        }
    }

    // confirmPlaylistSelection 确认当前焦点项并播放。
    private fun confirmPlaylistSelection() {
        val now = System.currentTimeMillis()
        if (now - lastPlaylistConfirmAt < PLAYLIST_CONFIRM_DEBOUNCE_MS) return
        lastPlaylistConfirmAt = now
        val gridIndex = binding.playlistGrid.selectedPosition
        val index = if (gridIndex in playlist.indices) gridIndex else playlistFocusIndex
        selectVideoAt(index)
    }

    // isPlaylistOverlayKey 判断按键是否应由底部列表层消费，避免传递给 PlayerView。
    private fun isPlaylistOverlayKey(keyCode: Int): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> true
            else -> false
        }
    }

    // movePlaylistSelection 在底部列表中按步长移动选中项。
    private fun movePlaylistSelection(delta: Int) {
        if (playlist.isEmpty()) return
        val next = (playlistFocusIndex + delta).coerceIn(0, playlist.size - 1)
        if (next == playlistFocusIndex) return
        playlistFocusIndex = next
        binding.playlistGrid.setSelectedPositionSmooth(playlistFocusIndex)
    }

    // handleBackPress 单击返回仅显示遮罩，短时间内连续按两次才退出。
    private fun handleBackPress() {
        val now = System.currentTimeMillis()
        if (isPlaylistOverlayVisible) {
            hidePlaylistOverlay()
            lastBackPressAt = now
            return
        }
        val controllerVisible = binding.playerView.isControllerFullyVisible
        if (!controllerVisible) {
            binding.playerView.showController()
            lastBackPressAt = now
            return
        }
        if (now - lastBackPressAt <= DOUBLE_BACK_EXIT_WINDOW_MS) {
            finish()
        } else {
            lastBackPressAt = now
            Toast.makeText(this, "再按一次返回退出播放", Toast.LENGTH_SHORT).show()
        }
    }

    // togglePlayPause 切换暂停与继续播放；暂停时弹出底部视频列表。
    private fun togglePlayPause() {
        val exo = player ?: return
        if (exo.isPlaying) {
            exo.playWhenReady = false
            showPlaylistOverlay()
        } else {
            exo.playWhenReady = true
            hidePlaylistOverlay()
        }
    }

    // setPlaying 设置播放状态；暂停时同步显示底部视频列表。
    private fun setPlaying(playing: Boolean) {
        val exo = player ?: return
        exo.playWhenReady = playing
        if (playing) {
            hidePlaylistOverlay()
        } else {
            showPlaylistOverlay()
        }
    }

    // showPlaylistOverlay 暂停并展示底部横向视频列表，定位到当前播放项。
    private fun showPlaylistOverlay() {
        player?.playWhenReady = false
        binding.playerView.isFocusable = false
        binding.playerView.isFocusableInTouchMode = false
        syncPlaylistAdapter()
        binding.playlistOverlay.visibility = View.VISIBLE
        binding.playlistGrid.isFocusable = true
        binding.playlistGrid.isFocusableInTouchMode = true
        isPlaylistOverlayVisible = true
        scrollPlaylistToIndex(currentIndex)
    }

    // hidePlaylistOverlay 隐藏底部视频列表。
    private fun hidePlaylistOverlay() {
        binding.playlistOverlay.visibility = View.INVISIBLE
        binding.playlistGrid.isFocusable = false
        binding.playlistGrid.isFocusableInTouchMode = false
        isPlaylistOverlayVisible = false
        binding.playerView.isFocusable = true
        binding.playerView.isFocusableInTouchMode = true
    }

    // syncPlaylistAdapter 将当前播放列表同步到底部列表适配器。
    private fun syncPlaylistAdapter() {
        val adapterSize = playlistAdapter.size()
        if (adapterSize == playlist.size) return
        if (adapterSize < playlist.size) {
            for (index in adapterSize until playlist.size) {
                playlistAdapter.add(playlist[index])
            }
            return
        }
        playlistAdapter.clear()
        playlistAdapter.addAll(0, playlist)
    }

    // restorePlaylistSelection 在列表数据变更后恢复选中位置。
    private fun restorePlaylistSelection() {
        if (!isPlaylistOverlayVisible || playlist.isEmpty()) return
        scrollPlaylistToIndex(playlistFocusIndex.coerceIn(0, playlist.size - 1))
    }

    // scrollPlaylistToIndex 等列表布局完成后再滚动并选中指定索引。
    private fun scrollPlaylistToIndex(index: Int) {
        val targetIndex = index.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
        if (playlist.isEmpty()) return
        playlistFocusIndex = targetIndex
        val grid = binding.playlistGrid
        val applySelection = Runnable {
            grid.setSelectedPosition(playlistFocusIndex)
            grid.post {
                grid.setSelectedPosition(playlistFocusIndex)
                grid.requestFocus()
            }
        }
        if (grid.width > 0) {
            grid.post(applySelection)
            return
        }
        grid.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    if (grid.width <= 0) return
                    grid.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    applySelection.run()
                }
            },
        )
        grid.requestLayout()
    }

    // selectVideoAt 切换到指定索引的视频并开始播放。
    private fun selectVideoAt(index: Int) {
        if (index !in playlist.indices) return
        hidePlaylistOverlay()
        if (index == currentIndex) {
            player?.playWhenReady = true
            return
        }
        currentIndex = index
        player?.let { loadVideo(it, currentIndex) }
    }

    // seekBy 以秒级步长快进或快退。
    private fun seekBy(deltaMs: Long) {
        val exo = player ?: return
        val duration = exo.duration.let { if (it > 0) it else Long.MAX_VALUE }
        val target = (exo.currentPosition + deltaMs).coerceIn(0L, duration)
        exo.seekTo(target)
        updateProgressText()
    }

    // playNext 切换到下一条视频，已到末尾则退出。
    private fun playNext() {
        val next = currentIndex + 1
        if (next < playlist.size) {
            currentIndex = next
            player?.let { loadVideo(it, currentIndex) }
        } else if (hasMore) {
            pendingAdvanceAfterPrefetch = true
            maybePrefetchMore(force = true)
        } else {
            finishWithResult()
        }
    }

    // playPrev 切换到上一条视频。
    private fun playPrev() {
        val prev = currentIndex - 1
        if (prev >= 0) {
            currentIndex = prev
            player?.let { loadVideo(it, currentIndex) }
        }
    }

    // showError 展示播放错误信息。
    private fun showError(message: String) {
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = message
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // maybePrefetchMore 在剩余视频较少时预取下一页，保障连续播放衔接顺滑。
    private fun maybePrefetchMore(force: Boolean) {
        if (!hasMore || isPrefetching) return
        val remaining = playlist.size - currentIndex - 1
        if (!force && remaining > PREFETCH_TRIGGER_REMAINING) return
        val cursor = nextCursor ?: run {
            hasMore = false
            return
        }
        isPrefetching = true
        lifecycleScope.launch {
            try {
                val app = application as NinumaoApp
                val config = app.configRepository.getConfig()
                DebugLogger.log("Playback", "预取下一页 sinceId=${cursor.sinceId} page=${cursor.page}")
                val page = app.weiboRepository.fetchVideoPage(config, cursor = cursor)
                val merged = (playlist + page.videos).distinctBy { it.id }
                val added = merged.size - playlist.size
                playlist = merged
                nextCursor = page.nextCursor
                hasMore = page.nextCursor != null && page.videos.isNotEmpty() && added > 0
                DebugLogger.log(
                    "Playback",
                    "预取完成 added=$added hasMore=$hasMore next=${page.nextCursor}",
                )
            } catch (e: Exception) {
                DebugLogger.log("Playback", "预取失败: ${e.message}")
            } finally {
                isPrefetching = false
                if (isPlaylistOverlayVisible) {
                    syncPlaylistAdapter()
                    restorePlaylistSelection()
                }
                if (pendingAdvanceAfterPrefetch) {
                    pendingAdvanceAfterPrefetch = false
                    val next = currentIndex + 1
                    if (next < playlist.size) {
                        currentIndex = next
                        player?.let { loadVideo(it, currentIndex) }
                    } else if (!hasMore) {
                        finishWithResult()
                    }
                }
            }
        }
    }

    // updateTitleOverlay 刷新标题栏上的视频标题与序号。
    private fun updateTitleOverlay() {
        val video = playlist.getOrNull(currentIndex) ?: return
        binding.overlayTitle.text = video.title
        updateProgressText()
    }

    // updateProgressText 刷新播放进度文字。
    private fun updateProgressText() {
        val exo = player ?: return
        val position = exo.currentPosition.coerceAtLeast(0)
        val duration = exo.duration.let { if (it <= 0) 0L else it }
        binding.overlayProgress.text = buildString {
            append(formatMs(position))
            append(" / ")
            append(if (duration > 0) formatMs(duration) else "--:--")
            append("  ·  第 ${currentIndex + 1} 条 / 共 ${playlist.size} 条")
        }
    }

    // formatMs 将毫秒格式化为 mm:ss 字符串。
    private fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        return "%02d:%02d".format(totalSec / 60, totalSec % 60)
    }

    // finishWithResult 退出时回传最后播放的视频索引。
    private fun finishWithResult() {
        finish()
    }

    // finish 覆盖所有退出路径，始终把当前播放索引写入 result，避免系统返回等路径漏掉。
    override fun finish() {
        setResult(RESULT_OK, Intent().putExtra(RESULT_LAST_INDEX, currentIndex))
        super.finish()
    }

    // readPlaylist 兼容不同 API 级别读取视频列表。
    @Suppress("DEPRECATION")
    private fun readPlaylist(): List<VideoItem> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableArrayListExtra(EXTRA_PLAYLIST, VideoItem::class.java)
        } else {
            intent.getParcelableArrayListExtra(EXTRA_PLAYLIST)
        } ?: emptyList()
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        progressHandler.removeCallbacks(progressRunnable)
        player?.release()
        player = null
    }

    companion object {
        private const val EXTRA_PLAYLIST = "extra_playlist"
        private const val EXTRA_INDEX = "extra_index"
        const val RESULT_LAST_INDEX = "result_last_index"
        private const val SEEK_STEP_MS = 10_000L
        private const val PREFETCH_TRIGGER_REMAINING = 3
        private const val DOUBLE_BACK_EXIT_WINDOW_MS = 1500L
        private const val PLAYLIST_CONFIRM_DEBOUNCE_MS = 300L
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        // createIntent 构造播放页 Intent，播放列表通过 PlaybackSession 传递以避免 Intent 过大。
        fun createIntent(context: Context, startIndex: Int = 0): Intent {
            return Intent(context, PlaybackActivity::class.java).apply {
                putExtra(EXTRA_INDEX, startIndex)
            }
        }
    }
}
