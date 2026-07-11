package com.neonvpn.app.config

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * v4.6 — FAST "Auto Test" connectivity probe.
 *
 * When the user taps AUTO TEST a small page opens with just a progress bar. That
 * page runs THIS probe, which answers one question quickly:
 *
 *   "Which of the 50 live sources can this user actually reach right now, and can
 *    we find a WORKING vless + a WORKING vmess through them?"
 *
 * Behaviour (per the v4.6 brief):
 *   • It scans the [LiveSources] a few at a time (bounded concurrency) rather than
 *     all 50 at once, so it's fast and light.
 *   • As soon as it finds ONE reachable VLESS and ONE reachable VMESS config
 *     (confirmed by the same censored-endpoint [Pinger] the real connect uses),
 *     it STOPS immediately and returns them — the page closes and the engine uses
 *     them. It does NOT keep scanning the rest.
 *   • It is hard time-boxed ([BUDGET_MS]) so it can never hang the page.
 *   • Fully exception-safe: a dead source / malformed line never crashes it.
 */
object ConnectivityProbe {

    private const val TAG = "ConnectivityProbe"

    /** Whole-probe wall-clock ceiling — the page never waits longer than this. */
    const val BUDGET_MS = 25_000L

    /**
     * v5.8 — how long we spend actively trying to PING a candidate through the
     * live Xray core before we simply accept the successfully-FETCHED config as
     * good. On Iran's weak/unstable mobile links a real proxied ping frequently
     * can't complete inside the whole-probe budget, which made the probe return
     * empty ("connection error") even though the 50 source feeds opened fine.
     * Reaching a source + extracting a valid vless/vmess is itself proof the user
     * can talk to the feeds, so once that budget elapses we add what we fetched.
     */
    private const val PING_PHASE_BUDGET_MS = 9_000L

    /** How many source feeds we fetch at once. */
    private const val FETCH_CONCURRENCY = 6

    /** How many candidate configs we ping at once. */
    private const val PING_CONCURRENCY = 6

    data class Result(
        /** A vless we could confirm works (fell back to a fetched one on timeout). */
        val vless: ServerConfig?,
        /** A vmess we could confirm works (fell back to a fetched one on timeout). */
        val vmess: ServerConfig?,
        /**
         * v5.8 — true if we managed to OPEN at least one source feed and pull a
         * valid config out of it. This is the real "did we reach the internet /
         * the sources" signal. `ok` is derived from it so we add configs whenever
         * the feeds are reachable, whether or not a live ping completed in time.
         */
        val reachedSource: Boolean = (vless != null || vmess != null)
    ) {
        /**
         * v5.8 — we have something to add whenever a source was reachable and we
         * extracted at least one config. We deliberately do NOT require a live
         * ping to succeed (see [PING_PHASE_BUDGET_MS]); the user pings later,
         * manually, from My Configs.
         */
        val ok: Boolean get() = (vless != null || vmess != null)
    }

    /**
     * Run the probe. Emits coarse progress (0..100) through [onProgress] for the
     * page's bar. Returns the first working vless/vmess pair it can confirm.
     */
    suspend fun probe(
        ctx: Context,
        onProgress: (percent: Int) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val res = withTimeoutOrNull(BUDGET_MS) { runProbe(ctx, onProgress) }
        res ?: Result(null, null, reachedSource = false)
    }

    private suspend fun runProbe(
        ctx: Context,
        onProgress: (Int) -> Unit
    ): Result = coroutineScope {
        // Interleave sources so we hit vless / vmess feeds early. Walk in pairs.
        val vlessSrc = LiveSources.VLESS
        val vmessSrc = LiveSources.VMESS
        val pairs = maxOf(vlessSrc.size, vmessSrc.size)

        // Thread-safe result holders (java.util.concurrent atomic refs).
        // found*  = a config we CONFIRMED with a live ping (best).
        // fetched* = the FIRST valid config we merely pulled from a reachable
        //            source (fallback — proves the feeds are reachable even when
        //            a live ping can't complete on a weak Iranian link).
        val foundVless = java.util.concurrent.atomic.AtomicReference<ServerConfig?>(null)
        val foundVmess = java.util.concurrent.atomic.AtomicReference<ServerConfig?>(null)
        val fetchedVless = java.util.concurrent.atomic.AtomicReference<ServerConfig?>(null)
        val fetchedVmess = java.util.concurrent.atomic.AtomicReference<ServerConfig?>(null)
        val reachedAnySource = java.util.concurrent.atomic.AtomicBoolean(false)

        val fetchGate = Semaphore(FETCH_CONCURRENCY)
        val pingGate = Semaphore(PING_CONCURRENCY)
        val pingPhaseStartNs = System.nanoTime()
        fun pingBudgetLeft(): Boolean =
            (System.nanoTime() - pingPhaseStartNs) / 1_000_000L < PING_PHASE_BUDGET_MS

        // ---- SMOOTH PROGRESS ----
        // The bar used to jump 2 → 100 because progress only advanced when a whole
        // source finished (which can be near-instant on failure or seconds on
        // success). We now drive the bar from a steady time-based ticker so it
        // ALWAYS moves, and we let real work "pull" it forward when it's ahead of
        // the clock. The ticker eases toward 95% over the budget window; the final
        // 100% is emitted when the probe actually completes.
        val displayed = AtomicInteger(0)
        // `workFloor` is raised by real progress events so genuine work can push
        // the bar ahead of the time estimate (but never above 95 until done).
        val workFloor = AtomicInteger(0)
        val startNs = System.nanoTime()

        fun emit(target: Int) {
            val clamped = target.coerceIn(0, 95)
            // Monotonic: never let the bar go backwards.
            while (true) {
                val cur = displayed.get()
                if (clamped <= cur) return
                if (displayed.compareAndSet(cur, clamped)) {
                    onProgress(clamped)
                    return
                }
            }
        }

        // Steady ticker: every 120ms nudge the bar toward a time-based estimate,
        // eased so it decelerates as it approaches 95%. Runs until cancelled.
        val ticker = launch {
            while (coroutineContext.isActive) {
                val elapsed = (System.nanoTime() - startNs) / 1_000_000L
                val frac = (elapsed.toFloat() / BUDGET_MS.toFloat()).coerceIn(0f, 1f)
                // Ease-out curve: fast at first, gently slowing toward the cap.
                val eased = (1f - (1f - frac) * (1f - frac))
                val timeTarget = (eased * 92f).toInt() + 2   // 2 .. 94 over the window
                emit(maxOf(timeTarget, workFloor.get()))
                delay(120)
            }
        }
        emit(2)

        // Test a handful of candidate links from one source. v5.8: the FIRST
        // valid parsed link is recorded as the "fetched" fallback (proves the
        // source is reachable); we then try to confirm one with a live ping
        // ONLY while the ping-phase budget lasts, so a weak link can't stall us.
        suspend fun testSource(src: LiveSources.Src, isVless: Boolean) {
            if ((isVless && foundVless.get() != null) || (!isVless && foundVmess.get() != null)) return
            val body = fetchGate.withPermit {
                if (!coroutineContext.isActive) return
                try { SourceFetcher.fetch(src.url) } catch (_: Throwable) { null }
            } ?: return
            val links = try { SourceFetcher.extractLinks(body, src.kind, limit = 24) } catch (_: Throwable) { emptyList() }
            if (links.isEmpty()) return

            // We opened a source and it yielded usable links → the feeds ARE
            // reachable. Record the first valid config as the fallback right away.
            for (link in links) {
                val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null } ?: continue
                reachedAnySource.set(true)
                if (isVless) fetchedVless.compareAndSet(null, cfg)
                else fetchedVmess.compareAndSet(null, cfg)
                break
            }

            // Try to CONFIRM one with a real proxied ping — but only while we
            // still have ping-phase budget. If we run out, the fetched fallback
            // above is what we'll use; we do NOT block the whole probe on it.
            coroutineScope {
                for (link in links) {
                    if ((isVless && foundVless.get() != null) || (!isVless && foundVmess.get() != null)) break
                    if (!pingBudgetLeft()) break
                    launch {
                        if ((isVless && foundVless.get() != null) || (!isVless && foundVmess.get() != null)) return@launch
                        if (!pingBudgetLeft()) return@launch
                        val cfg = try { ConfigParser.parseSingleSafe(link) } catch (_: Throwable) { null } ?: return@launch
                        val ms = pingGate.withPermit {
                            if (!coroutineContext.isActive || !pingBudgetLeft()) return@withPermit -1L
                            try { Pinger.ping(cfg) } catch (_: Throwable) { -1L }
                        }
                        if (ms in 1..8_000L) {
                            if (isVless) foundVless.compareAndSet(null, cfg)
                            else foundVmess.compareAndSet(null, cfg)
                        }
                    }
                }
            }
            // Real progress "pull": raise the work floor a little each time a
            // source finishes so genuine work can run ahead of the time estimate.
            val step = (90 / maxOf(pairs, 1)).coerceAtLeast(1)
            val nf = workFloor.addAndGet(step).coerceAtMost(94)
            emit(nf)
        }

        // Walk source pairs. v5.8: keep going until we have a CONFIRMED (pinged)
        // vless+vmess, OR we run out of ping budget once we already have a
        // fetched fallback for both (no point burning the rest of the wall-clock
        // pinging when the feeds are clearly reachable and the user pings later).
        outer@ for (i in 0 until pairs) {
            val haveConfirmedBoth = foundVless.get() != null && foundVmess.get() != null
            if (haveConfirmedBoth) break@outer
            val haveFetchedBoth = fetchedVless.get() != null && fetchedVmess.get() != null
            if (haveFetchedBoth && !pingBudgetLeft()) break@outer

            val jobs = ArrayList<kotlinx.coroutines.Deferred<Unit>>()
            if (i < vlessSrc.size && foundVless.get() == null) {
                jobs.add(async { testSource(vlessSrc[i], isVless = true) })
            }
            if (i < vmessSrc.size && foundVmess.get() == null) {
                jobs.add(async { testSource(vmessSrc[i], isVless = false) })
            }
            jobs.forEach { runCatching { it.await() } }
        }

        ticker.cancel()
        onProgress(100)

        // v5.8 — prefer a ping-confirmed config, else fall back to a config we
        // simply fetched from a reachable source. Only when NOTHING was fetched
        // (i.e. not one of the 50 feeds could be opened) do we report failure.
        val resultVless = foundVless.get() ?: fetchedVless.get()
        val resultVmess = foundVmess.get() ?: fetchedVmess.get()
        val reached = reachedAnySource.get() || resultVless != null || resultVmess != null
        Log.i(
            TAG,
            "probe done: reached=$reached, vless=${resultVless != null}(ping=${foundVless.get() != null}), " +
                "vmess=${resultVmess != null}(ping=${foundVmess.get() != null})"
        )
        Result(resultVless, resultVmess, reachedSource = reached)
    }
}
