package com.example.ninumao.ui.playback

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.ninumao.NinumaoApp
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
    private var nextSinceId: Long? = null
    private var hasMore: Boolean = false
    private var isPrefetching: Boolean = false
    private var pendingAdvanceAfterPrefetch: Boolean = false
    private var lastBackPressAt: Long = 0L

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
            nextSinceId = sessionData.nextSinceId
            hasMore = sessionData.hasMore
        } else {
            playlist = readPlaylist()
            currentIndex = intent.getIntExtra(EXTRA_INDEX, 0)
                .coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            nextSinceId = null
            hasMore = false
        }

        if (playlist.isEmpty()) {
            finish()
            return
        }

        setupBackHandler()
        setupTitleOverlay()
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
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_PAGE_DOWN -> { playNext(); return true }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_PAGE_UP -> { playPrev(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_DPAD_CENTER,
                KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    setPlaying(true)
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    setPlaying(false)
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

    // handleBackPress 单击返回仅显示遮罩，短时间内连续按两次才退出。
    private fun handleBackPress() {
        val now = System.currentTimeMillis()
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

    // togglePlayPause 切换暂停与继续播放。
    private fun togglePlayPause() {
        val exo = player ?: return
        exo.playWhenReady = !exo.isPlaying
    }

    // setPlaying 设置播放状态。
    private fun setPlaying(playing: Boolean) {
        val exo = player ?: return
        exo.playWhenReady = playing
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
        val cursor = nextSinceId ?: run {
            hasMore = false
            return
        }
        isPrefetching = true
        lifecycleScope.launch {
            try {
                val app = application as NinumaoApp
                val config = app.configRepository.getConfig()
                DebugLogger.log("Playback", "预取下一页 sinceId=$cursor")
                val page = app.weiboRepository.fetchVideoPage(config, sinceId = cursor)
                val merged = (playlist + page.videos).distinctBy { it.id }
                val added = merged.size - playlist.size
                playlist = merged
                nextSinceId = page.nextSinceId
                hasMore = page.nextSinceId != null && page.videos.isNotEmpty()
                DebugLogger.log(
                    "Playback",
                    "预取完成 added=$added hasMore=$hasMore nextSinceId=${page.nextSinceId}",
                )
            } catch (e: Exception) {
                DebugLogger.log("Playback", "预取失败: ${e.message}")
            } finally {
                isPrefetching = false
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
