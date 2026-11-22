package com.music.bitchord.promo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** One tap zone: fractions of the poster (0–1) with its link URL. */
data class PromoZone(val x: Float, val y: Float, val w: Float, val h: Float, val url: String)

/** A poster ready to show: decoded bitmap, its size, and its tap zones. */
data class PromoPoster(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int,
    val zones: List<PromoZone>,
)

/**
 * App-open promotion poster, ported from Shorty 1.9.43 (PromoCheck).
 *
 * Once per process, on a background thread: GET /api/promo. If a poster is
 * enabled, download it and decode it FIRST — the dialog is posted only
 * when a real bitmap is in hand. No poster, promo OFF, or any error =
 * nothing shows (fail-open).
 *
 * Zones come from the same response ([{x, y, w, h, url}], fractions of the
 * poster, max 10 on the server). Tapping one opens its URL; taps elsewhere
 * do nothing. Only the X closes the poster.
 */
object PromoCheck {

    private const val BASE_URL = "https://zeuvia.vishaltech.site"
    private const val CONNECT_TIMEOUT_MS = 5000
    private const val READ_TIMEOUT_MS = 10000
    private const val MAX_ZONES = 10

    private val _poster = MutableStateFlow<PromoPoster?>(null)
    val poster: StateFlow<PromoPoster?> = _poster.asStateFlow()

    private val fetchStarted = AtomicBoolean(false)

    /** Call once from BitChordApplication.onCreate. Returns at once. */
    fun fetchOnLaunch(context: Context) {
        if (!fetchStarted.compareAndSet(false, true)) return
        Thread({ fetch(context.applicationContext) }, "promo-fetch").start()
    }

    /** Dismiss: clears the poster so it never reappears this launch. */
    fun dismiss() {
        _poster.value = null
    }

    private fun fetch(context: Context) {
        try {
            val json = getJson("$BASE_URL/api/promo") ?: return
            if (!json.optBoolean("enabled", false)) return
            val imgUrl = json.optString("url", "")
            val width = json.optInt("width", 0)
            val height = json.optInt("height", 0)
            if (imgUrl.isEmpty() || width <= 0 || height <= 0) return
            val zones = parseZones(json.optJSONArray("zones"))
            val bitmap = downloadBitmap(imgUrl) ?: return
            _poster.value = PromoPoster(bitmap, width, height, zones)
        } catch (_: Exception) {
            // Fail-open: any error = no popup.
        }
    }

    private fun parseZones(array: JSONArray?): List<PromoZone> {
        if (array == null) return emptyList()
        val zones = ArrayList<PromoZone>()
        for (i in 0 until minOf(array.length(), MAX_ZONES)) {
            val zone = array.optJSONObject(i) ?: continue
            val url = zone.optString("url", "")
            if (url.isEmpty()) continue
            zones.add(
                PromoZone(
                    x = zone.optDouble("x", 0.0).toFloat(),
                    y = zone.optDouble("y", 0.0).toFloat(),
                    w = zone.optDouble("w", 0.0).toFloat(),
                    h = zone.optDouble("h", 0.0).toFloat(),
                    url = url,
                ),
            )
        }
        return zones
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

    private fun downloadBitmap(url: String): Bitmap? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }
}
