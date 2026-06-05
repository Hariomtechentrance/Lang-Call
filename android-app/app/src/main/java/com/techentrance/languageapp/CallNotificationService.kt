package com.techentrance.languageapp

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.techentrance.languageapp.data.RetrofitClient
import com.techentrance.languageapp.data.SessionManager
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Foreground service that maintains a persistent WebSocket to /notify
 * so incoming LangCall calls are received even when the app is in background.
 */
class CallNotificationService : Service() {

    companion object {
        const val CHANNEL_ID = "langcall_service"
        const val CHANNEL_CALL_ID = "langcall_incoming"
        const val NOTIF_ID_SERVICE = 1001
        const val NOTIF_ID_CALL = 1002

        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_CALL_ID = "call_id"
        const val EXTRA_FROM_NAME = "from_name"
        const val EXTRA_FROM_LANG = "from_lang"

        const val ACTION_ACCEPT = "com.techentrance.languageapp.ACCEPT_CALL"
        const val ACTION_DECLINE = "com.techentrance.languageapp.DECLINE_CALL"
    }

    private val TAG = "CallNotifService"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var webSocket: WebSocket? = null
    private lateinit var session: SessionManager

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    override fun onCreate() {
        super.onCreate()
        session = SessionManager(this)
        try {
            createNotificationChannels()
            startForeground(NOTIF_ID_SERVICE, buildServiceNotification())
            connectNotifySocket()
        } catch (e: Exception) {
            Log.e(TAG, "Service startup error: ${e.message}")
            stopSelf()
        }
    }

    private fun connectNotifySocket() {
        val token = session.token ?: return
        val url = "${RetrofitClient.WS_BASE_URL}/notify?token=$token"
        val request = Request.Builder().url(url).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) {
                Log.d(TAG, "Notify socket connected")
                // Keep-alive ping every 25s
                scope.launch {
                    while (isActive) {
                        delay(25_000)
                        ws.send("ping")
                    }
                }
            }

            override fun onMessage(ws: WebSocket, text: String) {
                val json = JSONObject(text)
                if (json.optString("type") == "incoming_call") {
                    val roomId = json.optString("room_id")
                    val callId = json.optInt("call_id")
                    val fromName = json.optString("from_name")
                    val fromLang = json.optString("from_language")
                    showIncomingCallNotification(callId, roomId, fromName, fromLang)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Notify socket failed: ${t.message}")
                // Reconnect after 5 seconds
                scope.launch {
                    delay(5000)
                    connectNotifySocket()
                }
            }
        })
    }

    private fun showIncomingCallNotification(callId: Int, roomId: String, fromName: String, fromLang: String) {
        val acceptIntent = Intent(this, CallActivity::class.java).apply {
            putExtra(CallActivity.EXTRA_ROOM_ID, roomId)
            putExtra(CallActivity.EXTRA_LANGUAGE, session.userLanguage ?: "hindi")
            putExtra(CallActivity.EXTRA_CALL_ID, callId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val acceptPending = PendingIntent.getActivity(
            this, callId, acceptIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val declineIntent = Intent(ACTION_DECLINE).apply { putExtra(EXTRA_CALL_ID, callId) }
        val declinePending = PendingIntent.getBroadcast(
            this, callId + 1000, declineIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val langLabel = fromLang.replaceFirstChar { it.uppercase() }
        val notification = NotificationCompat.Builder(this, CHANNEL_CALL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming LangCall from $fromName")
            .setContentText("Speaks $langLabel — tap to answer with translation")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(acceptPending, true)
            .addAction(android.R.drawable.ic_menu_call, "Answer", acceptPending)
            .addAction(android.R.drawable.ic_delete, "Decline", declinePending)
            .setAutoCancel(true)
            .build()

        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_CALL, notification)
    }

    private fun buildServiceNotification(): Notification {
        val intent = PendingIntent.getActivity(
            this, 0, Intent(this, HomeActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("LangCall is active")
            .setContentText("Listening for incoming calls")
            .setContentIntent(intent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        NotificationChannel(CHANNEL_ID, "LangCall Service", NotificationManager.IMPORTANCE_LOW).also {
            it.description = "Background call listening"
            manager.createNotificationChannel(it)
        }
        NotificationChannel(CHANNEL_CALL_ID, "Incoming Calls", NotificationManager.IMPORTANCE_HIGH).also {
            it.description = "Incoming LangCall notifications"
            it.enableVibration(true)
            manager.createNotificationChannel(it)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        webSocket?.close(1000, "Service stopped")
    }
}
