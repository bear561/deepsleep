package com.deepsleep.chat.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.deepsleep.chat.DeepSleepApplication
import com.deepsleep.chat.di.AppContainer

@Composable
fun rememberAppContainer(): AppContainer {
    val context = LocalContext.current
    return remember(context) {
        (context.applicationContext as DeepSleepApplication).container
    }
}