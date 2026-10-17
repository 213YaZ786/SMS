package com.yaz.sms.core.chat

/**
 * The read receipts switch of the encrypted chat, as Delta Chat Android keeps it
 * (https://github.com/deltachat/deltachat-android, preferences/ChatsPreferenceFragment.java:
 * ReadReceiptToggleListener sets the engine's "mdns_enabled" to 1 or 0, default on). The core's own
 * meaning, chatmail core v2.62.0 src/config.rs (Config::MdnsEnabled): "True if Message Delivery
 * Notifications (read receipts) should be sent and requested", so off, the user sends none and,
 * as Delta Chat's pref_read_receipts_explain says, sees none from others.
 */
object ReadReceipts {

    /** The engine's config key (chatmail core, Config::MdnsEnabled). */
    const val KEY = "mdns_enabled"

    /** The value the engine takes for the switch: "1" on, "0" off (Delta Chat Android's setConfigInt). */
    fun value(on: Boolean): String = if (on) "1" else "0"
}
