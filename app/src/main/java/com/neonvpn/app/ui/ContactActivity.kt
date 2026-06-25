package com.neonvpn.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.neonvpn.app.R
import com.neonvpn.app.config.RemoteConfigStore

/**
 * "Contact Us" page. Its text + Telegram id come from the admin panel via
 * [RemoteConfigStore] (so the operator can change them without a new APK).
 *
 * Two buttons:
 *   - Copy id     → copies the Telegram id (e.g. @mx_pr) to the clipboard.
 *   - Send message→ opens Telegram directly at that id so the user can message.
 */
class ContactActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contact)
        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        val cfg = RemoteConfigStore.current().contact
        val textView = findViewById<TextView>(R.id.contact_text)
        val idView = findViewById<TextView>(R.id.contact_id)
        textView.text = cfg.text
        idView.text = cfg.telegramId

        findViewById<View>(R.id.btn_copy_id).setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("telegram_id", cfg.telegramId))
            Toast.makeText(this, getString(R.string.contact_copied), Toast.LENGTH_SHORT).show()
        }

        findViewById<View>(R.id.btn_send_msg).setOnClickListener {
            openTelegram(cfg.telegramUrl, cfg.telegramId)
        }
    }

    /** Open Telegram at the given id. Tries the tg:// deep link first (lands the
     *  user straight in the chat), then falls back to the https t.me link. */
    private fun openTelegram(url: String, id: String) {
        val handle = id.trim().removePrefix("@")
        val httpsUrl = url.ifBlank { "https://t.me/$handle" }
        try {
            val tg = Intent(Intent.ACTION_VIEW, Uri.parse("tg://resolve?domain=$handle")).apply {
                setPackage("org.telegram.messenger")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(tg)
        } catch (_: Throwable) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(httpsUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
            } catch (_: Throwable) {
                Toast.makeText(this, httpsUrl, Toast.LENGTH_LONG).show()
            }
        }
    }
}
