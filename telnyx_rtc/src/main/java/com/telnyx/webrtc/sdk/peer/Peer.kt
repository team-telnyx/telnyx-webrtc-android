/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.peer

import android.content.Context
import android.os.SystemClock
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.Config.DEFAULT_STUN
import com.telnyx.webrtc.sdk.Config.DEFAULT_TURN
import com.telnyx.webrtc.sdk.Config.DEFAULT_TURN_UDP
import com.telnyx.webrtc.sdk.Config.DEFAULT_TURNS_443
import com.telnyx.webrtc.sdk.Config.DEV_TURN
import com.telnyx.webrtc.sdk.Config.DEV_TURN_UDP
import com.telnyx.webrtc.sdk.Config.DEV_TURNS_443
import com.telnyx.webrtc.sdk.Config.GOOGLE_STUN
import com.telnyx.webrtc.sdk.Config.PASSWORD
import com.telnyx.webrtc.sdk.Config.USERNAME
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.CallState
import com.telnyx.webrtc.sdk.model.SocketMethod
import com.telnyx.webrtc.sdk.model.AudioConstraints
import com.telnyx.webrtc.sdk.model.TelnyxErrorCode
import com.telnyx.webrtc.sdk.model.TelnyxWarningCode
import com.telnyx.webrtc.sdk.stats.IceDiagnosticsCollector
import com.telnyx.webrtc.sdk.socket.TxSocket
import com.telnyx.webrtc.sdk.utilities.CallTimingBenchmark
import com.telnyx.webrtc.sdk.utilities.LatencyTracker
import com.telnyx.webrtc.sdk.utilities.Logger
import com.telnyx.webrtc.sdk.utilities.SdpUtils
import com.telnyx.webrtc.sdk.verto.send.SendingMessageBody
import com.telnyx.webrtc.sdk.verto.send.CandidateParams
import com.telnyx.webrtc.sdk.verto.send.EndOfCandidatesParams
import com.telnyx.webrtc.sdk.verto.send.CandidateDialogParams
import com.telnyx.webrtc.sdk.utilities.CodecUtils
import com.telnyx.webrtc.lib.AudioSource
import com.telnyx.webrtc.lib.AudioTrack
import com.telnyx.webrtc.lib.DataChannel
import com.telnyx.webrtc.lib.DefaultVideoDecoderFactory
import com.telnyx.webrtc.lib.DefaultVideoEncoderFactory
import com.telnyx.webrtc.lib.EglBase
import com.telnyx.webrtc.lib.CandidatePairChangeEvent
import com.telnyx.webrtc.lib.IceCandidate
import com.telnyx.webrtc.lib.MediaConstraints
import com.telnyx.webrtc.lib.MediaStream
import com.telnyx.webrtc.lib.PeerConnection
import com.telnyx.webrtc.lib.PeerConnectionFactory
import com.telnyx.webrtc.lib.SdpObserver
import com.telnyx.webrtc.lib.SessionDescription
import com.telnyx.webrtc.lib.RtpTransceiver
import com.telnyx.webrtc.lib.MediaStreamTrack
import com.telnyx.webrtc.lib.RtpCapabilities
import com.telnyx.webrtc.sdk.model.AudioCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.timerTask

/** The role of a planned ICE server, in Peer's documented order. */
internal enum class IceServerRole { PRIMARY_STUN, REDUNDANT_STUN, TURN_UDP, TURN_TCP, TURNS_443 }

/** One planned ICE server: the exact URL plus Peer's original credential policy. */
internal data class IceServerPlanEntry(
    val role: IceServerRole,
    val url: String,
    val withCredentials: Boolean,
)

/**
 * Peer class that represents a peer connection which is required to initiate a call.
 *
 * @param context the Context of the application
 * @param audioConstraints optional audio processing constraints for the call
 */
internal class Peer(
    context: Context,
    val client: TelnyxClient,
    private val providedTurn: String = DEFAULT_TURN,
    private val providedStun: String = DEFAULT_STUN,
    private val callId: UUID,
    private val prefetchIceCandidate: Boolean = false,
    private val forceRelayCandidate: Boolean = false,
    /**
     * ICE mode captured when this peer is created, mirroring [com.telnyx.webrtc.sdk.Call]'s
     * immutable per-call snapshot. A mid-call change of the session-wide default
     * (TelnyxClient.setUseTrickleIce) must not reroute candidate transport for a
     * call that already signaled its mode — e.g. an ANSWER already marked trickle.
     */
    private val useTrickleIce: Boolean = client.getUseTrickleIce(),
    private val isAnswering: Boolean = false,
    val onIceCandidateAdd: ((String) -> (Unit))? = null,
    private val audioConstraints: AudioConstraints? = null,
    private val peerConnectionFactoryOverride: PeerConnectionFactory? = null,
    private val initialV2CaptureToken: com.telnyx.webrtc.sdk.observability.V2CaptureToken? = null,
) {

    companion object {
        private const val V2_FINAL_STATS_TIMEOUT_MS = 250L
        private const val AUDIO_LOCAL_TRACK_ID = "audio_local_track"
        private const val AUDIO_LOCAL_STREAM_ID = "audio_local_stream"
        private const val NEGOTIATION_TIMEOUT = 300L // 300ms timeout for negotiation
        private const val END_OF_CANDIDATES_TIMEOUT =
            3000L // 3 seconds timeout for end-of-candidates
        private const val ENABLE_PREFETCH_CANDIDATES = 10
        private const val DISABLE_PREFETCH_CANDIDATES = 0
        private const val ICE_DIAG_TAG = "ICE_DIAG"

        // Upper bound for disconnect() waiting on in-flight native readers before
        // dispose(). Kept well below the 5 s ANR threshold (VSUP-324).
        private const val DISPOSE_LOCK_TIMEOUT_MS = 1000L
        private const val ICE_CANDIDATE_MIN_PARTS = 8
        private const val ICE_CANDIDATE_TRANSPORT_INDEX = 2
        private const val ICE_CANDIDATE_ADDRESS_INDEX = 4
        private const val ICE_CANDIDATE_PORT_INDEX = 5

        /**
         * Formats an ICE candidate for diagnostic logs with the fields that are
         * always populated: the candidate type and address parsed from the SDP
         * candidate line. `serverUrl` is only set for STUN/TURN-derived
         * candidates (empty for host candidates), so it says nothing about a
         * direct connection — the SDP-derived summary does.
         *
         * Produces e.g. `host 192.168.1.10:50000/udp` or
         * `srflx 84.x.x.x:50001/udp via stun:stun.l.google.com:19302`.
         */
        internal fun describeCandidate(candidate: IceCandidate?): String {
            if (candidate == null) return "none"
            val sdpParts = candidate.sdp?.trim()?.split(" ")
            var type: String? = null
            var address: String? = null
            var protocol: String? = null
            if (sdpParts != null && sdpParts.size >= ICE_CANDIDATE_MIN_PARTS) {
                // candidate:<foundation> <component-id> <transport> <priority> <ip> <port> typ <type> ...
                protocol = sdpParts[ICE_CANDIDATE_TRANSPORT_INDEX]
                address =
                    "${sdpParts[ICE_CANDIDATE_ADDRESS_INDEX]}:${sdpParts[ICE_CANDIDATE_PORT_INDEX]}"
                val typIndex = sdpParts.indexOf("typ")
                if (typIndex >= 0 && typIndex + 1 < sdpParts.size) {
                    type = sdpParts[typIndex + 1]
                }
            }
            val summary = buildString {
                append(type ?: "unknown")
                append(' ')
                append(address ?: candidate.sdp ?: "no-address")
                protocol?.let {
                    append('/')
                    append(it.lowercase())
                }
                // For STUN/TURN-derived candidates, include which server produced us
                candidate.serverUrl?.takeIf { it.isNotBlank() }?.let {
                    append(" via ")
                    append(it)
                }
            }
            return summary
        }

        private const val AUDIO_BUFFER_RESET_DELAY_MS =
            200L // 0.2 second delay for audio buffer reset
        private const val AUDIO_RE_ENABLE_DELAY_MS =
            100L // 0.1 second delay before re-enabling audio
        private const val ICE_GATHERING_SAFETY_TIMEOUT_MS = 15000L // matches JS Peer.ICE_GATHERING_SAFETY_TIMEOUT_MS

        // Recovery monitor cadence. IDLE only needs to match the signaling
        // health interval; active recovery uses a tighter deadline cadence.
        private const val RECOVERY_ACTIVE_TICK_MS = 250L
        private const val RECOVERY_IDLE_TICK_MS = 3_000L
        private const val RECOVERY_RTP_SAMPLE_MS = 1_000L


        // ICE candidate parsing constants
        private const val TYP_PREFIX = "typ "
        private const val PROTOCOL_INDEX = 2
        private const val UNKNOWN_VALUE = "unknown"

        /** Mirrors iOS candidate-path selection for the VPN relay fallback. */
        internal fun selectedCandidateUsesDirectVpn(
            statistics: Map<String, Map<String, Any?>>,
        ): Boolean {
            val transport = statistics.values.firstOrNull { it["type"] == "transport" }
            val candidatePair = (transport?.get("selectedCandidatePairId") as? String)
                ?.let(statistics::get)
            val localCandidate = (candidatePair?.get("localCandidateId") as? String)
                ?.let(statistics::get)
            val networkType = localCandidate?.get("networkType") as? String
            val candidateType = localCandidate?.get("candidateType") as? String

            return networkType?.equals("vpn", ignoreCase = true) == true &&
                candidateType?.equals("relay", ignoreCase = true) == false
        }

        /**
         * Shared PeerConnectionFactory used for codec queries and peer connections.
         * Lazily initialized on first access and shared across all Peer instances.
         * This is thread-safe and follows WebRTC best practices.
         */
        private val sharedPeerConnectionFactory: PeerConnectionFactory by lazy {
            buildSharedPeerConnectionFactory()
        }

        /**
         * Ensures PeerConnectionFactory is initialized with the application context.
         * This should be called before any WebRTC operations.
         * Safe to call multiple times - initialization only happens once.
         *
         * @param context Application context
         */
        private fun initPeerConnectionFactory(context: Context) {
            val options = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .setFieldTrials("WebRTC-H264HighProfile/Enabled/")
                .createInitializationOptions()
            PeerConnectionFactory.initialize(options)
        }

        /**
         * Builds the shared PeerConnectionFactory with standard configuration.
         * This factory is used for both codec queries and actual peer connections.
         *
         * @return Configured PeerConnectionFactory instance
         */
        private fun buildSharedPeerConnectionFactory(): PeerConnectionFactory {
            val rootEglBase = EglBase.create()
            return PeerConnectionFactory
                .builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
                .setVideoEncoderFactory(
                    DefaultVideoEncoderFactory(
                        rootEglBase.eglBaseContext,
                        true,
                        true
                    )
                )
                .setOptions(
                    PeerConnectionFactory.Options().apply {
                        disableEncryption = false
                        disableNetworkMonitor = true
                    }
                )
                .createPeerConnectionFactory()
        }

        /**
         * Gets the list of audio codecs supported by WebRTC without creating a Peer instance.
         * This is an efficient way to query available codecs before making calls.
         *
         * @param context Application context
         * @return List of AudioCodec objects representing supported codecs
         */
        fun getSupportedAudioCodecs(context: Context): List<AudioCodec> {
            return try {
                // Ensure WebRTC is initialized
                initPeerConnectionFactory(context)

                // Query capabilities from shared factory
                val capabilities = sharedPeerConnectionFactory.getRtpSenderCapabilities(
                    MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
                )

                Logger.d(
                    tag = "Peer.Companion",
                    message = "Retrieved ${capabilities.codecs.size} codec capabilities from shared factory"
                )

                // Convert to AudioCodec list
                CodecUtils.convertCapabilitiesToAudioCodecs(capabilities.codecs)
            } catch (e: Exception) {
                Logger.e(
                    tag = "Peer.Companion",
                    message = "Error retrieving supported audio codecs: ${e.message}"
                )
                emptyList()
            }
        }

        /** Derives the UDP and TCP variants of a TURN URL (single source for Peer and reporting). */
        internal fun turnTransportVariants(turnUrl: String): Pair<String, String> {
            val udp = when {
                turnUrl.contains("transport=tcp") -> turnUrl.replace("transport=tcp", "transport=udp")
                turnUrl.contains("transport=udp") -> turnUrl // Already UDP
                turnUrl.contains("?") -> "$turnUrl&transport=udp" // Has other params, append
                else -> "$turnUrl?transport=udp" // No params, add
            }
            val tcp = when {
                turnUrl.contains("transport=") -> turnUrl // Already has transport param
                turnUrl.contains("?") -> "$turnUrl&transport=tcp" // Has other params, append
                else -> "$turnUrl?transport=tcp" // No params, add
            }
            return udp to tcp
        }

        /**
         * The exact ICE server plan a Peer configures for the given provided TURN/STUN servers,
         * defaults included, in Peer's documented order. This is the single source of truth:
         * [buildIceServers] constructs the real servers from this plan and V2 call-started
         * reporting counts it — so the reported count always matches the built list by
         * construction, never by a separately maintained constant.
         *
         * Default production/development TURN hosts keep their predefined endpoint constants;
         * a custom TURN URL derives all three endpoint variants. The credential policy is the
         * original one: every planned server carries the configured TURN credentials except the
         * redundant public STUN server, which stays credential-free.
         */
        internal fun iceServerPlan(providedTurn: String, providedStun: String): List<IceServerPlanEntry> {
            val (turnUdp, turnTcp, turns443) = when (providedTurn) {
                DEFAULT_TURN -> Triple(DEFAULT_TURN_UDP, DEFAULT_TURN, DEFAULT_TURNS_443)
                DEV_TURN -> Triple(DEV_TURN_UDP, DEV_TURN, DEV_TURNS_443)
                else -> Triple(
                    turnTransportVariants(providedTurn).first,
                    turnTransportVariants(providedTurn).second,
                    deriveTurns443Url(providedTurn)
                )
            }
            return listOf(
                IceServerPlanEntry(IceServerRole.PRIMARY_STUN, providedStun, withCredentials = true),
                IceServerPlanEntry(IceServerRole.REDUNDANT_STUN, GOOGLE_STUN, withCredentials = false),
                IceServerPlanEntry(IceServerRole.TURN_UDP, turnUdp, withCredentials = true),
                IceServerPlanEntry(IceServerRole.TURN_TCP, turnTcp, withCredentials = true),
                IceServerPlanEntry(IceServerRole.TURNS_443, turns443, withCredentials = true),
            )
        }

        /** URL list of the plan (the ordered, defaults-included server URLs the call uses). */
        internal fun iceServerPlanUrls(providedTurn: String, providedStun: String): List<String> =
            iceServerPlan(providedTurn, providedStun).map { it.url }

        /** Number of ICE servers a Peer actually configures for the given provided servers (defaults included). */
        internal fun effectiveIceServerCount(providedTurn: String, providedStun: String): Int =
            iceServerPlan(providedTurn, providedStun).size

        /** Builds the real [PeerConnection.IceServer] list from the shared plan (single builder path). */
        internal fun buildIceServers(plan: List<IceServerPlanEntry>): List<PeerConnection.IceServer> =
            plan.map { entry ->
                val builder = PeerConnection.IceServer.builder(entry.url)
                if (entry.withCredentials) builder.setUsername(USERNAME).setPassword(PASSWORD)
                builder.createIceServer()
            }

        internal fun deriveTurns443Url(turnUrl: String): String {
            var derived = turnUrl

            // Upgrade `turn:` scheme to `turns:` (TLS) — guard against double prefix
            // when the input is already a `turns:` URL.
            if (!derived.startsWith("turns:")) {
                derived = derived.replaceFirst("turn:", "turns:")
            }

            // Replace any explicit port with 443
            derived = Regex(":\\d+").replace(derived, ":443")

            // TURNS (TURN over TLS) only supports TCP; override UDP if present.
            derived = derived.replace("transport=udp", "transport=tcp")

            // Ensure transport=tcp is set if no transport param exists
            if (!derived.contains("transport=")) {
                derived = if (derived.contains("?")) {
                    "$derived&transport=tcp"
                } else {
                    "$derived?transport=tcp"
                }
            }

            return derived
        }
    }

    private fun emitCallError(code: Int, message: String? = null) =
        client.emitError(code, message, v2CaptureToken)
    private fun emitCallWarning(code: Int) = client.emitWarning(code, v2CaptureToken)

    /**
     * t027: warning with an owned measurement. [value] is the actual elapsed
     * measurement that tripped the warning and [threshold] the configured limit
     * — both read from the Peer's own state, never fabricated.
     */
    private fun emitCallWarning(code: Int, metric: String?, value: Double?, threshold: Double?) =
        client.emitWarning(code, v2CaptureToken,
            metric?.let { com.telnyx.webrtc.sdk.observability.V2WarningMeasurement(it, value, threshold) })

    private val v2Logger = com.telnyx.webrtc.sdk.observability.V2ScopedLogger { client.callReportV2 }
    private var lastCandidateTime = System.currentTimeMillis()
    private var negotiationTimer: Timer? = null
    private var onNegotiationComplete: (() -> Unit)? = null

    // Deferred to signal when the first ICE candidate (local or remote) is processed
    internal val firstCandidateDeferred = CompletableDeferred<Unit>()
    private var firstCandidateReceived = false // Flag to ensure deferred completes only once

    // Selective candidate queuing for answering side (until ANSWER is sent).
    // Both queuedCandidates and answerSent are guarded by candidateLock to ensure
    // atomicity: onIceCandidate() checks answerSent and queues under the lock,
    // while flushQueuedCandidatesAfterAnswer() drains, clears, and sets answerSent
    // under the same lock. This prevents a candidate from being lost in the window
    // between the flush snapshot and clear().
    private val candidateLock = ReentrantLock()
    private val queuedCandidates = mutableListOf<IceCandidate>()
    private var answerSent = false

    // A trickle ICE restart must send its updateMedia offer before any new
    // generation candidates. WebRTC can emit candidates immediately after
    // setLocalDescription(), so hold them until the Modify is on the socket.
    private val queuedIceRestartCandidates = mutableListOf<IceCandidate>()
    private var iceRestartModifyPending = false
    private var iceRestartEndOfCandidatesPending = false

    // End-of-candidates timer management
    private var endOfCandidatesTimer: Timer? = null
    private var endOfCandidatesSent = false

    // ICE gathering safety timeout (mirrors JS Peer.ICE_GATHERING_SAFETY_TIMEOUT_MS):
    // if gathering has not completed within the window, warn ICE_GATHERING_TIMEOUT,
    // or ICE_GATHERING_EMPTY when no candidates were gathered at all (VSDK-393).
    private var iceGatheringSafetyTimer: Timer? = null
    private var gatheredCandidatesCount = 0
    // t027: monotonic anchor for the measured elapsed reported with gathering warnings.
    private var iceGatheringSafetyArmedElapsedMs: Long? = null

    private val mediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
        optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    }

    val iceServer = getIceServers()

    /**
     * Retrieves the IceServers built with the provided STUN and TURN servers
     *
     * ICE server order for optimal connectivity:
     * 1. Telnyx STUN - Primary STUN server
     * 2. Google STUN - Redundancy fallback
     * 3. TURN UDP - Lower latency, preferred for real-time media
     * 4. TURN TCP - Fallback for restrictive firewalls
     * 5. TURNS 443 - Last-resort fallback for restrictive firewalls
     *
     * The exact URLs, order and credential policy come from the shared plan
     * ([iceServerPlan] via [buildIceServers]); this method only attaches the plan to
     * diagnostics and logging. V2 call-started reporting uses the same plan for the
     * effective server count, so the reported count always matches the built list.
     *
     * @see [TxSocket]
     * @see [PeerConnection.IceServer]
     *
     * @return [List] of [PeerConnection.IceServer]
     */
    private fun getIceServers(): List<PeerConnection.IceServer> {
        // The shared plan is the single source of truth: the real servers below are built
        // from it (URLs, order, credentials), so the configured count and the built list
        // can never drift apart.
        val plan = iceServerPlan(providedTurn, providedStun)
        val turnUdp = plan.first { it.role == IceServerRole.TURN_UDP }.url
        val turnTcp = plan.first { it.role == IceServerRole.TURN_TCP }.url
        val turns443 = plan.first { it.role == IceServerRole.TURNS_443 }.url
        v2Logger.d(tag = ICE_DIAG_TAG, message = {
            "Start collection of ice servers | providedStun=$providedStun | providedTurn=$providedTurn | forceRelay=$forceRelayCandidate | prefetchPool=$iceCandidatePoolSize [${SystemClock.elapsedRealtime()}ms]"
        })

        recordIceDiagnostic(
            event = "ice_servers_config",
            fields = mapOf(
                "providedStun" to providedStun,
                "providedTurn" to IceDiagnosticsCollector.redactIceServerUrl(providedTurn),
                "forceRelay" to forceRelayCandidate,
                "prefetchPoolSize" to iceCandidatePoolSize,
                "derivedTurnUdp" to IceDiagnosticsCollector.redactIceServerUrl(turnUdp),
                "derivedTurnTcp" to IceDiagnosticsCollector.redactIceServerUrl(turnTcp),
                "derivedTurns443" to IceDiagnosticsCollector.redactIceServerUrl(turns443),
                "serverCount" to plan.size,
            ),
        )
        // The plan carries the exact URLs, order and the original credential policy; the real
        // servers are built from it in one place.
        val iceServers: List<PeerConnection.IceServer> = buildIceServers(plan)
        v2Logger.d(message = "End collection of ice servers: ${iceServers.size} servers configured (UDP: $turnUdp, TCP: $turnTcp, TURNS: $turns443)")
        return iceServers
    }

    val iceCandidatePoolSize = getIceCandidatePool()

    /*
    * Returns the number of ICE candidates to prefetch
    *
    * @return [Int] size of the ice candidate pool
     */
    private fun getIceCandidatePool(): Int {
        return if (prefetchIceCandidate) ENABLE_PREFETCH_CANDIDATES else DISABLE_PREFETCH_CANDIDATES
    }

    /**
     * Use the shared PeerConnectionFactory for this Peer instance.
     * This improves efficiency by reusing the factory across all peers.
     */
    private val peerConnectionFactory: PeerConnectionFactory
        get() = peerConnectionFactoryOverride ?: sharedPeerConnectionFactory

    // VSUP-324: volatile so teardown on one thread (disconnect()) is visible
    // to stats/collector threads reading via the snapshot guard in
    // get*Description()/getStats() — without the volatile, a reader could
    // keep a stale non-null reference while disconnect() already ran.
    @Volatile
    internal var peerConnection: PeerConnection? = null

    /**
     * Flag indicating whether the peer connection has been disposed.
     * Used to prevent stats collection from accessing a freed native PeerConnection.
     * See: https://github.com/team-telnyx/telnyx-webrtc-android/issues/787
     */
    internal val isDisposed = AtomicBoolean(false)
    private val v2PeerSnapshotLock = Any()

    /**
     * Serialises native reads of [peerConnection] against `dispose()` (VSUP-324).
     *
     * Readers go through [withLiveConnection] and hold the read lock only for the
     * duration of a single native call, so `dispose()` (write lock) can never free
     * the native object underneath them. Readers use a non-blocking `tryLock()`:
     * a reader on the WebRTC signaling thread must never wait for the disposing
     * thread, which itself may be waiting on the signaling thread inside dispose().
     */
    private val disposeLock = ReentrantReadWriteLock()

    internal var peerConnectionObserver: PeerConnectionObserver? = null
    private var localAudioTrack: AudioTrack? = null
    private var previousIceConnectionState: PeerConnection.IceConnectionState? = null

    /**
     * Per-call ICE-restart and peer-connection recovery monitor. The
     * monitor owns the state machine, debouncing, and one-at-a-time
     * decisions; the [Peer] still owns the actual SDP offer creation, the
     * updateMedia exchange, and the answer application. Construction is
     * deferred until [init] runs so the underlying [peerConnection] is
     * available for the Actions adapter.
     */
    internal lateinit var signalingHealthMonitor: SignalingHealthMonitor
    private var recoveryTickTimer: Timer? = null
    private var recoveryTickIntervalMs: Long? = null
    private val recoveryRequestLock = Any()
    private var pendingRecoveryRequestId: String? = null
    private var pendingRecoveryGeneration: Long? = null
    private val recoveryStatsRequestInFlight = AtomicBoolean(false)
    @Volatile
    private var lastRecoveryRtpSampleAtMs: Long = 0L

    private data class RecoveryResponseContext(
        val generation: Long?,
        val stale: Boolean,
    )

    /**
     * Gets the supported audio codec capabilities from the shared factory.
     * This method is kept for backward compatibility with existing code.
     *
     * @return List of codec capabilities
     */
    internal fun getSupportedSenderAudioCodecs(): List<RtpCapabilities.CodecCapability> {
        val capabilities = peerConnectionFactory.getRtpSenderCapabilities(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO)
        return capabilities.codecs
    }

    private fun logAudioTrackAndTransceiverState(contextTag: String) {
        if (peerConnection == null) {
            v2Logger.w(tag = "Peer:AudioState", message = "$contextTag - PeerConnection is null.")
            return
        }
        v2Logger.d(tag = "Peer:AudioState", message = "$contextTag - Checking audio state...")
        val localTrackId = localAudioTrack?.id()
        v2Logger.d(
            tag = "Peer:AudioState",
            message = "$contextTag - LocalAudioTrack ID: $localTrackId, State: ${localAudioTrack?.state()}, Enabled: ${localAudioTrack?.enabled()}"
        )

        val audioTransceiver = peerConnection?.transceivers?.find {
            it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO
        }

        if (audioTransceiver == null) {
            v2Logger.w(tag = "Peer:AudioState", message = "$contextTag - No audio transceiver found.")
        } else {
            reportV2PeerMedia(audioTransceiver)
            val senderTrackId = audioTransceiver.sender.track()?.id()
            v2Logger.d(
                tag = "Peer:AudioState",
                message = "$contextTag - Audio Transceiver Found: Mid: ${audioTransceiver.mid}, Direction: ${audioTransceiver.direction}, CurrentDirection: ${audioTransceiver.currentDirection}, Sender Track ID: $senderTrackId, Receiver Track ID: ${
                    audioTransceiver.receiver.track()?.id()
                }, Stopped: ${audioTransceiver.isStopped}"
            )
            if (senderTrackId != null && localTrackId != null && senderTrackId != localTrackId) {
                v2Logger.w(
                    tag = "Peer:AudioState",
                    message = "$contextTag - Audio transceiver sender track ID [$senderTrackId] does NOT match localAudioTrack ID [$localTrackId]"
                )
            } else if (senderTrackId == null && localTrackId != null) {
                v2Logger.w(
                    tag = "Peer:AudioState",
                    message = "$contextTag - Audio transceiver sender track is null, but localAudioTrack ID is [$localTrackId]"
                )
            }
        }
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(p0: PeerConnection.SignalingState?) {
            v2Logger.d(tag = "Observer", message = "Signaling State Change: $p0")
            p0?.let { state -> v2CaptureToken?.let { client.callReportV2?.peerState(it, "signaling_state", state.name) } }
            peerConnectionObserver?.onSignalingChange(p0)
            // Notify debug data collector
            p0?.let { client.debugDataCollector.onSignalingStateChange(callId, it.name) }
        }

        override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
            newState?.let { state -> v2CaptureToken?.let { client.callReportV2?.iceConnectionState(it, state.name) } }
            v2Logger.d(tag = ICE_DIAG_TAG, message = {
                "ICE Connection State Change: $newState [${SystemClock.elapsedRealtime()}ms]"
            })
            newState?.let { recordIceDiagnostic(event = "ice_connection_state", fields = mapOf("state" to it.name)) }
            peerConnectionObserver?.onIceConnectionChange(newState)
            // Notify debug data collector
            newState?.let { client.debugDataCollector.onIceConnectionStateChange(callId, it.name) }

            // Mark benchmark milestone for ICE connection state changes
            newState?.let { CallTimingBenchmark.mark("ice_state_${it.name}") }

            // Track latency milestones for ICE connection states
            newState?.let { state ->
                val milestone = when (state) {
                    PeerConnection.IceConnectionState.CHECKING -> LatencyTracker.MILESTONE_ICE_CHECKING
                    PeerConnection.IceConnectionState.CONNECTED -> LatencyTracker.MILESTONE_ICE_CONNECTED
                    PeerConnection.IceConnectionState.COMPLETED -> LatencyTracker.MILESTONE_ICE_COMPLETED
                    else -> null
                }
                milestone?.let {
                    client.latencyTracker.markCallMilestone(callId, it)
                    when (it) {
                        LatencyTracker.MILESTONE_ICE_CONNECTED -> reportV2Timing("ice_connected_ms")
                        LatencyTracker.MILESTONE_ICE_GATHERING_STARTED -> reportV2Timing("ice_gathering_started_ms")
                        LatencyTracker.MILESTONE_ICE_GATHERING_COMPLETE -> reportV2Timing("all_ice_candidates_gathered_ms")
                    }
                }
            }

            // Handle ICE connection state transitions
            handleIceConnectionStateTransition(previousIceConnectionState, newState)

            // Drive the recovery monitor. Duplicate callbacks against a
            // current recovery generation are no-ops at the monitor.
            signalingHealthMonitor.onIceConnectionChange(newState)
            scheduleRecoveryTickIfNeeded()

            // Update previous state
            previousIceConnectionState = newState
        }

        override fun onIceConnectionReceivingChange(p0: Boolean) {
            v2Logger.d(tag = ICE_DIAG_TAG, message = {
                "ICE Connection Receiving Change: $p0 [${SystemClock.elapsedRealtime()}ms]"
            })
            recordIceDiagnostic(event = "ice_receiving_change", fields = mapOf("receiving" to p0))
            peerConnectionObserver?.onIceConnectionReceivingChange(p0)
        }

        override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
            v2Logger.d(tag = ICE_DIAG_TAG, message = {
                "ICE Gathering State Change: $p0 [${SystemClock.elapsedRealtime()}ms]"
            })
            p0?.let { recordIceDiagnostic(event = "ice_gathering_state", fields = mapOf("state" to it.name)) }

            // ICE gathering safety timeout (JS parity, VSDK-393): reset the candidate
            // counter when gathering starts and arm a 15s watchdog; clear it on complete.
            when (p0) {
                PeerConnection.IceGatheringState.GATHERING -> {
                    gatheredCandidatesCount = 0
                    startIceGatheringSafetyTimeout()
                }
                PeerConnection.IceGatheringState.COMPLETE -> clearIceGatheringSafetyTimeout()
                else -> Unit
            }

            // Mark benchmark milestone for ICE gathering state changes
            p0?.let { CallTimingBenchmark.mark("ice_gathering_${it.name}") }

            // Track latency milestones for ICE gathering states
            p0?.let { state ->
                val milestone = when (state) {
                    PeerConnection.IceGatheringState.GATHERING -> LatencyTracker.MILESTONE_ICE_GATHERING_STARTED
                    PeerConnection.IceGatheringState.COMPLETE -> LatencyTracker.MILESTONE_ICE_GATHERING_COMPLETE
                    else -> null
                }
                milestone?.let {
                    client.latencyTracker.markCallMilestone(callId, it)
                    when (it) {
                        LatencyTracker.MILESTONE_ICE_CONNECTED -> reportV2Timing("ice_connected_ms")
                        LatencyTracker.MILESTONE_ICE_GATHERING_STARTED -> reportV2Timing("ice_gathering_started_ms")
                        LatencyTracker.MILESTONE_ICE_GATHERING_COMPLETE -> reportV2Timing("all_ice_candidates_gathered_ms")
                    }
                }
            }

            // Send end-of-candidates when ICE gathering is complete and trickle ICE is enabled.
            // During a restart it must follow the updateMedia offer, just like
            // the individual candidates.
            if (p0 == PeerConnection.IceGatheringState.COMPLETE && useTrickleIce) {
                sendOrQueueIceRestartEndOfCandidates()
            }

            p0?.let { state -> v2CaptureToken?.let { client.callReportV2?.peerState(it, "ice_gathering_state", state.name) } }
            peerConnectionObserver?.onIceGatheringChange(p0)
            // Notify debug data collector
            p0?.let { client.debugDataCollector.onIceGatheringStateChange(callId, it.name) }
        }

        override fun onIceCandidate(candidate: IceCandidate?) {
            // Count gathered candidates for the gathering safety watchdog (JS parity)
            if (candidate != null) {
                gatheredCandidatesCount++
                v2CaptureToken?.let { client.callReportV2?.candidate(it, candidate.sdp, remote = false,
                    ufrag = peerConnection?.localDescription?.description?.lineSequence()?.firstOrNull { line -> line.startsWith("a=ice-ufrag:") }?.removePrefix("a=ice-ufrag:")?.trim(),
                    // JS uses trickle || !peer.iceDone: this is eligibility, not write acceptance.
                    // Native has no authoritative non-trickle SDP-freeze latch here, so omit it.
                    signaled = true.takeIf { useTrickleIce },
                    serverUrl = candidate.serverUrl?.takeIf { url -> url.isNotBlank() },
                    adapterType = candidate.adapterType) }
            }
            // Signal that the first candidate has been received (only once)
            if (!firstCandidateReceived) {
                firstCandidateReceived = true
                firstCandidateDeferred.complete(Unit)
                // Mark benchmark milestone for first ICE candidate
                CallTimingBenchmark.markFirstCandidate()
                // Track latency milestone for first ICE candidate
                client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_FIRST_ICE_CANDIDATE)
                reportV2Timing("first_ice_candidate_ms")
                v2Logger.d(
                    tag = "Observer",
                    message = "First ICE candidate processed, completing deferred."
                )
            }

            v2Logger.d(tag = ICE_DIAG_TAG, message = {
                "Event-IceCandidate Generated: $candidate [${SystemClock.elapsedRealtime()}ms]"
            })
            candidate?.let {
                recordIceDiagnostic(
                    event = "candidate_generated",
                    fields = mapOf(
                        "candidate" to describeCandidate(it),
                        "sdpMid" to it.sdpMid,
                        "sdpMLineIndex" to it.sdpMLineIndex,
                    ),
                )
                v2Logger.d(tag = ICE_DIAG_TAG, message = {
                    "Processing ICE candidate: ${describeCandidate(it)} | sdpMid=${it.sdpMid} | sdpMLineIndex=${it.sdpMLineIndex} | sdp=${it.sdp} [${SystemClock.elapsedRealtime()}ms]"
                })
                val candidateType = extractCandidateType(it.sdp)
                if (candidateType == "srflx" || candidateType == "relay") reportV2Timing("first_srflx_or_relay_candidate_ms")
                if (useTrickleIce) {
                    var sendImmediately = false
                    candidateLock.lock()
                    try {
                        if (iceRestartModifyPending) {
                            queuedIceRestartCandidates.add(it)
                            v2Logger.d(
                                tag = "Observer",
                                message = "ICE restart candidate queued until updateMedia is sent: $it",
                            )
                        } else if (isAnswering && !answerSent) {
                            // Answering side: Queue candidate until ANSWER is sent
                            queuedCandidates.add(it)
                            v2Logger.d(
                                tag = "Observer",
                                message = "ICE candidate queued for answering side (ANSWER not sent yet): $it"
                            )
                        } else {
                            sendImmediately = true
                        }
                    } finally {
                        candidateLock.unlock()
                    }

                    if (sendImmediately) {
                        sendIceCandidate(it)
                        v2Logger.d(
                            tag = "Observer",
                            message = "ICE candidate sent via trickle ICE: $it (isAnswering=$isAnswering, answerSent=$answerSent)"
                        )
                        // Start/restart the end-of-candidates timer when sending candidates
                        startEndOfCandidatesTimer()
                    }

                    // Notify debug data collector about ICE candidate
                    val protocol = extractProtocol(it.sdp)
                    client.debugDataCollector.onIceCandidateAdded(callId, candidateType, protocol)
                    
                    // Track first server-reflexive or relay candidate
                    client.latencyTracker.markFirstSrflxRelayCandidate(callId, candidateType)

                } else {
                    // Traditional ICE: Only process if call is not ACTIVE or RENEGOTIATING yet
                    val currentCallState = client.calls[callId]?.callStateFlow?.value

                    // Allow ICE candidates when:
                    // 1. Call is NOT active (for initial invites/answers)
                    // 2. Call is in RENEGOTIATING state (for ICE restart)
                    if (currentCallState != CallState.ACTIVE ||
                        currentCallState == CallState.RENEGOTIATING
                    ) {
                        peerConnection?.addIceCandidate(it)
                        v2Logger.d(tag = "Observer", message = "ICE candidate added: $it")
                        onIceCandidateAdd?.invoke(it.serverUrl)
                        lastCandidateTime = System.currentTimeMillis()
                    } else {
                        v2Logger.d(
                            tag = "Observer",
                            message = "ICE candidate ignored - call is ACTIVE and not renegotiating"
                        )
                    }
                    peerConnectionObserver?.onIceCandidate(candidate)
                }
            }
        }

        override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {
            v2Logger.d(tag = ICE_DIAG_TAG, message = {
                "ICE Candidates Removed: $p0 [${SystemClock.elapsedRealtime()}ms]"
            })
            recordIceDiagnostic(event = "candidates_removed", fields = mapOf("count" to (p0?.size ?: 0)))
            peerConnectionObserver?.onIceCandidatesRemoved(p0)
        }

        override fun onSelectedCandidatePairChanged(event: CandidatePairChangeEvent?) {
            event?.let {
                v2Logger.d(tag = ICE_DIAG_TAG, throwable = null, message = {
                    "SELECTED PAIR CHANGED: local=${describeCandidate(it.local)} remote=${describeCandidate(it.remote)} reason=${it.reason} lastDataReceivedMs=${it.lastDataReceivedMs} estDisconnectedMs=${it.estimatedDisconnectedTimeMs} [${SystemClock.elapsedRealtime()}ms]"
                })
                recordIceDiagnostic(
                    event = "selected_pair_changed",
                    fields = mapOf(
                        "local" to describeCandidate(it.local),
                        "remote" to describeCandidate(it.remote),
                        "reason" to it.reason?.toString(),
                        "lastDataReceivedMs" to it.lastDataReceivedMs,
                        "estimatedDisconnectedTimeMs" to it.estimatedDisconnectedTimeMs,
                    ),
                )
            }
            // No forward to peerConnectionObserver: the internal PeerConnectionObserver
            // does not override onSelectedCandidatePairChanged, so the call was a
            // guaranteed no-op. Selected-pair data remains available via these logs.
        }

        override fun onAddStream(p0: MediaStream?) {
            v2Logger.d(tag = "Observer", message = "Stream Added: $p0")
            peerConnectionObserver?.onAddStream(p0)
        }

        override fun onRemoveStream(p0: MediaStream?) {
            v2Logger.d(tag = "Observer", message = "Stream Removed: $p0")
            peerConnectionObserver?.onRemoveStream(p0)
        }

        override fun onAddTrack(receiver: com.telnyx.webrtc.lib.RtpReceiver?, streams: Array<out MediaStream>?) {
            reportV2Timing("first_remote_track_ms")
            v2CaptureToken?.let { client.callReportV2?.peerState(it, "remote_track", "added") }
            peerConnectionObserver?.onAddTrack(receiver, streams)
        }

        override fun onDataChannel(p0: DataChannel?) {
            v2Logger.d(tag = "Observer", message = "Data Channel: $p0")
            peerConnectionObserver?.onDataChannel(p0)
        }

        override fun onRenegotiationNeeded() {
            v2Logger.d(tag = "Observer", message = "Renegotiation Needed")
            peerConnectionObserver?.onRenegotiationNeeded()
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            v2Logger.d(tag = ICE_DIAG_TAG, message = {
                "Peer Connection State Change: $newState [${SystemClock.elapsedRealtime()}ms]"
            })
            newState?.let { recordIceDiagnostic(event = "peer_connection_state", fields = mapOf("state" to it.name)) }
            newState?.let { state -> v2CaptureToken?.let { client.callReportV2?.peerState(it, "peer_connection_state", state.name) } }
            peerConnectionObserver?.onConnectionChange(newState)

            // Drive the recovery monitor on peer-connection FAILED.
            signalingHealthMonitor.onConnectionChange(newState)
            scheduleRecoveryTickIfNeeded()

            // Mark benchmark milestone for peer connection state changes
            newState?.let {
                CallTimingBenchmark.mark("peer_state_${it.name}")

                // Track latency milestone for peer connected
                if (it == PeerConnection.PeerConnectionState.CONNECTED) {
                    client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CONNECTED)
                    // End benchmark when peer connection reaches CONNECTED state
                    CallTimingBenchmark.end()
                }
            }

            // Structured warnings mirror the JS SDK Peer.ts behavior (VSDK-393):
            // 'disconnected' -> ICE_CONNECTIVITY_LOST, 'failed' -> PEER_CONNECTION_FAILED.
            when (newState) {
                PeerConnection.PeerConnectionState.DISCONNECTED ->
                    emitCallWarning(TelnyxWarningCode.ICE_CONNECTIVITY_LOST.code)
                PeerConnection.PeerConnectionState.FAILED ->
                    emitCallWarning(TelnyxWarningCode.PEER_CONNECTION_FAILED.code)
                else -> Unit
            }
        }
    }

    /**
     * Builds the PeerConnection with the provided IceServers from the getIceServers method
     * @see [getIceServers]
     */
    private fun buildPeerConnection(): PeerConnection? {
        val config = PeerConnection.RTCConfiguration(iceServer).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXCOMPAT
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            iceCandidatePoolSize = getIceCandidatePool()

            // Control local network access for ICE candidate gathering
            if (forceRelayCandidate) {
                iceTransportsType = PeerConnection.IceTransportsType.RELAY
            }
        }

        v2Logger.d(tag = ICE_DIAG_TAG, message = {
            "RTCConfiguration: sdpSemantics=${config.sdpSemantics} bundlePolicy=${config.bundlePolicy} continualGatheringPolicy=${config.continualGatheringPolicy} iceCandidatePoolSize=${config.iceCandidatePoolSize} iceTransportsType=${config.iceTransportsType} | iceServers=${iceServer.size} [${SystemClock.elapsedRealtime()}ms]"
        })
        recordIceDiagnostic(
            event = "rtc_configuration",
            fields = mapOf(
                "sdpSemantics" to config.sdpSemantics.toString(),
                "bundlePolicy" to config.bundlePolicy.toString(),
                "continualGatheringPolicy" to config.continualGatheringPolicy.toString(),
                "iceCandidatePoolSize" to config.iceCandidatePoolSize,
                "iceTransportsType" to config.iceTransportsType.toString(),
                "iceServerCount" to iceServer.size,
            ),
        )

        v2CaptureToken?.let { client.callReportV2?.peerConfiguration(it,
            com.telnyx.webrtc.sdk.observability.V2PeerConfiguration(forceRelayCandidate, config.iceCandidatePoolSize, iceServer.size)) }
        return peerConnectionFactory.createPeerConnection(config, observer).also {
            if (it != null) reportV2Timing("peer_setup_complete_ms")
        }
    }

    /**
     * Starts local audio capture to be used during call
     * Applies audio processing constraints (echo cancellation, noise suppression, auto gain control)
     * @param constraints Optional audio constraints to override constructor constraints
     * @see [AudioSource]
     * @see [AudioTrack]
     * @see [RtpTransceiver]
     */
    fun startLocalAudioCapture(constraints: AudioConstraints? = null) {
        v2Logger.d(tag = "Peer:Audio", message = "Attempting to start local audio capture...")

        if (localAudioTrack != null) {
            v2Logger.d(
                tag = "Peer:Audio",
                message = "Local audio capture is already active; reusing the existing track.",
            )
            return
        }

        // Use provided constraints, fall back to constructor constraints, or default to all enabled
        val effectiveConstraints = constraints ?: audioConstraints ?: AudioConstraints()
        v2Logger.d(
            tag = "Peer:Audio",
            message = "Audio constraints: echoCancellation=${effectiveConstraints.echoCancellation}, " +
                    "noiseSuppression=${effectiveConstraints.noiseSuppression}, " +
                    "autoGainControl=${effectiveConstraints.autoGainControl}"
        )

        val audioMediaConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair(
                "googEchoCancellation",
                effectiveConstraints.echoCancellation.toString()
            ))
            mandatory.add(MediaConstraints.KeyValuePair(
                "googNoiseSuppression",
                effectiveConstraints.noiseSuppression.toString()
            ))
            mandatory.add(MediaConstraints.KeyValuePair(
                "googAutoGainControl",
                effectiveConstraints.autoGainControl.toString()
            ))
        }

        val audioSource: AudioSource = peerConnectionFactory.createAudioSource(audioMediaConstraints)
        localAudioTrack = peerConnectionFactory.createAudioTrack(
            AUDIO_LOCAL_TRACK_ID,
            audioSource
        )

        if (localAudioTrack == null) {
            v2Logger.e(tag = "Peer:Audio", message = "Failed to create local audio track.")
            // Track getUserMedia failure
            client.debugDataCollector.onMicrophoneAccessError(callId, "Failed to create local audio track")
            return
        }

        localAudioTrack?.setEnabled(true)
        localAudioTrack?.setVolume(1.0)
        localAudioTrack?.let { track -> v2CaptureToken?.let { client.callReportV2?.inputTrack(it, track.enabled(), muted = runCatching {
            (client.context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager)?.isMicrophoneMute
        }.getOrNull()) } }

        // Track getUserMedia success with track details
        val track = localAudioTrack
        if (track != null) {
            client.debugDataCollector.onGetUserMediaAttempt(
                callId,
                track.id() ?: "audio_local_track",
                track.state()?.name ?: "LIVE",
                track.enabled()
            )
        }
        v2Logger.d(
            tag = "Peer:Audio",
            message = "Local audio track created. ID: ${localAudioTrack?.id()}, State: ${localAudioTrack?.state()}, Enabled: ${localAudioTrack?.enabled()}"
        )

        val localStream = peerConnectionFactory.createLocalMediaStream(AUDIO_LOCAL_STREAM_ID)
        localStream.addTrack(localAudioTrack)
        peerConnection?.addTrack(localAudioTrack)
        CallTimingBenchmark.mark("media_stream_acquired")
        
        // Mark media devices acquired milestone
        client.latencyTracker.markMediaDevicesAcquired(callId)
        reportV2Timing("media_devices_acquired_ms")
    }

    /**
     * Initiates a call, creating an offer with a local SDP
     * The offer creation is handled with an [SdpObserver]
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [SdpObserver]
     */
    private fun PeerConnection.call(sdpObserver: SdpObserver) {
        if (localAudioTrack == null) {
            v2Logger.w(
                tag = "Call",
                message = "Local audio track not initialized before creating offer."
            )
        }
        
        // Mark SDP negotiation started milestone
        client.latencyTracker.markSdpNegotiationStarted(callId)
        reportV2Timing("sdp_negotiation_started_ms")
        
        createOffer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    // Track local SDP created milestone
                    client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_LOCAL_SDP_CREATED)
                    reportV2Timing("sdp_local_created_ms")
                    
                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                v2Logger.e(
                                    tag = "Call",
                                    message = "setLocalDescription onSetFailure $p0"
                                )
                                emitCallError(TelnyxErrorCode.SDP_SET_LOCAL_DESCRIPTION_FAILED.code) // SDP_SET_LOCAL_DESCRIPTION_FAILED
                            }

                            override fun onSetSuccess() {
                                v2Logger.d(tag = "Call", message = "setLocalDescription onSetSuccess")
                                // Track local SDP set milestone
                                client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_LOCAL_SDP_SET)
                                // JS parity (VSDK-393): warn when local SDP contains
                                // only host candidates (no srflx/relay) — likely
                                // to fail connectivity outside same network.
                                warnIfOnlyHostIceCandidates()
                                reportV2Timing("local_description_applied_ms")
                                peerConnection?.localDescription?.description?.let { sdp ->
                                    v2CaptureToken?.let { client.callReportV2?.description(it, sdp, remote = false) }
                                }
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) {
                                v2Logger.d(tag = "Call", message = "createOffer onCreateSuccess")
                            }

                            override fun onCreateFailure(p0: String?) {
                                v2Logger.e(tag = "Call", message = "createOffer onCreateFailure $p0")
                                sdpObserver.onCreateFailure(p0)
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                    v2Logger.d(tag = "SDP", message = "[Local Offer SDP]:\n${desc?.description}")
                }

                override fun onCreateFailure(p0: String?) {
                    v2Logger.e(tag = "Call", message = "createOffer onCreateFailure $p0")
                    sdpObserver.onCreateFailure(p0)
                }
            },
            mediaConstraints
        )
    }

    /**
     * Answers a received invitation, creating an answer with a local SDP
     * The answer creation is handled with an [SdpObserver]
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [SdpObserver]
     */
    private fun PeerConnection.answer(sdpObserver: SdpObserver) {
        v2Logger.d(tag = "Answer", message = "Preparing to create answer...")

        v2Logger.d(
            tag = "Answer",
            message = "Adjusting transceiver directions before createAnswer..."
        )
        this.transceivers.forEach { transceiver ->
            if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) reportV2PeerMedia(transceiver)
            when (transceiver.mediaType) {
                MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO -> {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.SEND_RECV) {
                        v2Logger.w(
                            tag = "Answer",
                            message = "Audio transceiver direction was ${transceiver.direction}. Setting to SEND_RECV."
                        )
                        transceiver.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                    } else {
                        v2Logger.d(
                            tag = "Answer",
                            message = "Audio transceiver direction already SEND_RECV."
                        )
                    }
                }

                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO -> {
                    if (transceiver.direction != RtpTransceiver.RtpTransceiverDirection.INACTIVE) {
                        transceiver.direction = RtpTransceiver.RtpTransceiverDirection.INACTIVE
                        v2Logger.d(
                            tag = "Answer",
                            message = "Setting video transceiver [Mid: ${transceiver.mid}] direction to INACTIVE"
                        )
                    } else {
                        v2Logger.d(
                            tag = "Answer",
                            message = "Video transceiver [Mid: ${transceiver.mid}] direction already INACTIVE."
                        )
                    }
                }

                else -> {
                    v2Logger.d(
                        tag = "Answer",
                        message = "Ignoring transceiver with unknown media type: ${transceiver.mediaType}"
                    )
                }
            }
        }
        logAllTransceiverStates("Before createAnswer")
        
        // Mark SDP negotiation started milestone
        client.latencyTracker.markSdpNegotiationStarted(callId)
        reportV2Timing("sdp_negotiation_started_ms")

        createAnswer(
            object : SdpObserver by sdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    CallTimingBenchmark.mark("local_answer_created")
                    // Track local SDP created milestone
                    client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_LOCAL_SDP_CREATED)
                    reportV2Timing("sdp_local_created_ms")
                    logAllTransceiverStates("After createAnswer success, before setLocalDescription")

                    setLocalDescription(
                        object : SdpObserver {
                            override fun onSetFailure(p0: String?) {
                                v2Logger.e(
                                    tag = "Answer",
                                    message = "setLocalDescription onSetFailure $p0"
                                )
                                emitCallError(TelnyxErrorCode.SDP_SET_LOCAL_DESCRIPTION_FAILED.code) // SDP_SET_LOCAL_DESCRIPTION_FAILED
                            }

                            override fun onSetSuccess() {
                                v2Logger.d(
                                    tag = "Answer",
                                    message = "setLocalDescription onSetSuccess"
                                )
                                CallTimingBenchmark.mark("local_answer_sdp_set")
                                // Track local SDP set milestone
                                client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_LOCAL_SDP_SET)
                                reportV2Timing("local_description_applied_ms")
                                peerConnection?.localDescription?.description?.let { sdp ->
                                    v2CaptureToken?.let { client.callReportV2?.description(it, sdp, remote = false) }
                                }
                                logAllTransceiverStates("After setLocalDescription success")
                            }

                            override fun onCreateSuccess(p0: SessionDescription?) { /* No-op */
                            }

                            override fun onCreateFailure(p0: String?) { /* No-op */
                            }
                        },
                        desc
                    )
                    sdpObserver.onCreateSuccess(desc)
                    v2Logger.d(tag = "SDP", message = "[Local Answer SDP]:\n${desc?.description}")
                }

                override fun onCreateFailure(p0: String?) {
                    v2Logger.e(tag = "Answer", message = "createAnswer onCreateFailure $p0")
                    sdpObserver.onCreateFailure(p0)
                }
            },
            mediaConstraints
        )
    }

    private fun logAllTransceiverStates(contextTag: String) {
        if (peerConnection == null) return
        v2Logger.d(tag = "TransceiverState", message = "--- Transceiver States [$contextTag] ---")
        peerConnection?.transceivers?.forEachIndexed { index, t ->
            if (t.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) reportV2PeerMedia(t)
            v2Logger.d(
                tag = "TransceiverState",
                message = "[$contextTag] Transceiver[$index]: Mid=${t.mid}, MediaType=${t.mediaType}, Direction=${t.direction}, CurrentDirection=${t.currentDirection}, Stopped=${t.isStopped}, SenderTrack=${
                    t.sender.track()?.id()
                }, ReceiverTrack=${t.receiver.track()?.id()}"
            )
        }
        v2Logger.d(tag = "TransceiverState", message = "--- End Transceiver States [$contextTag] ---")
    }

    /**
     * Initiates an offer by setting a local SDP
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [call]
     */
    fun createOfferForSdp(sdpObserver: SdpObserver) = peerConnection?.call(sdpObserver)

    /**
     * Answers an invitation by setting a local SDP
     * @param sdpObserver, the provided [SdpObserver] that listens for SDP set events
     * @see [answer]
     */
    fun answer(sdpObserver: SdpObserver) = peerConnection?.answer(sdpObserver)

    /**
     * Sets the local SDP once a remote session has been received
     * @param sessionDescription, the provided [SessionDescription] that will attempt to be set.
     * @see [SessionDescription]
     */
    fun onRemoteSessionReceived(sessionDescription: SessionDescription) {
        v2Logger.d(
            tag = "SDP",
            message = "[Remote Offer/Answer SDP Received]:\n${sessionDescription.description}"
        )
        peerConnection?.setRemoteDescription(
            object : SdpObserver {
                override fun onSetFailure(p0: String?) {
                    client.onRemoteSessionErrorReceived(p0, v2CaptureToken)
                    v2Logger.e(
                        tag = "RemoteSessionReceived",
                        message = "Set Remote Description Failed: $p0"
                    )
                }

                override fun onSetSuccess() {
                    v2Logger.d(
                        tag = "RemoteSessionReceived",
                        message = "Set Remote Description Success"
                    )
                    // Mark different benchmarks based on SDP type
                    when (sessionDescription.type) {
                        SessionDescription.Type.OFFER -> CallTimingBenchmark.mark("remote_offer_sdp_set")
                        SessionDescription.Type.ANSWER -> CallTimingBenchmark.mark("remote_answer_sdp_set")
                        else -> CallTimingBenchmark.mark("remote_sdp_set")
                    }
                    reportV2Timing("remote_description_applied_ms")
                    v2CaptureToken?.let { client.callReportV2?.description(it, sessionDescription.description, remote = true) }
                    logAllTransceiverStates("After setRemoteDescription success")
                }

                override fun onCreateSuccess(p0: SessionDescription?) { /* No-op */
                }

                override fun onCreateFailure(p0: String?) {
                    v2Logger.e(
                        tag = "RemoteSessionReceived",
                        message = "Set Remote Description reported onCreateFailure: $p0"
                    )
                    client.onRemoteSessionErrorReceived(p0, v2CaptureToken)
                }
            },
            sessionDescription
        )
    }

    /**
     * Adds an [IceCandidate] to the [PeerConnection]
     * @param iceCandidate, the [IceCandidate] that wil bee added to the [PeerConnection]
     * @see [IceCandidate]
     */
    fun addIceCandidate(iceCandidate: IceCandidate?) {
        v2Logger.d(tag = ICE_DIAG_TAG, message = {
            "REMOTE ICE candidate added: ${describeCandidate(iceCandidate)} | sdpMid=${iceCandidate?.sdpMid} | sdp=${iceCandidate?.sdp} [${SystemClock.elapsedRealtime()}ms]"
        })
        recordIceDiagnostic(
            event = "remote_candidate_added",
            fields = mapOf(
                "candidate" to describeCandidate(iceCandidate),
                "sdpMid" to iceCandidate?.sdpMid,
            ),
        )
        // Gathering/receipt is not successful application. A missing peer or rejected
        // native add must not be recorded as an applied remote candidate.
        val accepted = peerConnection?.addIceCandidate(iceCandidate) == true
        if (accepted) iceCandidate?.let { candidate ->
            v2CaptureToken?.let { client.callReportV2?.candidate(it, candidate.sdp, remote = true) }
        }
    }

    /**
     * Sends an ICE candidate via signaling for trickle ICE
     * @param candidate the [IceCandidate] to send
     */
    private fun sendIceCandidate(candidate: IceCandidate) {
        val call = client.calls[callId]
        call?.let {
            // Clean the candidate string to remove WebRTC-specific extensions
            val cleanedCandidateString = SdpUtils.cleanCandidateString(candidate.sdp)

            val candidateMessage = SendingMessageBody(
                id = UUID.randomUUID().toString(),
                method = SocketMethod.CANDIDATE.methodName,
                params = CandidateParams(
                    sessid = it.sessionId,
                    candidate = cleanedCandidateString,
                    sdpMid = candidate.sdpMid,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    dialogParams = CandidateDialogParams(
                        callId = callId
                    )
                )
            )

            v2Logger.d(
                tag = "CandidateSend",
                message = "Sending cleaned candidate: $cleanedCandidateString"
            )
            client.socket.send(candidateMessage)
        }
    }

    /**
     * Sends end-of-candidates signal for trickle ICE
     */
    private fun sendEndOfCandidates() {
        // Prevent sending duplicate end-of-candidates signals
        if (endOfCandidatesSent) {
            v2Logger.d(tag = "EndOfCandidates", message = "Already sent, skipping duplicate")
            return
        }

        val call = client.calls[callId]
        call?.let {
            val endOfCandidatesMessage = SendingMessageBody(
                id = UUID.randomUUID().toString(),
                method = SocketMethod.END_OF_CANDIDATES.methodName,
                params = EndOfCandidatesParams(
                    sessid = it.sessionId,
                    dialogParams = CandidateDialogParams(
                        callId = callId
                    )
                )
            )
            client.socket.send(endOfCandidatesMessage)
            endOfCandidatesSent = true

            // Stop the timer since we've sent the signal
            stopEndOfCandidatesTimer()

            v2Logger.d(tag = "EndOfCandidates", message = "Signal sent successfully")
        }
    }

    /**
     * Returns the current local SDP
     *
     * Read through [withLiveConnection] (VSUP-324): a bare
     * `peerConnection?.localDescription` could race a concurrent [disconnect]
     * and call into a PeerConnection whose native object was already freed
     * (use-after-free → SIGSEGV). A Kotlin null-check alone cannot prevent this,
     * because the Java wrapper still routes the call into freed native memory.
     *
     * @return [SessionDescription] or null when the peer is being disconnected
     */
    fun getLocalDescription(): SessionDescription? =
        withLiveConnection { it.localDescription }

    /**
     * Returns the current remote SDP
     *
     * Read through [withLiveConnection], like [getLocalDescription] (VSUP-324).
     *
     * @return [SessionDescription] or null when the peer is being disconnected
     */
    fun getRemoteDescription(): SessionDescription? =
        withLiveConnection { it.remoteDescription }

    /**
     * Runs [block] against the live [PeerConnection] while guaranteeing that
     * [disconnect] cannot `dispose()` it until [block] returns (VSUP-324).
     *
     * Returns null without running [block] when the peer is disposed, being
     * disposed, or has no connection. Never blocks: if teardown currently holds
     * the write lock, the read is skipped. Keep [block] to short native calls —
     * asynchronous callbacks it registers (e.g. getStats) run after the lock is
     * released and must not touch the connection.
     */
    private fun reportV2Timing(field: String) {
        v2CaptureToken?.let { client.callReportV2?.timing(it, field) }
    }

    internal fun <T> withLiveConnection(block: (PeerConnection) -> T): T? {
        val readLock = disposeLock.readLock()
        if (!readLock.tryLock()) return null
        try {
            val connection = peerConnection
            return if (connection == null || isDisposed.get()) null else block(connection)
        } finally {
            readLock.unlock()
        }
    }

    internal fun v2CaptureEnabled(): Boolean = client.callReportV2?.collectsStats() == true

    internal var v2CaptureToken: com.telnyx.webrtc.sdk.observability.V2CaptureToken? = initialV2CaptureToken

    internal fun captureV2Stats(token: com.telnyx.webrtc.sdk.observability.V2CaptureToken,
                                report: com.telnyx.webrtc.lib.RTCStatsReport) {
        if (!v2CaptureEnabled()) return
        client.callReportV2?.stats(token, report)
        reportV2PeerMedia()
    }

    private fun reportV2PeerMedia(transceiver: RtpTransceiver? = null) {
        val token = v2CaptureToken ?: return
        val sink = client.callReportV2 ?: return
        if (!sink.collectsStats()) return
        synchronized(v2PeerSnapshotLock) {
            if (!isDisposed.get()) peerConnection?.let { connection ->
                com.telnyx.webrtc.sdk.observability.V2PeerMediaReader.snapshot(connection, localAudioTrack, transceiver)?.let {
                    sink.peerMedia(token, it)
                }
            }
        }
    }

    /**
     * Closes and disposes of current [PeerConnection]
     *
     * Idempotent: exactly one caller proceeds with the teardown (VSUP-324).
     * Overlapping teardown callers previously raced here — e.g. `endCall` →
     * `release()` while `onByeReceived` → `release()` lands on the socket
     * thread — and the second `dispose()` on an already-freed native
     * PeerConnection crashed (SIGILL in nativeFreeOwnedPeerConnection,
     * SIGBUS, or JniCommon.nativeReleaseRef during transceiver teardown).
     *
     * The reference is cleared and [isDisposed] set BEFORE `close()`, so new
     * [withLiveConnection] readers (e.g. the WebRTCReporter SIGNALING_CHANGE
     * handler, which is triggered by `close()` itself) bail out with null.
     * `dispose()` then runs under the write lock of [disposeLock], so a reader
     * that passed the guard just before teardown finishes its native call
     * before the native object is freed.
     */
    fun disconnect() {
        // Claim teardown atomically. MUST be the first statement; a caller
        // that loses this race returns without touching any teardown state.
        if (!isDisposed.compareAndSet(false, true)) {
            return
        }
        try {
            cancelFirstCandidateAwaiter()
            // Tear down the recovery monitor first so no in-flight timer
            // can reach into a disposed peer.
            recoveryTickTimer?.cancel()
            recoveryTickTimer = null
            recoveryTickIntervalMs = null
            clearIceGatheringSafetyTimeout()
            synchronized(recoveryRequestLock) {
                pendingRecoveryRequestId = null
                pendingRecoveryGeneration = null
            }
            recoveryStatsRequestInFlight.set(false)
            if (::signalingHealthMonitor.isInitialized) {
                signalingHealthMonitor.cancel("peer_disconnect")
            }
            // Clear the reference BEFORE close()/dispose() so new readers bail out.
            // close() leaves the native object alive, so it needs no lock.
            val connection = peerConnection
            peerConnection = null
            val capture = client.callReportV2
            val token = v2CaptureToken
            if (capture?.collectsStats() == true && token != null && connection != null) {
                // V2: capture one final stats snapshot before the native peer is
                // freed; teardown runs exactly once afterwards, even on timeout.
                com.telnyx.webrtc.sdk.observability.FinalStatsCapture<com.telnyx.webrtc.lib.RTCStatsReport> { timeout ->
                    val timer = java.util.Timer("telnyx-v2-final-stats", true)
                    timer.schedule(object : java.util.TimerTask() {
                        override fun run() { timeout(); timer.cancel() }
                    }, V2_FINAL_STATS_TIMEOUT_MS)
                    val cancel: () -> Unit = { timer.cancel() }
                    cancel
                }.finish(request = { callback -> connection.getStats { callback(it) } },
                    capture = { capture.peerClosing(token, it) },
                    dispose = { closeAndDispose(connection) })
            } else {
                token?.let { capture?.peerClosing(it, null) }
                connection?.let { closeAndDispose(it) }
            }
        } catch (e: IllegalStateException) {
            v2Logger.e(message = "Error during peer connection disconnect: $e")
        }
    }

    /**
     * Disposes [connection] once in-flight [withLiveConnection] readers finish.
     *
     * If they do not finish within [DISPOSE_LOCK_TIMEOUT_MS] (e.g. disconnect()
     * was invoked on the signaling thread while a reader waits on that same
     * thread), the native object is deliberately leaked: a leaked, already
     * closed PeerConnection is preferable to a use-after-free crash or a deadlock.
     */
    private fun disposeWhenNoReaders(connection: PeerConnection) {
        val writeLock = disposeLock.writeLock()
        val acquired = try {
            writeLock.tryLock(DISPOSE_LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Logger.e(message = "Interrupted while waiting to dispose peer connection: $e")
            false
        }
        if (!acquired) {
            Logger.e(message = "Peer connection readers still active; skipping dispose() to avoid use-after-free")
            return
        }
        try {
            connection.dispose()
        } finally {
            writeLock.unlock()
        }
    }

    /**
     * Closes then disposes [connection] once in-flight readers finish (VSUP-324).
     * Used by V2 final-stats teardown so the capture runs before the native
     * object is freed while keeping the same use-after-free protections.
     */
    private fun closeAndDispose(connection: PeerConnection) {
        try {
            connection.close()
        } catch (failure: IllegalStateException) {
            v2Logger.e(message = "Error closing peer after final stats: $failure")
        }
        synchronized(v2PeerSnapshotLock) {
            disposeWhenNoReaders(connection)
        }
    }

    /**
     * Sets a callback to be invoked when ICE negotiation is complete
     */
    fun setOnNegotiationComplete(callback: () -> Unit) {
        onNegotiationComplete = callback
        startNegotiationTimer()
    }

    /**
     * Starts the negotiation timer that checks for ICE candidate timeout
     */
    private fun startNegotiationTimer() {
        negotiationTimer?.cancel()
        negotiationTimer?.purge()

        negotiationTimer = Timer()
        negotiationTimer?.schedule(
            timerTask {
                val currentTime = System.currentTimeMillis()
                val timeSinceLastCandidate = currentTime - lastCandidateTime

                v2Logger.d(
                    tag = "NegotiationTimer",
                    message = "Time since last candidate: ${timeSinceLastCandidate}ms"
                )

                if (timeSinceLastCandidate >= NEGOTIATION_TIMEOUT) {
                    v2Logger.d(
                        tag = "NegotiationTimer",
                        message = "Negotiation timeout reached - Invoking onNegotiationComplete"
                    )
                    stopNegotiationTimer()
                    onNegotiationComplete?.invoke()
                }
            },
            NEGOTIATION_TIMEOUT, NEGOTIATION_TIMEOUT
        )
    }

    /**
     * Stops and cleans up the negotiation timer
     */
    private fun stopNegotiationTimer() {
        negotiationTimer?.cancel()
        negotiationTimer?.purge()
        negotiationTimer = null
        v2Logger.d(tag = "NegotiationTimer", message = "Negotiation timer stopped.")
    }

    /** Starts a monitor-owned ICE restart for one recovery generation. */
    private fun startRecoveryIceRenegotiation(recoveryGeneration: Long) {
        startIceRenegotiationInternal(recoveryGeneration)
    }

    private fun startIceRenegotiationInternal(recoveryGeneration: Long?) {
        v2Logger.d(tag = "ICE_RENEGOTIATION", message = "Starting ICE renegotiation process")

        if (isDisposed.get() || peerConnection == null) {
            recordIceRestartEvent(
                level = "error",
                message = "ICE restart failed - missing required parameters",
            )
            notifyIceRestartFailure(recoveryGeneration, "peer_unavailable")
            return
        }

        recordIceRestartEvent(level = "info", message = "ICE restart: initiated")

        // Set call state to RENEGOTIATING to allow ICE candidate processing
        client.calls[callId]?.updateCallState(CallState.RENEGOTIATING)

        val trickle = useTrickleIce
        prepareIceRestartCandidateDispatch(trickle)

        val iceRestartConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        }

        peerConnection?.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sessionDescription: SessionDescription?) {
                if (shouldIgnoreStaleRecoveryCallback(recoveryGeneration, "createOffer success")) {
                    return
                }
                sessionDescription?.let { sdp ->
                    v2Logger.d(
                        tag = "ICE_RENEGOTIATION",
                        message = "ICE restart offer created successfully"
                    )
                    peerConnection?.setLocalDescription(object : SdpObserver {
                        override fun onSetSuccess() {
                            if (shouldIgnoreStaleRecoveryCallback(
                                    recoveryGeneration,
                                    "setLocalDescription success",
                                )
                            ) {
                                return
                            }
                            v2Logger.d(
                                tag = "ICE_RENEGOTIATION",
                                message = "Local description set for ICE restart"
                            )
                            v2CaptureToken?.let { token -> peerConnection?.localDescription?.description?.let {
                                client.callReportV2?.description(token, it, remote = false)
                            } }

                            if (trickle) {
                                // Match the JS/RN flow: send the restart offer immediately,
                                // then trickle this ICE generation's candidates separately.
                                val currentSdp = peerConnection?.localDescription?.description
                                    ?: sdp.description
                                sendUpdateMediaMessage(
                                    sdp = currentSdp,
                                    recoveryGeneration = recoveryGeneration,
                                    trickle = true,
                                )
                            } else {
                                // Traditional ICE waits for candidate gathering and sends
                                // one complete SDP without the trickle field.
                                setOnNegotiationComplete {
                                    if (!isCurrentRecoveryGeneration(recoveryGeneration)) {
                                        v2Logger.d(
                                            tag = "ICE_RENEGOTIATION",
                                            message = "Ignoring negotiation completion for stale recovery generation",
                                        )
                                        return@setOnNegotiationComplete
                                    }
                                    v2Logger.d(
                                        tag = "ICE_RENEGOTIATION",
                                        message = "ICE negotiation complete for renegotiation, sending updateMedia message"
                                    )
                                    val currentSdp = peerConnection?.localDescription?.description
                                    if (currentSdp != null) {
                                        sendUpdateMediaMessage(
                                            sdp = currentSdp,
                                            recoveryGeneration = recoveryGeneration,
                                            trickle = false,
                                        )
                                    } else {
                                        v2Logger.e(
                                            tag = "ICE_RENEGOTIATION",
                                            message = "Failed to get local description after negotiation"
                                        )
                                        recordIceRestartEvent(
                                            level = "error",
                                            message = "ICE restart failed - no SDP generated",
                                        )
                                        client.calls[callId]?.updateCallState(CallState.ACTIVE)
                                        notifyIceRestartFailure(recoveryGeneration, "local_sdp_unavailable")
                                    }
                                }
                            }
                        }

                        override fun onSetFailure(error: String?) {
                            if (shouldIgnoreStaleRecoveryCallback(
                                    recoveryGeneration,
                                    "setLocalDescription failure",
                                )
                            ) {
                                return
                            }
                            v2Logger.e(
                                tag = "ICE_RENEGOTIATION",
                                message = "Failed to set local description for ICE restart: $error"
                            )
                            recordIceRestartEvent(
                                level = "error",
                                message = "ICE restart offer creation failed",
                                context = recoveryErrorContext(error),
                            )
                            // Reset call state back to ACTIVE on failure
                            client.calls[callId]?.updateCallState(CallState.ACTIVE)
                            notifyIceRestartFailure(recoveryGeneration, "set_local_description_failed")
                        }

                        override fun onCreateSuccess(p0: SessionDescription?) {
                            v2Logger.i(
                                tag = "ICE_RENEGOTIATION",
                                message = "onCreateSuccess called in setLocalDescription"
                            )
                        }

                        override fun onCreateFailure(p0: String?) {
                            if (shouldIgnoreStaleRecoveryCallback(
                                    recoveryGeneration,
                                    "setLocalDescription create failure",
                                )
                            ) {
                                return
                            }
                            v2Logger.e(
                                tag = "ICE_RENEGOTIATION",
                                message = "onCreateFailure called in setLocalDescription: $p0"
                            )
                            recordIceRestartEvent(
                                level = "error",
                                message = "ICE restart offer creation failed",
                                context = recoveryErrorContext(p0),
                            )
                            // Reset call state back to ACTIVE on failure
                            client.calls[callId]?.updateCallState(CallState.ACTIVE)
                            notifyIceRestartFailure(recoveryGeneration, "set_local_description_create_failed")
                        }
                    }, sdp)
                } ?: run {
                    recordIceRestartEvent(
                        level = "error",
                        message = "ICE restart failed - no SDP generated",
                    )
                    notifyIceRestartFailure(recoveryGeneration, "offer_sdp_unavailable")
                }
            }

            override fun onCreateFailure(error: String?) {
                if (shouldIgnoreStaleRecoveryCallback(recoveryGeneration, "createOffer failure")) {
                    return
                }
                v2Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "Failed to create ICE restart offer: $error"
                )
                recordIceRestartEvent(
                    level = "error",
                    message = "ICE restart offer creation failed",
                    context = recoveryErrorContext(error),
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
                notifyIceRestartFailure(recoveryGeneration, "create_offer_failed")
            }

            override fun onSetSuccess() {
                v2Logger.i(
                    tag = "ICE_RENEGOTIATION",
                    message = "onSetSuccess called in createOffer"
                )
            }

            override fun onSetFailure(p0: String?) {
                if (shouldIgnoreStaleRecoveryCallback(recoveryGeneration, "createOffer set failure")) {
                    return
                }
                v2Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "onSetFailure called in createOffer: $p0"
                )
                recordIceRestartEvent(
                    level = "error",
                    message = "ICE restart offer creation failed",
                    context = recoveryErrorContext(p0),
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
                notifyIceRestartFailure(recoveryGeneration, "create_offer_set_failed")
            }
        }, iceRestartConstraints)
    }

    /**
     * Sends the updateMedia modify message with the new SDP for ICE renegotiation
     */
    private fun sendUpdateMediaMessage(
        sdp: String,
        recoveryGeneration: Long?,
        trickle: Boolean,
    ) {
        if (shouldIgnoreStaleRecoveryCallback(recoveryGeneration, "updateMedia send")) {
            return
        }

        v2Logger.d(tag = "ICE_RENEGOTIATION", message = "Sending updateMedia message")

        recordIceRestartEvent(
            level = "info",
            message = "ICE restart: sending ${if (trickle) "trickle " else ""}Modify with new offer SDP",
            context = mapOf("trickle" to trickle),
        )

        // Reset call state back to ACTIVE after sending the updateMedia message
        client.calls[callId]?.updateCallState(CallState.ACTIVE)

        if (recoveryGeneration == null) {
            client.sendUpdateMediaMessage(callId, sdp, trickle.takeIf { it })
            flushQueuedIceRestartCandidatesAfterModify(trickle)
            return
        }

        val requestId = UUID.randomUUID().toString()
        synchronized(recoveryRequestLock) {
            pendingRecoveryRequestId = requestId
            pendingRecoveryGeneration = recoveryGeneration
        }
        client.sendRecoveryUpdateMediaMessage(
            callId = callId,
            sdp = sdp,
            requestId = requestId,
            trickle = trickle.takeIf { it },
        )
        flushQueuedIceRestartCandidatesAfterModify(trickle)
    }

    private fun prepareIceRestartCandidateDispatch(trickle: Boolean) {
        stopEndOfCandidatesTimer()
        candidateLock.lock()
        try {
            queuedIceRestartCandidates.clear()
            iceRestartModifyPending = trickle
            iceRestartEndOfCandidatesPending = false
            if (trickle) {
                // The initial negotiation may already have sent end-of-candidates.
                // A restart is a new ICE generation and needs its own signal.
                endOfCandidatesSent = false
            }
        } finally {
            candidateLock.unlock()
        }
    }

    private fun flushQueuedIceRestartCandidatesAfterModify(trickle: Boolean) {
        if (!trickle) return

        val candidates: List<IceCandidate>
        val shouldSendEndOfCandidates: Boolean
        candidateLock.lock()
        try {
            iceRestartModifyPending = false
            candidates = queuedIceRestartCandidates.toList()
            queuedIceRestartCandidates.clear()
            shouldSendEndOfCandidates = iceRestartEndOfCandidatesPending
            iceRestartEndOfCandidatesPending = false
        } finally {
            candidateLock.unlock()
        }

        candidates.forEach { candidate ->
            sendIceCandidate(candidate)
            startEndOfCandidatesTimer()
        }
        if (shouldSendEndOfCandidates) {
            sendEndOfCandidates()
            v2Logger.d(tag = "Observer", message = "Queued end-of-candidates sent after updateMedia")
        }
    }

    private fun sendOrQueueIceRestartEndOfCandidates() {
        var sendImmediately = false
        candidateLock.lock()
        try {
            if (iceRestartModifyPending) {
                iceRestartEndOfCandidatesPending = true
                v2Logger.d(
                    tag = "Observer",
                    message = "End-of-candidates queued until ICE restart updateMedia is sent",
                )
            } else {
                sendImmediately = true
            }
        } finally {
            candidateLock.unlock()
        }

        if (sendImmediately) {
            sendEndOfCandidates()
            v2Logger.d(tag = "Observer", message = "End-of-candidates sent via trickle ICE")
        }
    }

    private fun isCurrentRecoveryGeneration(recoveryGeneration: Long?): Boolean =
        !isDisposed.get() &&
            (recoveryGeneration == null ||
                (signalingHealthMonitor.generation() == recoveryGeneration &&
                    signalingHealthMonitor.state() == SignalingHealthMonitor.State.ICE_RESTARTING))

    private fun shouldIgnoreStaleRecoveryCallback(
        recoveryGeneration: Long?,
        callback: String,
    ): Boolean {
        if (isCurrentRecoveryGeneration(recoveryGeneration)) return false
        v2Logger.d(
            tag = "ICE_RENEGOTIATION",
            message = "Ignoring $callback for stale recovery generation=$recoveryGeneration",
        )
        return true
    }

    private fun notifyIceRestartFailure(recoveryGeneration: Long?, reason: String) {
        prepareIceRestartCandidateDispatch(trickle = false)
        if (recoveryGeneration == null) return
        signalingHealthMonitor.onIceRestartFailed(recoveryGeneration, reason)
        scheduleRecoveryTickIfNeeded()
    }

    /**
     * Handles ICE connection state transitions for automatic recovery
     *
     * @param previousState Previous ICE connection state
     * @param newState New ICE connection state
     */
    private fun handleIceConnectionStateTransition(
        previousState: PeerConnection.IceConnectionState?,
        newState: PeerConnection.IceConnectionState?
    ) {
        if (previousState == null || newState == null) {
            v2Logger.d(
                tag = "IceStateTransition",
                message = "ICE state transition: null -> $newState"
            )
            return
        }

        v2Logger.d(
            tag = "IceStateTransition",
            message = "ICE state transition: $previousState -> $newState"
        )

        // Recovery decisions are owned exclusively by SignalingHealthMonitor.
        // This method retains only audio-buffer maintenance and diagnostics.

        // Case 1: connected -> disconnected: Reset audio buffers
        if (previousState == PeerConnection.IceConnectionState.CONNECTED &&
            newState == PeerConnection.IceConnectionState.DISCONNECTED
        ) {
            v2Logger.w(
                tag = "IceStateTransition",
                message = "ICE connection disconnected - resetting audio buffers"
            )

            // Reset audio device module to clear accumulated buffers with delay
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    resetAudioDeviceModule()
                }
            }, AUDIO_BUFFER_RESET_DELAY_MS)
        }

        // Case 2: disconnected -> connected: Reset audio buffers
        if (previousState == PeerConnection.IceConnectionState.DISCONNECTED &&
            newState == PeerConnection.IceConnectionState.CONNECTED
        ) {
            v2Logger.i(
                tag = "IceStateTransition",
                message = "ICE connection restored - resetting audio buffers"
            )

            // Reset audio device module to clear accumulated buffers with delay
            Timer().schedule(object : TimerTask() {
                override fun run() {
                    resetAudioDeviceModule()
                }
            }, AUDIO_BUFFER_RESET_DELAY_MS)
        }

        // Handle connected/completed states for logging
        if (newState == PeerConnection.IceConnectionState.CONNECTED ||
            newState == PeerConnection.IceConnectionState.COMPLETED
        ) {
            logAudioTrackAndTransceiverState("onIceConnectionChange ($newState)")
        }
    }

    /**
     * Resets the audio device module to clear accumulated buffers
     * Similar to iOS implementation
     */
    private fun resetAudioDeviceModule() {
        v2Logger.d(tag = "AudioReset", message = "Resetting audio device module")

        // Stop and restart local audio capture to clear buffers
        localAudioTrack?.setEnabled(false)
        Timer().schedule(object : TimerTask() {
            override fun run() {
                localAudioTrack?.setEnabled(true)
                v2Logger.d(tag = "AudioReset", message = "Audio device module reset completed")
            }
        }, AUDIO_RE_ENABLE_DELAY_MS)
    }

    /**
     * Handles the updateMedia response containing the new remote SDP
     * This completes the ICE renegotiation process
     */
    fun handleUpdateMediaResponse(remoteSdp: String) {
        handleUpdateMediaResponse(remoteSdp, responseId = null)
    }

    internal fun handleUpdateMediaResponse(remoteSdp: String, responseId: String?) {
        v2Logger.d(
            tag = "ICE_RENEGOTIATION",
            message = "Handling updateMedia response with remote SDP"
        )

        val responseContext = synchronized(recoveryRequestLock) {
            val expectedRequestId = pendingRecoveryRequestId
            if (responseId != null && expectedRequestId != null && responseId != expectedRequestId) {
                v2Logger.w(
                    tag = "ICE_RENEGOTIATION",
                    message = "Ignoring stale updateMedia response id=$responseId expected=$expectedRequestId",
                )
                RecoveryResponseContext(generation = null, stale = true)
            } else if (expectedRequestId != null &&
                (responseId == null || responseId == expectedRequestId)
            ) {
                // Older gateways may omit the JSON-RPC id. There is no exact
                // correlation available in that case, so consume the single
                // in-flight recovery request to preserve compatibility.
                val generationAtSend = pendingRecoveryGeneration
                pendingRecoveryRequestId = null
                pendingRecoveryGeneration = null
                RecoveryResponseContext(generation = generationAtSend, stale = false)
            } else {
                RecoveryResponseContext(generation = null, stale = false)
            }
        }
        val recoveryGeneration = responseContext.generation

        if (responseContext.stale ||
            (recoveryGeneration != null && !isCurrentRecoveryGeneration(recoveryGeneration))
        ) {
            v2Logger.w(
                tag = "ICE_RENEGOTIATION",
                message = "Ignoring stale updateMedia response generation=$recoveryGeneration",
            )
            return
        }

        if (remoteSdp.isBlank()) {
            recordIceRestartEvent(
                level = "error",
                message = "ICE restart Modify response missing SDP",
            )
            notifyIceRestartFailure(recoveryGeneration, "remote_sdp_unavailable")
            return
        }

        recordIceRestartEvent(level = "info", message = "ICE restart Modify response received")

        val connection = peerConnection
        if (connection == null || isDisposed.get()) {
            recordIceRestartEvent(
                level = "error",
                message = "ICE restart remote SDP apply failed",
                context = recoveryErrorContext("Peer connection unavailable"),
            )
            notifyIceRestartFailure(recoveryGeneration, "peer_unavailable_for_remote_sdp")
        } else {
            applyRemoteIceRestartDescription(connection, remoteSdp, recoveryGeneration)
        }
    }

    private fun applyRemoteIceRestartDescription(
        connection: PeerConnection,
        remoteSdp: String,
        recoveryGeneration: Long?,
    ) {
        val remoteSessionDescription = SessionDescription(SessionDescription.Type.ANSWER, remoteSdp)
        connection.setRemoteDescription(object : SdpObserver {
            override fun onSetSuccess() {
                if (shouldIgnoreStaleRecoveryCallback(
                        recoveryGeneration,
                        "setRemoteDescription success",
                    )
                ) {
                    return
                }
                v2Logger.d(
                    tag = "ICE_RENEGOTIATION",
                    message = "ICE renegotiation completed successfully"
                )
                v2CaptureToken?.let { client.callReportV2?.description(it, remoteSdp, remote = true) }
                recordIceRestartEvent(
                    level = "info",
                    message = "ICE restart remote SDP applied",
                )
                // Ensure call state is reset to ACTIVE on successful completion
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
                // Signal the recovery monitor that the updateMedia answer
                // is applied. The monitor advances from ICE_RESTARTING
                // into VERIFYING_MEDIA (or returns to IDLE if recovery is no
                // longer the current generation).
                recoveryGeneration?.let { generationAtSend ->
                    signalingHealthMonitor.onUpdateMediaAnswerApplied(generationAtSend)
                }
                scheduleRecoveryTickIfNeeded()
            }

            override fun onSetFailure(error: String?) {
                if (shouldIgnoreStaleRecoveryCallback(
                        recoveryGeneration,
                        "setRemoteDescription failure",
                    )
                ) {
                    return
                }
                v2Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "Failed to set remote description for ICE renegotiation: $error"
                )
                recordIceRestartEvent(
                    level = "error",
                    message = "ICE restart remote SDP apply failed",
                    context = recoveryErrorContext(error),
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
                notifyIceRestartFailure(recoveryGeneration, "set_remote_description_failed")
            }

            override fun onCreateSuccess(p0: SessionDescription?) {
                v2Logger.i(
                    tag = "ICE_RENEGOTIATION",
                    message = "onCreateSuccess called in setRemoteDescription"
                )
            }

            override fun onCreateFailure(p0: String?) {
                if (shouldIgnoreStaleRecoveryCallback(
                        recoveryGeneration,
                        "setRemoteDescription create failure",
                    )
                ) {
                    return
                }
                v2Logger.e(
                    tag = "ICE_RENEGOTIATION",
                    message = "onCreateFailure called in setRemoteDescription: $p0"
                )
                recordIceRestartEvent(
                    level = "error",
                    message = "ICE restart remote SDP apply failed",
                    context = recoveryErrorContext(p0),
                )
                // Reset call state back to ACTIVE on failure
                client.calls[callId]?.updateCallState(CallState.ACTIVE)
                notifyIceRestartFailure(recoveryGeneration, "set_remote_description_create_failed")
            }
        }, remoteSessionDescription)
    }

    /** Mirrors the iOS ICE-restart lifecycle entries in the call-report logs array. */
    private fun recordIceRestartEvent(
        level: String,
        message: String,
        context: Map<String, Any> = emptyMap(),
    ) {
        client.debugDataCollector.addLogEntry(
            callId = callId,
            level = level,
            message = message,
            context = context + mapOf(
                "callId" to callId.toString(),
                "sessionId" to client.sessid,
            ),
        )
    }

    /**
     * Arms the ICE gathering safety watchdog (mirrors JS
     * `Peer.ICE_GATHERING_SAFETY_TIMEOUT_MS`, VSDK-393 parity).
     *
     * Fires a structured warning when gathering does not complete within
     * [ICE_GATHERING_SAFETY_TIMEOUT_MS]: [TelnyxWarningCode.ICE_GATHERING_EMPTY]
     * when no candidates were gathered at all, otherwise
     * [TelnyxWarningCode.ICE_GATHERING_TIMEOUT].
     */
    private fun startIceGatheringSafetyTimeout() {
        clearIceGatheringSafetyTimeout()
        // t027: anchor the measured elapsed for the warning payload.
        iceGatheringSafetyArmedElapsedMs = android.os.SystemClock.elapsedRealtime()
        iceGatheringSafetyTimer = Timer().apply {
            schedule(object : TimerTask() {
                override fun run() {
                    if (isDisposed.get()) return
                    val elapsedMs = iceGatheringSafetyArmedElapsedMs
                        ?.let { (android.os.SystemClock.elapsedRealtime() - it).toDouble() }
                    if (gatheredCandidatesCount == 0) {
                        Logger.w(
                            tag = ICE_DIAG_TAG,
                            message = "ICE gathering produced no candidates within timeout",
                        )
                        emitCallWarning(TelnyxWarningCode.ICE_GATHERING_EMPTY.code,
                            metric = "ice_gathering_ms", value = elapsedMs,
                            threshold = ICE_GATHERING_SAFETY_TIMEOUT_MS.toDouble())
                    } else {
                        Logger.w(
                            tag = ICE_DIAG_TAG,
                            message = "ICE gathering did not complete within timeout",
                        )
                        emitCallWarning(TelnyxWarningCode.ICE_GATHERING_TIMEOUT.code,
                            metric = "ice_gathering_ms", value = elapsedMs,
                            threshold = ICE_GATHERING_SAFETY_TIMEOUT_MS.toDouble())
                    }
                }
            }, ICE_GATHERING_SAFETY_TIMEOUT_MS)
        }
    }

    /** Cancels the pending ICE gathering safety watchdog, if any. */
    private fun clearIceGatheringSafetyTimeout() {
        iceGatheringSafetyTimer?.cancel()
        iceGatheringSafetyTimer = null
        iceGatheringSafetyArmedElapsedMs = null
    }

    /**
     * Mirrors JS `BaseCall._warnIfOnlyHostIceCandidates` (VSDK-393 parity):
     * fires [TelnyxWarningCode.ONLY_HOST_ICE_CANDIDATES] when the local SDP
     * has at least one `a=candidate:` line and every one of them is
     * `typ host` (no srflx/relay candidates gathered).
     */
    private fun warnIfOnlyHostIceCandidates() {
        val sdp = peerConnection?.getLocalDescription()?.description ?: return
        val candidateLines = sdp
            .split("\r\n", "\n")
            .filter { it.startsWith("a=candidate:", ignoreCase = true) }
        if (candidateLines.isEmpty()) return
        if (candidateLines.all { Regex("\\styp\\s+host(\\s|$)", RegexOption.IGNORE_CASE).containsMatchIn(it) }) {
            Logger.w(
                tag = ICE_DIAG_TAG,
                message = "Local SDP contains only host ICE candidates — connectivity may be limited",
            )
            emitCallWarning(TelnyxWarningCode.ONLY_HOST_ICE_CANDIDATES.code)
        }
    }

    /**
     * Mirrors one `ICE_DIAG` logcat line into the call report (VSUP-295).
     *
     * Keeps the existing logcat output unchanged and additionally writes an
     * aggregated, bounded entry via [DebugDataCollector.onIceDiagnostic].
     * Credential material in ICE server URLs must be redacted before it
     * reaches [fields] — see [IceDiagnosticsCollector.redactIceServerUrl].
     */
    private fun recordIceDiagnostic(event: String, fields: Map<String, Any?> = emptyMap()) {
        client.debugDataCollector.onIceDiagnostic(
            callId = callId,
            event = event,
            fields = fields,
            tsMs = SystemClock.elapsedRealtime(),
        )
    }

    private fun recoveryErrorContext(error: String?): Map<String, Any> =
        mapOf("error" to (error ?: "Unknown error"))

    /**
     * Flushes all queued ICE candidates after ANSWER is sent.
     * This is called when the answering side sends the ANSWER message.
     *
     * Atomically drains the queue and sets answerSent under candidateLock so that
     * any candidate generated by onIceCandidate() after the drain sees answerSent=true
     * and is sent immediately instead of being queued (and then lost on clear()).
     */
    internal fun flushQueuedCandidatesAfterAnswer() {
        if (!useTrickleIce || !isAnswering) {
            v2Logger.d(
                tag = "CandidateFlush",
                message = "Not flushing - trickleIce=$useTrickleIce, isAnswering=$isAnswering"
            )
            return
        }

        // Atomically drain the queue and flip answerSent so that any candidate
        // generated after this point is sent immediately by onIceCandidate().
        val toFlush: List<IceCandidate>
        candidateLock.lock()
        try {
            v2Logger.d(
                tag = "CandidateFlush",
                message = "Flushing ${queuedCandidates.size} queued candidates after ANSWER sent"
            )
            toFlush = queuedCandidates.toList()
            queuedCandidates.clear()
            answerSent = true
        } finally {
            candidateLock.unlock()
        }

        // Send the drained candidates outside the lock to avoid holding it during I/O
        toFlush.forEach { candidate ->
            sendIceCandidate(candidate)
            v2Logger.d(tag = "CandidateFlush", message = "Flushed queued candidate: $candidate")
        }

        val hadCandidates = toFlush.isNotEmpty()

        // Start the end-of-candidates timer after flushing candidates (if there were any)
        if (hadCandidates) {
            startEndOfCandidatesTimer()
        }

        v2Logger.d(tag = "CandidateFlush", message = "Candidate flush completed, answerSent = true")
    }

    /**
     * Starts the end-of-candidates timer that sends end-of-candidates signal
     * after a period of inactivity in ICE candidate discovery
     */
    private fun startEndOfCandidatesTimer() {
        // Only start timer if trickle ICE is enabled and end-of-candidates not already sent
        if (!useTrickleIce) {
            v2Logger.d(
                tag = "EndOfCandidatesTimer",
                message = "Timer not started - Trickle ICE disabled"
            )
            return
        }
        if (endOfCandidatesSent) {
            v2Logger.d(
                tag = "EndOfCandidatesTimer",
                message = "Timer not started - end-of-candidates already sent"
            )
            return
        }

        // Cancel any existing timer
        endOfCandidatesTimer?.cancel()
        endOfCandidatesTimer?.purge()

        endOfCandidatesTimer = Timer()
        endOfCandidatesTimer?.schedule(
            timerTask {
                v2Logger.d(
                    tag = "EndOfCandidatesTimer",
                    message = "Timer triggered after ${END_OF_CANDIDATES_TIMEOUT}ms of inactivity"
                )

                // Send end-of-candidates if not already sent
                if (!endOfCandidatesSent && useTrickleIce) {
                    v2Logger.d(
                        tag = "EndOfCandidatesTimer",
                        message = "Sending end-of-candidates via timer"
                    )
                    sendEndOfCandidates()
                }

                stopEndOfCandidatesTimer()
            },
            END_OF_CANDIDATES_TIMEOUT
        )

        v2Logger.d(tag = "EndOfCandidatesTimer", message = "Timer started/restarted")
    }

    /**
     * Stops and cleans up the end-of-candidates timer
     */
    private fun stopEndOfCandidatesTimer() {
        endOfCandidatesTimer?.cancel()
        endOfCandidatesTimer?.purge()
        endOfCandidatesTimer = null
        v2Logger.d(tag = "EndOfCandidatesTimer", message = "Timer stopped")
    }

    /**
     * Applies audio codec preferences to the peer connection's audio transceiver.
     * This method must be called before creating an offer or answer to ensure the
     * preferred codecs are negotiated in the correct order.
     *
     * @param preferredCodecs List of preferred audio codecs in order of preference
     */
    fun applyAudioCodecPreferences(preferredCodecs: List<AudioCodec>?) {
        if (preferredCodecs.isNullOrEmpty()) {
            v2Logger.d(tag = "CodecPreferences", message = "No codec preferences provided, using defaults")
            return
        }

        try {
            // Find the audio transceiver
            val audioTransceiver = CodecUtils.findAudioTransceiver(peerConnection?.transceivers) ?: run {
                v2Logger.w(tag = "CodecPreferences", message = "No audio transceiver found, cannot apply codec preferences")
                return
            }

            // Convert AudioCodec list directly to CodecCapability list
            val codecCapabilities = CodecUtils.convertAudioCodecsToCapabilities(preferredCodecs)
            if (codecCapabilities.isEmpty()) {
                v2Logger.w(tag = "CodecPreferences", message = "No valid codec capabilities created, using defaults")
            } else {
                // Apply codec preferences to transceiver
                audioTransceiver.setCodecPreferences(codecCapabilities)
                v2Logger.d(tag = "CodecPreferences", message = "Successfully applied codec preferences. Order: ${codecCapabilities.map { it.mimeType }}")
            }
        } catch (e: Exception) {
            v2Logger.e(tag = "CodecPreferences", message = "Error applying codec preferences: ${e.message}")
        }
    }

    /**
     * Gets the list of audio codecs that are currently available from the WebRTC peer connection.
     * This reflects the actual codecs that can be used for negotiation.
     *
     * @return List of AudioCodec objects representing available codecs, or empty list if unavailable
     */
    fun getAvailableAudioCodecs(): List<AudioCodec> {
        val audioTransceiver = CodecUtils.findAudioTransceiver(peerConnection?.transceivers)
            ?: run {
                v2Logger.w(tag = "CodecQuery", message = "No audio transceiver found")
                return emptyList()
            }

        return CodecUtils.getAvailableAudioCodecs(audioTransceiver)
    }

    /**
     * Cleans up resources when the peer is no longer needed
     */
    fun release() {
        v2Logger.d(message = "Releasing Peer resources...")
        cancelFirstCandidateAwaiter()
        stopNegotiationTimer()
        stopEndOfCandidatesTimer()
        // Clear queued candidates and reset flags
        candidateLock.lock()
        try {
            queuedCandidates.clear()
            queuedIceRestartCandidates.clear()
            answerSent = false
            iceRestartModifyPending = false
            iceRestartEndOfCandidatesPending = false
        } finally {
            candidateLock.unlock()
        }
        endOfCandidatesSent = false
        // disconnect() is idempotent (VSUP-324); this check only skips the call
        // for peers that never built a connection.
        if (peerConnection != null) {
            disconnect()
        }
    }

    private fun cancelFirstCandidateAwaiter() {
        if (!firstCandidateDeferred.isCompleted) {
            firstCandidateDeferred.cancel(
                CancellationException("Peer disposed before first ICE candidate")
            )
        }
    }

    /**
     * Requests an ICE restart through the active-call recovery coordinator.
     *
     * @return `true` when the request was accepted, or `false` when the call
     * is not active, signaling is unavailable, or recovery is already active.
     */
    fun restartIce(): Boolean {
        val started = signalingHealthMonitor.requestIceRestart()
        if (started) {
            v2Logger.i(tag = "ICE_RENEGOTIATION", message = "Application requested ICE restart")
            scheduleRecoveryTickIfNeeded()
        } else {
            v2Logger.w(tag = "ICE_RENEGOTIATION", message = "Application ICE restart request was rejected")
        }
        return started
    }

    /** Forwards every inbound signaling frame to the health monitor. */
    internal fun onSignalingMessage(message: JsonObject) {
        val idElement = if (message.has("id")) message.get("id") else null
        val messageId = if (idElement == null || idElement.isJsonNull) null else idElement.asString
        signalingHealthMonitor.onSignalingMessage(
            messageId = messageId,
            hasResult = message.has("result"),
            hasError = message.has("error"),
        )
        scheduleRecoveryTickIfNeeded()
    }

    /**
     * Extracts the candidate type from an ICE candidate SDP string.
     * Example SDP: "candidate:0 1 UDP 2122252543 192.168.1.1 54321 typ host"
     *
     * @param sdp The ICE candidate SDP string
     * @return The candidate type (host, srflx, prflx, relay) or "unknown"
     */
    private fun extractCandidateType(sdp: String): String {
        return try {
            val typIndex = sdp.indexOf(TYP_PREFIX)
            if (typIndex != -1) {
                val typeStart = typIndex + TYP_PREFIX.length
                val typeEnd = sdp.indexOf(' ', typeStart).takeIf { it != -1 } ?: sdp.length
                sdp.substring(typeStart, typeEnd)
            } else {
                UNKNOWN_VALUE
            }
        } catch (e: Exception) {
            v2Logger.d(tag = "Peer", message = "Failed to extract candidate type: ${e.message}")
            UNKNOWN_VALUE
        }
    }

    /**
     * Extracts the protocol from an ICE candidate SDP string.
     * Example SDP: "candidate:0 1 UDP 2122252543 192.168.1.1 54321 typ host"
     *
     * @param sdp The ICE candidate SDP string
     * @return The protocol (udp, tcp) or "unknown"
     */
    private fun extractProtocol(sdp: String): String {
        return try {
            val parts = sdp.split(" ")
            if (parts.size >= PROTOCOL_INDEX + 1) {
                parts[PROTOCOL_INDEX].lowercase()
            } else {
                UNKNOWN_VALUE
            }
        } catch (e: Exception) {
            v2Logger.d(tag = "Peer", message = "Failed to extract protocol: ${e.message}")
            UNKNOWN_VALUE
        }
    }

    /**
     * Initializes the Peer with the provided context and builds the PeerConnection.
     * Uses the shared PeerConnectionFactory from the companion object.
     */
    init {
        // Tests can inject a factory to exercise the real signaling wiring
        // without loading native WebRTC/EGL in the local JVM.
        if (peerConnectionFactoryOverride == null) {
            initPeerConnectionFactory(context)
        }
        peerConnection = buildPeerConnection()
        CallTimingBenchmark.mark("peer_connection_created")
        // Track peer connection created milestone
        client.latencyTracker.markCallMilestone(callId, LatencyTracker.MILESTONE_PEER_CREATED)
        reportV2Timing("peer_created_ms")
        // Reset flags when a new Peer is created
        firstCandidateReceived = false
        endOfCandidatesSent = false
        candidateLock.lock()
        try {
            answerSent = false
            queuedCandidates.clear()
        } finally {
            candidateLock.unlock()
        }
        // Construct the signaling health monitor. The adapter bridges the
        // monitor's policy decisions onto this Peer's existing
        // startRecoveryIceRenegotiation primitive without duplicating any SDP or
        // socket logic.
        signalingHealthMonitor = SignalingHealthMonitor(
            callId = callId,
            actions = peerRecoveryActions(),
            onWarning = { warningCode -> emitCallWarning(warningCode.code) },
            onError = { errorCode -> emitCallError(errorCode.code) },
        )
        scheduleRecoveryTickIfNeeded()
    }

    /**
     * Adapts this [Peer] to the [SignalingHealthMonitor.Actions] surface. Every
     * call is best-effort: if the underlying peer has been disposed or the
     * call is no longer active, the action is a no-op.
     */
    private fun peerRecoveryActions(): SignalingHealthMonitor.Actions =
        object : SignalingHealthMonitor.Actions {
            override fun isCallActive(): Boolean {
                if (isDisposed.get()) return false
                return client.calls[callId]?.callStateFlow?.value?.let {
                    it is CallState.ACTIVE || it is CallState.RENEGOTIATING
                } ?: false
            }

            override fun isSignalingAvailable(): Boolean {
                return client.isSocketConnected()
            }

            override fun startIceRestart(generation: Long) {
                if (isDisposed.get()) return
                startRecoveryIceRenegotiation(generation)
            }

            override fun sendProbePing(probeId: String): Boolean {
                if (isDisposed.get()) return false
                return client.sendRecoveryProbe(probeId)
            }

            override fun shouldForceRelayForRecovery(onResult: (Boolean) -> Unit) {
                evaluateRelayFallback(onResult)
            }

            override fun requestReattach(forceRelay: Boolean): Boolean =
                client.requestCallRecoveryReattach(callId, forceRelay)

            override fun snapshotForLog(): Map<String, String> {
                val iceState = if (isDisposed.get()) {
                    "disposed"
                } else {
                    withLiveConnection { it.iceConnectionState()?.name } ?: "null"
                }
                return mapOf("iceState" to iceState)
            }
        }

    private fun evaluateRelayFallback(onResult: (Boolean) -> Unit) {
        try {
            // VSUP-324: the native getStats() call runs under the dispose read lock.
            val requested = withLiveConnection { connection ->
                connection.getStats { report ->
                    val statistics = report.statsMap.mapValues { (_, stat) ->
                        stat.members.toMutableMap<String, Any?>().apply {
                            this["type"] = stat.type
                        }
                    }
                    val forceRelay = selectedCandidateUsesDirectVpn(statistics)
                    if (forceRelay) {
                        v2Logger.w(
                            tag = "SignalingHealthMonitor",
                            message = "Failed VPN direct path; forcing relay for replacement call",
                        )
                    }
                    onResult(forceRelay)
                }
                true
            } ?: false
            if (!requested) onResult(false)
        } catch (error: RuntimeException) {
            v2Logger.e(
                tag = "SignalingHealthMonitor",
                message = "Unable to inspect recovery candidate path: ${error.message}",
            )
            onResult(false)
        }
    }

    /** Tick interval for the monitor's current state, or null to stop ticking. */
    private fun desiredRecoveryTickIntervalMs(): Long? = when (signalingHealthMonitor.state()) {
        SignalingHealthMonitor.State.PROBING,
        SignalingHealthMonitor.State.ICE_RESTARTING,
        SignalingHealthMonitor.State.VERIFYING_MEDIA -> RECOVERY_ACTIVE_TICK_MS
        SignalingHealthMonitor.State.IDLE -> {
            if (isCallActiveForRecovery()) RECOVERY_IDLE_TICK_MS else null
        }
        SignalingHealthMonitor.State.REATTACHING -> {
            // Keep ticking only while the relay evaluation deadline runs.
            if (signalingHealthMonitor.isAwaitingRelayEvaluation()) RECOVERY_ACTIVE_TICK_MS else null
        }
    }

    /**
     * Starts the recovery tick timer if the monitor is in a watchable
     * state. Idempotent and safe to call from any state. The timer drives
     * active-call health checks as well as recovery deadlines, and tears
     * itself down when the call is no longer active or recovery is terminal.
     */
    @Synchronized
    private fun scheduleRecoveryTickIfNeeded() {
        if (isDisposed.get()) {
            recoveryTickTimer?.cancel()
            recoveryTickTimer = null
            recoveryTickIntervalMs = null
            return
        }
        val desiredIntervalMs = desiredRecoveryTickIntervalMs()

        if (recoveryTickTimer != null && recoveryTickIntervalMs != desiredIntervalMs) {
            recoveryTickTimer?.cancel()
            recoveryTickTimer = null
            recoveryTickIntervalMs = null
        }

        if (desiredIntervalMs != null && recoveryTickTimer == null) {
            // Repeating timer so the monitor's debounce / restart-timeout /
            // media-verify deadlines elapse without depending on external
            // scheduleRecoveryTickIfNeeded() calls. Cancellation is handled by
            // the null-interval branch below and the isDisposed guard above.
            recoveryTickIntervalMs = desiredIntervalMs
            recoveryTickTimer = Timer()
            recoveryTickTimer?.schedule(
                timerTask {
                    try {
                        if (!isDisposed.get()) {
                            signalingHealthMonitor.onTick()
                            sampleRecoveryInboundRtpIfNeeded()
                        }
                        // A tick may finish verification or escalate to
                        // reattach. Re-evaluate here so the repeating Timer
                        // cannot survive a terminal monitor state.
                        scheduleRecoveryTickIfNeeded()
                    } catch (e: Exception) {
                        v2Logger.e(
                            tag = "SignalingHealthMonitor",
                            message = "tick error: ${e.message}",
                        )
                    }
                },
                desiredIntervalMs,
                desiredIntervalMs,
            )
        } else if (desiredIntervalMs == null && recoveryTickTimer != null) {
            recoveryTickTimer?.cancel()
            recoveryTickTimer = null
            recoveryTickIntervalMs = null
        }
    }

    /** Keeps active-call health checks aligned with the Call lifecycle. */
    internal fun onCallStateChanged(state: CallState) {
        if (!::signalingHealthMonitor.isInitialized || isDisposed.get()) return
        when (state) {
            is CallState.DONE,
            is CallState.DROPPED,
            CallState.ERROR -> signalingHealthMonitor.cancel("terminal_call_state")
            else -> Unit
        }
        scheduleRecoveryTickIfNeeded()
    }

    /** Preempts per-peer recovery before the client reconnects for a path change. */
    internal fun onNetworkPathDidChange() {
        if (!::signalingHealthMonitor.isInitialized || isDisposed.get()) return
        signalingHealthMonitor.onNetworkPathDidChange()
        scheduleRecoveryTickIfNeeded()
    }

    private fun isCallActiveForRecovery(): Boolean =
        client.calls[callId]?.callStateFlow?.value?.let {
            it is CallState.ACTIVE || it is CallState.RENEGOTIATING
        } ?: false

    private fun sampleRecoveryInboundRtpIfNeeded() {
        val now = System.currentTimeMillis()
        val shouldSample = signalingHealthMonitor.state() == SignalingHealthMonitor.State.VERIFYING_MEDIA &&
            now - lastRecoveryRtpSampleAtMs >= RECOVERY_RTP_SAMPLE_MS &&
            recoveryStatsRequestInFlight.compareAndSet(false, true)
        if (!shouldSample) return
        lastRecoveryRtpSampleAtMs = now

        try {
            // VSUP-324: the native getStats() call runs under the dispose read lock.
            val requested = withLiveConnection { connection ->
                connection.getStats { report ->
                    try {
                        if (!isDisposed.get()) {
                            var packetsReceived = 0L
                            var foundInboundAudio = false
                            report.statsMap.values.forEach { stat ->
                                if (stat.type == "inbound-rtp") {
                                    val kind = stat.members["kind"]?.toString()
                                        ?: stat.members["mediaType"]?.toString()
                                    val packets = stat.members["packetsReceived"] as? Number
                                    if (kind == "audio" && packets != null) {
                                        packetsReceived += packets.toLong()
                                        foundInboundAudio = true
                                    }
                                }
                            }
                            if (foundInboundAudio) {
                                signalingHealthMonitor.onInboundRtpSample(packetsReceived)
                            }
                        }
                    } finally {
                        recoveryStatsRequestInFlight.set(false)
                        scheduleRecoveryTickIfNeeded()
                    }
                }
                true
            } ?: false
            if (!requested) recoveryStatsRequestInFlight.set(false)
        } catch (error: RuntimeException) {
            recoveryStatsRequestInFlight.set(false)
            v2Logger.e(
                tag = "SignalingHealthMonitor",
                message = "Unable to sample inbound RTP: ${error.message}",
            )
        }
    }
}
