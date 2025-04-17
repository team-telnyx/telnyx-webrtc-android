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
        var resultSdp = answerSdp // Initialize with the original answer

        try {
            val offerAudioCodecs = extractAudioCodecs(offerSdp)
            if (offerAudioCodecs.isEmpty()) {
                Logger.w(tag = "SDP_Modify", message = "No audio codecs found in Offer SDP. Returning original Answer.")
                // Proceed to return original answerSdp
            } else {
                val answerAudioCodecs = extractAudioCodecs(answerSdp)
                val missingCodecs = offerAudioCodecs.filterKeys { it !in answerAudioCodecs.keys }

                if (missingCodecs.isEmpty()) {
                    Logger.d(tag = "SDP_Modify", message = "No missing audio codecs detected. Returning original Answer.")
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
}
