package com.noop.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.noop.BuildConfig
import java.io.File
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * In-app diagnostic log. Lines go to logcat AND to a small rotating file in app
 * storage, so a user can export what the app was doing on their real device —
 * strap pairing, offloads, imports, scoring — without a debugger attached.
 *
 * Nothing here ever leaves the phone unless the user taps Export in Settings.
 */
object AppLog {

    private const val TAG = "NOOP"
    private const val DIR = "logs"
    private const val FILE = "noop.log"
    private const val ROTATED = "noop.1.log"
    /** Rotate the live file past this size; the previous generation is kept. */
    private const val MAX_BYTES = 1L shl 20 // 1 MB

    private val lock = Any()
    private var file: File? = null
    private var rotated: File? = null

    private val stamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    /** Call once from Application.onCreate. Safe to call again (idempotent). */
    fun init(context: Context) {
        synchronized(lock) {
            if (file != null) return
            val dir = File(context.filesDir, DIR).apply { mkdirs() }
            file = File(dir, FILE)
            rotated = File(dir, ROTATED)
        }
        i("App", "start ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE}/${BuildConfig.TIER})")
    }

    fun d(tag: String, msg: String) = write('D', tag, msg, null)
    fun i(tag: String, msg: String) = write('I', tag, msg, null)
    fun w(tag: String, msg: String, t: Throwable? = null) = write('W', tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = write('E', tag, msg, t)

    private fun write(level: Char, tag: String, msg: String, t: Throwable?) {
        when (level) {
            'D' -> Log.d(TAG, "$tag: $msg", t)
            'I' -> Log.i(TAG, "$tag: $msg", t)
            'W' -> Log.w(TAG, "$tag: $msg", t)
            else -> Log.e(TAG, "$tag: $msg", t)
        }
        val f = file ?: return
        val line = buildString {
            append(stamp.format(Instant.now())).append(' ').append(level).append('/').append(tag).append(": ").append(msg)
            if (t != null) append('\n').append(Log.getStackTraceString(t).trimEnd())
            append('\n')
        }
        synchronized(lock) {
            runCatching {
                if (f.length() > MAX_BYTES) {
                    rotated?.delete()
                    rotated?.let { f.renameTo(it) }
                }
                f.appendText(line)
            }
        }
    }

    /** Number of lines currently held on disk (both generations). */
    fun lineCount(): Int = synchronized(lock) {
        listOfNotNull(rotated, file).filter { it.exists() }.sumOf { f ->
            runCatching { f.useLines { it.count() } }.getOrDefault(0)
        }
    }

    /**
     * The export bundle: a device/app header, the app log (older generation first), then
     * this process's own logcat (crash traces, framework Bluetooth messages) which an app
     * may read for its own PID without any permission.
     */
    fun exportBundle(context: Context): String = buildString {
        appendLine("NOOP diagnostic log")
        appendLine("exported: ${stamp.format(Instant.now())} (${ZoneId.systemDefault()})")
        appendLine("app: ${BuildConfig.APPLICATION_ID} ${BuildConfig.VERSION_NAME} build ${BuildConfig.VERSION_CODE} ${BuildConfig.BUILD_TYPE}/${BuildConfig.TIER}")
        appendLine("device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("package: ${context.packageName}")
        appendLine()
        appendLine("======== app log ========")
        synchronized(lock) {
            listOfNotNull(rotated, file).filter { it.exists() }.forEach { f ->
                runCatching { append(f.readText()) }
            }
        }
        appendLine()
        appendLine("======== logcat (this process) ========")
        append(ownLogcat())
    }

    /** Best-effort dump of this process's logcat; empty string if the shell is unavailable. */
    private fun ownLogcat(): String = runCatching {
        val pid = android.os.Process.myPid()
        val proc = ProcessBuilder("logcat", "-d", "-v", "threadtime", "--pid=$pid")
            .redirectErrorStream(true)
            .start()
        val text = InputStreamReader(proc.inputStream).use { it.readText() }
        proc.waitFor()
        text
    }.getOrElse { "(logcat unavailable: ${it.message})\n" }

    /** Write the export bundle to a user-chosen SAF document. */
    fun writeTo(context: Context, uri: Uri) {
        val bundle = exportBundle(context)
        context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
            out.write(bundle.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("Could not open $uri for writing")
        i("App", "log exported (${bundle.length} chars)")
    }

    /** Wipe both generations (Settings → Clear log). */
    fun clear() {
        synchronized(lock) {
            rotated?.delete()
            file?.delete()
        }
        i("App", "log cleared")
    }
}
