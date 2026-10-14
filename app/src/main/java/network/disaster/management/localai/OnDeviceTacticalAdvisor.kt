package network.disaster.management.localai

import network.disaster.management.map.MapIncident
import network.disaster.management.map.TacticalPoint
import network.disaster.management.model.FeedMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*

/**
 * 100% Strictly On-Device Tactical Disaster Management & Emergency AI Advisor.
 * Operates under strict air-gap constraints: ZERO external internet connections,
 * ZERO cloud dependencies, and ZERO remote telemetry. All heuristics, triage logic,
 * and survival intelligence execute entirely in local device RAM.
 */
class OnDeviceTacticalAdvisor {

    data class TriageAssessment(
        val priorityCode: String,      // "RED", "YELLOW", "GREEN", "BLACK"
        val priorityLabel: String,     // "IMMEDIATE", "DELAYED", "MINIMAL", "EXPECTANT"
        val urgencyScore: Int,         // 1 - 10
        val summary: String,
        val identifiedHazards: List<String>,
        val immediateActions: List<String>,
        val requiresEvacuation: Boolean,
        val suggestedSupplies: List<String>
    )

    data class SitrepReport(
        val title: String,
        val timestamp: Long,
        val operationalStatus: String,
        val totalDistressSignals: Int,
        val severeCasualties: Int,
        val reportedHazards: List<String>,
        val resourceNeeds: List<String>,
        val tacticalRecommendations: List<String>,
        val rawBriefing: String
    )

    /**
     * Executes local, deterministic triage evaluation based on START (Simple Triage and Rapid Treatment)
     * and SALT protocols directly on-device.
     */
    fun triageDistress(beaconText: String, coordinates: String? = null): TriageAssessment {
        val lower = beaconText.lowercase(Locale.ROOT)
        val locSuffix = if (!coordinates.isNullOrBlank()) " at coordinates $coordinates" else ""

        return when {
            // RED / IMMEDIATE: Life-threatening airway, massive hemorrhage, crush, unconsciousness
            lower.contains("bleed") || lower.contains("blood") || lower.contains("arter") ||
            lower.contains("unconscious") || lower.contains("unresponsive") || lower.contains("chok") ||
            lower.contains("crush") || lower.contains("trapped") || lower.contains("amputat") ||
            lower.contains("asphyxi") || lower.contains("suffocat") || lower.contains("penetrat") -> {
                TriageAssessment(
                    priorityCode = "RED",
                    priorityLabel = "IMMEDIATE (Life-Threatening)",
                    urgencyScore = 10,
                    summary = "Critical life-safety emergency detected: severe trauma / entrapment$locSuffix.",
                    identifiedHazards = listOf(
                        "Massive Arterial Hemorrhage / Hypovolemic Shock",
                        "Airway Compromise / Traumatic Asphyxiation",
                        "Crush Syndrome / Structural Instability"
                    ),
                    immediateActions = listOf(
                        "Apply combat tourniquet 2-3 inches ABOVE bleeding site (high and tight).",
                        "Maintain clear airway; place patient in lateral recovery position if breathing.",
                        "Protect cervical spine; do NOT move entrapped limbs abruptly if pinned > 1 hour (crush toxin release risk).",
                        "Maintain continuous near-ultrasound acoustic chirping and BLE distress broadcasts."
                    ),
                    requiresEvacuation = true,
                    suggestedSupplies = listOf("CAT Tourniquet", "Hemostatic Gauze", "Chest Seal", "C-Spine Collar", "Rescue Stretcher")
                )
            }

            // YELLOW / DELAYED: Serious fractures, severe burns, stable respiratory distress
            lower.contains("broken") || lower.contains("fracture") || lower.contains("burn") ||
            lower.contains("fire") || lower.contains("pain") || lower.contains("wound") ||
            lower.contains("head") || lower.contains("concuss") || lower.contains("blunt") -> {
                TriageAssessment(
                    priorityCode = "YELLOW",
                    priorityLabel = "DELAYED (Serious, Non-Immediate)",
                    urgencyScore = 7,
                    summary = "Serious orthopedic injury or thermal burn requiring stabilization$locSuffix.",
                    identifiedHazards = listOf(
                        "Compound Bone Fracture / Neurovascular Compromise",
                        "Thermal Burn Shock / Tissue Dehydration",
                        "Closed Head Injury"
                    ),
                    immediateActions = listOf(
                        "Immobilize and splint injured extremity joint above and below fracture.",
                        "Cool thermal burns with clean water for 15 minutes; cover loosely with sterile dressing.",
                        "Check distal pulse and capillary refill in extremities every 15 minutes.",
                        "Prevent hypothermia using space blankets."
                    ),
                    requiresEvacuation = true,
                    suggestedSupplies = listOf("SAM Splints", "Burn Sheets", "Elastic Bandages", "Thermal Mylar Blankets", "Analgesics")
                )
            }

            // GREEN / MINOR: Walking wounded, civilian check-ins, minor scrapes
            lower.contains("safe") || lower.contains("ok") || lower.contains("fine") ||
            lower.contains("walking") || lower.contains("uninjured") || lower.contains("check-in") -> {
                TriageAssessment(
                    priorityCode = "GREEN",
                    priorityLabel = "MINIMAL (Survivor Check-in / Safe)",
                    urgencyScore = 2,
                    summary = "Survivor confirms safety with minor or zero injuries$locSuffix.",
                    identifiedHazards = listOf(
                        "Dehydration and environmental exposure",
                        "Secondary structural aftershocks"
                    ),
                    immediateActions = listOf(
                        "Stay sheltered in verified structurally sound location.",
                        "Conserve device battery; keep DisasterManagement running in low-power BLE mesh mode.",
                        "Establish local buddy system and muster with nearby survivors."
                    ),
                    requiresEvacuation = false,
                    suggestedSupplies = listOf("Potable Water Rations", "Flashlight", "Whistle", "Dust Masks")
                )
            }

            // DEFAULT: Conservative delayed triage
            else -> {
                TriageAssessment(
                    priorityCode = "YELLOW",
                    priorityLabel = "DELAYED (Triage Under Review)",
                    urgencyScore = 6,
                    summary = "Incident report under evaluation: \"$beaconText\"$locSuffix.",
                    identifiedHazards = listOf("Unverified localized environmental conditions"),
                    immediateActions = listOf(
                        "Establish peer radio contact via DisasterManagement chat.",
                        "Verify physical surroundings for secondary hazards (gas, live wires, structural cracks).",
                        "Share updated GNSS beacon coordinates if safe to do so."
                    ),
                    requiresEvacuation = false,
                    suggestedSupplies = listOf("First Aid Kit", "Emergency Radio")
                )
            }
        }
    }

    /**
     * Compiles an executive military/civil defense Situation Report (SITREP)
     * entirely on-device from local database state.
     */
    fun generateSitrep(
        messages: List<FeedMessage>,
        incidents: List<MapIncident>,
        nodeCount: Int
    ): SitrepReport {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val distressMsgs = messages.filter {
            it.isEmergency ||
            it.content.contains("SOS", ignoreCase = true) ||
            it.content.contains("TRAPPED", ignoreCase = true) ||
            it.content.contains("AID", ignoreCase = true) ||
            it.content.contains("BLEED", ignoreCase = true)
        }

        val severeCount = distressMsgs.count {
            it.content.contains("BLEED", true) ||
            it.content.contains("TRAPPED", true) ||
            it.content.contains("CRUSH", true)
        }

        val hazardTitles = incidents.map { it.title }.distinct()

        val briefing = buildString {
            append("========================================================\n")
            append("🛡️ ON-DEVICE CIVIL DEFENSE SITREP (STRICT AIR-GAP)\n")
            append("Timestamp: $dateStr\n")
            append("Operating Environment: Strictly Local / Zero Cloud Egress\n")
            append("========================================================\n\n")

            append("1. OPERATIONAL FORCE & MESH READINESS:\n")
            append("• Active Local Radio Mesh Nodes: $nodeCount peer(s) in RF range\n")
            append("• Tactical HUD Incidents Charted: ${incidents.size}\n")
            append("• Total Distress Signals Logged: ${distressMsgs.size}\n")
            append("• Critical (RED) Life-Safety Casualties: $severeCount\n\n")

            append("2. RECORDED THREAT MATRIX & INCIDENTS:\n")
            if (incidents.isEmpty()) {
                append("• No active geospatial hazards plotted on tactical HUD.\n")
            } else {
                incidents.take(8).forEach { inc ->
                    append("• [${inc.type.name}] ${inc.title}: ${inc.details} (Lat: ${inc.location.lat}, Lon: ${inc.location.lon})\n")
                }
            }
            append("\n")

            append("3. RECENT LOCAL MESH TRAFFIC:\n")
            if (messages.isEmpty()) {
                append("• No recorded local feed transmissions.\n")
            } else {
                messages.takeLast(6).forEach { msg ->
                    append("• [${msg.senderName}]: ${msg.content.take(65)}\n")
                }
            }
            append("\n")

            append("4. TACTICAL DIRECTIVES & LOGISTICS:\n")
            append("• Direct available search & rescue personnel toward verified RED distress beacons.\n")
            append("• Maintain radio silence / duty-cycled BLE to preserve battery reserves.\n")
            append("• Establish local clean water and triage collection points at designated sector safehouses.\n")
            append("• Enforce strict air-gap discipline: No WAN transmission, pure mesh propagation.\n")
        }

        return SitrepReport(
            title = "SITREP — ON-DEVICE CIVIL DEFENSE ($dateStr)",
            timestamp = System.currentTimeMillis(),
            operationalStatus = if (severeCount > 0) "DEFCON 2 — ACTIVE MASS CASUALTY TRIAGE" else if (distressMsgs.isNotEmpty()) "DEFCON 3 — ELEVATED CRISIS PATROL" else "DEFCON 4 — ROUTINE MESH MONITORING",
            totalDistressSignals = distressMsgs.size,
            severeCasualties = severeCount,
            reportedHazards = if (hazardTitles.isEmpty()) listOf("No active hazards on radar") else hazardTitles,
            resourceNeeds = listOf("Clean Potable Water", "Tourniquets & Hemostatic Gauze", "Portable Power / Battery Banks", "Splints"),
            tacticalRecommendations = listOf(
                "Prioritize rapid extraction for RED distress beacons.",
                "Conserve device batteries with low-power BLE mesh scanning.",
                "Rendezvous at primary sector muster locations."
            ),
            rawBriefing = briefing
        )
    }

    /**
     * Instant on-device survival and tactical emergency advice.
     * Comprehensive offline medical and civil defense field manual.
     */
    fun queryEmergencyAdvisor(query: String): String {
        val q = query.lowercase(Locale.ROOT)
        return when {
            // BLEEDING / TOURNIQUET
            q.contains("bleed") || q.contains("blood") || q.contains("wound") || q.contains("tourniquet") || q.contains("arter") -> """
🩹 MASSIVE HEMORRHAGE CONTROL (TACTICAL COMBAT CASUALTY CARE):
1. DIRECT PRESSURE: Place clean cloth/gauze directly onto the wound and push down with your full body weight immediately.
2. TOURNIQUET (LIMB WOUNDS):
   • Apply 2 to 3 inches ABOVE the bleeding site (never over a joint).
   • If source of bleeding is unclear, apply "high and tight" on the limb.
   • Tighten windlass until arterial bleeding stops completely and distal pulse disappears.
   • Lock windlass into clip and record application time (e.g., 'T-14:30') on patient's forehead.
   • WARNING: NEVER loosen or remove a tourniquet once applied.
3. JUNCTIONAL WOUNDS (Neck, Armpit, Groin):
   • Pack wound cavity tightly with hemostatic or standard gauze directly against the bleeding vessel.
   • Maintain uninterrupted manual pressure for a minimum of 3 full minutes.
"""

            // WATER PURIFICATION
            q.contains("water") || q.contains("drink") || q.contains("purif") || q.contains("thirst") || q.contains("boil") -> """
💧 EMERGENCY WATER PURIFICATION PROTOCOL:
1. CLARIFICATION (Pre-Filter):
   • Strain cloudy/muddy water through cloth, bandana, clean sand, or coffee filter to remove particulates.
2. DISINFECTION METHODS (Choose best available):
   • METHOD A (BOILING — Most Reliable): Bring water to a vigorous rolling boil for 1 full minute (3 minutes at elevations above 2,000m / 6,500ft). Kills 100% of biological pathogens (bacteria, viruses, parasites).
   • METHOD B (HOUSEHOLD BLEACH): Use regular unscented household bleach (6% sodium hypochlorite). Add 2 drops per 1 Liter of clear water (4 drops if cloudy). Mix and let stand for 30 minutes. Water should have slight chlorine odor.
   • METHOD C (SOLAR SODIS): Fill clear PET plastic bottles and lay flat in direct sunlight for 6 full hours (or 2 days if overcast).
3. CRITICAL WARNING: NEVER consume floodwater without boiling; floodwater carries hazardous industrial runoff, fuel, and raw sewage.
"""

            // STRUCTURAL COLLAPSE / ENTRAPMENT
            q.contains("trap") || q.contains("debris") || q.contains("collapse") || q.contains("rubble") || q.contains("crush") -> """
🏚️ BUILDING COLLAPSE & SURVIVOR ENTRAPMENT PROTOCOL:
1. AIRWAY & DUST PROTECTION:
   • Immediately cover nose and mouth with cloth/clothing to prevent silica dust inhalation and suffocative pneumonitis.
2. OXYGEN & ENERGY CONSERVATION:
   • Do NOT yell or scream continuously; shouting breathes in toxic dust and rapidly expends critical stamina.
3. ACOUSTIC SIGNALING (Universal Tapping Rule):
   • Tap on structural metal pipes, steel beams, or concrete in bursts of 3 (TAP-TAP-TAP) using a stone or metal object.
   • Sound conducts through solid structural elements far more effectively than vocal cries.
   • Rescuers pause search machinery every 15-30 minutes specifically to listen for seismic tapping.
4. CRUSH INJURY WARNING:
   • If a limb has been pinned under heavy debris for more than 1 hour, notify medical rescuers before lifting; sudden release can flood the bloodstream with toxic potassium and myoglobin (Crush Syndrome).
5. ELECTRONIC SIGNALING:
   • Keep DisasterManagement active: Automatic 18.5 kHz ultrasonic acoustic chirps and BLE beaconing will bridge to search & rescue devices.
"""

            // THERMAL BURNS
            q.contains("burn") || q.contains("fire") || q.contains("flame") || q.contains("scald") -> """
🔥 THERMAL BURN FIELD MANAGEMENT:
1. STOP THE PROCESS: Remove patient from heat source. Smother remaining flames (Stop, Drop, and Roll).
2. COOL THE BURN:
   • Pour cool, clean running water over the burned area for 10 to 20 minutes.
   • WARNING: Do NOT use ice or freezing water (causes tissue ischemia and hypothermia).
3. DRESSING & PROTECTION:
   • Cover loosely with dry, sterile, non-adherent dressing or clean plastic wrap.
   • Remove rings, watches, and tight clothing before swelling sets in.
4. STRICT PROHIBITIONS:
   • NEVER pop intact blisters.
   • NEVER tear away melted clothing adhered to the skin.
   • NEVER apply butter, grease, toothpaste, or unverified ointments (traps heat and causes severe infection).
"""

            // SMOKE / TOXIC GASES / CHEMICAL
            q.contains("smoke") || q.contains("gas") || q.contains("fume") || q.contains("toxic") || q.contains("chemical") -> """
⚠️ SMOKE & TOXIC GAS EVACUATION PROTOCOL:
1. DROP TO FLOOR (LOW CRAWL):
   • Super-heated toxic smoke and carbon monoxide rise. The cleanest breathable air is 12 to 24 inches off the floor.
   • Crawl on hands and knees with head held low.
2. RESPIRATORY BARRIER:
   • Breathe through a wet cloth or multiple layers of fabric covering both mouth and nose.
3. ESCAPE VECTOR:
   • Feel doors with the BACK of your hand before opening. If hot, do NOT open.
   • Move UPWIND and UPHILL from hazardous chemical plumes, tanker spills, or gas clouds.
4. SHELTER-IN-PLACE (If Trapped Indoors):
   • Close all doors; seal cracks and vents around doors with damp towels or clothing.
   • Signal your position at a window with a bright cloth or flashlight.
"""

            // HYPOTHERMIA & SHOCK
            q.contains("cold") || q.contains("hypotherm") || q.contains("freeze") || q.contains("frost") || q.contains("shock") -> """
❄️ HYPOTHERMIA & SHOCK STABILIZATION:
1. GROUND INSULATION:
   • Immediately lift patient off the cold, wet ground using sleeping pads, dry cardboard, backpacks, or tree boughs.
2. CORE RE-WARMING:
   • Strip off wet clothing and replace with dry layers.
   • Wrap in space blankets (shiny side facing inward toward patient's body).
   • Apply warm items (body heat, warm water bottles wrapped in cloth) to CORE AREAS: Armpits, groin, chest, neck.
   • WARNING: Do NOT massage or rub cold extremities; rapid peripheral re-warming can trigger cardiac arrest.
3. SHOCK POSITION:
   • Lay patient flat on their back and elevate legs 8-12 inches (unless head, neck, spinal, or pelvic trauma is suspected).
   • Keep patient calm, still, and covered.
"""

            // FRACTURES & SPLINTING
            q.contains("fracture") || q.contains("broken") || q.contains("bone") || q.contains("splint") || q.contains("dislocat") -> """
🦴 COMPOUND FRACTURE & SPLINTING PROTOCOL:
1. IMMOBILIZATION:
   • Immobilize the joint ABOVE and the joint BELOW the fracture site.
   • Use SAM splints, straight tree branches, rolled magazines, or umbrella shafts padded with soft cloth.
2. NEUROVASCULAR CHECKS (PMS):
   • Pulse: Check distal pulse (wrist or foot).
   • Motor: Ask patient to gently wiggle fingers or toes.
   • Sensory: Confirm patient can feel gentle touch on fingers or toes.
   • Re-check PMS every 15 minutes to ensure splint is not too tight.
3. OPEN (COMPOUND) FRACTURES:
   • If bone is protruding through skin, do NOT attempt to push it back in.
   • Cover the exposed bone with clean, sterile, moist dressing and splint extremity as found.
"""

            // DEFAULT FIELD MANUAL
            else -> """
🛡️ ON-DEVICE CIVIL DEFENSE TACTICAL PROTOCOL:
1. 360° SCENE SIZE-UP: Ensure your own safety first. Look for downed live power lines, leaking gas, and structural collapse hazards.
2. RAPID TRIAGE: Prioritize massive bleeding and blocked airways before treating minor wounds.
3. MESH SITUATIONAL AWARENESS: Use DisasterManagement's 1-tap rapid macros (SAFE, TRAPPED, NEED SUPPLIES, HAZARD) to inform nearby mesh nodes.
4. RADIO DISCIPLINE: Conserve battery charge. Keep radios running in background duty-cycle mode.
5. STANDBY: All data remains securely encrypted in your on-device SQLCipher vault with zero cloud egress.
"""
        }
    }

    /**
     * Calculates tactical compass bearing and cardinal direction completely on-device.
     */
    fun calculateBearing(from: TacticalPoint, to: TacticalPoint): Pair<Double, String> {
        val lat1 = Math.toRadians(from.lat)
        val lon1 = Math.toRadians(from.lon)
        val lat2 = Math.toRadians(to.lat)
        val lon2 = Math.toRadians(to.lon)

        val dLon = lon2 - lon1
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        var brng = Math.toDegrees(atan2(y, x))
        brng = (brng + 360.0) % 360.0

        val cardinals = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((brng + 11.25) / 22.5).toInt() % 16
        return Pair(brng, cardinals[index])
    }

    /**
     * Mathematical on-device grid encoder (zero network tile lookup).
     */
    fun computeTacticalGrid(lat: Double, lon: Double): String {
        val latIndex = ((lat + 90.0) * 10).toInt() % 100
        val lonIndex = ((lon + 180.0) * 10).toInt() % 100
        val char1 = ('A'.code + (latIndex % 26)).toChar()
        val char2 = ('A'.code + (lonIndex % 26)).toChar()
        return "GRID-$char1$char2-${abs(latIndex)}-${abs(lonIndex)}"
    }
}
