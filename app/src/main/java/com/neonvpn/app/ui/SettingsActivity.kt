package com.neonvpn.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import com.neonvpn.app.BuildConfig
import com.neonvpn.app.R
import com.neonvpn.app.service.XrayManager
import com.neonvpn.app.util.AppPrefs

/**
 * Settings page (opened from the hamburger menu). Lets the user switch the
 * theme (dark default / light) and the language (Persian / English). Changing
 * either recreates the activity so it applies immediately, and sets a flag so
 * MainActivity rebuilds itself when the user returns.
 *
 * v6.2 — the Privacy / paste-history section has been REMOVED from the UI per
 * the brief. The paste-history feature stays enabled by default (in AppPrefs)
 * so "Paste From Clipboard" keeps scanning the accumulated on-device history of
 * pasted links and adding every vless/vmess it finds — it just no longer shows
 * a toggle or a "clear paste history" button. The user never sees the privacy
 * controls; the behaviour is always on.
 */
class SettingsActivity : BaseActivity() {

    private lateinit var optThemeDark: TextView
    private lateinit var optThemeLight: TextView
    private lateinit var optLangFa: TextView
    private lateinit var optLangEn: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        optThemeDark = findViewById(R.id.opt_theme_dark)
        optThemeLight = findViewById(R.id.opt_theme_light)
        optLangFa = findViewById(R.id.opt_lang_fa)
        optLangEn = findViewById(R.id.opt_lang_en)

        optThemeDark.setOnClickListener { applyTheme(AppPrefs.THEME_DARK) }
        optThemeLight.setOnClickListener { applyTheme(AppPrefs.THEME_LIGHT) }
        optLangFa.setOnClickListener { applyLanguage(AppPrefs.LANG_FA) }
        optLangEn.setOnClickListener { applyLanguage(AppPrefs.LANG_EN) }

        // v6.2 — Privacy section removed from the UI. Paste history stays ON by
        // default (AppPrefs.isPasteHistoryEnabled defaults to true), so the
        // clipboard-paste scan of accumulated history keeps working invisibly.
        // We no longer bind the switch / clear button (they are gone from the
        // layout), and we make sure the default stays enabled.
        runCatching { AppPrefs.setPasteHistoryEnabled(this, true) }

        highlightSelections()

        val version = try {
            XrayManager.cachedVersion()
        } catch (_: Throwable) { "?" }
        findViewById<TextView>(R.id.about_version).text =
            "${getString(R.string.settings_version)}: ${BuildConfig.VERSION_NAME}"
        findViewById<TextView>(R.id.about_core).text =
            "${getString(R.string.settings_core)}: v$version"
    }

    private fun highlightSelections() {
        val dark = AppPrefs.isDark(this)
        optThemeDark.isActivated = dark
        optThemeLight.isActivated = !dark
        val fa = AppPrefs.getLanguage(this) == AppPrefs.LANG_FA
        optLangFa.isActivated = fa
        optLangEn.isActivated = !fa
    }

    private fun applyTheme(theme: String) {
        if (AppPrefs.getTheme(this) == theme) return
        AppPrefs.setTheme(this, theme)
        markUiDirty()
        recreate()
    }

    private fun applyLanguage(lang: String) {
        if (AppPrefs.getLanguage(this) == lang) return
        AppPrefs.setLanguage(this, lang)
        markUiDirty()
        recreate()
    }

    /** Tell MainActivity (and any parent) that it must rebuild on resume. */
    private fun markUiDirty() {
        getSharedPreferences("neonvpn_prefs", MODE_PRIVATE)
            .edit().putBoolean("ui_dirty", true).apply()
    }
}
