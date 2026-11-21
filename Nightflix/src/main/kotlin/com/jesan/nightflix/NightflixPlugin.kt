package com.jesan.nightflix

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NightflixPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(NightflixProvider())
    }
}
