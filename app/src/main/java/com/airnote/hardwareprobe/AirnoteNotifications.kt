package com.airnote.hardwareprobe

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

object AirnoteNotifications {
    private const val CHANNEL_ID = "airnote_settlement"
    private const val NOTIFICATION_ID = 1100

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyReadyToSettle(context: Context, payment: AirnotePayment) {
        show(
            context,
            payment,
            title = "${amount(payment)} USDC Airnote ready to settle",
            text = "You're back online. Open AIRNOTE to authorize settlement on Solana."
        )
    }

    fun notifySettled(context: Context, payment: AirnotePayment) {
        show(
            context,
            payment,
            title = "${amount(payment)} USDC settled",
            text = "Your Airnote is finalized on Solana."
        )
    }

    fun notifyNeedsAttention(context: Context, payment: AirnotePayment, text: String) {
        show(
            context,
            payment,
            title = "${amount(payment)} USDC settlement needs attention",
            text = text
        )
    }

    private fun show(
        context: Context,
        payment: AirnotePayment,
        title: String,
        text: String
    ) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val intent = Intent(context, AirnotePaymentDetailActivity::class.java)
            .putExtra(AirnotePaymentDetailActivity.EXTRA_PAYMENT_ID, payment.id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pendingIntent = PendingIntent.getActivity(
            context,
            payment.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_more)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context)
            .notify("airnote:${payment.id}", NOTIFICATION_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "AIRNOTE settlement",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Settlement readiness and Solana finalization updates"
            }
        )
    }

    private fun amount(payment: AirnotePayment): String =
        "%.2f".format(payment.amountBaseUnits / 1_000_000.0)
}
