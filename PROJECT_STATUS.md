# DisasterManagement: Project Status & Implementation Roadmap

> **Current Status:** Phase 2 Production Prototype Active  
> **Last Updated:** October 2026  
> **Target Platform:** Android 10+ (API 29+), compiled against Android 14 (API 34)  
> **Hardware Verified:** OnePlus Pad (`OPD2304`, Android 14)

---

## 1. Project Overview & Architectural Standards

**DisasterManagement** is an off-grid civil protection and tactical disaster communications platform designed for commodity Android hardware when cellular infrastructure, satellite links, and terrestrial backhauls are severed or jammed.

### Core Architectural Pillars
* **4,096-Byte Uniform Packet Invariant:** Every communication packet (text, triage beacon, audio note, optical droplet, or media slice) is padded to an invariant length of exactly 4,096 bytes to eliminate metadata leakage and packet-size side-channel traffic analysis.
* **Hybrid Post-Quantum Cryptography:** ML-KEM-768 (FIPS 203 Kyber-768) combined with classical X25519 key agreement, authenticated via ChaCha20-Poly1305 and AES-256-GCM.
* **Storage Isolation (Anti-Starvation Tri-Pool Quotas):**
  * **Pool A (Emergency Life-Safety):** 10% / 50 MB quota (priority alerts, distress beacons).
  * **Pool B (Encrypted Text & Feed):** 50% / 250 MB quota (field reports, situational feed).
  * **Pool C (Bulk Media & Voice):** 40% / 200 MB quota (voice memos, map photos, mission files).
  * Low-bandwidth life-safety telemetry can *never* be crowded out by large media chunks.
* **Low-Wear Cryptographic Shredding:** Low-wear subkey vault stores 32-byte ephemeral keys per bundle. Deleting or shredding a bundle overwrites only the 32-byte key, rendering encrypted payloads permanently unrecoverable without degrading device flash memory.
* **Native Memory Pinning:** Native C11 JNI `mlock()` prevents cryptographic keys and decrypted buffers from being paged to disk or swap storage.

---

## 2. Completed Features & Verified Implementations (What Is Done)

### A. Cryptographic & Native Core
- [x] **Native Memory Pinning (`native-crypto.c` & `NativeMemoryLock.kt`):** C11 JNI allocations using `mmap()` + `mlock()`, secure byte reading/writing, and `munlock()` + `munmap()` zeroization.
- [x] **Hybrid Post-Quantum Engine (`HybridEngine.kt`):** BouncyCastle-backed ML-KEM-768 key encapsulation + X25519 scalar agreement + HKDF-SHA256 hybrid key derivation.
- [x] **Uniform Packet Format (`EnvelopeFormat.kt`):** 4,096-byte uniform wire format with magic headers (`0xD15A`), protocol versioning, proof-of-work (PoW) validation, and rolling recipient tags.
- [x] **Double Ratchet Engine (`OutOfOrderRatchetEngine.kt`):** Out-of-order packet arrival handling with skipped-key vaults for asynchronous store-and-forward mesh topologies.

### B. Storage & Anti-Starvation Subsystem
- [x] **SQLCipher Encrypted Database (`LowWearCryptoShredder.kt`):** Transparent AES-256 encrypted database (`disaster_vault.db`) storing civil groups, feed messages, file chunks, and bundles.
- [x] **Strict Anti-Starvation Tri-Pool Partitioning:** Hard quota enforcement for Pool A (50 MB), Pool B (250 MB), and Pool C (200 MB) with FIFO eviction on overflow.
- [x] **Subkey Vault Shredding:** Ephemeral 32-byte AES-GCM subkeys stored per 4KB bundle. Shredding invalidates the subkey with random noise, cryptographically destroying the bundle without physical storage wear.
- [x] **Emergency Panic Physical Purge:** Zeroes out database keys, overwrites database headers with random noise, and terminates application process immediately.

### C. Multi-Modal Physical Transports
- [x] **BLE Mesh Transceiver (`MeshForegroundService.kt` & `BleMeshSupervisor.kt`):**
  - Persistent foreground service with emergency notification banner.
  - Duty-cycled BLE background scanning (`SCAN_MODE_LOW_POWER` / `BALANCED`).
  - L2CAP Connection-Oriented Channels (CoC) support with Bluetooth Classic RFCOMM fallback.
- [x] **Acoustic Modem (`FastFskModem.kt`):**
  - Near-ultrasound 4FSK modulation across 18.5 kHz, 18.8 kHz, 19.1 kHz, and 19.4 kHz.
  - Goertzel filter audio demodulator operating at 44.1 kHz via device microphone.
  - Hardware speaker chirping tested and verified with `0xAA55` emergency framing.
- [x] **Optical LT Fountain Transceiver (`OpticalFountainActivity.kt` & `FountainQrCodec.kt`):**
  - Zero-RF air-gapped transmitter streaming animated Luby Transform QR droplets at 8 FPS.
  - Robust Soliton degree distribution generator.
  - Peeling Gaussian elimination decoder for reconstructing 4KB invariant packets from camera/manual droplets.
- [x] **Opportunistic WAN Egress (`DnsTunnelingExfiltrator.kt` & `DomainFrontingTransport.kt`):**
  - Base32 Port 53 DNS tunneling generator for egressing through captive portals or restrictive firewalls.
  - TLS SNI domain fronting client for opportunistic backhaul connections.

### D. User Interface & High-Stress Incident Operations
- [x] **Glove-Friendly Tactical Interface (`MainActivity.kt`):**
  - High-contrast, dark-mode civil defense UI with oversized hit-boxes.
  - **1-Tap Rapid Triage Action Bar:**
    - 🟢 `I AM SAFE`: Broadcasts instant civilian check-in with GNSS coordinates to Pool B.
    - 🔴 `TRAPPED / AID`: Broadcasts critical distress alert to Pool A + near-ultrasound acoustic chirp.
    - 🟡 `NEED SUPPLIES`: Instant modal dialog for critical supply needs (Water, Medical, Food, Shelter, Power, Tools).
    - ⚠️ `HAZARD REPORT`: Instant hazard reporting (Path Blocked, Live Wire, Fire, Flood, Collapse, Gas Leak).
- [x] **Civilian Encrypted Groups & Mesh Channels (`GroupChatActivity.kt`):**
  - Group creation with 256-bit AES-GCM symmetric master keys.
  - Air-gapped QR code group key sharing (`GroupQrHelper.kt`).
  - Group leaving with instantaneous local cryptographic credential shredding (`LEAVE & SHRED`).
  - Tactical Team Incident Bar inside group chat (Check-in, Need Evacuation, Supply Request, Share GPS).
- [x] **Audio Voice Memos & PTT Subsystem:**
  - Integrated push-to-talk microphone recording (`memo_*.m4a` AAC/MP4).
  - Uniform invariant chunking: Slices audio files into 2,800-byte payloads inside 4,096-byte invariant envelopes with unique SHA-256 chunk bundle IDs.
  - Native in-app `MediaPlayer` with `FileInputStream` file descriptor playback directly in chat cards (no third-party music player required).
- [x] **Live Storage Quota Gauges (`Relays` Tab):**
  - Byte/KB/MB responsive display and progress bars updated dynamically in real-time.
  - Verified live on hardware: Pool A: `0.7 KB / 50 MB`, Pool B: `12.0 KB / 250 MB`, Pool C: `4.0 KB / 200 MB`.

---

## 3. Pending Enhancements & Application Roadmap (What Needs To Be Done)

### Priority 1: Field Hardening & Protocol Polish
- [x] **CameraX Live Scanner for LT Fountain:** Integrated native CameraX (`PreviewView` + `ImageAnalysis`) and ZXing `PlanarYUVLuminanceSource` directly to `FountainQrCodec.LTDecoder` in `OpticalFountainActivity` with automated droplet capture and matrix solving.
- [x] **Dual-Device P2P BLE Handshake & Zero-Wi-Fi Dispatch:** Integrated dynamic L2CAP PSM advertising in BLE Scan Response, persistent bi-directional Bluetooth socket streaming, and `ResilientTransportSupervisor.broadcastEverywhere()` unified multi-transport dispatcher so devices communicate off-grid over Bluetooth Low Energy with Wi-Fi and Hotspot turned off.
- [x] **Dynamic PoW Difficulty Scaling:** Integrated battery percentage and power save monitoring in `OemBatteryManager` to dynamically scale PoW difficulty between 2 bits (< 15% battery or emergency) and 4 bits (normal).

### Priority 2: Mesh Routing & Network Optimization
- [x] **Epidemic Routing with Bloom Filters:** Built `BundleBloomFilter` (256-bit / 32-byte bitset) and `MSG_BLOOM_SUMMARY` protocol exchange in `BleMeshSupervisor` to discover missing bundle IDs in only 49 bytes per handshake.
- [x] **Geographic Mesh Prioritization:** Built WGS-84 Haversine distance calculator and GNSS beacon coordinate parser in `GpsBeaconHelper` to prioritize geographically local relay bundles.
- [x] **Time Drift Consensus Synchronization (`TimeConsensusManager.kt`):** Decentralized Lamport logical clock counter and median peer clock offset tracking to prevent TTL replay attacks when devices operate off-grid.

### Priority 3: Offline GIS Mapping & Tactical Situational Awareness
- [x] **Simplified Offline Local Area Map (`TacticalLocalMapView.kt`):** Built high-contrast vector HUD canvas with concentric distance range rings (100m, 250m, 500m, 1000m), compass cardinals, survivor position, hostile threat cones, environmental hazard zones, and scavengeable survival supply points without reliance on external tile backhauls.
- [x] **Post-Apocalyptic Trapped Survivor Simulation (`SurvivorSimulationEngine.kt`):** Built interactive scenario engine modeling realistic post-apocalyptic survival challenges (dehydration, starvation, trauma, hostile SIGINT reconnaissance drone avoidance, and civil protection safehouse extraction).
- [x] **Hostile Direction-Finding & SIGINT Triangulation Evasion:** Implemented Radio Silence (Stealth Mode) suppressing BLE advertisements and Wi-Fi beacons (0m RF footprint) and 100ms Low-Probability-of-Interception (LPI) Micro-burst SOS transmissions to defeat drone signal triangulation.
- [x] **Emergency Whole-App Kill Switch (`AppStopManager.kt`):** Built one-touch stop switch terminating all BLE/Wi-Fi mesh radios, halting background discovery services, flushing encrypted vaults, and cleanly exiting the process.
- [ ] **Civil Defense Radio / APRS Audio Bridge:** Provide optional AFSK 1200 baud modem profile in `FastFskModem` for interoperability with standard VHF/UHF amateur radio transceivers.

### Priority 4: Distribution & Packaging
- [x] **Signed Release Build Pipeline:** Configured reproducible release signing with Android Keystore V1, V2, and V3 signatures.
- [ ] **Emergency Sideloading via Optical QR:** Create a high-density LT Fountain installer script to beam minimal APK bootstrap blobs across air-gapped camera feeds.

### Priority 5: Strictly Local On-Device AI & Air-Gap Enforcer
- [x] **100% On-Device Tactical Emergency Advisor (`OnDeviceTacticalAdvisor.kt`):** Zero cloud calls, zero external network dependency, and zero remote telemetry. Executes deterministic trauma triage (START/SALT protocols), instant military SITREP compilation, and extensive offline emergency medical and disaster survival manuals (hemorrhage control, water purification, entrapment tapping, burns, smoke inhalation, hypothermia, fractures) completely in local device RAM.
- [x] **Local Air-Gap Attestation Manager (`LocalAirGapManager.kt`):** Proves and audits strict air-gap compliance. Verifies zero outbound WAN exfiltration, hardware KeyStore cryptographic security, and SQLCipher AES-256 local-only database persistence.
- [x] **Offline Tactical Geo-Intelligence:** Pure mathematical on-device WGS-84 Haversine distance, compass bearing, and civil defense sector grid encoding (`GRID-DK-47-82`) without external tile or geocoding servers.
- [x] **Glove-Friendly Tactical AI Dashboard:** Interactive UI tab with rapid triage macros, survival field chips, copyable directives, and 1-tap local peer mesh broadcasting.

---

## 4. Hardware Verification Summary

| Test Area | Target Device | Status | Observations / Verification Log |
| :--- | :--- | :--- | :--- |
| **NDK `mlock()` Memory Pinning** | OnePlus Pad (`OPD2304`) | **PASSED** | Native library `libmeshcrypto.so` loaded and pinned 4,096 bytes without paging |
| **BLE Scanning & Advertising** | OnePlus Pad (`OPD2304`) | **PASSED** | `MeshForegroundService` active, duty-cycled scan registered (`scannerId=5`) |
| **Acoustic Modem 4FSK** | OnePlus Pad (`OPD2304`) | **PASSED** | `FastFskModem` synthesized and chirped emergency tones (18.5kHz - 19.5kHz) via `AudioTrack` |
| **Optical LT Fountain QR Stream** | OnePlus Pad (`OPD2304`) | **PASSED** | Animated droplet stream active at ~8 FPS, Robust Soliton degree calculation verified |
| **CameraX Live Optical Ingestion** | Local Engine | **PASSED** | CameraX 1.3.1 frame analysis pipeline wired to GF(2) Gaussian elimination solver |
| **256-Bit Bloom Filter Sync** | Local Engine | **PASSED** | 49-byte bloom handshake + Lamport clock + consensus time verified |
| **PTT Audio Memo & Slicing** | OnePlus Pad (`OPD2304`) | **PASSED** | Captured 3s AAC audio, sliced into 3 x 4KB invariant frames, stored in Pool C, verified native in-app playback |
| **Group Leave & Subkey Shred** | OnePlus Pad (`OPD2304`) | **PASSED** | Custom group wiped from SQLCipher, returning to `MainActivity` refreshed list automatically |
| **Storage Anti-Starvation Bars** | OnePlus Pad (`OPD2304`) | **PASSED** | Live gauges rendered `KB` / `MB` values across Pool A, Pool B, and Pool C |

---

## 5. Standalone APK & Release Signing Specifications

A dedicated production keystore has been provisioned to make the application installable on any Android 10+ device without developer tools or debug certificates:
* **Keystore Location:** `keystore/release.jks`
* **Key Alias:** `disastermesh`
* **Signing Protocols:** V1 (JAR), V2 (Full APK signature), and V3 (Key rotation support) enabled.
* **Universal Production APK:** [`DisasterManagement-v1.0.0-signed.apk`](file:///home/nitin/disaster%20managment/DisasterManagement-v1.0.0-signed.apk) (27 MB)
  * Can be transferred to any Android smartphone/tablet via USB, Bluetooth, SD Card, or messaging.
  * Tapping the `.apk` file installs the civil defense app directly with custom tactical shield launcher icons.

---

## 6. Developer & Build Guide

### Environment Setup
```bash
export JAVA_HOME=/home/nitin/.medscript-android-toolchain/jdk
export ANDROID_HOME=/home/nitin/.medscript-android-toolchain/sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH
```

### Build Commands
```bash
# Build Signed Production Release APK (Outputs to project root)
./gradlew assembleRelease
cp app/build/outputs/apk/release/app-release.apk ./DisasterManagement-v1.0.0-signed.apk

# Build Debug APK
./gradlew assembleDebug

# Sideload to Connected Android Device via ADB
adb install -r ./DisasterManagement-v1.0.0-signed.apk
adb shell am start -n network.disaster.management/.MainActivity
```

---

## 7. Daily Session Log (October 10, 2026)

### Accomplished Today:
1. **CameraX Live Optical Fountain Scanner (`OpticalFountainActivity.kt`):**
   - Implemented real-time CameraX preview with `ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST`.
   - Wired ZXing `PlanarYUVLuminanceSource` and `QRCodeReader` to automatically stream scanned droplets directly into the GF(2) Gaussian peeling decoder without manual text entry.
   - Added interactive HUD overlay reticle, live rank progress percentage gauge, and reset solver controls.
2. **Dynamic PoW Battery Scaling (`OemBatteryManager.kt` & `TriPoolRouter.kt`):**
   - Added battery percentage and power save mode detection.
   - Dynamically scaled Proof-of-Work difficulty to 2 bits when battery is < 15% or for emergency alerts, and 4 bits during normal power conditions.
   - Updated `TriPoolRouter` to accept dynamic difficulty thresholds >= 2 bits.
3. **256-Bit Bloom Filter Epidemic Mesh Sync (`BundleBloomFilter.kt` & `BleMeshSupervisor.kt`):**
   - Implemented 32-byte 256-bit compact Bloom filter.
   - Replaced full 1,920-byte bundle inventory dumps with compact 49-byte `MSG_BLOOM_SUMMARY` handshake frames, dramatically decreasing BLE radio duty cycles.
4. **Decentralized Time & Causality Consensus (`TimeConsensusManager.kt`):**
   - Implemented Lamport logical clocks (`tickLamport()`, `witnessLamport()`).
   - Integrated median peer time offset calculation and strict consensus TTL packet bounds checking to mitigate clock-skew replay attacks off-grid.
5. **Geographic Proximity Calculations (`GpsBeaconHelper.kt`):**
   - Implemented Great-Circle Haversine distance calculator and tactical beacon coordinate extraction.
6. **Production Release Build:**
   - Compiled, packaged, and signed updated universal release APK: [`DisasterManagement-v1.0.0-signed.apk`](file:///home/nitin/disaster%20managment/DisasterManagement-v1.0.0-signed.apk) (27 MB, APK Signature Scheme V3 verified, SHA-256: `bffb086d4d3d4430e117ded9973fa08044027924b64476ee1444dde6486a5d98`).
7. **Comprehensive Security Audit & Vulnerability Elimination:**
   - **Hardware KeyStore Master Passphrase (`VaultKeyManager.kt`):** Replaced hardcoded static DB passphrase with device-unique 256-bit AES-GCM sealed keys stored inside the Android KeyStore (TEE / StrongBox). Automatic on-the-fly migration for existing databases.
   - **Accidental Purge Prevention (`EmergencyShutdownReceiver.kt`):** Removed `panicPhysicalPurge` invocation on regular `ACTION_SHUTDOWN` events; now safely flushes and closes connection handles on reboot and only executes panic destruction upon authenticated internal panic actions.
   - **Path Traversal / Zip Slip Hardening (`MediaChunkingEngine.kt`):** Sanitized all media filenames and enforced canonical path boundary validation against `filesDir/received_media/`.
   - **Native JNI Memory Safety (`native-crypto.c`):** Added explicit array bounds verification in `writeLockedBytes` before `memcpy` to prevent out-of-bounds heap reading.
   - **DoS / Memory Exhaustion Defense (`OutOfOrderRatchetEngine.kt`):** Reduced maximum skipped ratchet keys from 2,000 to 256 and added FIFO eviction to prevent exhaustion of locked pages (`mlock`).
   - **FileProvider Over-Exposure Mitigation (`file_paths.xml`):** Restricted FileProvider permissions strictly to `mesh_media/` and `received_media/`, preventing exposure of internal database and configuration files.
   - **Network Security Configuration (`network_security_config.xml`):** Enforced `cleartextTrafficPermitted="false"` across all application network sockets.
   - **ProGuard / R8 Hardening (`proguard-rules.pro`):** Added keep rules for native JNI methods, BouncyCastle, SQLCipher, and CameraX.

