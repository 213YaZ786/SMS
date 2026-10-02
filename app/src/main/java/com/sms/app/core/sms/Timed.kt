package com.sms.app.core.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.sms.app.core.chat.RichChat
import com.sms.app.core.common.writeTextAtomically
import com.sms.app.data.settings.SettingsStore
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** A message written now to go later. */
@Serializable
data class Scheduled(val id: Long, val to: List<String>, val text: String, val at: Long, val sub: Int)

/**
 * What waits for a time: conversations set aside ("Later") and messages
 * to send later. Android's inexact alarms, within ten minutes of the
 * time, so no permission is needed.
 */
object Timed {

    private const val ACTION_LATER = "com.sms.app.LATER"
    private const val ACTION_SEND = "com.sms.app.SCHEDULED"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    /** The usual choices: in an hour, this evening at 8, tomorrow at 8. */
    fun choices(now: Long = System.currentTimeMillis()): List<Pair<String, Long>> {
        val zone = ZoneId.systemDefault()
        val evening = LocalDate.now().atTime(LocalTime.of(20, 0)).atZone(zone).toInstant().toEpochMilli()
        val morning = LocalDate.now().plusDays(1).atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
        return listOfNotNull(
            "In 1 hour" to now + 60 * 60 * 1000,
            if (evening > now + 30 * 60 * 1000) "This evening, 8 PM" to evening else null,
            "Tomorrow, 8 AM" to morning
        )
    }

    fun later(context: Context, settings: SettingsStore, threadId: Long, at: Long) {
        settings.update { it.copy(later = it.later + (threadId to at)) }
        alarm(context, ACTION_LATER, threadId.toInt(), at) { putExtra("thread", threadId) }
    }

    fun schedule(context: Context, message: Scheduled) {
        save(context, load(context) + message)
        alarm(context, ACTION_SEND, message.id.toInt(), message.at) { putExtra("id", message.id) }
    }

    fun cancel(context: Context, id: Long) {
        save(context, load(context).filterNot { it.id == id })
        context.getSystemService(AlarmManager::class.java).cancel(intent(context, ACTION_SEND, id.toInt()) { putExtra("id", id) })
    }

    fun scheduled(context: Context): List<Scheduled> = load(context)

    private fun alarm(context: Context, action: String, code: Int, at: Long, extras: Intent.() -> Unit) {
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, intent(context, action, code, extras))
    }

    private fun intent(context: Context, action: String, code: Int, extras: Intent.() -> Unit) = PendingIntent.getBroadcast(
        context, code, Intent(context, TimedReceiver::class.java).setAction(action).apply(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun file(context: Context) = File(context.filesDir, "scheduled.json")
    private fun load(context: Context): List<Scheduled> = runCatching { json.decodeFromString<List<Scheduled>>(file(context).readText()) }.getOrDefault(emptyList())
    private fun save(context: Context, list: List<Scheduled>) { runCatching { file(context).writeTextAtomically(json.encodeToString(list)) } }

    internal fun take(context: Context, id: Long): Scheduled? = load(context).firstOrNull { it.id == id }?.also { s -> save(context, load(context).filterNot { it.id == s.id }) }

    internal const val LATER = ACTION_LATER
    internal const val SEND = ACTION_SEND
}

/** A time came: a conversation set aside comes back, a scheduled message goes. Not exported. */
class TimedReceiver : BroadcastReceiver(), KoinComponent {
    private val settings: SettingsStore by inject()
    private val chat: RichChat by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    Timed.LATER -> {
                        val thread = intent.getLongExtra("thread", -1)
                        settings.update { it.copy(later = it.later - thread) }
                        MessageNotifier(app).reminder(thread)
                    }
                    Timed.SEND -> {
                        val s = Timed.take(app, intent.getLongExtra("id", -1)) ?: return@launch
                        val one = s.to.singleOrNull()
                        // Over the encrypted chat when the number, or every member of the group, has it.
                        if (chat.send(s.to, s.text, emptyList(), null)) return@launch
                        if (one != null) SmsSender.send(app, one, s.text, s.sub)
                        else com.sms.app.core.mms.MmsTransport.send(app, s.to, s.text, emptyList(), s.sub)
                    }
                }
            } finally {
                done.finish()
            }
        }
    }
}
