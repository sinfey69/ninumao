package com.example.ninumao.data.weibo

import retrofit2.http.GET
import retrofit2.http.Query

// WeiboApi 定义 m.weibo.cn 移动端接口。
interface WeiboApi {

    // getContainerIndex 拉取用户容器数据，可用于 init 或分页列表。
    @GET("api/container/getIndex")
    suspend fun getContainerIndex(
        @Query("type") type: String = "uid",
        @Query("value") uid: String,
        @Query("containerid") containerId: String? = null,
        @Query("since_id") sinceId: Long? = null,
    ): ContainerIndexResponse
}
