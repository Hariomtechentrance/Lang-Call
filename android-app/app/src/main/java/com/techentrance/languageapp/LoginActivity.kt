package com.techentrance.languageapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.techentrance.languageapp.data.LoginRequest
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager
import com.techentrance.languageapp.databinding.ActivityLoginBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        session = SessionManager(this)

        binding.btnLogin.setOnClickListener { doLogin() }
        binding.tvGoRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
        }
    }

    private fun doLogin() {
        val email = binding.etEmail.text.toString().trim()
        // Never trim passwords — the user may have intentionally included spaces
        val password = binding.etPassword.text.toString()

        if (email.isEmpty()) {
            Toast.makeText(this, "Enter your email", Toast.LENGTH_SHORT).show(); return
        }
        if (password.isEmpty()) {
            Toast.makeText(this, "Enter your password", Toast.LENGTH_SHORT).show(); return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.login(LoginRequest(email, password))
                if (response.isSuccessful && response.body() != null) {
                    session.save(response.body()!!)
                    startActivity(Intent(this@LoginActivity, HomeActivity::class.java))
                    finish()
                } else {
                    // Show the exact server message (e.g. remaining attempts, lockout duration)
                    Toast.makeText(this@LoginActivity, parseError(response.errorBody()?.string()), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@LoginActivity, "Cannot connect to server", Toast.LENGTH_SHORT).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun parseError(body: String?): String {
        if (body.isNullOrEmpty()) return "Login failed"
        return try {
            JSONObject(body).getString("detail")
        } catch (_: Exception) {
            body
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnLogin.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
