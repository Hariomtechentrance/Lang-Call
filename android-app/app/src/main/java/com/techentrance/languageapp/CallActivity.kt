package com.techentrance.languageapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager
import com.techentrance.languageapp.databinding.ActivityCallBinding
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALL_ID = "call_id"   // when answering an incoming call
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var session: SessionManager
    private lateinit var wsManager: WebSocketManager
    private lateinit var audioStreamer: AudioStreamer
    private lateinit var audioPlayer: AudioPlayer

    private lateinit var roomId: String
    private lateinit var language: String
    private var calleeName: String = "Other person"
    private var callId: Int = -1
    private var callActive = false
    private var muted = false

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) beginStreaming()
        else Toast.makeText(this, "Microphone permission required", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        session = SessionManager(this)
        roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "room1"
        language = intent.getStringExtra(EXTRA_LANGUAGE) ?: session.userLanguage ?: "hindi"
        calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Other person"
        callId = intent.getIntExtra(EXTRA_CALL_ID, -1)

        binding.tvRoomId.text = calleeName
        binding.tvYourLanguage.text = language.replaceFirstChar { it.uppercase() }
        binding.tvTheirLanguage.text = "Auto-detected"

        audioPlayer = AudioPlayer()
        audioStreamer = AudioStreamer { chunk -> if (callActive && !muted) wsManager.sendAudio(chunk) }

        // If answering an incoming call, mark it as answered
        if (callId != -1) {
            lifecycleScope.launch {
                try {
                    RetrofitClient.api.answerCall(session.bearerToken, callId)
                } catch (_: Exception) {}
            }
        }

        setupWebSocket()
        binding.btnEndCall.setOnClickListener { endCall() }
        binding.btnEndCall2.setOnClickListener { endCall() }
        binding.btnMute.setOnClickListener { toggleMute() }
    }

    private fun setupWebSocket() {
        wsManager = WebSocketManager(
            onAudioReceived = { bytes ->
                runOnUiThread { audioPlayer.playMp3(bytes, cacheDir) }
            },
            onTranscriptReceived = { original, translated ->
                runOnUiThread {
                    if (original.isNotEmpty()) {
                        binding.tvOriginal.text = original
                        binding.tvTranslated.text = translated.ifEmpty { "—" }
                    }
                }
            },
            onConnected = { slot ->
                runOnUiThread {
                    setStatus("Connected — waiting for ${if (slot == "user_a") "other person" else calleeName}…", true)
                    binding.tvLive.visibility = View.VISIBLE
                    requestMicAndStream()
                }
            },
            onError = { msg ->
                runOnUiThread { setStatus("Error: $msg", false) }
            },
        )
        setStatus("Connecting…", false)
        wsManager.connect(
            wsBaseUrl = RetrofitClient.WS_BASE_URL,
            roomId = roomId,
            language = language,
            token = session.token ?: "",
        )
    }

    private fun requestMicAndStream() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) {
            beginStreaming()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun beginStreaming() {
        audioStreamer.startStreaming()
        callActive = true
        setStatus("Call active — speak in ${language.replaceFirstChar { it.uppercase() }}", true)
    }

    private fun toggleMute() {
        muted = !muted
        binding.btnMute.text = if (muted) "Unmute" else "Mute"
        binding.btnMute.setBackgroundColor(
            if (muted) Color.parseColor("#FFF44336") else Color.parseColor("#FF6200EE")
        )
        setStatus(if (muted) "Muted" else "Call active — speak now", !muted)
    }

    private fun endCall() {
        callActive = false
        audioStreamer.stopStreaming()
        wsManager.disconnect()
        audioPlayer.release()
        finish()
    }

    private fun setStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(R.drawable.status_dot)
        binding.statusDot.background.setTint(
            if (connected) Color.parseColor("#FF4CAF50") else Color.parseColor("#FFBDBDBD")
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        callActive = false
        if (::audioStreamer.isInitialized) audioStreamer.stopStreaming()
        if (::wsManager.isInitialized) wsManager.disconnect()
        if (::audioPlayer.isInitialized) audioPlayer.release()
    }
}
