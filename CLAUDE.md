# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

BlockAds is a free, open-source (GPL-3.0) Android ad blocker. It blocks ads, trackers, and malware system-wide via DNS filtering through a local VPN (no root) or an iptables-based Root Proxy Mode. UI is Jetpack Compose + Material 3.

## Repository Layout

- `app/` — main Android app (Kotlin, package `app.pwhs.blockads`), pre-built Go tunnel AAR in `app/libs/tunnel.aar`
- `blockadstv/` — Android TV variant (package `app.pwhs.blockadstv`)
- `tunnel/` — Go module (`github.com/nqmgaming/blockads-tunnel`) providing the network data plane: DNS interception, userspace TCP/IP stack, HTTPS MITM filtering, scriptlets, WireGuard outbound
- `scripts/build_tunnel.sh` — builds the Go tunnel AAR via gomobile
- `.agent/skills/blockads-dev/` — deep-dive docs: VPN architecture, gomobile interop rules, root-proxy mode. **Read these before touching the tunnel or VPN service.**
- `docs/` — design notes (`backend-scriptlets.md`) and work plans (`plans/`)
- `fastlane/`, `metadata/` — F-Droid metadata and screenshots

## Build Commands

Requirements: Android Studio Ladybug+, JDK 17, Android SDK 36 (minSdk 24), Go 1.23+, gomobile (`github.com/sagernet/gomobile@v0.1.12`, then `gomobile init`).

```bash
./gradlew assembleDebug        # build debug APK (CI gate)
./gradlew lint                 # Android Lint (CI gate)
./gradlew bundleRelease        # release bundle, requires key.properties signing config
./gradlew buildGoTunnel        # rebuild tunnel.aar from tunnel/ sources (or ./scripts/build_tunnel.sh)
./gradlew testDebugUnitTest    # JVM unit tests
./gradlew testDebugUnitTest --tests "app.pwhs.blockads.VpnConflictRoutingTest"   # single test class
./gradlew connectedAndroidTest # instrumented tests (device/emulator required)
cd tunnel && go test ./...     # Go tunnel tests
```

CI (`.github/workflows/ci.yml`) runs: `buildGoTunnel` → `lint` → `assembleDebug`.

A pre-built `app/libs/tunnel.aar` is committed, so the Android build works without gomobile installed. Only rebuild the AAR when changing `tunnel/` sources.

## Architecture

Hybrid Go/Kotlin: Go handles the high-performance network data plane; Kotlin handles UI, persistence, and Android system integration. Deep documentation lives in `.agent/skills/blockads-dev/references/` (architecture.md, gomobile-interop.md, root-proxy.md).

### Go tunnel (`tunnel/`, bound via gomobile)

- `Engine` (engine.go) is the core: DNS interceptor (port 53 UDP/TCP, decoded with `github.com/miekg/dns`), domain matching via a memory-efficient Trie (`trie.go`), userspace TCP/IP stack (gVisor netstack) for HTTPS filtering/MITM, scriptlet injection, and outbound routing (direct or WireGuard).
- **DomainChecker interface**: Go calls into Kotlin for the actual blocklist decision (Kotlin uses an mmap'd Trie).
- **LogCallback interface**: Go reports every DNS query back to Kotlin, which persists to Room (`DnsLogDao`) and shows it in the UI.

### gomobile boundary constraints (strict)

Exported Go API must only use: primitives, `string`, `[]byte`, exported structs, and single-method interfaces for callbacks. **Not supported**: `uint`/`uint64`, maps, non-byte slices (`[]string`), pointers to basic types, function arguments. Pass complex data as JSON strings/bytes. See `references/gomobile-interop.md`.

**Never remove the `-extldflags=-Wl,-z,max-page-size=16384` linker flag** in `scripts/build_tunnel.sh` or the `buildGoTunnel` Gradle task — Android 15 requires 16KB page-size support.

### Two protection modes (Kotlin side)

1. **VPN Mode** — `service/AdBlockVpnService.kt`: `VpnService` captures all IP packets via a TUN fd and feeds them to the Go Engine.
2. **Root Proxy Mode** — `service/RootProxyService.kt` (requires root): starts the Go engine in standalone mode as a local DNS server on `127.0.0.1:15353`, then redirects outgoing port-53 traffic to it with iptables NAT rules via `IptablesManager`. A 10s watchdog re-applies rules. **On shutdown, `IptablesManager.teardownRules()` MUST run, or the device loses all DNS/internet.**

### Kotlin app structure

- **DI**: Koin — all bindings and ViewModels registered in `di/AppModule.kt`; Koin starts in `BlockAdsApplication`.
- **Persistence**: Room (`data/AppDatabase.kt`, entities in `data/entities/`, DAOs in `data/dao/`; schemas exported to `app/schemas/` via KSP — include a new schema JSON in migrations) + DataStore preferences (`data/datastore/AppPreferences.kt`).
- **UI**: Jetpack Compose + Material 3, Navigation 3 with typed keys (`ui/BlockAdsApp.kt`, keys in `ui/data/`). Feature pattern: `ui/<feature>/<Feature>Screen.kt` + `<Feature>ViewModel.kt` + `component/` + `dialog/`.
- **Background work**: WorkManager workers in `worker/` (filter auto-update, daily summary, profile schedules, VPN/root-proxy resume).
- **Services/receivers**: `service/` (VPN, root proxy, firewall, tile service, boot receiver), `receiver/` (Tasker), `widget/` (home screen widget).
- **Logging**: Timber; `FileLoggingTree` is planted in all builds for log export; Sentry is opt-in via `CrashReportingManager`.

## Conventions & Constraints

- Kotlin code style: `official`; JVM target 11; debug builds use applicationId suffix `.debug` (installs alongside release).
- ABI splits enabled (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` + universal).
- Builds aim to be **reproducible** (F-Droid): Sentry instrumentation/mapping upload is disabled, `libgojni.so` debug symbols are kept, no dependency-info blobs. Don't reintroduce nondeterministic build steps.
- Upstream HTTPS filtering maintains a curated passthrough list (`app/src/main/assets/https_passthrough.txt`) so banking/payment/gov apps bypass MITM — keep this in mind when changing MITM behavior.
- Translations live in `app/src/main/res/values-*/strings.xml` (default `values/strings.xml` is English); translations are managed via the csv-translator skill scripts in `.agent/skills/csv-translator/`.
