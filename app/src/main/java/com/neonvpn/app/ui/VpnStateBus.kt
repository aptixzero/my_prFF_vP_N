package com.neonvpn.app.ui

import com.neonvpn.app.service.NeonVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Live traffic snapshot pushed from the VPN service. */
data class VpnStats(
    val upRate: Long = 0,      // bytes/sec
    val downRate: Long = 0,    // bytes/sec
    val upTotal: Long = 0,     // bytes
    val downTotal: Long = 0,   // bytes
    val ping: Long = -1,       // ms (-1 unknown)
    val uptime: Long = 0       // seconds
)

/**
 * §4.3 — fine-grained connect progress emitted by [NeonVpnService] so the
 * Liquid Orb can animate a real fill (0..100) with a human label rather than a
 * binary connecting/connected flip. [percent] is clamped 0..100.
 */
data class ConnectionProgress(
    val percent: Int = 0,
    val label: String = ""
)

/** Tiny in-memory hub so fragments can observe the latest VPN state + stats. */
object VpnStateBus {
    @Volatile var state: String = NeonVpnService.STATE_DISCONNECTED
    @Volatile var info: String = ""
    @Volatile var stats: VpnStats = VpnStats()

    val listeners = mutableSetOf<(String, String) -> Unit>()
    val statsListeners = mutableSetOf<(VpnStats) -> Unit>()

    // §4.3 — connect-progress StateFlow consumed by the Liquid Orb widget.
    private val _progress = MutableStateFlow(ConnectionProgress())
    val progress: StateFlow<ConnectionProgress> = _progress.asStateFlow()

    /** Push a connect-progress update (clamped). Safe from any thread. */
    fun updateProgress(percent: Int, label: String) {
        _progress.value = ConnectionProgress(percent.coerceIn(0, 100), label)
    }

    fun update(newState: String, newInfo: String) {
        state = newState
        info = newInfo
        if (newState != NeonVpnService.STATE_CONNECTED) {
            // reset live numbers when not connected
            stats = VpnStats(ping = stats.ping.takeIf { newState == NeonVpnService.STATE_CONNECTING } ?: -1)
        }
        synchronized(listeners) {
            listeners.toList().forEach { it(newState, newInfo) }
        }
    }

    fun updateStats(s: VpnStats) {
        stats = s
        synchronized(statsListeners) {
            statsListeners.toList().forEach { it(s) }
        }
    }

    /**
     * Reconcile the in-memory state against the AUTHORITATIVE service state.
     *
     * Call this whenever the UI returns to the foreground. While the app is
     * backgrounded the broadcast receivers are unregistered, so a state change
     * the service made meanwhile (watchdog tore the tunnel down, OS killed the
     * service, etc.) was never delivered and [state] may be a stale "connected".
     *
     * The service keeps the truth in [NeonVpnService.liveState] /
     * [NeonVpnService.isTunnelUp]. If the process was killed and relaunched,
     * those statics naturally reset to disconnected — so this can NEVER leave the
     * UI claiming connected after the tunnel is actually gone.
     */
    fun reconcileWithService() {
        val truth = NeonVpnService.liveState
        val info = NeonVpnService.liveInfo
        // If the service believes it is connected but the tunnel flag is down,
        // trust the flag (tunnel gone) — defensive against a half-updated state.
        val resolved = if (truth == NeonVpnService.STATE_CONNECTED &&
            !NeonVpnService.isTunnelUp
        ) NeonVpnService.STATE_DISCONNECTED else truth
        if (resolved != state || info != this.info) {
            update(resolved, info)
        }
    }
}
