package com.parley.speaker.audio

import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 暂停态的试听：播的就是刚录下的那只 OGG。
 * MediaPlayer 的句柄只在这一类里流转，准备放在 IO 上做，界面只读进度与是否在播。
 */
class ClipPlayer {

    private val lock = Any()
    private var player: MediaPlayer? = null
    private var loaded: String? = null

    /** 换了一段就把旧的放掉重开；同一段再点播放只是回到开头。 */
    suspend fun prepare(path: String): Boolean = withContext(Dispatchers.IO) {
        synchronized(lock) {
            if (loaded != path || player == null) {
                releaseLocked()
                val created = try {
                    MediaPlayer().apply {
                        setDataSource(path)
                        prepare()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "prepare failed: ${e.message}")
                    null
                }
                if (created == null) return@synchronized false
                player = created
                loaded = path
            }
            val current = player ?: return@synchronized false
            try {
                if (!current.isPlaying) current.seekTo(0)
                true
            } catch (e: IllegalStateException) {
                Log.w(TAG, "seek failed: ${e.message}")
                false
            }
        }
    }

    fun start(): Boolean = synchronized(lock) {
        val current = player ?: return@synchronized false
        try {
            current.start()
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "start failed: ${e.message}")
            false
        }
    }

    fun pause() {
        synchronized(lock) {
            try {
                player?.pause()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "pause failed: ${e.message}")
            }
        }
    }

    fun isPlaying(): Boolean = synchronized(lock) {
        try {
            player?.isPlaying ?: false
        } catch (e: IllegalStateException) {
            false
        }
    }

    fun progress(): Float = synchronized(lock) {
        val current = player ?: return@synchronized 0f
        val duration = try {
            current.duration
        } catch (e: IllegalStateException) {
            return@synchronized 0f
        }
        val position = try {
            current.currentPosition
        } catch (e: IllegalStateException) {
            return@synchronized 0f
        }
        if (duration <= 0) 0f else (position.toFloat() / duration).coerceIn(0f, 1f)
    }

    fun release() {
        synchronized(lock) { releaseLocked() }
    }

    private fun releaseLocked() {
        try {
            player?.release()
        } catch (e: IllegalStateException) {
            Log.w(TAG, "release failed: ${e.message}")
        }
        player = null
        loaded = null
    }

    private companion object {
        const val TAG = "ClipPlayer"
    }
}
