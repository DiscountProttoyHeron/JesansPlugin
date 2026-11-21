package com.jesan.nightflix

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class NightflixPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(NightflixProvider())
    }
}
