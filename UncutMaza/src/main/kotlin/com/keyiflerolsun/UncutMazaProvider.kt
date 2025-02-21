package com.coxju

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class UncutMazaProvider: BasePlugin() {
    override fun load() {
        registerMainAPI(UncutMaza())
    }
}