package com.parley.speaker.data.analysis

import android.util.Log
import com.parley.speaker.domain.LinkStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 配置探针。先在本地把参数拼成一次最小请求：密钥没填、端点拼不出地址，就地给状态，一个字节都不发。
 * 拼得出来才算配置可用，真正发请求由 [check] 负责，界面拿到的仍是五档状态。
 */
object AnalysisProbe {

    data class Plan(
        val status: LinkStatus,
        val request: Request? = null,
    )

    fun plan(config: TextEngineConfig): Plan {
        if (config.apiKey.isBlank()) return Plan(LinkStatus.Unconfigured)
        if (config.endpoint.isBlank()) return Plan(LinkStatus.Unconfigured)
        val request = config.probeRequest() ?: return Plan(LinkStatus.Failed)
        return Plan(LinkStatus.Configured, request)
    }

    suspend fun check(
        config: TextEngineConfig,
        client: OkHttpClient = probeClient,
    ): LinkStatus = withContext(Dispatchers.IO) {
        val plan = plan(config)
        val request = plan.request ?: return@withContext plan.status
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) LinkStatus.Connected else LinkStatus.Failed
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            LinkStatus.Failed
        }
    }

    private const val TAG = "Probe"

    private val probeClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
}
