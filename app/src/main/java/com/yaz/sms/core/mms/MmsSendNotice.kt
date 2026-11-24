package com.yaz.sms.core.mms

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.yaz.sms.MainActivity
import com.yaz.sms.R
import com.yaz.sms.core.dial.ContactLookup
import com.yaz.sms.core.dial.Numbers

/**
 * The state of the progress bar, with the names and the steps of the platform's Notification.ProgressStyle
 * (mProgress, mIndeterminate, mIsStyledByProgress, setProgress, setProgressIndeterminate, setStyledByProgress,
 * getProgress, getProgressMax, isProgressIndeterminate, isStyledByProgress, DEFAULT_PROGRESS_MAX). Pure, so it is
 * tested without a phone. Transcoding fills the bar from 0 to [TRANSCODE_END], sending the rest.
 * Not ported: Segment, Point, setProgressSegments, addProgressSegment, setProgressPoints, addProgressPoint,
 * setProgressTrackerIcon, setProgressStartIcon, setProgressEndIcon (one plain bar here, no icons or stops),
 * and the Parcel and RemoteViews code of the style (the platform draws it from the real ProgressStyle below).
 * https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/app/Notification.java#ProgressStyle
 */
internal class MmsProgressStyle {
    private var mProgress = 0
    private var mIndeterminate = true
    private var mIsStyledByProgress = true
    /** True once the bar was taken away, at the end of the work. */
    var mRemoved = false
        private set

    /** The progress, never less than before: the three tries of the transcoder each start again at 0. */
    fun setProgress(progress: Int): MmsProgressStyle {
        if (!mRemoved) mProgress = Math.max(mProgress, Math.min(Math.max(progress, 0), getProgressMax()))
        return this
    }

    fun getProgress(): Int = mProgress

    /** The sum of the lengths of the one segment, 100 as the platform's default. */
    fun getProgressMax(): Int = DEFAULT_PROGRESS_MAX

    fun setProgressIndeterminate(indeterminate: Boolean): MmsProgressStyle {
        mIndeterminate = indeterminate
        return this
    }

    fun isProgressIndeterminate(): Boolean = mIndeterminate && !mRemoved

    fun setStyledByProgress(enabled: Boolean): MmsProgressStyle {
        mIsStyledByProgress = enabled
        return this
    }

    fun isStyledByProgress(): Boolean = mIsStyledByProgress

    /** The transcoder's figure, 0 to 100, null while it has none: indeterminate until the first one came. */
    fun setTranscodeProgress(percent: Int?): MmsProgressStyle {
        if (percent == null) return this
        setProgressIndeterminate(false)
        return setProgress(Math.min(Math.max(percent, 0), DEFAULT_PROGRESS_MAX) * TRANSCODE_END / DEFAULT_PROGRESS_MAX)
    }

    /** The film is ready and goes to the carrier. */
    fun setSending(): MmsProgressStyle {
        setProgressIndeterminate(false)
        return setProgress(TRANSCODE_END)
    }

    /** The bar is removed, whether the message left or not. */
    fun remove(success: Boolean) {
        if (mRemoved) return
        if (success) setProgress(DEFAULT_PROGRESS_MAX)
        mRemoved = true
    }

    companion object {
        const val DEFAULT_PROGRESS_MAX = 100
        const val TRANSCODE_END = 90

        /** A bar only for an MMS whose film is transcoded: an SMS, or an MMS that needs no transcoding, shows none. */
        fun isWanted(isMms: Boolean, transcoding: Boolean): Boolean = isMms && transcoding
    }
}

/**
 * The one silent notification "Sending video to <name>" while a film over the carrier's share is transcoded
 * and then sent. Progress on Android 16's Notification.ProgressStyle (Live Updates guidance), the platform's
 * Notification.Builder.setProgress below it. Own low importance channel, ongoing, alerts once, a tap opens
 * the thread. No foreground service: the work already runs on the sending thread. At the end it is
 * cancelled, success or not: a failure is told by MessageNotifier and the thread, not here.
 * https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/main/core/java/android/app/Notification.java#ProgressStyle
 */
internal class MmsSendNotice(
    context: Context,
    private val recipients: List<String>,
    private val thread: Long?,
) {
    private val app = context.applicationContext
    private val notifications = NotificationManagerCompat.from(app)
    private val style = MmsProgressStyle()
    private val id = thread?.toInt() ?: 0

    init {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, app.getString(R.string.mms_notice_channel), NotificationManager.IMPORTANCE_LOW))
    }

    /** The transcoder's figure, 0 to 100, null while it has none. */
    fun setProgress(percent: Int?) {
        style.setTranscodeProgress(percent)
        build()
    }

    /** The film is ready and goes to the carrier. */
    fun setSending() {
        style.setSending()
        build()
    }

    /** The end: the bar leaves; a failure leaves a normal notification in its place. */
    fun remove(success: Boolean) {
        style.remove(success)
        // A failure is told elsewhere (MessageNotifier.failed, or the Toast for a film too large).
        runCatching { notifications.cancel(TAG, id) }
    }

    /**
     * Polls the Transformer's progress while it runs, on the thread it already runs on, as Media3 documents
     * (Transformer.getProgress is called from the application thread, the looper of the transformer).
     * The handler is the transcoder's own; nothing new is started. Stops once the export is over.
     * https://raw.githubusercontent.com/androidx/media/release/libraries/transformer/src/main/java/androidx/media3/transformer/Transformer.java#getProgress
     */
    fun watch(handler: Handler, transformer: Transformer, isRunning: () -> Boolean) {
        val holder = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                if (!isRunning() || style.mRemoved) return
                val state = runCatching { transformer.getProgress(holder) }.getOrDefault(Transformer.PROGRESS_STATE_UNAVAILABLE)
                setProgress(if (state == Transformer.PROGRESS_STATE_AVAILABLE) holder.progress else null)
                handler.postDelayed(this, POLL_MS)
            }
        }
        handler.post(poll)
    }

    private fun name(): String =
        recipients.joinToString(", ") { ContactLookup.nameOf(app, it) ?: Numbers.format(app, it) }

    private fun open(): PendingIntent = PendingIntent.getActivity(
        app, id,
        Intent(app, MainActivity::class.java).setAction(MainActivity.ACTION_THREAD)
            .apply {
                if (thread != null) putExtra(MainActivity.EXTRA_THREAD, thread)
                putExtra(MainActivity.EXTRA_ADDRESS, recipients.firstOrNull().orEmpty())
            }
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** Builds the notification as the platform's example does (setStyle with a ProgressStyle), and posts it. */
    private fun build() {
        // Nothing shown when notifications are refused.
        if (!notifications.areNotificationsEnabled()) return
        val title = app.getString(R.string.mms_notice_sending, name())
        val note: Notification = if (Build.VERSION.SDK_INT >= 36) {
            Notification.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle(title)
                .setContentIntent(open())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setStyle(
                    Notification.ProgressStyle()
                        .setProgressIndeterminate(style.isProgressIndeterminate())
                        .setProgress(style.getProgress())
                        .setStyledByProgress(style.isStyledByProgress())
                )
                .build()
        } else {
            NotificationCompat.Builder(app, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_sms)
                .setContentTitle(title)
                .setContentIntent(open())
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setProgress(style.getProgressMax(), style.getProgress(), style.isProgressIndeterminate())
                .build()
        }
        runCatching { notifications.notify(TAG, id, note) }
    }

    private companion object {
        const val CHANNEL = "mms_video"
        const val TAG = "mms_video"
        const val POLL_MS = 500L
    }
}
