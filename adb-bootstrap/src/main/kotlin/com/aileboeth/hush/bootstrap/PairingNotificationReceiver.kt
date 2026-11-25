package com.aileboeth.hush.bootstrap

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles inline 6-digit wireless pairing code submission directly from the Android notification shade,
 * so the user doesn't have to leave Developer Options -> Wireless Debugging while pairing.
 */
class PairingNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SUBMIT_PAIR_CODE) return
        val remoteInput = RemoteInput.getResultsFromIntent(intent) ?: return
        val code = remoteInput.getCharSequence(KEY_PAIR_CODE)?.toString()?.trim().orEmpty()
        if (code.isEmpty()) return

        val pendingResult = goAsync()
        val callback = onPairCodeSubmitted
        if (callback == null) {
            pendingResult.finish()
            return
        }
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                callback(code)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "hush_wireless_pairing_v3"
        const val NOTIFICATION_ID = 20001
        const val ACTION_SUBMIT_PAIR_CODE = "com.aileboeth.hush.ACTION_SUBMIT_PAIR_CODE"
        const val KEY_PAIR_CODE = "key_pair_code"

        @Volatile
        internal var onPairCodeSubmitted: (suspend (String) -> Unit)? = null

        fun showPairingPrompt(
            context: Context,
            title: String = "Hush 等待无线调试配对",
            subtitle: String,
            alertHeadsUp: Boolean = true,
        ) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Hush 无线配对输入",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "用于在无线调试配对时弹出并输入 6 位配对码"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120)
                setShowBadge(false)
            }
            nm.createNotificationChannel(channel)

            val remoteInput = RemoteInput.Builder(KEY_PAIR_CODE)
                .setLabel("输入 6 位配对码")
                .build()

            val replyIntent = Intent(context, PairingNotificationReceiver::class.java).apply {
                action = ACTION_SUBMIT_PAIR_CODE
            }
            val replyPendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )

            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentPendingIntent = launchIntent?.let {
                PendingIntent.getActivity(
                    context,
                    1,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

            val action = Notification.Action.Builder(
                Icon.createWithResource(context, android.R.drawable.ic_menu_send),
                "输入配对码",
                replyPendingIntent,
            ).addRemoteInput(remoteInput).build()

            // Use CATEGORY_MESSAGE + non-ongoing so OEMs (e.g. OriginOS) do not suppress
            // heads-up display or hide it at the bottom as a background service notification.
            val builder = Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(title)
                .setContentText(subtitle)
                .setCategory(Notification.CATEGORY_MESSAGE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(!alertHeadsUp)
                .setAutoCancel(false)
                .addAction(action)

            if (contentPendingIntent != null) {
                builder.setContentIntent(contentPendingIntent)
            }

            nm.notify(NOTIFICATION_ID, builder.build())
        }

        fun dismiss(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
        }
    }
}
