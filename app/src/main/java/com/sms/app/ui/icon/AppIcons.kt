package com.sms.app.ui.icon

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The app's own icon set.
 *
 * Not depending on androidx.compose.material:material-icons-*, which has
 * been shifting in and out of the Compose BOM. The paths are Google's
 * Material Icons, filled style, 24dp grid, Apache License 2.0
 * (github.com/google/material-design-icons).
 */
object AppIcons {

    /** The app's glyph, a message with its three dots, for now its mark. */
    val TextSms: ImageVector by lazy {
        build("TextSms", "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM9 11H7V9h2v2zm4 0h-2V9h2v2zm4 0h-2V9h2v2z")
    }

    /** Add a photo or a video to a message. */
    val AddPhoto: ImageVector by lazy {
        build(
            "AddPhoto",
            "M19 7v2.99s-1.99.01-2 0V7h-3s.01-1.99 0-2h3V2h2v3h3v2h-3zm-3 4V8h-3V5H5c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12" +
                "c1.1 0 2-.9 2-2v-8h-3zM5 19l3-4 2 3 3-4 4 5H5z"
        )
    }

    /** A picture. */
    val Image: ImageVector by lazy {
        build("Image", "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z")
    }

    /** Answer a message, quoting it. */
    val Reply: ImageVector by lazy { build("Reply", "M10 9V5l-7 7 7 7v-4.1c5 0 8.5 1.6 11 5.1-1-5-4-10-11-11z") }

    /** End-to-end encrypted. */
    val Lock: ImageVector by lazy { build("Lock", "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z") }

    /** Send a message. */
    val Send: ImageVector by lazy { build("Send", "M2.01 21L23 12 2.01 3 2 10l15 2-15 2z") }

    /** Put a conversation away. */
    val Archive: ImageVector by lazy {
        build(
            "Archive",
            "M20.54 5.23l-1.39-1.68C18.88 3.21 18.47 3 18 3H6c-.47 0-.88.21-1.16.55L3.46 5.23C3.17 5.57 3 6.02 3 6.5V19" +
                "c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V6.5c0-.48-.17-.93-.46-1.27zM12 17.5L6.5 12H10v-2h4v2h3.5L12 17.5zM5.12 5l.81-1h12l.94 1H5.12z"
        )
    }

    /** Bring a conversation back. */
    val Unarchive: ImageVector by lazy {
        build(
            "Unarchive",
            "M20.55,5.22l-1.39-1.68C18.88,3.21,18.47,3,18,3H6C5.53,3,5.12,3.21,4.85,3.55L3.46,5.22C3.17,5.57,3,6.01,3,6.5V19" +
                "c0,1.1,0.89,2,2,2h14c1.1,0,2-0.9,2-2V6.5C21,6.01,20.83,5.57,20.55,5.22z M12,9.5l5.5,5.5H14v2h-4v-2H6.5L12,9.5z" +
                " M5.12,5l0.82-1h12l0.93,1H5.12z"
        )
    }

    /** Write a new message. */
    val Create: ImageVector by lazy {
        build(
            "Create",
            "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34" +
                "c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z"
        )
    }

    /** Sent. */
    val Done: ImageVector by lazy { build("Done", "M9 16.2L4.8 12l-1.4 1.4L9 19 21 7l-1.4-1.4L9 16.2z") }

    /** Delivered. */
    val DoneAll: ImageVector by lazy {
        build(
            "DoneAll",
            "M18 7l-1.41-1.41-6.34 6.34 1.41 1.41L18 7zm4.24-1.41L11.66 16.17 7.48 12l-1.41 1.41L11.66 19l12-12-1.42-1.41z" +
                "M.41 13.41L6 19l1.41-1.41L1.83 12 .41 13.41z"
        )
    }

    /** Still going out. */
    val Schedule: ImageVector by lazy {
        build(
            "Schedule",
            "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8" +
                "s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zM12.5 7H11v6l5.25 3.15.75-1.23-4.5-2.67z"
        )
    }

    /** Kept on top. */
    val PushPin: ImageVector by lazy {
        build(
            "PushPin",
            "M16,9V4l1,0c0.55,0,1-0.45,1-1v0c0-0.55-0.45-1-1-1H7C6.45,2,6,2.45,6,3v0 c0,0.55,0.45,1,1,1l1,0v5" +
                "c0,1.66-1.34,3-3,3h0v2h5.97v7l1,1l1-1v-7H19v-2h0C17.34,12,16,10.66,16,9z"
        )
    }

    /** Could not be sent. */
    val Error: ImageVector by lazy {
        build(
            "Error",
            "M11 15h2v2h-2zm0-8h2v6h-2zm.99-5C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2z" +
                "M12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8z"
        )
    }

    /** A favourite. */
    val Star: ImageVector by lazy {
        build(
            "Star",
            "M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z"
        )
    }

    /** Not a favourite. */
    val StarOutline: ImageVector by lazy {
        build(
            "StarOutline",
            "M22 9.24l-7.19-.62L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.63-7.03L22" +
                " 9.24zM12 15.4l-3.76 2.27 1-4.28-3.32-2.88 4.38-.38L12 6.1l1.71 4.04 4.38.38-3.32 2.88 1 4" +
                ".28L12 15.4z"
        )
    }

    /** The Recents tab. */
    val Recents: ImageVector by lazy {
        build(
            "Recents",
            "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c" +
                "-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-" +
                "9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z"
        )
    }

    /** A contact, the Contacts tab. */
    val Person: ImageVector by lazy {
        build(
            "Person",
            "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2" +
                "c0-2.66-5.33-4-8-4z"
        )
    }

    /** Voicemail, on the 1 key and the dialpad's voicemail button. */
    val Voicemail: ImageVector by lazy {
        build(
            "Voicemail",
            "M18.5 6C15.46 6 13 8.46 13 11.5c0 1.33.47 2.55 1.26 3.5H9.74c.79-.95 1.26-2.17 1.26-3.5C11 8" +
                ".46 8.54 6 5.5 6S0 8.46 0 11.5 2.46 17 5.5 17h13c3.04 0 5.5-2.46 5.5-5.5S21.54 6 18.5 6zm-13" +
                " 9C3.57 15 2 13.43 2 11.5S3.57 8 5.5 8 9 9.57 9 11.5 7.43 15 5.5 15zm13 0c-1.93 0-3.5-1.57-3." +
                "5-3.5S16.57 8 18.5 8 22 9.57 22 11.5 20.43 15 18.5 15z"
        )
    }

    /** A new contact from a number. */
    val PersonAdd: ImageVector by lazy {
        build(
            "PersonAdd",
            "M15 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm-9-2V7H4v3H1v2h3v3h2v-3h3v-2H6zm9 " +
                "4c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"
        )
    }

    /** The people of a conference call. */
    val Group: ImageVector by lazy {
        build(
            "Group",
            "M16 11c1.66 0 2.99-1.34 2.99-3S17.66 5 16 5c-1.66 0-3 1.34-3 3s1.34 3 3 3zm-8 0c1.66 0 2.99-" +
                "1.34 2.99-3S9.66 5 8 5C6.34 5 5 6.34 5 8s1.34 3 3 3zm0 2c-2.33 0-7 1.17-7 3.5V19h14v-2.5c0-" +
                "2.33-4.67-3.5-7-3.5zm8 0c-.29 0-.62.02-.97.05 1.16.84 1.97 1.97 1.97 3.45V19h6v-2.5c0-2.33-" +
                "4.67-3.5-7-3.5z"
        )
    }

    /** The microphone, live. */
    val Mic: ImageVector by lazy {
        build(
            "Mic",
            "M12 14c1.66 0 2.99-1.34 2.99-3L15 5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3zm5.3-3c0 " +
                "3-2.54 5.1-5.3 5.1S6.7 14 6.7 11H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c3.28-.48 6-3.3 6-6.72h-1.7z"
        )
    }

    /** Resume a call on hold. */
    val Play: ImageVector by lazy { build("Play", "M8 5v14l11-7z") }

    /** Silence the ringing. */
    val VolumeOff: ImageVector by lazy {
        build(
            "VolumeOff",
            "M16.5 12c0-1.77-1.02-3.29-2.5-4.03v2.21l2.45 2.45c.03-.2.05-.41.05-.63zm2.5 0c0 .94-.2 1.82-.54" +
                " 2.64l1.51 1.51C20.63 14.91 21 13.5 21 12c0-4.28-2.99-7.86-7-8.77v2.06c2.89.86 5 3.54 5 6.71z" +
                "M4.27 3L3 4.27 7.73 9H3v6h4l5 5v-6.73l4.25 4.25c-.67.52-1.42.93-2.25 1.18v2.06c1.38-.31 2.63-.95" +
                " 3.69-1.81L19.73 21 21 19.73l-9-9L4.27 3zM12 4L9.91 6.09 12 8.18V4z"
        )
    }

    /** Clear a field. */
    val Close: ImageVector by lazy {
        build("Close", "M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z")
    }

    /** A call that came in: a bold arrow down and to the left. */
    val ArrowIn: ImageVector by lazy { build("ArrowIn", "M15 19v-2H8.41L20 5.41 18.59 4 7 15.59V9H5v10h10z") }

    /** A call that went out: a bold arrow up and to the right. */
    val ArrowOut: ImageVector by lazy { build("ArrowOut", "M9 5v2h6.59L4 18.59 5.41 20 17 8.41V15h2V5H9z") }

    /** Sound in a headset. */
    val Headset: ImageVector by lazy {
        build(
            "Headset",
            "M12 1c-4.97 0-9 4.03-9 9v7c0 1.66 1.34 3 3 3h3v-8H5v-2c0-3.87 3.13-7 7-7s7 3.13 7 7v2h-4v8h3c1.66 0 " +
                "3-1.34 3-3v-7c0-4.97-4.03-9-9-9z"
        )
    }

    /** Sound at the ear, from the phone itself. */
    val Smartphone: ImageVector by lazy {
        build(
            "Smartphone",
            "M17 1.01 7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z"
        )
    }

    /** The dialpad. */
    val Dialpad: ImageVector by lazy {
        build(
            "Dialpad",
            "M12 19c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zM6 1c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-." +
                "9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9" +
                " 2-2-.9-2-2-2zm12-8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm-6 8c-1.1 0-2 .9-2 2s.9 2 " +
                "2 2 2-.9 2-2-.9-2-2-2zm6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0-6c-1.1 0-2 .9-2 2" +
                "s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0-6c-1.1 0-2" +
                " .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"
        )
    }

    /** Place or answer a call. */
    val Call: ImageVector by lazy {
        build(
            "Call",
            "M20.01 15.38c-1.23 0-2.42-.2-3.53-.56-.35-.12-.74-.03-1.01.24l-1.57 1.97c-2.83-1.35-5.48-3" +
                ".9-6.89-6.83l1.95-1.66c.27-.28.35-.67.24-1.02-.37-1.11-.56-2.3-.56-3.53 0-.54-.45-.99-.99-" +
                ".99H4.19C3.65 3 3 3.24 3 3.99 3 13.28 10.73 21 20.01 21c.71 0 .99-.63.99-1.18v-3.45c0-.54-" +
                ".45-.99-.99-.99z"
        )
    }

    /** Hang up, decline. */
    val CallEnd: ImageVector by lazy {
        build(
            "CallEnd",
            "M12 9c-1.6 0-3.15.25-4.6.72v3.1c0 .39-.23.74-.56.9-.98.49-1.87 1.12-2.66 1.85-.18.18-.43.2" +
                "8-.7.28-.28 0-.53-.11-.71-.29L.29 13.08c-.18-.17-.29-.42-.29-.7 0-.28.11-.53.29-.71C3.34 8" +
                ".78 7.46 7 12 7s8.66 1.78 11.71 4.67c.18.18.29.43.29.71 0 .28-.11.53-.29.71l-2.48 2.48c-.1" +
                "8.18-.43.29-.71.29-.27 0-.52-.11-.7-.28-.79-.74-1.69-1.36-2.67-1.85-.33-.16-.56-.5-.56-.9v" +
                "-3.1C15.15 9.25 13.6 9 12 9z"
        )
    }

    /** Microphone muted. */
    val MicOff: ImageVector by lazy {
        build(
            "MicOff",
            "M19 11h-1.7c0 .74-.16 1.43-.43 2.05l1.23 1.23c.56-.98.9-2.09.9-3.28zm-4.02.17c0-.06.02-.11" +
                ".02-.17V5c0-1.66-1.34-3-3-3S9 3.34 9 5v.18l5.98 5.99zM4.27 3L3 4.27l6.01 6.01V11c0 1.66 1." +
                "33 3 2.99 3 .22 0 .44-.03.65-.08l1.66 1.66c-.71.33-1.5.52-2.31.52-2.76 0-5.3-2.1-5.3-5.1H5" +
                "c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c.91-.13 1.77-.45 2.54-.9L19.73 21 21 19.73 4.27 3z"
        )
    }

    /** Audio through the speaker. */
    val Speaker: ImageVector by lazy {
        build(
            "Speaker",
            "M3 9v6h4l5 5V4L7 9H3zm13.5 3c0-1.77-1.02-3.29-2.5-4.03v8.05c1.48-.73 2.5-2.25 2.5-4.02zM14" +
                " 3.23v2.06c2.89.86 5 3.54 5 6.71s-2.11 5.85-5 6.71v2.06c4.01-.91 7-4.49 7-8.77s-2.99-7.86-" +
                "7-8.77z"
        )
    }

    /** Audio through Bluetooth. */
    val Bluetooth: ImageVector by lazy {
        build(
            "Bluetooth",
            "M17.71 7.71L12 2h-1v7.59L6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 11 14.41V22h1l5.71-5.71-4." +
                "3-4.29 4.3-4.29zM13 5.83l1.88 1.88L13 9.59V5.83zm1.88 10.46L13 18.17v-3.76l1.88 1.88z"
        )
    }

    /** Put a call on hold. */
    val Hold: ImageVector by lazy {
        build(
            "Hold",
            "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
        )
    }

    /** Add a call. */
    val AddCall: ImageVector by lazy {
        build(
            "AddCall",
            "M20 15.5c-1.25 0-2.45-.2-3.57-.57-.35-.11-.74-.03-1.02.24l-2.2 2.2c-2.83-1.44-5.15-3.75-6." +
                "59-6.59l2.2-2.21c.28-.26.36-.65.25-1C8.7 6.45 8.5 5.25 8.5 4c0-.55-.45-1-1-1H4c-.55 0-1 .4" +
                "5-1 1 0 9.39 7.61 17 17 17 .55 0 1-.45 1-1v-3.5c0-.55-.45-1-1-1zM21 6h-3V3h-2v3h-3v2h3v3h2" +
                "V8h3z"
        )
    }

    /** Merge calls into a conference. */
    val Merge: ImageVector by lazy {
        build(
            "Merge",
            "M17 20.41L18.41 19 15 15.59 13.59 17 17 20.41zM7.5 8H11v5.59L5.59 19 7 20.41l6-6V8h3.5L12 " +
                "3.5 7.5 8z"
        )
    }

    /** Swap between calls. */
    val Swap: ImageVector by lazy {
        build(
            "Swap",
            "M18 4l-4 4h3v7c0 1.1-.9 2-2 2s-2-.9-2-2V8c0-2.21-1.79-4-4-4S5 5.79 5 8v7H2l4 4 4-4H7V8c0-1" +
                ".1.9-2 2-2s2 .9 2 2v7c0 2.21 1.79 4 4 4s4-1.79 4-4V8h3l-4-4z"
        )
    }

    /** Separate a call from a conference. */
    val Split: ImageVector by lazy {
        build(
            "Split",
            "M14 4l2.29 2.29-2.88 2.88 1.42 1.42 2.88-2.88L20 10V4zm-4 0H4v6l2.29-2.29 4.71 4.7V20h2v-8" +
                ".41l-5.29-5.3z"
        )
    }

    /** Delete the last digit. */
    val Backspace: ImageVector by lazy {
        build(
            "Backspace",
            "M22 3H7c-.69 0-1.23.35-1.59.88L0 12l5.41 8.11c.36.53.9.89 1.59.89h15c1.1 0 2-.9 2-2V5c0-1." +
                "1-.9-2-2-2zm-3 12.59L17.59 17 14 13.41 10.41 17 9 15.59 12.59 12 9 8.41 10.41 7 14 10.59 1" +
                "7.59 7 19 8.41 15.41 12 19 15.59z"
        )
    }

    /** A missed call. */
    val Missed: ImageVector by lazy {
        build(
            "Missed",
            "M19.59 7L12 14.59 6.41 9H11V7H3v8h2v-4.59l7 7 9-9z"
        )
    }

    /** An answered incoming call. */
    val Incoming: ImageVector by lazy {
        build(
            "Incoming",
            "M20 5.41L18.59 4 7 15.59V9H5v10h10v-2H8.41z"
        )
    }

    /** An outgoing call. */
    val Outgoing: ImageVector by lazy {
        build(
            "Outgoing",
            "M9 5v2h6.59L4 18.59 5.41 20 17 8.41V15h2V5z"
        )
    }

    /** Block a number. */
    val Block: ImageVector by lazy {
        build(
            "Block",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zM4 12c0-4.42 3.58-8 8-8 " +
                "1.85 0 3.55.63 4.9 1.69L5.69 16.9C4.63 15.55 4 13.85 4 12zm8 8c-1.85 0-3.55-.63-4.9-1.69L1" +
                "8.31 7.1C19.37 8.45 20 10.15 20 12c0 4.42-3.58 8-8 8z"
        )
    }

    /** Send a text. */
    val Message: ImageVector by lazy {
        build(
            "Message",
            "M20 2H4c-1.1 0-1.99.9-1.99 2L2 22l4-4h14c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zm-2 12H6v-2h12v2z" +
                "m0-3H6V9h12v2zm0-3H6V6h12v2z"
        )
    }

    /** Copy a number. */
    val Copy: ImageVector by lazy {
        build(
            "Copy",
            "M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 " +
                "2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z"
        )
    }

    /** Delete. */
    val Delete: ImageVector by lazy {
        build(
            "Delete",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z"
        )
    }

    /** Back. */
    val ArrowBack: ImageVector by lazy {
        build(
            "ArrowBack",
            "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z"
        )
    }

    /** Search. */
    val Search: ImageVector by lazy {
        build(
            "Search",
            "M15.5 14h-.79l-.28-.27C15.41 12.59 16 11.11 16 9.5 16 5.91 13.09 3 9.5 3S3 5.91 3 9.5 5.91" +
                " 16 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11" +
                ".99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z"
        )
    }

    /** Switch to the letter keyboard. */
    val Keyboard: ImageVector by lazy {
        build(
            "Keyboard",
            "M20 5H4c-1.1 0-1.99.9-1.99 2L2 17c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm-9 3h2" +
                "v2h-2V8zm0 3h2v2h-2v-2zM8 8h2v2H8V8zm0 3h2v2H8v-2zm-1 2H5v-2h2v2zm0-3H5V8h2v2zm9 7H8v-2h8v" +
                "2zm0-4h-2v-2h2v2zm0-3h-2V8h2v2zm3 3h-2v-2h2v2zm0-3h-2V8h2v2z"
        )
    }

    /** Done, in place. */
    val CheckCircle: ImageVector by lazy {
        build(
            "CheckCircle",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z"
        )
    }

    /** The app's own updates. */
    val Update: ImageVector by lazy {
        build(
            "Update",
            "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14zm-1-6h-3V8h-2v5H8l4 4 4-4z"
        )
    }

    /** Settings, the same tuning sliders as the other apps. */
    val Settings: ImageVector by lazy {
        build(
            "Settings",
            "M3,17v2h6v-2L3,17zM3,5v2h10L13,5L3,5zM13,21v-2h8v-2h-8v-2h-2v6h2z" +
                "M7,9v2L3,11v2h4v2h2L9,9L7,9zM21,13v-2L11,11v2h10zM15,9h2L17,7h4L21,5h-4L17,3h-2v6z"
        )
    }

    /** About the app. */
    val Info: ImageVector by lazy {
        build(
            "Info",
            "M11,7h2v2h-2zM11,11h2v6h-2zM12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10" +
                "S17.52,2 12,2zM12,20c-4.41,0 -8,-3.59 -8,-8s3.59,-8 8,-8 8,3.59 8,8 -3.59,8 -8,8z"
        )
    }

    private fun build(name: String, pathData: String): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
            autoMirror = name == "ArrowBack" || name == "Backspace"
        ).apply {
            addPath(
                pathData = PathParser().parsePathString(pathData).toNodes(),
                fill = SolidColor(Color.Black)
            )
        }.build()
}
