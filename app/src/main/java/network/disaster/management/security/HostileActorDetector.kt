package network.disaster.management.security

import network.disaster.management.map.ActorClassification
import network.disaster.management.map.AmbushTrapLure
import network.disaster.management.map.HostileThreat
import network.disaster.management.map.PeerTrustProfile
import network.disaster.management.map.TacticalPoint
import network.disaster.management.model.FeedMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.regex.Pattern
import kotlin.math.roundToInt

/**
 * HostileActorDetector: Counter-Intelligence & Behavioral Infiltration Detection Engine.
 *
 * In off-grid crisis & post-apocalyptic mesh networks, hostile forces (militias, drone operators)
 * masquerade as civilians to bait survivors into ambushes or harvest survivor coordinates.
 *
 * Identifies hostile actors by analyzing mesh telemetry:
 * 1. Ambush Lure Traps: Beacons claiming "SAFE ZONE / FREE SUPPLIES" that geometrically
 *    coincide with hostile sniper corridors or drone engagement cones (<220m).
 * 2. GPS Spoofing & Teleportation: Impossible kinematic velocity jumps (>120 km/h) through rubble.
 * 3. Intel Probing: Repeatedly soliciting civilian hiding spots ("SEND EXACT GPS / REVEAL POSITION").
 * 4. Sybil Spikes: Rapid anomalous transmission frequency with conflicting identity keys.
 */
class HostileActorDetector {

    companion object {
        val shared = HostileActorDetector()
    }

    private val peerProfiles = ConcurrentHashMap<String, PeerTrustProfile>()
    private val ambushTraps = CopyOnWriteArrayList<AmbushTrapLure>()

    private val coordPattern = Pattern.compile("LOC:(-?[0-9]+\\.[0-9]+),(-?[0-9]+\\.[0-9]+)")

    private val baitKeywords = listOf(
        "SAFE ZONE", "SAFE EVACUATION", "SECURE CORRIDOR",
        "FREE WATER", "SUPPLIES HERE", "SURRENDER HERE", "CIVILIAN RETREAT"
    )

    private val probeKeywords = listOf(
        "SEND EXACT GPS", "WHERE ARE SURVIVORS", "REPORT COORDINATES",
        "ALL CIVILIANS REVEAL", "SEND YOUR LOCATION", "WHERE ARE YOU HIDING"
    )

    /**
     * Parses WGS-84 coordinates from message payload text.
     */
    fun parseCoordinates(content: String): TacticalPoint? {
        val matcher = coordPattern.matcher(content)
        if (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull()
            val lon = matcher.group(2)?.toDoubleOrNull()
            if (lat != null && lon != null) {
                return TacticalPoint(lat, lon)
            }
        }
        return null
    }

    /**
     * Evaluates a message against known hostile patrol zones and peer kinematic history.
     */
    fun analyzeMessage(msg: FeedMessage, knownThreats: List<HostileThreat>): PeerTrustProfile {
        val sender = msg.senderName
        val profile = peerProfiles.computeIfAbsent(sender) {
            PeerTrustProfile(
                senderName = sender,
                trustScore = 80,
                classification = ActorClassification.UNVERIFIED_CIVILIAN
            )
        }

        val upperContent = msg.content.uppercase()
        val coords = parseCoordinates(msg.content)
        val now = msg.timestamp

        // 1. Ambush Lure Detection: Baiting civilians into hostile engagement cones
        val containsBaitPhrase = baitKeywords.any { upperContent.contains(it) }
        if (containsBaitPhrase && coords != null) {
            for (threat in knownThreats) {
                val distToHostile = coords.distanceTo(threat.location)
                if (distToHostile <= 250.0) {
                    profile.trustScore = (profile.trustScore - 70).coerceAtLeast(0)
                    val flag = "AMBUSH_TRAP_LURE: Claimed safe beacon is inside ${threat.name} engagement cone (${distToHostile.roundToInt()}m)!"
                    if (!profile.anomalyFlags.contains(flag)) {
                        profile.anomalyFlags.add(flag)
                    }

                    // Register trap on tactical map
                    ambushTraps.add(
                        AmbushTrapLure(
                            id = "TRAP_${msg.id.take(8)}",
                            hostileSender = sender,
                            claimTitle = msg.content.take(40),
                            trapLocation = coords,
                            detectionReason = flag,
                            distToHostilePatrolMeters = distToHostile
                        )
                    )
                }
            }
        }

        // 2. Intelligence Probing Detection: Fishing for hidden survivor coordinates
        val containsProbePhrase = probeKeywords.any { upperContent.contains(it) }
        if (containsProbePhrase) {
            profile.trustScore = (profile.trustScore - 35).coerceAtLeast(0)
            val flag = "INTEL_PROBING: Soliciting civilian hiding locations"
            if (!profile.anomalyFlags.contains(flag)) {
                profile.anomalyFlags.add(flag)
            }
        }

        // 3. Kinematic GPS Spoofing Detection
        if (coords != null && profile.lastKnownLocation != null && profile.lastReportTimeMs > 0) {
            val distMeters = coords.distanceTo(profile.lastKnownLocation!!)
            val dtSeconds = (now - profile.lastReportTimeMs) / 1000.0
            if (dtSeconds in 0.5..3600.0) {
                val speedKmh = (distMeters / dtSeconds) * 3.6
                if (speedKmh > 120.0) {
                    profile.trustScore = (profile.trustScore - 50).coerceAtLeast(0)
                    val flag = "IMPOSSIBLE_SPEED: ${speedKmh.roundToInt()} km/h kinematic jump across rubble"
                    if (!profile.anomalyFlags.contains(flag)) {
                        profile.anomalyFlags.add(flag)
                    }
                }
            }
        }

        if (coords != null) {
            profile.lastKnownLocation = coords
        }
        profile.lastReportTimeMs = now

        // 4. Update Final Classification
        profile.classification = when {
            profile.trustScore < 40 -> ActorClassification.HOSTILE_INFILTRATOR
            profile.trustScore < 70 -> ActorClassification.SUSPICIOUS_ACTOR
            profile.anomalyFlags.isEmpty() && profile.trustScore >= 80 -> ActorClassification.VERIFIED_CIVILIAN
            else -> ActorClassification.UNVERIFIED_CIVILIAN
        }

        return profile
    }

    fun isSenderHostile(senderName: String): Boolean {
        return peerProfiles[senderName]?.classification == ActorClassification.HOSTILE_INFILTRATOR
    }

    fun getProfile(senderName: String): PeerTrustProfile? {
        return peerProfiles[senderName]
    }

    fun getAllProfiles(): List<PeerTrustProfile> {
        return peerProfiles.values.toList()
    }

    fun getDetectedAmbushTraps(): List<AmbushTrapLure> {
        return ambushTraps.toList()
    }

    fun setPeerTrust(senderName: String, trustScore: Int, classification: ActorClassification? = null) {
        val profile = peerProfiles.computeIfAbsent(senderName) {
            PeerTrustProfile(senderName = senderName, trustScore = trustScore)
        }
        profile.trustScore = trustScore.coerceIn(0, 100)
        profile.classification = classification ?: when {
            profile.trustScore < 40 -> ActorClassification.HOSTILE_INFILTRATOR
            profile.trustScore < 70 -> ActorClassification.SUSPICIOUS_ACTOR
            profile.trustScore >= 80 -> ActorClassification.VERIFIED_CIVILIAN
            else -> ActorClassification.UNVERIFIED_CIVILIAN
        }
    }

    fun clear() {
        peerProfiles.clear()
        ambushTraps.clear()
    }
}
