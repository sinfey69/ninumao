package com.example.ninumao.data.weibo

import retrofit2.http.GET
import retrofit2.http.Query

// WeiboApi 定义 m.weibo.cn 移动端接口。
interface WeiboApi {

    // getContainerIndex 拉取用户容器数据，可用于 init 或分页列表。
    @GET("api/container/getIndex")
    suspend fun getContainerIndex(
        @Query("type") type: String? = null,
        @Query("value") uid: String? = null,
        @Query("containerid") containerId: String? = null,
        @Query("since_id") sinceId: String? = null,
        @Query("page") page: Int? = null,
        @Query("page_type") pageType: String? = null,
    ): ContainerIndexResponse

    // getWaterFallContent 拉取桌面端视频瀑布流，用 cursor 翻页。
    @GET("https://weibo.com/ajax/profile/getWaterFallContent")
    suspend fun getWaterFallContent(
        @Query("uid") uid: String,
        @Query("cursor") cursor: String = "0",
    ): WaterFallResponse
}
