package com.taylorlauncher.model

import android.content.Intent

data class LauncherItem(
    val title: String,
    val intent: Intent? = null,
    val packageName: String? = null
)
