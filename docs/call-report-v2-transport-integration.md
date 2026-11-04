# Call Report V2 Android transport continuation

Stacks on the capture-only slice #886; depends on shared runtime #9 and native transport #10. No release or publication is performed.

## Build and defaults

Ordinary SDK builds remain V1-only. Selecting `-PcallReportV2SharedDir=/path/to/shared` explicitly links the unpublished shared source candidate and the native adapter. In that selected build, new SDK clients install V2 with JS defaults: enabled, independent telemetry socket, sanitized console mirroring. Unit fixture clients (`BuildConfig.IS_TESTING`) suppress automatic installation to avoid real external traffic.

Configure before login/call creation:

```kotlin
client.configureCallReportV2(CallReportV2Config(
    enabled = true,
    captureOnly = true,
    mirrorConsole = false,
))
```

The method returns false when the integration is not linked. Disable cancels the previous runtime without draining old-account observations. An identical configuration is a no-op and preserves active ownership. Changing modes begins a fresh reporting owner but retains the SDK instance’s monotonic sequence counter; existing call tokens are not migrated, so configure before login/call creation. Canonical production/development endpoints come from the shared selector; endpoint overrides accept loopback only for local protocol testing.

Distinct accounts require distinct TelnyxClient instances. Like the JS reference, same-instance credential correction/token refresh retains pending pre-auth events for the current credentials; tenant binding still needs live backend validation.

Credentials flow from the actual credential/token login entrypoints to the shared sender, never to capture/log callbacks. Gencred usernames use the corresponding login type. Anonymous login clears previous authenticated telemetry and remains local capture-only. Explicit client disconnect/logout suspends telemetry; transient signaling socket closure does not suspend its independent transport; subsequent authenticated login resumes it. Native ingress is bounded and fails closed on overflow so a lost account-change fact cannot keep an old account uploading new frames.

## Native events and timings

- TxSocket connect/open/close/failure, outbound send and inbound text are observed at the actual guarded entrypoints.
- Gateway, successful login/client-ready and session timing records use real native callbacks. Repeated ready notifications in one login attempt are deduplicated.
- Native-local initial sessid is not promoted to a provider session ID. Provider IDs are attached only once the backend confirms a session, including a genuine echoed client sessid; a missing-session gateway fallback is not confirmation.
- Call timing records retain observed monotonic offsets: peer creation, media acquisition, SDP negotiation/creation/application/sending, first ICE candidate, first srflx/relay candidate, ICE gathering and ICE connectivity, plus first ACTIVE and talk duration. Unknown/unobserved timing fields are omitted, not fabricated.
- Event wall times and duration endpoints are captured at callback ingress, before the bounded worker queue.

## Executable local acceptance

The focused fixture invokes the public configuration method, actual TelnyxClient credential/token/anonymous login and actual TxSocket.send. Signaling writes terminate in an injected WebSocket; the independent telemetry adapter connects through real OkHttp to a real loopback MockWebServer. The fixture verifies telemetry_login and a typed telemetry notification, raw signaling credential redaction, disable closing the authenticated socket, token reauthentication and anonymous upload revocation. This proves local protocol integration, not live calling or production backend acceptance.

Pre-client push ownership is not exposed in this Android repository; no provisional authenticated owner or imaginary preconfiguration buffer is created. Physical-device calls, actual FCM delivery, backend ingestion/rendered parity and resource measurements remain outstanding. SDK creation/device/app-state event families and timing facts with no wired native callback are not claimed as implemented here. Shared artifact distribution approval remains a separate gate.
