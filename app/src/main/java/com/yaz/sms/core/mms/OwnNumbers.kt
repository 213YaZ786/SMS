package com.yaz.sms.core.mms

import android.content.Context
import android.os.Build
import android.telephony.SubscriptionManager
import com.yaz.sms.core.dial.T9
import com.yaz.sms.data.settings.SettingsStore

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
    fun sim(context: Context): List<String> = runCatching {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java) ?: return emptyList()
        subscriptions.activeSubscriptionInfoList.orEmpty().mapNotNull { info ->
            val number = runCatching {
                if (Build.VERSION.SDK_INT >= 33) subscriptions.getPhoneNumber(info.subscriptionId) else info.number
            }.getOrNull()
            number?.takeIf { it.isNotBlank() }
        }.distinct()
    }.getOrDefault(emptyList())

    /** The number the user wrote in Settings, "My number", or null. */
    fun typed(context: Context): String? =
        runCatching { SettingsStore(context).current.ownNumber.trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * Every number that is the user's: the SIMs' and the one written by hand. AOSP Messaging's
     * PhoneUtils.getSelfRawNumber(allowOverride) lets a number typed in its "Phone number" setting
     * (mms_phone_number_pref_key; Google Messages: Settings, Advanced, Phone number) stand for a SIM
     * that gives none; here it is one more of the user's numbers, so that a SIM that gives one
     * stays recognised too.
     */
    fun of(context: Context): List<String> = (sim(context) + listOfNotNull(typed(context))).distinct()

    /**
     * Whether Android or the user gives the number of one SIM (AOSP Messaging's PduPersister is told it
     * as subPhoneNumber, taken from getSelfRawNumber(true), the number typed in its settings included).
     */
    @Suppress("DEPRECATION")
    fun knownFor(context: Context, subId: Int): Boolean = typed(context) != null || runCatching {
        val subscriptions = context.getSystemService(SubscriptionManager::class.java) ?: return false
        val number = if (Build.VERSION.SDK_INT >= 33) subscriptions.getPhoneNumber(subId)
        else subscriptions.getActiveSubscriptionInfo(subId)?.number
        !number.isNullOrBlank()
    }.getOrDefault(false)

    fun isOwn(number: String, own: List<String>): Boolean =
        own.any { T9.sameDigits(T9.clean(it), T9.clean(number)) }


    /**
     * A conversation's participants cleaned as AOSP Messaging's BugleDatabaseOperations
     * .sanitizeConversationParticipants does before it looks a conversation up
     * (android.googlesource.com/platform/packages/apps/Messaging, src/com/android/messaging/datamodel/
     * BugleDatabaseOperations.java): the same number twice is kept once; when more than one remain, every
     * own number is taken out (a group of others with oneself in it is the group of the others); when
     * all of them are oneself, the list stays, "maybe that's what the user wants". AOSP keeps the last of
     * two equal numbers, here the first stays, so that the sender is still the conversation's first person.
     */
    fun sanitize(people: List<String>, own: List<String>): List<String> {
        // The same address, or the same number written two ways (AOSP compares the normalized destination).
        val once = ArrayList<String>()
        val digits = ArrayList<String>()
        for (p in people) {
            if (p.isBlank()) continue
            val d = T9.clean(p)
            if (once.indices.none { once[it] == p || T9.sameDigits(digits[it], d) }) {
                once += p
                digits += d
            }
        }
        if (once.size > 1 && own.isNotEmpty()) {
            val others = once.filter { !isOwn(it, own) }
            if (others.isNotEmpty()) return others
        }
        return once
    }

    /**
     * The people of a received picture message, as AOSP Messaging's mmslib PduPersister.persist
     * and checkAndLoadToCcRecipients (android.googlesource.com/platform/packages/apps/Messaging,
     * src/com/android/messaging/mmslib/pdu/PduPersister.java): the sender, and the TO and CC
     * addresses; when the SIM's number is unknown and TO and CC hold one address only, that address
     * is oneself and is left out. Then [sanitize], so that a group whose TO holds the user's number
     * is the group of the others, as it was the first time.
     */
    fun peopleOf(from: String, toAndCc: List<String>, own: List<String>, simNumberKnown: Boolean): List<String> {
        val others = if (!simNumberKnown && toAndCc.size == 1) emptyList() else toAndCc
        return sanitize(listOf(from) + others, own)
    }

    /**
     * A conversation's recipients as shown and answered: the same sanitizing ([sanitize]) of what
     * Android's thread holds. Repairs a thread Android made of the sender and oneself without
     * touching the store.
     */
    fun withoutOwn(people: List<String>, own: List<String>): List<String> {
        if (people.size < 2) return people
        return sanitize(people, own)
    }
}
