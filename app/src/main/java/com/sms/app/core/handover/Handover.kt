package com.sms.app.core.handover

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.ParcelFileDescriptor
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Moving the app to a new package without losing anything: the old app
 * hands its private files to its successor, signed with the same key, and
 * the successor takes them before anything reads them. Messages, calls,
 * contacts and blocked numbers live in Android's own stores and never move.
 * The manifest names the other side: meta-data "handover.successor" in the
 * old app, "handover.predecessor" in the new one.
 */
object Handover {

    /** What the old app does before it gives its files away (stop an engine writing them). */
    interface Host {
        fun beforeHandover()
    }

    fun successor(context: Context): String? = meta(context, "handover.successor")
    fun predecessor(context: Context): String? = meta(context, "handover.predecessor")

    /** The old app gave its files away: it stays quiet and points to the new one. */
    fun handedOver(context: Context): Boolean = File(context.filesDir, HANDED).exists()

    fun installed(context: Context, pkg: String?): Boolean = pkg != null && runCatching {
        context.packageManager.getPackageInfo(pkg, 0); true
    }.getOrDefault(false)

    /** The old app is still on the phone (the new one offers to remove it). */
    fun oldInstalled(context: Context): Boolean = installed(context, predecessor(context))

    /**
     * The new app's first start: the old app's files come over, once, when
     * this one has none of its own yet. False when there was nothing to take.
     */
    fun takeOver(context: Context): Boolean {
        val old = predecessor(context) ?: return false
        val mark = File(context.filesDir, TAKEN)
        if (mark.exists()) return false
        val ownFiles = context.filesDir.listFiles()?.any { it.name != TAKEN } == true
        if (ownFiles || !installed(context, old)) {
            mark.createNewFile()
            return false
        }
        val taken = unpack(context, Uri.parse("content://$old.handover/all"))
        mark.createNewFile()
        return taken
    }

    /** The speech model, large, taken later in the background. */
    fun takeOverSpeech(context: Context): Boolean {
        val old = predecessor(context) ?: return false
        if (!installed(context, old)) return false
        return unpack(context, Uri.parse("content://$old.handover/speech"))
    }

    fun removeOld(context: Context) {
        val old = predecessor(context) ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$old")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun openNew(context: Context) {
        val new = successor(context) ?: return
        context.packageManager.getLaunchIntentForPackage(new)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    private fun unpack(context: Context, uri: Uri): Boolean = runCatching {
        val root = context.dataDir.canonicalFile
        var any = false
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            ZipInputStream(ParcelFileDescriptor.AutoCloseInputStream(pfd)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val target = File(root, entry.name).canonicalFile
                    // Nothing outside the app's own folder, whatever the archive says.
                    if (!target.path.startsWith(root.path + File.separator)) continue
                    if (entry.isDirectory) { target.mkdirs(); continue }
                    target.parentFile?.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                    any = true
                }
            }
        }
        any
    }.getOrDefault(false)

    private fun meta(context: Context, key: String): String? = runCatching {
        context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA).metaData?.getString(key)
    }.getOrNull()

    internal const val HANDED = ".handed-over"
    internal const val TAKEN = ".taken-over"
}

/**
 * In the old app: its private files as one archive, for its successor only
 * (the manifest guards it with a permission of signature level, and the
 * caller must be the successor named in the manifest).
 */
class HandoverProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val successor = Handover.successor(context) ?: return null
        val callers = context.packageManager.getPackagesForUid(Binder.getCallingUid()).orEmpty()
        if (successor !in callers) throw SecurityException("not the successor")
        val speech = uri.lastPathSegment == "speech"
        if (!speech) (context.applicationContext as? Handover.Host)?.beforeHandover()
        val (read, write) = ParcelFileDescriptor.createReliablePipe()
        Thread {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                runCatching {
                    ZipOutputStream(out).use { zip ->
                        val root = context.dataDir
                        val parts = if (speech) listOf(File(context.filesDir, "speech")) else listOf("files", "databases", "shared_prefs").map { File(root, it) }
                        parts.filter { it.exists() }.forEach { add(zip, root, it, speech) }
                    }
                    if (!speech) File(context.filesDir, Handover.HANDED).createNewFile()
                }
            }
        }.start()
        return read
    }

    private fun add(zip: ZipOutputStream, root: File, file: File, speech: Boolean) {
        val name = file.relativeTo(root).path
        // The speech model goes on its own, later; marks and caches stay.
        if (!speech && name == "files/speech") return
        if (file.name == Handover.HANDED || file.name == Handover.TAKEN) return
        if (file.isDirectory) {
            file.listFiles()?.forEach { add(zip, root, it, speech) }
            return
        }
        zip.putNextEntry(ZipEntry(name).apply { if (speech) method = ZipEntry.DEFLATED })
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String = "application/zip"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
