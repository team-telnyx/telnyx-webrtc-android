package com.telnyx.webrtc.sdk.utilities

import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.utilities.Logger
import java.util.UUID

/**
 * Utility object for handling ICE candidate processing and validation.
 * This object contains helper methods extracted from TelnyxClient to reduce complexity
 * and improve code organization.
 */
internal object CandidateUtils {

    /**
     * Validates that the candidate message has all required fields.
     * 
     * @param params The JSON object containing candidate parameters
     * @return true if all required fields are present, false otherwise
     */
    fun hasRequiredCandidateFields(params: JsonObject): Boolean {
        return params.has("candidate") && params.has("sdpMid") && params.has("sdpMLineIndex")
    }

    /**
     * Normalizes the candidate string by ensuring proper prefix.
     * Handles different formats that might be received from the server.
     * 
     * @param candidateString The original candidate string
     * @return The normalized candidate string with proper "candidate:" prefix
     */
    fun normalizeCandidateString(candidateString: String): String {
        return when {
            candidateString.startsWith("a=candidate:") -> {
                // Only strip "a=" not "a=candidate:"
                val normalized = candidateString.substring(2)  // Remove "a="
                Logger.d(tag = "onCandidateReceived", message = "Stripped 'a=' prefix from candidate string")
                normalized
            }
            !candidateString.startsWith("candidate:") -> {
                // If it doesn't start with "candidate:", add it
                val normalized = "candidate:$candidateString"
                Logger.d(tag = "onCandidateReceived", message = "Added 'candidate:' prefix to candidate string")
                normalized
            }
            else -> candidateString
        }
    }

    /**
     * Extracts call ID from the candidate message parameters.
     * Supports both new server format (callID in params) and legacy format (callId in dialogParams).
     * 
     * @param params The JSON object containing candidate parameters
     * @return The extracted UUID if found, null otherwise
     */
    fun extractCallIdFromCandidate(params: JsonObject): UUID? {
        var callId: UUID? = null
        
        // Try to get call ID from multiple possible locations
        
        // 1. Check directly in params for "callID" (new server format)
        if (params.has("callID")) {
            callId = try {
                val id = UUID.fromString(params.get("callID").asString)
                Logger.d(tag = "onCandidateReceived", message = "Found callID directly in params: $id")
                id
            } catch (e: Exception) {
                Logger.e(tag = "onCandidateReceived", message = "Failed to parse callID from params: ${e.message}")
                null
            }
        }
        
        // 2. Fallback to dialogParams for "callId" (legacy format) if not found yet
        if (callId == null && params.has("dialogParams")) {
            val dialogParams = params.get("dialogParams").asJsonObject
            if (dialogParams.has("callId")) {
                callId = try {
                    val id = UUID.fromString(dialogParams.get("callId").asString)
                    Logger.d(tag = "onCandidateReceived", message = "Found callId in dialogParams: $id")
                    id
                } catch (e: Exception) {
                    Logger.e(tag = "onCandidateReceived", message = "Failed to parse callId from dialogParams: ${e.message}")
                    null
                }
            }
        }
        
        return callId
    }
}
