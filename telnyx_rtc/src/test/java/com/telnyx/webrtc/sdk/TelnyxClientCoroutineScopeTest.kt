package com.telnyx.webrtc.sdk

import android.content.Context
import kotlinx.coroutines.Job
import org.mockito.Mockito
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelnyxClientCoroutineScopeTest {

    @Test
    fun `io coroutine work is owned by client lifecycle scope`() {
        val source = telnyxClientSource().readText()

        val standaloneIoScope = Regex("""CoroutineScope\s*\(\s*Dispatchers\.IO\s*\)\s*\.launch""")
        assertFalse(
            standaloneIoScope.containsMatchIn(source),
            "TelnyxClient should use clientScope.launch(Dispatchers.IO) for IO work."
        )

        val directClientIoLaunches = Regex("""clientScope\s*\.\s*launch\s*\(\s*Dispatchers\.IO\s*\)""")
            .findAll(source)
            .count()
        assertEquals(0, directClientIoLaunches)
        assertTrue(source.contains("private fun launchSocketConnect"))
        assertTrue(source.contains("private fun launchAcceptCallJob"))

        val callerOwnedSocketConnects = Regex("""parentScope\s*=\s*this""")
            .findAll(source)
            .count()
        assertEquals(6, callerOwnedSocketConnects)
    }

    @Test
    fun `removing call cancels pending accept job`() {
        val client = TelnyxClient(Mockito.mock(Context::class.java))
        val callId = UUID.randomUUID()
        val acceptJob = Job()

        acceptCallJobs(client)[callId] = acceptJob

        client.removeFromCalls(callId)

        assertTrue(acceptJob.isCancelled)
        assertFalse(acceptCallJobs(client).containsKey(callId))
        client.disconnect()
    }

    private fun telnyxClientSource(): File {
        val sourcePaths = listOf(
            "src/main/java/com/telnyx/webrtc/sdk/TelnyxClient.kt",
            "telnyx_rtc/src/main/java/com/telnyx/webrtc/sdk/TelnyxClient.kt"
        )

        val userDirectory = System.getProperty("user.dir") ?: error("user.dir is not set")
        var directory: File? = File(userDirectory).absoluteFile
        while (directory != null) {
            sourcePaths
                .map { path -> File(directory, path) }
                .firstOrNull { it.isFile }
                ?.let { return it }
            directory = directory.parentFile
        }

        error("Unable to locate TelnyxClient.kt from $userDirectory")
    }

    @Suppress("UNCHECKED_CAST")
    private fun acceptCallJobs(client: TelnyxClient): ConcurrentHashMap<UUID, Job> {
        return TelnyxClient::class.java.getDeclaredField("acceptCallJobs").apply {
            isAccessible = true
        }.get(client) as ConcurrentHashMap<UUID, Job>
    }
}
