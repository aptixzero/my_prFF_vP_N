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
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v4.6 — AUTO TEST connectivity page.
 *
 * A deliberately minimal screen: a title + a progress bar (no per-source text).
 * On open it runs the fast [ConnectivityProbe] which scans the 50 live sources a
 * few at a time and STOPS at the FIRST reachable vless + vmess pair. Those are
 * saved into My Configs (auto-selected), the page closes, and the continuous
 * [AutoTestEngine] is started to keep filling My Configs 240-at-a-time in the
 * background.
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
            val result = try {
                ConnectivityProbe.probe(applicationContext) { p ->
                    runOnUiThread {
                        if (!isFinishing) {
                            animateBarTo(p)
                            percent.text = "$p%"
                        }
                    }
                }
            } catch (_: Throwable) {
                ConnectivityProbe.Result(null, null)
            }

            // v5.7 — the connectivity test scan line has now FULLY finished.
            // Decide here (not later): only if we actually confirmed at least one
            // reachable vless/vmess do we save the configs and start the engine.
            if (result.ok) {
                // Save the confirmed working pair straight into My Configs, and
                // wait for the write to complete BEFORE we leave the page so the
                // configs are guaranteed present when the user lands on My Configs.
                val addedCount = runCatching { saveResult(result) }.getOrDefault(0)

                // Kick off the continuous engine to keep filling My Configs.
                runCatching { AutoTestEngine.start(applicationContext) }

                runOnUiThread {
                    if (addedCount > 0) {
                        toast(getString(R.string.probe_saved, addedCount))
                    }
                }
                // Close the page and return to Home — the engine keeps working.
                finishSafely()
            } else {
                // v5.7 — NO source responded (no reachable config found). Tell the
                // user clearly ("connection error, please try again") and do NOT
                // silently start the background engine — nothing was added.
                runOnUiThread {
                    if (!isFinishing) {
                        animateBarTo(100)
                        percent.text = "100%"
                        toast(getString(R.string.probe_error))
                    }
                }
                // Give the toast a beat to appear before returning home.
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

    /** @return how many configs were actually added to My Configs. */
    private suspend fun saveResult(result: ConnectivityProbe.Result): Int = withContext(Dispatchers.IO) {
        val store = ConfigStore(applicationContext)
        val toAdd = ArrayList<ServerConfig>(2)
        // Number them generically continuing from the current list size; give
        // neutral Server labels. v5.1 — bake the name into the link's #remark so
        // it stays "Server N" when copied into any other v2ray client.
        var n = store.getServers().size
        result.vless?.let {
            n++
            val name = "Server $n"
            toAdd.add(it.copy(
                remark = name,
                rawLink = ConfigParser.rewriteRemark(it.rawLink, name)
            ))
        }
        result.vmess?.let {
            n++
            val name = "Server $n"
            toAdd.add(it.copy(
                remark = name,
                rawLink = ConfigParser.rewriteRemark(it.rawLink, name)
            ))
        }
        if (toAdd.isNotEmpty()) {
            val added = store.addServers(toAdd)
            if (store.getSelectedId() == null) {
                store.getServers().firstOrNull()?.let { store.setSelectedId(it.id) }
            }
            added
        } else {
            0
        }
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
