package com.techentrance.languageapp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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
import com.techentrance.languageapp.db.AppDatabase
import com.techentrance.languageapp.db.TranscriptEntity
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
    private var callStatus = "timeout"   // updated to "completed" when both connect

    // WakeLock — keeps CPU alive so mic recording doesn't stop when screen turns off
    private var wakeLock: PowerManager.WakeLock? = null

    // Audio focus
    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioFocusListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Phone call came in — mute ourselves
                if (!muted) toggleMute()
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Focus returned — unmute if we auto-muted
                if (muted) toggleMute()
            }
        }
    }

    // Timer
    private val timerHandler = Handler(Looper.getMainLooper())
    private var callStartTime = 0L
    private var timerRunnable: Runnable? = null

    // Speaking animation
    private val dotsHandler = Handler(Looper.getMainLooper())
    private var dotsRunnable: Runnable? = null
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

        acquireWakeLock()
        requestAudioFocus()

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

    // ── WakeLock ─────────────────────────────────────────────────────────────

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "LangCall::CallWakeLock"
        ).apply { acquire(60 * 60 * 1000L) }   // max 1 hour
    }

    // ── Audio Focus ──────────────────────────────────────────────────────────

    private fun requestAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setOnAudioFocusChangeListener(audioFocusListener)
                .build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusListener,
                AudioManager.STREAM_VOICE_CALL,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    }

    private fun releaseAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusListener)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
    }

    // ── WebSocket ────────────────────────────────────────────────────────────

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
                        saveTranscriptLine(original, translated)
                    }
                }
            },
            onConnected = { slot ->
                runOnUiThread {
                    val waiting = if (slot == "user_a") "Ringing… waiting for $calleeName" else "Connected — speak now"
                    setStatus(waiting, connected = true)
                    binding.tvLive.visibility = View.VISIBLE
                    if (slot == "user_b") { startTimer(); binding.tvCallTimer.visibility = View.VISIBLE }
                    requestMicAndStream()
                }
            },
            onBothConnected = {
                runOnUiThread {
                    callStatus = "completed"
                    startTimer()
                    binding.tvCallTimer.visibility = View.VISIBLE
                    setStatus("Call active — speak in ${language.replaceFirstChar { it.uppercase() }}", connected = true)
                }
            },
            onCallTimeout = {
                runOnUiThread {
                    callStatus = "timeout"
                    setStatus("No answer — call timed out", connected = false)
                    Toast.makeText(this, "$calleeName didn't answer", Toast.LENGTH_LONG).show()
                    Handler(Looper.getMainLooper()).postDelayed({ endCall() }, 2000)
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

        setStatus("Connecting…", connected = false)
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
        ) beginStreaming()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun beginStreaming() {
        audioStreamer.startStreaming()
        callActive = true
    }

    // ── Mute ────────────────────────────────────────────────────────────────

    fun toggleMute() {
        muted = !muted
        binding.btnMute.text = if (muted) "🔇 Unmute" else "🎤 Mute"
        binding.btnMute.setBackgroundColor(
            if (muted) Color.parseColor("#FFF44336") else Color.parseColor("#FF6200EE")
        )
        binding.tvMicIcon.text = if (muted) "🔇" else "🎤"
    }

    // ── Speaker ──────────────────────────────────────────────────────────────

    private fun toggleSpeaker() {
        speakerOn = !speakerOn
        audioManager.isSpeakerphoneOn = speakerOn
        binding.btnSpeaker.text = if (speakerOn) "🔊 Speaker ON" else "🔈 Speaker"
        binding.btnSpeaker.setBackgroundColor(
            if (speakerOn) Color.parseColor("#FF4CAF50") else Color.parseColor("#FF6200EE")
        )
    }

    // ── Timer ────────────────────────────────────────────────────────────────

    private fun startTimer() {
        if (callStartTime != 0L) return
        callStartTime = System.currentTimeMillis()
        val r = object : Runnable {
            override fun run() {
                val e = (System.currentTimeMillis() - callStartTime) / 1000
                binding.tvCallTimer.text = "%02d:%02d".format(e / 60, e % 60)
                timerHandler.postDelayed(this, 1000)
            }
        }
        timerRunnable = r
        timerHandler.post(r)
    }

    private fun stopTimer(): Int {
        timerRunnable?.let { timerHandler.removeCallbacks(it) }
        timerRunnable = null
        return if (callStartTime != 0L) ((System.currentTimeMillis() - callStartTime) / 1000).toInt() else 0
    }

    // ── Speaking animation ───────────────────────────────────────────────────

    private fun showSpeakingAnimation() {
        binding.tvSpeakingDots.visibility = View.VISIBLE
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
        val r = object : Runnable {
            override fun run() {
                binding.tvSpeakingDots.text = dotFrames[dotFrame++ % dotFrames.size]
                dotsHandler.postDelayed(this, 200)
            }
        }
        dotsRunnable = r
        dotsHandler.post(r)
        dotsHandler.postDelayed({
            dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
            binding.tvSpeakingDots.visibility = View.GONE
        }, 3000)
    }

    // ── Transcript saving ────────────────────────────────────────────────────

    private fun saveTranscriptLine(original: String, translated: String) {
        lifecycleScope.launch {
            try {
                val db = AppDatabase.get(this@CallActivity)
                db.transcriptDao().insert(
                    TranscriptEntity(
                        roomId = roomId,
                        otherPersonName = calleeName,
                        originalText = original,
                        translatedText = translated,
                        speakerSlot = "them",
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ── End call ─────────────────────────────────────────────────────────────

    private fun endCall() {
        if (!callActive && callStartTime == 0L && callStatus == "timeout") {
            cleanup(); finish(); return
        }
        callActive = false
        val durationSeconds = stopTimer()
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
        audioStreamer.stopStreaming()
        wsManager.disconnect()
        audioPlayer.release()
        saveCallRecord(durationSeconds)
        cleanup()
        finish()
    }

    private fun cleanup() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        releaseAudioFocus()
        audioManager.isSpeakerphoneOn = false
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
                        status = callStatus,
                    )
                )
            } catch (_: Exception) {}
        }
    }

    // ── Status ───────────────────────────────────────────────────────────────

    private fun setStatus(msg: String, connected: Boolean) {
        binding.tvStatus.text = msg
        binding.statusDot.setBackgroundResource(R.drawable.status_dot)
        binding.statusDot.background.setTint(
            if (connected) Color.parseColor("#FF4CAF50") else Color.parseColor("#FFBDBDBD")
        )
    }

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        callActive = false
        stopTimer()
        dotsRunnable?.let { dotsHandler.removeCallbacks(it) }
        if (::audioStreamer.isInitialized) audioStreamer.stopStreaming()
        if (::wsManager.isInitialized) wsManager.disconnect()
        if (::audioPlayer.isInitialized) audioPlayer.release()
        cleanup()
    }
}
