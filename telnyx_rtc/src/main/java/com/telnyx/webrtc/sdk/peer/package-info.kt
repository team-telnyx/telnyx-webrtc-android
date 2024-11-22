/**
 * # Peer Package
 *
 * Contains components for managing WebRTC peer connections and media streaming.
 *
 * ## Key Components
 *
 * ### Peer Connection
 * - [Peer]: Main class for managing WebRTC peer connections, including ICE candidates and media streams
 *
 * ### Connection Events
 * - [PeerConnectionObserver]: Handles WebRTC connection events like ICE candidates, media streams, and connection state changes
 *
 * ## Features
 * - ICE candidate management
 * - Media stream handling
 * - Connection state monitoring
 * - Audio track management
 * - Data channel support
 *
 * ## Usage Example
 * ```kotlin
 * val peer = Peer(context, client, observer)
 * peer.startLocalAudioCapture()
 * peer.createOfferForSdp(sdpObserver)
 * ```
 *
 * @see com.telnyx.webrtc.sdk.peer.Peer
 * @see com.telnyx.webrtc.sdk.peer.PeerConnectionObserver
 */
package com.telnyx.webrtc.sdk.peer