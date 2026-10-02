package com.sms.app.core.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.sms.app.core.dial.T9

/**
 * Vibrations of one's own for a few people, to know who wrote without
 * looking. Each pattern has its notification channel, as Android sets a
 * vibration per channel.
 */
object Signatures {

    val patterns: Map<String, LongArray> = linkedMapOf(
        "Heartbeat" to longArrayOf(0, 70, 110, 70, 700),
        "Double tap" to longArrayOf(0, 40, 90, 40),
        "Wave" to longArrayOf(0, 30, 40, 60, 40, 100, 40, 60, 40, 30),
        "Long" to longArrayOf(0, 550)
    )

    fun key(number: String) = T9.clean(number).removePrefix("+").takeLast(9)

    /** The channel for [name]'s pattern, made when first needed. */
    fun channel(context: Context, name: String): String? {
        val pattern = patterns[name] ?: return null
        val id = "messages_" + name.lowercase().replace(' ', '_')
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Messages: $name", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = pattern
            }
        )
        return id
    }

    /** Lets the user feel a pattern while choosing it. */
    fun play(context: Context, name: String) {
        val pattern = patterns[name] ?: return
        runCatching { context.getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }
}
