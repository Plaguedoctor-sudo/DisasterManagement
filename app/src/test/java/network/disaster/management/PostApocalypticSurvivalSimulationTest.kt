package network.disaster.management

import network.disaster.management.map.*
import network.disaster.management.simulation.SurvivorSimulationEngine
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.roundToInt

class PostApocalypticSurvivalSimulationTest {

    @Test
    fun testHaversineBearingAndDistanceAccuracy() {
        // Survivor at substation
        val survivor = TacticalPoint(20.39712, 76.33967)
        // Water cistern ~250m East
        val waterCistern = TacticalPoint(20.39800, 76.34180)

        val dist = survivor.distanceTo(waterCistern)
        assertTrue("Distance to cistern should be between 200m and 300m (was $dist)", dist in 200.0..300.0)

        val bearing = survivor.bearingTo(waterCistern)
        assertTrue("Bearing should point East/North-East (0..90 deg, was $bearing)", bearing in 30.0f..90.0f)
    }

    @Test
    fun testHostileSigintAvoidanceWithRadioSilence() {
        val engine = SurvivorSimulationEngine(TacticalPoint(20.39712, 76.33967))

        // Position hostile drone close enough that continuous 65m RF emission triggers interception
        val drone = engine.hostiles.first { it.type == HostileType.SIGINT_DRONE }
        drone.location = TacticalPoint(20.39800, 76.34000) // ~100m away, sensor radius 350m

        // Case A: Normal Active Mesh (RF Emission Radius = 65m)
        engine.survivor.isRadioSilence = false
        val activeAssessment = engine.evaluateTacticalSituation()
        assertTrue("Continuous RF broadcasting must intersect hostile sensor radius", activeAssessment.isRfIntersectingHostileSensors)
        assertEquals("Threat level must be CRITICAL_TARGETED when transmitting near hostile DF sensors",
            ThreatLevel.CRITICAL_TARGETED, activeAssessment.threatLevel)

        // Case B: Engaging Radio Silence (Stealth Mode)
        val isSilent = engine.toggleRadioSilence()
        assertTrue(isSilent)
        assertEquals(0.0, engine.survivor.getRfEmissionRadiusMeters(), 0.001)

        val stealthAssessment = engine.evaluateTacticalSituation()
        assertFalse("Radio Silence must drop RF signature to 0m, eliminating hostile DF lock",
            stealthAssessment.isRfIntersectingHostileSensors)
        assertEquals("Hostiles cannot triangulate when Radio Silence is active",
            ThreatLevel.STEALTH_SECURE, stealthAssessment.threatLevel)
    }

    @Test
    fun testMicroBurstSosTransmissionEvadesHostileInterception() {
        val engine = SurvivorSimulationEngine(TacticalPoint(20.39712, 76.33967))
        val drone = engine.hostiles.first { it.type == HostileType.SIGINT_DRONE }
        drone.location = TacticalPoint(20.40050, 76.34250) // ~500m NE

        val initialBattery = engine.survivor.battery

        // Trigger 100ms micro-burst
        val (evaded, detail) = engine.triggerMicroBurstSos()

        assertTrue("Micro-burst must evade hostile DF sensors at standard patrol distance", evaded)
        assertTrue("Status must confirm hostile DF sensors evaded", detail.contains("evaded", ignoreCase = true))
        assertEquals("Micro-burst must consume minimal battery", initialBattery - 2, engine.survivor.battery)
    }

    @Test
    fun testTrappedSurvivorPhysiologicalDecayAndWaterScavenging() {
        val engine = SurvivorSimulationEngine(TacticalPoint(20.39712, 76.33967))
        val initialHydration = engine.survivor.hydration

        // Step simulation 5 ticks (advances time and resource consumption)
        for (i in 1..5) {
            engine.stepSimulation()
        }

        assertTrue("Hydration must deplete over simulation ticks", engine.survivor.hydration < initialHydration)
        val depletedHydration = engine.survivor.hydration

        // Scavenge municipal water reserve
        val res = engine.scavengeNearestSupply(SupplyType.POTABLE_WATER)
        assertTrue(res.contains("Reached", ignoreCase = true))
        assertTrue("Scavenging water must restore hydration", engine.survivor.hydration > depletedHydration)
        assertTrue(engine.supplyCaches.first { it.type == SupplyType.POTABLE_WATER }.isCollected)
    }

    @Test
    fun testTrappedSurvivorExtractionPath() {
        val engine = SurvivorSimulationEngine(TacticalPoint(20.39712, 76.33967))
        assertFalse(engine.isExtracted)
        assertTrue(engine.survivor.isTrappedInRubble)

        // Advance to Safehouse
        val res = engine.scavengeNearestSupply(SupplyType.SHELTER_EXTRACTION)
        assertTrue(res.contains("Safehouse", ignoreCase = true) || res.contains("Reached", ignoreCase = true))
        assertTrue("Survivor must achieve extraction status upon reaching safehouse", engine.isExtracted)
        assertFalse(engine.survivor.isTrappedInRubble)
    }
}
