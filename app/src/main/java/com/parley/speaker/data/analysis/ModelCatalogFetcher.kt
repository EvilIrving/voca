package com.parley.speaker.data.analysis

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * 两张清单的取数：供应商自己的 /models 与 models.dev 的公开目录。
 * 这里只拼请求、看状态码、读响应体，解析与合并全在 [ModelCatalog] 里。
 */
class ModelCatalogFetcher(
    private val client: OkHttpClient = catalogClient,
) {

    /** 带该供应商自己的密钥取一次清单，401、超时、断网都返回 null，调用方据此保留旧清单。 */
    suspend fun models(config: TextEngineConfig): List<TextModel>? = withContext(Dispatchers.IO) {
        val request = config.modelsRequest() ?: return@withContext null
        body(request)?.let(ModelCatalog::available)
    }

    /** models.dev 的目录里 key 那一家的清单，取不到就当没有，可用集合仍由 /models 决定。 */
    suspend fun directory(key: String): List<TextModel> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(DirectoryUrl).get().build()
        body(request)?.let { ModelCatalog.directory(it, key) }.orEmpty()
    }

    private fun body(request: Request): String? = try {
        client.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (response.isSuccessful) {
                text
            } else {
                Log.w(TAG, "models ${response.code}: ${text.take(160)}")
                null
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "models request failed: ${e.message}")
        null
    }

    companion object {
        const val DirectoryUrl = "https://models.dev/api.json"

        private const val TAG = "ModelCatalog"

        internal val catalogClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
