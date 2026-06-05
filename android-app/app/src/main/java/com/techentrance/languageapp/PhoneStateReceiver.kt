package com.techentrance.languageapp

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.techentrance.languageapp.data.SessionManager

/**
 * Detects incoming PSTN (regular cellular) calls.
 *
 * When someone calls your normal phone number, this shows a notification:
 * "Answer with LangCall for real-time translation"
 *
 * NOTE: We cannot modify the audio of a regular phone call (Android security).
 * This feature offers to START a parallel LangCall session with the same number.
 * Best experience: both parties have LangCall installed.
 */
class PhoneStateReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "langcall_service"
        const val NOTIF_ID_PSTN = 2001
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER) ?: return

        val session = SessionManager(context)
        if (!session.isLoggedIn) return

        if (state == TelephonyManager.EXTRA_STATE_RINGING && incomingNumber.isNotEmpty()) {
            showLangCallSuggestion(context, incomingNumber)
        } else if (state == TelephonyManager.EXTRA_STATE_IDLE) {
            // Clear suggestion when call ends
            context.getSystemService(NotificationManager::class.java)
                .cancel(NOTIF_ID_PSTN)
        }
    }

    private fun showLangCallSuggestion(context: Context, phoneNumber: String) {
        val session = SessionManager(context)

        // Deep-link into HomeActivity with the caller's number pre-filled
        val callIntent = Intent(context, HomeActivity::class.java).apply {
            putExtra(HomeActivity.EXTRA_PREFILL_PHONE, phoneNumber)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val callPending = PendingIntent.getActivity(
            context, NOTIF_ID_PSTN, callIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("Incoming call from $phoneNumber")
            .setContentText("Tap to call back through LangCall with real-time translation")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(callPending)
            .setAutoCancel(true)
            .build()

        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID_PSTN, notification)
    }
}
