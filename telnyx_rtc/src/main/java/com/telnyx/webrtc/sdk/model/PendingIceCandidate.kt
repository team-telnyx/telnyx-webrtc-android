/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.model

import java.util.UUID

/**
 * Data class to store pending ICE candidates that arrive before remote description is set.
 *
 * @property callId The UUID of the call this candidate belongs to
 * @property sdpMid The media stream identifier
 * @property sdpMLineIndex The media line index
 * @property candidateString The raw candidate string
 * @property enhancedCandidateString The enhanced candidate string with ICE parameters (if applicable)
 */
data class PendingIceCandidate(
    val callId: UUID,
    val sdpMid: String,
    val sdpMLineIndex: Int,
    val candidateString: String,
    val enhancedCandidateString: String
)