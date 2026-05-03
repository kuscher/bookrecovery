package com.google.chrome.recovery.data

import com.google.gson.annotations.SerializedName

data class RecoveryImage(
    @SerializedName("channel") val channel: String?,
    @SerializedName("desc") val desc: String?,
    @SerializedName("file") val file: String?,
    @SerializedName("filesize") val filesize: Long?,
    @SerializedName("hwidmatch") val hwidmatch: String?,
    @SerializedName("manufacturer") val manufacturer: String?,
    @SerializedName("md5") val md5: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("photourl") val photourl: String?,
    @SerializedName("sha1") val sha1: String?,
    @SerializedName("sku") val sku: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("version") val version: String?,
    @SerializedName("zipfilesize") val zipfilesize: Long?,
    @SerializedName("chrome_version") val chromeVersion: String?,
    @SerializedName("hwids") val hwids: List<String>?
)
