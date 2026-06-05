package com.techentrance.languageapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SessionManager(this)
        // Restore saved server URL so real-device APKs use the correct backend
        session.serverUrl?.let { RetrofitClient.configure(it) }
        val target = if (session.isLoggedIn) HomeActivity::class.java else LoginActivity::class.java
        startActivity(Intent(this, target))
        finish()
    }
}
