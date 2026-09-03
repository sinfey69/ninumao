package com.example.ninumao.util

import android.content.Context
import android.util.Log
import coil.Coil
import java.io.File

// AppCacheCleaner 管理图片与调试日志的缓存清理策略。
object AppCacheCleaner {

    private const val TAG = "AppCacheCleaner"
    private const val IMAGE_CACHE_DIR = "image_cache"
    private const val MAX_DISK_CACHE_BYTES = 150L * 1024L * 1024L
    private const val EXPIRE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L

    // imageCacheDirectory 返回 Coil 图片磁盘缓存目录。
    fun imageCacheDirectory(context: Context): File {
        return File(context.cacheDir, IMAGE_CACHE_DIR).apply { mkdirs() }
    }

    // maxDiskCacheBytes 图片磁盘缓存上限。
    fun maxDiskCacheBytes(): Long = MAX_DISK_CACHE_BYTES

    // onAppBackground 应用退到后台：清理图片内存缓存（磁盘过期清理放在下次启动）。
    fun onAppBackground(context: Context) {
        clearMemoryImageCache(context)
    }

    // onAppStart 应用启动、Coil 初始化前清理超过保留期的磁盘缓存。
    fun onAppStart(context: Context) {
        pruneExpiredDiskCache(context)
    }

    // wipeImageCache 删除整个图片磁盘缓存目录（用于缓存损坏恢复）。
    fun wipeImageCache(context: Context) {
        try {
            imageCacheDirectory(context).deleteRecursively()
            imageCacheDirectory(context)
        } catch (e: Exception) {
            Log.w(TAG, "清空图片磁盘缓存失败: ${e.message}")
        }
    }

    // clearMemoryImageCache 仅清理图片内存缓存，保留磁盘热数据。
    private fun clearMemoryImageCache(context: Context) {
        try {
            Coil.imageLoader(context).memoryCache?.clear()
        } catch (e: Exception) {
            Log.w(TAG, "清理图片内存缓存失败: ${e.message}")
        }
    }

    // pruneExpiredDiskCache 删除超过 7 天未访问/未修改的磁盘缓存文件。
    fun pruneExpiredDiskCache(context: Context) {
        val cacheDir = imageCacheDirectory(context)
        if (!cacheDir.exists()) return

        val expireBefore = System.currentTimeMillis() - EXPIRE_AFTER_MS
        var deletedFiles = 0
        var deletedBytes = 0L

        try {
            cacheDir.walkBottomUp().forEach { file ->
                if (!file.isFile) return@forEach
                // journal 由 Coil 维护，跳过以免破坏索引结构
                if (file.name.equals("journal", ignoreCase = true) ||
                    file.name.startsWith("journal.")
                ) {
                    return@forEach
                }
                if (file.lastModified() > 0L && file.lastModified() < expireBefore) {
                    val size = file.length()
                    if (file.delete()) {
                        deletedFiles++
                        deletedBytes += size
                    }
                }
            }
            if (deletedFiles > 0) {
                Log.i(
                    TAG,
                    "已清理过期图片缓存 files=$deletedFiles size=${deletedBytes / 1024}KB",
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "清理过期磁盘缓存失败: ${e.message}")
        }
    }
}
