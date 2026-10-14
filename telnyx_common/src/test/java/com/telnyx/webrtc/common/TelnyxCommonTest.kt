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
        // sdkIdentity matches the Commons artifact (name = "android_commons",
        // version = BuildConfig.COMMONS_VERSION) so every outbound verto
        // message emitted via the wrapper carries the Commons identity on
        // the wire.
        val client = telnyxCommon.getTelnyxClient(context)
        assertEquals("android_commons", client.sdkIdentity.name)
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
