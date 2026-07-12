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
import com.neonvpn.app.config.FreeConfigStore
import com.neonvpn.app.config.PingService
import com.neonvpn.app.config.PingStore
import com.neonvpn.app.config.SeenConfigStore
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v6.1 — AUTO TEST connectivity page (two-phase, no fake error).
 *
 * A deliberately minimal screen: a title + a progress bar. On open it runs the
 * two-phase [ConnectivityProbe]:
 *
 *   0 % → 60 %  : a REAL connection test against the live source feeds — it opens
 *                 each source and finds which one the user can actually reach, and
 *                 bonds to it. The bar advances as each source is genuinely probed
 *                 (no random timer, no `delay()`). v6.1: the bar does NOT lock on
 *                 a single number — the probe keeps re-trying so long as the link
 *                 recovers, and only pauses (holds) while the internet is fully
 *                 down, resuming the instant it is reachable again.
 *   60 % → 100 %: it pulls a FULL fresh 240 batch of configs FROM THAT reached
 *                 source, in the background (the user does not see the Free tab).
 *                 The bar climbs as configs are actually collected.
 *
 * v6.1 — THE BIG FIX: the collected 240 configs are added to **FREE CONFIGS**
 * (not My Configs). My Configs is the user's PERMANENT, hand-curated bucket — it
 * must ONLY ever contain configs the user pasted/added manually, plus the ones
 * that actually ping (the working ones), which the continuous [AutoTestEngine]
 * copies in automatically after they pass the ping test. Dumping all 240 raw
 * configs into My Configs (the pre-6.1 bug) was wrong.
 *
 * So at 100 %: the 240 fresh configs are placed in the Free list, the page
 * closes, and the continuous [AutoTestEngine] is (re)started. The engine then
 * pings the whole Free batch and, for EACH config that returns a real ping,
 * copies it — live, one by one — into My Configs. When the 240 are exhausted it
 * REPLACES the Free list with a brand-new 240 batch and repeats. My Configs is
 * never wiped by this flow.
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

            // v6.1 — the bar has reached 100 % (real source work done). Place the
            // collected 240 configs into FREE CONFIGS right now (NOT My Configs) so
            // they are present the moment the page closes. We NEVER show a
            // "connection error": whether we collected configs or not, we start the
            // background engine (so configs keep arriving as the unstable link
            // recovers) and close quietly.
            val addedCount = if (result.configs.isNotEmpty()) {
                runCatching { saveResult(result.configs, seenKeys) }.getOrDefault(0)
            } else 0

            // Always (RE)start the continuous engine — it pings the fresh Free
            // batch and copies ONLY the configs that actually ping into My Configs,
            // 240-at-a-time from the SAME bonded source. Using restart() (not
            // start()) means opening this page again — even while a previous run is
            // still going — never wedges the engine or leaves two loops fighting; it
            // always begins a fresh, clean search+ping+move cycle. The engine also
            // owns the Free-list ping sweep, so we do NOT ping here (pinging both
            // here and in the engine would double-drive the same list).
            runCatching { AutoTestEngine.restart(applicationContext) }

            if (addedCount > 0) {
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
     * v6.1 — place the freshly-probed batch into FREE CONFIGS (NOT My Configs).
     *
     * A fresh Auto Test REPLACES the previous FREE list with the new 240 batch —
     * this is the "free configs like the older versions" behaviour: the Free tab
     * shows exactly the batch currently under test. Configs are numbered
     * sequentially "Server N" (baked into the link's #remark so the number
     * survives being copied into another client).
     *
     * CRITICAL: My Configs is NOT touched here. It is the user's permanent bucket
     * (manual pastes + configs that actually ping). Only the continuous
     * [AutoTestEngine] adds to My Configs, and only for configs that pass the ping
     * test. So a fresh Auto Test never wipes or bloats My Configs.
     *
     * @return how many configs were actually placed in the Free list.
     */
    private suspend fun saveResult(
        configs: List<ServerConfig>,
        seenKeys: MutableSet<String>
    ): Int = withContext(Dispatchers.IO) {
        if (configs.isEmpty()) return@withContext 0
        val freeStore = FreeConfigStore(applicationContext)

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

        // REPLACE the previous Free batch with this brand-new one (old batch wiped,
        // new 240 take their place) — exactly like the older versions' Free tab.
        runCatching { freeStore.replaceAll(named) }

        // Clear any stale ping results for rows that no longer exist so the Free
        // bucket's badges reflect only the current batch.
        runCatching { PingService.clear(applicationContext, PingStore.FREE) }

        // Persist the dedup memory so the NEXT batch prefers fresh configs.
        runCatching { SeenConfigStore.save(applicationContext, seenKeys) }
        named.size
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
