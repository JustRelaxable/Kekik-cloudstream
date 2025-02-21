package com.coxju

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class xHamsterProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(xHamster())
    }
}