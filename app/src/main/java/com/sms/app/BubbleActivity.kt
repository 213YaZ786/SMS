package com.sms.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.sms.app.feature.thread.ThreadScreen
import com.sms.app.navigation.ReadableScroll
import com.sms.app.ui.theme.AppSurface

/** A conversation in a floating bubble, over the other apps. Not exported: only its own notification opens it. */
class BubbleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thread = intent.getLongExtra(MainActivity.EXTRA_THREAD, -1L).takeIf { it >= 0 }
        val address = intent.getStringExtra(MainActivity.EXTRA_ADDRESS).orEmpty()
        setContent {
            AppSurface {
                ReadableScroll { ThreadScreen(threadId = thread, address = address, draft = "", onBack = { finish() }) }
            }
        }
    }
}
