@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.lib.RTCStats
import io.mockk.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class AndroidV2CaptureTest {
    private fun client(id: String): TelnyxClient {
        val client = mockk<TelnyxClient>(relaxed = true)
        var sink: CallReportV2Ingress? = null
        every { client.callReportV2InstanceId } returns id
        every { client.callReportV2 } answers { sink }
        every { client.callReportV2 = any() } answers { sink = firstArg() }
        return client
    }
    private val options = V2CaptureOptions(false, false, false, 2)
    private fun params(frames: List<String>) = frames.map {
        Json.parseToJsonElement(it).jsonObject.getValue("params").jsonObject
    }
    private fun report(packets: Double): RTCStatsReport {
        val inbound = mockk<RTCStats>()
        every { inbound.id } returns "audio-in"
        every { inbound.type } returns "inbound-rtp"
        every { inbound.members } returns mapOf("kind" to "audio", "packetsReceived" to packets,
            "bytesReceived" to packets * 100, "packetsLost" to 0.0)
        val report = mockk<RTCStatsReport>()
        every { report.statsMap } returns mapOf("audio-in" to inbound)
        return report
    }
    @Test fun `two SDK clients sharing call ID cannot clear one another and terminal is once`() = runBlocking {
        val a = client("instance-a"); val b = client("instance-b")
        val captureA = AndroidV2Capture(a); val captureB = AndroidV2Capture(b)
        try {
            val tokenA = V2CaptureToken(); val tokenB = V2CaptureToken()
            a.callReportV2!!.started(tokenA, "same-call", false, false, true, options)
            b.callReportV2!!.started(tokenB, "same-call", true, false, false, options)
            a.callReportV2!!.signaling(1, "voice-a", "server-a")
            b.callReportV2!!.signaling(1, "voice-b", "server-b")
            a.callReportV2!!.state(tokenA, CallState.ACTIVE)
            a.callReportV2!!.state(tokenA, CallState.DONE())
            a.callReportV2!!.peerClosing(tokenA, report(10.0))
            a.callReportV2!!.peerClosing(tokenA, report(12.0))
            b.callReportV2!!.state(tokenB, CallState.ACTIVE)
            val eventsA = params(captureA.capturedFrames())
            val eventsB = params(captureB.capturedFrames())
            assertEquals(1, eventsA.count { it["name"]!!.jsonPrimitive.content == "call_ended" })
            assertEquals("instance-b", eventsB.last()["ids"]!!.jsonObject["sdk_instance_id"]!!.jsonPrimitive.content)
            assertEquals("same-call", eventsB.last()["ids"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
            assertTrue(eventsA.zipWithNext().all { (x,y) -> x["sequence"]!!.jsonPrimitive.long < y["sequence"]!!.jsonPrimitive.long })
            val end = eventsA.last()["payload"]!!.jsonObject
            assertEquals(10.0, end["totals"]!!.jsonObject["in_packets"]!!.jsonPrimitive.double, 0.01)
            assertEquals("before_peer_disposal", end["extra"]!!.jsonObject["final_stats_source"]!!.jsonPrimitive.content)
        } finally { captureA.shutdown(); captureB.shutdown() }
    }
    @Test fun `reattach rejects delayed old peer terminal and sampling`() = runBlocking {
        val client = client("instance"); val capture = AndroidV2Capture(client)
        try {
            val old = V2CaptureToken(); val replacement = V2CaptureToken()
            client.callReportV2!!.started(old, "call", true, false, false, options)
            client.callReportV2!!.started(replacement, "call", true, true, false, options)
            client.callReportV2!!.state(old, CallState.DONE())
            client.callReportV2!!.peerClosing(old, report(100.0))
            client.callReportV2!!.state(replacement, CallState.ACTIVE)
            val events = params(capture.capturedFrames())
            assertEquals(0, events.count { it["name"]!!.jsonPrimitive.content == "call_ended" })
            assertEquals("call_state", events.last()["name"]!!.jsonPrimitive.content)
            assertEquals("active", events.last()["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }
}
