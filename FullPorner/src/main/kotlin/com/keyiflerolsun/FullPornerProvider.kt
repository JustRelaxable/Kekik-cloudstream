package com.coxju

import com.coxju.FullPorner
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class FullPornerProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(FullPorner())
    }
}