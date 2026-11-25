package com.voca.speaker.data.assessor

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 线上探针：用 .env 里的真实凭据把一段音频打到讯飞评测，把服务端每次回的话原样记下来。
 * 单测不带网络，只有 tools/ise-check.sh 显式传了音频路径才跑，缺环境变量就跳过。
 */
class XfyunLiveProbeTest {

    @Test
    fun probe() {
        val appId = System.getenv("XFYUN_APP_ID").orEmpty()
        val apiKey = System.getenv("XFYUN_API_KEY").orEmpty()
        val apiSecret = System.getenv("XFYUN_API_SECRET").orEmpty()
        val pcmPath = System.getenv("ISE_PCM").orEmpty()
        assumeTrue("缺凭据或音频，跳过", apiKey.isNotBlank() && apiSecret.isNotBlank() && pcmPath.isNotBlank())
        val pcm = File(pcmPath).readBytes()
        val text = System.getenv("ISE_TEXT").orEmpty()

        val messages = mutableListOf<String>()
        val done = CountDownLatch(1)
        val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()
        val request = Request.Builder()
            .url(xfyunUrl(XfyunCredentials(appId, apiKey, apiSecret), xfyunDate()))
            .build()
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                println("PROBE open ${response.code}")
                Thread {
                    Thread.sleep(200)
                    println("PROBE first ${webSocket.send(xfyunFirstFrame(appId, text).toString())}")
                    for (frame in xfyunAudioFrames(pcm)) {
                        if (!webSocket.send(xfyunAudioFrame(frame).toString())) {
                            println("PROBE send failed at ${frame.status}")
                            break
                        }
                        if (frame.status == 1) Thread.sleep(40)
                    }
                }.start()
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                println("PROBE closing $code $reason")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                messages += text
                println("PROBE msg ${text.take(400)}")
                if (jsonOf(text)?.obj("data")?.int("status") == 2) done.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                println("PROBE failure ${t.message} ${response?.code}"); t.printStackTrace()
                done.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                println("PROBE closed $code $reason")
                done.countDown()
            }
        })
        done.await(40, TimeUnit.SECONDS)
        socket.close(1000, null)
        println("PROBE total ${messages.size}")
        val last = messages.lastOrNull()
        println("PROBE parsed ${last?.let(::parseXfyunResult)}")
        runBlocking { XfyunAssessor(XfyunCredentials(appId, apiKey, apiSecret)).assess(pcm, text, "en-US") }
            .let { println("PROBE assessor $it") }
        if (System.getenv("ISE_BAD_KEY") == "1") {
            val bad = XfyunCredentials(appId, "0".repeat(32), apiSecret)
            runBlocking { XfyunAssessor(bad).assess(pcm, text, "en-US") }
                .let { println("PROBE badkey $it") }
        }
    }
}
