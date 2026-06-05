package com.techentrance.languageapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.techentrance.languageapp.data.*
import com.techentrance.languageapp.databinding.ActivityRegisterBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        session = SessionManager(this)

        val labels = INDIAN_LANGUAGES.map { LANGUAGE_LABELS[it] ?: it }
        binding.spinnerLanguage.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)

        binding.btnRegister.setOnClickListener { doRegister() }
        binding.tvGoLogin.setOnClickListener { finish() }
    }

    private fun doRegister() {
        val name = binding.etName.text.toString().trim()
        val email = binding.etEmail.text.toString().trim()
        val phone = binding.etPhone.text.toString().trim().ifEmpty { null }
        // Do NOT trim password — spaces can be intentional
        val password = binding.etPassword.text.toString()
        val langIndex = binding.spinnerLanguage.selectedItemPosition
        val language = INDIAN_LANGUAGES[langIndex]

        // ── Client-side validation (also validated server-side) ─────────────
        if (name.length < 2) {
            showError("Name must be at least 2 characters"); return
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            showError("Enter a valid email address"); return
        }
        if (password.length < 8) {
            showError("Password must be at least 8 characters"); return
        }
        if (!password.any { it.isDigit() }) {
            showError("Password must contain at least one number (e.g. myPass1)"); return
        }
        if (!password.any { it.isLetter() }) {
            showError("Password must contain at least one letter"); return
        }
        if (phone != null && !phone.matches(Regex("^\\+?[0-9]{7,15}$"))) {
            showError("Phone number must be 7-15 digits (optionally starting with +)"); return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val response = RetrofitClient.api.register(
                    RegisterRequest(name, email, phone, password, language)
                )
                if (response.isSuccessful && response.body() != null) {
                    session.save(response.body()!!)
                    startActivity(Intent(this@RegisterActivity, HomeActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    })
                } else {
                    showError(parseError(response.errorBody()?.string()))
                }
            } catch (e: Exception) {
                showError("Cannot connect to server. Is the backend running?")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun showError(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun parseError(body: String?): String {
        if (body.isNullOrEmpty()) return "Registration failed"
        return try {
            val json = JSONObject(body)
            // FastAPI validation errors come as {"detail": [{"msg": "..."}]} or {"detail": "..."}
            val detail = json.get("detail")
            if (detail is org.json.JSONArray && detail.length() > 0) {
                detail.getJSONObject(0).optString("msg", body)
            } else {
                detail.toString()
            }
        } catch (_: Exception) {
            body
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
