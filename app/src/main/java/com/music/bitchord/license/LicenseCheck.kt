package com.music.bitchord.license

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.music.bitchord.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Cached license gate, ported from Shorty 1.9.43 (LicenseCheck).
 *
 * The server issues a 7-day license; the app caches it in SharedPreferences
 * ("sp_license") and re-validates on every launch. Behaviour:
 * - Valid cached license: app opens normally; a background thread checks the
 *   instant-ban status and renews the license when under 2 days remain.
 * - No/expired cached license: a fresh one is fetched in the background. If
 *   the server cannot be reached or refuses, a non-dismissible dialog blocks
 *   the app (fail-closed). First install with no server = blocked.
 * - Server/offline error with a valid cached license: the app keeps running
 *   on the cached license (grace). Only an explicit ban blocks it.
 * - Clock rollback (now well behind the last-seen time) invalidates the cache.
 *
 * Smoothness: [check] does only a fast SharedPreferences read on the calling
 * thread and returns immediately — it never delays the first frame. Every
 * network call runs on a background thread; the blocking dialog is posted to
 * the main thread only after the check finishes. Nothing here touches
 * Settings: the license is background-only, exactly like 1.9.43.
 *
 * The signing-cert fingerprint is computed at runtime from this build's own
 * certificate (never hardcoded), so a debug-signed build reports its own
 * SHA-256 and the server can allow-list it separately from the release key.
 */
object LicenseCheck {

    private const val BASE_URL = "https://zeuvia.vishaltech.site"
    private const val PREFS = "sp_license"
    private const val KEY_LICENSE = "license"
    private const val KEY_EXPIRES_AT = "expiresAt"
    private const val KEY_LAST_SEEN = "lastSeen"

    private const val RENEW_BEFORE_MS = 2L * 24 * 60 * 60 * 1000
    private const val ROLLBACK_TOLERANCE_MS = 5L * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    private val dialogShown = AtomicBoolean(false)

    /** Call from MainActivity.onCreate, before setContent. Returns at once. */
    fun check(activity: Activity) {
        val appContext = activity.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val cachedLicense = prefs.getString(KEY_LICENSE, null).orEmpty()
        val cachedExpiresAt = prefs.getLong(KEY_EXPIRES_AT, 0L)
        val lastSeen = prefs.getLong(KEY_LAST_SEEN, 0L)

        val rolledBack = lastSeen > 0L && now < lastSeen - ROLLBACK_TOLERANCE_MS
        if (rolledBack) prefs.edit().clear().apply()
        val cacheValid = !rolledBack && cachedLicense.isNotEmpty() && cachedExpiresAt > now
        if (cacheValid) prefs.edit().putLong(KEY_LAST_SEEN, max(lastSeen, now)).apply()

        Thread({ runCheck(appContext, activity, now, cacheValid, cachedExpiresAt) }, "license-check").start()
    }

    private fun runCheck(context: Context, activity: Activity, now: Long, cacheValid: Boolean, cachedExpiresAt: Long) {
        try {
            if (cacheValid) {
                if (isBanned(context)) {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
                    showBlocked(activity, "banned")
                    return
                }
                if (cachedExpiresAt - now < RENEW_BEFORE_MS) fetchLicense(context)
                return
            }
            val reason = fetchLicense(context)
            if (reason != null) showBlocked(activity, reason)
        } catch (_: Exception) {
            // Never crash the app. With a valid cache the app is already
            // running; without one, fail closed.
            if (!cacheValid) showBlocked(activity, "network")
        }
    }

    /** True only on an explicit {ok:true, banned:true}. Any error means grace. */
    private fun isBanned(context: Context): Boolean {
        return try {
            val query = "deviceId=${URLEncoder.encode(deviceId(context), "UTF-8")}" +
                "&fp=${certSha256(context)}" +
                "&name=${URLEncoder.encode(userName(context), "UTF-8")}"
            val conn = (URL("$BASE_URL/api/license/status?$query").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optBoolean("ok", false) && json.optBoolean("banned", false)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Fetches a fresh 7-day license. Returns null on success (license saved),
     * otherwise a reason code: the server's reason (bad_fp, app_off, blocked,
     * banned, license_banned) or "network" for any transport failure.
     */
    private fun fetchLicense(context: Context): String? {
        return try {
            val body = JSONObject().apply {
                put("deviceId", deviceId(context))
                put("name", userName(context))
                put("version", BuildConfig.VERSION_NAME)
                put("versionCode", BuildConfig.VERSION_CODE)
                put("fp", certSha256(context))
            }
            val conn = (URL("$BASE_URL/api/license").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return "network"
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (!json.optBoolean("ok", false)) return json.optString("reason", "network").ifEmpty { "network" }
            val license = json.optString("license", "")
            val expiresAt = json.optLong("expiresAt", 0L)
            if (license.isEmpty() || expiresAt <= System.currentTimeMillis()) return "network"
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_LICENSE, license)
                .putLong(KEY_EXPIRES_AT, expiresAt)
                .putLong(KEY_LAST_SEEN, System.currentTimeMillis())
                .apply()
            null
        } catch (_: Exception) {
            "network"
        }
    }

    private fun showBlocked(activity: Activity, reason: String) {
        if (!dialogShown.compareAndSet(false, true)) return
        activity.runOnUiThread {
            try {
                if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                AlertDialog.Builder(activity)
                    .setTitle("SHORTY PLAY")
                    .setMessage(messageFor(reason))
                    .setCancelable(false)
                    .setPositiveButton("Close") { _, _ -> activity.finish() }
                    .show()
            } catch (_: Exception) {
                try { activity.finish() } catch (_: Exception) { }
            }
        }
    }

    private fun messageFor(reason: String): String = when (reason) {
        "app_off" -> "SHORTY PLAY is currently unavailable. Please try again later."
        "blocked" -> "This version is no longer supported. Please update the app."
        "banned", "license_banned" -> "This device has been blocked."
        "bad_fp" -> "This app could not be verified. Please install the official SHORTY PLAY app."
        else -> "Could not verify your license. Check your connection and reopen the app."
    }

    private fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /** Onboarding / party nickname, read directly so no init order is assumed. */
    private fun userName(context: Context): String =
        context.getSharedPreferences("bitchord_listen_together", Context.MODE_PRIVATE)
            .getString("party_nickname", null).orEmpty()

    /** SHA-256 of this build's own signing certificate, uppercase hex. */
    private fun certSha256(context: Context): String {
        return try {
            val pm = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            }
            val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            val cert = certs?.firstOrNull()?.toByteArray() ?: return ""
            MessageDigest.getInstance("SHA-256").digest(cert)
                .joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            ""
        }
    }
}
