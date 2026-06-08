package com.techentrance.languageapp

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onTranscriptReceived: (String, String) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onBothConnected: () -> Unit = {},
    private val onReconnecting: (Int) -> Unit = {},
    private val onReconnected: () -> Unit = {},
    private val onCallTimeout: () -> Unit = {},
    private val onError: (String) -> Unit,
) {
    private val TAG = "WebSocketManager"
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 4
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectRunnable: Runnable? = null

    // Connection params saved for reconnect
    private var wsBaseUrl = ""
    private var roomId = ""
    private var language = ""
    private var token = ""
    private var destroyed = false
    private var currentSlot = ""

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    fun connect(wsBaseUrl: String, roomId: String, language: String, token: String) {
        this.wsBaseUrl = wsBaseUrl
        this.roomId = roomId
        this.language = language
        this.token = token
        destroyed = false
        openSocket()
    }

    private fun openSocket() {
        val url = "$wsBaseUrl/ws/$roomId?token=$token"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                reconnectAttempts = 0
                val init = JSONObject().apply { put("language", language) }.toString()
                ws.send(init)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "joined" -> {
                        currentSlot = json.optString("slot")
                        onConnected(currentSlot)
                    }
                    "peer_joined" -> onBothConnected()
                    "call_timeout" -> onCallTimeout()
                    "audio" -> {
                        onTranscriptReceived(
                            json.optString("original"),
                            json.optString("translated"),
                        )
                        val b64 = json.optString("audio_b64")
                        if (b64.isNotEmpty()) {
                            val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                            onAudioReceived(bytes)
                        }
                    }
                    "error" -> onError(json.optString("msg"))
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                if (!destroyed) scheduleReconnect(t.message ?: "Connection failed")
            }
        })
    }

    private fun scheduleReconnect(reason: String) {
        if (destroyed || reconnectAttempts >= maxReconnectAttempts) {
            onError("$reason (gave up after $maxReconnectAttempts attempts)")
            return
        }
        reconnectAttempts++
        onReconnecting(reconnectAttempts)

        // Exponential backoff: 2s, 4s, 8s, 16s
        val delayMs = (1L shl reconnectAttempts) * 1000L
        Log.i(TAG, "Reconnect attempt $reconnectAttempts in ${delayMs}ms")

        val runnable = Runnable {
            if (!destroyed) {
                webSocket?.cancel()
                webSocket = null
                openSocket()
                if (reconnectAttempts > 1) onReconnected()
            }
        }
        reconnectRunnable = runnable
        reconnectHandler.postDelayed(runnable, delayMs)
    }

    fun sendAudio(bytes: ByteArray) {
        webSocket?.send(bytes.toByteString())
    }

    fun disconnect() {
        destroyed = true
        reconnectRunnable?.let { reconnectHandler.removeCallbacks(it) }
        reconnectRunnable = null
        webSocket?.close(1000, "User left")
        webSocket = null
    }
}
