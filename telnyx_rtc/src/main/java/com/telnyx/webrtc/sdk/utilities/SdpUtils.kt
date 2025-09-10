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
        // Check if ice-options:trickle already exists
        if (sdp.contains("a=ice-options:trickle")) {
            Logger.d(tag = "SDP_Modify", message = "SDP already contains ice-options:trickle")
            return sdp
        }

        val lines = sdp.lines().toMutableList()
        
        // Find the origin line (o=) to insert ice-options after it at session level
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
}
