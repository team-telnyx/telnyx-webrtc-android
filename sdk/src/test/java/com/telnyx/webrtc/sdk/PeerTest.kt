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


class PeerTest : BaseTest() {

    @MockK
    private var options = Mockito.mock(PeerConnectionFactory.InitializationOptions::class.java)

    @BeforeEach
    fun setup() {
        MockitoAnnotations.openMocks(this)
        mockkStatic(PeerConnectionFactory::class)
        mockkStatic(PeerConnection::class)
        every {PeerConnectionFactory.initialize(options)} just runs
    }

}
