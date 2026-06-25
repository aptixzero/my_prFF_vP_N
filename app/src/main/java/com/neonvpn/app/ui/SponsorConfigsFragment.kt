package com.neonvpn.app.ui

import android.graphics.drawable.Animatable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.neonvpn.app.R
import com.neonvpn.app.config.ConfigParser
import com.neonvpn.app.config.Pinger
import com.neonvpn.app.config.RemoteConfig
import com.neonvpn.app.config.RemoteConfigStore
import com.neonvpn.app.config.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URL

/**
 * v3.0 "Sponsor" tab — a RESTRICTED neon display of admin-curated vless/vmess
 * configs published from the panel (the `sponsor.items` block of app_config.json).
 *
 * RESTRICTIONS (enforced — these are paid placements):
 *   • The user can see ONLY the sponsor title, optional media (image/GIF) and
 *     promo text, plus a ping result.
 *   • The user CANNOT copy, view details, see the host / IP, reveal the raw
 *     config, or add it to My Configs. The raw link lives only inside this
 *     fragment for the sole purpose of building a ping outbound.
 *   • The only supported action is PING (per row and "Ping All").
 *
 * Everything is fully admin-controlled and refreshes whenever the panel publishes.
 */
class SponsorConfigsFragment : Fragment() {

    private lateinit var adapter: SponsorAdapter
    private lateinit var heading: TextView
    private lateinit var note: TextView
    private lateinit var btnPingAll: TextView
    private lateinit var progressBox: View
    private lateinit var progressBar: ProgressBar
    private lateinit var progressLabel: TextView
    private lateinit var emptyView: View

    private var job: Job? = null
    @Volatile private var busy = false

    private val listener: (RemoteConfig) -> Unit = { cfg ->
        activity?.runOnUiThread { if (isAdded) bind(cfg) }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_sponsor_configs, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        heading = view.findViewById(R.id.sponsor_heading)
        note = view.findViewById(R.id.sponsor_note)
        btnPingAll = view.findViewById(R.id.btn_ping_all)
        progressBox = view.findViewById(R.id.progress_box)
        progressBar = view.findViewById(R.id.search_progress)
        progressLabel = view.findViewById(R.id.progress_label)
        emptyView = view.findViewById(R.id.empty_view)

        val recycler = view.findViewById<RecyclerView>(R.id.recycler)
        recycler.layoutManager = LinearLayoutManager(requireContext())
        adapter = SponsorAdapter(
            items = mutableListOf(),
            onPing = { item, pos -> pingOne(item, pos) }
        )
        recycler.adapter = adapter

        btnPingAll.setOnClickListener { pingAll() }

        RemoteConfigStore.addListener(listener)   // also fires immediately with current
    }

    override fun onDestroyView() {
        super.onDestroyView()
        RemoteConfigStore.removeListener(listener)
        job?.cancel()
    }

    private fun bind(cfg: RemoteConfig) {
        val sponsor = cfg.sponsor
        if (sponsor.heading.isNotBlank()) heading.text = sponsor.heading
        note.text = if (sponsor.note.isNotBlank()) sponsor.note else getString(R.string.sponsor_hint)

        val items = if (sponsor.enabled) sponsor.items else emptyList()
        adapter.items = items.toMutableList()
        adapter.notifyDataSetChanged()
        refreshEmpty()
        btnPingAll.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
    }

    // -------------------------------------------------------------- pinging
    /** Build an INTERNAL ServerConfig from the sponsor link, used only to ping.
     *  This never leaves the fragment and is never surfaced to the user. */
    private fun internalConfig(item: RemoteConfig.SponsorItem): ServerConfig? =
        try { ConfigParser.parseSingleSafe(item.link) } catch (_: Throwable) { null }

    private fun pingOne(item: RemoteConfig.SponsorItem, position: Int) {
        val cfg = internalConfig(item) ?: run {
            adapter.pingResults[item.id] = Pinger.UNREACHABLE
            if (position >= 0) adapter.notifyItemChanged(position)
            return
        }
        adapter.pingResults[item.id] = Pinger.TESTING
        if (position >= 0) adapter.notifyItemChanged(position)
        viewLifecycleOwner.lifecycleScope.launch {
            val ms = Pinger.ping(cfg)
            adapter.pingResults[item.id] = ms
            val idx = adapter.items.indexOfFirst { it.id == item.id }
            if (idx >= 0) adapter.notifyItemChanged(idx)
        }
    }

    private fun pingAll() {
        if (busy) return
        if (adapter.items.isEmpty()) return
        busy = true
        btnPingAll.alpha = 0.4f
        showProgress(0, getString(R.string.testing_ping))

        val snapshot = adapter.items.toList()
        snapshot.forEach { adapter.pingResults[it.id] = Pinger.TESTING }
        adapter.notifyDataSetChanged()

        job = viewLifecycleOwner.lifecycleScope.launch {
            var done = 0
            for (batch in snapshot.chunked(BATCH_SIZE)) {
                if (!isActive || !isAdded) break
                val results = batch.map { item ->
                    async(Dispatchers.IO) {
                        val cfg = internalConfig(item)
                        item to (if (cfg != null) Pinger.ping(cfg) else Pinger.UNREACHABLE)
                    }
                }.map { it.await() }
                for ((item, ms) in results) adapter.pingResults[item.id] = ms
                done += batch.size
                adapter.notifyDataSetChanged()
                showProgress(
                    done * 100 / snapshot.size.coerceAtLeast(1),
                    "Tested ${done.coerceAtMost(snapshot.size)}/${snapshot.size}"
                )
            }
            busy = false
            btnPingAll.alpha = 1f
            hideProgressDelayed()
        }
    }

    private fun showProgress(pct: Int, label: String) {
        progressBox.visibility = View.VISIBLE
        progressBar.progress = pct.coerceIn(0, 100)
        progressLabel.text = label
    }

    private fun hideProgressDelayed() {
        progressBox.postDelayed({
            if (isAdded && !busy) progressBox.visibility = View.GONE
        }, 1500)
    }

    private fun refreshEmpty() {
        emptyView.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
    }

    companion object {
        private const val BATCH_SIZE = 6
    }
}

/**
 * Restricted sponsor card adapter. Exposes ONLY title / media / promo text /
 * ping result + a ping button. There is intentionally NO copy / delete / detail
 * / select / "add to my configs" affordance, and the host/IP/raw link are never
 * bound to any view.
 */
class SponsorAdapter(
    var items: MutableList<RemoteConfig.SponsorItem>,
    val onPing: (RemoteConfig.SponsorItem, Int) -> Unit
) : RecyclerView.Adapter<SponsorAdapter.VH>() {

    val pingResults = HashMap<String, Long>()   // id -> ms (TESTING / UNREACHABLE / latency)

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val card: View = v.findViewById(R.id.card)
        val media: ImageView = v.findViewById(R.id.media)
        val name: TextView = v.findViewById(R.id.name)
        val detail: TextView = v.findViewById(R.id.detail)
        val badge: TextView = v.findViewById(R.id.badge)
        val promo: TextView = v.findViewById(R.id.promo)
        val ping: ImageView = v.findViewById(R.id.ping)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_sponsor, parent, false)
        return VH(v)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.name.text = item.title
        holder.badge.text = item.protocol.uppercase()

        if (item.text.isNotBlank()) {
            holder.promo.text = item.text
            holder.promo.visibility = View.VISIBLE
        } else holder.promo.visibility = View.GONE

        holder.detail.text = when (val p = pingResults[item.id]) {
            null -> "tap PING to test"
            Pinger.TESTING -> "testing…"
            Pinger.UNREACHABLE, -1L -> "✕ unreachable"
            else -> "● ${p}ms"
        }
        holder.detail.setTextColor(
            when (val p = pingResults[item.id]) {
                null, Pinger.TESTING -> 0xFF8B7BA8.toInt()
                Pinger.UNREACHABLE, -1L -> 0xFFC026D3.toInt()
                else -> when {
                    p < 300 -> 0xFFC084FC.toInt()
                    p < 800 -> 0xFFA855F7.toInt()
                    else -> 0xFF7C3AED.toInt()
                }
            }
        )

        // optional media (image / animated GIF) — loaded async, NO raw config text
        if (item.imageUrl.isNotBlank()) {
            holder.media.visibility = View.VISIBLE
            loadMedia(holder.media, item.imageUrl)
        } else {
            holder.media.visibility = View.GONE
            holder.media.setImageDrawable(null)
        }

        // the ONLY interaction allowed: ping. The card itself does nothing.
        holder.card.setOnClickListener(null)
        holder.card.isClickable = false
        holder.ping.setOnClickListener { onPing(item, holder.bindingAdapterPosition) }
    }

    private fun loadMedia(view: ImageView, url: String) {
        kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
            val bytes = try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 7000; readTimeout = 9000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "ProfessorVPN/3.0")
                }
                conn.inputStream.use { it.readBytes() }
            } catch (_: Throwable) { null }

            val drawable = if (bytes != null) decodeDrawable(view, bytes) else null
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (drawable != null) {
                    view.setImageDrawable(drawable)
                    if (drawable is Animatable) {
                        try { (drawable as Animatable).start() } catch (_: Throwable) {}
                    }
                    view.visibility = View.VISIBLE
                } else {
                    view.visibility = View.GONE
                }
            }
        }
    }

    private fun decodeDrawable(view: ImageView, bytes: ByteArray): android.graphics.drawable.Drawable? =
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
                android.graphics.ImageDecoder.decodeDrawable(src)
            } else {
                val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) android.graphics.drawable.BitmapDrawable(view.resources, bmp) else null
            }
        } catch (_: Throwable) {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bmp != null) android.graphics.drawable.BitmapDrawable(view.resources, bmp) else null
        }
}
