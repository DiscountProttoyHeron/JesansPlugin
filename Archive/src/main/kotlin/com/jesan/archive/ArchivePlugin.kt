package com.jesan.archive

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ArchivePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ArchiveProvider())
    }
}
