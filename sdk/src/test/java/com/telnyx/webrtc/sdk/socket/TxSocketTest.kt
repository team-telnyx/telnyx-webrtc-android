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
import io.ktor.network.sockets.*
import io.mockk.MockKAnnotations
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import androidx.test.rule.GrantPermissionRule
import org.mockito.junit.MockitoJUnit.rule


class TxSocketTest : BaseTest() {
    @Test
    fun mockFailure() = runBlocking {
        val mock = MockEngine { call ->
            respond("{}",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }

        val client = HttpClient(mock) {
            install(WebSockets)
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
        }
        val resp =  client.get<JsonObject>("dsf")
    }

    //TxSocketMocks
    @MockK private lateinit var listener: TelnyxClient
    @MockK private var mockContext: Context = mock(Context::class.java)


    /*@MockK private lateinit var webSocketSession: DefaultClientWebSocketSession

    @MockK private val sendChannel = ConflatedBroadcastChannel<String>() */

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    @MockK private lateinit var socket: TxSocket

    @Before
    fun setup() {
        MockKAnnotations.init(this, true)

    }

    @Test
    fun `connect with empty host or port`() {
        socket = TxSocket(
            host_address = "",
            port = 0,
        )
        listener = TelnyxClient(socket, mockContext)
        socket.connect(listener)
    }

}