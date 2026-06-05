package com.techentrance.languageapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.techentrance.languageapp.data.CallRecordRequest
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager
import com.techentrance.languageapp.databinding.ActivityCallBinding
import kotlinx.coroutines.launch

class CallActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_LANGUAGE = "language"
        const val EXTRA_CALLEE_NAME = "callee_name"
        const val EXTRA_CALLEE_ID = "callee_id"
        const val EXTRA_CALLEE_LANGUAGE = "callee_language"
        const val EXTRA_CALL_ID = "call_id"
    }

    private lateinit var binding: ActivityCallBinding
    private lateinit var session: SessionManager
    private lateinit var wsManager: WebSocketManager
    private lateinit var audioStreamer: AudioStreamer
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var audioManager: AudioManager

    private lateinit var roomId: String
    private lateinit var language: String
    private var calleeName: String = "Other person"
    private var calleeId: Int = -1
    private var calleeLanguage: String = ""
    private var callId: Int = -1
    private var callActive = false
    private var muted = false
    private var speakerOn = false

    // Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartTime = 0L
    private var timerRunnable: Runnable? = null

    // Speaking animation
    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsRunnable: Runnable? = null
    private var speakingActive = false

    // Dots animation frames
    private val dotFrames = arrayOf("●○○", "●●○", "●●●", "○●●", "○○●")
    private var dotFrame = 0

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
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager

        roomId = intent.getStringExtra(EXTRA_ROOM_ID) ?: "room1"
        language = intent.getStringExtra(EXTRA_LANGUAGE) ?: session.userLanguage ?: "hindi"
        calleeName = intent.getStringExtra(EXTRA_CALLEE_NAME) ?: "Other person"
        calleeId = intent.getIntExtra(EXTRA_CALLEE_ID, -1)
        calleeLanguage = intent.getStringExtra(EXTRA_CALLEE_LANGUAGE) ?: ""
        callId = intent.getIntExtra(EXTRA_CALL_ID, -1)

        binding.tvRoomId.text = calleeName
        binding.tvCalleeName.text = calleeName
        binding.tvYourLanguage.text = language.replaceFirstChar { it.uppercase() }
        binding.tvTheirLanguage.text = calleeLanguage.ifEmpty { "?" }.replaceFirstChar { it.uppercase() }

        audioPlayer = AudioPlayer()
        audioStreamer = AudioStreamer { chunk -> if (callActive && !muted) wsManager.sendAudio(chunk) }

        if (callId != -1) {
            lifecycleScope.launch {
                try { RetrofitClient.api.answerCall(session.bearerToken, callId) } catch (_: Exception) {}
            }
        }

        setupWebSocket()

        binding.btnEndCall.setOnClickListener { endCall() }
        binding.btnEndCall2.setOnClickListener { endCall() }
        binding.btnMute.setOnClickListener { toggleMute() }
        binding.btnSpeaker.setOnClickListener { toggleSpeaker() }
    }

    private fun setupWebSocket() {
        wsManager = WebSocketManager(
            onAudioReceived = { bytes ->
                runOnUiThread {
                    audioPlayer.playMp3(bytes, cacheDir)
                    showSpeakingAnimation()
                }
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
                    val waiting = if (slot == "user_a") "Waiting for $calleeName to join…" else "Connected — speak now"
                    setStatus(waiting, connected = true)
                    binding.tvLive.visibility = View.VISIBLE
                    if (slot == "user_b") {
                        // Both users now connected — start timer
                        startTimer()
                        binding.tvCallTimer.visibility = View.VISIBLE
                    }
                    requestMicAndStream()
                }
            },
            onBothConnected = {
                runOnUiThread {
                    startTimer()
                    binding.tvCallTimer.visibility = View.VISIBLE
                    setStatus("Call active — speak in ${language.replaceFirstChar { it.uppercase() }}", connected = true)
                }
            },
            onReconnecting = { attempt ->
                runOnUiThread {
                    binding.tvReconnecting.visibility = View.VISIBLE
                    setStatus("Reconnecting… (attempt $attempt)", connected = false)
                }
            },
            onReconnected = {
                runOnUiThread {
                    binding.tvReconnecting.visibility = View.GONE
                    setStatus("Reconnected ✓", connected = true)
                }
            },
            onError = { msg ->
                runOnUiThread {
                    binding.tvReconnecting.visibility = View.GONE
                    setStatus("Disconnected: $msg", connected = false)
                }
            },
        )

        setStatus("Connecting to server…", connected = false)
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
    }

    // ── Mute ────────────────────────────────────────────────────────────

    private fun toggleMute() {
        muted = !muted
        binding.btnMute.text = if (muted) "🔇 Unmute" else "🎤 Mute"
        binding.btnMute.setBackgroundColor(
            if (muted) Color.parseColor("#FFF44336") else Color.parseColor("#FF6200EE")
        )
        binding.tvMicIcon.text = if (muted) "🔇" else "🎤"
    }

    // ── Speaker toggle ───────────────────────────────────────────────────

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        audioManager.isSpeakerphoneOn = speakerOn
        binding.btnSpeaker.text = if (speakerOn) "🔊 Speaker ON" else "🔈 Speaker"
        binding.btnSpeaker.setBackgroundColor(
            if (speakerOn) Color.parseColor("#FF4CAF50") else Color.parseColor("#FF6200EE")
        )
    }

    // ── Call timer ───────────────────────────────────────────────────────

    private fun startTimer() {
        if (callStartTime != 0L) return      // already started
        callStartTime = System.currentTimeMillis()
        val runnable = object : Runnable {
            override fun run() {
                val elapsed = (System.currentTimeMillis() - callStartTime) / 1000
                val mm = elapsed / 60
                val ss = elapsed % 60
                binding.tvCallTimer.text = "%02d:%02d".format(mm, ss)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerRunnable = runnable
        timerHandler.post(runnable)
    }

    private fun stopTimer(): Int {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        return if (callStartTime != 0L)
            ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
        else 0
    }

    // ── Speaking animation ───────────────────────────────────────────────

    private fun showSpeakingAnimation() {
        binding.tvSpeakingDots.visibility = View.VISIBLE
        speakingActive = true
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }

        val runnable = object : Runnable {
            override fun run() {
                binding.tvSpeakingDots.text = dotFrames[dotFrame % dotFrames.size]
                dotFrame++
                dotsHandler.postDelayed(this, 200)
            }
        }
        dotsRunnable = runnable
        dotsHandler.post(runnable)

        // Hide after 3 seconds (enough for the TTS to finish playing)
        dotsHandler.postDelayed({
            dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
            binding.tvSpeakingDots.visibility = View.GONE
            speakingActive = false
        }, 3000)
    }

    // ── End call ─────────────────────────────────────────────────────────

    private fun endCall() {
        if (!callActive && callStartTime == 0L) {
            finish()
            return
        }
        callActive = false
        val durationSeconds = stopTimer()
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }

        audioStreamer.stopStreaming()
        wsManager.disconnect()
        audioPlayer.release()

        // Restore to earpiece
        audioManager.isSpeakerphoneOn = false

        saveCallRecord(durationSeconds)
        finish()
    }

    private fun saveCallRecord(durationSeconds: Int) {
        val myId = session.userId ?: return
        val myName = session.userName ?: return
        if (calleeId == -1) return

        lifecycleScope.launch {
            try {
                RetrofitClient.api.saveCallRecord(
                    session.bearerToken,
                    CallRecordRequest(
                        room_id = roomId,
                        caller_id = myId,
                        callee_id = calleeId,
                        caller_name = myName,
                        callee_name = calleeName,
                        caller_language = language,
                        callee_language = calleeLanguage,
                        duration_seconds = durationSeconds,
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ── Status dot ───────────────────────────────────────────────────────

    private fun setStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(R.drawable.status_dot)
        binding.statusDot.background.setTint(
            if (connected) Color.parseColor("#FF4CAF50") else Color.parseColor("#FFBDBDBD")
        )
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        callActive = false
        stopTimer()
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
        if (::audioStreamer.isInitialized) audioStreamer.stopStreaming()
        if (::wsManager.isInitialized) wsManager.disconnect()
        if (::audioPlayer.isInitialized) audioPlayer.release()
        if (::audioManager.isInitialized) audioManager.isSpeakerphoneOn = false
    }
}
