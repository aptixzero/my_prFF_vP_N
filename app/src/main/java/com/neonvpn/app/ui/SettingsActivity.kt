package com.neonvpn.app.ui

import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.neonvpn.app.BuildConfig
import com.neonvpn.app.R
import com.neonvpn.app.config.PasteHistoryStore
import com.neonvpn.app.service.XrayManager
import com.neonvpn.app.util.AppPrefs

/**
 * Settings page (opened from the hamburger menu). Lets the user switch the
 * theme (dark default / light) and the language (Persian / English). Changing
 * either recreates the activity so it applies immediately, and sets a flag so
 * MainActivity rebuilds itself when the user returns.
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

        // Privacy: opt-in paste history (v3.8). Default OFF, local-only.
        val pasteSwitch = findViewById<SwitchCompat>(R.id.switch_paste_history)
        pasteSwitch.isChecked = AppPrefs.isPasteHistoryEnabled(this)
        pasteSwitch.setOnCheckedChangeListener { _, enabled ->
            AppPrefs.setPasteHistoryEnabled(this, enabled)
            // Turning the feature OFF must immediately wipe any stored links.
            if (!enabled) PasteHistoryStore(this).clear()
        }
        findViewById<View>(R.id.btn_clear_paste_history).setOnClickListener {
            PasteHistoryStore(this).clear()
            Toast.makeText(this, R.string.paste_history_cleared, Toast.LENGTH_SHORT).show()
        }

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
