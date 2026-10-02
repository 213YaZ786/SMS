package com.sms.app.core.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Where each message of the encrypted chat sits in Android's store, kept
 * in a small database of the app's own: a row written per change, never
 * the whole list again, so years of messages cost no more than a few.
 */
class RefStore(context: Context) : SQLiteOpenHelper(context, "chat-refs.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE refs (msg INTEGER PRIMARY KEY, mms INTEGER NOT NULL, row INTEGER NOT NULL, seen INTEGER NOT NULL, " +
                "reactions TEXT NOT NULL, edited INTEGER NOT NULL, pinned INTEGER NOT NULL, mine INTEGER NOT NULL, chat INTEGER NOT NULL, " +
                "vanish_at INTEGER NOT NULL, vanish_for INTEGER NOT NULL)"
        )
        db.execSQL("CREATE INDEX refs_row ON refs (mms, row)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun all(): Map<Int, RichRef> {
        val out = HashMap<Int, RichRef>()
        readableDatabase.query("refs", null, null, null, null, null, null).use { c ->
            while (c.moveToNext()) {
                out[c.getInt(0)] = RichRef(
                    mms = c.getInt(1) != 0,
                    id = c.getLong(2),
                    seen = c.getInt(3) != 0,
                    reactions = c.getString(4).split('\u001f').filter { it.isNotEmpty() },
                    edited = c.getInt(5) != 0,
                    pinned = c.getInt(6) != 0,
                    mine = c.getInt(7) != 0,
                    chat = c.getInt(8),
                    vanishAt = c.getLong(9),
                    vanishFor = c.getInt(10)
                )
            }
        }
        return out
    }

    /** [ref] for [msg]; another one pointing at the same row of the store goes. */
    fun put(msg: Int, ref: RichRef) {
        writableDatabase.run {
            beginTransaction()
            try {
                delete("refs", "mms = ? AND row = ? AND msg != ?", arrayOf(if (ref.mms) "1" else "0", ref.id.toString(), msg.toString()))
                insertWithOnConflict("refs", null, values(msg, ref), SQLiteDatabase.CONFLICT_REPLACE)
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun putAll(refs: Map<Int, RichRef>) {
        writableDatabase.run {
            beginTransaction()
            try {
                refs.forEach { (msg, ref) -> insertWithOnConflict("refs", null, values(msg, ref), SQLiteDatabase.CONFLICT_REPLACE) }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun remove(msgs: Collection<Int>) {
        if (msgs.isEmpty()) return
        writableDatabase.run {
            beginTransaction()
            try {
                msgs.forEach { delete("refs", "msg = ?", arrayOf(it.toString())) }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    private fun values(msg: Int, ref: RichRef) = ContentValues().apply {
        put("msg", msg)
        put("mms", if (ref.mms) 1 else 0)
        put("row", ref.id)
        put("seen", if (ref.seen) 1 else 0)
        put("reactions", ref.reactions.joinToString("\u001f"))
        put("edited", if (ref.edited) 1 else 0)
        put("pinned", if (ref.pinned) 1 else 0)
        put("mine", if (ref.mine) 1 else 0)
        put("chat", ref.chat)
        put("vanish_at", ref.vanishAt)
        put("vanish_for", ref.vanishFor)
    }
}
