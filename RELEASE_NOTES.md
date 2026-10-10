# DisasterManagement v1.0.0 — Production Release

### 🛡️ Off-Grid Civil Protection & Tactical Disaster Communications

Initial production release of **DisasterManagement**, an off-grid civil protection and communications platform designed for Android devices when telecommunications backhauls, satellite links, and cellular towers fail.

---

### 📥 Download & Verification

* **Universal Release APK:** `DisasterManagement-v1.0.0-signed.apk` (18 MB)
* **Version:** 1.0.1 (VersionCode 2)
* **Minimum Android Version:** Android 8.0 (API Level 26)
* **Target Android Version:** Android 14.0 (API Level 34)
* **Verified Signature Scheme:** APK Signature Scheme V2 + V3 (Production Keystore)
* **SHA-256 Checksum:**
  ```text
  af31c481fd02e71059d5b2ac9b3348a26154047cf5e4c05f3d44ed476ee75927
  ```

---

### 🚀 Key Features

* **4,096-Byte Uniform Wire Invariant:** Strict wire sizing prevents metadata side-channel analysis across all packet types.
* **Hybrid Post-Quantum Cryptography:** BouncyCastle ML-KEM-768 (Kyber768) + X25519 authenticated via ChaCha20-Poly1305 and AES-256-GCM.
* **Native C11 `mlock()` Memory Pinning:** Prevents cryptographic keys and plaintext buffers from being swapped to flash.
* **Anti-Starvation Storage Isolation:** Hard quota partitioning for Pool A (Life Safety, 50MB), Pool B (Encrypted Text, 250MB), and Pool C (Audio & Media, 200MB).
* **Multi-Modal Physical Layer:**
  * Wi-Fi Hotspot & LAN mesh transceiver with UDP beacon auto-discovery (Port 42424), subnet ARP prober, and bidirectional TCP socket sync (Port 42425).
  * Bluetooth Low Energy (BLE) mesh with 256-bit Bloom filter epidemic synchronization.
  * Near-ultrasound 4FSK acoustic modem (18.5 kHz – 19.5 kHz).
  * Optical Luby Transform (LT) QR fountain stream with real-time CameraX peeling decoder.
  * Opportunistic WAN egress (Base32 Port 53 DNS tunneling & TLS domain fronting).
* **Crash-Resistant Permission Architecture:** Automated startup permission onboarding and foreground service protection ensuring stable off-grid execution on Android 10 through 14+.
* **Tactical Glove-Friendly UI:** 1-tap SOS triage action bar, Push-to-Talk audio memos, air-gapped QR group key exchange, and hardware-backed emergency panic wipe.

---

### 📦 Sideloading Instructions

```bash
adb install -r DisasterManagement-v1.0.0-signed.apk
```
Or download directly to your Android device, enable *"Install unknown apps"* in your settings, and open the APK.
