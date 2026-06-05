package com.techentrance.languageapp

import android.util.Log
import okhttp3.*
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class WebSocketManager(
    private val onAudioReceived: (ByteArray) -> Unit,
    private val onTranscriptReceived: (String, String) -> Unit,
    private val onConnected: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    private val TAG = "WebSocketManager"
    private var webSocket: WebSocket? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    fun connect(wsBaseUrl: String, roomId: String, language: String, token: String) {
        val url = "$wsBaseUrl/ws/$roomId?token=$token"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(ws: WebSocket, response: Response) {
                val init = JSONObject().apply { put("language", language) }.toString()
                ws.send(init)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                when (json.optString("type")) {
                    "joined" -> onConnected(json.optString("slot"))
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

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WS failure: ${t.message}")
                onError(t.message ?: "Connection failed")
            }
        })
    }

    fun sendAudio(bytes: ByteArray) {
        webSocket?.send(bytes.toByteString())
    }

    fun disconnect() {
        webSocket?.close(1000, "User left")
        webSocket = null
    }
}
