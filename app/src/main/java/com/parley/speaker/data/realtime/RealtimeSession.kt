package com.parley.speaker.data.realtime

interface RealtimeSession {
    val capabilities: Capabilities
    fun connect()
    fun startTurn()
    fun sendAudio(frame: ShortArray)
    fun sendText(text: String)
    fun endTurn()
    fun interrupt()
    fun close()
}
