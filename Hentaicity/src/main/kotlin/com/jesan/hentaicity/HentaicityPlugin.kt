package com.jesan.hentaicity

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class HentaicityPlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(HentaicityProvider())
    }
}
