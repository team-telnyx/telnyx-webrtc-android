package com.telnyx.webrtc.common.model

/**
 * Enum representing the available regions for Telnyx WebRTC connections.
 */
enum class Region(val displayName: String, val value: String) {
    AUTO("AUTO", "auto"),
    EU("EU", "eu"),
    US_CENTRAL("US-CENTRAL", "us-central"),
    US_EAST("US-EAST", "us-east"),
    US_WEST("US-WEST", "us-west"),
    CA_CENTRAL("CA-CENTRAL", "ca-central"),
    APAC("APAC", "apac");

    companion object {
        fun fromDisplayName(displayName: String): Region? {
            return values().find { it.displayName == displayName }
        }

        fun fromValue(value: String): Region? {
            return values().find { it.value == value }
        }
    }
}