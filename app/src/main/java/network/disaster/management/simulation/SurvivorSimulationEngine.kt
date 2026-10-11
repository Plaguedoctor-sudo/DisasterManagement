package network.disaster.management.simulation

import network.disaster.management.map.*
import java.security.SecureRandom
import kotlin.math.*

/**
 * Realistic Post-Apocalyptic Trapped Survivor Simulation Engine.
 *
 * Implements tactical challenges faced by an off-grid civilian in a hostile environment:
 * 1. SIGINT / Direction-Finding Avoidance: Hostile drones sweep 2.4GHz for transmitters.
 *    Continuous broadcasting causes hostile triangulation; Radio Silence & 100ms Micro-bursts
 *    evade hostile sensors.
 * 2. Physiological Survival: Dehydration, starvation, and wound trauma decay over time.
 * 3. Scavenging & Evasion: Routing around active fire, radiation plumes, and hostile checkpoints
 *    to reach potable water and civilian extraction shelters.
 */
class SurvivorSimulationEngine(
    initialSurvivorLocation: TacticalPoint = TacticalPoint(20.39712, 76.33967)
) {
    private val random = SecureRandom()

    val survivor = SurvivorState(location = initialSurvivorLocation)

    // Realistic surrounding threats (airborne drones and ground sweep units)
    val hostiles = mutableListOf(
        HostileThreat(
            id = "H1",
            name = "Hostile SIGINT Drone (Raven-4)",
            location = TacticalPoint(20.40050, 76.34250), // ~500m NE
            type = HostileType.SIGINT_DRONE,
            detectionRadiusMeters = 350.0
        ),
        HostileThreat(
            id = "H2",
            name = "Militia Search Patrol #2",
            location = TacticalPoint(20.39300, 76.33600), // ~600m SW
            type = HostileType.ARMED_GROUND_PATROL,
            detectionRadiusMeters = 250.0
        )
    )

    // Environmental Hazards
    val hazards = mutableListOf(
        HazardZone(
            id = "Z1",
            name = "Active Structural Fire / Toxic Smoke",
            location = TacticalPoint(20.39850, 76.33700), // ~300m NW
            type = HazardType.ACTIVE_FIRE,
            radiusMeters = 120.0
        ),
        HazardZone(
            id = "Z2",
            name = "Chemical / Industrial Contamination",
            location = TacticalPoint(20.40200, 76.33600), // ~650m N
            type = HazardType.RADIATION_CONTAM,
            radiusMeters = 200.0
        ),
        HazardZone(
            id = "Z3",
            name = "Collapsed Highway Overpass Debris",
            location = TacticalPoint(20.39550, 76.34150), // ~280m SE
            type = HazardType.STRUCTURAL_COLLAPSE,
            radiusMeters = 80.0
        )
    )

    // Crucial Supply Caches & Safe Extraction
    val supplyCaches = mutableListOf(
        SupplyCache(
            id = "S1",
            name = "Municipal Water Reserve Cistern",
            location = TacticalPoint(20.39800, 76.34180), // ~250m E
            type = SupplyType.POTABLE_WATER
        ),
        SupplyCache(
            id = "S2",
            name = "Civil Defense Medical Kit #4",
            location = TacticalPoint(20.39980, 76.33900), // ~310m N
            type = SupplyType.TRAUMA_KIT
        ),
        SupplyCache(
            id = "S3",
            name = "Bunkered Civil Protection Safehouse",
            location = TacticalPoint(20.40120, 76.34380), // ~620m NE
            type = SupplyType.EXTRACTION_BUNKER
        )
    )

    val eventLog = mutableListOf<String>()
    var simulationTicks = 0
    var isExtracted = false

    init {
        logEvent("🟢 Incident Simulation Initialized: Civilian trapped in collapsed substation.")
        logEvent("⚠️ WARNING: Hostile SIGINT surveillance active in sector. Minimize RF emission footprints.")
    }

    /**
     * Toggles Radio Silence (Stealth Mode) to evade hostile RF direction finding.
     */
    fun toggleRadioSilence(): Boolean {
        survivor.isRadioSilence = !survivor.isRadioSilence
        if (survivor.isRadioSilence) {
            logEvent("🔇 STEALTH ACTIVATED: Radio Silence engaged. BLE/Wi-Fi beacons halted. RF signature = 0m.")
        } else {
            logEvent("📡 ACTIVE MESH ENGAGED: BLE beacons broadcasting. RF detection signature = ~65m.")
        }
        return survivor.isRadioSilence
    }

    /**
     * Sends an encrypted 100ms micro-burst distress beacon.
     * In contrast to continuous mesh broadcasting, micro-bursts make DF triangulation statistically improbable.
     */
    fun triggerMicroBurstSos(): Pair<Boolean, String> {
        survivor.battery = (survivor.battery - 2).coerceAtLeast(0)
        val nearestThreat = getNearestHostile()
        val dist = nearestThreat?.let { survivor.location.distanceTo(it.location) } ?: 9999.0

        // Micro-burst is only detectable if drone is right on top (< 40m during that exact 100ms window)
        val detected = dist < 40.0 && !survivor.isRadioSilence

        val msg = if (detected) {
            logEvent("🚨 CRITICAL ALERT: Hostile SIGINT Drone intercepted micro-burst! Triangulation started!")
            "INTERCEPTED: Hostile drone in close proximity (<40m) caught the burst!"
        } else {
            logEvent("✅ MICRO-BURST SUCCESS: 100ms encrypted 4KB droplet beamed to relays without DF detection.")
            "SUCCESS: Droplet delivered to local relays. Hostile DF sensors evaded."
        }
        return Pair(!detected, msg)
    }

    /**
     * Scavenges towards the nearest supply cache while attempting to route around hostile sensor cones.
     */
    fun scavengeNearestSupply(type: SupplyType): String {
        val target = supplyCaches.filter {
            (it.type == type ||
             (type in listOf(SupplyType.EXTRACTION_BUNKER, SupplyType.SHELTER_EXTRACTION) &&
              it.type in listOf(SupplyType.EXTRACTION_BUNKER, SupplyType.SHELTER_EXTRACTION))) &&
            !it.isCollected
        }.minByOrNull { survivor.location.distanceTo(it.location) }

        if (target == null) {
            return "No remaining caches of type $type in range."
        }

        val dist = survivor.location.distanceTo(target.location)
        if (dist > 800.0) {
            return "Target ${target.name} is too far ($dist m). Must advance in tactical bounds."
        }

        // Advance survivor towards cache
        survivor.location = target.location
        target.isCollected = true

        when (type) {
            SupplyType.POTABLE_WATER -> {
                survivor.hydration = (survivor.hydration + 40).coerceAtMost(100)
                logEvent("💧 REHYDRATION: Reached ${target.name}. Hydration restored to ${survivor.hydration}%.")
            }
            SupplyType.TRAUMA_KIT -> {
                survivor.health = (survivor.health + 35).coerceAtMost(100)
                logEvent("🩹 TRAUMA TREATED: Reached ${target.name}. Health stabilized at ${survivor.health}%.")
            }
            SupplyType.FOOD_RATIONS -> {
                survivor.energy = (survivor.energy + 30).coerceAtMost(100)
                logEvent("🥫 NOURISHMENT: Reached ${target.name}. Energy restored to ${survivor.energy}%.")
            }
            SupplyType.SHELTER_EXTRACTION,
            SupplyType.EXTRACTION_BUNKER -> {
                isExtracted = true
                survivor.isTrappedInRubble = false
                logEvent("🚩 EXTRACTION COMPLETE: Civilian reached Bunkered Civil Protection Safehouse!")
            }
        }
        return "Reached ${target.name}."
    }

    /**
     * Advance simulation tick (patrol movements, vital decay, hostile detection checks).
     */
    fun stepSimulation(): TacticalAssessment {
        simulationTicks++

        // 1. Natural vital decay in high-stress post-apocalyptic environment
        survivor.hydration = (survivor.hydration - 2).coerceAtLeast(0)
        survivor.energy = (survivor.energy - 1).coerceAtLeast(0)
        if (survivor.hydration < 20) {
            survivor.health = (survivor.health - 5).coerceAtLeast(0)
            logEvent("⚠️ DEHYDRATION CRITICAL: Health dropping rapidly! Locate potable water immediately.")
        }

        // 2. Hostile Drone patrol movement (orbiting search pattern)
        for (h in hostiles) {
            if (h.type == HostileType.SIGINT_DRONE) {
                // Drone sweeps in dynamic patrol vector
                val angle = Math.toRadians((simulationTicks * 25.0) % 360.0)
                val baseLat = 20.39950
                val baseLon = 76.34100
                h.location = TacticalPoint(
                    baseLat + (sin(angle) * 0.0018),
                    baseLon + (cos(angle) * 0.0022)
                )
            }
        }

        // 3. Evaluate threat posture
        val assessment = evaluateTacticalSituation()

        if (assessment.isRfIntersectingHostileSensors) {
            logEvent("🚨 HOSTILE LOCK: Continuous RF beacon detected by ${assessment.nearestHostileName}! Enable Radio Silence immediately!")
        }

        return assessment
    }

    /**
     * Evaluates current threat posture and SIGINT exposure.
     */
    fun evaluateTacticalSituation(): TacticalAssessment {
        val nearestThreat = getNearestHostile()
        if (nearestThreat == null) {
            return TacticalAssessment(
                ThreatLevel.STEALTH_SECURE,
                9999.0,
                "None",
                false,
                "Sector clear of immediate hostile direction-finding assets."
            )
        }

        val dist = survivor.location.distanceTo(nearestThreat.location)
        val survivorRfRadius = survivor.getRfEmissionRadiusMeters()

        // Hostile detection occurs if hostile sensor range covers the survivor's RF emission radius
        val isIntersecting = !survivor.isRadioSilence && (dist <= nearestThreat.detectionRadiusMeters + survivorRfRadius)

        val threatLevel = when {
            isIntersecting -> ThreatLevel.CRITICAL_TARGETED
            survivor.isRadioSilence -> ThreatLevel.STEALTH_SECURE
            dist < (nearestThreat.detectionRadiusMeters + 150.0) -> ThreatLevel.ELEVATED_RISK
            else -> ThreatLevel.STEALTH_SECURE
        }

        val advisory = when (threatLevel) {
            ThreatLevel.CRITICAL_TARGETED ->
                "CRITICAL: Hostile ${nearestThreat.name} has SIGINT lock on your continuous RF transmissions! Engage Radio Silence!"
            ThreatLevel.ELEVATED_RISK ->
                "CAUTION: Hostile ${nearestThreat.name} is ${dist.roundToInt()}m away. Avoid unencrypted RF broadcasts."
            ThreatLevel.STEALTH_SECURE ->
                if (survivor.isRadioSilence) "STEALTH OPTIMAL: Radio Silence active (0m footprint). Hostile sensors blind."
                else "SECURE: Hostile assets out of range (${dist.roundToInt()}m)."
        }

        return TacticalAssessment(
            threatLevel,
            dist,
            nearestThreat.name,
            isIntersecting,
            advisory
        )
    }

    fun getNearestHostile(): HostileThreat? {
        return hostiles.minByOrNull { survivor.location.distanceTo(it.location) }
    }

    fun logEvent(msg: String) {
        eventLog.add(0, msg)
        if (eventLog.size > 50) eventLog.removeAt(eventLog.size - 1)
    }
}
