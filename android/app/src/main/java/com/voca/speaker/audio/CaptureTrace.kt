package com.voca.speaker.audio

/**
 * 一段采集的分段耗时与异常，只进日志。
 *
 * 微信输入法的采集对象里带着 `firstPcmData`、`lastPcmOutputElapsedRealtimeMs`、
 * `lastVadOutputElapsedRealtimeMs`、`lastEncodeOutputElapsedRealtimeMs` 与
 * `recorderCaptureInterruptedAtMs` 这几格，豆包的识别引擎也有 `AsrStartupStage`
 * 把启动拆成进入、拿锁、建句柄几步。分段留痕的价值不在于数字本身：
 * 一轮说完没识别出东西时，靠这几格能分开是麦克风没开、采集中断、还是编码与上行没跟上。
 *
 * 这里只记采集侧，识别与播放各自在自己的那一段里记。
 */
class CaptureTrace {

    /** 从按下到采集句柄启动，含 AudioRecord 初始化与权限检查。 */
    var openMs: Long = -1
        private set

    /** 启动到第一帧 PCM。 */
    var firstFrameMs: Long = -1
        private set

    /** 两帧之间最长的一次空档，通话抢占麦克风时会在这里露出来。 */
    var maxGapMs: Long = 0
        private set

    /** 空档超过这条线就记一次中断，取 500 毫秒：正常读循环的空档在几十毫秒级。 */
    var interruptions: Int = 0
        private set

    var frames: Int = 0
        private set

    /** 收到过的帧总时长，与编码器记的时长对不上就说明有丢帧。 */
    var frameMs: Long = 0
        private set

    private var openedAt = 0L
    private var firstAt = 0L
    private var lastAt = 0L

    fun opened(now: Long) {
        openedAt = now
    }

    fun started(now: Long, frameMilliseconds: Long) {
        if (firstAt == 0L) {
            firstAt = now
            lastAt = now
            openMs = openedAt.let { if (it == 0L) 0 else now - it }
            firstFrameMs = openMs
        } else {
            val gap = now - lastAt
            if (gap > maxGapMs) maxGapMs = gap
            if (gap > GapLimitMs) interruptions++
            lastAt = now
        }
        frames++
        frameMs += frameMilliseconds
    }

    /** 收尾的一行，按日志读，不进界面。 */
    fun describe(): String =
        "open=${openMs}ms first=${firstFrameMs}ms frames=$frames audio=${frameMs}ms " +
            "gapMax=${maxGapMs}ms interruptions=$interruptions"

    companion object {
        const val GapLimitMs = 500L
    }
}
