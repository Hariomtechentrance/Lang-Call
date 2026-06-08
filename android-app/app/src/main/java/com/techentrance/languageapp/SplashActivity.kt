package com.techentrance.languageapp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved dark/light mode preference before anything renders
        val appPrefs = getSharedPreferences("langcall_app", Context.MODE_PRIVATE)
        val isDark = appPrefs.getBoolean("dark_mode", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )

        val session = SessionManager(this)
        session.serverUrl?.let { RetrofitClient.configure(it) }

        val onboardingDone = appPrefs.getBoolean("onboarding_done", false)
        val target = when {
            !onboardingDone -> OnboardingActivity::class.java
            session.isLoggedIn -> HomeActivity::class.java
            else -> LoginActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }
}
