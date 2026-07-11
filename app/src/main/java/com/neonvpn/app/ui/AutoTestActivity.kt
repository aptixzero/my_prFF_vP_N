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
import com.neonvpn.app.config.SeenConfigStore
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v5.9 — AUTO TEST connectivity page.
 *
 * A deliberately minimal screen: a title + a progress bar. On open it runs the
 * [ConnectivityProbe] which walks the live sources and pulls a FULL fresh batch
 * of configs. The progress bar reflects the REAL work of reaching each source —
 * it fills as configs are actually collected, NOT on a random timer.
 *
 * When the bar reaches 100% the collected configs are added to My Configs the
 * SAME instant (the wait the user spends watching the bar IS the time we spend
 * finding real configs), the page closes, and the continuous [AutoTestEngine]
 * is started to keep filling My Configs 240-at-a-time in the background.
 *
 * There is NO false "connection error": we only show it when NOT ONE source
 * could be opened (a genuine offline state).
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

            // v5.9 — the progress bar has reached 100% (real source work done).
            // Add the collected configs to My Configs RIGHT NOW so they are
            // guaranteed present the moment the page closes. Configs are added
            // whenever a source was reachable — no live ping is required (on
            // Iran's weak links a proxied ping often can't finish inside the
            // budget, but reaching a feed and pulling valid vless/vmess is itself
            // proof the user is online). The user pings later from My Configs.
            if (result.ok) {
                val addedCount = runCatching { saveResult(result.configs, seenKeys) }.getOrDefault(0)

                // Kick off the continuous engine to keep filling My Configs with
                // more working configs in the background.
                runCatching { AutoTestEngine.start(applicationContext) }

                runOnUiThread {
                    if (addedCount > 0) toast(getString(R.string.probe_saved, addedCount))
                }
                finishSafely()
            } else {
                // Nothing could be fetched from ANY source — the user genuinely
                // has no path to the sources right now. Show the error, but STILL
                // start the background engine so that the moment the (unstable
                // Iranian) connection recovers, configs start arriving without the
                // user having to tap Auto Test again.
                runCatching { AutoTestEngine.start(applicationContext) }
                runOnUiThread {
                    if (!isFinishing) {
                        animateBarTo(100)
                        percent.text = "100%"
                        toast(getString(R.string.probe_error))
                    }
                }
                kotlinx.coroutines.delay(1_400)
                finishSafely()
            }
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
