package com.telnyx.webrtc.sdk


import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.BeforeEach
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory


class PeerTest : BaseTest() {

    @MockK
    private var options = Mockito.mock(PeerConnectionFactory.InitializationOptions::class.java)

    @BeforeEach
    fun setup() {
        BuildConfig.IS_TESTING.set(true);

        MockitoAnnotations.openMocks(this)
        mockkStatic(PeerConnectionFactory::class)
        mockkStatic(PeerConnection::class)
        every {PeerConnectionFactory.initialize(options)} just runs
    }

}
