# DisasterManagement - Off-Grid Civil Protection and Tactical Mesh Communications Platform

> [!IMPORTANT]
> Development status: Under active development.

DisasterManagement is an Android emergency communication and civil protection platform engineered for smartphones and tablets when cellular networks, power grids, and terrestrial internet backhauls collapse.

---

## What it can do

- **Uniform packet wire invariant**:
  - Enforces a strict 4,096-byte packet invariant across all physical transports (text, GNSS beacons, triage alerts, voice slices, and optical droplets).
  - Eliminates packet-size side channels and traffic pattern analysis.

- **Hybrid post-quantum cryptography**:
  - Implements ML-KEM-768 (FIPS 203 Kyber-768) quantum-resistant key encapsulation paired with classical X25519 Elliptic Curve Diffie-Hellman.
  - HKDF-SHA256 key derivation with ChaCha20-Poly1305 and AES-256-GCM authenticated encryption.
  - Native C11 `mlock()` memory pinning locks cryptographic keys and plaintext payloads in RAM, preventing paging to swap or flash storage wear.

- **Anti-starvation tri-pool storage**:
  - Partitioned local storage prevents non-critical media from crowding out emergency signals:
    - Pool A (Life-safety distress): 50 MB hard reservation for SOS beacons and medical alerts.
    - Pool B (Encrypted tactical feed): 250 MB quota for team check-ins and hazard reports.
    - Pool C (Bulk voice and media): 200 MB quota for push-to-talk audio memos and maps.
  - Low-wear subkey shredding zeroes only ephemeral 32-byte keys upon bundle deletion, rendering payloads instantly unrecoverable without flash memory wear.

- **Multi-modal physical transport layer**:
  - Wi-Fi Hotspot and LAN mesh: Off-grid peer-to-peer synchronization over local Wi-Fi hotspots via UDP discovery beacons (Port 42424) and persistent TCP sockets (Port 42425) with 256-bit Bloom filter epidemic sync.
  - BLE mesh: Duty-cycled background scanning with L2CAP Connection-Oriented Channels and Bloom filter digest comparison.
  - Near-ultrasound acoustic modem: 4FSK audio modulation across 18.5 kHz - 19.5 kHz for short-range sound transmission between speakers and microphones.
  - Optical LT fountain transceiver: Air-gapped visual data stream displaying animated Luby Transform QR droplets at 8 FPS, reconstructed via CameraX peeling decoder.
  - Opportunistic WAN egress: Fallback Base32 Port 53 DNS tunneling and domain fronting to escape captive portals when partial uplink exists.

- **Strictly on-device tactical AI advisor**:
  - 100% on-device deterministic intelligence engine (`OnDeviceTacticalAdvisor`) with zero cloud telemetry.
  - Executes START and SALT triage classification algorithms.
  - Automated military SITREP report compilation.
  - Offline emergency survival and trauma care manuals (hemorrhage control, burn care, water purification, entrapment signals, fractures).

- **Glove-friendly tactical interface**:
  - High-contrast, large-button macros: I Am Safe, Trapped / Aid, Need Supplies, Hazard Report, Push-to-Talk, and Panic Purge.
  - Panic Purge immediately wipes cryptographic database keys, overwrites headers with noise, and terminates application memory.

---

## What it cannot do

- **Does not provide satellite communications without external hardware**: The application utilizes local device radios (Wi-Fi, Bluetooth, speaker/microphone, camera) and does not connect to satellite networks (e.g., Iridium, Starlink) without external companion hardware.
- **Acoustic ultrasonic communications have limited physical range**: The 18.5 kHz - 19.5 kHz acoustic modem is designed for short-range line-of-sight proximity (1-5 meters) and is attenuated by walls, heavy fabric, or loud ambient noise.
- **BLE mesh is not designed for continuous video streaming**: Bluetooth Low Energy bandwidth is optimized for text, coordinates, and compressed audio slices; it cannot sustain high-definition real-time video transmission.
- **Does not replace physical search-and-rescue personnel**: DisasterManagement is a communications and coordination tool; it does not deploy emergency services or substitute for professional first responders.
- **Opportunistic DNS tunneling requires an active uplink route**: Egress DNS tunneling functions only if an accessible gateway allows recursive DNS queries over UDP port 53.

---

## Glove-friendly tactical macro actions

| Tactical macro | Destination pool | Wire action |
| :--- | :--- | :--- |
| **I Am Safe** | Pool B | Broadcasts civilian status check-in with GPS coordinates |
| **Trapped / Aid** | Pool A | Broadcasts priority SOS beacon and emits 18.5 kHz ultrasonic chirp |
| **Need Supplies** | Pool B | Opens rapid selector: Water, Medical, Food, Shelter, Power, Tools |
| **Hazard Report** | Pool B | Reports immediate hazards: Live Wire, Gas Leak, Fire, Flood, Collapse |
| **Push-to-Talk** | Pool C | Encodes voice memo into 2,800-byte slices inside 4KB invariant envelopes |
| **Panic Purge** | Device Hardware | Destroys database keys, overwrites headers with noise, terminates app |

---

## Building from source

### Prerequisites
- JDK 17 (`JAVA_HOME`)
- Android SDK 34 (`ANDROID_HOME`) with CMake and NDK

### Compile and test
```bash
# Clone the repository
git clone https://github.com/Plaguedoctor-sudo/DisasterManagement.git
cd DisasterManagement

# Run unit tests
./gradlew testReleaseUnitTest

# Build signed release APK
./gradlew assembleRelease
```

---

## License

Licensed under the [Apache License 2.0](LICENSE).
