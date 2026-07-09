# Gradle Task Cookbook

Quick reference for build, test, and quality tasks in the Telnyx Android WebRTC SDK.

## Modules

| Module | Path | Type | Description |
|---|---|---|---|
| `telnyx_rtc` | `telnyx_rtc/` | Android library | Core WebRTC SDK |
| `telnyx_common` | `telnyx_common/` | Android library | Shared utilities wrapping the SDK |
| `compose_app` | `samples/compose_app/` | Android app | Jetpack Compose sample |
| `xml_app` | `samples/xml_app/` | Android app | XML Views sample |
| `connection_service_app` | `samples/connection_service_app/` | Android app | Connection Service sample (deprecated) |

## Prerequisites

- JDK 17+ (Android Gradle Plugin 8.6.1)
- Android SDK with `compileSdk 35`
- Create a `local.properties` file at the project root and fill in SIP test credentials (sample apps need them at build time)

## Root-Level Tasks

```bash
# Clean all build outputs
./gradlew clean

# Build everything (assemble + test + lint)
./gradlew build

# Assemble all variants (no tests)
./gradlew assemble

# Assemble only debug variants
./gradlew assembleDebug

# Assemble only release variants
./gradlew assembleRelease
```

## Building the SDK

```bash
# Debug AAR
./gradlew :telnyx_rtc:assembleDebug

# Release AAR
./gradlew :telnyx_rtc:assembleRelease

# Common module
./gradlew :telnyx_common:assembleDebug
./gradlew :telnyx_common:assembleRelease
```

Output AARs land in `telnyx_rtc/build/outputs/aar/` and `telnyx_common/build/outputs/aar/`.

## Building Sample Apps

```bash
# Compose sample
./gradlew :samples:compose_app:assembleDebug
./gradlew :samples:compose_app:assembleRelease

# XML sample
./gradlew :samples:xml_app:assembleDebug
./gradlew :samples:xml_app:assembleRelease

# Connection Service sample (deprecated)
./gradlew :samples:connection_service_app:assembleDebug
```

## Installing on a Device

```bash
# Install debug APK of a sample app
./gradlew :samples:compose_app:installDebug
./gradlew :samples:xml_app:installDebug
./gradlew :samples:connection_service_app:installDebug
```

## Testing

```bash
# All unit tests across all modules
./gradlew test

# Per-module unit tests
./gradlew :telnyx_rtc:testDebugUnitTest
./gradlew :telnyx_rtc:testReleaseUnitTest
./gradlew :telnyx_common:testDebugUnitTest

# Run a specific test class
./gradlew :telnyx_rtc:testDebugUnitTest --tests "com.telnyx.webrtc.CallTest"

# Instrumented (connected-device) tests
./gradlew :telnyx_rtc:connectedDebugAndroidTest
./gradlew :samples:compose_app:connectedDebugAndroidTest
```

## Code Quality

### Detekt (static analysis — `telnyx_rtc` only)

```bash
# Run detekt on the SDK module
./gradlew :telnyx_rtc:detekt

# Detekt config lives at telnyx_rtc/config/detekt.yml
```

### Lint

```bash
# Lint all modules
./gradlew lint

# Per-module lint
./gradlew :telnyx_rtc:lintDebug
./gradlew :telnyx_rtc:lintRelease
./gradlew :telnyx_common:lintDebug
./gradlew :samples:compose_app:lintDebug
```

## Documentation

```bash
# Generate KDoc / Javadoc via Dokka (telnyx_rtc module)
./gradlew :telnyx_rtc:dokkaHtml

# Output: <project-root>/docs/
```

### ktlint (code style — all modules)

```bash
# Run ktlint on all modules
./gradlew ktlintCheck
```

## Publishing

```bash
# Publish SDK AAR to local Maven repo
./gradlew :telnyx_rtc:publishToMavenLocal

# Publish to GitHub Packages (requires github.properties credentials)
./gradlew :telnyx_rtc:publish
```

## Coverage

```bash
# Generate Kover coverage reports (root project)
./gradlew koverHtmlReport   # HTML report
./gradlew koverXmlReport     # XML report
```

## Common Combinations

```bash
# Clean + build everything
./gradlew clean build

# Quick SDK debug build + unit tests
./gradlew :telnyx_rtc:assembleDebug :telnyx_rtc:testDebugUnitTest

# Full quality gate: detekt + lint + tests
./gradlew :telnyx_rtc:detekt :telnyx_rtc:lintDebug :telnyx_rtc:testDebugUnitTest

# Build all sample APKs
./gradlew :samples:compose_app:assembleDebug :samples:xml_app:assembleDebug :samples:connection_service_app:assembleDebug
```

## Gradle Daemons

```bash
# Stop background daemons (free memory)
./gradlew --stop

# Build without daemon (slower, good for CI)
./gradlew build --no-daemon

# Show available tasks
./gradlew tasks
```
