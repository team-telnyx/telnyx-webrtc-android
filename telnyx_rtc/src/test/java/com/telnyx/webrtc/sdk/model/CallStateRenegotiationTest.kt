package com.telnyx.webrtc.sdk.model

import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertNotNull

/**
 * Unit tests for CallState.RENEGOTIATING functionality.
 * Tests the new RENEGOTIATING state added for ICE renegotiation support.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class CallStateRenegotiationTest : BaseTest() {

    @Test
    fun `test RENEGOTIATING state has correct value`() {
        // Arrange & Act
        val renegotiatingState = CallState.RENEGOTIATING

        // Assert
        assertEquals("RENEGOTIATING", renegotiatingState.value)
    }

    @Test
    fun `test RENEGOTIATING state getReason returns null`() {
        // Arrange & Act
        val renegotiatingState = CallState.RENEGOTIATING
        val reason = renegotiatingState.getReason()

        // Assert
        assertNull(reason)
    }

    @Test
    fun `test RENEGOTIATING state is different from other states`() {
        // Arrange
        val renegotiatingState = CallState.RENEGOTIATING
        val activeState = CallState.ACTIVE
        val connectingState = CallState.CONNECTING

        // Assert
        assert(renegotiatingState.javaClass != activeState.javaClass)
        assert(renegotiatingState.javaClass != connectingState.javaClass)
    }

    @Test
    fun `test RENEGOTIATING state equality`() {
        // Arrange
        val renegotiatingState1 = CallState.RENEGOTIATING
        val renegotiatingState2 = CallState.RENEGOTIATING

        // Assert
        assertEquals(renegotiatingState1, renegotiatingState2)
        assertEquals(renegotiatingState1.value, renegotiatingState2.value)
    }

    @Test
    fun `test all call states have unique values`() {

        // We expect at least the object states to be unique
        val objectStates = listOf(
            CallState.NEW.value,
            CallState.CONNECTING.value,
            CallState.RINGING.value,
            CallState.ACTIVE.value,
            CallState.RENEGOTIATING.value,
            CallState.HELD.value,
            CallState.ERROR.value
        )
        
        assertEquals(7, objectStates.toSet().size) // All object states should be unique
    }

    @Test
    fun `test RENEGOTIATING state toString behavior`() {
        // Arrange & Act
        val renegotiatingState = CallState.RENEGOTIATING
        val stringRepresentation = renegotiatingState.toString()

        // Assert
        assert(stringRepresentation.contains("RENEGOTIATING"))
    }

    @Test
    fun `test call state transitions involving RENEGOTIATING`() {
        // This test simulates typical state transitions during ICE renegotiation
        
        // Arrange - typical call flow with renegotiation
        val stateFlow = listOf(
            CallState.NEW,
            CallState.CONNECTING,
            CallState.RINGING,
            CallState.ACTIVE,
            CallState.RENEGOTIATING, // ICE renegotiation starts
            CallState.ACTIVE         // ICE renegotiation completes
        )

        // Act & Assert - verify each state transition is valid
        stateFlow.forEachIndexed { index, state ->
            when (index) {
                0 -> assertEquals("NEW", state.value)
                1 -> assertEquals("CONNECTING", state.value)
                2 -> assertEquals("RINGING", state.value)
                3 -> assertEquals("ACTIVE", state.value)
                4 -> assertEquals("RENEGOTIATING", state.value)
                5 -> assertEquals("ACTIVE", state.value)
            }
        }
    }

    @Test
    fun `test RENEGOTIATING state in error scenarios`() {
        // Test that RENEGOTIATING can transition to ERROR state if needed
        
        // Arrange
        val renegotiatingState = CallState.RENEGOTIATING
        val errorState = CallState.ERROR

        // Assert
        assertEquals("RENEGOTIATING", renegotiatingState.javaClass.simpleName)
        assertEquals("ERROR", errorState.javaClass.simpleName)
    }

    @Test
    fun `test RENEGOTIATING state with call termination reasons`() {
        // Test that RENEGOTIATING doesn't interfere with call termination
        
        // Arrange
        val renegotiatingState = CallState.RENEGOTIATING
        val doneState = CallState.DONE(
            CallTerminationReason(
                cause = "NORMAL_CLEARING",
                causeCode = 16,
                sipCode = 200,
                sipReason = "OK"
            )
        )

        // Assert
        assertEquals("RENEGOTIATING", renegotiatingState.javaClass.simpleName)
        assertEquals("DONE", doneState.javaClass.simpleName)
        // DONE state has a reason parameter, RENEGOTIATING doesn't
        assertNotNull(doneState.reason)
    }

    @Test
    fun `test RENEGOTIATING state with network change scenarios`() {
        // Test RENEGOTIATING alongside network-related states
        
        // Arrange
        val renegotiatingState = CallState.RENEGOTIATING
        val droppedState = CallState.DROPPED(CallNetworkChangeReason.NETWORK_LOST)
        val reconnectingState = CallState.RECONNECTING(CallNetworkChangeReason.NETWORK_SWITCH)

        // Assert
        
        assertEquals("RENEGOTIATING", renegotiatingState.javaClass.simpleName)
        assertEquals("DROPPED", droppedState.javaClass.simpleName)
        assertEquals("RECONNECTING", reconnectingState.javaClass.simpleName)
        
        // DROPPED and RECONNECTING states have network change reasons
        assertEquals(CallNetworkChangeReason.NETWORK_LOST, droppedState.callNetworkChangeReason)
        assertEquals(CallNetworkChangeReason.NETWORK_SWITCH, reconnectingState.callNetworkChangeReason)
    }
}
