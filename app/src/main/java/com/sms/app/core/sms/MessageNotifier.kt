package com.sms.app.core.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.sms.app.MainActivity
import com.sms.app.R
import com.sms.app.core.dial.ContactLookup
import com.sms.app.core.dial.Numbers
import com.sms.app.core.mms.MmsStore
import com.sms.app.core.mms.mediaWord

/**
 * The notifications of new messages, one per conversation, as Android
 * shows conversations: reply from it, mark it read, copy a code in one
 * tap. On a lock screen that hides content, only "New message".
 */
class MessageNotifier(private val context: Context) {

    private val notifications = NotificationManagerCompat.from(context)

    init {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Messages", NotificationManager.IMPORTANCE_HIGH))
        manager.createNotificationChannel(NotificationChannel(FAILED, "Messages not sent", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** The unread messages of [threadId], or nothing when all were read. */
    fun show(threadId: Long) {
        val unread = unread(threadId)
        if (unread.isEmpty()) {
            cancel(threadId)
            return
        }
        val address = unread.last().first
        val name = ContactLookup.nameOf(context, address) ?: Numbers.format(context, address)
        val sender = Person.Builder().setName(name).setKey(address).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        unread.forEach { (_, body, date) -> style.addMessage(body, date, sender) }
        val code = Codes.find(unread.last().second)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open(threadId, address))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setWhen(unread.last().third)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_stat_sms)
                    .setContentTitle(if (unread.size == 1) "New message" else "${unread.size} new messages")
                    .build()
            )
        if (code != null) {
            builder.addAction(
                NotificationCompat.Action.Builder(null, "Copy $code", action(NotificationActions.ACTION_COPY, threadId, address, code)).build()
            )
        }
        builder.addAction(
            NotificationCompat.Action.Builder(null, "Reply", reply(threadId, address))
                .addRemoteInput(RemoteInput.Builder(NotificationActions.KEY_REPLY).setLabel("Reply").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
        )
        builder.addAction(
            NotificationCompat.Action.Builder(null, "Mark as read", action(NotificationActions.ACTION_READ, threadId, address, null))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build()
        )
        post(threadId.toInt(), builder.build())
    }

    fun cancel(threadId: Long) = notifications.cancel(threadId.toInt())

    /** A message that came over the rich chat, shown in its number's conversation. */
    fun showRich(phone: String, text: String) {
        val thread = runCatching { Telephony.Threads.getOrCreateThreadId(context, phone) }.getOrNull() ?: return
        val name = ContactLookup.nameOf(context, phone) ?: Numbers.format(context, phone)
        val sender = Person.Builder().setName(name).setKey(phone).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage(text, System.currentTimeMillis(), sender)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(open(thread, phone))
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_sms).setContentTitle("New message").build())
        Codes.find(text)?.let { code ->
            builder.addAction(NotificationCompat.Action.Builder(null, "Copy $code", action(NotificationActions.ACTION_COPY, thread, phone, code)).build())
        }
        builder.addAction(
            NotificationCompat.Action.Builder(null, "Reply", reply(thread, phone))
                .addRemoteInput(RemoteInput.Builder(NotificationActions.KEY_REPLY).setLabel("Reply").build())
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .setShowsUserInterface(false)
                .build()
        )
        post(thread.toInt(), builder.build())
    }

    /** A message the network refused: a tap opens its conversation, to try again. */
    fun failed(uri: Uri) {
        val (thread, address) = runCatching {
            context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID, Telephony.Sms.ADDRESS), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getLong(0) to c.getString(1).orEmpty() else null
            }
        }.getOrNull() ?: return
        val name = ContactLookup.nameOf(context, address) ?: Numbers.format(context, address)
        post(
            FAILED_ID,
            NotificationCompat.Builder(context, FAILED)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("Not sent")
                .setContentText("Your message to $name. Tap to try again.")
                .setContentIntent(open(thread, address))
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        )
    }

    /** A picture message that could not be downloaded. */
    fun pictureFailed(from: String?) {
        val name = from?.takeIf { it.isNotBlank() }?.let { ContactLookup.nameOf(context, it) ?: Numbers.format(context, it) }
        post(
            PICTURE_ID,
            NotificationCompat.Builder(context, FAILED)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle("Picture message not downloaded")
                .setContentText(if (name != null) "From $name. Check mobile data." else "Check mobile data.")
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        )
    }

    /** The unread received messages of a thread, SMS and MMS, oldest first: address, text, time. */
    private fun unread(threadId: Long): List<Triple<String, String, Long>> {
        val out = ArrayList<Triple<String, String, Long>>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.READ} = 0", arrayOf(threadId.toString()),
                "${Telephony.Sms.DATE} DESC LIMIT 8"
            )?.use { c ->
                while (c.moveToNext()) out += Triple(c.getString(0).orEmpty(), c.getString(1).orEmpty(), c.getLong(2))
            }
        }
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.READ} = 0", arrayOf(threadId.toString()),
                "${Telephony.Mms.DATE} DESC LIMIT 8"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val (text, media) = MmsStore.parts(context, id)
                    val shown = text.ifBlank { mediaWord(media.firstOrNull()?.contentType) }
                    out += Triple(MmsStore.sender(context, id).orEmpty(), shown, c.getLong(1) * 1000)
                }
            }
        }
        return out.sortedBy { it.third }.takeLast(8)
    }

    private fun open(threadId: Long, address: String): PendingIntent = PendingIntent.getActivity(
        context, threadId.toInt(),
        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_THREAD)
            .putExtra(MainActivity.EXTRA_THREAD, threadId).putExtra(MainActivity.EXTRA_ADDRESS, address)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Mutable: Android puts the typed reply into it. Explicit, for this app only. */
    private fun reply(threadId: Long, address: String): PendingIntent = PendingIntent.getBroadcast(
        context, threadId.toInt() * 4 + 1,
        Intent(context, NotificationActions::class.java).setAction(NotificationActions.ACTION_REPLY)
            .putExtra(NotificationActions.EXTRA_THREAD, threadId).putExtra(NotificationActions.EXTRA_ADDRESS, address),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun action(name: String, threadId: Long, address: String, code: String?): PendingIntent = PendingIntent.getBroadcast(
        context, threadId.toInt() * 4 + if (name == NotificationActions.ACTION_READ) 2 else 3,
        Intent(context, NotificationActions::class.java).setAction(name)
            .putExtra(NotificationActions.EXTRA_THREAD, threadId).putExtra(NotificationActions.EXTRA_ADDRESS, address)
            .putExtra(NotificationActions.EXTRA_CODE, code),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun post(id: Int, note: Notification) {
        if (!notifications.areNotificationsEnabled()) return
        runCatching { notifications.notify(id, note) }
    }

    private companion object {
        const val CHANNEL = "messages"
        const val FAILED = "failed"
        const val FAILED_ID = -2
        const val PICTURE_ID = -3
    }
}
