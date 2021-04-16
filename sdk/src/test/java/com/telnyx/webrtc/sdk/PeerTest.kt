package com.telnyx.webrtc.sdk

import android.content.Context
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mockito
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class PeerTest : BaseTest() {

    @MockK
    private var mockContext: Context = Mockito.mock(Context::class.java)

    @MockK
    private lateinit var observer: PeerConnection.Observer

    @MockK
    private var options = Mockito.mock(PeerConnectionFactory.InitializationOptions::class.java)

    @MockK var peerConnection = Mockito.mock(PeerConnection::class.java)

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, true, true, true)
        mockkStatic(PeerConnectionFactory::class)
        mockkStatic(PeerConnection::class)
        every {PeerConnectionFactory.initialize(options)} just runs
    }
}
