package com.music.bitchord.appstatus

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/** A kill-switch notice: the same Software Update card, only the caption changes. */
data class BlockingNotice(val title: String, val body: String)

/**
 * App ON/OFF kill switch + version block + device ban, ported from Shorty
 * 1.9.43 (AppOffCheck).
 *
 * One background thread, once per process, in the 1.9.43 order:
 *  1. GET /api/app-status — enabled=false means the app is OFF: post the
 *     "App band hai" notice. OK closes the app.
 *  2. GET /api/version-status?version=X — blocked=true sets [versionBlocked].
 *     The Software Update dialog is NOT replaced: it shows as usual when a
 *     newer version exists, but its "Remind Me Later" is dead while blocked
 *     (the 1.9.43 BlockedGuardAction behaviour, as a disabled row).
 *  3. GET /api/device-status?deviceId=X — blocked=true posts the
 *     "Device Banned" notice. OK closes the app.
 *
 * Fail-open throughout: any error, timeout or non-200 response means the
 * check is skipped and the app runs normally — offline music is never
 * affected. The first blocking notice wins; a later check never overwrites
 * it. No Settings UI, no Toasts, nothing on the main thread.
 */
object AppStatusCheck {

    private const val BASE_URL = "https://zeuvia.vishaltech.site"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 10000

    private val _blocking = MutableStateFlow<BlockingNotice?>(null)
    val blocking: StateFlow<BlockingNotice?> = _blocking.asStateFlow()

    private val _versionBlocked = MutableStateFlow(false)
    val versionBlocked: StateFlow<Boolean> = _versionBlocked.asStateFlow()

    private val checkStarted = AtomicBoolean(false)

    /** Call once from BitChordApplication.onCreate. Returns at once. */
    fun check(context: Context) {
        if (!checkStarted.compareAndSet(false, true)) return
        Thread({ runChecks(context.applicationContext) }, "app-status-check").start()
    }

    private fun runChecks(context: Context) {
        try {
            if (isAppOff(context)) {
                postBlocking(
                    BlockingNotice(
                        "App band hai",
                        "SHORTY PLAY abhi band hai. Kuch der baad try karo.",
                    ),
                )
            }
            if (isVersionBlocked(context)) {
                _versionBlocked.value = true
            }
            if (isDeviceBanned(context)) {
                postBlocking(
                    BlockingNotice(
                        "Device Banned",
                        "Ye device SHORTY PLAY ke liye ban hai. Dusre device pe try karo.",
                    ),
                )
            }
        } catch (_: Exception) {
            // Fail-open: never crash, never block on error.
        }
    }

    private fun postBlocking(notice: BlockingNotice) {
        if (_blocking.value == null) _blocking.value = notice
    }

    private fun getJson(url: String): JSONObject? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } catch (_: Exception) {
            null
        }
    }

    private fun isAppOff(context: Context): Boolean {
        val json = getJson("$BASE_URL/api/app-status") ?: return false
        return !json.optBoolean("enabled", true)
    }

    private fun isVersionBlocked(context: Context): Boolean {
        val version = appVersion(context)
        val query = "version=" + URLEncoder.encode(version, "UTF-8")
        val json = getJson("$BASE_URL/api/version-status?$query") ?: return false
        return json.optBoolean("blocked", false)
    }

    private fun isDeviceBanned(context: Context): Boolean {
        val query = "deviceId=" + URLEncoder.encode(deviceId(context), "UTF-8")
        val json = getJson("$BASE_URL/api/device-status?$query") ?: return false
        return json.optBoolean("blocked", false)
    }

    private fun appVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
        } catch (_: Exception) {
            ""
        }
    }

    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
}
