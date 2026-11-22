package com.parley.speaker.data.analysis

import android.app.Application
import androidx.room.Room
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 清单缓存：拉到的结果连同时间戳落库，重开一次仍读得到；没有密钥返回空；
 * 拉取失败保留上一次；换了密钥之后的失败不再沿用旧清单。
 * 网络由拦截器顶掉，一个字节都不出机器。
 */
@RunWith(RobolectricTestRunner::class)
class ModelCatalogStoreTest {

    private val app: Application = RuntimeEnvironment.getApplication()
    private lateinit var db: ParleyDatabase
    private val server = ScriptedServer()

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
    fun `拉到的清单带时间戳落库，重开仍读得到`() = runBlocking {
        val store = store()

        val fetched = store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        assertEquals(Display, fetched.map { it.id })
        assertEquals(128000, fetched.first { it.id == "deepseek-chat" }.context)
        assertEquals("2025-03-24", fetched.first { it.id == "deepseek-chat" }.releaseDate)
        assertEquals(FetchedAt, store.fetchedAt("deepseek"))

        val reopened = store()
        assertEquals(Display, reopened.models("deepseek").map { it.id })
        assertEquals("high", reopened.model("deepseek", "deepseek-reasoner")?.defaultEffort)
    }

    @Test
    fun `DeepSeek 按自己的键补字段，转售同一个 id 的那家不串值`() = runBlocking {
        val fetched = store().refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        val chat = fetched.first { it.id == "deepseek-chat" }
        assertEquals("目录里 deepseek 那一家的窗口，不是转售那家的", 128000, chat.context)
        assertEquals("2025-03-24", chat.releaseDate)
    }

    @Test
    fun `Kimi 按 moonshotai 的键补字段，不取转售那家的值`() = runBlocking {
        val models = store().refresh(TextProviderCatalog.kimi, apiKey = "sk-kimi")

        assertEquals(262144, models.single().context)
        assertEquals("2025-07-11", models.single().releaseDate)
    }

    @Test
    fun `请求带该供应商自己的密钥与清单地址`() = runBlocking {
        store().refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        val models = server.requests.first()
        assertEquals("https://api.deepseek.com/v1/models", models.url.toString())
        assertEquals("GET", models.method)
        assertEquals("Bearer sk-deep", models.header("Authorization"))
        assertEquals(ModelCatalogFetcher.DirectoryUrl, server.requests.last().url.toString())
    }

    @Test
    fun `没有密钥就不列模型并清掉旧缓存`() = runBlocking {
        val store = store()
        store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        val cleared = store.refresh(TextProviderCatalog.deepseek, apiKey = "")

        assertTrue(cleared.isEmpty())
        assertTrue(store.models("deepseek").isEmpty())
        assertNull(store.fetchedAt("deepseek"))
    }

    @Test
    fun `拉取失败保留上一次的清单`() = runBlocking {
        val store = store()
        store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")
        server.modelsStatus = 401

        val afterFailure = store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        assertEquals(Display, afterFailure.map { it.id })
        assertEquals(Display, store.models("deepseek").map { it.id })
        assertEquals(FetchedAt, store.fetchedAt("deepseek"))
    }

    @Test
    fun `从未成功过的供应商是空清单`() = runBlocking {
        server.modelsStatus = 401
        val store = store()

        assertTrue(store.refresh(TextProviderCatalog.kimi, apiKey = "sk-kimi").isEmpty())
        assertTrue(store.models("kimi").isEmpty())
        assertNull(store.fetchedAt("kimi"))
    }

    @Test
    fun `换了密钥之后失败的那个不再沿用旧清单`() = runBlocking {
        val store = store()
        store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-old")
        server.modelsStatus = 401

        val afterRekey = store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-new")

        assertTrue(afterRekey.isEmpty())
        assertTrue(store.models("deepseek").isEmpty())
    }

    @Test
    fun `清空一家的缓存不动别家`() = runBlocking {
        val store = store()
        store.refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")
        store.refresh(TextProviderCatalog.kimi, apiKey = "sk-kimi")

        store.clear("deepseek")

        assertTrue(store.models("deepseek").isEmpty())
        assertEquals(listOf("kimi-k2"), store.models("kimi").map { it.id })
    }

    @Test
    fun `展示按发布日倒序并每家截到八条`() = runBlocking {
        val models = store().refresh(TextProviderCatalog.deepseek, apiKey = "sk-deep")

        assertEquals(Display, models.map { it.id })
        assertEquals(8, models.size)
        assertFalse("非对话模型不进清单", models.any { it.id.contains("embedding") })
    }

    private fun store(): ModelCatalogStore = ModelCatalogStore(
        dao = db.modelCatalogs(),
        fetcher = ModelCatalogFetcher(OkHttpClient.Builder().addInterceptor(server).build()),
        now = { FetchedAt },
    )

    private companion object {
        const val FetchedAt = 1_760_000_000_000L

        val Display = listOf(
            "deepseek-chat",
            "deepseek-reasoner",
            "deepseek-v3",
            "deepseek-lite",
            "deepseek-coder",
            "deepseek-v2",
            "deepseek-vl",
            "deepseek-math",
        )
    }
}

/**
 * 顶掉网络的拦截器：清单地址与目录地址各回一份，请求按顺序记下来给断言用。
 * 换供应商时按主机名换一份清单返回体，拉取失败由 [modelsStatus] 模拟。
 */
private class ScriptedServer : Interceptor {
    var modelsStatus = 200

    val requests = mutableListOf<Request>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val directory = request.url.toString() == ModelCatalogFetcher.DirectoryUrl
        val code = if (directory) 200 else modelsStatus
        val body = when {
            directory -> Directory
            request.url.host.contains("moonshot") -> MoonshotModels
            else -> DeepseekModels
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code == 200) "OK" else "Unauthorized")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()
    }

    private companion object {
        const val DeepseekModels = """{"object": "list", "data": [
            {"id": "deepseek-chat"},
            {"id": "deepseek-reasoner", "reasoning": true, "effort": {"default": "high"}},
            {"id": "deepseek-coder"},
            {"id": "deepseek-vl"},
            {"id": "deepseek-math"},
            {"id": "deepseek-v3"},
            {"id": "deepseek-v2"},
            {"id": "deepseek-v1"},
            {"id": "deepseek-lite"},
            {"id": "deepseek-embedding"}]}"""

        const val MoonshotModels = """{"object": "list", "data": [{"id": "kimi-k2"}]}"""

        const val Directory = """{
            "openrouter": {"id": "openrouter", "models": {
                "deepseek-chat": {"release_date": "2024-01-02", "limit": {"context": 4096}},
                "kimi-k2": {"release_date": "2024-02-02", "limit": {"context": 8192}}}},
            "deepseek": {"id": "deepseek", "models": {
                "deepseek-chat": {"release_date": "2025-03-24", "reasoning": false,
                    "limit": {"context": 128000, "output": 8192}},
                "deepseek-reasoner": {"release_date": "2025-01-20", "reasoning": true,
                    "limit": {"context": 128000, "output": 65536}},
                "deepseek-coder": {"release_date": "2024-06-14", "limit": {"context": 128000}},
                "deepseek-vl": {"release_date": "2024-03-11"},
                "deepseek-math": {"release_date": "2024-02-05"},
                "deepseek-v3": {"release_date": "2024-12-26"},
                "deepseek-v2": {"release_date": "2024-05-06"},
                "deepseek-v1": {"release_date": "2024-01-11"},
                "deepseek-lite": {"release_date": "2024-09-05"}}},
            "moonshotai": {"id": "moonshotai", "models": {
                "kimi-k2": {"release_date": "2025-07-11", "limit": {"context": 262144}}}}}"""
    }
}
