package com.yaz.sms.core.mms

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import com.yaz.sms.core.dial.T9

/**
 * The user's own numbers, kept out of a conversation's people: a message
 * sent to one person and to oneself is a conversation with that person.
 */
object OwnNumbers {

    /**
     * This phone's numbers, one per active SIM: SubscriptionManager.getPhoneNumber (API 33,
     * developer.android.com/reference/android/telephony/SubscriptionManager#getPhoneNumber(int);
     * the default SMS app may read it), else the SIM's stored number as AOSP Messaging's
     * PhoneUtils.getSelfRawNumber (android.googlesource.com/platform/packages/apps/Messaging,
     * src/com/android/messaging/util/PhoneUtils.java) reads SubscriptionInfo.getNumber.
     */
    @Suppress("DEPRECATION")
    fun of(context: Context): List<String> = runCatching {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        subscriptions.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
            val number = runCatching {
                if (Build.VERSION.SDK_INT >= 33) subscriptions.getPhoneNumber(info.subscriptionId) else info.number
            }.getOrNull()
            number?.takeIf { it.isNotBlank() }
        }.distinct()
    }.getOrDefault(emptyList())

    /** Whether Android gives the number of one SIM (AOSP Messaging's PduPersister is told it as subPhoneNumber). */
    @Suppress("DEPRECATION")
    fun knownFor(context: Context, subId: Int): Boolean = runCatching {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java) ?: return false
        val number = if (Build.VERSION.SDK_INT >= 33) subscriptions.getPhoneNumber(subId)
        else subscriptions.getActiveSubscriptionInfo(subId)?.number
        !number.isNullOrBlank()
    }.getOrDefault(false)

    fun isOwn(number: String, own: List<String>): Boolean =
        own.any { T9.sameDigits(T9.clean(it), T9.clean(number)) }

    /**
     * The people of a received picture message, as AOSP Messaging's mmslib PduPersister.persist
     * and checkAndLoadToCcRecipients (android.googlesource.com/platform/packages/apps/Messaging,
     * src/com/android/messaging/mmslib/pdu/PduPersister.java): the sender, and the TO and CC
     * addresses without one's own number; when the SIM's number is unknown and TO and CC hold
     * one address only, that address is oneself and is left out too.
     */
    fun peopleOf(from: String, toAndCc: List<String>, own: List<String>, simNumberKnown: Boolean): List<String> {
        val others = if (!simNumberKnown && toAndCc.size == 1) emptyList()
        else toAndCc.filter { !isOwn(it, own) }
        return (listOf(from) + others).filter { it.isNotBlank() }.distinct()
    }

    /**
     * A conversation's recipients as shown and answered: the own numbers taken out when others
     * remain (a conversation with oneself alone stays as it is). Repairs a thread Android made
     * of the sender and oneself without touching the store.
     */
    fun withoutOwn(people: List<String>, own: List<String>): List<String> {
        if (people.size < 2 || own.isEmpty()) return people
        val others = people.filter { !isOwn(it, own) }
        return others.ifEmpty { people }
    }
}
