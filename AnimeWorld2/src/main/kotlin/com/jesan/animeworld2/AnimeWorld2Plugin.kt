package com.jesan.animeworld2

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class AnimeWorld2Plugin : BasePlugin() {
    override fun load() {
        registerMainAPI(AnimeWorld2Provider())
    }
}
