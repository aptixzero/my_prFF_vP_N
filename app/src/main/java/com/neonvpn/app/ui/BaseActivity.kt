package com.neonvpn.app.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.neonvpn.app.R
import com.neonvpn.app.util.AppPrefs

/**
 * Common base for every Activity: applies the user-selected language (via a
 * wrapped Context) and the user-selected theme (dark = default / light) BEFORE
 * the view hierarchy is inflated, so language + theme changes take effect on the
 * very next screen with a simple recreate().
 */
abstract class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppPrefs.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Theme MUST be set before super.onCreate / setContentView.
        if (AppPrefs.isDark(this)) {
            setTheme(R.style.Theme_ProfessorVPN)
        } else {
            setTheme(R.style.Theme_ProfessorVPN_Light)
        }
        super.onCreate(savedInstanceState)
    }
}
