package com.telnyx.webrtc.sdk

import android.content.Context
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.appcompat.app.AppCompatActivity
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.observability.CallReportV2Ingress
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.telnyx_rtc.BuildConfig
import com.telnyx.webrtc.sdk.testhelpers.BaseTest
import com.telnyx.webrtc.sdk.utilities.ConnectivityHelper
import io.mockk.MockKAnnotations
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID
import org.mockito.Mockito

/**
 * t027 regression (client lifecycle): a reconnect login response may only bind
 * the session when it correlates with the LATEST outstanding LOGIN RPC on the
 * same socket; wrong-id, failed, stale-attempt, and non-string sessid responses
 * must never bind identity or touch readiness. A correlated confirmation then
 * resolves the reconnection state machine and cancels the stale 60s reconnection
 * timer, and the CLIENT_READY push emits client_ready for the reconnect.
 *
 * Reflection-only access to pre-existing private members so this suite compiles
 * unchanged against the base (fail-before) and candidate (pass-after) sources;
 * the new seam itself is invoked through getDeclaredMethod, so the fail-before
 * run reports NoSuchMethodException instead of a compile error.
 */
class TelnyxClientReconnectLifecycleTest : BaseTest() {

    @get:Rule
    val rule = InstantTaskExecutorRule()

    private val testDispatcher = StandardTestDispatcher()

    private val mockContext = mockk<Context>(relaxed = true)
    private val connectivityHelper = mockk<ConnectivityHelper>(relaxed = true)
    private val connectivityManager = mockk<ConnectivityManager>(relaxed = true)
    private val activeNetwork = mockk<Network>(relaxed = true)
    private val capabilities = mockk<NetworkCapabilities>(relaxed = true)
    private val audioManager = Mockito.spy(AudioManager::class.java)

    private lateinit var client: TelnyxClient
    private lateinit var reconnectSocket: TxSocket

    @Before
    fun setUp() {
        MockKAnnotations.init(this, true, true, true)
        Dispatchers.setMain(testDispatcher)
        BuildConfig.IS_TESTING.set(true)

        val networkCallback = object : ConnectivityHelper.NetworkCallback() {
            override fun onNetworkAvailable() {}
            override fun onNetworkUnavailable() {}
        }
        every { mockContext.getSystemService(Context.CONNECTIVITY_SERVICE) } returns connectivityManager
        every { connectivityManager.activeNetwork } returns activeNetwork
        every { connectivityManager.getNetworkCapabilities(activeNetwork) } returns capabilities
        every { capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) } returns false
        every { connectivityHelper.registerNetworkStatusCallback(mockContext, networkCallback) } just Runs
        every { mockContext.getSystemService(AppCompatActivity.AUDIO_SERVICE) } returns audioManager

        client = TelnyxClient(mockContext)
        reconnectSocket = mockk<TxSocket>(relaxed = true)
        client.socket = reconnectSocket
    }

    private fun setField(name: String, value: Any?) {
        val f = TelnyxClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        f.set(client, value)
    }

    private fun fieldOf(name: String): Any? {
        val f = TelnyxClient::class.java.getDeclaredField(name)
        f.isAccessible = true
        return f.get(client)
    }

    private fun notePendingLogin(rpcId: String, sentOn: TxSocket) {
        val m = TelnyxClient::class.java.getDeclaredMethod(
            "v2NotePendingLogin", String::class.java, TxSocket::class.java)
        m.isAccessible = true
        m.invoke(client, rpcId, sentOn)
    }

    private fun invokeOnLoginResponseConfirmed(sentOn: TxSocket, responseId: String?, response: JsonObject) {
        invokeOnLoginResponseConfirmedWithBarrier(sentOn, responseId, response) {}
    }

    private fun lookupLoginConfirmMethod(): java.lang.reflect.Method {
        val m = TelnyxClient::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("onLoginResponseConfirmed") &&
                it.parameterTypes.contentEquals(
                    arrayOf(TxSocket::class.java, String::class.java, JsonObject::class.java, Function0::class.java))
        } ?: TelnyxClient::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("onLoginResponseConfirmed") &&
                it.parameterTypes.contentEquals(
                    arrayOf(TxSocket::class.java, String::class.java, JsonObject::class.java))
        } ?: error("no onLoginResponseConfirmed seam found; methods=" +
            TelnyxClient::class.java.declaredMethods
                .filter { it.name.startsWith("onLoginResponseConfirmed") }.joinToString { it.name + it.parameterTypes.contentToString() })
        return m
    }

    /**
     * Deterministic supersession barrier: [barrier] runs inside the login-commit
     * guard after the response has passed the dispatch checks and BEFORE any
     * session/capture/isLoggedIn/timer side effect. Publishing a newer same-socket
     * auth (or swapping the socket) there reproduces the exact races the linearizable
     * commit must reject.
     */
    private fun invokeOnLoginResponseConfirmedWithBarrier(
        sentOn: TxSocket,
        responseId: String?,
        response: JsonObject,
        barrier: () -> Unit,
    ) {
        val m = lookupLoginConfirmMethod()
        if (m.parameterTypes.size == 3) m.invoke(client, sentOn, responseId, response)
        else m.invoke(client, sentOn, responseId, response, barrier)
    }

    private fun loginResponse(id: String, sessid: Any?): JsonObject {
        val result = JsonObject().apply {
            addProperty("message", "logged in")
            when (sessid) {
                is String -> addProperty("sessid", sessid)
                is Boolean -> addProperty("sessid", sessid)
                is Number -> addProperty("sessid", sessid)
            }
        }
        return JsonObject().apply {
            addProperty("id", id)
            add("result", result)
        }
    }

    private fun credentialConfig() = CredentialConfig(
        sipUser = "user", sipPassword = "secret", sipCallerIDName = null,
        sipCallerIDNumber = null, fcmToken = null, ringtone = null, ringBackTone = null)

    private fun armReconnectingWithPending() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-current", reconnectSocket)
    }

    private fun armReconnectionTimer() {
        val m = TelnyxClient::class.java.getDeclaredMethod("startReconnectionTimer")
        m.isAccessible = true
        m.invoke(client)
    }

    @Test
    fun `correlated reconnect login response binds the confirmed session and resolves the reconnection machine`() {
        armReconnectingWithPending()
        armReconnectionTimer()

        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-current",
            loginResponse("rpc-current", "sess-current"))

        assertEquals("sessid must bind the server-confirmed session of the correlated response",
            "sess-current", client.sessid)
        assertEquals("recorder context must carry the confirmed session id",
            "sess-current", fieldOf("callReportV2ServerSessionId") as? String)
        assertFalse("the correlated confirmation must resolve the reconnection state machine",
            (fieldOf("reconnecting") as? Boolean) == true)
        assertFalse("the stale reconnection timer must be cancelled by the resolution",
            (fieldOf("reconnectTimeOutJob") as? Job)?.isActive == true)
    }

    @Test
    fun `reconnect login confirmation cancels the stale reconnection timer`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-current", reconnectSocket)
        armReconnectionTimer()
        assertTrue("timer must be armed before resolution",
            (fieldOf("reconnectTimeOutJob") as? Job)?.isActive == true)

        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-current",
            loginResponse("rpc-current", "sess-current"))

        assertFalse("60s reconnection timer must be cancelled on a confirmed reconnect login",
            (fieldOf("reconnectTimeOutJob") as? Job)?.isActive == true)
        assertFalse("reconnecting must be resolved", (fieldOf("reconnecting") as? Boolean) == true)
    }

    @Test
    fun `a wrong-id login response never binds the session`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        notePendingLogin("rpc-current", reconnectSocket)
        val sessidBefore = client.sessid

        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-other",
            loginResponse("rpc-other", "sess-attacker"))

        assertEquals("sessid must stay unchanged when the response does not correlate",
            sessidBefore, client.sessid)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue("reconnecting stays in progress when the response does not correlate",
            (fieldOf("reconnecting") as? Boolean) == true)
    }

    @Test
    fun `a stale login response from a superseded attempt is rejected`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        notePendingLogin("rpc-first", reconnectSocket)
        // a newer auth attempt on the same socket supersedes the first
        notePendingLogin("rpc-second", reconnectSocket)

        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-first",
            loginResponse("rpc-first", "sess-old"))

        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue("state must remain reconnecting", (fieldOf("reconnecting") as? Boolean) == true)
    }

    @Test
    fun `a failed login response never binds or resolves`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        notePendingLogin("rpc-current", reconnectSocket)
        val failed = JsonObject().apply {
            addProperty("id", "rpc-current")
            add("error", JsonObject().apply { addProperty("message", "bad login") })
        }

        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-current", failed)

        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue("state must remain reconnecting", (fieldOf("reconnecting") as? Boolean) == true)
    }

    @Test
    fun `a non-string or blank sessid response never binds`() {
        val sessids = listOf<Any?>(true, 12345, "", null)
        for (sessid in sessids) {
            val ingress = mockk<CallReportV2Ingress>(relaxed = true)
            client.callReportV2 = ingress
            setField("reconnecting", true)
            notePendingLogin("rpc-current", reconnectSocket)

            invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-current",
                loginResponse("rpc-current", sessid))

            verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
            assertTrue("rejected response must not resolve reconnecting: sessid=$sessid",
                (fieldOf("reconnecting") as? Boolean) == true)
        }
    }

    @Test
    fun `a response from a foreign socket is rejected`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        notePendingLogin("rpc-current", reconnectSocket)
        val foreign = mockk<TxSocket>(relaxed = true)

        invokeOnLoginResponseConfirmed(foreign, "rpc-current",
            loginResponse("rpc-current", "sess-foreign"))

        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue("state must remain reconnecting", (fieldOf("reconnecting") as? Boolean) == true)
    }

    @Test
    fun `client ready push emits ready once the reconnect login is confirmed`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("waitingForReg", false)
        every { reconnectSocket.isLoggedIn } returns true

        val params = JsonObject().apply { addProperty("voice_sdk_id", "voice-current") }
        val m = TelnyxClient::class.java.getDeclaredMethod("onClientReady", JsonObject::class.java)
        m.isAccessible = true
        m.invoke(client, params)

        verify(exactly = 1) { ingress.ready() }
    }

    @Test
    fun `a newer same-socket login published at the commit barrier supersedes the stale response`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-old", reconnectSocket)
        val sessidBefore = client.sessid
        val isNewerTimerArmed = { (fieldOf("reconnectTimeOutJob") as? Job)?.isActive == true }

        invokeOnLoginResponseConfirmedWithBarrier(reconnectSocket, "rpc-old",
            loginResponse("rpc-old", "sess-old")) {
            // NEWER SAME-SOCKET AUTH publishes between the old response's dispatch and
            // its commit — under the same owned guard (production publishes via
            // v2NotePendingLogin; the guard makes this transition atomic).
            notePendingLogin("rpc-newer", reconnectSocket)
            // the newer reconnect cycle re-arms the reconnection timer (owned by the same guard)
            armReconnectionTimer()
            assertTrue(isNewerTimerArmed())
        }

        // STALE RESPONSE: zero binds, zero capture publication, no readiness, no timer cancellation.
        assertEquals("stale response must not bind an old session", sessidBefore, client.sessid)
        assertEquals("recorder context must not be restored by a stale response",
            null, fieldOf("callReportV2ServerSessionId") as? String)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue("reconnecting must remain owned by the newer cycle",
            (fieldOf("reconnecting") as? Boolean) == true)
        assertTrue("a stale response must not cancel the newer reconnection timer", isNewerTimerArmed())

        // The GENUINE newest response succeeds and resolves the reconnect machine.
        invokeOnLoginResponseConfirmedWithBarrier(reconnectSocket, "rpc-newer",
            loginResponse("rpc-newer", "sess-newest")) {}
        assertEquals("newest correlated response binds its session", "sess-newest", client.sessid)
        assertEquals("newest response restores the recorder context", "sess-newest",
            fieldOf("callReportV2ServerSessionId") as? String)
        verify(exactly = 1) { ingress.signaling(any(), any(), any()) }
        assertFalse("the newest resolution clears reconnecting",
            (fieldOf("reconnecting") as? Boolean) == true)
        assertFalse("the newest resolution cancels its reconnection timer", isNewerTimerArmed())
    }

    @Test
    fun `a socket replacement at the commit barrier rejects the stale response and the new socket binds`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-old", reconnectSocket)
        val replacementSocket = mockk<TxSocket>(relaxed = true)
        val sessidBefore = client.sessid

        invokeOnLoginResponseConfirmedWithBarrier(reconnectSocket, "rpc-old",
            loginResponse("rpc-old", "sess-old")) {
            // SOCKET REPLACEMENT between the old response's dispatch and its commit
            // (production: reconnectToSocket's guard-held swap) plus the newer pending.
            client.socket = replacementSocket
            notePendingLogin("rpc-newest", replacementSocket)
        }

        // STALE RESPONSE: zero binds against the replaced socket.
        assertEquals("stale response must not bind across a replaced socket",
            sessidBefore, client.sessid)
        assertEquals(null, fieldOf("callReportV2ServerSessionId") as? String)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue((fieldOf("reconnecting") as? Boolean) == true)
        assertFalse("the replaced socket must not be marked logged in by a stale response",
            replacementSocket.isLoggedIn)

        // The GENUINE newest response on the new socket binds.
        invokeOnLoginResponseConfirmedWithBarrier(replacementSocket, "rpc-newest",
            loginResponse("rpc-newest", "sess-newest")) {}
        assertEquals("newest response binds the new socket's session", "sess-newest", client.sessid)
        assertEquals("sess-newest", fieldOf("callReportV2ServerSessionId") as? String)
        verify(exactly = 1) { ingress.signaling(any(), any(), any()) }
        // the commit marks the CURRENT socket logged in
        verify { replacementSocket.isLoggedIn = true }
    }
    // ---- Functional snapshot-vs-apply race (Gson parser seam; no production test hook) ----

    /**
     * Functional reproduction of the snapshot-vs-apply ownership race: the
     * response's `result` object is a mock whose parser seam blocks AFTER the
     * pending snapshot/checks and BEFORE any side effect. While blocked, a newer
     * same-socket auth publishes (and arms a newer reconnection timer) in another
     * actual thread. On the pre-correction source (e6bb) the stale response then
     * binds the old sessid, marks the current socket logged in and cancels the
     * newer timer - functional red. On the corrected linearizable commit the
     * blocked parse happens before the guard, so the newer publication wins the
     * ownership check and the stale response binds nothing; the genuine newest
     * response then binds. (Mock-based; the recorder call order proves that the
     * stale session is never published after the newer authenticate.)
     */
    @Test
    fun `a newer same-socket auth published during the parser seam cannot be bound by a stale response`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-old", reconnectSocket)
        val sessidBefore = client.sessid
        val realResult = loginResult("sess-old")
        val responseMock = mockk<JsonObject>(relaxed = true)
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { responseMock.getAsJsonObject("result") } answers {
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
            realResult
        }
        val staleError = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val stale = Thread {
            try {
                invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-old", responseMock)
                java.io.File("build/probe-parser-seam.txt").appendText(
                    "THREAD-DONE sessid=" + client.sessid + " callReportV2ServerSessionId=" +
                        fieldOf("callReportV2ServerSessionId") + "\n")
            }
            catch (t: Throwable) { staleError.set(t) }
        }
        stale.start()
        assertTrue("response reached the parser seam", inFlight.await(5, TimeUnit.SECONDS))

        // NEWER SAME-SOCKET AUTH publishes while the old response is in flight.
        notePendingLogin("rpc-newer", reconnectSocket)
        armReconnectionTimer() // the newer reconnect cycle owns the timer
        ingress.authenticate("sip_credentials", "user", "secret")

        release.countDown()
        stale.join(5_000)
        staleError.get()?.let { throw AssertionError("stale dispatch failed", it) }

        // Zero stale side effects: the newer publication owns the session decision.
        assertEquals("stale response must not bind an old session", sessidBefore, client.sessid)
        assertEquals("recorder context must not be restored by a stale response",
            null, fieldOf("callReportV2ServerSessionId") as? String)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue("reconnecting must remain owned by the newer cycle",
            (fieldOf("reconnecting") as? Boolean) == true)
        assertTrue("a stale response must not cancel the newer reconnection timer",
            (fieldOf("reconnectTimeOutJob") as? Job)?.isActive == true)

        // The GENUINE newest response binds and resolves.
        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-newer",
            loginResponse("rpc-newer", "sess-newest"))
        assertEquals("newest correlated response binds its session", "sess-newest", client.sessid)
        assertEquals("sess-newest", fieldOf("callReportV2ServerSessionId") as? String)
        assertFalse("the newest resolution clears reconnecting",
            (fieldOf("reconnecting") as? Boolean) == true)
        assertFalse("the newest resolution cancels its reconnection timer",
            (fieldOf("reconnectTimeOutJob") as? Job)?.isActive == true)
    }

    /**
     * Socket replacement variant: while the stale response sits in the parser
     * seam, the reconnect cycle bumps the connection generation, swaps the socket
     * (guard-held in production) and publishes the newer pending; the stale
     * response must bind nothing (and, on e6bb, it must not stamp the newer
     * generation with the old session).
     */
    @Test
    fun `a socket replacement during the parser seam rejects the stale response and the new socket binds`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-old", reconnectSocket)
        val replacementSocket = mockk<TxSocket>(relaxed = true)
        val sessidBefore = client.sessid
        val realResult = loginResult("sess-old")
        val responseMock = mockk<JsonObject>(relaxed = true)
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { responseMock.getAsJsonObject("result") } answers {
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
            realResult
        }
        val staleError = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val stale = Thread {
            try { invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-old", responseMock) }
            catch (t: Throwable) { staleError.set(t) }
        }
        stale.start()
        assertTrue("response reached the parser seam", inFlight.await(5, TimeUnit.SECONDS))

        // SOCKET REPLACEMENT + newer pending (production reconnectToSocket sequence):
        nextConnectionGenerationForTest()
        synchronized(client) { client.socket = replacementSocket }
        notePendingLogin("rpc-newest", replacementSocket)
        armReconnectionTimer()
        ingress.authenticate("sip_credentials", "user", "secret")

        release.countDown()
        stale.join(5_000)
        staleError.get()?.let { throw AssertionError("stale dispatch failed", it) }

        assertEquals("stale response must not bind across a replaced socket",
            sessidBefore, client.sessid)
        assertEquals(null, fieldOf("callReportV2ServerSessionId") as? String)
        verify(exactly = 0) { ingress.signaling(any(), any(), any()) }
        assertTrue((fieldOf("reconnecting") as? Boolean) == true)
        assertFalse("the replaced socket must not be marked logged in by a stale response",
            replacementSocket.isLoggedIn)

        invokeOnLoginResponseConfirmed(replacementSocket, "rpc-newest",
            loginResponse("rpc-newest", "sess-newest"))
        assertEquals("newest response binds the new socket's session", "sess-newest", client.sessid)
        assertEquals("sess-newest", fieldOf("callReportV2ServerSessionId") as? String)
        verify { replacementSocket.isLoggedIn = true }
    }

    /**
     * Builds the real login RESULT object behind a mocked response envelope:
     * the client reads `response.getAsJsonObject("result")?.get("sessid")`, so the
     * mocked envelope's result access must yield the inner result object.
     */
    private fun loginResult(sessid: String): JsonObject {
        val result = JsonObject()
        result.addProperty("message", "logged in")
        result.addProperty("sessid", sessid)
        return result
    }

    /** AFTER-only seam check: the guard-local timer expiry is invoked reflectively. */
    private fun fireReconnectionTimeoutIfOwned(job: Job?, epoch: Long) {
        val m = TelnyxClient::class.java.declaredMethods.firstOrNull {
            it.name.startsWith("fireReconnectionTimeoutIfOwned") &&
                it.parameterTypes.contentEquals(arrayOf(Job::class.java, Long::class.java))
        } ?: error("TelnyxClient.fireReconnectionTimeoutIfOwned(...) not found (fail-before)")
        m.isAccessible = true
        m.invoke(client, job, epoch)
    }

    private fun nextConnectionGenerationForTest() {
        val m = TelnyxClient::class.java.getDeclaredMethod("nextConnectionGeneration")
        m.isAccessible = true
        m.invoke(client)
    }

    // ---- Reconnection timer ownership (positive/negative barrier) ----

    @Test
    fun `reconnection timeout fires only for the owning job and epoch`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        armReconnectionTimer()
        val job = fieldOf("reconnectTimeOutJob") as? Job
        val epoch = fieldOf("v2TimerEpoch") as? Long ?: -1L
        val victimCall = mockk<Call>(relaxed = true)
        val victimId = UUID.randomUUID()
        every { victimCall.currentSignalingCallId() } returns victimId
        client.calls[victimId] = victimCall

        fireReconnectionTimeoutIfOwned(job, epoch)

        assertTrue("an owned expiry resolves reconnecting",
            (fieldOf("reconnecting") as? Boolean) == false)
        // the captured victim gets its per-call teardown exactly once and is detached
        verify(exactly = 1) { victimCall.setReconnectionTimeout() }
        assertTrue("the victim is detached from the session", !client.calls.containsKey(victimId))
        // the global reset/error still fire for the owning expiry
        verify(exactly = 1) { reconnectSocket.callNotOngoing() }
        verify(exactly = 1) { ingress.error(any(), any()) }
        assertTrue("the owning expiry clears its timer", fieldOf("reconnectTimeOutJob") == null)
    }

    @Test
    fun `a superseded reconnection timer expiry never tears down or clears a newer timer`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        armReconnectionTimer()
        val oldJob = fieldOf("reconnectTimeOutJob") as? Job
        val oldEpoch = fieldOf("v2TimerEpoch") as? Long ?: -1L
        // A newer cycle re-arms the timer (new job + new epoch) and owns a newer call.
        armReconnectionTimer()
        val newerJob = fieldOf("reconnectTimeOutJob") as? Job
        val newerCall = mockk<Call>(relaxed = true)
        val newerCallId = UUID.randomUUID()
        every { newerCall.currentSignalingCallId() } returns newerCallId
        client.calls[newerCallId] = newerCall

        fireReconnectionTimeoutIfOwned(oldJob, oldEpoch)

        // The newer cycle is untouched: reconnecting stays owned, the newer timer stays
        // armed, and the newer call is never torn down.
        assertTrue("the newer cycle keeps reconnecting", (fieldOf("reconnecting") as? Boolean) == true)
        assertTrue("the newer timer must remain armed", (fieldOf("reconnectTimeOutJob") as? Job) === newerJob)
        assertTrue("the newer timer must stay active", newerJob?.isActive == true)
        verify(exactly = 0) { newerCall.setReconnectionTimeout() }
        verify(exactly = 0) { reconnectSocket.callNotOngoing() }
        verify(exactly = 0) { ingress.error(any(), any()) }
    }

    @Test
    fun `an expiry decision that loses the race to a newest commit never fires afterwards`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        armReconnectionTimer()
        val job = fieldOf("reconnectTimeOutJob") as? Job
        val epoch = fieldOf("v2TimerEpoch") as? Long ?: -1L
        notePendingLogin("rpc-current", reconnectSocket)
        // The newest login commit resolves the reconnection before the expiry runs.
        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-current",
            loginResponse("rpc-current", "sess-current"))

        fireReconnectionTimeoutIfOwned(job, epoch)

        assertFalse("a resolved reconnection never fires the timeout afterwards",
            (fieldOf("reconnecting") as? Boolean) == true)
        verify(exactly = 0) { reconnectSocket.callNotOngoing() }
        verify(exactly = 1) { ingress.signaling(any(), any(), any()) }
    }
    // ---- Expiry claim vs newest login/newer timer between claim and teardown ----

    /**
     * Deterministic production-entrypoint barrier: the expiry commits its claim
     * under the owned guard (ownership + victim capture + irrevocable detach),
     * then parks at the existing timeout logger seam BEFORE the destructive work.
     * A newest confirmed login and a newer timer/call are published in another
     * actual thread while parked. Assertions: the newest session binds; the newer
     * call is never torn down; the newer timer is not cancelled; and no stale
     * global error/reset runs against the newer cycle's ownership.
     */
    @Test
    fun `a newest login and newer timer published between expiry claim and teardown win without stale effects`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", true)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-current", reconnectSocket)
        armReconnectionTimer()
        val claimedJob = fieldOf("reconnectTimeOutJob") as? Job
        val claimedEpoch = fieldOf("v2TimerEpoch") as? Long ?: -1L

        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ingress.log(any(), any()) } answers {
            if (secondArg<String>().contains("Reconnection timeout")) {
                inFlight.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
            Unit
        }
        val expiry = Thread { fireReconnectionTimeoutIfOwned(claimedJob, claimedEpoch) }
        expiry.start()
        assertTrue("expiry reached the logger seam after its claim", inFlight.await(5, TimeUnit.SECONDS))

        // NEWEST LOGIN publishes and commits while the expiry thread is parked after
        // its guarded claim; a NEWER timer is armed and a NEWER call is registered.
        notePendingLogin("rpc-newest", reconnectSocket)
        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-newest",
            loginResponse("rpc-newest", "sess-newest"))
        armReconnectionTimer()
        val newerJob = fieldOf("reconnectTimeOutJob") as? Job
        val newerCall = mockk<Call>(relaxed = true)
        val newerCallId = UUID.randomUUID()
        every { newerCall.currentSignalingCallId() } returns newerCallId
        client.calls[newerCallId] = newerCall

        release.countDown()
        expiry.join(5_000)

        // zero stale teardown: the newer call is never torn down
        verify(exactly = 0) { newerCall.setReconnectionTimeout() }
        // no stale global error/reset against the newer cycle's ownership
        verify(exactly = 0) { ingress.error(any(), any()) }
        verify(exactly = 0) { reconnectSocket.callNotOngoing() }
        // no newer-timer cancellation
        assertTrue("the newer timer must remain armed", (fieldOf("reconnectTimeOutJob") as? Job) === newerJob)
        assertTrue("the newer timer must stay active", newerJob?.isActive == true)
        // the newest session binds and reconnecting stays resolved
        assertEquals("newest correlated response binds its session", "sess-newest", client.sessid)
        assertFalse((fieldOf("reconnecting") as? Boolean) == true)
    }
    // ---- B-WIN: auth owner publication precedes any capture auth/clear effect ----

    /**
     * B-WIN barrier: a newer auth entrypoint publishes its owner BEFORE its
     * capture authenticate (parked here via the existing ingress.authenticate
     * seam in another actual thread). An older login response dispatched during
     * the parked window must be rejected: its rpcId no longer matches the
     * publication. On the pre-correction source (owner published AFTER the
     * capture authenticate) the stale response binds the old session — functional
     * red; on the corrected source it binds nothing and the newest response binds.
     */
    @Test
    fun `a newer auth owner published before its capture auth rejects an older login response`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", false)
        setField("credentialSessionConfig", credentialConfig())
        notePendingLogin("rpc-old", reconnectSocket)
        val sessidBefore = client.sessid

        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        val inFlight = CountDownLatch(1)
        val release = CountDownLatch(1)
        every { ingress.authenticate(any(), any(), any()) } answers {
            events.add("authenticate")
            inFlight.countDown()
            release.await(5, TimeUnit.SECONDS)
        }
        every { ingress.signaling(any(), any(), any()) } answers {
            events.add("signaling:" + thirdArg<String>())
        }
        val newerAuth = Thread {
            try { client.credentialLogin(credentialConfig()) }
            catch (t: Throwable) { staleErrorRef().set(t) }
        }
        staleErrorRef().set(null)
        newerAuth.start()
        assertTrue("newer auth reached the capture authenticate seam", inFlight.await(5, TimeUnit.SECONDS))

        // THE OLD RESPONSE DISPATCHES while the newer auth is parked inside its
        // capture authenticate: the corrected owner publication happened first, so
        // the pending rpcId no longer matches the stale response.
        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-old",
            loginResponse("rpc-old", "sess-old"))

        release.countDown()
        newerAuth.join(5_000)
        staleErrorRef().get()?.let { throw AssertionError("newer auth failed", it) }

        // zero stale binds: the older response never binds
        assertEquals("older response must not bind an old session", sessidBefore, client.sessid)
        // the genuine newest response binds — its id is the rpcId the newer auth
        // published (the login RPC id), so the correlated commit matches it.
        val newestPendingId = fieldOf("v2PendingLogin")?.let { pending ->
            pending.javaClass.getDeclaredField("rpcId").apply { isAccessible = true }.get(pending) as? String
        } ?: error("no pending login publication")
        invokeOnLoginResponseConfirmed(reconnectSocket, newestPendingId,
            loginResponse(newestPendingId, "sess-newest"))
        assertEquals("newest correlated response binds its session", "sess-newest", client.sessid)
        // FIFO: the newer capture authenticate is recorded BEFORE the newest session
        // publication, and the stale session is never published on the corrected source.
        assertEquals(
            "capture FIFO: authenticate precedes the newest session; no stale session is published",
            listOf("authenticate", "signaling:sess-newest"), events)
    }

    /** Shared throwable slot for background-thread assertions. */
    private var staleError: java.util.concurrent.atomic.AtomicReference<Throwable>? = null

    private fun staleErrorRef(): java.util.concurrent.atomic.AtomicReference<Throwable> {
        if (staleError == null) staleError = java.util.concurrent.atomic.AtomicReference()
        return staleError!!
    }

    private fun requestGatewayStatusForTest() {
        val m = TelnyxClient::class.java.getDeclaredMethod("requestGatewayStatus")
        m.isAccessible = true
        m.invoke(client)
    }

    private fun currentGatewayRpcIdForTest(): String? {
        val pending = fieldOf("v2PendingGateway") as? Any ?: return null
        val rpcId = pending.javaClass.getDeclaredField("rpcId").apply { isAccessible = true }.get(pending)
        return rpcId as? String
    }

    private fun invokeOnGatewayStateResponseConfirmed(
        sentOn: TxSocket,
        responseId: String?,
        gatewayState: String,
        sessionId: String?,
    ) {
        val m = TelnyxClient::class.java.declaredMethods.first {
            it.name.startsWith("onGatewayStateResponseConfirmed") &&
                it.parameterTypes.contentEquals(
                    arrayOf(TxSocket::class.java, String::class.java, String::class.java, String::class.java))
        } ?: error("TelnyxClient.onGatewayStateResponseConfirmed not found (fail-before)")
        m.isAccessible = true
        m.invoke(client, sentOn, responseId, gatewayState, sessionId)
    }

    // ---- B-GW: gateway RPC correlation ----

    /**
     * Stale REGED response: an uncorrelated gateway RPC response (wrong id) must
     * not complete a registration — no onLoginSuccessful side effects, no session
     * rebind, no ready. The genuine correlated response then completes normally.
     */
    @Test
    fun `a stale REGED gateway response cannot complete a newer auth registration`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", false)
        setField("waitingForReg", true)
        setField("credentialSessionConfig", credentialConfig())
        val sessidBefore = client.sessid

        requestGatewayStatusForTest()
        val gatewayRpcId = currentGatewayRpcIdForTest()

        // stale id
        invokeOnGatewayStateResponseConfirmed(reconnectSocket, "wrong-id", "REGED", "sess-old")

        assertEquals("stale REGED must not bind", sessidBefore, client.sessid)
        verify(exactly = 0) { ingress.signaling(any(), any(), withArg<String> { assertEquals("sess-old", it) }) }
        verify(exactly = 0) { ingress.ready() }

        // genuine correlated response
        invokeOnGatewayStateResponseConfirmed(reconnectSocket, gatewayRpcId, "REGED", "sess-gw")
        testDispatcher.scheduler.runCurrent()
        assertEquals("correlated REGED binds its session", "sess-gw", client.sessid)
        verify(exactly = 1) { ingress.signaling(any(), any(), withArg<String> { assertEquals("sess-gw", it) }) }
        verify(exactly = 1) { ingress.ready() }
        assertTrue((fieldOf("reconnectTimeOutJob") as? Job) == null)
    }

    /**
     * Healthy initial gateway path: login response bound the session context, the
     * correlated gateway RPC response completes registration (REGED) and the
     * client_ready/session_timings are emitted in FIFO order with the login
     * publication.
     */
    @Test
    fun `healthy initial gateway path registers and emits ready in FIFO order`() {
        val ingress = mockk<CallReportV2Ingress>(relaxed = true)
        client.callReportV2 = ingress
        setField("reconnecting", false)
        setField("waitingForReg", true)
        setField("credentialSessionConfig", credentialConfig())
        val events = java.util.Collections.synchronizedList(mutableListOf<String>())
        every { ingress.signaling(any(), any(), any()) } answers {
            events.add("signaling:" + thirdArg<String>())
        }
        every { ingress.ready() } answers { events.add("ready") }
        // simulate the login response having already bound the session
        notePendingLogin("rpc-login", reconnectSocket)
        invokeOnLoginResponseConfirmed(reconnectSocket, "rpc-login", loginResponse("rpc-login", "sess-login"))

        requestGatewayStatusForTest()
        val gatewayRpcId = currentGatewayRpcIdForTest()

        invokeOnGatewayStateResponseConfirmed(reconnectSocket, gatewayRpcId, "REGED", "sess-gw")
        testDispatcher.scheduler.runCurrent()

        assertEquals("gateway REGED completes the registration with the server sessid", "sess-gw", client.sessid)
        // FIFO: the login publication precedes the gateway publication, and the
        // client_ready/session_timings (ready) follow the registration.
        assertEquals(
            "capture FIFO: login session precedes gateway session; ready last",
            listOf("signaling:sess-login", "signaling:sess-gw", "ready"), events)
    }
}
