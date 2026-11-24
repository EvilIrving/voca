package com.parley.speaker.ui.chat

import kotlin.math.exp

/**
 * 行高在正文里是量化的一格：长出一行，整块内容就往上一格，切在一帧上，尾巴淡入盖不住它。
 * 这里记住上一帧量到的内容高度，长出来的那段先让「画」把整块往回压，再按时间常数逐帧收掉。
 * 收的是位移，布局一格没动：不重排、不滚动，也就不需要等下一帧才追上。
 * 主线程独享，读写在画那一层，不进快照。
 */
internal class ContentSlide(
    /** 一屏级别的跳变（切会话、载入历史）不做补位，直接从当下位置开始，免得整段内容飞上来。 */
    private val maxStepPx: Float = 320f,
    /** 收掉的那一段走指数衰减，时间常数这么长，大约四拍走完九成五。 */
    private val tauMs: Float = 55f,
) {
    private var measured = 0
    private var offset = 0f
    private var lastNanos = 0L

    /** 与当下布局对齐，不补位：用户自己在拖、或者内容整块换掉时走这条。 */
    fun sync(contentHeight: Int) {
        measured = contentHeight
        offset = 0f
        lastNanos = 0L
    }

    /** 返回这一帧要往下让多少像素。 */
    fun settle(contentHeight: Int, nowNanos: Long): Float {
        if (lastNanos == 0L) {
            measured = contentHeight
            lastNanos = nowNanos
            return 0f
        }
        val elapsedMs = (nowNanos - lastNanos) / 1_000_000f
        lastNanos = nowNanos
        val growth = contentHeight - measured
        measured = contentHeight
        if (growth > maxStepPx) {
            offset = 0f
            return 0f
        }
        if (growth > 0) offset += growth
        if (offset > 0f && elapsedMs > 0f) {
            offset *= exp(-elapsedMs / tauMs)
            if (offset < HalfPixel) offset = 0f
        }
        return offset
    }

    private companion object {
        const val HalfPixel = 0.5f
    }
}
