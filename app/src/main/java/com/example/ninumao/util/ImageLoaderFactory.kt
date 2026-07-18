package com.example.ninumao.util

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache

// ImageLoaderFactory 创建带容量限制的 Coil ImageLoader。
object ImageLoaderFactory {

    // create 构建应用级图片加载器：内存缓存 + 磁盘缓存（上限约 150MB）。
    fun create(context: Context): ImageLoader {
        val appContext = context.applicationContext
        return ImageLoader.Builder(appContext)
            .crossfade(true)
            .memoryCache {
                MemoryCache.Builder(appContext)
                    .maxSizePercent(0.20)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(AppCacheCleaner.imageCacheDirectory(appContext))
                    .maxSizeBytes(AppCacheCleaner.maxDiskCacheBytes())
                    .build()
            }
            .build()
    }
}
