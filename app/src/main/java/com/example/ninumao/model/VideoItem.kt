package com.example.ninumao.model

import android.os.Parcel
import android.os.Parcelable

// VideoItem 表示一条可播放的微博视频条目。
data class VideoItem(
    val id: String,
    val title: String,
    val coverUrl: String?,
    val streamUrl: String,
    val createdAt: String?,
) : Parcelable {

    constructor(parcel: Parcel) : this(
        id = parcel.readString().orEmpty(),
        title = parcel.readString().orEmpty(),
        coverUrl = parcel.readString(),
        streamUrl = parcel.readString().orEmpty(),
        createdAt = parcel.readString(),
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeString(id)
        parcel.writeString(title)
        parcel.writeString(coverUrl)
        parcel.writeString(streamUrl)
        parcel.writeString(createdAt)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<VideoItem> {
        override fun createFromParcel(parcel: Parcel): VideoItem = VideoItem(parcel)

        override fun newArray(size: Int): Array<VideoItem?> = arrayOfNulls(size)
    }
}
