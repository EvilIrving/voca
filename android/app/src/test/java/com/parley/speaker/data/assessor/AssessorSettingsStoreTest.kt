package com.parley.speaker.data.assessor

import android.content.Context
import android.content.SharedPreferences
import com.parley.speaker.data.settings.putAssessor
import com.parley.speaker.data.settings.readAssessor
import com.parley.speaker.domain.AssessorProvider
import com.parley.speaker.domain.AssessorSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * 存取往返用的是进程内的普通 SharedPreferences，不碰真机上的加密存储。
 * 断言的是界面上填完凭据再进设置页看到的状态：哪家齐备就哪家启用。
 */
@RunWith(RobolectricTestRunner::class)
class AssessorSettingsStoreTest {

    private val prefs: SharedPreferences =
        RuntimeEnvironment.getApplication().getSharedPreferences("assessor", Context.MODE_PRIVATE)

    @Before
    fun clear() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `讯飞三件套存回后启用`() {
        save(AssessorSettings(xfyunAppId = appId, xfyunApiKey = key, xfyunApiSecret = secret))

        val loaded = readAssessor(prefs)
        assertEquals(AssessorProvider.Xfyun, loaded.provider)
        assertEquals(XfyunCredentials(appId = appId, apiKey = key, apiSecret = secret), loaded.xfyunCredentials)
        assertTrue(assessorOf(loaded) is XfyunAssessor)
    }

    @Test
    fun `Azure 两项存回后启用，讯飞半套不算`() {
        save(AssessorSettings(azureKey = key, azureRegion = region))
        assertEquals(AssessorProvider.Azure, readAssessor(prefs).provider)
        assertTrue(assessorOf(readAssessor(prefs)) is AzureAssessor)

        save(AssessorSettings(xfyunAppId = appId, xfyunApiKey = key))
        assertEquals(AssessorProvider.None, readAssessor(prefs).provider)
        assertNull(assessorOf(readAssessor(prefs)))
    }

    @Test
    fun `两家都齐备时显式存下的 provider 压过推导`() {
        val both = AssessorSettings(
            azureKey = key,
            azureRegion = region,
            xfyunAppId = appId,
            xfyunApiKey = key,
            xfyunApiSecret = secret,
        )

        save(both.copy(provider = AssessorProvider.Xfyun))
        assertEquals(AssessorProvider.Xfyun, readAssessor(prefs).provider)

        save(both.copy(provider = AssessorProvider.Azure))
        assertEquals(AssessorProvider.Azure, readAssessor(prefs).provider)
    }

    @Test
    fun `凭据清空后回到未选择`() {
        save(AssessorSettings(azureKey = key, azureRegion = region))
        save(AssessorSettings())

        val loaded = readAssessor(prefs)
        assertEquals(AssessorProvider.None, loaded.provider)
        assertEquals("", loaded.azureKey)
        assertNull(assessorOf(loaded))
    }

    private fun save(settings: AssessorSettings) {
        prefs.edit().putAssessor(settings).commit()
    }

    private companion object {
        const val key = "k-000"
        const val region = "eastasia"
        const val appId = "app-000"
        const val secret = "s-000"
    }
}
