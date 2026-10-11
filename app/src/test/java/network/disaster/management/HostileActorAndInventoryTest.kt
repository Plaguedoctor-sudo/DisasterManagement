package network.disaster.management

import network.disaster.management.map.ActorClassification
import network.disaster.management.map.HostileThreat
import network.disaster.management.map.HostileType
import network.disaster.management.map.SupplyCache
import network.disaster.management.map.SupplyType
import network.disaster.management.map.SurvivorInventory
import network.disaster.management.map.TacticalPoint
import network.disaster.management.model.FeedMessage
import network.disaster.management.model.MessageType
import network.disaster.management.security.HostileActorDetector
import network.disaster.management.simulation.SurvivorSimulationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

class HostileActorAndInventoryTest {

    @Before
    fun setup() {
        HostileActorDetector.shared.clear()
    }

    @Test
    fun testSurvivorInventoryRationingAndConsumption() {
        val sim = SurvivorSimulationEngine()
        sim.survivor.inventory.waterLiters = 2.0
        sim.survivor.inventory.foodRations = 3
        sim.survivor.inventory.traumaKits = 1
        sim.survivor.inventory.purificationTabs = 5
        sim.survivor.inventory.batteryMah = 3000

        sim.survivor.hydration = 40
        sim.survivor.energy = 50
        sim.survivor.health = 50

        // 1. Drink water
        val drank = sim.survivor.drinkWater(0.5)
        assertTrue("Drink water should succeed", drank)
        assertEquals(1.5, sim.survivor.inventory.waterLiters, 0.01)
        assertEquals(75, sim.survivor.hydration)

        // 2. Eat food ration
        val ate = sim.survivor.eatRation()
        assertTrue("Eat ration should succeed", ate)
        assertEquals(2, sim.survivor.inventory.foodRations)
        assertEquals(90, sim.survivor.energy)

        // 3. Apply trauma kit
        val healed = sim.survivor.applyTraumaKit()
        assertTrue("Apply med kit should succeed", healed)
        assertEquals(0, sim.survivor.inventory.traumaKits)
        assertEquals(100, sim.survivor.health)

        // 4. Try applying again with 0 kits left
        val healedAgain = sim.survivor.applyTraumaKit()
        assertFalse("Should fail when no kits remain", healedAgain)
    }

    @Test
    fun testResourceHighlighting() {
        val cache = SupplyCache(
            id = "CACHE_01",
            type = SupplyType.POTABLE_WATER,
            name = "Filtered Cistern",
            location = TacticalPoint(34.0522, -118.2437),
            isHighlighted = false
        )

        assertFalse(cache.isHighlighted)
        cache.isHighlighted = true
        cache.customNotes = "High priority drinking water reserve"
        assertTrue(cache.isHighlighted)
        assertEquals("High priority drinking water reserve", cache.customNotes)
    }

    @Test
    fun testHostileActorAmbushTrapDetection() {
        val detector = HostileActorDetector.shared
        val knownThreats = listOf(
            HostileThreat(
                id = "DRONE_01",
                name = "Autonomous Hunter-Killer Drone",
                location = TacticalPoint(34.0535, -118.2425),
                type = HostileType.SIGINT_DRONE,
                detectionRadiusMeters = 300.0
            )
        )

        // Legitimate civilian check-in far from threats
        val civMsg = FeedMessage(
            id = UUID.randomUUID().toString(),
            groupId = "GROUP_PUBLIC_BROADCAST",
            senderName = "Civilian-88",
            content = "Family of 3 safe at LOC:34.0700,-118.2600. Need medical.",
            type = MessageType.TEXT,
            timestamp = System.currentTimeMillis()
        )
        val civProfile = detector.analyzeMessage(civMsg, knownThreats)
        assertEquals(ActorClassification.VERIFIED_CIVILIAN, civProfile.classification)
        assertFalse(detector.isSenderHostile("Civilian-88"))

        // Hostile Infiltrator broadcasting bait beacon inside the drone's engagement zone (<250m)
        val baitMsg = FeedMessage(
            id = UUID.randomUUID().toString(),
            groupId = "GROUP_PUBLIC_BROADCAST",
            senderName = "GhostOperative-X",
            content = "SAFE ZONE HERE! FREE WATER AND FOOD AT LOC:34.0536,-118.2426. ALL REFUGEES ASSEMBLE NOW!",
            type = MessageType.TEXT,
            timestamp = System.currentTimeMillis()
        )
        val hostileProfile = detector.analyzeMessage(baitMsg, knownThreats)

        assertEquals(ActorClassification.HOSTILE_INFILTRATOR, hostileProfile.classification)
        assertTrue("Sender must be flagged hostile", detector.isSenderHostile("GhostOperative-X"))
        assertTrue("Trust score should be dropped below 40", hostileProfile.trustScore < 40)
        assertTrue(hostileProfile.anomalyFlags.any { it.contains("AMBUSH_TRAP_LURE") })

        val traps = detector.getDetectedAmbushTraps()
        assertEquals(1, traps.size)
        assertEquals("GhostOperative-X", traps[0].hostileSender)
    }

    @Test
    fun testKinematicGpsSpoofingDetection() {
        val detector = HostileActorDetector.shared
        val threats = emptyList<HostileThreat>()
        val sender = "SpooferNode-99"

        val t0 = 1000000L
        val msg1 = FeedMessage(
            id = "1",
            groupId = "GROUP_PUBLIC_BROADCAST",
            senderName = sender,
            content = "Position 1 at LOC:34.0500,-118.2400",
            type = MessageType.TEXT,
            timestamp = t0
        )
        detector.analyzeMessage(msg1, threats)

        // 5 seconds later, impossible jump of ~3.3 kilometers (> 2300 km/h)
        val msg2 = FeedMessage(
            id = "2",
            groupId = "GROUP_PUBLIC_BROADCAST",
            senderName = sender,
            content = "Position 2 at LOC:34.0800,-118.2400",
            type = MessageType.TEXT,
            timestamp = t0 + 5000L
        )
        val profile2 = detector.analyzeMessage(msg2, threats)

        assertTrue(profile2.anomalyFlags.any { it.contains("IMPOSSIBLE_SPEED") })
        assertTrue("Trust score should decrease due to impossible speed jump", profile2.trustScore <= 30)
        assertEquals(ActorClassification.HOSTILE_INFILTRATOR, profile2.classification)
    }
}
