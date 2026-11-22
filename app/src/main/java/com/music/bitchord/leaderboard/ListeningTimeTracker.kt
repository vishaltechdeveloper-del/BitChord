package com.music.bitchord.leaderboard

import android.content.Context
import com.music.bitchord.playback.PlaybackService
import kotlin.concurrent.thread

/**
 * Listening-time tracker, ported from Shorty 1.9.43.
 *
 * Polls every 2s whether THIS app is currently playing audio and flushes
 * every 30s of accumulated playback to POST /api/listening-time. The "is
 * playing" signal is [PlaybackService.isPlayingNow], a volatile flag the
 * service sets in its own onIsPlayingChanged — no MediaController binding,
 * no binder round-trips, and the service is never woken just to be asked.
 *
 * The thread is a daemon: it costs one volatile read every 2s while idle
 * and cannot hold the process open. On a failed flush the seconds stay
 * pending and ride along with the next flush (capped at one day, matching
 * the server). At most 30s of listening is lost if the process dies
 * mid-window — the same as 1.9.43.
 */
object ListeningTimeTracker {

    private const val POLL_MS = 2000L
    private const val FLUSH_SECONDS = 30
    private const val MAX_PENDING_SECONDS = 86400

    @Volatile private var started = false
    @Volatile private var pendingSeconds = 0

    /** Call once from BitChordApplication.onCreate. Returns at once. */
    fun start(context: Context) {
        if (started) return
        started = true
        val appContext = context.applicationContext
        thread(name = "listening-time-tracker", isDaemon = true) {
            while (true) {
                try {
                    if (PlaybackService.isPlayingNow) {
                        pendingSeconds = minOf(MAX_PENDING_SECONDS, pendingSeconds + 2)
                        if (pendingSeconds >= FLUSH_SECONDS) {
                            val toSend = pendingSeconds
                            if (LeaderboardCheck.postListeningTime(appContext, toSend)) {
                                pendingSeconds = 0
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Never kill the tracker thread.
                }
                try {
                    Thread.sleep(POLL_MS)
                } catch (_: InterruptedException) {
                    return@thread
                }
            }
        }
    }
}
