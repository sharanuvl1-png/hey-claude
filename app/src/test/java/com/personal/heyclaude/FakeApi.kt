package com.personal.heyclaude

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.util.Collections

/** A stand-in for the Claude API: replays scripted replies and keeps every request body. */
class FakeApi(private val script: List<Pair<Int, String>>) {
    val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = MockWebServer()
    val url: String

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request.body.readUtf8()
                val (code, body) = script[minOf(requests.size - 1, script.size - 1)]
                return MockResponse().setResponseCode(code).setHeader("content-type", "application/json").setBody(body)
            }
        }
        server.start()
        url = server.url("/").toString().trimEnd('/')
    }

    fun stop() = server.shutdown()

    companion object {
        fun ok(vararg replies: String) = FakeApi(replies.map { 200 to it })

        fun msg(stop: String, content: String) = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-opus-5-5","content":[$content],
             "stop_reason":"$stop","stop_sequence":null,"usage":{"input_tokens":10,"output_tokens":5}}
        """.trimIndent()

        fun text(t: String) = msg("end_turn", """{"type":"text","text":"$t"}""")

        const val BAD_KEY = """{"type":"error","error":{"type":"authentication_error","message":"invalid x-api-key"}}"""
    }
}

class FakeTools : MehithaTools {
    val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
    override fun searchPhoneData(query: String): String { calls += "phone:$query"; return "SMS 25 Oct from redBus: ticket TVBN78163260, 6:30 PM" }
    override fun stockScan(): String { calls += "scan"; return "# Mehitha daily stock scan, 2026-10-26\nHCLTECH BUY NOW" }
}
