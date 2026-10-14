# 🛡️ Operator Field Manual: DisasterManagement v1.2.0
**For Field Coordinators, UN ETC, TSF, Disaster Tech Lab, IRC Operatives & Civil Defense**

---

## 1. Tactical Mission & Verification
This package contains production release `v1.2.0` of the DisasterManagement tactical mesh communication platform, designed for environments under total infrastructural collapse, siege warfare, or acute disaster conditions.

### Package Manifest & Checksums:
* **APK Binary:** `DisasterManagement-v1.2.0-signed.apk`
* **File Size:** ~18.7 MB
* **SHA-256:** `a270f475fa57b36f93fd62ae93993f3f4b7dfdfd3191af039e5d03afedf30d05`
* **Target OS:** Android 10+ (API 29 to 35)

---

## 2. Fast Bulk Sideloading via Field Laptops

### Option A: Linux / macOS / Raspberry Pi
Connect multiple Android phones to a powered USB-hub attached to your field laptop:
```bash
chmod +x install_all_connected_devices.sh
./install_all_connected_devices.sh
```

### Option B: Windows Field Laptops
Run `install_all_connected_devices.bat` from this directory.

### Option C: Instant Local Wi-Fi Web Server
Run the included Python hotspot server script on any laptop or portable Linux box:
```bash
python3 run_offline_wifi_hotspot_server.py
```
This serves `START_HERE.html` and the APK directly on port `8080` with zero internet dependency.

---

## 3. High-Risk Operational Directives

### A. Sudan & Conflict Blackouts (Anti-Interrogation)
* Ensure all field personnel and civilians are instructed on the **PANIC PURGE** function.
* The app features zero cloud accounts, zero phone number storage, and locks cryptographic secrets in native RAM (`mlock`).
* All transmissions are fixed at **4,096 bytes** uniform size to resist RF traffic classification by military electronic warfare.

### B. Myanmar (Airstrikes & Structural Collapse)
* In zones where RF transmission is actively monitored by drone SIGINT, utilize the **Optical LT Fountain** transceiver (animated QR droplets) for air-gapped data synchronization across line-of-sight.
* For search-and-rescue teams locating civilians trapped under collapsed structures, activate the **18.5 kHz ultrasonic acoustic modem** via the `TRAPPED / AID` button.

### C. Haiti (Gang Blockades & Municipal Blackouts)
* Set up solar-powered distribution hubs at water points and medical clinics.
* Use BLE mesh (10–30m hopping) to route supply requests across barricades without exposing physical locations.

### D. East Nusa Tenggara (NTT), Indonesia (Archipelago Earthquakes)
* Deploy USB-OTG drives via maritime sneakernets (ferries, fishing boats) to reach isolated islands.
* The app guarantees **Pool A (50 MB hard reservation)** so life-safety distress beacons are never dropped during network recovery.

### E. Freetown, Sierra Leone (Flash Flooding & Mudslides)
* Utilize the glove-friendly, high-contrast UI for rapid mass-casualty triage and status reporting in evacuation centers.
