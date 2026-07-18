package com.example.ninumao.ui.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.ninumao.util.ErrorMapper
import com.example.ninumao.data.config.ConfigRepository
import com.example.ninumao.data.weibo.WeiboRepository
import com.example.ninumao.model.VideoItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// VideoUiState 描述视频列表页 UI 状态。
data class VideoUiState(
    val videos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val errorMessage: String? = null,
    val hasMore: Boolean = true,
    val nextSinceId: Long? = null,
    val uid: String = "",
)

// VideoViewModel 负责加载与分页微博视频列表。
class VideoViewModel(
    private val configRepository: ConfigRepository,
    private val weiboRepository: WeiboRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoUiState())
    val uiState: StateFlow<VideoUiState> = _uiState.asStateFlow()

    private var nextSinceId: Long? = null

    init {
        refresh()
    }

    // refresh 重新加载第一页视频。
    fun refresh() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    errorMessage = null,
                    videos = emptyList(),
                    hasMore = true,
                    nextSinceId = null,
                    uid = "",
                )
            }
            nextSinceId = null
            loadInternal(reset = true)
        }
    }

    // loadMore 加载下一页视频。
    fun loadMore() {
        val state = _uiState.value
        if (state.isLoading || state.isLoadingMore || !state.hasMore || nextSinceId == null) {
            return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMore = true, errorMessage = null) }
            loadInternal(reset = false)
        }
    }

    // loadInternal 执行实际的列表请求。
    private suspend fun loadInternal(reset: Boolean) {
        try {
            val config = configRepository.getConfig()
            if (config.uid.isBlank()) {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        isLoadingMore = false,
                        errorMessage = "请先在设置中配置博主 UID",
                        uid = "",
                    )
                }
                return
            }

            val page = weiboRepository.fetchVideoPage(
                config = config,
                sinceId = if (reset) null else nextSinceId,
            )
            nextSinceId = page.nextSinceId
            // 首页刷新成功后，把接口解析到的博主名写回最近列表
            if (reset) {
                weiboRepository.lastResolvedBloggerName?.let { name ->
                    configRepository.updateRecentBloggerName(config.uid, name)
                }
            }
            _uiState.update { current ->
                val merged = if (reset) page.videos else (current.videos + page.videos).distinctBy { it.id }
                current.copy(
                    videos = merged,
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = if (merged.isEmpty()) "暂无视频，请检查 UID 或 Cookie" else null,
                    hasMore = page.nextSinceId != null && page.videos.isNotEmpty(),
                    nextSinceId = page.nextSinceId,
                    uid = config.uid,
                )
            }
        } catch (e: Exception) {
            com.example.ninumao.util.DebugLogger.log("ViewModel", "异常: ${e.javaClass.simpleName} - ${e.message}")
            e.cause?.let { cause ->
                com.example.ninumao.util.DebugLogger.log("ViewModel", "原因: ${cause.javaClass.simpleName} - ${cause.message}")
            }
            val userMessage = ErrorMapper.toUserMessage(e)
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLoadingMore = false,
                    errorMessage = userMessage,
                )
            }
        }
    }
}

// VideoViewModelFactory 创建 VideoViewModel 实例。
class VideoViewModelFactory(
    private val configRepository: ConfigRepository,
    private val weiboRepository: WeiboRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(VideoViewModel::class.java)) {
            return VideoViewModel(configRepository, weiboRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
