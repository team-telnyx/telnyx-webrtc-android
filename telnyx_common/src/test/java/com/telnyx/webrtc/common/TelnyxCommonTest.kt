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
}
