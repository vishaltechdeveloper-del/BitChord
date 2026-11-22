package com.music.bitchord.security

import android.content.Context
import android.os.Process
import org.json.JSONObject
import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SHA-256 self-check, ported from Shorty 1.9.43 (ShaCheck).
 *
 * At launch, compute THIS APK file's SHA-256 (from
 * ApplicationInfo.sourceDir — the installed file, not the signing cert)
 * and verify it against GET /api/sha-verify?version=X&sha256=Y. The hash
 * must match the hash saved for THAT version; a hash from another version
 * does not pass.
 *
 * Match = original, the app runs. Mismatch = tampered, the app closes
 * SILENTLY (Process.killProcess, no dialog, no message).
 *
 * Fail-open: any error (no internet, timeout, non-200, unreadable APK) =
 * the app runs normally, so offline music listening is never affected.
 *
 * Every new build's SHA must be saved in Stats → SHA Versions BEFORE the
 * app is opened the first time — the GitHub artifact's SHA-256 IS the APK
 * file hash. Until it is saved, the new build closes silently on launch.
 * That is the gate working, not a crash.
 */
object ShaCheck {

    private const val BASE_URL = "https://zeuvia.vishaltech.site"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 5000

    private val checkStarted = AtomicBoolean(false)

    /**
     * Call FIRST from BitChordApplication.onCreate, before the other server
     * checks — a bad hash kills the process before any dialog can show.
     * Returns at once; the check runs on its own thread.
     */
    fun check(context: Context) {
        if (!checkStarted.compareAndSet(false, true)) return
        Thread({ runCheck(context.applicationContext) }, "sha-check").start()
    }

    private fun runCheck(context: Context) {
        try {
            val version = appVersion(context)
            val apkHash = sha256File(context.applicationInfo.sourceDir) ?: return
            val query = "version=" + URLEncoder.encode(version, "UTF-8") +
                "&sha256=" + URLEncoder.encode(apkHash, "UTF-8")
            val conn = (URL("$BASE_URL/api/sha-verify?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("valid", false)) {
                // Mismatch: close silently, no dialog, no message.
                Process.killProcess(Process.myPid())
            }
        } catch (_: Exception) {
            // Fail-open: any error = app runs normally.
        }
    }

    /** SHA-256 of a file, uppercase hex. Null on error. */
    private fun sha256File(path: String): String? {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(path).use { input ->
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun appVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }
}
