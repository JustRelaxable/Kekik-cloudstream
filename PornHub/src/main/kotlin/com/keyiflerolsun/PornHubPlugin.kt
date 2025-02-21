package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class PornHubPlugin: BasePlugin() {
    override fun load() {
        registerMainAPI(PornHub())
    }
}