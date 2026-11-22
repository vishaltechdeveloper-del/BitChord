package com.music.bitchord.leaderboard

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** One row of the Top 5, exactly as the server returns it. */
data class LeaderboardEntry(val rank: Int, val name: String, val seconds: Int)

/** GET /api/leaderboard response, trimmed to what the dialog shows. */
data class LeaderboardData(
    val top: List<LeaderboardEntry>,
    val yourRank: Int?,
    val yourSeconds: Int,
    val date: String,
)

/**
 * Top 5 Listeners leaderboard, ported from Shorty 1.9.43.
 *
 * On every app launch a background thread fetches GET /api/leaderboard. If
 * the day has data, the result is posted to [dialogData] and MainActivity
 * shows the frosted dialog once; if the Top 5 is empty (or the leaderboard
 * is toggled off, or the fetch fails) nothing is posted and no dialog shows.
 * The fetch runs once per process — dismissing clears the value, so the
 * dialog never reappears until the next launch.
 *
 * The identity helpers (deviceId / userName / certSha256) are the same three
 * tiny functions LicenseCheck carries; they are duplicated here on purpose
 * so this module stays self-contained and committable on its own. A future
 * cleanup could extract a shared ShortyIdentity object.
 */
object LeaderboardCheck {

    private const val BASE_URL = "https://zeuvia.vishaltech.site"
    private const val CONNECT_TIMEOUT_MS = 8000
    private const val READ_TIMEOUT_MS = 8000

    private val _dialogData = MutableStateFlow<LeaderboardData?>(null)
    val dialogData: StateFlow<LeaderboardData?> = _dialogData.asStateFlow()

    private val fetchStarted = AtomicBoolean(false)

    /** Call once from BitChordApplication.onCreate. Returns at once. */
    fun fetchOnLaunch(context: Context) {
        if (!fetchStarted.compareAndSet(false, true)) return
        Thread({ fetch(context.applicationContext) }, "leaderboard-fetch").start()
    }

    /** Dismiss: clears the pending dialog so it never reappears this launch. */
    fun dismiss() {
        _dialogData.value = null
    }

    private fun fetch(context: Context) {
        try {
            val data = fetchLeaderboard(context) ?: return
            if (data.top.isEmpty()) return
            _dialogData.value = data
        } catch (_: Exception) {
            // Never crash, never show anything on error.
        }
    }

    internal fun fetchLeaderboard(context: Context): LeaderboardData? {
        val query = "deviceId=" + URLEncoder.encode(deviceId(context), "UTF-8")
        val conn = (URL("$BASE_URL/api/leaderboard?$query").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }
        if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
        val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        if (!json.optBoolean("enabled", true)) return null
        val topJson = json.optJSONArray("top") ?: JSONArray()
        val top = (0 until topJson.length()).mapNotNull { i ->
            val row = topJson.optJSONObject(i) ?: return@mapNotNull null
            LeaderboardEntry(
                rank = row.optInt("rank", i + 1),
                name = row.optString("name", "").ifEmpty { "Listener" },
                seconds = row.optInt("seconds", 0),
            )
        }
        return LeaderboardData(
            top = top,
            yourRank = json.optInt("yourRank", 0).takeIf { it > 0 },
            yourSeconds = json.optInt("yourSeconds", 0),
            date = json.optString("date", ""),
        )
    }

    /** POST /api/listening-time. True when the server accepted the seconds. */
    internal fun postListeningTime(context: Context, seconds: Int): Boolean {
        return try {
            if (seconds <= 0) return true
            val body = JSONObject().apply {
                put("deviceId", deviceId(context))
                put("name", userName(context))
                put("seconds", seconds)
                put("fp", certSha256(context))
            }
            val conn = (URL("$BASE_URL/api/listening-time").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json")
                doOutput = true
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return false
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optBoolean("ok", false)
        } catch (_: Exception) {
            false
        }
    }

    internal fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()

    /** Onboarding / party nickname, read directly so no init order is assumed. */
    internal fun userName(context: Context): String =
        context.getSharedPreferences("bitchord_listen_together", Context.MODE_PRIVATE)
            .getString("party_nickname", null).orEmpty()

    /** SHA-256 of this build's own signing certificate, uppercase hex. */
    internal fun certSha256(context: Context): String {
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

    /** "1h 23m" / "45m" — same shape as the backend's fmtSecs. */
    fun formatSeconds(seconds: Int): String {
        val s = maxOf(0, seconds)
        val h = s / 3600
        val m = (s % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }
}
