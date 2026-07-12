package com.neonvpn.app.ui

import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.animation.DecelerateInterpolator
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import com.neonvpn.app.R
import com.neonvpn.app.config.AutoTestEngine
import com.neonvpn.app.config.ConfigParser
import com.neonvpn.app.config.ConfigStore
import com.neonvpn.app.config.ConnectivityProbe
import com.neonvpn.app.config.PingService
import com.neonvpn.app.config.PingStore
import com.neonvpn.app.config.SeenConfigStore
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6.0 — AUTO TEST connectivity page (two-phase, no fake error).
 *
 * A deliberately minimal screen: a title + a progress bar. On open it runs the
 * two-phase [ConnectivityProbe]:
 *
 *   0 % → 60 %  : a REAL connection test against the live source feeds — it opens
 *                 each source and finds which one the user can actually reach, and
 *                 bonds to it. The bar advances as each source is genuinely probed
 *                 (no random timer, no `delay()`).
 *   60 % → 100 %: it pulls a FULL fresh 240 batch of configs FROM THAT reached
 *                 source, in the background (the user does not see the Free tab).
 *                 The bar climbs as configs are actually collected.
 *
 * When the bar reaches 100 % the collected configs are added to My Configs the
 * SAME instant, the page closes, and a PING ALL is kicked off automatically so
 * the working configs sort to the top. The continuous [AutoTestEngine] is then
 * started to keep filling My Configs 240-at-a-time from the same bonded source.
 *
 * There is NO false "connection error" — the reported bug. Even when the network
 * is momentarily unreachable we simply close quietly and let the background engine
 * keep trying; we never flash "Connection error".
 *
 * Every step is guarded so the page can never crash the app.
 */
class AutoTestActivity : BaseActivity() {

    private lateinit var bar: ProgressBar
    private lateinit var percent: TextView
    private var probeJob: Job? = null
    private var barAnimator: ObjectAnimator? = null
    @Volatile private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_auto_test)
        bar = findViewById(R.id.probe_bar)
        percent = findViewById(R.id.probe_percent)
        bar.max = 100

        findViewById<TextView>(R.id.btn_probe_cancel).setOnClickListener {
            finishSafely()
        }

        startProbe()
    }

    private fun startProbe() {
        probeJob = lifecycleScope.launch {
            // Shared dedup memory (persistent seen-set + everything already in My
            // Configs) so the probe never hands back a config the user already
            // has. The probe mutates this and we persist it once the batch lands.
            val seenKeys = withContext(Dispatchers.IO) {
                val s = HashSet<String>()
                runCatching { s.addAll(SeenConfigStore.load(applicationContext)) }
                runCatching {
                    ConfigStore(applicationContext).getServers()
                        .forEach { s.add(ConfigParser.dedupKey(it)) }
                }
                s
            }

            val result = try {
                ConnectivityProbe.probe(applicationContext, seenKeys) { p ->
                    runOnUiThread {
                        if (!isFinishing) {
                            animateBarTo(p)
                            percent.text = "$p%"
                        }
                    }
                }
            } catch (_: Throwable) {
                ConnectivityProbe.Result(emptyList(), reachedSource = false)
            }

            // v6.0 — the bar has reached 100 % (real source work done). Add the
            // collected configs to My Configs RIGHT NOW so they are guaranteed
            // present the moment the page closes. We NEVER show a "connection
            // error": whether we collected configs or not, we start the background
            // engine (so configs keep arriving as the unstable link recovers) and
            // close quietly. When configs WERE collected we also fire an automatic
            // PING ALL so the user immediately sees the working ones pinned on top.
            val addedCount = if (result.configs.isNotEmpty()) {
                runCatching { saveResult(result.configs, seenKeys) }.getOrDefault(0)
            } else 0

            // Always (RE)start the continuous engine — it keeps filling My Configs
            // 240-at-a-time from the SAME bonded source in the background. Using
            // restart() (not start()) means opening this page again — even while a
            // previous run is still going — never wedges the engine or leaves two
            // loops fighting; it always begins a fresh, clean search+add cycle.
            runCatching { AutoTestEngine.restart(applicationContext) }

            // v6.0 — auto-ping the freshly-added batch so working configs sort to
            // the top the instant the user lands back on My Configs.
            if (addedCount > 0) {
                runCatching {
                    val list = ConfigStore(applicationContext).getServers()
                    if (list.isNotEmpty()) {
                        PingService.pingAll(applicationContext, list, PingStore.MY)
                    }
                }
                runOnUiThread { toast(getString(R.string.probe_saved, addedCount)) }
            }
            finishSafely()
        }
    }

    private fun toast(msg: String) {
        runCatching {
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * v5.9 — add the freshly-probed batch to My Configs.
     *
     * Per the brief, a fresh Auto Test REPLACES the previous list with 240 new
     * configs: we WIPE the old My-Configs list and store the new batch. Configs
     * are numbered sequentially "Server N" (baked into the link's #remark so it
     * survives being copied into another client), and the first one is selected.
     *
     * @return how many configs were actually added to My Configs.
     */
    private suspend fun saveResult(
        configs: List<ServerConfig>,
        seenKeys: MutableSet<String>
    ): Int = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext 0
        val store = ConfigStore(applicationContext)

        // WIPE the previous list — a fresh Auto Test gives a brand-new 240 batch.
        runCatching { store.saveServers(emptyList()) }

        // Number them "Server 1..N" and bake the name into the raw link's remark.
        val named = ArrayList<ServerConfig>(configs.size)
        var n = 0
        for (cfg in configs) {
            n++
            val name = "Server $n"
            named.add(
                cfg.copy(
                    remark = name,
                    rawLink = ConfigParser.rewriteRemark(cfg.rawLink, name)
                )
            )
        }

        val added = store.addServers(named)
        if (store.getSelectedId() == null) {
            store.getServers().firstOrNull()?.let { store.setSelectedId(it.id) }
        }
        // Persist the dedup memory so the NEXT batch prefers fresh configs.
        runCatching { SeenConfigStore.save(applicationContext, seenKeys) }
        added
    }

    /**
     * Tween the ProgressBar from its current value to [target] so motion is
     * buttery-smooth even when the probe reports progress in discrete jumps.
     * Never goes backwards.
     */
    private fun animateBarTo(target: Int) {
        val clamped = target.coerceIn(0, 100)
        if (clamped <= bar.progress) return
        barAnimator?.cancel()
        val anim = ObjectAnimator.ofInt(bar, "progress", bar.progress, clamped)
        anim.duration = if (clamped >= 100) 260L else 380L
        anim.interpolator = DecelerateInterpolator()
        barAnimator = anim
        anim.start()
    }

    private fun finishSafely() {
        if (finished) return
        finished = true
        runCatching { probeJob?.cancel() }
        runCatching { finish() }
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { barAnimator?.cancel() }
        runCatching { probeJob?.cancel() }
    }
}
