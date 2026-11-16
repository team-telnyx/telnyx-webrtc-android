@file:Suppress("FunctionNaming", "MagicNumber")

package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.lib.RTCStatsReport
import com.telnyx.webrtc.lib.RTCStats
import io.mockk.*
import com.telnyx.webrtc.mobile.shared.observability.core.v2.V2Clock
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class AndroidV2CaptureTest {
    private fun client(id: String): TelnyxClient {
        val client = mockk<TelnyxClient>(relaxed = true)
        var sink: CallReportV2Ingress? = null
        val sequence = java.util.concurrent.atomic.AtomicLong()
        every { client.nextCallReportV2Sequence() } answers { sequence.incrementAndGet() }
        every { client.callReportV2InstanceId } returns id
        every { client.callReportV2 } answers { sink }
        every { client.callReportV2 = any() } answers { sink = firstArg() }
        return client
    }
    private val options = V2CaptureOptions(false, false, false, 2, 5)
    @Test fun `shutdown remains idempotent after disposal and reporter replacement`() = runBlocking {
        val client = client("disposed-owner")
        val old = AndroidV2Capture(client)
        client.callReportV2!!.dispose()
        val replacement = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2
            old.shutdown(); old.shutdown()
            assertSame(sink, client.callReportV2)
            assertTrue(sink!!.isActive())
        } finally { replacement.shutdown() }
    }
    @Test fun `OS microphone observations remain app scoped during calls`() = runBlocking {
        val client = client("os-facts"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "active-call", false, false, true, options)
            sink.nativeFact(V2NativeFact.MicrophonePermission(false))
            sink.nativeFact(V2NativeFact.MicrophoneSystemMute(true))
            val events = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "logs" }
            assertEquals(2, events.size)
            events.forEach { assertNull(it["ids"]!!.jsonObject["call_id"]) }
            val body = events.last()["payload"].toString()
            assertTrue(body.contains("microphone_system_mute_observed"))
            assertTrue(body.contains("android_os"))
            assertTrue(body.contains("microphone"))
            assertFalse(body.contains("silenced_count"))
        } finally { capture.shutdown() }
    }
    @Test fun `terminal DROPPED with live peer closes exactly once despite teardown races`() = runBlocking {
        val client = client("closure-once"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "closure-call", false, false, true, options)
            sink.state(token, CallState.DROPPED(com.telnyx.webrtc.sdk.model.CallNetworkChangeReason.NETWORK_LOST))
            sink.peerClosing(token, null)
            sink.state(token, CallState.ACTIVE)
            sink.peerClosing(token, report(5.0))
            val events = params(capture.capturedFrames())
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "call_ended" })
            val states = events.filter { it["name"]!!.jsonPrimitive.content == "call_state" }
            assertEquals(listOf("hangup"), states.map { it["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content })
        } finally { capture.shutdown() }
    }
    private class SteppingClock(var nowMs: Long) : V2Clock {
        override fun monotonicMs() = nowMs
        override fun wallTimestamp() = "2026-11-11T00:00:00.000Z"
    }
    private suspend fun timingsLast(capture: AndroidV2Capture): JsonObject =
        params(capture.capturedFrames()).last { it["name"]!!.jsonPrimitive.content == "session_timings" }["payload"]!!.jsonObject
    @Test fun `session timings measure chain start, login-to-ready and first-chain app wait`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("chain-timings"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 1_400
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 1_500
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"chain-one"}""")
            clock.nowMs = 1_600
            sink.signalingFrame("""{"id":"chain-one","result":{"message":"logged in"}}""", false)
            clock.nowMs = 3_000
            sink.ready()
            val timings = timingsLast(capture)
            assertEquals(1_000.0, timings["app_wait_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(2_000.0, timings["connect_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(400.0, timings["socket_connect_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(100.0, timings["login_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1_400.0, timings["login_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, timings["socket_attempts"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, timings["login_attempts"]!!.jsonPrimitive.double, 0.0)
            // Reconnect chain: per-chain counters reset, openedAt/loginChainAt do not leak,
            // app_wait_ms stays a first-chain-only fact.
            clock.nowMs = 3_100
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 3_200
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 3_300
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"chain-two"}""")
            clock.nowMs = 3_400
            sink.signalingFrame("""{"id":"chain-two","result":{"message":"logged in"}}""", false)
            clock.nowMs = 3_600
            sink.ready()
            val reconnected = timingsLast(capture)
            assertFalse(reconnected.containsKey("app_wait_ms"))
            assertEquals(500.0, reconnected["connect_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(100.0, reconnected["socket_connect_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(100.0, reconnected["login_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(200.0, reconnected["login_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, reconnected["socket_attempts"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, reconnected["login_attempts"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }
    @Test fun `socket retry before ready stays one chain measured from the first attempt`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("same-chain-retry"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 1_400
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 1_500
            sink.socketClosed(1011, "connection reset", failed = true)
            clock.nowMs = 2_000
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 2_400
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 2_500
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"retry"}""")
            clock.nowMs = 2_600
            sink.signalingFrame("""{"id":"retry","result":{"message":"logged in"}}""", false)
            clock.nowMs = 4_000
            sink.ready()
            val timings = timingsLast(capture)
            assertEquals(2.0, timings["socket_attempts"]!!.jsonPrimitive.double, 0.0)
            assertEquals(3_000.0, timings["connect_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1_400.0, timings["socket_connect_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, timings["login_attempts"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }
    @Test fun `login retry before ready keeps chain start and cumulative attempts`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("login-retry"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 1_100
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 1_200
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"first"}""")
            sink.rpcFailed("first", 401, "bad token", willRetry = true)
            clock.nowMs = 2_000
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"second"}""")
            clock.nowMs = 2_100
            sink.signalingFrame("""{"id":"second","result":{"message":"logged in"}}""", false)
            clock.nowMs = 2_500
            sink.ready()
            val timings = timingsLast(capture)
            assertEquals(2.0, timings["login_attempts"]!!.jsonPrimitive.double, 0.0)
            assertEquals(900.0, timings["login_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(400.0, timings["login_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1_500.0, timings["connect_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

    @Test fun `connect chain starts at connect call before first socket attempt`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("connect-chain"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!
            clock.nowMs = 1_200
            sink.connectChainStarted()
            clock.nowMs = 1_700
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 2_000
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 2_100
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"cc"}""")
            clock.nowMs = 2_200
            sink.signalingFrame("""{"id":"cc","result":{"message":"logged in"}}""", false)
            clock.nowMs = 2_800
            sink.ready()
            val timings = timingsLast(capture)
            assertEquals(1_200.0, timings["app_wait_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1_600.0, timings["connect_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(300.0, timings["socket_connect_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(1.0, timings["socket_attempts"]!!.jsonPrimitive.double, 0.0)
            // After ready the chain is cleared: the next socketStarting begins a NEW chain.
            clock.nowMs = 3_000
            sink.socketStarting("wss://rtc.telnyx.com:14938")
            clock.nowMs = 3_400
            sink.socketOpened("wss://rtc.telnyx.com:14938")
            clock.nowMs = 3_500
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"cc2"}""")
            clock.nowMs = 3_600
            sink.signalingFrame("""{"id":"cc2","result":{"message":"logged in"}}""", false)
            clock.nowMs = 3_900
            sink.ready()
            val second = timingsLast(capture)
            assertFalse(second.containsKey("app_wait_ms"))
            assertEquals(1.0, second["socket_attempts"]!!.jsonPrimitive.double, 0.0)
            assertEquals(900.0, second["connect_to_ready_ms"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }

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
    @Test fun `repeated and delayed states do not invent transitions after hangup`() = runBlocking {
        val client = client("state-dedupe"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "state-call", false, false, true, options)
            sink.state(token, CallState.ACTIVE); sink.state(token, CallState.ACTIVE)
            sink.state(token, CallState.HELD); sink.state(token, CallState.HELD)
            sink.state(token, CallState.DONE()); sink.state(token, CallState.ACTIVE)
            sink.state(token, CallState.DONE()); sink.peerClosing(token, null)
            val states = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "call_state" }
            assertEquals(listOf("active", "held", "hangup"), states.map { it["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content })
        } finally { capture.shutdown() }
    }
    @Test fun `authentication success matches current login response independently of readiness`() = runBlocking {
        val client = client("auth-boundary"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            sink.authenticate("credential", "user", "secret")
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"current"}""")
            sink.signalingFrame("""{"id":"stale","result":{"message":"logged in"}}""", false)
            sink.signalingFrame("""{"id":"current","result":{"message":"logged in"}}""", true)
            assertFalse(params(capture.capturedFrames()).any { it["name"]!!.jsonPrimitive.content == "login_succeeded" })
            sink.signalingFrame("""{"id":"current","result":{"message":"logged in"}}""", false)
            sink.signalingFrame("""{"id":"current","result":{"message":"logged in"}}""", false)
            val beforeReady = params(capture.capturedFrames())
            assertEquals(1, beforeReady.count { it["name"]!!.jsonPrimitive.content == "login_succeeded" })
            assertFalse(beforeReady.any { it["name"]!!.jsonPrimitive.content == "client_ready" })
            sink.ready(); sink.ready()
            sink.authenticate("credential", "user", "secret"); sink.ready()
            // Readiness requires a matching successful login: the duplicate ready
            // and the ready after an unsuccessed re-authenticate must not emit.
            val events = params(capture.capturedFrames())
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "login_succeeded" })
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "client_ready" })
            sink.signalingWriteStarted("""{"method":"telnyx_rtc.login","id":"relogin"}""")
            sink.signalingFrame("""{"id":"relogin","result":{"message":"logged in"}}""", false)
            sink.ready()
            val refreshed = params(capture.capturedFrames())
            assertEquals(2, refreshed.count { it["name"]!!.jsonPrimitive.content == "client_ready" })
            val latestTimings = refreshed.last { it["name"]!!.jsonPrimitive.content == "session_timings" }["payload"]!!.jsonObject
            assertTrue(latestTimings["login_ms"]!!.jsonPrimitive.double >= 0.0)
        } finally { capture.shutdown() }
    }
    @Test fun `push observations preserve origin and original receipt time without active call inference`() = runBlocking {
        val client = client("push-facts"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "unrelated-call", false, false, true, options)
            val callId = java.util.UUID.randomUUID(); val originId = java.util.UUID.randomUUID()
            sink.pushEvent(CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                originId = originId, observedAtEpochMs = 1000, observedAtMonotonicMs = 27))
            sink.pushEvent(CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                originId = originId, observedAtEpochMs = 2000, observedAtMonotonicMs = 1027))
            val answer = CallReportV2PushEvent(CallReportV2PushEvent.Fact.NOTIFICATION_ACTION, callId,
                action = CallReportV2PushEvent.Action.ANSWER)
            sink.pushEvent(answer)
            sink.pushEvent(answer.copy(observedAtEpochMs = answer.observedAtEpochMs + 1))
            sink.pushEvent(answer.copy(action = CallReportV2PushEvent.Action.REJECT,
                originId = CallReportV2PushEvent.originFor(answer.fact, callId, CallReportV2PushEvent.Action.REJECT)))
            val event = params(capture.capturedFrames()).first { it["name"]!!.jsonPrimitive.content == "logs" }
            val details = event["payload"]!!.jsonObject["details"]!!.jsonObject
            assertEquals("push_received", details["fact"]!!.jsonPrimitive.content)
            assertEquals(callId.toString(), details["call_id"]!!.jsonPrimitive.content)
            assertEquals(originId.toString(), details["origin_id"]!!.jsonPrimitive.content)
            assertEquals(1000, details["observed_at_epoch_ms"]!!.jsonPrimitive.int)
            assertFalse(event["ids"].toString().contains("unrelated-call"))
            val logs = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "logs" }
            assertEquals(3, logs.size) // One receipt, one answer, one distinct reject.
            assertEquals("android_elapsed_realtime", details["clock_domain"]!!.jsonPrimitive.content)
            assertEquals(answer.originId, CallReportV2PushEvent(answer.fact, callId, answer.action).originId)
        } finally { capture.shutdown() }
    }
    @Test fun `push attach failures report error severity while milestones stay info`() = runBlocking {
        val client = client("push-severity"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            val callId = java.util.UUID.randomUUID()
            sink.pushEvent(CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                observedAtEpochMs = 1_000, observedAtMonotonicMs = 10))
            sink.pushEvent(CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_ATTACH_STARTED, callId,
                originId = CallReportV2PushEvent.originFor(CallReportV2PushEvent.Fact.PUSH_ATTACH_STARTED, callId),
                observedAtEpochMs = 1_100, observedAtMonotonicMs = 20))
            sink.pushEvent(CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED, callId,
                reason = CallReportV2PushEvent.Reason.INVITE_TIMEOUT,
                originId = CallReportV2PushEvent.originFor(CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED, callId),
                observedAtEpochMs = 1_200, observedAtMonotonicMs = 30))
            sink.pushEvent(CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED, callId,
                reason = CallReportV2PushEvent.Reason.SERVER_ERROR,
                originId = CallReportV2PushEvent.originFor(CallReportV2PushEvent.Fact.PUSH_ATTACH_FAILED, callId,
                    deliveryId = "rpc-1"),
                observedAtEpochMs = 1_300, observedAtMonotonicMs = 40))
            val logs = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "logs" }
            assertEquals(4, logs.size)
            assertEquals(listOf("info", "info", "error", "error"), logs.map {
                it["payload"]!!.jsonObject["level"]!!.jsonPrimitive.content
            })
            logs.take(2).forEach { event ->
                val details = event["payload"]!!.jsonObject["details"]!!.jsonObject
                assertEquals("connection", event["payload"]!!.jsonObject["category"]!!.jsonPrimitive.content)
                assertFalse(details.containsKey("message_kind"))
            }
        } finally { capture.shutdown() }
    }
    @Test fun `cleanup push receipts carry message kind without colliding with incoming receipts`() = runBlocking {
        val client = client("push-cleanup"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            val callId = java.util.UUID.randomUUID()
            val incoming = CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                originId = CallReportV2PushEvent.originFor(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                    deliveryId = "msg-1"),
                observedAtEpochMs = 2_000, observedAtMonotonicMs = 50)
            val cleanup = CallReportV2PushEvent(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                messageKind = CallReportV2PushEvent.MessageKind.MISSED_CALL,
                originId = CallReportV2PushEvent.originFor(CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
                    deliveryId = "msg-1",
                    messageKind = CallReportV2PushEvent.MessageKind.MISSED_CALL),
                observedAtEpochMs = 2_500, observedAtMonotonicMs = 60)
            assertNotEquals(incoming.originId, cleanup.originId)
            sink.pushEvent(incoming)
            sink.pushEvent(cleanup)
            val logs = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "logs" }
            assertEquals(2, logs.size)
            val cleanupDetails = logs.last()["payload"]!!.jsonObject["details"]!!.jsonObject
            assertEquals("missed_call", cleanupDetails["message_kind"]!!.jsonPrimitive.content)
            assertEquals("push_received", cleanupDetails["fact"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }
    @Test fun `errors and warnings retain exact call scope without active-call inference`() = runBlocking {
        val client = client("errors-instance"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            val first = V2CaptureToken(); val second = V2CaptureToken()
            sink.started(first, "first", false, false, true, options)
            sink.started(second, "second", true, false, true, options)
            sink.error(com.telnyx.webrtc.sdk.model.TelnyxErrorRegistry.get(40001)!!, first)
            sink.error(com.telnyx.webrtc.sdk.model.TelnyxErrorRegistry.get(46001)!!, null)
            sink.warning(com.telnyx.webrtc.sdk.model.TelnyxWarningRegistry.get(33001)!!, second)
            sink.warning(com.telnyx.webrtc.sdk.model.TelnyxWarningRegistry.get(34001)!!, null)
            val events = params(capture.capturedFrames())
            val errors = events.filter { it["name"]!!.jsonPrimitive.content == "error" }
            assertEquals(2, errors.size)
            assertEquals("first", errors[0]["ids"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
            assertEquals("call", errors[0]["payload"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
            assertEquals("40001", errors[0]["payload"]!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
            assertFalse(errors[1]["ids"]!!.jsonObject.containsKey("call_id"))
            assertEquals("login", errors[1]["payload"]!!.jsonObject["stage"]!!.jsonPrimitive.content)
            val warning = events.single { it["name"]!!.jsonPrimitive.content == "call_warning" }
            assertEquals("second", warning["ids"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
            val logs = events.single { it["name"]!!.jsonPrimitive.content == "logs" }
            assertFalse(logs["ids"]!!.jsonObject.containsKey("call_id"))
        } finally { capture.shutdown() }
    }
    @Test fun `replaced call tokens cannot report errors into a replacement lease`() = runBlocking {
        val client = client("replacement-instance"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val old = V2CaptureToken(); val current = V2CaptureToken()
            sink.started(old, "same", false, false, true, options)
            sink.started(current, "same", false, true, true, options)
            sink.error(com.telnyx.webrtc.sdk.model.TelnyxErrorRegistry.get(40001)!!, old)
            sink.warning(com.telnyx.webrtc.sdk.model.TelnyxWarningRegistry.get(33001)!!, old)
            sink.error(com.telnyx.webrtc.sdk.model.TelnyxErrorRegistry.get(40002)!!, current)
            val errors = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "error" }
            assertEquals(1, errors.size)
            assertEquals("40002", errors.single()["payload"]!!.jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }
    @Test fun `network callbacks deduplicate without inventing radio estimates`() = runBlocking {
        val client = client("network-instance"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            sink.network("WIFI", true); sink.network("WIFI", true)
            sink.network("WIFI", false); sink.network("CELLULAR", true)
            val events = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "network_changed" }
            assertEquals(3, events.size)
            val payloads = events.map { it["payload"]!!.jsonObject }
            assertTrue(payloads.first()["initial"]!!.jsonPrimitive.boolean)
            assertFalse(payloads[1]["initial"]!!.jsonPrimitive.boolean)
            assertEquals("wifi", payloads[1]["network_type"]!!.jsonPrimitive.content)
            assertFalse(payloads[1]["online"]!!.jsonPrimitive.boolean)
            assertTrue(payloads.none { it.containsKey("effective_type") || it.containsKey("downlink_mbps") })
        } finally { capture.shutdown() }
    }
    @Test fun `sdk creation and observed app lifecycle retain instance and sequence`() = runBlocking {
        val client = client("lifecycle-instance")
        val capture = AndroidV2Capture(client)
        try {
            client.callReportV2!!.appState(false)
            client.callReportV2!!.appState(true)
            val events = params(capture.capturedFrames())
            assertEquals(listOf("sdk_created", "app_state_changed", "app_state_changed"),
                events.map { it["name"]!!.jsonPrimitive.content })
            assertEquals("foreground", events[1]["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals("background", events[2]["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertTrue(events.all { it["ids"]!!.jsonObject["sdk_instance_id"]!!.jsonPrimitive.content == "lifecycle-instance" })
        } finally { capture.shutdown() }
    }
    @Test fun `ICE media callbacks are scoped to current call lease`() = runBlocking {
        val client = client("ice-instance"); val capture = AndroidV2Capture(client)
        try {
            val old = V2CaptureToken(); val current = V2CaptureToken()
            client.callReportV2!!.started(old, "call", true, false, false, options)
            client.callReportV2!!.started(current, "call", true, true, false, options)
            client.callReportV2!!.iceConnectionState(old, "FAILED")
            client.callReportV2!!.iceConnectionState(current, "CONNECTED")
            client.callReportV2!!.iceConnectionState(current, "invented")
            val media = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "call_media_changed" }
            assertEquals(1, media.size)
            assertEquals("connected", media.single()["payload"]!!.jsonObject["ice_state"]!!.jsonPrimitive.content)
            assertEquals("call", media.single()["ids"]!!.jsonObject["call_id"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }
    @Test fun `login failures require the actual outbound login RPC and preserve retry fact`() = runBlocking {
        val client = client("login-instance"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            sink.authenticate("sip_credentials", "synthetic", "must-never-be-captured")
            val login = """{"jsonrpc":"2.0","id":"login-id","method":"login","params":{"passwd":"must-never-be-captured"}}"""
            sink.signalingWriteStarted(login)
            // A fast error callback may arrive before the successful send call returns.
            sink.rpcFailed("login-id", -32001, "Login Incorrect", true)
            sink.signalingFrame(login, true)
            sink.rpcFailed("other-id", -32001, "not this request", false)
            sink.rpcFailed("login-id", -32001, "Login Incorrect", true)
            sink.rpcFailed("login-id", -32001, "duplicate", true)
            val frames = capture.capturedFrames()
            val failures = params(frames).filter { it["name"]!!.jsonPrimitive.content == "login_failed" }
            assertEquals(1, failures.size)
            assertTrue(failures.single()["payload"]!!.jsonObject["will_retry"]!!.jsonPrimitive.boolean)
            assertFalse(frames.joinToString().contains("must-never-be-captured"))
        } finally { capture.shutdown() }
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
            val state = events.last { it["name"]!!.jsonPrimitive.content == "call_state" }
            assertEquals("active", state["payload"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        } finally { capture.shutdown() }
    }
    private fun mediaReport(pairId: String = "pair-a", dtls: String = "connected", localAddress: String = "10.0.0.20"): RTCStatsReport {
        fun row(id: String, type: String, members: Map<String, Any>): RTCStats = mockk<RTCStats>().also {
            every { it.id } returns id; every { it.type } returns type; every { it.members } returns members
        }
        val rows = listOf(
            row("transport", "transport", mapOf("selectedCandidatePairId" to pairId, "dtlsState" to dtls)),
            row(pairId, "candidate-pair", mapOf("localCandidateId" to "local", "remoteCandidateId" to "remote")),
            row("local", "local-candidate", mapOf("candidateType" to "relay", "protocol" to "udp", "relayProtocol" to "udp",
                "networkType" to "ethernet", "foundation" to "3412957284", "priority" to 41885695,
                "address" to localAddress, "port" to 57816, "url" to "turn:user:secret@turn.telnyx.com:3478?transport=udp")),
            row("remote", "remote-candidate", mapOf("candidateType" to "relay", "protocol" to "udp",
                "foundation" to "7f2a1b", "priority" to 41885694, "address" to "203.0.113.9", "port" to 17314)),
            row("codec", "codec", mapOf("mimeType" to "audio/opus", "clockRate" to 48000, "channels" to 2, "payloadType" to 111)),
            row("audio", "inbound-rtp", mapOf("kind" to "audio", "codecId" to "codec", "packetsReceived" to 5)))
        return mockk<RTCStatsReport>().also { every { it.statsMap } returns rows.associateBy { row -> row.id } }
    }
    @Test fun `canonical media keeps codec dtls and same-category pair reselection with complete selected candidates`() = runBlocking {
        val client = client("media"); val capture = AndroidV2Capture(client)
        try {
            val token = V2CaptureToken(); val sink = client.callReportV2!!
            sink.started(token, "call", false, false, true, options)
            sink.stats(token, mediaReport()); sink.stats(token, mediaReport("pair-b", localAddress = "10.0.0.21")); sink.stats(token, mediaReport("pair-b", localAddress = "10.0.0.21"))
            val frames = capture.capturedFrames()
            val media = params(frames).filter { it["name"]!!.jsonPrimitive.content == "call_media_changed" }
            assertEquals(2, media.size)
            val first = media.first()["payload"]!!.jsonObject
            assertEquals("audio/opus", first["codec_in"]!!.jsonObject["mime_type"]!!.jsonPrimitive.content)
            assertEquals("connected", first["dtls_state"]!!.jsonPrimitive.content)
            val firstLocal = first["local_candidate"]!!.jsonObject
            assertEquals("relay", firstLocal["candidate_type"]!!.jsonPrimitive.content)
            assertEquals("udp", firstLocal["protocol"]!!.jsonPrimitive.content)
            assertEquals("udp", firstLocal["relay_protocol"]!!.jsonPrimitive.content)
            assertEquals("ethernet", firstLocal["network_type"]!!.jsonPrimitive.content)
            assertEquals("3412957284", firstLocal["foundation"]!!.jsonPrimitive.content)
            assertEquals(41885695.0, firstLocal["priority"]!!.jsonPrimitive.double, 0.0)
            assertEquals("10.0.0.20", firstLocal["address"]!!.jsonPrimitive.content)
            assertEquals(57816.0, firstLocal["port"]!!.jsonPrimitive.double, 0.0)
            assertEquals("turn:turn.telnyx.com:3478?transport=udp", firstLocal["url"]!!.jsonPrimitive.content)
            val last = media.last()["payload"]!!.jsonObject
            assertTrue(last["changed"]!!.jsonArray.any { it.jsonPrimitive.content == "candidate_pair" })
            assertEquals("10.0.0.21", last["local_candidate"]!!.jsonObject["address"]!!.jsonPrimitive.content)
            assertEquals("10.0.0.20", last["previous_local_candidate"]!!.jsonObject["address"]!!.jsonPrimitive.content)
            assertEquals("203.0.113.9", last["remote_candidate"]!!.jsonObject["address"]!!.jsonPrimitive.content)
            assertFalse(frames.joinToString().contains("user:secret"))
            assertFalse(frames.joinToString().contains("ice-ufrag"))
        } finally { capture.shutdown() }
    }
    @Test fun `timing completes once in either active dtls ordering and rejects false completion`() = runBlocking {
        val client = client("timings"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            repeat(3) { index ->
                val token = V2CaptureToken(); sink.started(token, "call-$index", true, false, true, options)
                if (index == 0) sink.stats(token, mediaReport())
                sink.state(token, CallState.ACTIVE)
                if (index == 1) sink.stats(token, mediaReport())
                sink.timing(token, "sdp_sent_ms"); sink.state(token, CallState.DONE()); sink.peerClosing(token, null)
            }
            val timings = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "call_timings" }
            assertEquals(3, timings.size)
            assertEquals(listOf(true, true, false), timings.map { it["payload"]!!.jsonObject["complete"]!!.jsonPrimitive.boolean })
        } finally { capture.shutdown() }
    }
    @Test fun `end reasons follow explicit initiator and call direction not SIP guesswork`() = runBlocking {
        val client = client("terminal"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            val cases = listOf(Triple(true, true, false), Triple(false, true, false), Triple(false, true, true), Triple(true, false, true), Triple(false, false, false))
            cases.forEachIndexed { index, (inbound, local, answered) ->
                val token = V2CaptureToken(); sink.started(token, "call-$index", inbound, false, true, options)
                if (answered) sink.state(token, CallState.ACTIVE)
                sink.termination(token, local); sink.state(token, CallState.DONE()); sink.peerClosing(token, null)
                sink.termination(token, !local); sink.peerClosing(token, null)
            }
            val ends = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "call_ended" }
            assertEquals(listOf("rejected", "cancelled", "local_hangup", "remote_hangup", "remote_hangup"),
                ends.map { it["payload"]!!.jsonObject["end_reason"]!!.jsonPrimitive.content })
        } finally { capture.shutdown() }
    }
    @Test fun `candidate signaled is optional local eligibility not accepted-write transition`() = runBlocking {
        val client = client("candidate-eligibility"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "candidate-call", false, false, true, options)
            val candidate = "candidate:1 1 UDP 123 10.0.0.9 10000 typ host"
            sink.candidate(token, candidate, false, "first", signaled = true)
            // Re-observation/description cannot turn a candidate into another occurrence.
            sink.candidate(token, candidate, false, "first", signaled = false)
            sink.description(token, "a=ice-ufrag:first\r\na=$candidate", false)
            sink.candidate(token, "candidate:2 1 UDP 123 10.0.0.9 10001 typ host", false, "first")
            sink.candidate(token, "candidate:3 1 UDP 123 10.0.0.9 10002 typ host", false, "first", signaled = false)
            sink.candidate(token, candidate, true, "remote", signaled = true)
            val events = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "ice_candidate" }
            assertEquals(4, events.size)
            val payloads = events.map { it["payload"]!!.jsonObject }
            assertEquals(true, payloads[0]["signaled"]!!.jsonPrimitive.boolean)
            assertTrue(payloads[1]["signaled"] == null || payloads[1]["signaled"] is JsonNull)
            assertEquals(false, payloads[2]["signaled"]!!.jsonPrimitive.boolean)
            assertTrue(payloads[3]["signaled"] == null || payloads[3]["signaled"] is JsonNull)
        } finally { capture.shutdown() }
    }
    @Test fun `candidate restart before description has one new generation and stores fields as sent`() = runBlocking {
        val client = client("candidates"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "call", false, false, true, options)
            val candidate = "candidate:1 1 UDP 123 10.0.0.9 10000 typ host"
            sink.candidate(token, candidate, false, "first")
            sink.description(token, "a=ice-ufrag:first\r\na=$candidate", false)
            sink.candidate(token, "$candidate ufrag second", false, "first")
            sink.description(token, "a=ice-ufrag:second\r\na=$candidate", false)
            sink.candidate(token, candidate, true, "remote")
            val frames = capture.capturedFrames()
            val candidates = params(frames).filter { it["name"]!!.jsonPrimitive.content == "ice_candidate" }
            assertEquals(listOf(1.0, 2.0, 1.0), candidates.map { it["payload"]!!.jsonObject["ice_generation"]!!.jsonPrimitive.double })
            assertEquals(listOf("local", "local", "remote"), candidates.map { it["payload"]!!.jsonObject["side"]!!.jsonPrimitive.content })
            val payload = candidates[0]["payload"]!!.jsonObject
            assertEquals("1", payload["foundation"]!!.jsonPrimitive.content)
            assertEquals(123.0, payload["priority"]!!.jsonPrimitive.double, 0.0)
            assertEquals("10.0.0.9", payload["address"]!!.jsonPrimitive.content)
            assertEquals(10000.0, payload["port"]!!.jsonPrimitive.double, 0.0)
            assertFalse(frames.joinToString().contains("ice-ufrag"))
            assertFalse(frames.joinToString().contains("a=candidate:"))
        } finally { capture.shutdown() }
    }
    @Test fun `candidate since_gathering uses the observed gathering start and stays unknown otherwise`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("gathering"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "gathering-call", false, false, true, options)
            clock.nowMs = 1_100
            sink.candidate(token, "candidate:1 1 udp 1 10.0.0.1 5000 typ host", false, "a")
            clock.nowMs = 1_200
            sink.peerState(token, "ice_gathering_state", "GATHERING")
            clock.nowMs = 1_500
            sink.candidate(token, "candidate:2 1 udp 2 10.0.0.2 5001 typ host", false, "a")
            clock.nowMs = 1_700
            sink.description(token, "a=ice-ufrag:b\r\na=candidate:3 1 udp 3 10.0.0.3 5002 typ host", true)
            clock.nowMs = 1_900
            sink.candidate(token, "candidate:4 1 udp 4 10.0.0.4 5003 typ host", true, "b")
            val payload = params(capture.capturedFrames())
                .filter { it["name"]!!.jsonPrimitive.content == "ice_candidate" }
                .map { it["payload"]!!.jsonObject }
            assertNull(payload[0]["since_gathering_started_ms"])
            assertEquals(300.0, payload[1]["since_gathering_started_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(0.0, payload[2]["since_gathering_started_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(200.0, payload[3]["since_gathering_started_ms"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }
    @Test fun `remote candidate after ufrag change has no stale description anchor until the new description`() = runBlocking {
        val clock = SteppingClock(1_000)
        val client = client("remote-anchor"); val capture = AndroidV2Capture(client, clock = clock)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "remote-anchor-call", false, false, true, options)
            clock.nowMs = 1_100
            sink.description(token, "a=ice-ufrag:one\r\na=candidate:1 1 udp 1 10.0.0.1 5000 typ host", true)
            clock.nowMs = 1_400
            sink.candidate(token, "candidate:1 1 udp 1 10.0.0.1 5000 typ host", true, "one")
            clock.nowMs = 1_700
            sink.candidate(token, "candidate:2 1 udp 2 10.0.0.2 5001 typ host", true, "two")
            clock.nowMs = 2_100
            sink.description(token, "a=ice-ufrag:three\r\na=candidate:3 1 udp 3 10.0.0.3 5002 typ host", true)
            clock.nowMs = 2_400
            sink.candidate(token, "candidate:4 1 udp 4 10.0.0.4 5003 typ host", true, "three")
            val payload = params(capture.capturedFrames())
                .filter { it["name"]!!.jsonPrimitive.content == "ice_candidate" }
                .map { it["payload"]!!.jsonObject }
            assertEquals(listOf(1.0, 2.0, 3.0, 3.0), payload.map { it["ice_generation"]!!.jsonPrimitive.double })
            assertEquals(0.0, payload[0]["since_gathering_started_ms"]!!.jsonPrimitive.double, 0.0)
            assertNull(payload[1]["since_gathering_started_ms"])
            assertEquals(0.0, payload[2]["since_gathering_started_ms"]!!.jsonPrimitive.double, 0.0)
            assertEquals(300.0, payload[3]["since_gathering_started_ms"]!!.jsonPrimitive.double, 0.0)
        } finally { capture.shutdown() }
    }
    @Test fun `ice payloads carry json numbers while metrics counters keep integer tokens`() = runBlocking {
        val client = client("tokens"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val token = V2CaptureToken()
            sink.started(token, "tokens-call", false, false, true, options)
            sink.candidate(token, "candidate:3412957284 1 udp 41885695 198.51.100.112 57816 typ relay", false, "first")
            sink.signaling(1, "voice-1", "session-1")
            sink.stats(token, mediaReport())
            val frames = capture.capturedFrames()
            val ice = params(frames).first { it["name"]!!.jsonPrimitive.content == "ice_candidate" }["payload"]!!.jsonObject
            assertFalse(ice["priority"]!!.jsonPrimitive.isString)
            assertEquals(41885695.0, ice["priority"]!!.jsonPrimitive.double, 0.0)
            assertEquals(57816.0, ice["port"]!!.jsonPrimitive.double, 0.0)
            assertFalse(ice["priority"]!!.jsonPrimitive.content.contains("e-"))
            val counters = setOf("interval_ms", "in_packets", "in_bytes", "in_lost", "in_discarded", "out_packets", "out_bytes")
            val metrics = params(frames).filter { it["name"]!!.jsonPrimitive.content == "call_metrics" }
            assertTrue(metrics.isNotEmpty())
            val integerToken = Regex("-?[0-9]+")
            metrics.forEach { frame ->
                val payload = frame["payload"]!!.jsonObject
                payload.keys.filter { it in counters }.forEach { key ->
                    val value = payload[key]!!.jsonPrimitive
                    assertTrue(value.isString.not())
                    assertTrue("payload=$payload", integerToken.matches(value.content))
                }
            }
        } finally { capture.shutdown() }
    }
    @Test fun `socket end is exclusively failed before open and closed after open`() = runBlocking {
        val client = client("socket"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            sink.socketStarting("wss://example.test"); sink.socketFailed("IOException", true); sink.socketClosed(null, null, true)
            sink.socketStarting("wss://example.test"); sink.socketOpened("wss://example.test")
            sink.socketFailed("IOException", false); sink.socketClosed(null, null, true)
            val events = params(capture.capturedFrames())
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "socket_failed" })
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "socket_closed" })
        } finally { capture.shutdown() }
    }
    @Test fun `device selection and inventory keep session scope with concurrent calls`() = runBlocking {
        val client = client("devices"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            sink.started(V2CaptureToken(), "a", false, false, true, options)
            sink.started(V2CaptureToken(), "b", true, false, true, options)
            val output = V2AudioDevice("android:1:output", "speaker", false)
            sink.devices(listOf(output)); sink.devices(listOf(output)); sink.outputDevice(output); sink.outputDevice(output)
            sink.devices(emptyList())
            val events = params(capture.capturedFrames())
            val inventory = events.filter { it["name"]!!.jsonPrimitive.content == "device_list_changed" }
            assertEquals(2, inventory.size)
            assertEquals(1, inventory.last()["payload"]!!.jsonObject["removed"]!!.jsonArray.size)
            assertTrue(inventory.all { !it["ids"]!!.jsonObject.containsKey("call_id") })
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "output_device_changed" })
        } finally { capture.shutdown() }
    }
    @Test fun `gateway outcomes match only existing checks and close once`() = runBlocking {
        val client = client("gateway"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!
            sink.gatewayCheckResult("unsolicited", "REGED")
            sink.gatewayCheckStarted("check"); sink.gatewayCheckResult("check", "REGED"); sink.gatewayCheckFailed("check", "late", true)
            sink.gatewayCheckStarted("failed"); sink.gatewayCheckFailed("failed", "write rejected", false)
            val events = params(capture.capturedFrames())
            assertEquals(2, events.count { it["name"]!!.jsonPrimitive.content == "gateway_check_started" })
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "gateway_check_succeeded" })
            assertEquals(1, events.count { it["name"]!!.jsonPrimitive.content == "gateway_check_failed" })
        } finally { capture.shutdown() }
    }
    @Test fun `construction records actual finish or safely detaches on failure`() = runBlocking {
        val client = client("construction"); val capture = AndroidV2Capture(client, constructing = true)
        try {
            client.callReportV2!!.creationFinished(12)
            assertEquals(listOf("sdk_creation_started", "sdk_created"), params(capture.capturedFrames()).map { it["name"]!!.jsonPrimitive.content })
            val sink = client.callReportV2!!
            sink.creationFailed("IllegalStateException")
            kotlinx.coroutines.withTimeout(3000) { while (sink.isActive()) kotlinx.coroutines.delay(10) }
            assertNull(client.callReportV2)
        } finally { capture.shutdown() }
    }

    @Test fun `provider identities bind only to explicit live token and never enter metrics`() = runBlocking {
        val client = client("ids"); val capture = AndroidV2Capture(client)
        try {
            val sink = client.callReportV2!!; val first = V2CaptureToken(); val second = V2CaptureToken()
            sink.started(first, "first", false, false, true, options); sink.started(second, "second", false, false, true, options)
            sink.providerIds(first, "confirmed-leg", "confirmed-session")
            sink.state(first, CallState.ACTIVE); sink.state(second, CallState.ACTIVE)
            val states = params(capture.capturedFrames()).filter { it["name"]!!.jsonPrimitive.content == "call_state" }
            assertEquals("confirmed-leg", states.first()["ids"]!!.jsonObject["telnyx_leg_id"]!!.jsonPrimitive.content)
            assertFalse(states.last()["ids"]!!.jsonObject.containsKey("telnyx_leg_id"))
        } finally { capture.shutdown() }
    }
    @Test fun `nominated succeeded pair supports native reports lacking transport selection`() {
        val rows = mediaReport().statsMap.toMutableMap()
        rows.remove("transport")
        val pair = mockk<RTCStats>()
        every { pair.id } returns "pair-a"; every { pair.type } returns "candidate-pair"
        every { pair.members } returns mapOf("nominated" to true, "state" to "succeeded", "localCandidateId" to "local", "remoteCandidateId" to "remote")
        rows["pair-a"] = pair
        val report = mockk<RTCStatsReport>(); every { report.statsMap } returns rows
        val media = V2NativeMedia.snapshot(report)
        assertNotNull(media.local_candidate); assertNotNull(media.remote_candidate)
    }

}
