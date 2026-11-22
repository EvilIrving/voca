package com.parley.speaker.data.settings

import android.util.Log
import com.parley.speaker.domain.LinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class ConnectivityProbe {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun checkGemini(apiKey: String): LinkStatus = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext LinkStatus.Unconfigured
        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> LinkStatus.Connected
                    response.code == 401 || response.code == 403 -> LinkStatus.Failed
                    else -> LinkStatus.Failed
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            LinkStatus.Failed
        }
    }

    private companion object {
        const val TAG = "Probe"
    }
}
