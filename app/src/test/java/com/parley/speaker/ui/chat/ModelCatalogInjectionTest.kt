package com.parley.speaker.ui.chat

import android.app.Application
import androidx.room.Room
import com.parley.speaker.data.analysis.ModelCatalogFetcher
import com.parley.speaker.data.analysis.ModelCatalogStore
import com.parley.speaker.data.analysis.TextProviderCatalog
import com.parley.speaker.data.db.ParleyDatabase
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 清单从拉取到上屏：有密钥才拉，拉到的按发布日倒序截断后注入 provider.models，
 * 清空密钥就把该家清单收掉，拉取失败保留上一次的，界面不会突然空掉。
 * 网络由拦截器顶掉，一个字节都不出机器。
 */
@RunWith(RobolectricTestRunner::class)
class ModelCatalogInjectionTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var db: ParleyDatabase
    private val server = CatalogServer()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(app, ParleyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `有密钥就拉到清单并注入这一家`() = runBlocking {
        val models = store().refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        val injected = injectModels(TextProviderCatalog.all, TextProviderCatalog.deepseek.id, models)

        assertEquals(
            "发布日新的在前，别的顺序一概不认",
            listOf("deepseek-chat", "deepseek-v3", "deepseek-coder"),
            injected.first { it.id == "deepseek" }.models.map { it.id },
        )
        assertTrue("没有密钥的那家不列模型", injected.first { it.id == "kimi" }.models.isEmpty())
    }

    @Test
    fun `清空密钥就收掉该家的清单`() = runBlocking {
        val store = store()
        store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        val cleared = store.refresh(TextProviderCatalog.deepseek, apiKey = "")
        val injected = injectModels(TextProviderCatalog.all, TextProviderCatalog.deepseek.id, cleared)

        assertTrue(cleared.isEmpty())
        assertTrue(injected.first { it.id == "deepseek" }.models.isEmpty())
    }

    @Test
    fun `拉取失败保留上一次的清单`() = runBlocking {
        val store = store()
        val first = store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")
        server.status = 401

        val afterFailure = store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        assertEquals(first.map { it.id }, afterFailure.map { it.id })
        assertEquals(first.map { it.id }, store.models("deepseek").map { it.id })
    }

    @Test
    fun `换了密钥之后的失败不再沿用旧清单`() = runBlocking {
        val store = store()
        store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-old")
        server.status = 401

        val afterRekey = store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-new")

        assertTrue(afterRekey.isEmpty())
        assertTrue(injectModels(TextProviderCatalog.all, "deepseek", afterRekey).first { it.id == "deepseek" }.models.isEmpty())
    }

    private fun store(): ModelCatalogStore = ModelCatalogStore(
        dao = db.modelCatalogs(),
        fetcher = ModelCatalogFetcher(OkHttpClient.Builder().addInterceptor(server).build()),
        now = { FetchedAt },
    )

    private companion object {
        const val FetchedAt = 1_760_000_000_000L
    }
}

/** 顶掉网络的拦截器：清单地址与目录地址各回一份，拉取失败由 [status] 模拟。 */
private class CatalogServer : Interceptor {
    var status = 200

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val directory = request.url.toString() == ModelCatalogFetcher.DirectoryUrl
        val code = if (directory) 200 else status
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Unauthorized")
            .body((if (directory) Directory else Models).toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val Models = """{"object": "list", "data": [
            {"id": "deepseek-coder"},
            {"id": "deepseek-chat"},
            {"id": "deepseek-v3"},
            {"id": "deepseek-embedding"}]}"""

        const val Directory = """{
            "deepseek": {"id": "deepseek", "models": {
                "deepseek-chat": {"release_date": "2025-03-24", "limit": {"context": 128000}},
                "deepseek-coder": {"release_date": "2024-06-14", "limit": {"context": 128000}},
                "deepseek-v3": {"release_date": "2024-12-26", "limit": {"context": 128000}}}}}"""
    }
}
