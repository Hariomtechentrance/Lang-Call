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

class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        session = SessionManager(this)

        // Populate language spinner with all Indian languages
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
        val password = binding.etPassword.text.toString().trim()
        val langIndex = binding.spinnerLanguage.selectedItemPosition
        val language = INDIAN_LANGUAGES[langIndex]

        if (name.isEmpty() || email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "Please fill name, email and password", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.length < 6) {
            Toast.makeText(this, "Password must be at least 6 characters", Toast.LENGTH_SHORT).show()
            return
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
                    val msg = response.errorBody()?.string() ?: "Registration failed"
                    Toast.makeText(this@RegisterActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@RegisterActivity, "Cannot connect to server. Is the backend running?", Toast.LENGTH_LONG).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnRegister.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
