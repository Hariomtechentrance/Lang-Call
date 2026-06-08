package com.techentrance.languageapp

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.techentrance.languageapp.data.ChangePasswordRequest
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager
import com.techentrance.languageapp.databinding.ActivityChangePasswordBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class ChangePasswordActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChangePasswordBinding
    private lateinit var session: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChangePasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        session = SessionManager(this)

        binding.btnBack.setOnClickListener { finish() }
        binding.btnChangePassword.setOnClickListener { doChangePassword() }
    }

    private fun doChangePassword() {
        val current = binding.etCurrentPassword.text.toString()
        val newPass = binding.etNewPassword.text.toString()
        val confirm = binding.etConfirmPassword.text.toString()

        if (current.isEmpty() || newPass.isEmpty() || confirm.isEmpty()) {
            toast("Please fill all fields"); return
        }
        if (newPass.length < 8) {
            toast("New password must be at least 8 characters"); return
        }
        if (!newPass.any { it.isDigit() }) {
            toast("New password must contain at least one number"); return
        }
        if (newPass != confirm) {
            toast("New passwords do not match"); return
        }
        if (current == newPass) {
            toast("New password must be different from current password"); return
        }

        setLoading(true)
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.changePassword(
                    session.bearerToken,
                    ChangePasswordRequest(current, newPass),
                )
                if (resp.isSuccessful && resp.body() != null) {
                    // Update token with the new one returned (all other sessions are revoked)
                    session.token = resp.body()!!.new_token
                    toast("Password changed successfully ✓")
                    finish()
                } else {
                    toast(parseError(resp.errorBody()?.string()))
                }
            } catch (_: Exception) {
                toast("Cannot connect to server")
            } finally {
                setLoading(false)
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun parseError(body: String?): String {
        if (body.isNullOrEmpty()) return "Failed to change password"
        return try { JSONObject(body).getString("detail") } catch (_: Exception) { body }
    }

    private fun setLoading(loading: Boolean) {
        binding.btnChangePassword.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}
