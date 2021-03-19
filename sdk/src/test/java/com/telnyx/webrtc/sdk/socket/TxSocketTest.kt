package com.telnyx.webrtc.sdk.socket

import com.google.gson.JsonObject
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TxSocketTest {
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
}