package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import androidx.appcompat.app.AppCompatActivity
import com.telnyx.webrtc.sdk.socket.TxCallSocket
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.testhelpers.extensions.CoroutinesTestExtension
import com.telnyx.webrtc.sdk.testhelpers.extensions.InstantExecutorExtension
import io.ktor.client.features.websocket.*
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.impl.annotations.RelaxedMockK
import io.mockk.just
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import org.webrtc.EglBase
import org.webrtc.PeerConnection
import kotlin.test.assertEquals

@ExtendWith(InstantExecutorExtension::class, CoroutinesTestExtension::class)
class PeerTest : BaseTest() {

    @MockK
    private lateinit var mockContext: Context

    @MockK
    private lateinit var observer: PeerConnection.Observer

    @MockK
    private lateinit var eglBase: EglBase

    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this, true, true, true)
    }

    @Test
    fun `test starting local audio capture does not throw exception`() {

            val peer = Peer(mockContext, observer)
           // peer.startLocalAudioCapture()

    }

}