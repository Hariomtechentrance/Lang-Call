package com.techentrance.languageapp

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.ContactsContract
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.textfield.TextInputEditText
import com.techentrance.languageapp.data.*
import com.techentrance.languageapp.databinding.ActivityHomeBinding
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.json.JSONObject

class HomeActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PREFILL_PHONE = "prefill_phone"
        private const val TAG = "HomeActivity"
    }

    private lateinit var binding: ActivityHomeBinding
    private lateinit var session: SessionManager
    private lateinit var connectivity: ConnectivityObserver

    // Contacts picker
    private val contactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) openContactsPicker() else Toast.makeText(this, "Contacts permission denied", Toast.LENGTH_SHORT).show()
    }

    private val contactPicker = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            result.data?.data?.let { uri ->
                val cursor = contentResolver.query(uri,
                    arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val number = it.getString(0)?.replace("\\s".toRegex(), "") ?: return@use
                        binding.etCallPhone.setText(number)
                    }
                }
            }
        }
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) launchNotificationService() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()
        session = SessionManager(this)
        connectivity = ConnectivityObserver(this)

        populateUI()
        refreshProfileFromServer()
        requestNotifPermissionThenStartService()
        observeConnectivity()

        intent.getStringExtra(EXTRA_PREFILL_PHONE)?.let { binding.etCallPhone.setText(it) }

        binding.btnCall.setOnClickListener { initiateCall() }
        binding.btnPickContact.setOnClickListener { requestContactsAndPick() }
        binding.btnSavePhone.setOnClickListener { savePhone() }
        binding.btnSaveLanguage.setOnClickListener { saveLanguage() }
        binding.btnSettings.setOnClickListener { showServerUrlDialog() }
        binding.btnDarkMode.setOnClickListener { toggleDarkMode() }
        binding.btnCallHistory.setOnClickListener { startActivity(Intent(this, CallHistoryActivity::class.java)) }
        binding.btnInviteFriend.setOnClickListener { inviteFriend() }
        binding.btnChangePassword.setOnClickListener { startActivity(Intent(this, ChangePasswordActivity::class.java)) }
        binding.btnLogout.setOnClickListener { confirmLogout() }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.getStringExtra(EXTRA_PREFILL_PHONE)?.let { binding.etCallPhone.setText(it) }
    }

    // ── Offline banner ───────────────────────────────────────────────────────

    private fun observeConnectivity() {
        connectivity.statusFlow.onEach { isOnline ->
            binding.tvOfflineBanner.visibility = if (isOnline) View.GONE else View.VISIBLE
        }.launchIn(lifecycleScope)
    }

    // ── Dark mode ────────────────────────────────────────────────────────────

    private fun toggleDarkMode() {
        val prefs = getSharedPreferences("langcall_app", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("dark_mode", true)
        val newDark = !isDark
        prefs.edit().putBoolean("dark_mode", newDark).apply()
        AppCompatDelegate.setDefaultNightMode(
            if (newDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }

    // ── Avatar with initials ─────────────────────────────────────────────────

    private fun populateUI() {
        val name = session.userName ?: "User"
        binding.tvUserName.text = "Hi, $name 👋"
        binding.tvUserPhone.text = session.userPhone?.let { "📱 $it" } ?: "No phone number — tap below to add"

        // Avatar: first letter of first + last name
        val parts = name.trim().split(" ")
        val initials = if (parts.size >= 2)
            "${parts[0].firstOrNull() ?: ""}${parts[1].firstOrNull() ?: ""}".uppercase()
        else
            name.take(2).uppercase()
        binding.tvAvatar.text = initials

        session.userPhone?.let { binding.etMyPhone.setText(it) }

        val labels = INDIAN_LANGUAGES.map { LANGUAGE_LABELS[it] ?: it }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        binding.spinnerLanguage.adapter = adapter
        val idx = INDIAN_LANGUAGES.indexOf(session.userLanguage ?: "hindi").coerceAtLeast(0)
        binding.spinnerLanguage.setSelection(idx)
    }

    // ── Contacts picker ──────────────────────────────────────────────────────

    private fun requestContactsAndPick() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            == PackageManager.PERMISSION_GRANTED
        ) openContactsPicker()
        else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    private fun openContactsPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
        contactPicker.launch(intent)
    }

    // ── Invite friend ────────────────────────────────────────────────────────

    private fun inviteFriend() {
        val myPhone = session.userPhone ?: ""
        val msg = "Hey! I use LangCall to make translation calls across Indian languages. " +
                "You can call me on $myPhone. Download LangCall and let's talk! 🌏📞"
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, msg)
        }, "Invite friend via"))
    }

    // ── Profile sync ─────────────────────────────────────────────────────────

    private fun refreshProfileFromServer() {
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.getMe(session.bearerToken)
                if (resp.isSuccessful && resp.body() != null) {
                    val user = resp.body()!!
                    session.userPhone = user.phone
                    session.userLanguage = user.preferred_language
                    binding.tvUserPhone.text = user.phone?.let { "📱 $it" } ?: "No phone number — tap below to add"
                    if (!user.phone.isNullOrEmpty()) binding.etMyPhone.setText(user.phone)
                    val idx = INDIAN_LANGUAGES.indexOf(user.preferred_language).coerceAtLeast(0)
                    binding.spinnerLanguage.setSelection(idx)
                }
            } catch (_: Exception) {}
        }
    }

    // ── Phone number save ────────────────────────────────────────────────────

    private fun savePhone() {
        val phone = binding.etMyPhone.text.toString().trim()
        if (phone.isEmpty()) { Toast.makeText(this, "Enter your phone number first", Toast.LENGTH_SHORT).show(); return }
        binding.btnSavePhone.isEnabled = false
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.updateProfile(session.bearerToken, UpdateProfileRequest(phone = phone))
                if (resp.isSuccessful && resp.body() != null) {
                    session.userPhone = phone
                    binding.tvUserPhone.text = "📱 $phone"
                    Toast.makeText(this@HomeActivity, "Phone number saved ✓", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@HomeActivity, parseError(resp.errorBody()?.string()), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Cannot reach server", Toast.LENGTH_SHORT).show()
            } finally {
                binding.btnSavePhone.isEnabled = true
            }
        }
    }

    // ── Outgoing call ────────────────────────────────────────────────────────

    private fun initiateCall() {
        val phone = binding.etCallPhone.text.toString().trim()
        if (phone.isEmpty()) { Toast.makeText(this, "Enter the phone number to call", Toast.LENGTH_SHORT).show(); return }
        if (session.userPhone.isNullOrEmpty()) {
            Toast.makeText(this, "Please save your own phone number first", Toast.LENGTH_LONG).show(); return
        }
        if (!connectivity.isOnline) {
            Toast.makeText(this, "No internet connection", Toast.LENGTH_SHORT).show(); return
        }
        val language = INDIAN_LANGUAGES[binding.spinnerLanguage.selectedItemPosition]
        binding.btnCall.isEnabled = false
        binding.callProgress.visibility = View.VISIBLE
        binding.tvCallStatus.text = "Looking up user…"
        binding.tvCallStatus.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.initiateCall(session.bearerToken, InitiateCallRequest(phone, language))
                if (resp.isSuccessful && resp.body() != null) {
                    val call = resp.body()!!
                    binding.tvCallStatus.text = call.message
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
                    Toast.makeText(this@HomeActivity, parseError(resp.errorBody()?.string()), Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@HomeActivity, "Cannot reach server. Check Settings (⚙️).", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnCall.isEnabled = true
                binding.callProgress.visibility = View.GONE
                binding.tvCallStatus.visibility = View.GONE
            }
        }
    }

    // ── Language save ────────────────────────────────────────────────────────

    private fun saveLanguage() {
        val language = INDIAN_LANGUAGES[binding.spinnerLanguage.selectedItemPosition]
        lifecycleScope.launch {
            try {
                val resp = RetrofitClient.api.updateProfile(session.bearerToken, UpdateProfileRequest(preferred_language = language))
                if (resp.isSuccessful) {
                    session.userLanguage = language
                    Toast.makeText(this@HomeActivity, "Language saved ✓", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this@HomeActivity, "Could not save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Server URL dialog ────────────────────────────────────────────────────

    private fun showServerUrlDialog() {
        val currentUrl = session.serverUrl ?: RetrofitClient.DEFAULT_SERVER
        val input = TextInputEditText(this).apply {
            setText(currentUrl)
            hint = "https://lang-call.onrender.com"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("Server URL")
            .setMessage("Production: https://lang-call.onrender.com\nLocal (emulator): http://10.0.2.2:8000")
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
            .setNeutralButton("Reset") { _, _ ->
                session.serverUrl = null
                RetrofitClient.configure(RetrofitClient.DEFAULT_SERVER)
                Toast.makeText(this, "Reset to production server", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    // ── Notification service ─────────────────────────────────────────────────

    private fun requestNotifPermissionThenStartService() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            when {
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED -> launchNotificationService()
                else -> notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else launchNotificationService()
    }

    private fun launchNotificationService() {
        try {
            val intent = Intent(this, CallNotificationService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                startForegroundService(intent)
            else startService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start notification service: ${e.message}")
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun parseError(errorBody: String?): String {
        if (errorBody.isNullOrEmpty()) return "Something went wrong"
        return try { JSONObject(errorBody).getString("detail") } catch (_: Exception) { errorBody }
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure?")
            .setPositiveButton("Logout") { _, _ ->
                val token = session.bearerToken
                try { stopService(Intent(this, CallNotificationService::class.java)) } catch (_: Exception) {}
                session.clear()
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
