package com.yaz.sms.core.sms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import com.yaz.sms.MainActivity
import com.yaz.sms.R
import com.yaz.sms.core.dial.ContactLookup
import com.yaz.sms.core.dial.Numbers
import com.yaz.sms.core.dial.SalesCalls
import com.yaz.sms.core.mms.MmsStore
import com.yaz.sms.core.mms.OwnNumbers
import com.yaz.sms.core.mms.mediaWord
import com.yaz.sms.data.settings.SettingsStore
import java.util.concurrent.atomic.AtomicInteger
import org.koin.core.context.GlobalContext

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

    /**
     * The unread messages of [threadId], or nothing when all were read. The visible thread is left out
     * of the notifications: Signal's DefaultMessageNotifier (visibleThread, setVisibleThread,
     * clearVisibleThread).
     * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/notifications/v2/DefaultMessageNotifier.kt#setVisibleThread
     */
    fun show(threadId: Long, subscription: Int = SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
        if (threadId == visibleThread) return
        val unread = unread(threadId)
        if (unread.isEmpty() || (silenced(threadId) && !breaksThrough(threadId, unread.map { it.second }))) {
            cancel(threadId)
            return
        }
        // The quiet sales senders and the spam, for every arrival (SMS, MMS, chat), in one place.
        if (!getNotificationState(threadId, unread.last().first, unread.last().second, subscription)) return
        val address = unread.last().first
        val name = ContactLookup.nameOf(context, address) ?: Numbers.format(context, address)
        // Their face on the conversation, its shortcut and its bubble.
        val face = Faces.of(context, address, name)
        val sender = Person.Builder().setName(name).setKey(address).setIcon(face).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
        // The group (Signal's NotificationConversation: isGroup, getConversationTitle).
        val participants = participantsOf(threadId)
        val isGroup = participants.size > 1
        val title = if (isGroup) conversationTitle(participants) else name
        if (isGroup) style.setConversationTitle(title).setGroupConversation(true)
        // One Person per author, each line under its own (MultiMessageNotificationState: authorFullName).
        val authors = HashMap<String, Person>()
        authors[address] = sender
        unread.forEach { (from, body, date) ->
            val author = authors.getOrPut(from) {
                val authorFullName = ContactLookup.nameOf(context, from) ?: Numbers.format(context, from)
                Person.Builder().setName(authorFullName).setKey(from).setIcon(Faces.of(context, from, authorFullName)).build()
            }
            style.addMessage(Markup.plain(Effects.plain(SmsReactions.plain(body))), date, author)
        }
        val code = Codes.find(unread.last().second)
        // Someone with a vibration of their own rings on its channel: the one
        // the Contacts app keeps in the contact first, else the one set here.
        val look = com.yaz.sms.core.dial.ContactLook.ofNumber(context, address)
        val signature = look?.vibration?.takeIf { it in Signatures.patterns }
            ?: runCatching {
                org.koin.core.context.GlobalContext.get().get<com.yaz.sms.data.settings.SettingsStore>().current.signatures[Signatures.key(address)]
            }.getOrNull()
        // Their own sound too, when the Contacts app keeps one for them.
        val builder = NotificationCompat.Builder(context, Signatures.channel(context, signature, look?.tone) ?: CHANNEL)
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
        // A conversation of Android's own, so it can float as a bubble.
        val shortcut = "thread_$threadId"
        runCatching {
            androidx.core.content.pm.ShortcutManagerCompat.pushDynamicShortcut(
                context,
                androidx.core.content.pm.ShortcutInfoCompat.Builder(context, shortcut)
                    .setShortLabel(title)
                    .setLongLived(true)
                    .setPerson(sender)
                    .setIcon(face)
                    .setIntent(
                        Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_THREAD)
                            .putExtra(MainActivity.EXTRA_THREAD, threadId).putExtra(MainActivity.EXTRA_ADDRESS, address)
                    )
                    .build()
            )
            builder.setShortcutId(shortcut)
            builder.setBubbleMetadata(
                NotificationCompat.BubbleMetadata.Builder(
                    PendingIntent.getActivity(
                        context, threadId.toInt(),
                        Intent(context, com.yaz.sms.BubbleActivity::class.java)
                            .putExtra(MainActivity.EXTRA_THREAD, threadId).putExtra(MainActivity.EXTRA_ADDRESS, address),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    ),
                    face
                ).setDesiredHeight(640).build()
            )
        }
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

    /**
     * The conversation was opened or sent again: its "Not sent" notification goes, as
     * checkFailedMessages cancels the failure notification when no failed message is left.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/MessageNotificationState.java#checkFailedMessages
     */
    fun cancelFailed(threadId: Long) {
        val notificationManager = notifications
        notificationManager.cancel(FAILED_TAG, threadId.toInt())
    }

    /**
     * Read in its bubble: the notification stays, as the bubble lives only
     * with it, but leaves the shade and makes no sound again.
     */
    fun settle(threadId: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications.firstOrNull { it.id == threadId.toInt() && it.packageName == context.packageName } ?: return
        val bubble = active.notification.bubbleMetadata ?: return
        if (bubble.isNotificationSuppressed) return
        val intent = bubble.intent ?: return
        val icon = bubble.icon ?: return
        runCatching {
            val note = Notification.Builder.recoverBuilder(context, active.notification)
                .setOnlyAlertOnce(true)
                .setBubbleMetadata(
                    Notification.BubbleMetadata.Builder(intent, icon)
                        .setDesiredHeight(bubble.desiredHeight)
                        .setSuppressNotification(true)
                        .build()
                )
                .build()
            manager.notify(threadId.toInt(), note)
        }
    }

    /**
     * A message naming the user rings even in a silenced conversation unless "Mentions" is off for it.
     * Signal's NotificationStateProvider (AGPL-3.0, https://github.com/signalapp/Signal-Android,
     * notifications/v2/NotificationStateProvider.kt): includeMessage lets a message through a muted
     * thread when breaksThroughMute(), which is hasSelfMention() with mentionSetting ALWAYS_NOTIFY.
     * The user is named by their own name, as the chat and a group's SMS carry mentions ("@Name").
     */
    private fun breaksThrough(threadId: Long, bodies: List<String>): Boolean {
        if (!com.yaz.sms.core.chat.MentionSetting.of(context).notifies(threadId)) return false
        val mine = com.yaz.sms.core.chat.MentionSetting.myNames(context)
        return mine.isNotEmpty() && bodies.any { com.yaz.sms.core.chat.Mentions.mentions(it, mine) }
    }

    /** The user silenced this conversation for now. */
    private fun silenced(threadId: Long): Boolean = runCatching {
        (org.koin.core.context.GlobalContext.get().get<com.yaz.sms.data.settings.SettingsStore>().current.silenced[threadId] ?: 0L) > System.currentTimeMillis()
    }.getOrDefault(false)

    /** A message that came over the rich chat, shown in its number's conversation. */
    fun showRich(phone: String, text: String) {
        val thread = runCatching { Telephony.Threads.getOrCreateThreadId(context, phone) }.getOrNull() ?: return
        if (silenced(thread) || thread == visibleThread) return
        if (!getNotificationState(thread, phone, text, SubscriptionManager.INVALID_SUBSCRIPTION_ID)) return
        val name = ContactLookup.nameOf(context, phone) ?: Numbers.format(context, phone)
        val sender = Person.Builder().setName(name).setKey(phone).setIcon(Faces.of(context, phone, name)).build()
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName("You").build())
            .addMessage(Markup.plain(Effects.plain(text)), System.currentTimeMillis(), sender)
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

    /** A message the network refused, SMS or picture message: a tap opens its conversation, to try again. */
    fun failed(uri: Uri) {
        val thread = threadOf(uri) ?: return
        notifyMessageDeliveryFailed(thread, visibleThread)
    }

    /**
     * One notification per thread, none for the visible thread, its text the count of messages that failed.
     * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/notifications/v2/NotificationFactory.kt#notifyMessageDeliveryFailed
     */
    private fun notifyMessageDeliveryFailed(thread: Long, visibleThread: Long) {
        if (thread == visibleThread) return
        val recipient = participantsOf(thread)
        val intent = open(thread, recipient.firstOrNull().orEmpty())
        val builder = NotificationCompat.Builder(context, FAILED)
        builder.setSmallIcon(R.drawable.ic_stat_sms)
        builder.setContentTitle("Not sent")
        builder.setContentText(failedText(recipient, failedMessages(thread)))
        builder.setContentIntent(intent)
        builder.setAutoCancel(true)
        builder.setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
        post(thread.toInt(), builder.build(), FAILED_TAG)
    }

    /**
     * The people of a conversation, the user's own number left out: MessageNotificationState.scanFirstNames
     * binds the conversation's participants and skips the self participant (seenSelf); here the
     * participants are the thread's recipients in Android's store.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/MessageNotificationState.java#scanFirstNames
     */
    private fun participantsOf(conversationId: Long): List<String> = runCatching {
        val participantsData = ArrayList<String>()
        context.contentResolver.query(
            Uri.parse("content://mms-sms/conversations?simple=true"), arrayOf("recipient_ids"),
            "_id = ?", arrayOf(conversationId.toString()), null
        )?.use { participantsCursor ->
            if (participantsCursor.moveToFirst()) {
                participantsCursor.getString(0).orEmpty().split(' ').filter { it.isNotBlank() }.forEach { id ->
                    context.contentResolver.query(Uri.parse("content://mms-sms/canonical-address/$id"), arrayOf("address"), null, null, null)?.use { c ->
                        if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() }?.let { participantsData.add(it) }
                    }
                }
            }
        }
        OwnNumbers.withoutOwn(participantsData, OwnNumbers.of(context))
    }.getOrDefault(emptyList())

    /**
     * Truncate group conversation name to be displayed in the notifications: the last comma in the
     * available length, else the whole length, then an ellipsis.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/MessageNotificationState.java#truncateGroupMessageName
     */
    private fun truncateGroupMessageName(conversationName: String): String {
        if (conversationName.length <= MAX_CHARACTERS_IN_GROUP_NAME) return conversationName
        var endIndex = MAX_CHARACTERS_IN_GROUP_NAME
        for (i in MAX_CHARACTERS_IN_GROUP_NAME downTo 0) {
            if (conversationName[i] == ',') {
                endIndex = i
                break
            }
        }
        return conversationName.substring(0, endIndex) + '…'
    }

    /** The group's name over its lines (MultiMessageNotificationState: mTitle = mGroupConversationName): its people. */
    private fun conversationTitle(participants: List<String>): String =
        participants.joinToString(", ") { ContactLookup.nameOf(context, it) ?: Numbers.format(context, it) }

    /** "Not sent" under the title: the one message or the count, to the person or the group (shortened). */
    private fun failedText(recipient: List<String>, failedMessages: Int): String {
        val name = if (recipient.size > 1) truncateGroupMessageName(conversationTitle(recipient))
        else recipient.firstOrNull()?.let { ContactLookup.nameOf(context, it) ?: Numbers.format(context, it) }.orEmpty()
        val count = failedMessages.coerceAtLeast(1)
        val to = if (name.isBlank()) "" else " to $name"
        return if (count == 1) "Your message$to. Tap to try again." else "$count messages$to. Tap to try again."
    }

    /**
     * The failed messages of one conversation, SMS and picture messages together, as
     * MessageNotificationState.checkFailedMessages collects them (failedMessages, one cursor row each).
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/MessageNotificationState.java#checkFailedMessages
     */
    private fun failedMessages(thread: Long): Int {
        val failedMessages = ArrayList<Int>()
        runCatching {
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.THREAD_ID} = ? AND ${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_FAILED}", arrayOf(thread.toString()), null
            )?.use { messageDataCursor ->
                while (messageDataCursor.moveToNext()) failedMessages.add(messageDataCursor.position)
            }
        }
        runCatching {
            context.contentResolver.query(
                Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID),
                "${Telephony.Mms.THREAD_ID} = ? AND ${Telephony.Mms.MESSAGE_BOX} = ${Telephony.Mms.MESSAGE_BOX_FAILED}", arrayOf(thread.toString()), null
            )?.use { messageDataCursor ->
                while (messageDataCursor.moveToNext()) failedMessages.add(messageDataCursor.position)
            }
        }
        return failedMessages.size
    }

    /**
     * A class 0 (flash) message is shown, never kept: AOSP's SmsReceiver.deliverSmsMessages gives it to
     * ClassZeroActivity instead of ReceiveSmsMessageAction. Here a high priority notification with the
     * text (AOSP's postNewMessageSecondaryUserNotification: PRIORITY_HIGH, BigTextStyle, notificationManager.notify),
     * which needs no screen of ours; it goes when tapped or swiped away.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/receiver/SmsReceiver.java#postNewMessageSecondaryUserNotification
     */
    fun postNewMessageNotification(address: String, messageValues: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL)
        builder.setContentTitle(ContactLookup.nameOf(context, address) ?: Numbers.format(context, address))
            .setSmallIcon(R.drawable.ic_stat_sms)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_sms).setContentTitle("New message").build())
        val bigTextStyle = NotificationCompat.BigTextStyle(builder)
        bigTextStyle.bigText(messageValues)
        val notification = builder.build()
        // Not ported: notification.defaults (the channel decides sound and vibration) and the ticker.
        val notificationManager = notifications
        if (!notificationManager.areNotificationsEnabled()) return
        runCatching { notificationManager.notify(CLASS_ZERO_TAG, classZero.getAndIncrement().mod(CLASS_ZERO_SLOTS), notification) }
    }

    /**
     * The conversation of a failed message of the store, SMS or picture message: checkFailedMessages binds the
     * messageData of the cursor row and takes its conversationId.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/MessageNotificationState.java#checkFailedMessages
     */
    private fun threadOf(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(Telephony.Sms.THREAD_ID), null, null, null)?.use { messageDataCursor ->
            if (messageDataCursor.moveToFirst()) messageDataCursor.getLong(0) else null
        }
    }.getOrNull()

    /**
     * Whether an arrival goes into a notification at all: MessageNotificationState.getNotificationState makes
     * no state (no notification) for a conversation that has none to show. Here no notification for a sales
     * sender the user chose to keep quiet (kept, not shown), nor for spam (Google Messages: "you won't get
     * notifications for spam"): one test for the SMS, the picture message and the chat.
     * https://raw.githubusercontent.com/LineageOS/android_packages_apps_Messaging/lineage-23.0/src/com/android/messaging/datamodel/MessageNotificationState.java#getNotificationState
     */
    private fun getNotificationState(conversationId: Long, mAuthorFullName: String, mContent: String, mSubId: Int): Boolean {
        val state = GlobalContext.getOrNull() ?: return true
        val convInfo = state.get<SettingsStore>().current
        val convList = convInfo.quietSales && SalesCalls.isSalesCall(mAuthorFullName, SalesCalls.country(context, subscription = mSubId)) &&
            !ContactLookup.isContact(context, mAuthorFullName)
        if (convList || SpamJudge.arrivedIsSpam(context, conversationId, mAuthorFullName, mContent)) {
            Log.v(TAG, "MessageNotificationState: No unseen notifications")
            return false
        }
        return true
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

    private fun post(id: Int, note: Notification, tag: String? = null) {
        if (!notifications.areNotificationsEnabled()) return
        runCatching { notifications.notify(tag, id, note) }
    }

    companion object {
        private const val CHANNEL = "messages"
        private const val FAILED = "failed"
        private const val FAILED_TAG = "failed"
        private const val CLASS_ZERO_TAG = "classzero"
        private const val CLASS_ZERO_SLOTS = 20
        private const val PICTURE_ID = -3
        private const val TAG = "MessageNotifier"

        /** AOSP's MAX_CHARACTERS_IN_GROUP_NAME: a group's name is cut beyond it in a notification. */
        private const val MAX_CHARACTERS_IN_GROUP_NAME = 30

        private val classZero = AtomicInteger()

        /**
         * The conversation open on screen, -1 when none: nothing is posted for it. The thread screen sets it
         * when it resumes (setVisibleThread) and clears it when it pauses (clearVisibleThread).
         * https://raw.githubusercontent.com/signalapp/Signal-Android/main/app/src/main/java/org/thoughtcrime/securesms/notifications/v2/DefaultMessageNotifier.kt#setVisibleThread
         */
        @Volatile
        var visibleThread: Long = -1L

        fun clearVisibleThread() {
            visibleThread = -1L
        }

        /** visibleThread.compareAndSet(conversationId, null): cleared only if it is still the visible one. */
        @Synchronized
        fun clearVisibleThread(conversationId: Long) {
            if (visibleThread == conversationId) visibleThread = -1L
        }
    }
}
