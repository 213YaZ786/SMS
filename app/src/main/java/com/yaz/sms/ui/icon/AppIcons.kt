package com.yaz.sms.ui.icon

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
    /** Select a part of a message. */
    val SelectAll: ImageVector by lazy { build("SelectAll", "M3 5h2V3c-1.1 0-2 .9-2 2zm0 8h2v-2H3v2zm4 8h2v-2H7v2zM3 9h2V7H3v2zm10-6h-2v2h2V3zm6 0v2h2c0-1.1-.9-2-2-2zM5 21v-2H3c0 1.1.9 2 2 2zm-2-4h2v-2H3v2zM9 3H7v2h2V3zm2 18h2v-2h-2v2zm8-8h2v-2h-2v2zm0 8c1.1 0 2-.9 2-2h-2v2zm0-12h2V7h-2v2zm0 8h2v-2h-2v2zm-4 4h2v-2h-2v2zm0-16h2V3h-2v2zM7 17h10V7H7v10zm2-8h6v6H9V9z") }

    /** Text styles: bold, italic, underline, strikethrough, and the styles at all. */
    val FormatBold: ImageVector by lazy { build("FormatBold", "M15.6 10.79c.97-.67 1.65-1.77 1.65-2.79 0-2.26-1.75-4-4-4H7v14h7.04c2.09 0 3.71-1.7 3.71-3.79 0-1.52-.86-2.82-2.15-3.42zM10 6.5h3c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-3v-3zm3.5 9H10v-3h3.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5z") }
    val FormatItalic: ImageVector by lazy { build("FormatItalic", "M10 4v3h2.21l-3.42 8H6v3h8v-3h-2.21l3.42-8H18V4z") }
    val FormatUnderlined: ImageVector by lazy { build("FormatUnderlined", "M12 17c3.31 0 6-2.69 6-6V3h-2.5v8c0 1.93-1.57 3.5-3.5 3.5S8.5 12.93 8.5 11V3H6v8c0 3.31 2.69 6 6 6zm-7 2v2h14v-2H5z") }
    val FormatStrikethrough: ImageVector by lazy { build("FormatStrikethrough", "M10 19h4v-3h-4v3zM5 4v3h5v3h4V7h5V4H5zM3 14h18v-2H3v2z") }
    val TextFormat: ImageVector by lazy { build("TextFormat", "M5 17v2h14v-2H5zm4.5-4.2h5l.9 2.2h2.1L12.75 4h-1.5L6.5 15h2.1l.9-2.2zM12 5.98L13.87 11h-3.74L12 5.98z") }

    /** Effects for a message: sparkles. */
    val AutoAwesome: ImageVector by lazy { build("AutoAwesome", "M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z") }

    /** A conversation's background. */
    val Palette: ImageVector by lazy { build("Palette", "M12,2C6.49,2,2,6.49,2,12s4.49,10,10,10c1.38,0,2.5-1.12,2.5-2.5c0-0.61-0.23-1.2-0.64-1.67c-0.08-0.1-0.13-0.21-0.13-0.33 c0-0.28,0.22-0.5,0.5-0.5H16c3.31,0,6-2.69,6-6C22,6.04,17.51,2,12,2z M17.5,13c-0.83,0-1.5-0.67-1.5-1.5c0-0.83,0.67-1.5,1.5-1.5 s1.5,0.67,1.5,1.5C19,12.33,18.33,13,17.5,13z M14.5,9C13.67,9,13,8.33,13,7.5C13,6.67,13.67,6,14.5,6S16,6.67,16,7.5 C16,8.33,15.33,9,14.5,9z M5,11.5C5,10.67,5.67,10,6.5,10S8,10.67,8,11.5C8,12.33,7.33,13,6.5,13S5,12.33,5,11.5z M11,7.5 C11,8.33,10.33,9,9.5,9S8,8.33,8,7.5C8,6.67,8.67,6,9.5,6S11,6.67,11,7.5z") }

    /** A photo. */
    val Photo: ImageVector by lazy { build("Photo", "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z") }

    /** A poll. */
    val Poll: ImageVector by lazy { build("Poll", "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zM9 17H7v-7h2v7zm4 0h-2V7h2v10zm4 0h-2v-4h2v4z") }

    /** Notifications off for a conversation. */
    val NotificationsOff: ImageVector by lazy { build("NotificationsOff", "M20 18.69L7.84 6.14 5.27 3.49 4 4.76l2.8 2.8v.01c-.52.99-.8 2.16-.8 3.42v5l-2 2v1h13.73l2 2L21 19.72l-1-1.03zM12 22c1.11 0 2-.89 2-2h-4c0 1.11.89 2 2 2zm6-7.32V11c0-3.08-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68c-.15.03-.29.08-.42.12-.1.03-.2.07-.3.11h-.01c-.01 0-.01 0-.02.01-.23.09-.46.2-.68.31 0 0-.01 0-.01.01L18 14.68z") }

    /** Notifications back on for a conversation. */
    val NotificationsOn: ImageVector by lazy { build("NotificationsOn", "M7.58 4.08L6.15 2.65C3.75 4.48 2.17 7.3 2.03 10.5h2c.15-2.65 1.51-4.97 3.55-6.42zm12.39 6.42h2c-.15-3.2-1.73-6.02-4.12-7.85l-1.42 1.43c2.02 1.45 3.39 3.77 3.54 6.42zM18 11c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2v-5zm-6 11c.14 0 .27-.01.4-.04.65-.14 1.18-.58 1.44-1.18.1-.24.15-.5.15-.78h-4c.01 1.1.9 2 2.01 2z") }

    /** Up and down, between the messages a search found. */
    val ArrowUp: ImageVector by lazy { build("ArrowUp", "M7.41 15.41L12 10.83l4.59 4.58L18 14l-6-6-6 6z") }
    val ArrowDown: ImageVector by lazy { build("ArrowDown", "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z") }

    /** What two people sent each other: photos, files, links. */
    val Collections: ImageVector by lazy { build("Collections", "M22 16V4c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2zm-11-4l2.03 2.71L16 11l4 5H8l3-4zM2 6v14c0 1.1.9 2 2 2h14v-2H4V6H2z") }

    /** A warning: a link that may be a scam. */
    val Warning: ImageVector by lazy { build("Warning", "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z") }

    /** A conversation's calls and contact, folded (Material Icons "grid_view", Apache-2.0). */
    val GridView: ImageVector by lazy {
        build(
            "GridView",
            "M3 3v8h8V3H3zm6 6H5V5h4v4zm-6 4v8h8v-8H3zm6 6H5v-4h4v4zm4-16v8h8V3h-8zm6 6h-4V5h4v4zm-6 4v8h8v-8h-8zm6 6h-4v-4h4v4z"
        )
    }

    /** An encrypted call: the handset with a padlock (Material Icons "phone_locked", Apache-2.0). */
    val CallLocked: ImageVector by lazy {
        build(
            "CallLocked",
            "M20 15.5c-1.25 0-2.45-.2-3.57-.57-.35-.11-.74-.03-1.02.24l-2.2 2.2c-2.83-1.44-5.15-3.75-6.59-6.59l2.2-2.21c.28-.26.36-.65.25-1" +
                "L8.5 4c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1 0 9.39 7.61 17 17 17 .55 0 1-.45 1-1v-3.5c0-.55-.45-1-1-1zM20 4v-.5C20 2.12 " +
                "18.88 1 17.5 1S15 2.12 15 3.5V4c-.55 0-1 .45-1 1v4c0 .55.45 1 1 1h5c.55 0 1-.45 1-1V5c0-.55-.45-1-1-1zm-.8 0h-3.4v-.5c0-.94" +
                ".76-1.7 1.7-1.7s1.7.76 1.7 1.7V4z"
        )
    }

    val Lock: ImageVector by lazy { build("Lock", "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z") }

    /** A vibration of one's own for a person. */
    val Vibration: ImageVector by lazy { build("Vibration", "M0 15h2V9H0v6zm3 2h2V7H3v10zm19-8v6h2V9h-2zm-3 8h2V7h-2v10zM16.5 3h-9C6.67 3 6 3.67 6 4.5v15c0 .83.67 1.5 1.5 1.5h9c.83 0 1.5-.67 1.5-1.5v-15c0-.83-.67-1.5-1.5-1.5zM16 19H8V5h8v14z") }

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
    val Place: ImageVector by lazy { build("Place", "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z") }
    /** Live location. Material Icons my_location (maps), Apache-2.0, github.com/google/material-design-icons. */
    val MyLocation: ImageVector by lazy { build("MyLocation", "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06zM12 19c-3.87 0-7-3.13-7-7s3.13-7 7-7 7 3.13 7 7-3.13 7-7 7z") }
    val Link: ImageVector by lazy { build("Link", "M3.9 12c0-1.71 1.39-3.1 3.1-3.1h4V7H7c-2.76 0-5 2.24-5 5s2.24 5 5 5h4v-1.9H7c-1.71 0-3.1-1.39-3.1-3.1zM8 13h8v-2H8v2zm9-6h-4v1.9h4c1.71 0 3.1 1.39 3.1 3.1s-1.39 3.1-3.1 3.1h-4V17h4c2.76 0 5-2.24 5-5s-2.24-5-5-5z") }

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

    /** Take someone out of a group (Material Icons person_remove, github.com/google/material-design-icons, Apache-2.0). */
    val PersonRemove: ImageVector by lazy {
        build("PersonRemove", "M14,8c0-2.21-1.79-4-4-4S6,5.79,6,8s1.79,4,4,4S14,10.21,14,8z M17,10v2h6v-2H17z M2,18v2h16v-2c0-2.66-5.33-4-8-4 S2,15.34,2,18z")
    }

    /** Leave a group (Material Icons exit_to_app, github.com/google/material-design-icons, Apache-2.0). */
    val ExitToApp: ImageVector by lazy {
        build("ExitToApp", "M10.09 15.59L11.5 17l5-5-5-5-1.41 1.41L12.67 11H3v2h9.67l-2.58 2.59zM19 3H5c-1.11 0-2 .9-2 2v4h2V5h14v14H5v-4H3v4c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2z")
    }

    /** Mentions, the @ (Material Icons alternate_email, github.com/google/material-design-icons, Apache-2.0). */
    val AlternateEmail: ImageVector by lazy {
        build(
            "AlternateEmail",
            "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10h5v-2h-5c-4.34 0-8-3.66-8-8s3.66-8 8-8 8 3.66 8 8v1.43c0 .79-.71 1.57-1.5 1.57s-1.5-.78-1.5-1.57V12" +
                "c0-2.76-2.24-5-5-5s-5 2.24-5 5 2.24 5 5 5c1.38 0 2.64-.56 3.54-1.47.65.89 1.77 1.47 2.96 1.47 1.97 0 3.5-1.6 3.5-3.57V12c0-5.52-4.48-10-10-10z" +
                "m0 13c-1.66 0-3-1.34-3-3s1.34-3 3-3 3 1.34 3 3-1.34 3-3 3z"
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

    /** Report spam: Material Icons "report" (google/material-design-icons, src/content/report/materialicons). */
    val Report: ImageVector by lazy {
        build(
            "Report",
            "M15.73 3H8.27L3 8.27v7.46L8.27 21h7.46L21 15.73V8.27L15.73 3zM12 17.3c-.72 0-1.3-.58-1.3-1.3 0-.72.58-1.3 1.3-1.3.72 0 1.3.58 1.3 1.3 0 .72-.58 1.3-1.3 1.3zm1-4.3h-2V7h2v6z"
        )
    }

    /** Not spam: Material Icons "report_off" (google/material-design-icons, src/content/report_off/materialicons). */
    val ReportOff: ImageVector by lazy {
        build(
            "ReportOff",
            "M11 7h2v2.92l6.91 6.91 1.09-1.1V8.27L15.73 3H8.27L7.18 4.1 11 7.92zm11.27 14.73l-20-20.01L1 2.99l3.64 3.64L3 8.27v7.46L8.27 21h7.46l1.64-1.63L21 23l1.27-1.27zM12 17.3c-.72 0-1.3-.58-1.3-1.3s.58-1.3 1.3-1.3 1.3.58 1.3 1.3-.58 1.3-1.3 1.3z"
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

    /**
     * Bring a conversation back from the Trash. Material Icons restore_from_trash, filled, 24dp
     * (https://github.com/google/material-design-icons/blob/master/src/action/restore_from_trash/materialicons/24px.svg).
     */
    val RestoreFromTrash: ImageVector by lazy {
        build(
            "RestoreFromTrash",
            "M19 4h-3.5l-1-1h-5l-1 1H5v2h14zM6 7v12c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6zm8 7v4h-4v-4H8l4-4 4 4h-2z"
        )
    }

    /**
     * Delete a trashed conversation for good. Material Icons delete_forever, filled, 24dp
     * (https://github.com/google/material-design-icons/blob/master/src/action/delete_forever/materialicons/24px.svg).
     */
    val DeleteForever: ImageVector by lazy {
        build(
            "DeleteForever",
            "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zm2.46-7.12l1.41-1.41L12 12.59l2.12-2.12 1.41 1.41L13.41 14l2.12 2.12-1.41 1.41L12 15.41" +
                "l-2.12 2.12-1.41-1.41L10.59 14l-2.13-2.12zM15.5 4l-1-1h-5l-1 1H5v2h14V4z"
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

    /** Settings: the gear, as in every app. */
    val Settings: ImageVector by lazy {
        build(
            "Settings",
            "M19.14,12.94c0.04-0.3,0.06-0.61,0.06-0.94c0-0.32-0.02-0.64-0.07-0.94l2.03-1.58c0.18-0.14,0.23-0.41,0.12-0.61 l-1.92-3.32c-0.12-0.22-0.37-0.29-0.59-0.22l-2.39,0.96c-0.5-0.38-1.03-0.7-1.62-0.94L14.4,2.81c-0.04-0.24-0.24-0.41-0.48-0.41 h-3.84c-0.24,0-0.43,0.17-0.47,0.41L9.25,5.35C8.66,5.59,8.12,5.92,7.63,6.29L5.24,5.33c-0.22-0.08-0.47,0-0.59,0.22L2.74,8.87 C2.62,9.08,2.66,9.34,2.86,9.48l2.03,1.58C4.84,11.36,4.8,11.69,4.8,12s0.02,0.64,0.07,0.94l-2.03,1.58 c-0.18,0.14-0.23,0.41-0.12,0.61l1.92,3.32c0.12,0.22,0.37,0.29,0.59,0.22l2.39-0.96c0.5,0.38,1.03,0.7,1.62,0.94l0.36,2.54 c0.05,0.24,0.24,0.41,0.48,0.41h3.84c0.24,0,0.44-0.17,0.47-0.41l0.36-2.54c0.59-0.24,1.13-0.56,1.62-0.94l2.39,0.96 c0.22,0.08,0.47,0,0.59-0.22l1.92-3.32c0.12-0.22,0.07-0.47-0.12-0.61L19.14,12.94z M12,15.6c-1.98,0-3.6-1.62-3.6-3.6 s1.62-3.6,3.6-3.6s3.6,1.62,3.6,3.6S13.98,15.6,12,15.6z"
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

    /** Pass a message on to someone else. Material Icons, Apache-2.0. */
    val Forward: ImageVector by lazy {
        build(
            "Forward",
            "M12 8V4l8 8-8 8v-4H4V8z"
        )
    }

    /** Only the unread conversations: Material Icons' mark_email_unread (github.com/google/material-design-icons, Apache-2.0). */
    val MarkUnread: ImageVector by lazy {
        build(
            "MarkUnread",
            "M22,8.98V18c0,1.1-0.9,2-2,2H4c-1.1,0-2-0.9-2-2V6c0-1.1,0.9-2,2-2h10.1C14.04,4.32,14,4.66,14,5 " +
                "c0,1.48,0.65,2.79,1.67,3.71L12,11L4,6v2l8,5l5.3-3.32C17.84,9.88,18.4,10,19,10C20.13,10,21.16,9.61,22,8.98z M16,5 " +
                "c0,1.66,1.34,3,3,3s3-1.34,3-3s-1.34-3-3-3S16,3.34,16,5z"
        )
    }

    /** Save a message's picture or film to the phone. */
    val Download: ImageVector by lazy { build("Download", "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z") }

    /** Hand a message's picture or film to another app. */
    val Share: ImageVector by lazy {
        build(
            "Share",
            "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1" +
                ".25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6." +
                "79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 " +
                "0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z"
        )
    }

    /** Messages that vanish. Material Icons, Apache-2.0. */
    val Timer: ImageVector by lazy {
        build(
            "Timer",
            "M19.03,7.39l1.42-1.42c-0.43-0.51-0.9-0.99-1.41-1.41l-1.42,1.42C16.07,4.74,14.12,4,12,4c-4." +
                "97,0-9,4.03-9,9 c0,4.97,4.02,9,9,9s9-4.03,9-9C21,10.88,20.26,8.93,19.03,7.39z M13,14h-2V8h" +
                "2V14zM9 1h6v2H9z"
        )
    }

    /** A video call. Material Icons, Apache-2.0. */
    val Videocam: ImageVector by lazy {
        build(
            "Videocam",
            "M17 10.5V7c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v10c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-3.5l4 " +
                "4v-11l-4 4z"
        )
    }

    /** The camera turned off in a call. Material Icons, Apache-2.0. */
    val VideocamOff: ImageVector by lazy {
        build(
            "VideocamOff",
            "M21 6.5l-4 4V7c0-.55-.45-1-1-1H9.82L21 17.18V6.5zM3.27 2L2 3.27 4.73 6H4c-.55 0-1 .45-1 1v" +
                "10c0 .55.45 1 1 1h12c.21 0 .39-.08.54-.18L19.73 21 21 19.73 3.27 2z"
        )
    }

    /** Take a photo. Material Icons, Apache-2.0. */
    val PhotoCamera: ImageVector by lazy {
        build(
            "PhotoCamera",
            "M9 2L7.17 4H4c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15" +
                " 2H9zm3 15c-2.76 0-5-2.24-5-5s2.24-5 5-5 5 2.24 5 5-2.24 5-5 5zM12 15.2a3.2 3.2 0 1 0 0-6." +
                "4 3.2 3.2 0 0 0 0 6.4z"
        )
    }

    /** Send a file. Material Icons, Apache-2.0. */
    val AttachFile: ImageVector by lazy {
        build(
            "AttachFile",
            "M16.5 6v11.5c0 2.21-1.79 4-4 4s-4-1.79-4-4V5c0-1.38 1.12-2.5 2.5-2.5s2.5 1.12 2.5 2.5v10.5" +
                "c0 .55-.45 1-1 1s-1-.45-1-1V6H10v9.5c0 1.38 1.12 2.5 2.5 2.5s2.5-1.12 2.5-2.5V5c0-2.21-1.7" +
                "9-4-4-4S7 2.79 7 5v12.5c0 3.04 2.46 5.5 5.5 5.5s5.5-2.46 5.5-5.5V6h-1.5z"
        )
    }

    /** Send a contact card. Material Icons, Apache-2.0. */
    val ContactPage: ImageVector by lazy {
        build(
            "ContactPage",
            "M14,2H6C4.9,2,4,2.9,4,4v16c0,1.1,0.9,2,2,2h12c1.1,0,2-0.9,2-2V8L14,2z M12,10c1.1,0,2,0.9,2" +
                ",2c0,1.1-0.9,2-2,2s-2-0.9-2-2 C10,10.9,10.9,10,12,10z M16,18H8v-0.57c0-0.81,0.48-1.53,1.22" +
                "-1.85C10.07,15.21,11.01,15,12,15c0.99,0,1.93,0.21,2.78,0.58 C15.52,15.9,16,16.62,16,17.43V" +
                "18z"
        )
    }

    /** Start a group. Material Icons, Apache-2.0. */
    val GroupAdd: ImageVector by lazy {
        build(
            "GroupAdd",
            "M8,12c2.21,0,4-1.79,4-4s-1.79-4-4-4S4,5.79,4,8S5.79,12,8,12zM8,13c-2.67,0-8,1.34-8,4v3h16v" +
                "-3C16,14.34,10.67,13,8,13zM12.51,4.05C13.43,5.11,14,6.49,14,8s-0.57,2.89-1.49,3.95C14.47,1" +
                "1.7,16,10.04,16,8S14.47,4.3,12.51,4.05zM16.53,13.83C17.42,14.66,18,15.7,18,17v3h2v-3C20,15" +
                ".55,18.41,14.49,16.53,13.83zM22 9V7h-2v2h-2v2h2v2h2v-2h2V9z"
        )
    }

    /** Stop a recording. Material Icons, Apache-2.0. */
    val Stop: ImageVector by lazy {
        build(
            "Stop",
            "M6 6h12v12H6z"
        )
    }

    /** Pause a voice message. Material Icons, Apache-2.0. */
    val Pause: ImageVector by lazy {
        build(
            "Pause",
            "M6 19h4V5H6v14zm8-14v14h4V5h-4z"
        )
    }

    /** More to send: photos, camera, file, contact. Material Icons, Apache-2.0. */
    val Add: ImageVector by lazy {
        build(
            "Add",
            "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z"
        )
    }

    /** Front or back camera. Material Icons, Apache-2.0. */
    val CameraSwitch: ImageVector by lazy {
        build(
            "CameraSwitch",
            "M16,7h-1l-1-1h-4L9,7H8C6.9,7,6,7.9,6,9v6c0,1.1,0.9,2,2,2h8c1.1,0,2-0.9,2-2V" +
                "9C18,7.9,17.1,7,16,7z M12,14 c-1.1,0-2-0.9-2-2c0-1.1,0.9-2,2-2s2,0.9,2,2C14,13.1,13.1,14,1" +
                "2,14zM8.57,0.51l4.48,4.48V2.04c4.72,0.47,8.48,4.23,8.95,8.95c0,0,2,0,2,0C23.34,3.02,15.49-" +
                "1.59,8.57,0.51zM10.95,21.96C6.23,21.49,2.47,17.73,2,13.01c0,0-2,0-2,0c0.66,7.97,8.51,12.58" +
                ",15.43,10.48l-4.48-4.48V21.96z"
        )
    }

    /** Start a conversation: Material Symbols chat_add_on (rounded), Apache-2.0, drawn on its 960 grid. */
    val NewChat: ImageVector by lazy {
        ImageVector.Builder(name = "NewChat", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f).apply {
            addGroup(translationY = 960f)
            addPath(
                pathData = PathParser().parsePathString(
                    "m240-280-86 86q-10 10-22 5t-12-19v-552q0-33 23.5-56.5T200-840h480q33 0 56.5 23.5T760-760v1" +
                "61q0 17-11.5 28T720-560q-17 0-28.5-11.5T680-600v-160H200v400h240q17 0 28.5 11.5T480-320q0 " +
                "17-11.5 28.5T440-280H240Zm80-320h240q17 0 28.5-11.5T600-640q0-17-11.5-28.5T560-680H320q-17" +
                " 0-28.5 11.5T280-640q0 17 11.5 28.5T320-600Zm0 160h120q17 0 28.5-11.5T480-480q0-17-11.5-28" +
                ".5T440-520H320q-17 0-28.5 11.5T280-480q0 17 11.5 28.5T320-440Zm360 160h-80q-17 0-28.5-11.5" +
                "T560-320q0-17 11.5-28.5T600-360h80v-80q0-17 11.5-28.5T720-480q17 0 28.5 11.5T760-440v80h80" +
                "q17 0 28.5 11.5T880-320q0 17-11.5 28.5T840-280h-80v80q0 17-11.5 28.5T720-160q-17 0-28.5-11" +
                ".5T680-200v-80Zm-480-80v-400 400Z"
                ).toNodes(),
                fill = SolidColor(Color.Black)
            )
            clearGroup()
        }.build()
    }

    /** Pure black: Material Icons dark_mode, as Dialer's AppIcons.Night (Apache-2.0, google/material-design-icons). */
    val Night: ImageVector by lazy {
        build("Night", "M12,3c-4.97,0-9,4.03-9,9s4.03,9,9,9s9-4.03,9-9c0-0.46-0.04-0.92-0.1-1.36c-0.98,1.37-2.58,2.26-4.4,2.26 c-2.98,0-5.4-2.42-5.4-5.4c0-1.81,0.89-3.42,2.26-4.4C12.92,3.04,12.46,3,12,3L12,3z")
    }

    /** Hidden in recent apps: Material Icons visibility_off, as Browser's AppIcons.VisibilityOff (Apache-2.0, google/material-design-icons). */
    val VisibilityOff: ImageVector by lazy {
        build(
            "VisibilityOff",
            "M12 7c2.76 0 5 2.24 5 5 0 .65-.13 1.26-.36 1.83l2.92 2.92c1.51-1.26 2.7-2.89 3.43-4.75-1.73-4.39-6-7.5-11-7.5-1.4 0-2.74.25-3.98.7l2.16 2.16C10.74 7.13 11.35 7 12 7zM2 4.27l2.28 2.28.46.46C3.08 8.3 1.78 10.02 1 12c1.73 4.39 6 7.5 11 7.5 1.55 0 3.03-.3 4.38-.84l.42.42L19.73 22 21 20.73 3.27 3 2 4.27zM7.53 9.8l1.55 1.55c-.05.21-.08.43-.08.65 0 1.66 1.34 3 3 3 .22 0 .44-.03.65-.08l1.55 1.55c-.67.33-1.41.53-2.2.53-2.76 0-5-2.24-5-5 0-.79.2-1.53.53-2.2zm4.31-.78l3.15 3.15.02-.16c0-1.66-1.34-3-3-3l-.17.01z"
        )
    }

    /** Roaming, the world: Material Symbols language (rounded), as Passwords' AppIcons.Language (Apache-2.0, google/material-design-icons). */
    val Language: ImageVector by lazy {
        symbol(
            "Language",
            "M323-111.5Q250-143 196-197t-85-127.5Q80-398 80-482t31-156.5Q142-711 196-765t127-84.5Q396-880 480-880t157 30.5Q710-819 764-765t85 126.5Q880-566 880-482t-31 157.5Q818-251 764-197t-127 85.5Q564-80 480-80t-157-31.5ZM480-138q35-36 58.5-82.5T577-331H384q14 60 37.5 108t58.5 85Zm-85-12q-25-38-43-82t-30-99H172q38 71 88 111.5T395-150Zm171-1q72-23 129.5-69T788-331H639q-13 54-30.5 98T566-151ZM152-391h159q-3-27-3.5-48.5T307-482q0-25 1-44.5t4-43.5H152q-7 24-9.5 43t-2.5 45q0 26 2.5 46.5T152-391Zm221 0h215q4-31 5-50.5t1-40.5q0-20-1-38.5t-5-49.5H373q-4 31-5 49.5t-1 38.5q0 21 1 40.5t5 50.5Zm275 0h160q7-24 9.5-44.5T820-482q0-26-2.5-45t-9.5-43H649q3 35 4 53.5t1 34.5q0 22-1.5 41.5T648-391Zm-10-239h150q-33-69-90.5-115T565-810q25 37 42.5 80T638-630Zm-254 0h194q-11-53-37-102.5T480-820q-32 27-54 71t-42 119Zm-212 0h151q11-54 28-96.5t43-82.5q-75 19-131 64t-91 115Z"
        )
    }

    /**
     * Translate a message: Material Symbols translate (rounded), the path of
     * https://fonts.google.com/icons?icon.query=translate (Apache-2.0), copied from
     * https://github.com/marella/material-symbols svg-400/rounded/translate.svg (npm @material-symbols/svg-400).
     */
    val Translate: ImageVector by lazy {
        symbol(
            "Translate",
            "m584-197-38 98q-3 8-10.5 13.5T519-80q-16 0-25-13t-3-28l160-415q4-11 13.5-17.5T685-560h29q11 0 20.5 6.5T748-536l160 415q6 15-3 28t-25 13q-10 0-17.5-5.5T852-100l-38-97H584ZM363-401 183-222q-9 8-21 8.5t-21-8.5q-9-9-9-21t9-21l180-180q-51-53-85.5-107.5T183-660h65q16 43 43.5 85t72.5 88q46-48 85-117.5T505-740H70q-13 0-21.5-8.5T40-770q0-13 8.5-21.5T70-800h260v-50q0-13 8.5-21.5T360-880q13 0 21.5 8.5T390-850v50h260q13 0 21.5 8.5T680-770q0 13-8.5 21.5T650-740h-83q-17 78-61.5 159.5T406-443l102 104-24 63-121-125Zm242 149h188l-94-248-94 248Z"
        )
    }

    // The icons of Settings' tiles: Material Icons filled, 24 grid (Apache-2.0), the paths of
    // https://github.com/google/material-design-icons/tree/master/src/<group>/<name>/materialicons/24px.svg
    /** Relays, a server rack: action/dns. */
    val Dns: ImageVector by lazy { build("Dns", "M20 13H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1v-6c0-.55-.45-1-1-1zM7 19c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zM20 3H4c-.55 0-1 .45-1 1v6c0 .55.45 1 1 1h16c.55 0 1-.45 1-1V4c0-.55-.45-1-1-1zM7 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2z") }

    /** Swipe left: action/swipe_left. */
    val SwipeLeft: ImageVector by lazy { build("SwipeLeft", "M19.98,16.82l-0.63,4.46C19.21,22.27,18.36,23,17.37,23h-6.16c-0.53,0-1.29-0.21-1.66-0.59L5,17.62l0.83-0.84 c0.24-0.24,0.58-0.35,0.92-0.28L10,17.24V6.5C10,5.67,10.67,5,11.5,5S13,5.67,13,6.5v6h0.91c0.31,0,0.62,0.07,0.89,0.21l4.09,2.04 C19.66,15.14,20.1,15.97,19.98,16.82z M12,2.5c4.74,0,7.67,2.52,8.43,4.5H22c-0.73-2.88-4.51-6-10-6C8.78,1,5.82,2.13,3.5,4.02V2H2 v5h5V5.5H4.09C6.21,3.64,8.97,2.5,12,2.5z") }

    /** Swipe right: action/swipe_right. */
    val SwipeRight: ImageVector by lazy { build("SwipeRight", "M19.98,16.82l-0.63,4.46C19.21,22.27,18.36,23,17.37,23h-6.16c-0.53,0-1.29-0.21-1.66-0.59L5,17.62l0.83-0.84 c0.24-0.24,0.58-0.35,0.92-0.28L10,17.24V6.5C10,5.67,10.67,5,11.5,5S13,5.67,13,6.5v6h0.91c0.31,0,0.62,0.07,0.89,0.21l4.09,2.04 C19.66,15.14,20.1,15.97,19.98,16.82z M19.91,5.5H17V7h5V2h-1.5v2.02C18.18,2.13,15.22,1,12,1C6.51,1,2.73,4.12,2,7h1.57 C4.33,5.02,7.26,2.5,12,2.5C15.03,2.5,17.79,3.64,19.91,5.5z") }

    /** Undo send: content/undo. */
    val Undo: ImageVector by lazy { build("Undo", "M12.5 8c-2.65 0-5.05.99-6.9 2.6L2 7v9h9l-3.62-3.62c1.39-1.16 3.16-1.88 5.12-1.88 3.54 0 6.55 2.31 7.6 5.5l2.37-.78C21.08 11.03 17.15 8 12.5 8z") }

    /** Text size: editor/format_size. */
    val FormatSize: ImageVector by lazy { build("FormatSize", "M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z") }

    /** Theme, light and dark: image/brightness_6. */
    val Brightness: ImageVector by lazy { build("Brightness", "M20 15.31L23.31 12 20 8.69V4h-4.69L12 .69 8.69 4H4v4.69L.69 12 4 15.31V20h4.69L12 23.31 15.31 20H20v-4.69zM12 18V6c3.31 0 6 2.69 6 6s-2.69 6-6 6z") }

    /** Source code: action/code. */
    val Code: ImageVector by lazy { build("Code", "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z") }

    /** A face, for reactions and emoji: social/mood. */
    val Mood: ImageVector by lazy { build("Mood", "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm3.5-9c.83 0 1.5-.67 1.5-1.5S16.33 8 15.5 8 14 8.67 14 9.5s.67 1.5 1.5 1.5zm-7 0c.83 0 1.5-.67 1.5-1.5S9.33 8 8.5 8 7 8.67 7 9.5 7.67 11 8.5 11zm3.5 6.5c2.33 0 4.31-1.46 5.11-3.5H6.89c.8 2.04 2.78 3.5 5.11 3.5z") }

    /** The chat engine: hardware/memory. */
    val Memory: ImageVector by lazy { build("Memory", "M15 9H9v6h6V9zm-2 4h-2v-2h2v2zm8-2V9h-2V7c0-1.1-.9-2-2-2h-2V3h-2v2h-2V3H9v2H7c-1.1 0-2 .9-2 2v2H3v2h2v2H3v2h2v2c0 1.1.9 2 2 2h2v2h2v-2h2v2h2v-2h2c1.1 0 2-.9 2-2v-2h2v-2h-2v-2h2zm-4 6H7V7h10v10z") }

    /** A 960-grid Material Symbols path, as NewChat above and Passwords' AppIcons.symbol. */
    private fun symbol(name: String, pathData: String): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 960f, viewportHeight = 960f).apply {
            addGroup(translationY = 960f)
            addPath(pathData = PathParser().parsePathString(pathData).toNodes(), fill = SolidColor(Color.Black))
            clearGroup()
        }.build()

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
