package com.yaz.sms.core.dial

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.BlockedNumberContract

/**
 * What the Contacts app may ask about the user's exchanges, for "keep in
 * touch" and "add those you talk to often": when the last one with each of
 * some numbers was, and which unsaved numbers come back often. Read here
 * and answered, nothing kept. Only apps signed with the same key reach it
 * (a permission of signature level, checked again here: Android does not
 * check call()).
 */
abstract class PeopleDoor : ContentProvider() {

    /**
     * Each exchange since [since] (epoch milliseconds), newest first: the
     * other side's number and when. [visit] returns false to stop.
     */
    protected abstract fun exchanges(context: Context, since: Long, visit: (number: String, at: Long) -> Boolean)

    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context ?: return null
        if (context.checkCallingPermission(context.packageName + ".permission.PEOPLE") != PackageManager.PERMISSION_GRANTED) throw SecurityException("Not allowed")
        return when (method) {
            "last" -> last(context, extras?.getStringArrayList("numbers").orEmpty().take(MAX_NUMBERS))
            "frequent" -> frequent(context, (extras?.getInt("days") ?: 30).coerceIn(1, 90))
            else -> null
        }
    }

    private fun last(context: Context, numbers: List<String>): Bundle {
        val keys = numbers.map(::key)
        val wanted = keys.filterNotNull().toMutableSet()
        val at = HashMap<String, Long>()
        if (wanted.isNotEmpty()) exchanges(context, 0L) { number, date ->
            val k = key(number)
            if (k != null && k in wanted) {
                at[k] = date
                wanted -= k
            }
            wanted.isNotEmpty()
        }
        return Bundle().apply { putLongArray("at", LongArray(keys.size) { i -> keys[i]?.let { at[it] } ?: 0L }) }
    }

    private fun frequent(context: Context, days: Int): Bundle {
        val since = System.currentTimeMillis() - days * DAY_MS
        class Seen(val number: String) { var count = 0; val days = HashSet<Long>() }
        val seen = LinkedHashMap<String, Seen>()
        exchanges(context, since) { number, date ->
            val k = key(number)
            if (k != null) seen.getOrPut(k) { Seen(number) }.apply { count++; this.days += date / DAY_MS }
            true
        }
        // Three days at least, never a saved, private or blocked number.
        val chosen = seen.values.filter { it.days.size >= 3 }
            .filter { ContactLookup.nameOf(context, it.number) == null }
            .filter { runCatching { !BlockedNumberContract.isBlocked(context, it.number) }.getOrDefault(true) }
            .sortedByDescending { it.count }
            .take(MAX_NUMBERS)
        return Bundle().apply {
            putStringArrayList("numbers", ArrayList(chosen.map { it.number }))
            putIntArray("counts", chosen.map { it.count }.toIntArray())
        }
    }

    /** The same number written 06… or +33 6…, as the lists compare them; null for short and hidden numbers. */
    private fun key(number: String): String? {
        val digits = number.filter(Char::isDigit)
        return if (digits.length < 7) null else digits.takeLast(9)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0

    private companion object {
        const val MAX_NUMBERS = 500
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
