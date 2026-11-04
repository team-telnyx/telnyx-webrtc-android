# Call Report V2 Android capture slice

This review slice wires real native SDK entrypoints to the V2 shared runtime without changing V1 collection or upload. It is not production V2 enablement or live backend validation.

## Local source integration

The shared V2 stack is unpublished. Normal Android artifacts do not add an unpublished Maven dependency. To review the exact shared candidate locally, pass its checkout explicitly:

```sh
./gradlew :telnyx_rtc:testDebugUnitTest \
  -PcallReportV2SharedDir=/absolute/path/to/kmp-call-report-v2-runtime
```

The explicit property enables a composite build, `src/callReportV2` and its additional tests. `AndroidV2Capture(client)` must be created before client login/call creation. It is capture-only: no telemetry socket can open and no credentials enter this adapter. Read bounded JSON-RPC captures via `capturedFrames()`; `shutdown()` detaches the observer and stops its owner. The normal SDK build leaves these entrypoints inert.

A reviewed shared artifact coordinate and dependency/distribution change are required before enabling the adapter in distributed SDKs. No package publication is performed by these Gradle tasks.

## Implemented paths

- Stable per-client `sdk_instance_id`, included in actual credential, token and anonymous signaling login only when the adapter is installed.
- Native outgoing creation, received INVITE, reattach and Call state transitions use immutable per-Call tokens. Delayed old peer callbacks cannot end a replacement call or another client's identically named call.
- Native WebRTCReporter reports are translated to shared numeric snapshot keys; opted-in collection runs at up to 1 Hz without reducing faster debug sampling. Missing and nonfinite values are omitted.
- Teardown marks the peer inaccessible to stats/recovery timers immediately, requests a final native snapshot, and closes/disposes exactly once after the callback or a 250ms fallback. No UI/signaling thread blocks. Without capture installed native teardown remains synchronous.
- Terminal payloads identify whether their totals include the before-disposal snapshot or a last-snapshot timeout fallback. Recovery peer replacement resets the metrics baseline without ending the call.
- Adapter ingress and local captures are bounded; `droppedIngress` reports rejected/failed ingress. The shared runtime still owns event sequencing, schema/sanitizer, call leases, metrics reduction and terminal cleanup.

## Tests versus runtime evidence

Tests cover actual `Call` ingress and options, shared V2 frame output across two clients sharing a call ID, delayed reattach callbacks, final-snapshot totals and terminal deduplication. Final-capture tests verify callback-before-dispose, native request failures, timeout, late callbacks and cleanup after capture failure. These are injected native ingress tests, not on-device real calls.

Not implemented by this slice: session lifecycle/socket event mapping, fresh preconfiguration push buffering, automatic/default V2 enablement, authenticated telemetry transport selection, full native call timing events, backend ingestion, physical-device resource validation, Flutter/RN runtime attachment. End reason remains `unknown` for ordinary DONE because current DONE does not distinguish local versus remote hangup; no cause is invented. More specific cause and timing mappings belong in the next integration slice.

Shared stack dependencies: [runtime #9](https://github.com/team-telnyx/telnyx-webrtc-kmp-shared-module/pull/9) and [platform sockets #10](https://github.com/team-telnyx/telnyx-webrtc-kmp-shared-module/pull/10). This capture slice requires #9; actual transport enablement remains separate.

Reference: JS PR #799 at `a853d5689d310a65283d5bd2184afde8ee16fd8b`; shared contract schema 2.1. The shared candidate must include `core.v2.V2Runtime` (the runtime continuation of KMP PR #8). No V1 code is removed.
