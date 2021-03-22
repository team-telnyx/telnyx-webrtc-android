package com.telnyx.webrtc.sdk.socket

import android.Manifest
import android.content.Context
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.mock.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import androidx.test.rule.GrantPermissionRule
import io.kotest.matchers.shouldBe
import io.mockk.*
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.robolectric.RuntimeEnvironment.application
import java.net.ConnectException
import kotlin.test.assertFailsWith

class TxSocketTest : BaseTest() {
    @Test
    fun mockFailure() = runBlocking {
        val mock = MockEngine { call ->
            respond(
                "{}",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString())
            )
        }

        val client = HttpClient(mock) {
            install(WebSockets)
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
        }
        val resp = client.get<JsonObject>("dsf")
    }

    //TxSocketMocks
    @MockK
    private lateinit var listener: TelnyxClient
    @MockK
    private var mockContext: Context = mock(Context::class.java)

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.INTERNET,
    )

    @MockK
    private lateinit var socket: TxSocket

    @Before
    fun setup() {
        MockKAnnotations.init(this, true)
        Mockito.`when`(application.applicationContext).thenReturn(mockContext)

        every {socket.callOngoing()} just Runs
        every {socket.callNotOngoing()} just Runs
    }

    @Test
    fun `connect with valid host and port`() {
        socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        listener = TelnyxClient(socket, mockContext)
        socket.connect(listener)
    }

    @Test
    fun `connect with empty host or port`() {
        socket = TxSocket(
            host_address = "",
            port = 0,
        )
        listener = TelnyxClient(socket, mockContext)
        assertFailsWith<ConnectException> {
            socket.connect(listener)
        }
    }

    @Test
    fun `set call to ongoing`() {
        socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        socket.callOngoing()
        socket.ongoingCall shouldBe true
    }

    @Test
    fun `set call to not ongoing`() {
        socket = TxSocket(
            host_address = "rtc.telnyx.com",
            port = 14938,
        )
        socket.callNotOngoing()
        socket.ongoingCall shouldBe false
    }

    @Test
    fun `get web socket session before connection is established`() {
        assertFailsWith<UninitializedPropertyAccessException> {
            socket.getWebSocketSession()
        }
    }
}