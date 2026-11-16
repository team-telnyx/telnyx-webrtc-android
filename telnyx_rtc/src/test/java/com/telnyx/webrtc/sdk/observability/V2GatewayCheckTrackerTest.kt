package com.telnyx.webrtc.sdk.observability

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2GatewayCheckTrackerTest {
    private val events = mutableListOf<String>()
    private val tracker = V2GatewayCheckTracker(
        { events.add("start:$it") },
        { id, state -> events.add("result:$id:$state") },
        { id, message, retry -> events.add("failed:$id:$message:$retry") }
    )

    private fun request(id: String) = """{"id":"$id","method":"telnyx_rtc.gatewayState","params":{}}"""
    private fun receive(text: String, retry: Boolean = false) =
        tracker.received(JsonParser.parseString(text).asJsonObject, retry)

    @Test fun `response may arrive before write returns and completes only once`() {
        val id = tracker.beforeWrite(request("a"))
        receive("""{"id":"a","result":{"params":{"state":"REGED"}}}""")
        tracker.rejected(id)
        receive("""{"id":"a","result":{"params":{"state":"REGED"}}}""")
        assertEquals(listOf("start:a", "result:a:REGED"), events)
    }

    @Test fun `unsolicited state and unrelated rpc replies do not manufacture checks`() {
        assertNull(tracker.beforeWrite("""{"id":"login","method":"login"}"""))
        tracker.beforeWrite(request("a"))
        receive("""{"params":{"state":"REGED"}}""")
        receive("""{"id":"other","result":{"params":{"state":"REGED"}}}""")
        assertEquals(listOf("start:a"), events)
    }

    @Test fun `rejected send fails without retry and is no longer pending`() {
        tracker.rejected(tracker.beforeWrite(request("a")))
        tracker.socketLost(true)
        assertEquals(listOf("start:a", "failed:a:Gateway check write rejected:false"), events)
    }

    @Test fun `server errors are correlated and do not leak arbitrary messages`() {
        tracker.beforeWrite(request("a"))
        receive("""{"id":"a","error":{"message":"secret credential"}}""", true)
        assertEquals(listOf("start:a", "failed:a:Gateway check rejected by server:true"), events)
    }

    @Test fun `socket loss closes outstanding checks once with actual retry decision`() {
        tracker.beforeWrite(request("a"))
        tracker.beforeWrite(request("b"))
        tracker.socketLost(true)
        tracker.socketLost(false)
        assertEquals(listOf("start:a", "start:b",
            "failed:a:Gateway check interrupted by socket closure:true",
            "failed:b:Gateway check interrupted by socket closure:true"), events)
    }

    @Test fun `malformed response fails the requested check instead of claiming a state`() {
        tracker.beforeWrite(request("a"))
        receive("""{"id":"a","result":{}}""")
        assertEquals(listOf("start:a", "failed:a:Gateway check response missing state:false"), events)
    }

    @Test fun `missing responses have bounded correlation storage`() {
        repeat(65) { tracker.beforeWrite(request("$it")) }
        assertEquals(1, events.count { it == "failed:0:Gateway check correlation capacity exceeded:false" })
        tracker.socketLost(false)
        assertEquals(65, events.count { it.startsWith("failed:") })
    }

    @Test fun `malformed ids and methods cannot crash socket callbacks`() {
        assertNull(tracker.beforeWrite("""{"method":{},"id":"a"}"""))
        assertNull(tracker.beforeWrite("""{"method":"telnyx_rtc.gatewayState","id":{}}"""))
        receive("""{"id":{},"result":{}}""")
        assertEquals(emptyList<String>(), events)
    }
}
