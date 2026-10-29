package com.kdgames.wake_on_lan

import android.app.Application
import com.kdgames.wake_on_lan.domain.agent.AgentLinker
import com.kdgames.wake_on_lan.domain.status.StatusEngine
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class WolApp : Application() {
    @Inject lateinit var statusEngine: StatusEngine
    @Inject lateinit var agentLinker: AgentLinker

    override fun onCreate() {
        super.onCreate()
        // Turlar ve discovery yalnız uygulama ön plandayken çalışır (spec 9.1, 7.5).
        statusEngine.start()
        agentLinker.start()
    }
}
