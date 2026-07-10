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

        // v5.6 — LIFECYCLE HANDLER: if Auto Test was left ON but the OS killed the
        // process (e.g. during a long screen-off run), transparently resume it on
        // relaunch so the user never silently drops out of auto-test mode.
        try {
            if (com.neonvpn.app.util.AppPrefs.isAutoTestOn(this) &&
                !com.neonvpn.app.config.AutoTestEngine.isRunning
            ) {
                com.neonvpn.app.config.AutoTestEngine.start(this)
            }
        } catch (_: Throwable) {}
    }

    /**
     * v4.8 — OVERNIGHT STABILITY. When the OS signals memory pressure (which is
     * exactly what happens during a long screen-off Auto-Test session on a phone
     * that's also caching other apps), we proactively shed the biggest shedable
     * loads BEFORE the kernel OOM-kills us. This keeps the process alive and the
     * tunnel up through the night instead of crashing when the cache fills.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        try {
            if (level >= TRIM_MEMORY_RUNNING_LOW) {
                // v5.6 — do NOT wipe the ping map here. The old prune(emptySet())
                // cleared the user's measured pings on any memory pressure tick
                // (which fires routinely during a long screen-off Auto-Test run),
                // so "my pings disappeared after a few minutes" was really the OS
                // asking us to trim. The ping map is tiny; only hint a GC. Results
                // are also persisted to disk, so nothing is ever lost.
                runCatching { System.gc() }
            }
        } catch (_: Throwable) {}
    }

    override fun onLowMemory() {
        super.onLowMemory()
        try {
            // Only under TRUE low-memory do we shed, and even then the persisted
            // PingStore restores the results on the next hydrate (content-keyed),
            // so the badges reappear rather than being permanently reset.
            runCatching { System.gc() }
        } catch (_: Throwable) {}
    }

    companion object {
        lateinit var instance: NeonApp
            private set
    }
}
