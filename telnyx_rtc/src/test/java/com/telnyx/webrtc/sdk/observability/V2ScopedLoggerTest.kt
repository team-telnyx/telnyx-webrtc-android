package com.telnyx.webrtc.sdk.observability

import com.telnyx.webrtc.sdk.model.LogLevel
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.TxLogger
import io.mockk.*
import org.junit.Assert.*
import org.junit.Test

class V2ScopedLoggerTest {
    @Test fun explicitOwnersCannotBroadcastAcrossClients() {
        val first = mockk<CallReportV2Ingress>(relaxed = true)
        val second = mockk<CallReportV2Ingress>(relaxed = true)
        V2ScopedLogger { first }.i(message = "first-only")
        V2ScopedLogger { second }.w(message = "second-only")
        verify(exactly = 1) { first.log("info", "first-only") }
        verify(exactly = 0) { first.log(any(), "second-only") }
        verify(exactly = 1) { second.log("warn", "second-only") }
        verify(exactly = 0) { second.log(any(), "first-only") }
    }

    @Test fun absentReportingPreservesLazyDebugFiltering() {
        Logger.init(LogLevel.NONE)
        var built = false
        V2ScopedLogger { null }.d(message = { built = true; "unused" })
        assertFalse(built)
    }

    @Test fun failedReportingDoesNotSuppressExistingApplicationLogger() {
        val sink = mockk<CallReportV2Ingress>(relaxed = true)
        every { sink.log(any(), any()) } throws IllegalStateException("reporter stopped")
        val appLogger = mockk<TxLogger>(relaxed = true)
        Logger.init(LogLevel.ALL, appLogger)
        try {
            V2ScopedLogger { sink }.e(message = "still-delivered")
            verify(exactly = 1) { appLogger.log(LogLevel.ERROR, null, "still-delivered", null) }
        } finally { Logger.init(LogLevel.NONE) }
    }
}
