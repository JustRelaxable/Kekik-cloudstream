package com.coxju

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class SpankBangPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(SpankBang())
    }
}