package com.deepsleep.chat

import android.app.Application
import com.deepsleep.chat.di.AppContainer

class DeepSleepApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}