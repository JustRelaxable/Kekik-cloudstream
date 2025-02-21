package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class NetflixMirrorPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(NetflixMirror())
        registerMainAPI(PrimeVideoMirror())
    }
}