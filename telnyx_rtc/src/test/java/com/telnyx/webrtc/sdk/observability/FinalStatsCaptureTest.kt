package com.telnyx.webrtc.sdk.observability

import org.junit.Test
import org.junit.Assert.*

class FinalStatsCaptureTest {
    @Test fun `captures final snapshot before native disposal exactly once`() {
        val order = mutableListOf<String>()
        var callback: ((String) -> Unit)? = null
        var timeout: (() -> Unit)? = null
        FinalStatsCapture<String> { action -> timeout = action; { order.add("cancel") } }
            .finish({ callback = it }, { order.add("capture:$it") }, { order.add("dispose") })
        callback!!("final")
        timeout!!()
        callback!!("late")
        assertEquals(listOf("cancel", "capture:final", "dispose"), order)
    }
    @Test fun `unresponsive peer times out and late result cannot access disposed peer`() {
        val order = mutableListOf<String>()
        var callback: ((String) -> Unit)? = null
        var timeout: (() -> Unit)? = null
        FinalStatsCapture<String> { action -> timeout = action; {} }
            .finish({ callback = it }, { order.add("capture:$it") }, { order.add("dispose") })
        timeout!!()
        callback!!("late")
        assertEquals(listOf("capture:null", "dispose"), order)
    }
    @Test fun `synchronous native failure still disposes exactly once`() {
        var disposed = 0
        var timeout: (() -> Unit)? = null
        FinalStatsCapture<String> { action -> timeout = action; {} }
            .finish({ throw IllegalStateException("native failure") }, { assertNull(it) }, { disposed++ })
        timeout!!()
        assertEquals(1, disposed)
    }
    @Test fun `capture failure cannot leak native resources`() {
        var disposed = false
        try {
            FinalStatsCapture<String> { {} }.finish({ it("final") }, { error("capture failure") }, { disposed = true })
        } catch (_: Exception) { }
        assertTrue(disposed)
    }
}
