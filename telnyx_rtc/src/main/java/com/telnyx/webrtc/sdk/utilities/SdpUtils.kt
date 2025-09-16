package com.telnyx.webrtc.sdk.utilities

import android.util.Log
import java.util.regex.Pattern

/**
 * Utility object for Session Description Protocol (SDP) manipulation.
 */
internal object SdpUtils {

    private const val MINIMUM_M_LINE_PARTS = 3
    private const val PAYLOAD_START_INDEX = 3
    private const val MEDIA_LINE_PREFIX = "m=audio"
    private const val ATTRIBUTE_PREFIX = "a="

    /**
     * SDP Munging function to modify the generated Answer SDP to include audio codecs from the Offer SDP
     * that might have been excluded by the WebRTC library.
     */
    internal fun modifyAnswerSdpToIncludeOfferCodecs(offerSdp: String, answerSdp: String): String {
        var resultSdp = answerSdp

        try {
            val offerAudioCodecs = extractAudioCodecs(offerSdp)
            if (offerAudioCodecs.isEmpty()) {
                Logger.w(tag = "SDP_Modify", message = "No audio codecs found in Offer SDP. Returning original Answer.")
                // Proceed to return original answerSdp
            } else {
                val answerAudioCodecs = extractAudioCodecs(answerSdp)

                // Extract the names of codecs supported in the original answer
                val supportedAnswerCodecNames = answerAudioCodecs.values
                    .mapNotNull { parseCodecName(it) }
                    .map { it.lowercase() } // Normalize to lowercase for comparison
                    .toSet()

                // Find codecs present in the offer but missing from the answer,
                // *and* whose codec name is supported by the answer.
                val missingCodecs = offerAudioCodecs.filter { (payload, offerRtpmapLine) ->
                    val offerCodecName = parseCodecName(offerRtpmapLine)?.lowercase()
                    payload !in answerAudioCodecs.keys &&
                            offerCodecName != null &&
                            offerCodecName in supportedAnswerCodecNames
                }

                if (missingCodecs.isEmpty()) {
                    Logger.d(tag = "SDP_Modify", message = "No missing codecs (matching supported types) detected. Returning original Answer.")
                    // Proceed to return original answerSdp
                } else {
                    Logger.d(tag = "SDP_Modify", message = "Missing codecs to add: ${missingCodecs.keys}")

                    val answerLines = answerSdp.lines().toMutableList()
                    val (audioMLineIndex, firstAudioAttrIndex) = findAudioIndices(answerLines)

                    if (audioMLineIndex == -1 || firstAudioAttrIndex == -1) { // Check if indices are valid
                        Logger.e(tag = "SDP_Modify", message = "Could not find m=audio line or attribute insertion point in Answer SDP. Returning original.")
                        // Proceed to return original answerSdp
                    } else {
                        val originalMLine = answerLines[audioMLineIndex]
                        val mLineParts = originalMLine.split(" ").toMutableList()

                        // Check that the m-line has at least the minimum parts required to be valid
                        if (mLineParts.size < MINIMUM_M_LINE_PARTS) {
                            Logger.w(tag = "SDP_Modify", message = "Unexpected m=audio line format: $originalMLine. It contains less than the minimum number of parts required")
                            // Proceed to return original answerSdp
                        } else {
                            // Extract the existing payloads
                            val existingPayloads = mLineParts.subList(PAYLOAD_START_INDEX, mLineParts.size).toSet()
                            val newPayloads = missingCodecs.keys.filter { it !in existingPayloads }
                            mLineParts.addAll(newPayloads)
                            answerLines[audioMLineIndex] = mLineParts.joinToString(" ")
                            Logger.d(tag = "SDP_Modify", message = "Modified m=audio line: ${answerLines[audioMLineIndex]}")

                            val rtpmapLinesToAdd = missingCodecs.values.toList()
                            // Ensure insertion index is valid, handles case where no attributes exist yet.
                            val insertionPoint = if (firstAudioAttrIndex > answerLines.size) answerLines.size else firstAudioAttrIndex
                            answerLines.addAll(insertionPoint, rtpmapLinesToAdd)
                            Logger.d(tag = "SDP_Modify", message = "Added rtpmap lines: $rtpmapLinesToAdd at index $insertionPoint")

                            resultSdp = answerLines.joinToString("\r\n") // Update resultSdp on successful modification
                        }
                    }
                }
            }
        } catch (ioobe: IndexOutOfBoundsException) {
            Logger.e(tag = "SDP_Modify", message = "Error modifying SDP due to index issue: ${ioobe.message}. Returning original Answer.")
            Logger.e(tag = "SDP_Modify", message = Log.getStackTraceString(ioobe))
            // resultSdp remains original answerSdp
        } catch (e: Exception) {
            Logger.e(tag = "SDP_Modify", message = "Error modifying SDP: ${e.message}. Returning original Answer.")
            Logger.e(tag = "SDP_Modify", message = Log.getStackTraceString(e))
            // resultSdp remains original answerSdp
        }

        return resultSdp // Single return point
    }

    /**
     * Finds the index of the 'm=audio' line and the index where new attributes should be inserted.
     * @param answerLines The lines of the Answer SDP.
     * @return A Pair containing the audio m-line index and the first attribute index (or insertion point).
     *         Returns Pair(-1, -1) if the 'm=audio' line is not found.
     */
    private fun findAudioIndices(answerLines: List<String>): Pair<Int, Int> {
        var audioMLineIndex = -1
        var firstAudioAttrIndex = -1

        for (i in answerLines.indices) {
            val line = answerLines[i]
            if (line.startsWith(MEDIA_LINE_PREFIX)) {
                audioMLineIndex = i
                // Reset attribute index for the new media section
                firstAudioAttrIndex = -1
            } else if (audioMLineIndex != -1) { // Only look for attributes after finding m=audio
                if (line.startsWith(ATTRIBUTE_PREFIX)) {
                    if (firstAudioAttrIndex == -1) {
                        // Found the first attribute line for the current m=audio section
                        firstAudioAttrIndex = i
                    }
                } else if (line.startsWith("m=")) {
                    // Found the start of the next media section before finding any attributes
                    if (firstAudioAttrIndex == -1) {
                         firstAudioAttrIndex = i // Set insertion point to the start of the next m-line
                    }
                    break // Stop searching within this audio section
                }
            }
        }

        // If we found an m=audio line but reached the end of SDP without finding attributes or another m-line
        if (audioMLineIndex != -1 && firstAudioAttrIndex == -1) {
           firstAudioAttrIndex = answerLines.size // Set insertion point to the end of the list
        }

        return Pair(audioMLineIndex, firstAudioAttrIndex)
    }

    /**
     * Extracts audio codec payload types and their corresponding a=rtpmap lines from SDP.
     */
    private fun extractAudioCodecs(sdp: String): Map<String, String> {
        val codecs = mutableMapOf<String, String>()
        val lines = sdp.lines()
        var inAudioSection = false
        val rtpmapPattern = Pattern.compile("^a=rtpmap:(\\d+)\\s+(.+)$")

        for (line in lines) {
            if (line.startsWith("m=audio")) {
                inAudioSection = true
            } else if (line.startsWith("m=")) {
                if (inAudioSection) break
            }

            if (inAudioSection && line.startsWith("a=rtpmap:")) {
                val matcher = rtpmapPattern.matcher(line)
                if (matcher.find()) {
                    val payloadType = matcher.group(1)
                    if (payloadType != null) {
                        codecs[payloadType] = line
                    }
                }
            }
        }
        return codecs
    }

    /**
     * Parses the codec name (e.g., "opus", "PCMU") from an a=rtpmap line.
     * Example: "a=rtpmap:102 opus/48000/2" -> "opus"
     */
    private fun parseCodecName(rtpmapLine: String): String? {
        // Regex to capture payload type and encoding name
        // Format: a=rtpmap:<payload> <encodingName>/<clockRate>[/<encodingParameters>]
        val pattern = Pattern.compile("""^a=rtpmap:(\d+)\s+([\w-]+)/.*$""")
        val matcher = pattern.matcher(rtpmapLine)
        return if (matcher.find() && matcher.groupCount() >= 2) {
            matcher.group(2) // Group 2 is the encoding name
        } else {
            null
        }
    }

    /**
     * Adds the trickle ICE capability to an SDP if not already present.
     * This adds "a=ice-options:trickle" at the session level after the origin (o=) line.
     * 
     * @param sdp The original SDP string
     * @return The modified SDP with ice-options:trickle added
     */
    internal fun addTrickleIceCapability(sdp: String): String {
        val lines = sdp.lines().toMutableList()

        // Check if there's an existing ice-options line that needs modification
        for (i in lines.indices) {
            if (lines[i].startsWith("a=ice-options:")) {
                val currentOptions = lines[i]

                // If it already contains just "trickle", we're done
                if (currentOptions == "a=ice-options:trickle") {
                    Logger.d(tag = "SDP_Modify", message = "SDP already contains a=ice-options:trickle")
                    return sdp
                }

                // Replace any ice-options line with just trickle
                // This handles cases like "a=ice-options:trickle renomination"
                lines[i] = "a=ice-options:trickle"
                Logger.d(tag = "SDP_Modify", message = "Replaced ice-options line from '$currentOptions' to 'a=ice-options:trickle'")
                return lines.joinToString("\r\n")
            }
        }

        // If no ice-options line exists, find the origin line (o=) to insert after it
        var insertIndex = -1
        for (i in lines.indices) {
            if (lines[i].startsWith("o=")) {
                // Insert after the origin line
                insertIndex = i + 1
                break
            }
        }

        if (insertIndex != -1) {
            // Insert ice-options:trickle at session level
            lines.add(insertIndex, "a=ice-options:trickle")
            Logger.d(tag = "SDP_Modify", message = "Added a=ice-options:trickle to SDP at index $insertIndex")
            return lines.joinToString("\r\n")
        } else {
            Logger.w(tag = "SDP_Modify", message = "Could not find origin line in SDP, returning original")
            return sdp
        }
    }

    /**
     * Checks if an SDP contains trickle ICE capability.
     * 
     * @param sdp The SDP string to check
     * @return true if the SDP advertises trickle ICE support
     */
    internal fun hasTrickleIceCapability(sdp: String): Boolean {
        return sdp.contains("a=ice-options:trickle")
    }

    /**
     * Data class to hold ICE parameters extracted from SDP
     */
    data class IceParameters(
        val ufrag: String? = null,
        val pwd: String? = null,
        val generation: Int = 0
    )

    /**
     * Extracts ICE parameters (ufrag, pwd) from an SDP string.
     * These parameters can be used to enhance ICE candidates for consistency.
     * 
     * @param sdp The SDP string to parse
     * @return IceParameters containing extracted values, or default values if not found
     */
    internal fun extractIceParameters(sdp: String): IceParameters {
        val lines = sdp.lines()
        var ufrag: String? = null
        var pwd: String? = null
        var inAudioSection = false
        
        for (line in lines) {
            when {
                line.startsWith("m=audio") -> {
                    inAudioSection = true
                }
                line.startsWith("m=") && !line.startsWith("m=audio") -> {
                    // If we found values in audio section, stop looking
                    if (inAudioSection && ufrag != null && pwd != null) break
                    inAudioSection = false
                }
                line.startsWith("a=ice-ufrag:") -> {
                    val extractedUfrag = line.substringAfter("a=ice-ufrag:").trim()
                    if (inAudioSection || ufrag == null) {
                        ufrag = extractedUfrag
                    }
                }
                line.startsWith("a=ice-pwd:") -> {
                    val extractedPwd = line.substringAfter("a=ice-pwd:").trim()
                    if (inAudioSection || pwd == null) {
                        pwd = extractedPwd
                    }
                }
            }
        }
        
        Logger.d(tag = "SDP_Ice", message = "Extracted ICE parameters - ufrag: $ufrag, pwd: $pwd")
        return IceParameters(ufrag = ufrag, pwd = pwd, generation = 0)
    }

    /**
     * Enhances an ICE candidate string with additional parameters for consistency.
     * Adds ufrag, generation, and placeholder network parameters if not present.
     * 
     * @param candidateString The original candidate string
     * @param iceParameters The ICE parameters extracted from SDP
     * @return Enhanced candidate string with additional parameters
     */
    internal fun enhanceCandidateString(candidateString: String, iceParameters: IceParameters): String {
        // Check if the candidate already has these parameters
        if (candidateString.contains("ufrag") || candidateString.contains("generation")) {
            Logger.d(tag = "CandidateEnhance", message = "Candidate already contains extensions, not enhancing")
            return candidateString
        }
        
        val enhancedCandidate = StringBuilder(candidateString)
        
        // Add generation (always 0 for trickle ICE)
        enhancedCandidate.append(" generation ${iceParameters.generation}")
        
        // Add ufrag if available
        iceParameters.ufrag?.let { ufrag ->
            enhancedCandidate.append(" ufrag $ufrag")
        }
        
        // Add placeholder network parameters for consistency with local candidates
        enhancedCandidate.append(" network-id 1 network-cost 10")
        
        Logger.d(tag = "CandidateEnhance", message = "Enhanced candidate: $enhancedCandidate")
        return enhancedCandidate.toString()
    }

    /**
     * Cleans an ICE candidate string to remove WebRTC-specific extensions.
     * Extracts only the RFC 5245/8838 standard fields from a WebRTC candidate string.
     * 
     * Standard format: candidate:<foundation> <component> <transport> <priority> <IP address> <port> <candidate-type> [rel-addr <IP>] [rel-port <port>]
     * 
     * @param candidateString The raw candidate string from WebRTC (e.g., from IceCandidate.sdp)
     * @return The cleaned candidate string with only RFC-compliant fields
     */
    internal fun cleanCandidateString(candidateString: String): String {
        Logger.d(tag = "CandidateClean", message = "Original candidate: $candidateString")

        // Split the candidate string into parts
        val parts = candidateString.trim().split(" ")
        
        if (parts.isEmpty() || !parts[0].startsWith("candidate:")) {
            Logger.w(tag = "CandidateClean", message = "Invalid candidate format: $candidateString")
            return candidateString // Return original if format is unexpected
        }
        
        val cleanedParts = mutableListOf<String>()
        var i = 0
        
        // Process standard fields in order
        while (i < parts.size) {
            val part = parts[i]
            
            when {
                // Foundation (candidate:...)
                part.startsWith("candidate:") -> {
                    cleanedParts.add(part)
                    i++
                }
                // Component (1 or 2)
                i == 1 && part.matches(Regex("\\d+")) -> {
                    cleanedParts.add(part)
                    i++
                }
                // Transport (udp, tcp)
                i == 2 && (part.equals("udp", ignoreCase = true) || part.equals("tcp", ignoreCase = true)) -> {
                    cleanedParts.add(part)
                    i++
                }
                // Priority (numeric)
                i == 3 && part.matches(Regex("\\d+")) -> {
                    cleanedParts.add(part)
                    i++
                }
                // IP address (IPv4 or IPv6)
                i == 4 -> {
                    cleanedParts.add(part)
                    i++
                }
                // Port (numeric)
                i == 5 && part.matches(Regex("\\d+")) -> {
                    cleanedParts.add(part)
                    i++
                }
                // Candidate type (typ)
                part == "typ" && i == 6 -> {
                    cleanedParts.add(part)
                    i++
                    // Add the actual type (host, srflx, prflx, relay)
                    if (i < parts.size) {
                        cleanedParts.add(parts[i])
                        i++
                    }
                }
                // Related address (raddr)
                part == "raddr" -> {
                    cleanedParts.add(part)
                    i++
                    // Add the related address value
                    if (i < parts.size) {
                        cleanedParts.add(parts[i])
                        i++
                    }
                }
                // Related port (rport)
                part == "rport" -> {
                    cleanedParts.add(part)
                    i++
                    // Add the related port value
                    if (i < parts.size) {
                        cleanedParts.add(parts[i])
                        i++
                    }
                }
                // Skip WebRTC-specific extensions
                part == "generation" || part == "ufrag" || part == "network-id" || part == "network-cost" -> {
                    Logger.d(tag = "CandidateClean", message = "Skipping WebRTC extension: $part")
                    i++
                    // Skip the value that follows
                    if (i < parts.size && !parts[i].contains("=") && !parts[i].startsWith("candidate:")) {
                        Logger.d(tag = "CandidateClean", message = "Skipping extension value: ${parts[i]}")
                        i++
                    }
                }
                else -> {
                    // Skip unknown extensions
                    Logger.d(tag = "CandidateClean", message = "Skipping unknown field: $part")
                    i++
                }
            }
        }
        
        val cleanedCandidate = cleanedParts.joinToString(" ")
        Logger.d(tag = "CandidateClean", message = "Cleaned candidate: $cleanedCandidate")
        
        return cleanedCandidate
    }
}
