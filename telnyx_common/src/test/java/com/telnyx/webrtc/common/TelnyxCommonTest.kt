package com.telnyx.webrtc.common

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.telnyx.webrtc.sdk.model.SocketConnectionMetrics
import com.telnyx.webrtc.sdk.model.SocketConnectionQuality
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TelnyxCommonTest {
    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()
    private val telnyxCommon = TelnyxCommon.getInstance()
    private lateinit var context: Context

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        every { context.applicationContext } returns context
        telnyxCommon.resetTelnyxClient()
    }

    @After
    fun tearDown() {
        telnyxCommon.resetTelnyxClient()
        Dispatchers.resetMain()
    }

    @Test
    fun coldPushRejectDrainsOnlyMatchingFactsToDedicatedOwnerOnce() {
        val callId = java.util.UUID.randomUUID()
        val unrelatedCallId = java.util.UUID.randomUUID()
        val owner = mockk<com.telnyx.webrtc.sdk.TelnyxClient>(relaxed = true)
        val otherOwner = mockk<com.telnyx.webrtc.sdk.TelnyxClient>(relaxed = true)
        val receipt = com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent(
            com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_RECEIVED, callId,
            observedAtEpochMs = 1000, observedAtMonotonicMs = 10)
        val reject = com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent(
            com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.NOTIFICATION_ACTION, callId,
            action = com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Action.REJECT,
            observedAtEpochMs = 1100, observedAtMonotonicMs = 110)
        val unrelated = receipt.copy(callId = unrelatedCallId, originId = java.util.UUID.randomUUID())
        telnyxCommon.recordPushTelemetry(receipt)
        telnyxCommon.recordPushTelemetry(unrelated)
        telnyxCommon.recordPushTelemetry(reject)
        telnyxCommon.recordPushTelemetry(reject.copy(observedAtEpochMs = 1200))
        assertNull(telnyxCommon.telnyxClient) // No Activity or normal-client construction.
        telnyxCommon.drainPushTelemetry(callId, owner)
        telnyxCommon.drainPushTelemetry(callId, owner)
        io.mockk.verify(exactly = 1) { owner.recordCallReportV2PushEvent(receipt) }
        io.mockk.verify(exactly = 1) { owner.recordCallReportV2PushEvent(reject) }
        io.mockk.verify(exactly = 0) { owner.recordCallReportV2PushEvent(unrelated) }
        telnyxCommon.drainPushTelemetry(unrelatedCallId, otherOwner)
        io.mockk.verify(exactly = 1) { otherOwner.recordCallReportV2PushEvent(unrelated) }
    }

    @Test
    fun pendingPushQueueIsBoundedAndKeepsNewest32WithoutPayloads() {
        val owner = mockk<com.telnyx.webrtc.sdk.TelnyxClient>(relaxed = true)
        val events = (0 until 35).map {
            com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent(
                com.telnyx.webrtc.sdk.observability.CallReportV2PushEvent.Fact.PUSH_RECEIVED,
                java.util.UUID.randomUUID(), observedAtEpochMs = it.toLong(), observedAtMonotonicMs = it.toLong())
        }
        events.forEach(telnyxCommon::recordPushTelemetry)
        events.forEach { telnyxCommon.drainPushTelemetry(it.callId, owner) }
        events.take(3).forEach { event -> io.mockk.verify(exactly = 0) { owner.recordCallReportV2PushEvent(event) } }
        events.drop(3).forEach { event -> io.mockk.verify(exactly = 1) { owner.recordCallReportV2PushEvent(event) } }
    }

    @Test
    fun resetTelnyxClientCancelsPreviousConnectionMetricsCollector() = runTest(testDispatcher) {
        val firstClient = telnyxCommon.getTelnyxClient(context)
        val initialMetrics = SocketConnectionMetrics(quality = SocketConnectionQuality.GOOD)

        firstClient.pingPong(initialMetrics)
        runCurrent()

        assertEquals(initialMetrics, telnyxCommon.connectionMetrics.value)

        telnyxCommon.resetTelnyxClient()

        assertNull(telnyxCommon.connectionMetrics.value)

        firstClient.pingPong(SocketConnectionMetrics(quality = SocketConnectionQuality.POOR))
        runCurrent()

        assertNull(telnyxCommon.connectionMetrics.value)
    }

    @Test
    fun commonsClientCarriesAndroidCommonsSdkIdentity() {
        // The Commons wrapper must construct a TelnyxClient whose
        // sdkIdentity matches the Commons artifact (name = "webrtc-android-commons",
        // version = BuildConfig.COMMONS_VERSION) so every outbound verto
        // message emitted via the wrapper carries the Commons identity on
        // the wire.
        val client = telnyxCommon.getTelnyxClient(context)
        assertEquals("webrtc-android-commons", client.sdkIdentity.name)
        assertEquals(BuildConfig.COMMONS_VERSION, client.sdkIdentity.version)
        // The companion-level identity constant is the single authoritative
        // source the wrapper uses; the client's identity must equal it.
        assertEquals(TelnyxCommon.commonsSdkIdentity, client.sdkIdentity)
        // And the Commons version itself is tied to the Commons module's
        // publishing/build metadata — it must be non-empty so callers can
        // rely on it as a wire-level identifier.
        assert(BuildConfig.COMMONS_VERSION.isNotBlank()) {
            "COMMONS_VERSION must not be blank — check telnyx_common/build.gradle"
        }
    }
}
