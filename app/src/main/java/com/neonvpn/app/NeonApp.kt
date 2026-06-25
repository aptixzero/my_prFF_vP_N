package com.neonvpn.app

import android.app.Application
import com.neonvpn.app.config.RemoteConfigStore
import com.neonvpn.app.stats.UserStatsReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class NeonApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        // Install the global crash guard as early as possible so even a very
        // early crash (e.g. on the first CONNECT tap / permission return) is
        // caught and the process is kept alive instead of dying.
        try { CrashHandler.install(this) } catch (_: Throwable) {}

        // Load the cached panel settings instantly so the ad banner / contact
        // copy show correct text immediately, then refresh from the admin panel
        // in the background so any operator change is picked up on launch.
        try {
            RemoteConfigStore.loadCache(this)
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                try { RemoteConfigStore.refresh(this@NeonApp) } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        // Privacy-first anonymous user-stats heartbeat (Online / Offline / All in
        // the admin panel's Users section). Reports only an opaque, non-reversible
        // token + aggregate counts — never an IP, device id, account, or location.
        // Fully best-effort: runs off the main thread and never blocks/crashes UI.
        try { UserStatsReporter.start(this) } catch (_: Throwable) {}
    }

    companion object {
        lateinit var instance: NeonApp
            private set
    }
}
