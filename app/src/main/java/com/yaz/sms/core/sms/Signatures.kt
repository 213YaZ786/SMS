package com.yaz.sms.core.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import com.yaz.sms.core.dial.T9

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
    fun channel(context: Context, name: String): String? = channel(context, name, null)

    /**
     * The channel for a person's own vibration ([name]) and own sound
     * ([tone], chosen in the Contacts app), made when first needed: Android
     * keeps both per channel, so one channel per pair.
     */
    fun channel(context: Context, name: String?, tone: String?): String? {
        val pattern = name?.let { patterns[it] }
        if (pattern == null && tone == null) return null
        val id = "messages_" + (name?.lowercase()?.replace(' ', '_') ?: "phone") +
            (tone?.let { "_" + Integer.toHexString(it.hashCode()) } ?: "")
        val label = listOfNotNull(name, if (tone != null) "own sound" else null).joinToString(" · ")
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(id, "Messages: $label", NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                pattern?.let { vibrationPattern = it }
                tone?.let {
                    setSound(
                        android.net.Uri.parse(it),
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                }
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
