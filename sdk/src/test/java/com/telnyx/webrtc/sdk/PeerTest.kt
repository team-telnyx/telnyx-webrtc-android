package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.mockk.*
import io.mockk.impl.annotations.MockK
import org.junit.Before
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.mockito.Spy
import org.robolectric.RuntimeEnvironment.application
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory


//@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class PeerTest : BaseTest() {

    @Spy
    private var mockContext: Context = Mockito.spy(Context::class.java)

    @MockK
    private lateinit var observer: PeerConnection.Observer

    @MockK
    private var options = Mockito.mock(PeerConnectionFactory.InitializationOptions::class.java)

    @MockK
    private var peerConnection = Mockito.mock(PeerConnection::class.java)

    @MockK
    private var peer = Mockito.mock(Peer::class.java)

   // @Spy
    private lateinit var client: TelnyxClient

   // @Spy
    private lateinit var socket: TxSocket

    @Spy
    private var audioManager: AudioManager = Mockito.spy(AudioManager::class.java)

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)
        mockkStatic(PeerConnectionFactory::class)
        mockkStatic(PeerConnection::class)
        every {PeerConnectionFactory.initialize(options)} just runs
    }

    /*@Test
    fun `instantiate peer connection without error`() {

        @Spy
        socket = Mockito.spy(TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        ))


        @Spy
        client = Mockito.spy(TelnyxClient(mockContext, socket))

        val newCall = Call(mockContext, client, socket, "123", audioManager)
        newCall.newInvite("Test", "00000", "00000", "Test State")
        Mockito.verify(peer, Mockito.times(1)).startLocalAudioCapture()

    }*/

}
