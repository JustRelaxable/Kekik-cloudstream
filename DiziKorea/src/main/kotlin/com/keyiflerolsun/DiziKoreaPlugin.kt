package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DiziKoreaPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(DiziKorea())
        registerExtractorAPI(VideoSeyred())
    }
}