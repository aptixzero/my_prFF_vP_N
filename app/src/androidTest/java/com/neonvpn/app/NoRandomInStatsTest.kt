package com.neonvpn.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §4.8 — Golden Rule #2 guard: the connection / config core and the UI must
 * NEVER fabricate stats, pings or any user-facing number with a random source.
 *
 * Real Professor VPN figures come from the live tunnel (NeonVpnService traffic
 * counters) and real network probes (Pinger). A `kotlin.random.Random` or
 * `java.util.Random` (or `Math.random()`) anywhere under `config/`, `service/`,
 * `stats/` or `ui/` is therefore a release blocker.
 *
 * The build copies those production `.kt` sources into the androidTest APK's
 * assets (see `collectSourceScan` in app/build.gradle.kts). This instrumented
 * test reads them back on device and fails if any forbidden RNG token appears
 * on a line that is not a comment.
 */
@RunWith(AndroidJUnit4::class)
class NoRandomInStatsTest {

    private val assets =
        InstrumentationRegistry.getInstrumentation().context.assets

    /** Forbidden tokens. Word-boundaries keep it from matching unrelated names. */
    private val forbidden = listOf(
        Regex("""\bkotlin\.random\.Random\b"""),
        Regex("""\bjava\.util\.Random\b"""),
        Regex("""\bMath\.random\s*\("""),
        // bare `Random(` / `Random.` constructor or static use (either import)
        Regex("""(?<![A-Za-z0-9_.])Random\s*\("""),
        Regex("""(?<![A-Za-z0-9_.])Random\.""")
    )

    @Test
    fun scannedSourcesArePresent() {
        val files = listAllKt(ROOT)
        // Sanity: the copy task must have produced files, otherwise the guard
        // would silently "pass" by scanning nothing.
        assertTrue(
            "No source files found under assets/$ROOT — the collectSourceScan " +
                "copy task did not run. The anti-Random guard cannot be trusted.",
            files.isNotEmpty()
        )
    }

    @Test
    fun noRandomInCoreOrUi() {
        val files = listAllKt(ROOT)
        val violations = mutableListOf<String>()

        for (path in files) {
            val text = readAsset(path)
            text.lineSequence().forEachIndexed { idx, rawLine ->
                val line = stripComment(rawLine)
                if (line.isBlank()) return@forEachIndexed
                for (rx in forbidden) {
                    if (rx.containsMatchIn(line)) {
                        violations += "$path:${idx + 1}: ${rawLine.trim()}"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            fail(
                "Forbidden random-number usage found in core/ui sources " +
                    "(Golden Rule #2 — stats must be real):\n" +
                    violations.joinToString("\n")
            )
        }
    }

    // --- helpers -----------------------------------------------------------

    /** Recursively list every .kt asset under [dir]. */
    private fun listAllKt(dir: String): List<String> {
        val out = mutableListOf<String>()
        val entries = try { assets.list(dir) ?: emptyArray() } catch (_: Throwable) { emptyArray() }
        for (e in entries) {
            val child = "$dir/$e"
            val sub = try { assets.list(child) } catch (_: Throwable) { null }
            if (sub != null && sub.isNotEmpty()) {
                out += listAllKt(child)
            } else if (e.endsWith(".kt")) {
                out += child
            }
        }
        return out
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader().use { it.readText() }

    /**
     * Remove `// line comments` so a comment that merely *mentions* Random
     * (like this test's own design notes echoed in source docs) is never a
     * false positive. Block comments are handled coarsely by dropping anything
     * after `/*` on a line — adequate for the single-line doc style used here.
     */
    private fun stripComment(line: String): String {
        var s = line
        val lc = s.indexOf("//")
        if (lc >= 0) s = s.substring(0, lc)
        val bc = s.indexOf("/*")
        if (bc >= 0) s = s.substring(0, bc)
        // strip leading `*` of a kdoc continuation line
        val t = s.trimStart()
        if (t.startsWith("*")) return ""
        return s
    }

    companion object {
        private const val ROOT = "source-scan"
    }
}
