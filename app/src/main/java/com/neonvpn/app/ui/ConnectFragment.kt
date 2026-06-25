package com.neonvpn.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.neonvpn.app.R
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.RemoteConfig
import com.neonvpn.app.config.RemoteConfigStore
import com.neonvpn.app.service.NeonVpnService
import com.neonvpn.app.ui.widget.LiquidOrbConnectView
import com.neonvpn.app.util.Format
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Home / Connect screen. The connect control is a v3.8 LIQUID ORB
 * (see [LiquidOrbConnectView]) with five states & a real connect-progress arc:
 *   IDLE · CONNECTING (with 0..100% arc) · CONNECTED · DISCONNECTING · ERROR.
 * Tapping the orb toggles the real VpnService on/off (350ms-debounced, serialised
 * through a single Mutex-guarded state-machine coroutine — §4.5).
 *
 * All speed / ping / uptime numbers shown here come straight from the live Xray
 * core via [NeonVpnService] — there are NO random / fake values anywhere.
 */
class ConnectFragment : Fragment() {

    private lateinit var store: ConfigStore
    private lateinit var statusText: TextView
    private lateinit var serverText: TextView
    private lateinit var orb: LiquidOrbConnectView
    private var appLogo: ImageView? = null
    private var telegramIcon: View? = null

    // §4.5 — a SINGLE state-machine coroutine consumes toggle intents through a
    // CONFLATED channel (latest-tap-wins) guarded by a Mutex, so rapid taps can
    // never spawn overlapping start/stop sequences. Taps are 350ms-debounced.
    private val toggleChannel = Channel<Unit>(Channel.CONFLATED)
    private val toggleMutex = Mutex()
    private var lastTapMs = 0L
    private val tapDebounceMs = 350L

    /** Live in-app Telegram URL (admin-controlled). Kept in sync via the remote
     *  config listener so the icon always opens the latest published channel. */
    @Volatile private var telegramUrl: String = ""

    private val remoteListener: (RemoteConfig) -> Unit = { cfg ->
        activity?.runOnUiThread {
            bindLogo(cfg.appLogoUrl)
            telegramUrl = cfg.homeTelegramUrl
        }
    }

    private lateinit var downloadSpeed: TextView
    private lateinit var downloadTotal: TextView
    private lateinit var uploadSpeed: TextView
    private lateinit var uploadTotal: TextView
    private lateinit var pingValue: TextView
    private lateinit var uptimeValue: TextView

    private val listener: (String, String) -> Unit = { state, info ->
        activity?.runOnUiThread { render(state, info) }
    }
    private val statsListener: (VpnStats) -> Unit = { s ->
        activity?.runOnUiThread { renderStats(s) }
    }

    private val vpnPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == Activity.RESULT_OK) {
                actuallyStartVpn()
            } else {
                render(NeonVpnService.STATE_DISCONNECTED, "Permission denied")
            }
        }

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_connect, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        store = ConfigStore(requireContext())
        statusText = view.findViewById(R.id.status_text)
        serverText = view.findViewById(R.id.server_text)
        orb = view.findViewById(R.id.connect_control)
        appLogo = view.findViewById(R.id.app_logo)
        telegramIcon = view.findViewById(R.id.telegram_icon)

        // §4.2 — Telegram icon opens the admin-configured "In-App Telegram Link"
        // (never a hardcoded link). Seed from the dedicated pref cache so the icon
        // is correct instantly on cold start; the remote listener keeps it live.
        telegramUrl = RemoteConfigStore.cachedTelegramUrl(requireContext())
        telegramIcon?.setOnClickListener { openTelegram() }
        // Refresh on start (background) so an operator change is picked up.
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteConfigStore.refreshTelegramThrottled(requireContext())
        }

        downloadSpeed = view.findViewById(R.id.download_speed)
        downloadTotal = view.findViewById(R.id.download_total)
        uploadSpeed = view.findViewById(R.id.upload_speed)
        uploadTotal = view.findViewById(R.id.upload_total)
        pingValue = view.findViewById(R.id.ping_value)
        uptimeValue = view.findViewById(R.id.uptime_value)

        orb.onClick = { onTap() }

        // §4.5 — single state-machine consumer + §4.3 — observe connect progress.
        // Both run on viewLifecycleOwner so they're torn down with the view.
        startToggleStateMachine()
        observeProgress()

        maybeAskNotifications()
    }

    /**
     * §4.5 — a tap just OFFERS a debounced intent to the conflated channel. The
     * single consumer ([startToggleStateMachine]) serialises the actual
     * start/stop work under a Mutex so overlapping taps can't race.
     */
    private fun onTap() {
        val now = System.currentTimeMillis()
        if (now - lastTapMs < tapDebounceMs) return
        lastTapMs = now
        toggleChannel.trySend(Unit)
    }

    private fun startToggleStateMachine() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                for (ignored in toggleChannel) {
                    toggleMutex.withLock { onToggle() }
                }
            }
        }
    }

    /** §4.3 — drive the Liquid Orb's progress arc from the service's StateFlow. */
    private fun observeProgress() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                VpnStateBus.progress.collect { p -> orb.setProgress(p.percent) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        synchronized(VpnStateBus.listeners) { VpnStateBus.listeners.add(listener) }
        synchronized(VpnStateBus.statsListeners) { VpnStateBus.statsListeners.add(statsListener) }
        RemoteConfigStore.addListener(remoteListener)
        // Reconcile against the authoritative service state FIRST — while we were
        // backgrounded the tunnel may have died (watchdog / OS kill) and that
        // broadcast was missed, so trusting the in-memory bus alone would show a
        // stale "Connected". This makes the resumed UI always reflect reality.
        VpnStateBus.reconcileWithService()
        render(VpnStateBus.state, VpnStateBus.info)
        renderStats(VpnStateBus.stats)
        // §4.2 — pick up any operator-changed Telegram link, throttled to 60s.
        telegramUrl = RemoteConfigStore.cachedTelegramUrl(requireContext())
        viewLifecycleOwner.lifecycleScope.launch {
            RemoteConfigStore.refreshTelegramThrottled(requireContext())
        }
    }

    override fun onPause() {
        super.onPause()
        synchronized(VpnStateBus.listeners) { VpnStateBus.listeners.remove(listener) }
        synchronized(VpnStateBus.statsListeners) { VpnStateBus.statsListeners.remove(statsListener) }
        RemoteConfigStore.removeListener(remoteListener)
    }

    /** Show the admin-controlled app logo (live). Empty = fall back to wordmark. */
    private fun bindLogo(url: String) {
        val iv = appLogo ?: return
        if (url.isBlank()) { iv.visibility = View.GONE; return }
        CoroutineScope(Dispatchers.IO).launch {
            val bmp = try {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 9000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ProfessorVPN/2.9")
                }
                c.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
            } catch (_: Throwable) { null }
            withContext(Dispatchers.Main) {
                if (isAdded && bmp != null) {
                    iv.setImageBitmap(bmp); iv.visibility = View.VISIBLE
                }
            }
        }
    }


    /** Open the live, admin-controlled Telegram channel URL. */
    private fun openTelegram() {
        val url = telegramUrl.ifBlank { RemoteConfigStore.cachedTelegramUrl(requireContext()) }.trim()
        if (url.isBlank()) return
        try {
            startActivity(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
        } catch (_: Throwable) { /* no telegram / browser — ignore */ }
    }

    private fun onToggle() {
        val current = VpnStateBus.state
        if (current == NeonVpnService.STATE_CONNECTED ||
            current == NeonVpnService.STATE_CONNECTING
        ) {
            stopVpn()
            return
        }

        val selected = store.getSelected()
        if (selected == null) {
            statusText.text = getString(R.string.no_config)
            orb.setState(LiquidOrbConnectView.State.IDLE)
            return
        }

        try {
            val prepare = VpnService.prepare(requireContext())
            if (prepare != null) {
                vpnPermissionLauncher.launch(prepare)
            } else {
                actuallyStartVpn()
            }
        } catch (e: Throwable) {
            render(NeonVpnService.STATE_ERROR, "Cannot request VPN permission")
        }
    }

    private fun actuallyStartVpn() {
        try {
            render(NeonVpnService.STATE_CONNECTING, store.getSelected()?.remark ?: "")
            val intent = Intent(requireContext(), NeonVpnService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                requireContext().startForegroundService(intent)
            } else {
                requireContext().startService(intent)
            }
        } catch (e: Throwable) {
            render(NeonVpnService.STATE_ERROR, "Failed to start VPN service")
        }
    }

    private fun stopVpn() {
        // brief "disconnecting" beat on the orb before it settles to idle
        orb.setState(LiquidOrbConnectView.State.DISCONNECTING)
        val intent = Intent(requireContext(), NeonVpnService::class.java).apply {
            action = NeonVpnService.ACTION_STOP
        }
        requireContext().startService(intent)
        render(NeonVpnService.STATE_DISCONNECTED, "")
    }

    private fun render(state: String, info: String) {
        val selected = store.getSelected()
        serverText.text = selected?.let { "${it.remark}  ·  ${it.protocol.uppercase()}" }
            ?: getString(R.string.no_config)

        when (state) {
            NeonVpnService.STATE_CONNECTED -> {
                statusText.text = getString(R.string.connected)
                statusText.setTextColor(themeColor(R.attr.appAccentGreen))
                orb.setState(LiquidOrbConnectView.State.CONNECTED)
            }
            NeonVpnService.STATE_CONNECTING -> {
                statusText.text = getString(R.string.connecting)
                statusText.setTextColor(0xFFFFB020.toInt())
                orb.setState(LiquidOrbConnectView.State.CONNECTING)
            }
            NeonVpnService.STATE_ERROR -> {
                statusText.text = if (info.isNotBlank()) "ERROR · $info" else getString(R.string.error)
                statusText.setTextColor(themeColor(R.attr.appAccentRed))
                orb.setState(LiquidOrbConnectView.State.ERROR)
                resetStats()
            }
            else -> {
                statusText.text = getString(R.string.disconnected)
                statusText.setTextColor(themeColor(R.attr.appTextSecondary))
                orb.setState(LiquidOrbConnectView.State.IDLE)
                resetStats()
            }
        }
    }

    private fun renderStats(s: VpnStats) {
        val connected = VpnStateBus.state == NeonVpnService.STATE_CONNECTED
        if (!connected) return
        downloadSpeed.text = Format.speed(s.downRate)
        uploadSpeed.text = Format.speed(s.upRate)
        downloadTotal.text = Format.size(s.downTotal)
        uploadTotal.text = Format.size(s.upTotal)
        pingValue.text = Format.ping(s.ping)
        uptimeValue.text = Format.duration(s.uptime)
    }

    private fun resetStats() {
        downloadSpeed.text = "0 B/s"
        uploadSpeed.text = "0 B/s"
        downloadTotal.text = "0 B"
        uploadTotal.text = "0 B"
        pingValue.text = "—"
        uptimeValue.text = "00:00:00"
    }

    private fun themeColor(attr: Int): Int {
        val tv = android.util.TypedValue()
        return if (requireContext().theme.resolveAttribute(attr, tv, true)) tv.data
        else 0xFF00FF66.toInt()
    }

    private fun maybeAskNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ActivityCompat.checkSelfPermission(
                requireContext(), android.Manifest.permission.POST_NOTIFICATIONS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
