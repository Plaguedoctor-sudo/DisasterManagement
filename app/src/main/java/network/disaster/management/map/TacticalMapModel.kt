package network.disaster.management.map

import kotlin.math.*

/**
 * Tactical Geographical Point using WGS-84 coordinates.
 */
data class TacticalPoint(
    val lat: Double,
    val lon: Double
) {
    /**
     * Haversine distance in meters to another point.
     */
    fun distanceTo(other: TacticalPoint): Double {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(other.lat - this.lat)
        val dLon = Math.toRadians(other.lon - this.lon)
        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(this.lat)) * cos(Math.toRadians(other.lat)) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * Compass bearing in degrees (0..360) from this point to another point.
     */
    fun bearingTo(other: TacticalPoint): Float {
        val lat1 = Math.toRadians(this.lat)
        val lat2 = Math.toRadians(other.lat)
        val dLon = Math.toRadians(other.lon - this.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        val brng = Math.toDegrees(atan2(y, x))
        return ((brng + 360) % 360).toFloat()
    }
}

/**
 * Tactical Disaster Management Incident Categories.
 */
enum class DisasterIncidentType {
    DISTRESS_SOS,    // 🔴 Distress beacon / trapped civilian / medical aid
    HAZARD_ALERT,    // ⚠️ Hazard: road blocked, live wire, fire, flood, collapse, gas leak
    SUPPLY_REQUEST,  // 🟡 Resource need: water, medical supplies, food, shelter, power
    CIVILIAN_SAFE    // 🟢 Civilian check-in / safe zone coordinates
}

/**
 * Real-time Disaster Map Incident record parsed from off-grid mesh telemetry.
 */
data class MapIncident(
    val id: String,
    val type: DisasterIncidentType,
    val title: String,
    val senderName: String,
    val location: TacticalPoint,
    val timestamp: Long,
    val details: String = ""
)

/**
 * User Inventory Subsystem.
 * Tracks water, rations, medical trauma kits, power, and purification tablets.
 */
data class SurvivorInventory(
    var waterLiters: Double = 3.0,          // Liters of clean water
    var foodRations: Int = 4,              // Number of food meals / rations
    var traumaKits: Int = 2,               // Field medical trauma kits
    var purificationTabs: Int = 10,        // Water purification tablets
    var batteryMah: Int = 10000,           // Power bank battery mAh
    var defenseTools: String = "Multi-tool & Tactical Flashlight"
) {
    fun drink(amountLiters: Double = 0.5): Boolean {
        if (waterLiters >= amountLiters) {
            waterLiters = (waterLiters - amountLiters).coerceAtLeast(0.0)
            return true
        }
        return false
    }

    fun eat(): Boolean {
        if (foodRations > 0) {
            foodRations--
            return true
        }
        return false
    }

    fun applyMedical(): Boolean {
        if (traumaKits > 0) {
            traumaKits--
            return true
        }
        return false
    }
}

/**
 * Trapped Survivor State in post-apocalyptic environment.
 */
data class SurvivorState(
    var location: TacticalPoint,
    var health: Int = 100,         // 0 - 100%
    var hydration: Int = 85,       // 0 - 100% (Dehydration kills in ~3 days)
    var energy: Int = 90,          // 0 - 100%
    var battery: Int = 75,         // 0 - 100%
    var isRadioSilence: Boolean = false,
    var isTrappedInRubble: Boolean = true,
    var statusMessage: String = "Trapped in partially collapsed substation",
    val inventory: SurvivorInventory = SurvivorInventory()
) {
    /**
     * Estimated RF Emission radius in meters based on stealth posture.
     * Radio Silence = 0m (pure passive listening).
     * Micro-Burst = 8m (ultra-low power burst).
     * Normal Mesh = 65m (BLE beacon advertising).
     */
    fun getRfEmissionRadiusMeters(): Double {
        return if (isRadioSilence) 0.0 else 65.0
    }

    fun drinkWater(amountLiters: Double = 0.5): Boolean {
        if (inventory.drink(amountLiters)) {
            hydration = (hydration + 35).coerceAtMost(100)
            return true
        }
        return false
    }

    fun eatRation(): Boolean {
        if (inventory.eat()) {
            energy = (energy + 40).coerceAtMost(100)
            return true
        }
        return false
    }

    fun applyTraumaKit(): Boolean {
        if (inventory.applyMedical()) {
            health = (health + 50).coerceAtMost(100)
            return true
        }
        return false
    }
}

enum class HostileType {
    SIGINT_DRONE,          // Airborne UAV scanning 2.4GHz RF spectrum for civilian emitters
    ARMED_GROUND_PATROL,   // Armed militia sweep unit with directional antenna
    SNIPER_OVERWATCH       // Fixed hostile checkpoint
}

/**
 * Hostile Threat Vector.
 */
data class HostileThreat(
    val id: String,
    val name: String,
    var location: TacticalPoint,
    val type: HostileType,
    val detectionRadiusMeters: Double, // Distance at which hostile sensors detect non-stealth RF
    val isAlerted: Boolean = false
)

enum class HazardType {
    ACTIVE_FIRE,           // Thermal / smoke blockage
    RADIATION_CONTAM,      // Fallout or chemical plume
    STRUCTURAL_COLLAPSE    // Impassable rubble debris
}

/**
 * Environmental Hazard Zone.
 */
data class HazardZone(
    val id: String,
    val name: String,
    val location: TacticalPoint,
    val type: HazardType,
    val radiusMeters: Double
)

enum class SupplyType {
    POTABLE_WATER,
    TRAUMA_KIT,
    FOOD_RATIONS,
    EXTRACTION_BUNKER,
    SHELTER_EXTRACTION
}

/**
 * Scavengeable Supply Point or Extraction Node.
 * Supports user highlighting for high-priority resource planning.
 */
data class SupplyCache(
    val id: String,
    val name: String,
    val location: TacticalPoint,
    val type: SupplyType,
    var isCollected: Boolean = false,
    var isHighlighted: Boolean = false,    // User-highlighted critical resource
    var highlightPriority: Int = 1,        // 1 = High, 2 = Critical
    var customNotes: String = ""           // Custom tactical notes from survivor
)

enum class ThreatLevel {
    STEALTH_SECURE,     // Hostiles far away or Radio Silence active (0% detection risk)
    ELEVATED_RISK,      // Hostile sensors in range, but no direct line-of-sight yet
    CRITICAL_TARGETED   // Hostile SIGINT lock! Active mesh signal being triangulated
}

/**
 * Real-time tactical threat evaluation.
 */
data class TacticalAssessment(
    val threatLevel: ThreatLevel,
    val nearestHostileDistanceMeters: Double,
    val nearestHostileName: String,
    val isRfIntersectingHostileSensors: Boolean,
    val advisoryText: String
)

/**
 * Malicious Ambush Trap Lure identified by data analysis.
 */
data class AmbushTrapLure(
    val id: String,
    val hostileSender: String,
    val claimTitle: String,
    val trapLocation: TacticalPoint,
    val detectionReason: String,
    val distToHostilePatrolMeters: Double
)

enum class ActorClassification {
    VERIFIED_CIVILIAN,     // Validated cryptographic identity, realistic movement
    UNVERIFIED_CIVILIAN,   // Unknown peer, normal behavior
    SUSPICIOUS_ACTOR,      // Minor anomaly or suspicious keyword pattern
    HOSTILE_INFILTRATOR    // Ambush trap beacon, GPS teleportation, or hostile patrol coordination
}

/**
 * Peer Reputation & Counter-Intelligence Profile.
 */
data class PeerTrustProfile(
    val senderName: String,
    var trustScore: Int = 80, // 0 - 100
    var classification: ActorClassification = ActorClassification.UNVERIFIED_CIVILIAN,
    var lastKnownLocation: TacticalPoint? = null,
    var lastReportTimeMs: Long = 0L,
    val anomalyFlags: MutableList<String> = mutableListOf()
)
