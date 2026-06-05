package com.techentrance.languageapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.techentrance.languageapp.data.*
import com.techentrance.languageapp.databinding.ActivityHomeBinding
import kotlinx.coroutines.launch
import org.json.JSONObject

class HomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL_PHONE = "prefill_phone"
        private const val TAG = "HomeActivity"
    }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var session: SessionManager

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchNotificationService()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        session = SessionManager(this)

        populateUI()
        refreshProfileFromServer()   // sync session cache with DB on every launch
        requestNotifPermissionThenStartService()

        intent.getStringExtra(EXTRA_PREFILL_PHONE)?.let { binding.etCallPhone.setText(it) }

        binding.btnCall.setOnClickListener { initiateCall() }
        binding.btnSavePhone.setOnClickListener { savePhone() }
        binding.btnSaveLanguage.setOnClickListener { saveLanguage() }
        binding.btnSettings.setOnClickListener { showServerUrlDialog() }
        binding.btnCallHistory.setOnClickListener { startActivity(Intent(this, CallHistoryActivity::class.java)) }
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_PREFILL_PHONE)?.let { binding.etCallPhone.setText(it) }
    }

    private fun populateUI() {
        binding.tvUserName.text = "Hi, ${session.userName} 👋"
        binding.tvUserPhone.text = session.userPhone?.let { "📱 $it" } ?: "No phone number set — tap below to add"

        // Pre-fill the phone edit field if we already have a number
        session.userPhone?.let { binding.etMyPhone.setText(it) }

        val labels = INDIAN_LANGUAGES.map { LANGUAGE_LABELS[it] ?: it }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerLanguage.adapter = adapter

        val savedLang = session.userLanguage ?: "hindi"
        val idx = INDIAN_LANGUAGES.indexOf(savedLang).coerceAtLeast(0)
        binding.spinnerLanguage.setSelection(idx)
    }

    // ── Profile sync ───────────────────────────────────────────────────────

    private fun refreshProfileFromServer() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getMe(session.bearerToken)
                if (resp.isSuccessful && resp.body() != null) {
                    val user = resp.body()!!
                    session.userPhone = user.phone
                    session.userLanguage = user.preferred_language
                    // Update header without full re-inflate
                    binding.tvUserPhone.text = user.phone?.let { "📱 $it" } ?: "No phone number set — tap below to add"
                    if (!user.phone.isNullOrEmpty()) binding.etMyPhone.setText(user.phone)
                    val idx = INDIAN_LANGUAGES.indexOf(user.preferred_language).coerceAtLeast(0)
                    binding.spinnerLanguage.setSelection(idx)
                }
            } catch (_: Exception) {
                // Silently ignore — session cache is used as fallback
            }
        }
    }

    // ── Phone number save ───────────────────────────────────────────────────

    private fun savePhone() {
        val phone = binding.etMyPhone.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(this, "Enter your phone number first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnSavePhone.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.updateProfile(
                    session.bearerToken,
                    UpdateProfileRequest(phone = phone),
                )
                if (resp.isSuccessful && resp.body() != null) {
                    session.userPhone = phone
                    binding.tvUserPhone.text = "📱 $phone"
                    Toast.makeText(this@HomeActivity, "Phone number saved ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@HomeActivity, parseError(resp.errorBody()?.string()), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Cannot reach server. Is it running?", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnSavePhone.isEnabled = true
            }
        }
    }

    // ── Outgoing call ───────────────────────────────────────────────────────

    private fun initiateCall() {
        val phone = binding.etCallPhone.text.toString().trim()
        if (phone.isEmpty()) {
            Toast.makeText(this, "Enter the phone number to call", Toast.LENGTH_SHORT).show()
            return
        }
        if (session.userPhone.isNullOrEmpty()) {
            Toast.makeText(this, "Please save your own phone number first (section above)", Toast.LENGTH_LONG).show()
            return
        }
        val language = INDIAN_LANGUAGES[binding.spinnerLanguage.selectedItemPosition]

        binding.btnCall.isEnabled = false
        binding.callProgress.visibility = View.VISIBLE
        binding.tvCallStatus.text = "Looking up user…"
        binding.tvCallStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.initiateCall(
                    session.bearerToken,
                    InitiateCallRequest(phone, language),
                )
                if (resp.isSuccessful && resp.body() != null) {
                    val call = resp.body()!!
                    binding.tvCallStatus.text = call.message
                    // Look up callee user to get their ID for call record saving
                    var calleeId = -1
                    try {
                        val userResp = RetrofitClient.api.findByPhone(session.bearerToken, phone)
                        if (userResp.isSuccessful) calleeId = userResp.body()?.id ?: -1
                    } catch (_: Exception) {}
                    startActivity(Intent(this@HomeActivity, CallActivity::class.java).apply {
                        putExtra(CallActivity.EXTRA_ROOM_ID, call.room_id)
                        putExtra(CallActivity.EXTRA_LANGUAGE, language)
                        putExtra(CallActivity.EXTRA_CALLEE_NAME, call.callee_name)
                        putExtra(CallActivity.EXTRA_CALLEE_ID, calleeId)
                        putExtra(CallActivity.EXTRA_CALLEE_LANGUAGE, call.callee_language)
                    })
                } else {
                    val msg = parseError(resp.errorBody()?.string())
                    Toast.makeText(this@HomeActivity, msg, Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Cannot reach server. Check your server URL in Settings (⚙️).", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnCall.isEnabled = true
                binding.callProgress.visibility = View.GONE
                binding.tvCallStatus.visibility = View.GONE
            }
        }
    }

    // ── Language save ───────────────────────────────────────────────────────

    private fun saveLanguage() {
        val language = INDIAN_LANGUAGES[binding.spinnerLanguage.selectedItemPosition]
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.updateProfile(
                    session.bearerToken,
                    UpdateProfileRequest(preferred_language = language),
                )
                if (resp.isSuccessful) {
                    session.userLanguage = language
                    Toast.makeText(this@HomeActivity, "Language saved ✓", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Could not save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Server URL settings dialog ──────────────────────────────────────────

    private fun showServerUrlDialog() {
        val currentUrl = session.serverUrl ?: RetrofitClient.DEFAULT_SERVER
        val input = TextInputEditText(this).apply {
            setText(currentUrl)
            hint = "http://192.168.x.x:8000"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setMessage(
                "For emulator: http://10.0.2.2:8000\n" +
                "For real phone (same WiFi): http://<Mac IP>:8000\n" +
                "For internet testing: use ngrok URL"
            )
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val url = input.text.toString().trim().trimEnd('/')
                if (url.isNotEmpty()) {
                    session.serverUrl = url
                    RetrofitClient.configure(url)
                    Toast.makeText(this, "Server URL updated ✓", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Reset to default") { _, _ ->
                session.serverUrl = null
                RetrofitClient.configure(RetrofitClient.DEFAULT_SERVER)
                Toast.makeText(this, "Reset to emulator default", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ── Notification service ────────────────────────────────────────────────

    private fun requestNotifPermissionThenStartService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> launchNotificationService()
                else -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            launchNotificationService()
        }
    }

    private fun launchNotificationService() {
        try {
            val intent = Intent(this, CallNotificationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not start notification service: ${e.message}")
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Extract the readable "detail" string from a FastAPI error body. */
    private fun parseError(errorBody: String?): String {
        if (errorBody.isNullOrEmpty()) return "Something went wrong"
        return try {
            JSONObject(errorBody).getString("detail")
        } catch (_: Exception) {
            errorBody
        }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure?")
            .setPositiveButton("Logout") { _, _ ->
                val token = session.bearerToken
                try { stopService(Intent(this, CallNotificationService::class.java)) } catch (_: Exception) {}
                session.clear()
                // Tell the server to invalidate the token (bump token_version) — fire and forget
                lifecycleScope.launch {
                    try { RetrofitClient.api.logout(token) } catch (_: Exception) {}
                }
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
