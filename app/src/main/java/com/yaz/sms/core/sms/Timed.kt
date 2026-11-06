package com.yaz.sms.core.sms

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.yaz.sms.core.chat.RichChat
import com.yaz.sms.core.common.writeTextAtomically
import com.yaz.sms.data.settings.SettingsStore
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject

/** A message written now to go later. */
@Serializable
data class Scheduled(
    val id: Long, val to: List<String>, val text: String, val at: Long, val sub: Int,
    /** A copy kept by PendingSends while a message waits in its undo delay, sent only if the app died meanwhile; never listed. */
    val backup: Boolean = false,
    /** How many times a send that returned nothing was put back; at [Timed.MAX_TRIES] it stays listed, with no alarm. */
    val tries: Int = 0
)

/**
 * What waits for a time: messages to send later. Android's inexact alarms, within ten minutes of the
 * time, so no permission is needed.
 */
object Timed {

    private const val ACTION_LATER = "com.yaz.sms.LATER"
    private const val ACTION_SEND = "com.yaz.sms.SCHEDULED"
    private const val WINDOW_MS = 10 * 60 * 1000L
    private val json = Json { ignoreUnknownKeys = true }

    /** A send that returned nothing is put back this long after, at most [MAX_TRIES] times. */
    internal const val RETRY_MS = 15 * 60 * 1000L
    internal const val MAX_TRIES = 4

    // One lock around every read, change and write of scheduled.json (a schedule, a take and a cancel arriving
    // together never lose each other's change): the Mutex of Feeder's RssLocalSync.syncMutex, taken with withLock
    // https://raw.githubusercontent.com/spacecowboy/Feeder/master/app/src/main/java/com/nononsenseapps/feeder/model/RssLocalSync.kt#L44-L47
    private val mutex = Mutex()

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

    /** Writes [message] and arms its alarm; false when it could not be saved (then no alarm is set). A message put back replaces its entry. */
    fun schedule(context: Context, message: Scheduled): Boolean = runBlocking {
        mutex.withLock {
            if (!save(context, load(context).filterNot { it.id == message.id } + message)) return@withLock false
            if (message.tries < MAX_TRIES) alarm(context, ACTION_SEND, message.id.toInt(), message.at) { putExtra("id", message.id) }
            keepAcrossReboots(context)
            true
        }
    }

    /** Removes the entry and its alarm; false when the file could not be written (both then stay, as they are). */
    fun cancel(context: Context, id: Long): Boolean = runBlocking {
        mutex.withLock {
            if (!save(context, load(context).filterNot { it.id == id })) return@withLock false
            context.getSystemService(AlarmManager::class.java).cancel(intent(context, ACTION_SEND, id.toInt()) { putExtra("id", id) })
            keepAcrossReboots(context)
            true
        }
    }

    /**
     * Every waiting message armed again: alarms die with a reboot. Android's recipe
     * (https://developer.android.com/develop/background-work/services/alarms/schedule,
     * "Start an alarm when the device restarts"), run as Fossify Messages'
     * RescheduleAlarmsReceiver runs it; a time already past fires at once.
     */
    fun rearm(context: Context) = runBlocking {
        mutex.withLock {
            // A message put back too many times stays listed, its alarm is not set again.
            load(context).filter { it.tries < MAX_TRIES }.forEach { m -> alarm(context, ACTION_SEND, m.id.toInt(), m.at) { putExtra("id", m.id) } }
            keepAcrossReboots(context)
        }
    }

    /** The boot receiver on only while a message waits, as the same Android recipe enables and disables it. */
    private fun keepAcrossReboots(context: Context) {
        val on = load(context).isNotEmpty()
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                android.content.ComponentName(context, TimedRearmReceiver::class.java),
                if (on) android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                android.content.pm.PackageManager.DONT_KILL_APP
            )
        }
    }

    /** The messages the user set to go later: the backups of messages in their undo delay are not among them. */
    fun scheduled(context: Context): List<Scheduled> = load(context).filterNot { it.backup }

    private fun alarm(context: Context, action: String, code: Int, at: Long, extras: Intent.() -> Unit) {
        context.getSystemService(AlarmManager::class.java).setWindow(AlarmManager.RTC_WAKEUP, at, WINDOW_MS, intent(context, action, code, extras))
    }

    private fun intent(context: Context, action: String, code: Int, extras: Intent.() -> Unit) = PendingIntent.getBroadcast(
        context, code, Intent(context, TimedReceiver::class.java).setAction(action).apply(extras),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun file(context: Context) = File(context.filesDir, "scheduled.json")
    private fun load(context: Context): List<Scheduled> = runCatching { json.decodeFromString<List<Scheduled>>(file(context).readText()) }.getOrDefault(emptyList())
    private fun save(context: Context, list: List<Scheduled>): Boolean = runCatching { file(context).writeTextAtomically(json.encodeToString(list)) }.isSuccess

    /**
     * The entry [id] taken out of the file to be sent, or null when it is not there or the file could not be
     * written (then nothing is sent: the entry is still listed). A send that returns nothing puts it back
     * with [schedule] (see TimedReceiver).
     */
    internal fun take(context: Context, id: Long): Scheduled? = runBlocking {
        mutex.withLock {
            val all = load(context)
            val s = all.firstOrNull { it.id == id } ?: return@withLock null
            if (!save(context, all.filterNot { it.id == id })) return@withLock null
            keepAcrossReboots(context)
            s
        }
    }

    internal const val LATER = ACTION_LATER
    internal const val SEND = ACTION_SEND
}

/**
 * After a reboot, an update of the app, or the clock or time zone changed: the waiting
 * messages armed again. Android's boot-receiver recipe
 * (https://developer.android.com/develop/background-work/services/alarms/schedule),
 * with the actions Fossify Messages' RescheduleAlarmsReceiver listens to. Not exported;
 * disabled while nothing waits.
 */
class TimedRearmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        Thread {
            try {
                Timed.rearm(context.applicationContext)
            } finally {
                done.finish()
            }
        }.start()
    }
}

/** A time came: a scheduled message goes. Not exported. */
class TimedReceiver : BroadcastReceiver(), KoinComponent {
    private val settings: SettingsStore by inject()
    private val chat: RichChat by inject()

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    // Set aside conversations are gone; an alarm left from before does nothing.
                    Timed.LATER -> Unit
                    Timed.SEND -> {
                        val s = Timed.take(app, intent.getLongExtra("id", -1)) ?: return@launch
                        val one = s.to.singleOrNull()
                        // Over the encrypted chat when the number, or every member of the group, has it.
                        if (chat.send(s.to, s.text, emptyList(), null)) return@launch
                        // A group set to "One SMS each" keeps it here too: a group MMS would show every number
                        // to everyone (PendingSends.broadcast, AOSP Messaging's broadcast SMS).
                        val each = if (one == null) runCatching { android.provider.Telephony.Threads.getOrCreateThreadId(app, s.to.toSet()) }.getOrNull()
                            ?.takeIf { com.yaz.sms.data.settings.BroadcastStore.of(app).contains(it) } else null
                        // The link is proven (or the group is ready): a failed chat send never falls back to a plain
                        // SMS or MMS, the entry is kept and put back below (as in PendingSends.deliver).
                        val encrypted = (one != null && chat.linkFor(one) != null) || chat.groupReady(s.to)
                        val sent: Any? = if (encrypted) null
                        else if (each != null) get<PendingSends>().broadcast(each, s.to, s.text, s.sub).takeIf { it }
                        else if (one != null) SmsSender.send(app, one, s.text, s.sub)
                        else com.yaz.sms.core.mms.MmsTransport.send(app, s.to, s.text, emptyList(), s.sub)
                        // A send that returned nothing is not lost: the entry is put back for a later try, as
                        // ResendMessageAction puts a message back to yet-to-send; after MAX_TRIES it stays
                        // listed in Send later with no alarm, and the user is told.
                        // https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/action/ResendMessageAction.java#executeAction
                        if (sent == null) {
                            val tries = s.tries + 1
                            Timed.schedule(app, s.copy(at = System.currentTimeMillis() + Timed.RETRY_MS, tries = tries))
                            val reason = if (encrypted) "Not sent: the encrypted chat is not available." else "Not sent: a message set for later could not be sent. It stays in Send later."
                            if (encrypted || tries >= Timed.MAX_TRIES) {
                                android.os.Handler(android.os.Looper.getMainLooper()).post {
                                    android.widget.Toast.makeText(app, reason, android.widget.Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                }
            } finally {
                done.finish()
            }
        }
    }
}
