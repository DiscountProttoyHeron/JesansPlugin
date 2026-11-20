package com.jesan.animeworld2

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AnimeWorld2Plugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(AnimeWorld2Provider())
    }
}
