# 🌍 Crisis Population Operational Profiles & Field Delivery SOPs
**Detailed Guidance for Frontline Relief Workers, TSF Engineers & IRC Protection Teams**

---

## 1. 🇸🇩 Civilians in Sudan (Siege Warfare & Telecommunications Blackout)

### Threat Environment:
* Ongoing active civil war between SAF and RSF with intense urban siege warfare (El Fasher, Khartoum, Wad Madani).
* Intentional, weaponized state and regional telecommunications blackouts.
* Armed military/paramilitary checkpoints where mobile phones are inspected for signs of political alignment, intelligence reporting, or unauthorized communication.

### Tactical Delivery Strategy:
1. **MicroSD Cards in Medical Supplies:** Distribute pre-loaded MicroSD cards concealed within first aid kits, gauze wrappers, or hygiene parcels where phones themselves might not be openly distributed.
2. **Mandatory Checkpoint Drill:** Train civilians thoroughly on the `🚨 PANIC PURGE` button. At the first sign of an approaching checkpoint or forced interrogation, 1 tap purges all keys in native RAM (`mlock`), fills SQLite file headers with random bytes, and kills the application process without leaving unencrypted traces.
3. **4,096-Byte Traffic Invariant:** Explain to civilian coordinators that all transmissions across Bluetooth or local Wi-Fi are padded to exactly 4KB. Hostile radio spectrum analyzers scanning the 2.4 GHz band cannot distinguish an emergency medical distress beacon from routine network chatter.
4. **Primary Language:** Use `GUIDE_ARABIC_العربية.txt`.

---

## 2. 🇲🇲 Displaced Populations in Myanmar (Conflict & Earthquake Zones)

### Threat Environment:
* Escalating conflict and military junta airstrikes against civilian populations and displacement (IDP) camps.
* Infrastructure collapse compounded by the central Myanmar earthquake.
* Electronic warfare and UAV/drone surveillance monitoring RF emissions (Wi-Fi/Bluetooth) to target gatherings in jungle hideouts.

### Tactical Delivery Strategy:
1. **Cross-Border Satellite Ingress:** Relief teams along the Thai and Indian borders (Mae Sot, Mizoram) use portable battery-powered Starlink or BGAN terminals to download the master package and flash seed devices before crossing rivers into Karen, Kachin, or Shan states.
2. **Optical LT Fountain Transceiver:** When RF silence is strictly required to evade aerial drone sniffers, use the application's **Optical Fountain** mode. Phones transmit data visually across line-of-sight using animated QR droplets at 8 FPS, emitting zero radio frequency signals.
3. **Acoustic Rescue for Rubble:** For survivors trapped under earthquake-collapsed buildings where radio waves cannot penetrate thick brick and mortar, activate the 18.5 kHz ultrasonic acoustic modem via `🔴 TRAPPED / AID`.
4. **Primary Language:** Use `GUIDE_BURMESE_မြန်မာ.txt`.

---

## 3. 🇭🇹 Urban Residents in Haiti (Gang Blockades & Municipal Collapse)

### Threat Environment:
* Port-au-Prince gang-controlled blockades cutting off arterial roads (Martissant, Route Nationale).
* Displacement of over 1.4 million residents; complete collapse of municipal electricity, water, and emergency medical services.
* Threat of violent abduction or extortion at armed roadblocks.

### Tactical Delivery Strategy:
1. **Protected Solar Water Point Hubs:** Deploy battery/solar-backed offline Wi-Fi distribution hubs at managed community water points, clinics, and parish centers. Civilians queuing for water can connect and download the app directly to their phones.
2. **Decentralized Multi-Hop Mesh:** The dense layout of Port-au-Prince neighborhoods allows Bluetooth Low Energy store-and-forward hopping (10–30 meters per hop) to route distress messages and supply requests around barricades without line-of-sight.
3. **Complete Anonymity:** Emphasize that the app requires no phone numbers, no real names, and no accounts, ensuring that intercepted messages cannot be linked to individuals or family members.
4. **Primary Languages:** Use `GUIDE_HAITIAN_CREOLE_Kreyol.txt` and `GUIDE_FRENCH_Français.txt`.

---

## 4. 🇮🇩 Earthquake Survivors in East Nusa Tenggara (NTT), Indonesia

### Threat Environment:
* Magnitude 7.7 offshore earthquake causing severe structural devastation across islands (Flores, Alor, Timor, Sumba).
* Severed undersea fiber-optic communication cables; mountainous terrain and isolated coastal villages cut off by landslides.
* High vulnerability to aftershocks and localized tsunamis.

### Tactical Delivery Strategy:
1. **Maritime Sneakernet Distribution:** Inter-island ferries, fishing boats, and naval patrol craft transport USB-OTG thumb drives and pre-flashed tablets from regional logistics centers to isolated outer islands.
2. **Anti-Starvation Tri-Pool Storage:** The app’s architecture reserves **Pool A (50 MB hard reservation)** exclusively for life-safety distress beacons. Even when island networks are flooded with audio messages or photos, SOS alerts are never displaced.
3. **Offline AI Triage Protocols:** In remote villages where medical doctors are absent, health volunteers can use the built-in offline `OnDeviceTacticalAdvisor` to perform deterministic START/SALT mass-casualty triage and access offline trauma guides.
4. **Primary Language:** Use `GUIDE_INDONESIAN_Bahasa.txt`.

---

## 5. 🇸🇱 Flood Victims in Freetown, Sierra Leone (Utility Collapse)

### Threat Environment:
* Extreme flash flooding, hillside soil erosion, and mudslides in urban and informal hillside settlements.
* Catastrophic utility collapse, washed-out transformer substations, contaminated drinking wells, and severe risk of cholera outbreaks.
* Water-damaged touchscreens and zero electrical power for days.

### Tactical Delivery Strategy:
1. **Shelter Hotspot Sideloading:** Civil Defense units set up battery-powered Wi-Fi download hotspots at elevated community evacuation centers (schools, churches).
2. **Glove & Water-Splattered Touch UI:** Oversized high-contrast macro buttons allow wet, trembling, or muddy hands to trigger check-ins (🟢 I AM SAFE) or alert responders (⚠️ REPORT DANGER).
3. **Duty-Cycled Battery Preservation:** The background BLE mesh automatically duty-cycles scanning to preserve device battery, enabling smartphones to last days on minimal solar or powerbank charges.
4. **Primary Languages:** Use `QUICK_START_CIVILIAN_GUIDE.txt` and Krio section in `START_HERE.html`.
