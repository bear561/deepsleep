package com.deepsleep.chat.data.remote

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

class HttpClients {
    /** Streaming clients must not follow redirects, otherwise Authorization can leak to another host. */
    val streaming: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    val standard: OkHttpClient = streaming.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .build()

    val search: OkHttpClient = streaming.newBuilder()
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
}