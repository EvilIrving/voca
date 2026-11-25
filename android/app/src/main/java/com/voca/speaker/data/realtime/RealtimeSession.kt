package com.voca.speaker.data.realtime

interface RealtimeSession {
    val capabilities: Capabilities
    fun connect()
    fun startTurn()
    fun sendAudio(frame: ShortArray)
    fun sendText(text: String)
    fun endTurn()

    /** 取消这一轮：通知服务端别生成，并把 turnId 记进短命黑名单，迟到的事件带着它回来。 */
    fun cancelTurn(turnId: String)
    fun interrupt()
    fun close()
}
